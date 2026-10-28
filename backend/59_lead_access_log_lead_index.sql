-- =====================================================================
--  MIGRATION 59 — index lead_access_log.lead_id so deleting leads is fast
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- lead_access_log.lead_id references leads(id) on delete cascade but had no
-- index (the only one is on (agent_id, viewed_at)). Deleting a lead therefore
-- scanned the whole log once per lead, so delete_batch() on a ~16,000-lead
-- batch hit the statement timeout ("canceling statement due to statement
-- timeout") on 28 Oct 2026. With the index the same delete takes well under
-- a second.

create index if not exists lead_access_log_lead_idx
  on public.lead_access_log (lead_id);
