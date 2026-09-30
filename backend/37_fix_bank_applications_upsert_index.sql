-- =====================================================================
--  MIGRATION 37 — fix bank_applications bulk-upload upsert
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- backend/36_bank_applications.sql's dedupe index was PARTIAL
-- ("where application_id is not null"), so the dashboard's bulk upload
-- (`.upsert(chunk, { onConflict: "bank,application_id" })`) failed with
-- "there is no unique or exclusion constraint matching the ON CONFLICT
-- specification" — Postgres will only use a partial index as an
-- ON CONFLICT arbiter when the ON CONFLICT clause repeats that same
-- predicate, which PostgREST's upsert has no way to do.
--
-- Dropping the partial predicate and making this a plain unique index
-- keeps the same behaviour: standard SQL treats NULLs as distinct for
-- uniqueness purposes, so rows with application_id null (INDUS/SBI/YES
-- POP, per the original comment) can still repeat freely per bank —
-- but now the index also works as a real ON CONFLICT arbiter for rows
-- that do have an application_id.

drop index if exists public.bank_applications_bank_appid_uniq;

create unique index if not exists bank_applications_bank_appid_uniq
  on public.bank_applications (bank, application_id);
