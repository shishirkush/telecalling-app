-- =====================================================================
--  MIGRATION 50 — drop the SMS delivery log
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- The app no longer sends any SMS (Apply Card texts discontinued 2026-10-20,
-- recruitment texting removed in 1.18.0), so the delivery log, the view over
-- it and the function that wrote to it go away.
--
-- reassign_agent_history() (migration 25) updated sms_delivery_log, so it is
-- replaced FIRST, without that step — dropping the table underneath it would
-- have broken the dashboard's "move an archived agent's history" tool.

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

  return jsonb_build_object(
    'call_dispositions', n_dispositions,
    'call_attempts',     n_attempts,
    'lead_access_log',   n_access_log,
    'lead_search_log',   n_search_log,
    'lead_edit_log',     n_edit_log,
    'login_log',         n_login_log
  );
end;
$$;

grant execute on function public.reassign_agent_history(uuid, uuid) to authenticated;

-- No cascade: if anything unexpected still depends on these, the drop fails
-- instead of silently removing it.
drop view if exists public.v_sms_outcomes;
drop function if exists public.log_sms_outcome(bigint, text, text);
drop table if exists public.sms_delivery_log;
