-- =====================================================================
--  MIGRATION 25 — reassign an archived agent's call history to an
--  active agent
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- Renaming an archived agent (full_name + login_id, see the companion
-- rename-agent Edge Function) repurposes the seat's underlying uuid for
-- a new hire. That uuid is exactly what every log table's agent_id
-- column points at, so without this, the new hire would silently
-- inherit the previous person's entire call history the moment they're
-- renamed — every call_dispositions/call_attempts/login_log row would
-- now read as theirs. This lets an admin move that history onto a
-- different, still-active agent first, so a renamed/repurposed seat
-- starts clean.
--
-- Only log tables are touched. leads.assigned_to/locked_by need no
-- attention: set_agent_active() (19_deactivate_agent.sql) already
-- released every open lead back to the pool the moment the source
-- agent was archived, and save_disposition() (22_require_call_before_
-- disposition.sql) always nulls assigned_to/locked_by on close — so an
-- archived agent's row in `leads` is never still pointing at them.

create or replace function public.reassign_agent_history(
  p_from_agent_id uuid,
  p_to_agent_id   uuid
)
returns jsonb
language plpgsql
security definer set search_path = public
as $$
declare
  n_dispositions int;
  n_attempts     int;
  n_access_log   int;
  n_search_log   int;
  n_edit_log     int;
  n_login_log    int;
  n_sms_log      int;
begin
  if not public.is_admin() then
    raise exception 'not permitted: only an admin may reassign agent history';
  end if;

  if p_from_agent_id = p_to_agent_id then
    raise exception 'source and destination agent must be different';
  end if;

  if not exists (
    select 1 from public.profiles
     where id = p_from_agent_id and role = 'agent' and not active
  ) then
    raise exception 'source agent % is not an archived agent', p_from_agent_id;
  end if;

  if not exists (
    select 1 from public.profiles
     where id = p_to_agent_id and role = 'agent' and active
  ) then
    raise exception 'destination agent % is not an active agent', p_to_agent_id;
  end if;

  update public.call_dispositions set agent_id = p_to_agent_id where agent_id = p_from_agent_id;
  get diagnostics n_dispositions = row_count;

  update public.call_attempts set agent_id = p_to_agent_id where agent_id = p_from_agent_id;
  get diagnostics n_attempts = row_count;

  update public.lead_access_log set agent_id = p_to_agent_id where agent_id = p_from_agent_id;
  get diagnostics n_access_log = row_count;

  update public.lead_search_log set agent_id = p_to_agent_id where agent_id = p_from_agent_id;
  get diagnostics n_search_log = row_count;

  update public.lead_edit_log set agent_id = p_to_agent_id where agent_id = p_from_agent_id;
  get diagnostics n_edit_log = row_count;

  update public.login_log set agent_id = p_to_agent_id where agent_id = p_from_agent_id;
  get diagnostics n_login_log = row_count;

  update public.sms_delivery_log set agent_id = p_to_agent_id where agent_id = p_from_agent_id;
  get diagnostics n_sms_log = row_count;

  return jsonb_build_object(
    'call_dispositions', n_dispositions,
    'call_attempts',     n_attempts,
    'lead_access_log',   n_access_log,
    'lead_search_log',   n_search_log,
    'lead_edit_log',     n_edit_log,
    'login_log',         n_login_log,
    'sms_delivery_log',  n_sms_log
  );
end;
$$;

grant execute on function public.reassign_agent_history(uuid, uuid) to authenticated;
