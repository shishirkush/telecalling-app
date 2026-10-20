-- =====================================================================
--  MIGRATION 45 — supervisor (manager) logins are dashboard-only
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- On 2026-10-20 an agent (dilkhush) made 28 calls signed in as the
-- manager1 login, so his calls were recorded under the manager and his own
-- report was empty. A non-admin supervisor must not be able to claim
-- leads, log call attempts or save outcomes in the calling app. Admin is
-- exempt (used for testing).
--
-- Enforced in the database so it holds for every app version: a lead can't
-- be assigned to such an account, and no call attempt / disposition can be
-- written for it.

create or replace function public.block_supervisor_calls()
returns trigger
language plpgsql
security definer set search_path = public
as $$
declare
  v_target uuid;
begin
  if tg_table_name = 'leads' then
    v_target := new.assigned_to;
  else
    v_target := new.agent_id;
  end if;

  if v_target is not null and exists (
    select 1 from public.profiles p
     where p.id = v_target
       and p.role = 'supervisor'
       and not coalesce(p.is_admin, false)
  ) then
    raise exception 'Supervisor accounts can only use the dashboard, not the calling app. Sign in with your agent login.';
  end if;

  return new;
end;
$$;

drop trigger if exists block_supervisor_lead_claim on public.leads;
create trigger block_supervisor_lead_claim
  before update of assigned_to on public.leads
  for each row
  when (new.assigned_to is distinct from old.assigned_to and new.assigned_to is not null)
  execute function public.block_supervisor_calls();

drop trigger if exists block_supervisor_attempt on public.call_attempts;
create trigger block_supervisor_attempt
  before insert on public.call_attempts
  for each row execute function public.block_supervisor_calls();

drop trigger if exists block_supervisor_disposition on public.call_dispositions;
create trigger block_supervisor_disposition
  before insert on public.call_dispositions
  for each row execute function public.block_supervisor_calls();

-- ---------------------------------------------------------------------
-- One-off: sign the manager1 login out everywhere (run separately, once).
-- The app logs itself out when its session token no longer matches
-- (backend/27_single_session_per_agent.sql); deleting the auth sessions
-- stops the dashboard / app refreshing its login.
-- ---------------------------------------------------------------------
--   update public.profiles
--      set active_session_token = 'revoked-' || gen_random_uuid()::text
--    where login_id = 'manager1';
--   delete from auth.sessions
--    where user_id = (select id from public.profiles where login_id = 'manager1');
