-- =====================================================================
--  MIGRATION 19 — deactivate/reactivate an agent from the dashboard
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- A real hard delete (removing the auth.users row) is not actually
-- available here: call_dispositions.agent_id is `not null references
-- profiles(id)` with no ON DELETE clause, so Postgres refuses to delete
-- a profile that has ever made a single call — exactly the row you'd
-- most want to keep anyway, since it's the audit trail this app is
-- built around (see 05_fix_profile_privilege_escalation.sql,
-- lead_access_log, lead_search_log). Losing an exited agent's call
-- history would also break every "Agent performance" / "Recent calls"
-- row that references them.
--
-- profiles.active already exists and is already load-bearing —
-- claim_next_lead, save_disposition, search_lead, etc. all refuse an
-- inactive caller (`where id = auth.uid() and active`) — it just had
-- no dashboard button wired to it, and column-level grants revoked
-- direct client writes to it after the privilege-escalation fix
-- (migration 05), so it needs a security-definer function like this
-- one rather than a plain PATCH.
--
-- One function, not a one-way "delete": an admin misclick or a rehire
-- both need to undo this, same as the existing batch active/inactive
-- toggle (13_batch_active_toggle.sql). Deactivating also releases the
-- agent's currently-open leads (freshly claimed, or CALL_LATER
-- awaiting a callback) back to the pool — otherwise they'd sit stuck
-- assigned to someone who can no longer act on them. claim_next_lead
-- already treats assigned_to is null as claimable and leaves
-- callback_at untouched, so a pending callback just gets served to
-- whichever agent is next when it's due (see 01_schema.sql).
-- Reactivating does not try to give old leads back — too much may have
-- changed since; a returning agent just starts claiming fresh.

create or replace function public.set_agent_active(p_agent_id uuid, p_active boolean)
returns void
language plpgsql
security definer set search_path = public
as $$
begin
  if not public.is_admin() then
    raise exception 'not permitted: only an admin may change an agent''s active status';
  end if;

  if not exists (
    select 1 from public.profiles where id = p_agent_id and role = 'agent'
  ) then
    raise exception 'agent % not found', p_agent_id;
  end if;

  update public.profiles
     set active = p_active
   where id = p_agent_id;

  if not p_active then
    update public.leads
       set assigned_to = null,
           locked_by   = null,
           locked_at   = null
     where assigned_to = p_agent_id
       and is_closed = false;
  end if;
end;
$$;

grant execute on function public.set_agent_active(uuid, boolean) to authenticated;

-- v_app_versions is the one place the dashboard already lists every
-- agent with their active status (as "(inactive)") — appending id here
-- (rather than a separate query) is what lets the new toggle button
-- target set_agent_active(p_agent_id, ...) without a second round trip.
-- Appended at the end on purpose — CREATE OR REPLACE VIEW cannot
-- reorder or insert columns mid-list (42P16).
create or replace view public.v_app_versions
with (security_invoker = true) as
select
  login_id,
  full_name,
  app_version,
  app_version_reported_at,
  active,
  device_model,
  device_id,
  id
from public.profiles
where role = 'agent'
order by app_version_reported_at desc nulls last;
