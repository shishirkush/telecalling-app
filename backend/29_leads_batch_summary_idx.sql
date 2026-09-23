-- =====================================================================
--  MIGRATION 29 — covering index for the batch-summary aggregate.
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- THE PROBLEM
-- -----------
-- v_batch_summary (backend/13_batch_active_toggle.sql) groups the whole
-- leads table by batch, computing four count(*) filter(...) aggregates
-- plus min(created_at) per group. With leads past 80,000 rows and no
-- index carrying batch/attempts/is_closed/created_at together, this was
-- a full heap scan every time the dashboard's Database section loaded —
-- and as of today it's slow enough to hit Supabase's statement timeout
-- outright (57014 canceling statement due to statement timeout),
-- returning a bare 500 that used to blank the whole Database section
-- (see dashboard's loadDatabaseStats() fix, same commit as this file).
--
-- THE FIX
-- -------
-- A btree index on `batch` with the other three columns carried as
-- INCLUDE payload lets Postgres satisfy the whole aggregate as an
-- index-only scan — grouped and mostly in batch order already, and
-- never touching the heap for attempts/is_closed/created_at. This does
-- not help the plain per-row queries (leads_queue_idx and
-- leads_mobile_idx already cover those); it exists specifically for
-- this one aggregate view.

create index if not exists leads_batch_summary_idx
  on public.leads (batch)
  include (attempts, is_closed, created_at);
