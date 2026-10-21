-- =====================================================================
--  MIGRATION 49 — training material for the Android "T" page
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- The admin uploads audio recordings and PDFs (calling script, card features /
-- benefits) from the dashboard; every signed-in agent sees them on the app's
-- T page, plays the audio and reads the PDFs in the app.
--
--   admin  : upload / rename / reorder / hide / delete (public.is_admin() only)
--   agent  : read only
--
-- Files live in the private bucket 'training-material' (flat: "<id>.<ext>").
-- The list is served by list_training_items(); the table itself is closed.

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('training-material', 'training-material', false, 52428800,
        array['audio/mpeg', 'audio/mp3', 'audio/mp4', 'audio/x-m4a', 'audio/m4a', 'audio/aac',
              'audio/wav', 'audio/x-wav', 'audio/ogg', 'application/pdf'])
on conflict (id) do update
   set public = false,
       file_size_limit = excluded.file_size_limit,
       allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists "training readable by signed-in users" on storage.objects;
create policy "training readable by signed-in users" on storage.objects
  for select to authenticated
  using (bucket_id = 'training-material');

drop policy if exists "training admin insert" on storage.objects;
create policy "training admin insert" on storage.objects
  for insert to authenticated
  with check (bucket_id = 'training-material' and public.is_admin());

drop policy if exists "training admin update" on storage.objects;
create policy "training admin update" on storage.objects
  for update to authenticated
  using (bucket_id = 'training-material' and public.is_admin())
  with check (bucket_id = 'training-material' and public.is_admin());

drop policy if exists "training admin delete" on storage.objects;
create policy "training admin delete" on storage.objects
  for delete to authenticated
  using (bucket_id = 'training-material' and public.is_admin());

create table if not exists public.training_items (
  id           uuid        primary key,
  kind         text        not null check (kind in ('audio', 'pdf')),
  title        text        not null check (char_length(title) between 1 and 120),
  storage_path text        not null,
  size_bytes   bigint      not null default 0,
  sort_order   int         not null default 0,
  active       boolean     not null default true,
  created_at   timestamptz not null default now()
);

alter table public.training_items enable row level security;
revoke all on public.training_items from anon, authenticated;
-- No policies: every read/write goes through the functions below.

-- Agents (and anyone signed in with an active profile): the visible items.
create or replace function public.list_training_items()
returns table (id uuid, kind text, title text, storage_path text, size_bytes bigint, sort_order int)
language plpgsql
stable
security definer set search_path = public
as $$
begin
  if not exists (select 1 from public.profiles p where p.id = auth.uid() and p.active) then
    raise exception 'not permitted';
  end if;
  return query
    select t.id, t.kind, t.title, t.storage_path, t.size_bytes, t.sort_order
      from public.training_items t
     where t.active
     order by t.sort_order, t.created_at;
end;
$$;

revoke execute on function public.list_training_items() from public, anon;
grant execute on function public.list_training_items() to authenticated;

-- Admin side
create or replace function public.admin_list_training_items()
returns table (id uuid, kind text, title text, storage_path text, size_bytes bigint,
               sort_order int, active boolean, created_at timestamptz)
language plpgsql
stable
security definer set search_path = public
as $$
begin
  if not public.is_admin() then raise exception 'not permitted: admin only'; end if;
  return query
    select t.id, t.kind, t.title, t.storage_path, t.size_bytes, t.sort_order, t.active, t.created_at
      from public.training_items t
     order by t.sort_order, t.created_at;
end;
$$;

revoke execute on function public.admin_list_training_items() from public, anon;
grant execute on function public.admin_list_training_items() to authenticated;

-- Called after the dashboard has uploaded the file to Storage.
create or replace function public.admin_add_training_item(
  p_id uuid, p_kind text, p_title text, p_path text, p_size bigint, p_sort int default 0
) returns void
language plpgsql
security definer set search_path = public
as $$
begin
  if not public.is_admin() then raise exception 'not permitted: admin only'; end if;
  if p_title is null or btrim(p_title) = '' then raise exception 'title cannot be empty'; end if;
  insert into public.training_items (id, kind, title, storage_path, size_bytes, sort_order)
  values (p_id, p_kind, left(btrim(p_title), 120), p_path, greatest(coalesce(p_size, 0), 0), coalesce(p_sort, 0));
end;
$$;

revoke execute on function public.admin_add_training_item(uuid, text, text, text, bigint, int) from public, anon;
grant execute on function public.admin_add_training_item(uuid, text, text, text, bigint, int) to authenticated;

create or replace function public.admin_update_training_item(
  p_id uuid, p_title text, p_sort int, p_active boolean
) returns void
language plpgsql
security definer set search_path = public
as $$
begin
  if not public.is_admin() then raise exception 'not permitted: admin only'; end if;
  if p_title is null or btrim(p_title) = '' then raise exception 'title cannot be empty'; end if;
  update public.training_items t
     set title = left(btrim(p_title), 120), sort_order = coalesce(p_sort, t.sort_order), active = coalesce(p_active, t.active)
   where t.id = p_id;
end;
$$;

revoke execute on function public.admin_update_training_item(uuid, text, int, boolean) from public, anon;
grant execute on function public.admin_update_training_item(uuid, text, int, boolean) to authenticated;

-- Removes the row and returns its file path; the dashboard then deletes the file.
create or replace function public.admin_delete_training_item(p_id uuid)
returns text
language plpgsql
security definer set search_path = public
as $$
declare
  path text;
begin
  if not public.is_admin() then raise exception 'not permitted: admin only'; end if;
  delete from public.training_items t where t.id = p_id returning t.storage_path into path;
  return path;
end;
$$;

revoke execute on function public.admin_delete_training_item(uuid) from public, anon;
grant execute on function public.admin_delete_training_item(uuid) to authenticated;
