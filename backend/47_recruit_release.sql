-- =====================================================================
--  MIGRATION 47 — recruitment SMS: let the app hand contacts back
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- When a batch stops early (a run of failures, no SIM balance, permission
-- missing) the app now reports the contacts it did not send — and the run of
-- failures that stopped it — with an outcome starting "released". Those go
-- back to 'pending' with no claim, so they are neither lost nor counted
-- against the agent's daily cap.

create or replace function public.report_recruit_results(p_results jsonb)
returns int
language plpgsql
security definer set search_path = public
as $$
declare
  n int;
begin
  update public.recruit_contacts c
     set status     = case when r.outcome like 'released%' then 'pending'
                           when r.outcome = 'sent'         then 'sent'
                           else 'failed' end,
         claimed_by = case when r.outcome like 'released%' then null else c.claimed_by end,
         claimed_at = case when r.outcome like 'released%' then null else c.claimed_at end,
         outcome    = case when r.outcome like 'released%' then null else left(r.outcome, 80) end,
         sent_at    = case when r.outcome like 'released%' then null else now() end
    from jsonb_to_recordset(p_results) as r(id bigint, outcome text)
   where c.id = r.id and c.claimed_by = auth.uid() and c.status = 'claimed';
  get diagnostics n = row_count;
  return n;
end;
$$;

grant execute on function public.report_recruit_results(jsonb) to authenticated;

-- One-off: 2026-10-20 the first two batches (Dilkhush, Lokesh) failed on
-- every send, so nothing was delivered. Put everything that is not 'sent'
-- back in the pool.
-- Left commented out until the fixed app build is out (1.17.2 builds would
-- just strand another batch): run it then to restore the agents' cap today.
-- update public.recruit_contacts
--    set status = 'pending', claimed_by = null, claimed_at = null, outcome = null, sent_at = null
--  where status in ('claimed', 'failed');
