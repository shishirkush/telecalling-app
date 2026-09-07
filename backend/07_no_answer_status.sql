-- =====================================================================
--  MIGRATION 07 — add a "No answer" call outcome
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Run the two statements SEPARATELY — a new enum value cannot be used
--  in the same transaction that adds it, so the view update must be a
--  second statement/batch, not appended to the same one as ALTER TYPE.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. New outcome: the call rang out with nobody picking up. Distinct from
--    SWITCHED_OFF (device unreachable) — same downstream behaviour though:
--    save_disposition() only closes WRONG_NUMBER / NOT_INTERESTED / LEAD,
--    so NO_ANSWER falls through unchanged and stays open for a retry.
-- ---------------------------------------------------------------------

alter type call_status add value if not exists 'NO_ANSWER';


-- ---------------------------------------------------------------------
-- 2. Track it in the supervisor reporting view alongside the other
--    outcomes. Run only after statement 1 above has committed.
-- ---------------------------------------------------------------------

create or replace view public.v_agent_performance
with (security_invoker = true) as
select
  p.id                        as agent_id,
  p.full_name,
  count(d.id)                                                as total_calls,
  count(*) filter (where d.status = 'LEAD')                  as leads,
  count(*) filter (where d.status = 'NOT_INTERESTED')        as not_interested,
  count(*) filter (where d.status = 'CALL_LATER')            as call_later,
  count(*) filter (where d.status = 'SWITCHED_OFF')          as switched_off,
  count(*) filter (where d.status = 'WRONG_NUMBER')          as wrong_number,
  round(
    100.0 * count(*) filter (where d.status = 'LEAD')
    / nullif(count(d.id), 0), 1
  )                                                          as conversion_pct,
  max(d.called_at)                                           as last_activity,
  -- Appended, not inserted alongside the other outcome counts above:
  -- CREATE OR REPLACE VIEW can only add columns at the end, not reorder
  -- or insert them (Postgres error 42P16). Keep new columns last here.
  count(*) filter (where d.status = 'NO_ANSWER')             as no_answer
from public.profiles p
left join public.call_dispositions d on d.agent_id = p.id
where p.role = 'agent'
group by p.id, p.full_name;
