-- =====================================================================
--  MIGRATION 08 — stop "Next lead" from immediately re-serving the lead
--  an agent just released
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run. Database-only — no app rebuild needed, since the
--  client just calls the same claim_next_lead() RPC either way.
-- =====================================================================
--
-- Bug: claim_next_lead() ordered strictly by (due callback, then lead id).
-- A lead given SWITCHED_OFF or NO_ANSWER goes back into the pool with
-- assigned_to = null, but its id doesn't change — so if it was the
-- lowest-id eligible lead, the very next "Next lead" tap picked the exact
-- same lead right back up. Reported as: "No answer selected, same number
-- shows up again."
--
-- Fix: order by attempts first (untouched leads before ones that have
-- already been tried at least once), then by how long ago it was last
-- called (nulls — never called — first). A lead just dispositioned has
-- attempts incremented and last_called_at = now(), so it now sorts
-- behind every fresher lead and only comes back around once those are
-- exhausted, instead of bouncing straight back.

create or replace function public.claim_next_lead()
returns setof public.leads
language plpgsql
security definer set search_path = public
as $$
declare
  stale_after constant interval := '30 minutes';
begin
  if not exists (select 1 from public.profiles where id = auth.uid() and active) then
    raise exception 'inactive or unknown user';
  end if;

  return query
  with picked as (
    select c.id
      from public.leads c
     where c.is_closed = false
       -- unassigned, already mine, or abandoned by someone else
       and (c.assigned_to is null
            or c.assigned_to = auth.uid()
            or c.locked_at < now() - stale_after)
       -- skip callbacks that are not due yet
       and (c.callback_at is null or c.callback_at <= now())
     order by
       -- due callbacks first
       (c.callback_at is not null) desc,
       c.callback_at asc nulls last,
       -- then never-attempted leads before ones already tried once
       c.attempts asc,
       -- among equally-attempted leads, the one least recently touched
       -- (never-called sorts first, so a just-released lead goes last)
       c.last_called_at asc nulls first,
       c.id asc
     limit 1
     for update skip locked
  ),
  claimed as (
    update public.leads l
       set assigned_to = auth.uid(),
           locked_by   = auth.uid(),
           locked_at   = now()
      from picked p
     where l.id = p.id
    returning l.*
  )
  select * from claimed;
end;
$$;
