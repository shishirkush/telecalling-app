-- =====================================================================
--  MIGRATION 23 — require 30 seconds between a call attempt and saving
--  its outcome, on top of migration 22's "must have called at all."
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- Migration 22 closed "claim a lead, view its details, save without
-- ever calling" — but log_call_attempt() fires the instant the agent
-- taps "Call", before the call even rings, let alone connects. Nothing
-- measures how long the call actually lasted, so "tap Call, hang up
-- immediately, save No Answer" satisfied that gate exactly as well as
-- a genuine unanswered call. This app deliberately never adds
-- READ_CALL_LOG (see CLAUDE.md section 8), and there is no browser API
-- at all for a web page to observe real call state or duration, so
-- actual call-duration verification isn't available on either
-- platform. A minimum elapsed time between the attempt and the save is
-- the honest, permission-free approximation: it can't prove the call
-- was answered, but it does make the fastest possible "fake" cycle no
-- quicker than a real one would realistically take.

create or replace function public.save_disposition(
  p_lead_id      bigint,
  p_status       call_status,
  p_lead_quality lead_quality default null,
  p_remarks      text         default null,
  p_callback_at  timestamptz  default null,
  p_sim_slot     int          default null
)
returns bigint                 -- id of the disposition row that was written
language plpgsql
security definer set search_path = public
as $$
declare
  new_id bigint;
  lead_last_called_at timestamptz;
  min_dwell constant interval := '30 seconds';
begin
  select last_called_at into lead_last_called_at
    from public.leads
   where id = p_lead_id
     and (assigned_to = auth.uid() or public.is_admin());

  if not found then
    raise exception 'lead % is not assigned to you', p_lead_id;
  end if;

  -- Admin's write-override (fixing a lead directly) is exempt on
  -- purpose — same tier as the ownership check just above, a
  -- correction doesn't require the admin to have personally called.
  if not public.is_admin() then
    if not exists (
      select 1 from public.call_attempts
       where lead_id = p_lead_id
         and agent_id = auth.uid()
         and attempted_at > coalesce(lead_last_called_at, '-infinity'::timestamptz)
    ) then
      raise exception 'Call this customer before saving an outcome.';
    end if;

    -- A fresh attempt exists (checked above) but none of them are old
    -- enough yet — distinct message so the agent knows to wait, not to
    -- call again. min(attempted_at) among the fresh ones is what
    -- matters: the earliest fresh attempt is the one the 30s clock
    -- should count from, same call the agent is actually on.
    if not exists (
      select 1 from public.call_attempts
       where lead_id = p_lead_id
         and agent_id = auth.uid()
         and attempted_at > coalesce(lead_last_called_at, '-infinity'::timestamptz)
         and attempted_at <= now() - min_dwell
    ) then
      raise exception 'Wait at least 30 seconds after calling before saving an outcome.';
    end if;
  end if;

  insert into public.call_dispositions
    (lead_id, agent_id, status, lead_quality, remarks, callback_at, sim_slot)
  values
    (p_lead_id, auth.uid(), p_status, p_lead_quality,
     nullif(btrim(p_remarks), ''), p_callback_at, p_sim_slot)
  returning id into new_id;

  update public.leads
     set last_status    = p_status,
         last_remarks   = nullif(btrim(p_remarks), ''),
         last_called_at = now(),
         attempts       = attempts + 1,
         callback_at    = case when p_status = 'CALL_LATER' then p_callback_at else null end,
         is_closed      = p_status in ('WRONG_NUMBER', 'NOT_INTERESTED', 'LEAD'),
         locked_by      = null,
         locked_at      = null,
         assigned_to    = case when p_status = 'CALL_LATER' then assigned_to else null end
   where id = p_lead_id;

  return new_id;
end;
$$;
