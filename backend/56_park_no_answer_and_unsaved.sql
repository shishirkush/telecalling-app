-- =====================================================================
--  MIGRATION 56 — "No answer" and unsaved leads are parked, not re-offered
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- Builds on migration 55 (leads.deferred_at, the leads_defer_unsaved trigger).
-- deferred_at now means PARKED: claim_next_lead() never hands out a lead whose
-- deferred_at is set — not today, not tomorrow, to nobody — until an admin
-- explicitly releases it.
--
-- A lead is parked when
--   * an outcome of NO_ANSWER is saved for it, or
--   * it was called, no outcome was saved, and it is then released from its
--     agent (30-minute stale lock, campaign switch, archiving, admin release).
-- Saving any other outcome clears the park.
--
-- To release parked leads (only when the owner approves), clear the mark:
--     update public.leads set deferred_at = null
--      where not is_closed and deferred_at is not null;      -- all of them
--   or add  and id in (...)  /  and last_status = 'NO_ANSWER'  to pick some.
-- They then rejoin the pool at the bottom of the queue (migration 55 ordering).
--
-- Existing open NO_ANSWER leads with nobody assigned are parked by the
-- backfill at the end.

create or replace function public.leads_defer_unsaved()
returns trigger
language plpgsql
security definer set search_path = public
as $$
begin
  if new.last_called_at is distinct from old.last_called_at then
    -- an outcome was saved: NO_ANSWER parks the lead, anything else clears it
    new.deferred_at := case when new.last_status = 'NO_ANSWER' then now() else null end;
  elsif old.assigned_to is not null and new.assigned_to is null
        and not new.is_closed
        and exists (
          select 1 from public.call_attempts a
           where a.lead_id = new.id
             and a.attempted_at > coalesce(new.last_called_at, '-infinity'::timestamptz)
        ) then
    new.deferred_at := now();                      -- called, never saved, now released
  end if;
  return new;
end;
$$;

-- claim_next_lead(): the two "not today" filters from 55 become one rule,
-- "never while parked". Patches the live definition in place.
do $patch$
declare d text; n text;
begin
  d := pg_get_functiondef('public.claim_next_lead()'::regprocedure);
  if d !~ 'c\.deferred_at is null' then
    n := regexp_replace(
      d,
      'and not coalesce\(c\.last_status = ''NO_ANSWER'' and c\.last_called_at >= ist_day_start, false\)\s+and not coalesce\(c\.deferred_at >= ist_day_start, false\)',
      'and c.deferred_at is null'
    );
    if n = d then raise exception 'claim_next_lead: pattern not found, nothing changed'; end if;
    execute n;
  end if;
end
$patch$;

-- Park the open No-answer leads that are in the pool now.
update public.leads
   set deferred_at = now()
 where not is_closed
   and last_status = 'NO_ANSWER'
   and assigned_to is null
   and deferred_at is null;
