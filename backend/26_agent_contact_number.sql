-- =====================================================================
--  MIGRATION 26 — agent's own calling number, self-reported
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- Not a per-call log — Android can't reliably read a SIM's own number
-- (many Indian carriers/prepaid SIMs never populate it, even with
-- READ_PHONE_NUMBERS granted), so a per-call auto-detect would be blank
-- more often than not. Instead this is one reliable fact per agent: the
-- number their supervisor calls back if a customer complains about a
-- specific number, self-reported once and editable any time from the
-- app. See the companion "My number" dialog in LeadQueueScreen.kt /
-- webapp/index.html.
--
-- Reuses 05_fix_profile_privilege_escalation.sql's exact pattern rather
-- than a new RPC: that migration already narrowed `authenticated`'s
-- UPDATE grant on profiles to individual columns, with RLS's "update
-- own profile row" policy (id = auth.uid()) doing the row-scoping. This
-- just adds contact_number to that same column grant — an agent can set
-- their own, nobody else's, no new trigger or function needed.

alter table public.profiles add column if not exists contact_number text;

do $$
begin
  if not exists (
    select 1 from pg_constraint where conname = 'profiles_contact_number_format'
  ) then
    alter table public.profiles
      add constraint profiles_contact_number_format
      check (contact_number is null or contact_number ~ '^[0-9+][0-9+ -]{5,19}$');
  end if;
end$$;

grant update (contact_number) on public.profiles to authenticated;

-- Narrow read surface for the dashboard's "Agent contact numbers" list —
-- just identity + number, not the whole profiles row shape.
-- security_invoker so "read own profile" RLS still applies: an admin's
-- dashboard session sees every agent via is_supervisor(), same as every
-- other admin-only dashboard list.
create or replace view public.v_agent_contacts
with (security_invoker = true) as
select id, login_id, full_name, contact_number, active
from public.profiles
where role = 'agent'
order by full_name;
