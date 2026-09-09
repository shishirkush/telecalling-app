-- =====================================================================
--  MIGRATION 11 — random pick instead of sequential-by-id
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run. Database-only — no app rebuild needed.
-- =====================================================================
--
-- claim_next_lead()'s final tiebreaker was `c.id asc`, so within any
-- given priority tier (same attempts count, same last_called_at) leads
-- were always served lowest-id-first — i.e. in CSV row order, the same
-- order for every agent, every time. Replacing it with `random()` keeps
-- every existing priority (due callbacks first, untouched leads before
-- retried ones, least-recently-touched first — see
-- backend/08_fix_queue_reorder.sql) and only randomizes which lead wins
-- among ties, which in practice is almost always "which untouched lead
-- comes next" — the common case for a fresh batch.

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
       -- random pick among whatever ties on everything above
       random()
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
