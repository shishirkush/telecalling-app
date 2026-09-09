-- =====================================================================
--  MIGRATION 10 — whole-database report (admin only)
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- The existing tiles (Calls made, Leads generated, ...) are scoped to a
-- date range and to call_dispositions — good for "how did today go?",
-- no good for "how much of the lead pool is left?". This view answers
-- the second question: one row per CSV import batch, whole-pool counts,
-- no date filter.
--
-- security_invoker means it runs under the caller's own RLS on `leads`
-- (backend/09_manager_scoping.sql) — a non-admin manager querying this
-- would only see counts for leads tied to their own agents, not the
-- true batch totals. The dashboard only shows this to admin, same as
-- Create Agent and CSV Import.

create or replace view public.v_batch_summary
with (security_invoker = true) as
select
  coalesce(batch, '(no batch)')       as batch,
  count(*)                            as total,
  count(*) filter (where attempts > 0) as called,
  count(*) filter (where attempts = 0) as pending,
  count(*) filter (where is_closed)    as closed,
  min(created_at)                     as imported_at
from public.leads
group by batch
order by min(created_at) desc;
