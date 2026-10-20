-- =====================================================================
--  MIGRATION 44 — remote on/off switch for the Apply Card text message
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- The Android app sends the Apply Card SMS from the agent's own SIM after
-- each call, so nothing on the server could stop it. The app (1.17.2+)
-- now asks apply_card_sms_enabled() on every launch/resume and before each
-- send, remembers the last answer, and sends nothing while it is false.
-- The web app hides its "Text the Apply Card link" button the same way.
-- Older Android builds (1.17.1 and earlier) don't know the switch and keep
-- sending until they update.
--
-- Admin flips it from the dashboard (admin_set_app_flag). Every change is
-- stamped with who/when in app_flags.

create table if not exists public.app_flags (
  key        text        primary key,
  value      boolean     not null,
  updated_at timestamptz not null default now(),
  updated_by uuid        references public.profiles(id)
);

alter table public.app_flags enable row level security;
-- No direct table access for anyone: reads and writes go through the
-- two functions below.

insert into public.app_flags (key, value)
values ('apply_card_sms_enabled', true)
on conflict (key) do nothing;

create or replace function public.apply_card_sms_enabled()
returns boolean
language sql
stable
security definer set search_path = public
as $$
  select coalesce(
    (select value from public.app_flags where key = 'apply_card_sms_enabled'),
    true
  )
  where exists (select 1 from public.profiles where id = auth.uid() and active);
$$;

grant execute on function public.apply_card_sms_enabled() to authenticated;

create or replace function public.admin_set_app_flag(p_key text, p_value boolean)
returns void
language plpgsql
security definer set search_path = public
as $$
begin
  if not public.is_admin() then
    raise exception 'not permitted: admin only';
  end if;
  if p_key not in ('apply_card_sms_enabled') then
    raise exception 'unknown flag %', p_key;
  end if;
  insert into public.app_flags (key, value, updated_at, updated_by)
  values (p_key, p_value, now(), auth.uid())
  on conflict (key) do update
     set value = excluded.value, updated_at = now(), updated_by = auth.uid();
end;
$$;

grant execute on function public.admin_set_app_flag(text, boolean) to authenticated;

-- Admin-only read of the switch plus who last changed it, for the dashboard.
create or replace function public.admin_get_app_flags()
returns table (key text, value boolean, updated_at timestamptz, updated_by_name text)
language plpgsql
stable
security definer set search_path = public
as $$
begin
  if not public.is_admin() then
    raise exception 'not permitted: admin only';
  end if;
  return query
    select f.key, f.value, f.updated_at, p.full_name
      from public.app_flags f
      left join public.profiles p on p.id = f.updated_by;
end;
$$;

grant execute on function public.admin_get_app_flags() to authenticated;
