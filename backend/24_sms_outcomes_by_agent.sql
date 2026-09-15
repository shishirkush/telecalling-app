-- =====================================================================
--  MIGRATION 24 — break the Apply Card SMS delivery report down by
--  agent, not just by outcome.
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- v_sms_outcomes (migration 14) answered "is this feature broken right
-- now" with one row per outcome, totalled across every agent — exactly
-- the wrong shape for "which agent's SMS never actually sends," since
-- one agent stuck on permission_denied is invisible in a total that
-- also includes everyone else's successful sends. sms_delivery_log
-- already carries agent_id; this was always available, just never
-- surfaced with that dimension.

-- DROP + CREATE, not CREATE OR REPLACE — the column list changes shape
-- entirely (3 columns starting with `outcome` to 6 starting with
-- `full_name`), which CREATE OR REPLACE VIEW rejects (42P16: it only
-- ever allows appending new columns after the existing ones, never
-- reordering). Safe here specifically because nothing else in the
-- database references this view — only the dashboard queries it, over
-- REST, so there is no dependent object to break by dropping it first.
drop view if exists public.v_sms_outcomes;

create view public.v_sms_outcomes
with (security_invoker = true) as
select
  p.full_name,
  p.login_id,
  l.agent_id,
  l.outcome,
  count(*)         as total,
  max(l.created_at) as most_recent
from public.sms_delivery_log l
join public.profiles p on p.id = l.agent_id
group by p.full_name, p.login_id, l.agent_id, l.outcome
order by p.full_name, total desc;
