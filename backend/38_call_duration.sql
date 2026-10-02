-- =====================================================================
--  MIGRATION 38 — real call duration, from the phone's own call log
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- The agent report's "Gap" (tap Call -> save outcome) measures how long
-- the agent took, not how long the call rang or ran. The app now reads
-- the duration the phone itself recorded for the call it just placed
-- (READ_CALL_LOG, requested knowingly — this reverses the earlier
-- "never add READ_CALL_LOG" rule in CLAUDE.md section 8) and reports
-- ONLY that number, for the one lead being saved. No call-log rows,
-- numbers, or other entries ever leave the phone.

alter table public.call_attempts
  add column if not exists duration_secs int;

create or replace function public.report_call_duration(
  p_lead_id       bigint,
  p_duration_secs int
)
returns void
language plpgsql
security definer set search_path = public
as $$
begin
  if p_duration_secs is null or p_duration_secs < 0 or p_duration_secs > 14400 then
    raise exception 'invalid call duration';
  end if;

  -- Only the caller's own most recent attempt for this lead, and only
  -- once — a later report can't overwrite an earlier one.
  update public.call_attempts
     set duration_secs = p_duration_secs
   where id = (
     select id from public.call_attempts
      where lead_id = p_lead_id
        and agent_id = auth.uid()
      order by attempted_at desc
      limit 1
   )
   and duration_secs is null;
end;
$$;

grant execute on function public.report_call_duration(bigint, int) to authenticated;
