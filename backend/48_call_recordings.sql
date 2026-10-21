-- =====================================================================
--  MIGRATION 48 — call recordings (agent's microphone), ADMIN ONLY
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- The Android app records the agent's side of each call it places
-- (CallRecordingService) and uploads it to the PRIVATE storage bucket
-- 'agent-recordings' as "<agent id>/<recording id>.m4a". This table holds the
-- call's details.
--
-- WHO CAN DO WHAT
--   agent       : upload into their OWN folder only, and report their own
--                 recordings. Cannot list, read, download or delete anything —
--                 not even their own — through the server.
--   manager /
--   supervisor  : nothing.
--   admin       : list, play and delete everything. "Admin" is exactly
--                 public.is_admin() (a supervisor-role profile with the
--                 is_admin flag, active) — every read/delete path below checks it.
--
-- The table has RLS on and NO policies, so it is unreachable except through
-- the functions here; the bucket is private (no public URLs), and the only
-- storage read/delete policies require is_admin().

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('agent-recordings', 'agent-recordings', false, 52428800,
        array['audio/mp4', 'audio/x-m4a', 'audio/m4a', 'audio/aac'])
on conflict (id) do update
   set public = false,
       file_size_limit = excluded.file_size_limit,
       allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists "agents upload own recordings" on storage.objects;
create policy "agents upload own recordings" on storage.objects
  for insert to authenticated
  with check (bucket_id = 'agent-recordings'
              and (storage.foldername(name))[1] = auth.uid()::text);

drop policy if exists "admin reads recordings" on storage.objects;
create policy "admin reads recordings" on storage.objects
  for select to authenticated
  using (bucket_id = 'agent-recordings' and public.is_admin());

drop policy if exists "admin deletes recordings" on storage.objects;
create policy "admin deletes recordings" on storage.objects
  for delete to authenticated
  using (bucket_id = 'agent-recordings' and public.is_admin());

create table if not exists public.call_recordings (
  id           uuid        primary key,
  agent_id     uuid        not null references public.profiles(id),
  lead_id      bigint,
  mobile       text,
  started_at   timestamptz not null,
  duration_ms  int         not null check (duration_ms >= 0),
  size_bytes   bigint      not null default 0,
  has_audio    boolean     not null,   -- false: the phone's mic was silent; no file kept
  storage_path text,
  uploaded_at  timestamptz not null default now()
);

create index if not exists call_recordings_started_idx on public.call_recordings (started_at desc);
create index if not exists call_recordings_agent_idx on public.call_recordings (agent_id, started_at desc);

alter table public.call_recordings enable row level security;
revoke all on public.call_recordings from anon, authenticated;
-- No policies: every read/write goes through the functions below.

-- ---------------------------------------------------------------------
-- Agent side: report a finished call (after its audio, if any, is uploaded).
-- Idempotent. An agent can only ever register recordings as themselves.
-- ---------------------------------------------------------------------
create or replace function public.register_call_recording(
  p_id uuid, p_lead_id bigint, p_mobile text, p_started_at timestamptz,
  p_duration_ms int, p_size_bytes bigint, p_has_audio boolean
) returns void
language plpgsql
security definer set search_path = public
as $$
declare
  path text := case when p_has_audio then auth.uid()::text || '/' || p_id::text || '.m4a' end;
begin
  if not exists (select 1 from public.profiles where id = auth.uid() and active) then
    raise exception 'not permitted';
  end if;
  if p_has_audio and not exists (select 1 from storage.objects where bucket_id = 'agent-recordings' and name = path) then
    raise exception 'audio file not found - upload it first';
  end if;
  insert into public.call_recordings (id, agent_id, lead_id, mobile, started_at, duration_ms, size_bytes, has_audio, storage_path)
  values (p_id, auth.uid(), p_lead_id, left(p_mobile, 20), coalesce(p_started_at, now()),
          greatest(coalesce(p_duration_ms, 0), 0), greatest(coalesce(p_size_bytes, 0), 0), p_has_audio, path)
  on conflict (id) do nothing;
end;
$$;

revoke execute on function public.register_call_recording(uuid, bigint, text, timestamptz, int, bigint, boolean) from public, anon;
grant execute on function public.register_call_recording(uuid, bigint, text, timestamptz, int, bigint, boolean) to authenticated;

-- ---------------------------------------------------------------------
-- Admin side — each function refuses anyone who is not public.is_admin().
-- ---------------------------------------------------------------------
create or replace function public.admin_list_call_recordings(
  p_agent uuid default null,
  p_before timestamptz default null,        -- only calls started before this
  p_include_silent boolean default false,   -- also show calls where no audio was captured
  p_oldest_first boolean default false,
  p_cursor timestamptz default null,        -- paging: continue after this started_at
  p_limit int default 50
) returns table (
  id uuid, agent_id uuid, agent_name text, login_id text, lead_id bigint, mobile text,
  started_at timestamptz, duration_ms int, size_bytes bigint, has_audio boolean, storage_path text
)
language plpgsql
stable
security definer set search_path = public
as $$
begin
  if not public.is_admin() then raise exception 'not permitted: admin only'; end if;
  return query
    select r.id, r.agent_id, p.full_name, p.login_id, r.lead_id, r.mobile,
           r.started_at, r.duration_ms, r.size_bytes, r.has_audio, r.storage_path
      from public.call_recordings r
      join public.profiles p on p.id = r.agent_id
     where (p_agent is null or r.agent_id = p_agent)
       and (p_before is null or r.started_at < p_before)
       and (p_include_silent or r.has_audio)
       and (p_cursor is null
            or (p_oldest_first and r.started_at > p_cursor)
            or (not p_oldest_first and r.started_at < p_cursor))
     order by case when p_oldest_first then r.started_at end asc,
              case when not p_oldest_first then r.started_at end desc
     limit least(greatest(coalesce(p_limit, 50), 1), 500);
end;
$$;

revoke execute on function public.admin_list_call_recordings(uuid, timestamptz, boolean, boolean, timestamptz, int) from public, anon;
grant execute on function public.admin_list_call_recordings(uuid, timestamptz, boolean, boolean, timestamptz, int) to authenticated;

-- Storage used vs the free plan's 1 GB, for the dashboard's "nearly full" warning.
create or replace function public.admin_call_recording_stats()
returns jsonb
language plpgsql
stable
security definer set search_path = public
as $$
declare
  limit_bytes constant bigint := 1073741824;   -- 1 GB
  used bigint;
begin
  if not public.is_admin() then raise exception 'not permitted: admin only'; end if;
  select coalesce(sum(size_bytes), 0) into used from public.call_recordings;
  return jsonb_build_object(
    'calls',       (select count(*) from public.call_recordings),
    'with_audio',  (select count(*) from public.call_recordings where has_audio),
    'no_audio',    (select count(*) from public.call_recordings where not has_audio),
    'minutes',     (select round(coalesce(sum(duration_ms), 0) / 60000.0, 1) from public.call_recordings where has_audio),
    'bytes_used',  used,
    'limit_bytes', limit_bytes,
    'percent',     round(used * 100.0 / limit_bytes, 1)
  );
end;
$$;

revoke execute on function public.admin_call_recording_stats() from public, anon;
grant execute on function public.admin_call_recording_stats() to authenticated;

-- Deletes the rows; the dashboard removes the audio files from Storage first
-- (allowed by the admin-only delete policy above).
create or replace function public.admin_delete_call_recordings(p_ids uuid[])
returns int
language plpgsql
security definer set search_path = public
as $$
declare
  n int;
begin
  if not public.is_admin() then raise exception 'not permitted: admin only'; end if;
  delete from public.call_recordings where id = any(p_ids);
  get diagnostics n = row_count;
  return n;
end;
$$;

revoke execute on function public.admin_delete_call_recordings(uuid[]) from public, anon;
grant execute on function public.admin_delete_call_recordings(uuid[]) to authenticated;
