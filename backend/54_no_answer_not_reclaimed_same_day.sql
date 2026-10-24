-- =====================================================================
--  MIGRATION 54 — a "No answer" lead stays out of the pool for the rest of the day
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- save_disposition() hands a NO_ANSWER lead back to the pool (assigned_to =
-- null, still open) so it gets another try. Until now any agent could claim it
-- again straight away, so the same customer was rung two or three times within
-- the hour (e.g. leads 48399 and 81325 on 24 Oct 2026 went to two agents each).
--
-- claim_next_lead() now:
--   1. skips a lead whose last outcome is NO_ANSWER and was saved today (IST);
--   2. from the next day, serves every NO_ANSWER lead LAST — after all other
--      open leads, whatever their attempt count — so they are the bottom of the
--      pile. Due callbacks still come first, as before. A lead stops being
--      "last" as soon as it gets any other outcome.
--
-- No new column or data change: it uses leads.last_status / last_called_at,
-- which save_disposition() already keeps current. The only other change to the
-- function (vs migration 39) is the extra ORDER BY key, so the claim-order
-- index below is rebuilt to include it (otherwise every claim would sort the
-- whole pool again — the problem migration 39 fixed).

create index if not exists leads_claim_order_v2_idx
  on public.leads (
    (callback_at is not null) desc,
    callback_at asc nulls last,
    (coalesce(last_status = 'NO_ANSWER', false)) asc,
    attempts asc,
    last_called_at asc nulls first,
    random_rank
  )
  where is_closed = false;

drop index if exists public.leads_claim_order_idx;

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

  with picked as (
    select c.id
      from public.leads c
     where c.is_closed = false
       and (c.assigned_to is null
            or c.assigned_to = auth.uid()
            or c.locked_at < now() - stale_after)
       and (c.callback_at is null or c.callback_at <= now())
       and not (c.last_status = 'NO_ANSWER' and c.last_called_at >= ist_day_start)
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
       coalesce(c.last_status = 'NO_ANSWER', false) asc,
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
