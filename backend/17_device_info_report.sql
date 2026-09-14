-- =====================================================================
--  MIGRATION 17 — capture what device each agent's app is actually
--  running on, to settle "is this a real phone or a PC Android
--  emulator" without guessing.
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- Every call from Kishan and Manisha, across three app versions, has
-- shown sim_slot = null and zero SMS delivery reports — consistent
-- with there being no real SIM/cellular radio at all, which is exactly
-- what a desktop Android player (BlueStacks, LDPlayer, NoxPlayer,
-- MEmu — all common in India, all commonly used for business apps on
-- a desk) looks like from here. Build.MANUFACTURER/MODEL reports that
-- distinction directly instead of inferring it from missing signals.

alter table public.profiles
  add column if not exists device_model text;

create or replace function public.report_app_version(
  p_version      text,
  p_device_model text default null
)
returns void
language plpgsql
security definer set search_path = public
as $$
begin
  update public.profiles
     set app_version = p_version,
         app_version_reported_at = now(),
         device_model = coalesce(p_device_model, device_model)
   where id = auth.uid();
end;
$$;

-- Appended at the end of the SELECT list on purpose — CREATE OR REPLACE
-- VIEW cannot reorder or insert columns mid-list (42P16).
create or replace view public.v_app_versions
with (security_invoker = true) as
select
  login_id,
  full_name,
  app_version,
  app_version_reported_at,
  active,
  device_model
from public.profiles
where role = 'agent'
order by app_version_reported_at desc nulls last;
