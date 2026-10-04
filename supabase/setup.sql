-- Pasar Purnama: accounts + per-user library.
-- Run once in the Supabase dashboard: SQL Editor -> New query -> paste this file -> Run.
-- Safe to run again.

-- One row per book in a user's library: reading progress + reader state (bookmarks, theme, ...).
create table if not exists public.books (
  user_id      uuid    not null default auth.uid() references auth.users (id) on delete cascade,
  book_id      text    not null,
  title        text,
  subtitle     text,
  has_file     boolean not null default false,  -- imported book file stored in the "novels" bucket
  progress     integer not null default -1,     -- last chapter index, -1 = not opened yet
  reader_state text,                            -- JSON from reader.html (bookmarks, theme, position)
  updated_at   bigint  not null default 0,      -- client time in ms, last write wins
  file_version bigint  not null default 0,      -- bumped when the book file is re-uploaded
  primary key (user_id, book_id)
);

alter table public.books enable row level security;

grant select, insert, update, delete on public.books to authenticated;

-- Every user sees and changes only their own rows.
drop policy if exists "books_select_own" on public.books;
drop policy if exists "books_insert_own" on public.books;
drop policy if exists "books_update_own" on public.books;
drop policy if exists "books_delete_own" on public.books;

create policy "books_select_own" on public.books
  for select to authenticated using ((select auth.uid()) = user_id);
create policy "books_insert_own" on public.books
  for insert to authenticated with check ((select auth.uid()) = user_id);
create policy "books_update_own" on public.books
  for update to authenticated using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "books_delete_own" on public.books
  for delete to authenticated using ((select auth.uid()) = user_id);

-- Private bucket for imported book files, stored as <user id>/<book id>.json (max 50 MB each).
insert into storage.buckets (id, name, public, file_size_limit)
values ('novels', 'novels', false, 52428800)
on conflict (id) do nothing;

drop policy if exists "novels_select_own" on storage.objects;
drop policy if exists "novels_insert_own" on storage.objects;
drop policy if exists "novels_update_own" on storage.objects;
drop policy if exists "novels_delete_own" on storage.objects;

create policy "novels_select_own" on storage.objects
  for select to authenticated
  using (bucket_id = 'novels' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "novels_insert_own" on storage.objects
  for insert to authenticated
  with check (bucket_id = 'novels' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "novels_update_own" on storage.objects
  for update to authenticated
  using (bucket_id = 'novels' and (storage.foldername(name))[1] = (select auth.uid())::text)
  with check (bucket_id = 'novels' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "novels_delete_own" on storage.objects
  for delete to authenticated
  using (bucket_id = 'novels' and (storage.foldername(name))[1] = (select auth.uid())::text);
