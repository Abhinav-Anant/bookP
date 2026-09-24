-- Run with `supabase test db`.
begin;
create extension if not exists pgtap with schema extensions;
select plan(5);

insert into auth.users (id, email) values
  ('11111111-1111-1111-1111-111111111111', 'reader1@example.in'),
  ('22222222-2222-2222-2222-222222222222', 'reader2@example.in');

insert into public.books (id, title, status) values
  ('33333333-3333-3333-3333-333333333331', 'Published', 'approved'),
  ('33333333-3333-3333-3333-333333333332', 'Draft', 'draft');

insert into public.reading_state (user_id, book_id, locator) values
  ('22222222-2222-2222-2222-222222222222', '33333333-3333-3333-3333-333333333331', '{}');

insert into public.devices (user_id, name, key_hash, revoked) values
  ('11111111-1111-1111-1111-111111111111', 'live', encode(extensions.digest('live-key', 'sha256'), 'hex'), false),
  ('11111111-1111-1111-1111-111111111111', 'gone', encode(extensions.digest('gone-key', 'sha256'), 'hex'), true);

select is(public.device_user('live-key'), '11111111-1111-1111-1111-111111111111'::uuid, 'live key resolves');
select is(public.device_user('gone-key'), null, 'revoked key is refused');

set local role authenticated;
set local request.jwt.claims = '{"sub":"11111111-1111-1111-1111-111111111111","role":"authenticated"}';

select is((select count(*) from public.books)::int, 1, 'drafts are hidden from readers');
select is((select count(*) from public.reading_state)::int, 0, 'other readers'' positions are hidden');
select throws_ok(
  $$ update public.profiles set role = 'admin' where id = auth.uid() $$,
  '42501', null, 'readers cannot promote themselves');

select * from finish();
rollback;
