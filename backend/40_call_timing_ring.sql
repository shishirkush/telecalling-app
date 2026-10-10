-- =====================================================================
--  MIGRATION 40 — ring time: total dial-to-hangup time per call attempt
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- The phone's call log records only TALK time for an outgoing call
-- (call_attempts.duration_secs, migration 38) — an unanswered call is
-- 0s, so it can't say how long the phone actually rang. The Android app
-- (1.17.1+) now also watches the phone's call state from the Call tap
-- (READ_PHONE_STATE, already granted for SIM detection): OFFHOOK when
-- dialling starts, IDLE when the call ends. That span is dial_secs.
--
--   ring time = dial_secs - duration_secs   (computed in the dashboard)
--
-- For an unanswered call that is simply how long it rang before the
-- agent hung up or it timed out. Android app only — a web page cannot
-- observe call state, so web-app agents have no dial_secs.
--
-- report_call_timing() supersedes report_call_duration() for new app
-- builds and may be called with either value null (the call log can lag
-- the call state, or vice versa). Each field is written once, never
-- overwritten. report_call_duration() stays for older builds.

alter table public.call_attempts
  add column if not exists dial_secs int;

create or replace function public.report_call_timing(
  p_lead_id       bigint,
  p_duration_secs int,
  p_dial_secs     int
)
returns void
language plpgsql
security definer set search_path = public
as $$
begin
  if p_duration_secs is not null and (p_duration_secs < 0 or p_duration_secs > 14400) then
    raise exception 'invalid call duration';
  end if;
  if p_dial_secs is not null and (p_dial_secs < 0 or p_dial_secs > 14400) then
    raise exception 'invalid dial time';
  end if;

  update public.call_attempts
     set duration_secs = coalesce(duration_secs, p_duration_secs),
         dial_secs     = coalesce(dial_secs, p_dial_secs)
   where id = (
     select id from public.call_attempts
      where lead_id = p_lead_id
        and agent_id = auth.uid()
      order by attempted_at desc
      limit 1
   );
end;
$$;

grant execute on function public.report_call_timing(bigint, int, int) to authenticated;
