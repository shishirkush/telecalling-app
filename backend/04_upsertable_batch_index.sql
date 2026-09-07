-- =====================================================================
--  MIGRATION 04 — make the (batch, mobile) index usable by ON CONFLICT
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- The dashboard's CSV import upserts with
--   .upsert(chunk, { onConflict: "batch,mobile", ignoreDuplicates: true })
-- so that a part-finished import can simply be re-uploaded: chunks are not
-- one transaction, and with a plain INSERT the already-committed rows
-- collide on retry and the whole import fails.
--
-- That needs Postgres to *infer* the index from `on conflict (batch, mobile)`.
-- It cannot infer a PARTIAL index unless the statement repeats the index
-- predicate, and PostgREST gives no way to express that. The original
-- definition was partial:
--
--   create unique index leads_batch_mobile_uniq
--     on public.leads (batch, mobile) where batch is not null;
--
-- which fails with:
--   ERROR 42P10: there is no unique or exclusion constraint matching the
--   ON CONFLICT specification
--
-- Dropping the WHERE predicate changes nothing semantically. Postgres treats
-- NULLs as distinct in a unique index by default, so rows with batch IS NULL
-- still never conflict with each other — exactly as before. The index simply
-- becomes inferable.

drop index if exists public.leads_batch_mobile_uniq;

create unique index if not exists leads_batch_mobile_uniq
  on public.leads (batch, mobile);
