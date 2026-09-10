-- =====================================================================
--  MIGRATION 13 — admin can turn a batch on/off for calling
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- Until now every open, unassigned lead in the whole pool was fair game
-- for claim_next_lead() — there was no way to say "only serve leads from
-- Batch2TeleApp right now". This adds a per-batch on/off switch admin
-- controls from the dashboard's Database section, next to Delete.
--
-- Leads with no batch (batch is null) are always eligible — there is no
-- name to toggle them by, and the null-batch row in the dashboard is
-- just v_batch_summary's display sentinel, not a real batch.

create table if not exists public.batch_settings (
  batch      text primary key,
  is_active  boolean     not null default true,
  updated_at timestamptz not null default now()
);

-- Backfill every batch that already exists so the dashboard's toggle
-- list is complete immediately, and every existing batch starts active
-- — this migration must not silently stop any calling that was already
-- happening.
insert into public.batch_settings (batch)
select distinct batch from public.leads where batch is not null
on conflict (batch) do nothing;

alter table public.batch_settings enable row level security;

-- Same tier as CSV import and "Create agent": admin-only, both to read
-- and to write. The dashboard's CSV importer upserts a (batch, is_active
-- default true) row here for every newly-imported batch — same RLS gate
-- it already goes through to insert into `leads` directly.
drop policy if exists "admin manages batch settings" on public.batch_settings;
create policy "admin manages batch settings"
  on public.batch_settings for all
  using (public.is_admin())
  with check (public.is_admin());

-- ---------------------------------------------------------------------
-- claim_next_lead(): skip leads whose batch has been switched off.
-- Everything else about the ordering is unchanged from
-- backend/11_random_call_order.sql — this only adds a filter.
-- ---------------------------------------------------------------------

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
       -- skip leads whose batch admin has switched off — a lead with no
       -- batch has no switch, so it's always eligible; a batch with no
       -- row here yet defaults to active too (see backend/13_batch_active_toggle.sql)
       and (
         c.batch is null
         or coalesce(
              (select bs.is_active from public.batch_settings bs where bs.batch = c.batch),
              true
            )
       )
     order by
       -- due callbacks first
       (c.callback_at is not null) desc,
       c.callback_at asc nulls last,
       -- then never-attempted leads before ones already tried once —
       -- see backend/08_fix_queue_reorder.sql for why: without this, a
       -- lead released back to the pool (SWITCHED_OFF, NO_ANSWER) could
       -- be immediately re-served on the very next claim if it had a low id.
       c.attempts asc,
       c.last_called_at asc nulls first,
       -- random, not sequential-by-id — see backend/11_random_call_order.sql
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

-- ---------------------------------------------------------------------
-- v_batch_summary: append is_active so the dashboard can render the
-- toggle's current state. Appended at the end of the SELECT list on
-- purpose — CREATE OR REPLACE VIEW cannot reorder or insert columns
-- mid-list (42P16), only add at the end.
-- ---------------------------------------------------------------------

create or replace view public.v_batch_summary
with (security_invoker = true) as
select
  coalesce(l.batch, '(no batch)')        as batch,
  count(*)                               as total,
  count(*) filter (where l.attempts > 0) as called,
  count(*) filter (where l.attempts = 0) as pending,
  count(*) filter (where l.is_closed)    as closed,
  min(l.created_at)                      as imported_at,
  bool_and(coalesce(bs.is_active, true)) as is_active
from public.leads l
left join public.batch_settings bs on bs.batch = l.batch
group by l.batch
order by min(l.created_at) desc;
