-- =====================================================================
--  MIGRATION 35 — report whether a small, named list of competitor
--  referral apps is installed on the agent's phone
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- The concern: an agent could copy a lead's details into GroMo, Zet or
-- MyMoneyMantra — competing financial-product referral apps — and earn
-- a commission by re-selling the same lead outside this system.
--
-- This is disclosed, not covert (see docs/privacy-policy/index.html,
-- "Device identifiers" section) and deliberately narrow: Android 11+
-- named-package visibility (AndroidManifest.xml's <queries>, one <package>
-- entry per app) reports only whether these three specific, named apps
-- are present — never a general inventory of everything installed, which
-- is what the much broader QUERY_ALL_PACKAGES permission would expose and
-- is not requested here, matching this app's standing rule against
-- invasive permissions (see CLAUDE.md section 8 / the READ_CALL_LOG note).

alter table public.profiles
  add column if not exists competitor_apps text[],
  add column if not exists competitor_apps_checked_at timestamptz;

create or replace function public.report_competitor_apps(p_apps text[])
returns void
language plpgsql
security definer set search_path = public
as $$
begin
  update public.profiles
     set competitor_apps = coalesce(p_apps, '{}'::text[]),
         competitor_apps_checked_at = now()
   where id = auth.uid();
end;
$$;

grant execute on function public.report_competitor_apps(text[]) to authenticated;

-- Appended at the end of the SELECT list on purpose — CREATE OR REPLACE
-- VIEW cannot reorder or insert columns mid-list (42P16). Preserves every
-- column migration 19 already added (including id, which the dashboard's
-- Archive button targets) and just appends the two new ones.
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
  id,
  competitor_apps,
  competitor_apps_checked_at
from public.profiles
where role = 'agent'
order by app_version_reported_at desc nulls last;
