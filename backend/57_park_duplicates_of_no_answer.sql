-- =====================================================================
--  MIGRATION 57 — a customer who already gave "No answer" isn't called again
--  via a duplicate lead row
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- Migration 56 parks a lead once its outcome is NO_ANSWER, so that lead is
-- never handed out again. But the same mobile number can also sit on a
-- second, untouched lead row (a re-upload, another batch), and that row would
-- be called as if the customer had never been tried. 339 such rows existed
-- when this was written.
--
--   * New leads: a lead inserted with the same mobile as a parked No-answer
--     lead is parked on arrival (leads_park_known_no_answer, exact-mobile match
--     so it can use leads_mobile_idx during bulk uploads).
--   * Existing leads: untouched, unassigned open rows whose mobile (last 10
--     digits) matches a parked No-answer lead are parked now.
--
-- Release the same way as migration 56 (explicit owner approval only):
--     update public.leads set deferred_at = null where ...;

create or replace function public.leads_park_known_no_answer()
returns trigger
language plpgsql
security definer set search_path = public
as $$
begin
  if new.deferred_at is null and exists (
    select 1 from public.leads l
     where l.mobile = new.mobile
       and l.last_status = 'NO_ANSWER'
       and l.deferred_at is not null
  ) then
    new.deferred_at := now();
  end if;
  return new;
end;
$$;

drop trigger if exists leads_park_known_no_answer on public.leads;
create trigger leads_park_known_no_answer
  before insert on public.leads
  for each row execute function public.leads_park_known_no_answer();

update public.leads u
   set deferred_at = now()
 where not u.is_closed
   and u.assigned_to is null
   and u.deferred_at is null
   and u.attempts = 0
   and right(regexp_replace(u.mobile, '\D', '', 'g'), 10) in (
     select right(regexp_replace(p.mobile, '\D', '', 'g'), 10)
       from public.leads p
      where p.last_status = 'NO_ANSWER' and p.deferred_at is not null
   );
