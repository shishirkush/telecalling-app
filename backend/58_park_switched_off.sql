-- =====================================================================
--  MIGRATION 58 — "Switched off" is parked exactly like "No answer"
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- Migrations 56 and 57 park a lead (leads.deferred_at) when its outcome is
-- NO_ANSWER, so claim_next_lead() never hands it out again until the owner
-- explicitly releases it. SWITCHED_OFF now gets the same treatment:
--   * saving SWITCHED_OFF parks the lead (leads_defer_unsaved);
--   * a new lead with the same mobile as a parked No-answer / Switched-off lead
--     arrives parked (leads_park_known_no_answer);
--   * open Switched-off leads already in the pool are parked now, and so are
--     untouched duplicate rows of any parked No-answer / Switched-off number.
--
-- Release only with the owner's explicit approval:
--     update public.leads set deferred_at = null where ... ;

create or replace function public.leads_defer_unsaved()
returns trigger
language plpgsql
security definer set search_path = public
as $$
begin
  if new.last_called_at is distinct from old.last_called_at then
    -- an outcome was saved: NO_ANSWER / SWITCHED_OFF park the lead, anything else clears it
    new.deferred_at := case when new.last_status in ('NO_ANSWER', 'SWITCHED_OFF') then now() else null end;
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

create or replace function public.leads_park_known_no_answer()
returns trigger
language plpgsql
security definer set search_path = public
as $$
begin
  if new.deferred_at is null and exists (
    select 1 from public.leads l
     where l.mobile = new.mobile
       and l.last_status in ('NO_ANSWER', 'SWITCHED_OFF')
       and l.deferred_at is not null
  ) then
    new.deferred_at := now();
  end if;
  return new;
end;
$$;

-- Park the open Switched-off leads that are in the pool now.
update public.leads
   set deferred_at = now()
 where not is_closed
   and last_status = 'SWITCHED_OFF'
   and assigned_to is null
   and deferred_at is null;

-- And untouched duplicate rows of any parked No-answer / Switched-off number.
update public.leads u
   set deferred_at = now()
 where not u.is_closed
   and u.assigned_to is null
   and u.deferred_at is null
   and u.attempts = 0
   and right(regexp_replace(u.mobile, '\D', '', 'g'), 10) in (
     select right(regexp_replace(p.mobile, '\D', '', 'g'), 10)
       from public.leads p
      where p.last_status in ('NO_ANSWER', 'SWITCHED_OFF') and p.deferred_at is not null
   );
