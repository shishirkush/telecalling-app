-- =====================================================================
--  MIGRATION 28 — a separate "app_opened" event in the login log.
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- WHY
-- ---
-- Until now every app launch with a still-valid session wrote a "login"
-- row (see migration 21), so Login Activity showed a "login" each time
-- Android killed and restarted the app — e.g. while a phone call was in
-- the foreground — which read as an agent signing in over and over.
--
-- From app version 1.10.8 on, "login" means a real password sign-in only.
-- A launch that resumes an existing session is logged as "app_opened",
-- and only if at least 30 minutes have passed since that device last
-- logged any event. Older builds keep writing "login" on every launch
-- until they update.

alter table public.login_log
  drop constraint if exists login_log_event_check;

alter table public.login_log
  add constraint login_log_event_check
  check (event in ('login', 'logout', 'app_opened'));

create or replace function public.log_login_event(
  p_event    text,
  p_platform text default null
)
returns void
language plpgsql
security definer set search_path = public
as $$
begin
  if p_event not in ('login', 'logout', 'app_opened') then
    raise exception 'invalid event: %', p_event;
  end if;
  if not exists (select 1 from public.profiles where id = auth.uid()) then
    raise exception 'unknown user';
  end if;

  insert into public.login_log (agent_id, event, platform)
  values (auth.uid(), p_event, p_platform);
end;
$$;

grant execute on function public.log_login_event(text, text) to authenticated;
