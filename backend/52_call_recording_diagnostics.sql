-- =====================================================================
--  MIGRATION 52 — call recordings: store the mic level and source, and
--  show the agent's phone in the admin list
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- 1.18.0 threw away a recording whenever the phone's loudness reading was low,
-- and Realme / Lava phones were reporting 100% silence (39 of 71 calls blank).
-- That reading is unreliable, so 1.18.1 keeps and uploads every recording and
-- sends the measured peak level and which microphone source was used. The
-- admin list shows both, plus the agent's phone model, so we can see which
-- phones and sources actually capture the agent's voice.
--
-- register_call_recording gains two OPTIONAL parameters, so 1.18.0 phones
-- (which don't send them) keep working.

alter table public.call_recordings add column if not exists peak int;
alter table public.call_recordings add column if not exists audio_source text;

drop function if exists public.register_call_recording(uuid, bigint, text, timestamptz, int, bigint, boolean);

create or replace function public.register_call_recording(
  p_id uuid, p_lead_id bigint, p_mobile text, p_started_at timestamptz,
  p_duration_ms int, p_size_bytes bigint, p_has_audio boolean,
  p_peak int default null, p_source text default null
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
  insert into public.call_recordings (id, agent_id, lead_id, mobile, started_at, duration_ms, size_bytes, has_audio, storage_path, peak, audio_source)
  values (p_id, auth.uid(), p_lead_id, left(p_mobile, 20), coalesce(p_started_at, now()),
          greatest(coalesce(p_duration_ms, 0), 0), greatest(coalesce(p_size_bytes, 0), 0), p_has_audio, path,
          p_peak, left(p_source, 40))
  on conflict (id) do nothing;
end;
$$;

revoke execute on function public.register_call_recording(uuid, bigint, text, timestamptz, int, bigint, boolean, int, text) from public, anon;
grant execute on function public.register_call_recording(uuid, bigint, text, timestamptz, int, bigint, boolean, int, text) to authenticated;

-- The admin list returns more columns, so the old definition has to go first.
drop function if exists public.admin_list_call_recordings(uuid, timestamptz, boolean, boolean, timestamptz, int);

create or replace function public.admin_list_call_recordings(
  p_agent uuid default null,
  p_before timestamptz default null,
  p_include_silent boolean default false,
  p_oldest_first boolean default false,
  p_cursor timestamptz default null,
  p_limit int default 50
) returns table (
  id uuid, agent_id uuid, agent_name text, login_id text, device_model text, lead_id bigint, mobile text,
  started_at timestamptz, duration_ms int, size_bytes bigint, has_audio boolean, storage_path text,
  peak int, audio_source text
)
language plpgsql
stable
security definer set search_path = public
as $$
begin
  if not public.is_admin() then raise exception 'not permitted: admin only'; end if;
  return query
    select r.id, r.agent_id, p.full_name, p.login_id, p.device_model, r.lead_id, r.mobile,
           r.started_at, r.duration_ms, r.size_bytes, r.has_audio, r.storage_path,
           r.peak, r.audio_source
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
