-- =====================================================================
--  MIGRATION 05 — SECURITY FIX: agents could promote themselves
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- THE BUG
-- -------
-- 01_schema.sql created this policy:
--
--   create policy "update own name"
--     on public.profiles for update
--     using (id = auth.uid())
--     with check (id = auth.uid());
--
-- The name says "own name", but an RLS policy constrains which ROWS you may
-- touch, never which COLUMNS. So the policy allowed an agent to update any
-- column of their own profile row — including `role`.
--
-- Confirmed against a live project: a plain agent ran
--
--   PATCH /rest/v1/profiles?id=eq.<their own id>   {"role":"supervisor"}
--
-- and it succeeded. Once `role = 'supervisor'`, public.is_supervisor() returns
-- true, which unlocks:
--   * "agents read assigned leads"  -> or public.is_supervisor()  = read EVERY lead
--   * "supervisors manage leads"    -> for all                    = write EVERY lead
--   * "supervisors read access log" -> the whole audit trail
--
-- i.e. any agent could dump the entire KYC-grade PII database and edit the
-- lead pool. The anon key needed is embedded in the APK and is extractable,
-- so the only thing an attacker needs beyond that is one valid agent login.
-- This defeats the "never give agents direct write access to leads" rule in
-- section 8 of CLAUDE.md — the agent just grants it to themselves.
--
-- THE FIX
-- -------
-- RLS cannot express column restrictions, so use column-level privileges,
-- which is the correct Postgres mechanism. `authenticated` keeps UPDATE on
-- exactly one harmless column and loses it everywhere else on this table.
-- `role` and `active` then become writable only by something that bypasses
-- RLS/grants — the SQL editor, or a security-definer function.

revoke update on public.profiles from authenticated;
grant  update (full_name) on public.profiles to authenticated;

-- Belt and braces: even if a future migration re-grants UPDATE on the whole
-- table, refuse a role/active change that did not come from a supervisor or
-- from a security-definer context. Cheap, and it fails loudly instead of
-- silently handing out admin.
create or replace function public.prevent_self_privilege_escalation()
returns trigger
language plpgsql
security definer set search_path = public
as $$
begin
  if (new.role is distinct from old.role)
     or (new.active is distinct from old.active) then
    -- auth.uid() is null for the service role and for SQL-editor sessions,
    -- so administrative changes still work.
    if auth.uid() is not null and not public.is_supervisor() then
      raise exception 'not permitted: only a supervisor may change role or active';
    end if;
    -- And nobody may promote themselves, supervisor or not.
    if auth.uid() is not null and new.id = auth.uid()
       and (new.role is distinct from old.role) then
      raise exception 'not permitted: you cannot change your own role';
    end if;
  end if;
  return new;
end;
$$;

drop trigger if exists profiles_block_privilege_escalation on public.profiles;
create trigger profiles_block_privilege_escalation
  before update on public.profiles
  for each row execute function public.prevent_self_privilege_escalation();

-- Rename the policy so the next reader is not misled by "update own name"
-- into thinking columns were ever constrained by it.
alter policy "update own name" on public.profiles rename to "update own profile row";
