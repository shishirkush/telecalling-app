-- =====================================================================
--  MIGRATION 27 — one active session per agent login.
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- THE PROBLEM
-- -----------
-- The same login ID signed in on two devices at once (shared credentials,
-- or an agent who just never signed out of an old phone) meant two people
-- could work the same queue simultaneously under one identity —
-- indistinguishable in every report from one agent working fast.
--
-- Supabase's own "single session per user" enforcement (Authentication ->
-- Sessions in the dashboard) is Pro-plan and above. This is a free-tier
-- equivalent: a random token stamped onto the profile at sign-in, which
-- the app compares its own locally-remembered token against on every cold
-- start/resume — see AppViewModel.loadProfileAndQueue(). A device that
-- loses the comparison gets signed out right there.
--
-- Deliberately NOT enforced via RLS on every other table (leads,
-- call_dispositions, etc.) — that would need every policy in this schema
-- to carry the check, and would turn "you've been replaced, please sign
-- in again" into requests failing unpredictably mid-call. Same tolerance
-- as login_log itself (migration 21): this catches it on the replaced
-- device's next app open/resume, not to the second.

alter table public.profiles
  add column if not exists active_session_token uuid;

-- No column grant added for this on `authenticated` — migration 05 left
-- UPDATE on profiles scoped to exactly the `full_name` column, and this
-- stays that way. The only way to set active_session_token is the
-- security-definer function below, so a client can never hand itself (or
-- anyone else) the token that keeps its own session alive.

create or replace function public.claim_session()
returns uuid
language plpgsql
security definer set search_path = public
as $$
declare
  new_token uuid := gen_random_uuid();
begin
  if not exists (select 1 from public.profiles where id = auth.uid()) then
    raise exception 'unknown user';
  end if;

  update public.profiles
     set active_session_token = new_token
   where id = auth.uid();

  return new_token;
end;
$$;

grant execute on function public.claim_session() to authenticated;
