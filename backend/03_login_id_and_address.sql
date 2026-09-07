-- =====================================================================
--  MIGRATION 03 — login IDs instead of email, plus lead address
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. ADDRESS on leads
-- ---------------------------------------------------------------------
-- A tenth field visible to the caller. Capped at 2000 characters and
-- enforced here, not only in the UI — same rule as `remarks`.

alter table public.leads add column if not exists address text;

do $$
begin
  if not exists (
    select 1 from pg_constraint where conname = 'leads_address_length'
  ) then
    alter table public.leads
      add constraint leads_address_length check (char_length(address) <= 2000);
  end if;
end$$;


-- ---------------------------------------------------------------------
-- 2. LOGIN ID on profiles
-- ---------------------------------------------------------------------
-- Agents never see or type an email address. The supervisor issues them a
-- login ID; the app turns "<login_id>" into "<login_id>@<LOGIN_DOMAIN>"
-- before it talks to GoTrue. That keeps Supabase Auth — and therefore
-- auth.uid(), every RLS policy, session refresh and password hashing —
-- exactly as it was. The synthetic address is an internal detail; it is
-- never shown to an agent and never receives mail.
--
-- login_id is stored here so a supervisor can see "rahul.k" in the
-- dashboard rather than "rahul.k@telecall.local".

alter table public.profiles add column if not exists login_id text;

-- Case-insensitive uniqueness: "Rahul.K" and "rahul.k" must not coexist.
create unique index if not exists profiles_login_id_uniq
  on public.profiles (lower(login_id));


-- ---------------------------------------------------------------------
-- 3. Populate login_id on signup
-- ---------------------------------------------------------------------
-- Replaces the version in 01_schema.sql. Same behaviour as before, plus
-- login_id taken from the local-part of the address the supervisor typed.

create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer set search_path = public
as $$
begin
  insert into public.profiles (id, full_name, role, login_id)
  values (
    new.id,
    coalesce(new.raw_user_meta_data->>'full_name', split_part(new.email, '@', 1)),
    coalesce((new.raw_user_meta_data->>'role')::user_role, 'agent'),
    lower(split_part(new.email, '@', 1))
  );
  return new;
end;
$$;


-- ---------------------------------------------------------------------
-- 4. Backfill anyone who signed up before this migration
-- ---------------------------------------------------------------------

update public.profiles p
   set login_id = lower(split_part(u.email, '@', 1))
  from auth.users u
 where u.id = p.id
   and p.login_id is null;
