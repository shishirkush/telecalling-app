-- =====================================================================
--  MIGRATION 18 — report each phone's ANDROID_ID, so two agents on the
--  identical model (e.g. company-issued SM-M315F units) can actually
--  be told apart instead of looking like the same device.
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- device_model (migration 17) is Build.MANUFACTURER + Build.MODEL — a
-- model string, identical across every unit of that model ever made.
-- It settled "real phone or desktop emulator," but it cannot settle
-- "is this really two different phones, or one phone switching
-- accounts" when several agents share a phone model. Settings.Secure
-- .ANDROID_ID is a 64-bit value generated once per app install and
-- stable across relaunches (it changes only on factory reset or an
-- uninstall/reinstall) — a real per-install signal, not a per-model one.

alter table public.profiles
  add column if not exists device_id text;

create or replace function public.report_app_version(
  p_version      text,
  p_device_model text default null,
  p_device_id    text default null
)
returns void
language plpgsql
security definer set search_path = public
as $$
begin
  update public.profiles
     set app_version = p_version,
         app_version_reported_at = now(),
         device_model = coalesce(p_device_model, device_model),
         device_id = coalesce(p_device_id, device_id)
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
  device_model,
  device_id
from public.profiles
where role = 'agent'
order by app_version_reported_at desc nulls last;
