-- =====================================================================
--  MIGRATION 15 — the app reports its own version, so "which agents
--  have updated" is a direct query instead of a guess built from
--  unrelated signals (call activity, SMS outcome rows, etc.)
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- This has come up three times now: "check which agents have the
-- latest version", "which agents have SMS operational", "check which
-- agents updated to v1.7.0" — each time answered by inference from
-- sms_delivery_log or call_dispositions activity, which can only ever
-- say "this agent is on v1.6.0 or later", never the exact version, and
-- says nothing at all about an agent who hasn't called anyone recently.
--
-- report_app_version() is called once per cold launch (AppViewModel),
-- right alongside update.UpdateChecker's own check — same moment, same
-- reasoning: telling the backend what's actually installed beats
-- inferring it from side effects.

alter table public.profiles
  add column if not exists app_version           text,
  add column if not exists app_version_reported_at timestamptz;

create or replace function public.report_app_version(p_version text)
returns void
language plpgsql
security definer set search_path = public
as $$
begin
  update public.profiles
     set app_version = p_version,
         app_version_reported_at = now()
   where id = auth.uid();
end;
$$;

-- At-a-glance rollout view for the dashboard: one row per agent, admin
-- (and their managers) only — security_invoker means it runs under the
-- caller's own RLS on profiles, same scoping as everything else here.
create or replace view public.v_app_versions
with (security_invoker = true) as
select
  login_id,
  full_name,
  app_version,
  app_version_reported_at,
  active
from public.profiles
where role = 'agent'
order by app_version_reported_at desc nulls last;
