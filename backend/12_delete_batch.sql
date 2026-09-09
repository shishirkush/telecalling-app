-- =====================================================================
--  MIGRATION 12 — let admin delete a whole CSV batch
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- Deleting a lead cascades to call_dispositions, lead_access_log and
-- lead_edit_log (all `on delete cascade` back to leads.id) — so this
-- permanently deletes the call history for every lead in the batch,
-- not just the leads themselves. That is real, irreversible data loss
-- if the batch has already been worked, so this is:
--   * admin-only (checked here, not just hidden in the UI)
--   * a single RPC rather than a raw DELETE the dashboard could fire
--     off with no record of what was asked for
--   * reports back exactly what it removed, so the confirmation the
--     dashboard shows before calling this can be backed by a real count
--     and the result after can say what actually happened.

create or replace function public.delete_batch(p_batch text)
returns table(deleted_leads bigint, deleted_dispositions bigint)
language plpgsql
security definer set search_path = public
as $$
declare
  v_leads bigint;
  v_disp  bigint;
begin
  if not public.is_admin() then
    raise exception 'only admin can delete a batch';
  end if;

  select count(*) into v_disp
    from public.call_dispositions cd
    join public.leads l on l.id = cd.lead_id
   where l.batch is not distinct from p_batch;

  delete from public.leads where batch is not distinct from p_batch;
  get diagnostics v_leads = row_count;

  if v_leads = 0 then
    raise exception 'no leads found in batch %', coalesce(p_batch, '(no batch)');
  end if;

  return query select v_leads, v_disp;
end;
$$;
