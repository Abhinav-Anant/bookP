-- Publisher/admin portal: author ownership, review workflow, blocking, stats.
-- Every rule lives here; the portal only hides buttons.

alter table public.profiles add column blocked boolean not null default false;
alter table public.authors add column profile_id uuid unique references public.profiles on delete set null;
alter table public.books
  add column owner_id uuid references public.profiles on delete set null,
  add column rejection_reason text,
  add column submitted_at timestamptz;
create index books_owner_idx on public.books (owner_id);

create table public.author_applications (
  user_id uuid primary key references public.profiles on delete cascade,
  display_name text not null,
  bio text,
  status text not null default 'pending' check (status in ('pending', 'approved', 'rejected')),
  rejection_reason text,
  created_at timestamptz not null default now()
);
alter table public.author_applications enable row level security;
create policy read_application on public.author_applications for select
  using (user_id = auth.uid() or public.is_admin());
create policy apply on public.author_applications for insert
  with check (user_id = auth.uid() and status = 'pending');
create policy reapply on public.author_applications for update
  using (user_id = auth.uid() and status = 'rejected')
  with check (user_id = auth.uid() and status = 'pending');
create policy admin_applications on public.author_applications for all
  using (public.is_admin()) with check (public.is_admin());

create function public.is_author() returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from public.profiles
                 where id = auth.uid() and role in ('author', 'admin') and not blocked)
$$;

-- Guards only apply to API callers; the SQL editor, service_role and the
-- security-definer functions below run as other roles and pass through.
create function public.from_client() returns boolean
language sql stable as $$ select current_user in ('authenticated', 'anon') $$;

-- Profiles: anyone may edit their own row, but not their role or blocked flag.
drop policy own_profile on public.profiles;
create policy own_profile on public.profiles for all
  using (id = auth.uid() or public.is_admin())
  with check (id = auth.uid() or public.is_admin());

create function public.profiles_guard() returns trigger
language plpgsql set search_path = public as $$
begin
  if public.from_client() and not public.is_admin() and (
       (tg_op = 'INSERT' and (new.role <> 'reader' or new.blocked)) or
       (tg_op = 'UPDATE' and (new.role is distinct from old.role or new.blocked is distinct from old.blocked))) then
    raise exception 'only an admin can change role or blocked' using errcode = '42501';
  end if;
  return new;
end $$;
create trigger profiles_guard before insert or update on public.profiles
  for each row execute function public.profiles_guard();

-- Books: authors edit their own drafts and rejected books, and may only
-- move them to draft or pending. Admin-only fields keep their old values.
create function public.books_guard() returns trigger
language plpgsql set search_path = public as $$
begin
  new.updated_at := now();
  if new.status = 'pending' and (tg_op = 'INSERT' or old.status <> 'pending') then
    new.submitted_at := now();
  end if;
  if not public.from_client() or public.is_admin() then
    return new;
  end if;
  if tg_op = 'UPDATE' and old.status not in ('draft', 'rejected') then
    raise exception 'a % book can no longer be edited', old.status using errcode = '42501';
  end if;
  if new.status not in ('draft', 'pending') then
    raise exception 'only an admin can set status %', new.status using errcode = '42501';
  end if;
  if tg_op = 'INSERT' then
    new.owner_id := auth.uid();
    new.featured := false;
    new.editors_pick := false;
    new.rejection_reason := null;
  else
    new.owner_id := old.owner_id;
    new.featured := old.featured;
    new.editors_pick := old.editors_pick;
    new.rejection_reason := old.rejection_reason;
    new.created_at := old.created_at;
  end if;
  new.author_id := (select id from public.authors where profile_id = new.owner_id);
  return new;
end $$;
create trigger books_guard before insert or update on public.books
  for each row execute function public.books_guard();

create policy author_read_books on public.books for select using (owner_id = auth.uid());
create policy author_insert_books on public.books for insert
  with check (public.is_author() and owner_id = auth.uid());
create policy author_update_books on public.books for update
  using (owner_id = auth.uid() and public.is_author()) with check (owner_id = auth.uid());
create policy author_delete_books on public.books for delete
  using (owner_id = auth.uid() and status = 'draft');

create function public.owns_editable_book(book uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from public.books
                 where id = book and owner_id = auth.uid() and status in ('draft', 'rejected'))
$$;
create policy author_files on public.book_files for all
  using (public.owns_editable_book(book_id)) with check (public.owns_editable_book(book_id));
create policy author_read_files on public.book_files for select
  using (exists (select 1 from public.books b where b.id = book_id and b.owner_id = auth.uid()));

-- Storage: an author writes only under a folder named after their id.
create policy author_storage on storage.objects for all
  using (bucket_id in ('books', 'covers') and public.is_author()
         and (storage.foldername(name))[1] = auth.uid()::text)
  with check (bucket_id in ('books', 'covers') and public.is_author()
              and (storage.foldername(name))[1] = auth.uid()::text);

-- A blocked user's device keys stop working at the one gate every API call passes.
create or replace function public.device_user(key text) returns uuid
language sql security definer set search_path = public, extensions as $$
  update public.devices d set last_seen = now()
  from public.profiles p
  where d.key_hash = encode(extensions.digest(key, 'sha256'), 'hex')
    and not d.revoked and p.id = d.user_id and not p.blocked
  returning d.user_id
$$;

-- Admin functions -----------------------------------------------------------
create function public.require_admin() returns void
language plpgsql stable set search_path = public as $$
begin
  if not public.is_admin() then
    raise exception 'admins only' using errcode = '42501';
  end if;
end $$;

create function public.review_author_application(applicant uuid, approve boolean, reason text default null)
returns void language plpgsql security definer set search_path = public as $$
declare app public.author_applications;
begin
  perform public.require_admin();
  update public.author_applications
     set status = case when approve then 'approved' else 'rejected' end,
         rejection_reason = case when approve then null else reason end
   where user_id = applicant
  returning * into app;
  if not found then
    raise exception 'no application from %', applicant;
  end if;
  if approve then
    update public.profiles set role = 'author' where id = applicant and role = 'reader';
    insert into public.authors (name, bio, profile_id) values (app.display_name, app.bio, applicant)
    on conflict (profile_id) do update set name = excluded.name, bio = excluded.bio;
  end if;
end $$;

create function public.admin_users(q text default '')
returns table (id uuid, email text, display_name text, role text, blocked boolean,
               created_at timestamptz, devices bigint)
language plpgsql stable security definer set search_path = public as $$
#variable_conflict use_column
begin
  perform public.require_admin();
  return query
    select p.id, u.email::text, p.display_name, p.role, p.blocked, p.created_at,
           (select count(*) from public.devices d where d.user_id = p.id and not d.revoked)
      from public.profiles p join auth.users u on u.id = p.id
     where q = '' or u.email ilike '%' || q || '%' or p.display_name ilike '%' || q || '%'
     order by p.created_at desc
     limit 100;
end $$;

create function public.set_blocked(target uuid, value boolean)
returns void language plpgsql security definer set search_path = public as $$
begin
  perform public.require_admin();
  if target = auth.uid() then
    raise exception 'you cannot block yourself';
  end if;
  update public.profiles set blocked = value where id = target;
  if value then
    update public.devices set revoked = true where user_id = target;
  end if;
end $$;

-- Stats ---------------------------------------------------------------------
-- Readers and finishes for the caller's books (every book, for an admin).
create function public.my_book_stats()
returns table (book_id uuid, readers bigint, downloads bigint, finished bigint)
language sql stable security definer set search_path = public as $$
  select b.id,
         (select count(*) from public.reading_state r where r.book_id = b.id),
         (select count(*) from public.entitlements e where e.book_id = b.id),
         (select count(*) from public.reading_state r where r.book_id = b.id and r.completed)
    from public.books b
   where b.owner_id = auth.uid() or public.is_admin()
$$;

-- ponytail: reading_state keeps only the latest write per user and book, so
-- "daily active" counts last activity, not every day read; a sessions table
-- fixes that when reading-time sync lands.
create function public.admin_stats(days int default 30)
returns jsonb language plpgsql stable security definer set search_path = public as $$
declare since timestamptz := now() - make_interval(days => days);
begin
  perform public.require_admin();
  return jsonb_build_object(
    'users', (select count(*) from public.profiles),
    'new_users', (select count(*) from public.profiles where created_at > since),
    'active_users', (select count(distinct user_id) from public.reading_state where updated_at > since),
    'books', (select coalesce(jsonb_object_agg(status, n), '{}')
                from (select status, count(*) n from public.books group by status) s),
    'top_books', (select coalesce(jsonb_agg(t), '[]') from (
        select b.title, count(*) readers
          from public.reading_state r join public.books b on b.id = r.book_id
         where r.updated_at > since
         group by b.id, b.title order by readers desc limit 10) t),
    'top_authors', (select coalesce(jsonb_agg(t), '[]') from (
        select a.name, count(*) readers
          from public.reading_state r
          join public.books b on b.id = r.book_id
          join public.authors a on a.id = b.author_id
         where r.updated_at > since
         group by a.id, a.name order by readers desc limit 10) t),
    'daily', (select coalesce(jsonb_agg(t order by t.day), '[]') from (
        select updated_at::date as day, count(distinct user_id) readers
          from public.reading_state where updated_at > since group by 1) t)
  );
end $$;
