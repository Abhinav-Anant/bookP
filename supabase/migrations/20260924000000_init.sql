-- APA cloud library: schema, row-level security, storage buckets.
create extension if not exists pgcrypto with schema extensions;

-- Profiles ------------------------------------------------------------------
create table public.profiles (
  id uuid primary key references auth.users on delete cascade,
  display_name text,
  avatar_url text,
  role text not null default 'reader' check (role in ('reader', 'author', 'admin')),
  ui_language text,
  created_at timestamptz not null default now()
);

create function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  insert into public.profiles (id, display_name) values (new.id, split_part(new.email, '@', 1));
  return new;
end $$;

create trigger on_auth_user_created after insert on auth.users
  for each row execute function public.handle_new_user();

create function public.is_admin() returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from public.profiles where id = auth.uid() and role = 'admin')
$$;

-- Catalog -------------------------------------------------------------------
-- array_to_string is only STABLE, which a generated column refuses.
create function public.genres_text(genres text[]) returns text
language sql immutable as $$ select array_to_string(genres, ' ') $$;

create table public.authors (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  bio text,
  photo_url text
);

create table public.books (
  id uuid primary key default gen_random_uuid(),
  title text not null,
  subtitle text,
  author_id uuid references public.authors,
  publisher text,
  description text,
  genres text[] not null default '{}',
  language text not null default 'en',
  pages int,
  published_on date,
  isbn text,
  cover_path text,
  price_inr numeric(10, 2) not null default 0,
  is_free boolean not null default true,
  free_chapters int not null default 0,
  status text not null default 'draft' check (status in ('draft', 'pending', 'approved', 'rejected')),
  featured boolean not null default false,
  editors_pick boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  search tsvector generated always as (
    to_tsvector('simple',
      coalesce(title, '') || ' ' || coalesce(subtitle, '') || ' ' ||
      coalesce(publisher, '') || ' ' || coalesce(isbn, '') || ' ' ||
      public.genres_text(genres))
  ) stored
);
create index books_search_idx on public.books using gin (search);
create index books_author_idx on public.books (author_id);

create table public.book_files (
  id uuid primary key default gen_random_uuid(),
  book_id uuid not null references public.books on delete cascade,
  format text not null check (format in ('epub', 'pdf', 'cbz')),
  storage_path text not null,
  size_bytes bigint,
  sha256 text,
  unique (book_id, format)
);

-- Per-user state ------------------------------------------------------------
create table public.entitlements (
  user_id uuid not null references auth.users on delete cascade,
  book_id uuid not null references public.books on delete cascade,
  source text not null check (source in ('free', 'purchase', 'subscription', 'grant')),
  granted_at timestamptz not null default now(),
  primary key (user_id, book_id)
);

create table public.library_items (
  user_id uuid not null references auth.users on delete cascade,
  book_id uuid not null references public.books on delete cascade,
  favorite boolean not null default false,
  wishlist boolean not null default false,
  added_at timestamptz not null default now(),
  primary key (user_id, book_id)
);

create table public.reading_state (
  user_id uuid not null references auth.users on delete cascade,
  book_id uuid not null references public.books on delete cascade,
  locator jsonb,
  completed boolean not null default false,
  device_id text,
  device_name text,
  updated_at timestamptz not null default now(),
  primary key (user_id, book_id)
);
create index reading_state_recent_idx on public.reading_state (updated_at);

create table public.devices (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users on delete cascade,
  name text,
  key_hash text not null unique,
  created_at timestamptz not null default now(),
  last_seen timestamptz,
  revoked boolean not null default false
);

-- The user a device key belongs to, or null if unknown or revoked.
create function public.device_user(key text) returns uuid
language sql security definer set search_path = public, extensions as $$
  update public.devices set last_seen = now()
  where key_hash = encode(extensions.digest(key, 'sha256'), 'hex') and not revoked
  returning user_id
$$;
revoke execute on function public.device_user(text) from public, anon, authenticated;

-- Most-read approved books over the last `days` days.
create function public.trending_books(days int default 7, lim int default 20)
returns table (book_id uuid, readers bigint)
language sql stable security definer set search_path = public as $$
  select rs.book_id, count(*) as readers
  from public.reading_state rs join public.books b on b.id = rs.book_id
  where b.status = 'approved' and rs.updated_at > now() - make_interval(days => days)
  group by rs.book_id order by readers desc limit lim
$$;

-- Row-level security --------------------------------------------------------
alter table public.profiles enable row level security;
alter table public.authors enable row level security;
alter table public.books enable row level security;
alter table public.book_files enable row level security;
alter table public.entitlements enable row level security;
alter table public.library_items enable row level security;
alter table public.reading_state enable row level security;
alter table public.devices enable row level security;

create policy own_profile on public.profiles for all
  using (id = auth.uid() or public.is_admin())
  with check ((id = auth.uid() and role = 'reader') or public.is_admin());
create policy read_authors on public.authors for select using (true);
create policy admin_authors on public.authors for all using (public.is_admin()) with check (public.is_admin());
create policy read_books on public.books for select using (status = 'approved' or public.is_admin());
create policy admin_books on public.books for all using (public.is_admin()) with check (public.is_admin());
create policy admin_files on public.book_files for all using (public.is_admin()) with check (public.is_admin());
create policy own_entitlements on public.entitlements for select using (user_id = auth.uid() or public.is_admin());
create policy admin_entitlements on public.entitlements for all using (public.is_admin()) with check (public.is_admin());
create policy own_library on public.library_items for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy own_state on public.reading_state for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy own_devices on public.devices for select using (user_id = auth.uid() or public.is_admin());
create policy revoke_own_device on public.devices for update
  using (user_id = auth.uid() or public.is_admin()) with check (user_id = auth.uid() or public.is_admin());

-- Storage: covers are public, book files are only reachable via signed URLs.
insert into storage.buckets (id, name, public)
values ('covers', 'covers', true), ('books', 'books', false)
on conflict (id) do nothing;

create policy admin_upload_books on storage.objects for all
  using (bucket_id in ('books', 'covers') and public.is_admin())
  with check (bucket_id in ('books', 'covers') and public.is_admin());
