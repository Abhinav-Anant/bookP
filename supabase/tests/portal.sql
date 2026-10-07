-- Publisher/admin portal rules. Run with `supabase test db`, or against a
-- linked project with `supabase db query --linked -f` (it rolls back).
begin;
create extension if not exists pgtap with schema extensions;
select plan(17);

insert into auth.users (id, email) values
  ('a0000000-0000-0000-0000-000000000001', 'admin@example.in'),
  ('a0000000-0000-0000-0000-00000000000a', 'author-a@example.in'),
  ('a0000000-0000-0000-0000-00000000000b', 'author-b@example.in'),
  ('a0000000-0000-0000-0000-00000000000c', 'reader@example.in');
update public.profiles set role = 'admin' where id = 'a0000000-0000-0000-0000-000000000001';
update public.profiles set role = 'author'
  where id in ('a0000000-0000-0000-0000-00000000000a', 'a0000000-0000-0000-0000-00000000000b');

insert into public.books (id, title, status, owner_id) values
  ('b0000000-0000-0000-0000-0000000000a1', 'A draft', 'draft', 'a0000000-0000-0000-0000-00000000000a'),
  ('b0000000-0000-0000-0000-0000000000a2', 'A live', 'approved', 'a0000000-0000-0000-0000-00000000000a'),
  ('b0000000-0000-0000-0000-0000000000b1', 'B draft', 'draft', 'a0000000-0000-0000-0000-00000000000b');

insert into public.devices (user_id, name, key_hash) values
  ('a0000000-0000-0000-0000-00000000000c', 'phone', encode(extensions.digest('reader-key', 'sha256'), 'hex'));
insert into public.author_applications (user_id, display_name, bio) values
  ('a0000000-0000-0000-0000-00000000000c', 'Reader Writes', 'Poems');

-- Author A ------------------------------------------------------------------
set local role authenticated;
set local request.jwt.claims = '{"sub":"a0000000-0000-0000-0000-00000000000a","role":"authenticated"}';

select is((select count(*) from public.books where status <> 'approved')::int, 1,
  'an author sees their own drafts but not another author''s');
update public.books set title = 'stolen' where id = 'b0000000-0000-0000-0000-0000000000b1';
select throws_ok(
  $$ update public.books set status = 'approved' where id = 'b0000000-0000-0000-0000-0000000000a1' $$,
  '42501', null, 'an author cannot approve their own book');
update public.books set featured = true, editors_pick = true where id = 'b0000000-0000-0000-0000-0000000000a1';
select is((select featured or editors_pick from public.books where id = 'b0000000-0000-0000-0000-0000000000a1'),
  false, 'an author cannot feature their own book');
select throws_ok(
  $$ update public.books set title = 'changed' where id = 'b0000000-0000-0000-0000-0000000000a2' $$,
  '42501', null, 'an approved book is no longer editable by its author');
update public.books set status = 'pending' where id = 'b0000000-0000-0000-0000-0000000000a1';
select isnt((select submitted_at from public.books where id = 'b0000000-0000-0000-0000-0000000000a1'),
  null, 'submitting stamps submitted_at');
insert into public.books (title, owner_id) values ('New one', 'a0000000-0000-0000-0000-00000000000b');

-- Reader --------------------------------------------------------------------
set local request.jwt.claims = '{"sub":"a0000000-0000-0000-0000-00000000000c","role":"authenticated"}';

select throws_ok($$ insert into public.books (title) values ('Nope') $$,
  '42501', null, 'a reader cannot add books');
select throws_ok($$ update public.profiles set blocked = true where id = auth.uid() $$,
  '42501', null, 'a reader cannot change their blocked flag');
select lives_ok($$ update public.profiles set display_name = 'Me' where id = auth.uid() $$,
  'a reader can rename themselves');
select throws_ok($$ select * from public.admin_users('') $$, '42501', null, 'admin_users is admin only');
select throws_ok($$ select public.set_blocked(auth.uid(), false) $$, '42501', null, 'set_blocked is admin only');
select throws_ok(
  $$ select public.review_author_application('a0000000-0000-0000-0000-00000000000c', true) $$,
  '42501', null, 'reviewing applications is admin only');

-- Admin ---------------------------------------------------------------------
set local request.jwt.claims = '{"sub":"a0000000-0000-0000-0000-000000000001","role":"authenticated"}';

select public.review_author_application('a0000000-0000-0000-0000-00000000000c', true);
select is((select role from public.profiles where id = 'a0000000-0000-0000-0000-00000000000c'),
  'author', 'approving an application makes an author');
select is((select count(*) from public.authors where profile_id = 'a0000000-0000-0000-0000-00000000000c')::int,
  1, 'approving an application creates the public author page');

select public.set_blocked('a0000000-0000-0000-0000-00000000000c', true);
select is((select bool_and(revoked) from public.devices where user_id = 'a0000000-0000-0000-0000-00000000000c'),
  true, 'blocking revokes every device');
reset role;
update public.devices set revoked = false where user_id = 'a0000000-0000-0000-0000-00000000000c';
select is(public.device_user('reader-key'), null, 'a blocked user''s key is refused');
select is((select title from public.books where id = 'b0000000-0000-0000-0000-0000000000b1'),
  'B draft', 'an author cannot edit another author''s book');
select is((select owner_id from public.books where title = 'New one'),
  'a0000000-0000-0000-0000-00000000000a'::uuid, 'a new book is owned by whoever creates it');

select * from finish();
rollback;
