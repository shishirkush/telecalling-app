-- =====================================================================
--  MIGRATION 55 — unsaved leads go to the bottom; Call-later stays with its agent
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- 1. A lead an agent CALLED but never saved an outcome for no longer goes
--    back to the pool at its old position. When it is released (the 30-minute
--    stale lock below, a campaign switch, an archived agent, an admin
--    release) it is marked deferred_at = now(). claim_next_lead() then (a) does
--    not hand it out for the rest of that day and (b) serves it after every
--    other open lead — same "bottom of the pile" as a NO_ANSWER lead. Saving
--    any outcome clears the mark.
--
-- 2. A CALL_LATER lead stays with the agent who set the callback:
--      - the stale lock only unlocks it (it keeps assigned_to);
--      - switch_campaign() and admin_set_agent_campaign() no longer release it;
--      - callbacks released earlier by those paths (open, callback set,
--        nobody assigned) are handed back to the agent who set them.
--    Deliberate exception: set_agent_active(false) (archiving an agent) still
--    releases everything, so an archived agent's leads aren't stranded.
--
-- Also fixes migration 54: its NO_ANSWER filter used `not (a and b)` which is
-- NULL for a lead that has never been called, so claim_next_lead() skipped
-- every untouched lead. It is wrapped in coalesce(..., false) here.

alter table public.leads add column if not exists deferred_at timestamptz;

-- The stale-lock release below looks up locked leads on every claim.
create index if not exists leads_locked_at_idx
  on public.leads (locked_at) where locked_at is not null;

-- Marks a released-but-called lead, clears the mark when an outcome is saved.
create or replace function public.leads_defer_unsaved()
returns trigger
language plpgsql
security definer set search_path = public
as $$
begin
  if new.last_called_at is distinct from old.last_called_at then
    new.deferred_at := null;                       -- an outcome was saved
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

drop trigger if exists leads_defer_unsaved on public.leads;
create trigger leads_defer_unsaved
  before update on public.leads
  for each row
  when (old.assigned_to is distinct from new.assigned_to
        or old.last_called_at is distinct from new.last_called_at)
  execute function public.leads_defer_unsaved();

-- Bottom-of-the-pile ordering key now covers deferred leads too.
create index if not exists leads_claim_order_v3_idx
  on public.leads (
    (callback_at is not null) desc,
    callback_at asc nulls last,
    ((coalesce(last_status = 'NO_ANSWER', false) or deferred_at is not null)) asc,
    attempts asc,
    last_called_at asc nulls first,
    random_rank
  )
  where is_closed = false;

create or replace function public.claim_next_lead()
returns setof public.leads
language plpgsql
security definer set search_path = public
as $$
declare
  stale_after      constant interval := '30 minutes';
  unresolved_grace constant interval := '10 minutes';
  claim_cooldown   constant interval := '30 seconds';
  ist_time         time := (now() at time zone 'Asia/Kolkata')::time;
  ist_day_start    timestamptz := date_trunc('day', now() at time zone 'Asia/Kolkata') at time zone 'Asia/Kolkata';
  blocking_lead    record;
  v_campaign_id    bigint;
  v_claimed        public.leads;
begin
  if not exists (select 1 from public.profiles where id = auth.uid() and active) then
    raise exception 'inactive or unknown user';
  end if;

  if ist_time < time '09:00' or ist_time >= time '19:00' then
    raise exception 'Leads can only be claimed between 9:00 AM and 7:00 PM IST.';
  end if;

  if not public.is_admin() then
    if exists (
      select 1 from public.profiles
       where id = auth.uid() and last_claim_at > now() - claim_cooldown
    ) then
      raise exception 'Wait a moment before claiming another lead.';
    end if;

    select l.id, l.name
      into blocking_lead
      from public.leads l
      join public.call_attempts a
        on a.lead_id = l.id and a.agent_id = auth.uid()
     where l.assigned_to = auth.uid()
       and a.attempted_at > coalesce(l.last_called_at, '-infinity'::timestamptz)
       and a.attempted_at <= now() - unresolved_grace
     order by a.attempted_at asc
     limit 1;

    if blocking_lead.id is not null then
      raise exception 'Save the outcome for % before claiming another lead.',
        coalesce(blocking_lead.name, 'the lead you called');
    end if;
  end if;

  select current_campaign_id into v_campaign_id from public.profiles where id = auth.uid();

  if not public.is_admin() and v_campaign_id is null then
    raise exception 'Pick a campaign before claiming leads.';
  end if;

  -- Release locks left behind for 30+ minutes. A Call-later lead only unlocks
  -- (it stays with its agent); any other lead goes back to the pool, and the
  -- trigger above marks it deferred if it was called but never saved.
  update public.leads
     set assigned_to = case when callback_at is null then null else assigned_to end,
         locked_by   = null,
         locked_at   = null
   where locked_at < now() - stale_after
     and is_closed = false;

  with picked as (
    select c.id
      from public.leads c
     where c.is_closed = false
       and (c.assigned_to is null or c.assigned_to = auth.uid())
       and (c.callback_at is null or c.callback_at <= now())
       and not coalesce(c.last_status = 'NO_ANSWER' and c.last_called_at >= ist_day_start, false)
       and not coalesce(c.deferred_at >= ist_day_start, false)
       and (
         public.is_admin()
         or exists (
              select 1 from public.batch_settings bs
               where bs.batch = c.batch
                 and bs.campaign_id = v_campaign_id
                 and bs.is_active
            )
       )
     order by
       (c.callback_at is not null) desc,
       c.callback_at asc nulls last,
       (coalesce(c.last_status = 'NO_ANSWER', false) or c.deferred_at is not null) asc,
       c.attempts asc,
       c.last_called_at asc nulls first,
       c.random_rank
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
  select * into v_claimed from claimed;

  if v_claimed.id is not null then
    update public.profiles set last_claim_at = now() where id = auth.uid();
    return next v_claimed;
  end if;

  return;
end;
$$;

drop index if exists public.leads_claim_order_v2_idx;

-- Switching campaign (by the agent or by an admin) no longer releases Call-later
-- leads. Patches the live definitions in place; skipped if already patched.
do $patch$
declare d text; n text;
begin
  d := pg_get_functiondef('public.switch_campaign(bigint)'::regprocedure);
  if d !~ 'callback_at is null' then
    n := regexp_replace(d, '(where assigned_to = auth\.uid\(\)\s+and is_closed = false)', E'\\1\n       and callback_at is null');
    if n = d then raise exception 'switch_campaign: pattern not found, nothing changed'; end if;
    execute n;
  end if;

  d := pg_get_functiondef('public.admin_set_agent_campaign(uuid,bigint)'::regprocedure);
  if d !~ 'callback_at is null' then
    n := regexp_replace(d, '(and l\.is_closed = false)', E'\\1\n     and l.callback_at is null');
    if n = d then raise exception 'admin_set_agent_campaign: pattern not found, nothing changed'; end if;
    execute n;
  end if;
end
$patch$;

-- Hand back Call-later leads that earlier releases left with nobody: to the
-- agent who saved that callback, if they are still active.
update public.leads l
   set assigned_to = d.agent_id
  from (
    select distinct on (lead_id) lead_id, agent_id, status
      from public.call_dispositions
     order by lead_id, called_at desc
  ) d
  join public.profiles p on p.id = d.agent_id and p.active
 where l.id = d.lead_id
   and d.status = 'CALL_LATER'
   and l.is_closed = false
   and l.callback_at is not null
   and l.assigned_to is null;
