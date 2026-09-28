-- =====================================================================
--  MIGRATION 34 — tighten claim/search rate limits
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- From the 2026-09-28 security review: an agent (real, phished, or —
-- before that same review disabled public signups — self-created for
-- free) can read every PII field on any lead they claim. Neither
-- search_lead() nor claim_next_lead() had a rate limit tight enough to
-- meaningfully cap that blast radius per credential. This migration
-- tightens both.
--
-- ---------------------------------------------------------------------
-- 1. search_lead(): 30/hour -> 5/hour
-- ---------------------------------------------------------------------
-- Checked real usage first rather than guessing: the highest any real
-- agent has ever hit in one hour, across the full history of
-- lead_search_log, is 5 (dilkhush, one occasion) — everything else is
-- 4 or fewer. 5/hour would not have blocked a single legitimate lookup
-- to date, while cutting the abuse ceiling 6x.

create or replace function public.search_lead(
  p_mobile text default null,
  p_pan    text default null
)
returns setof public.leads
language plpgsql
security definer set search_path = public
as $$
declare
  v_cleaned_mobile text;
  v_cleaned_pan    text;
  v_query_type     text;
  v_query_masked   text;
  v_match_count    int;
  v_recent_count   int;
begin
  if not exists (select 1 from public.profiles where id = auth.uid() and active) then
    raise exception 'inactive or unknown user';
  end if;

  select count(*) into v_recent_count
    from public.lead_search_log
   where agent_id = auth.uid() and searched_at > now() - interval '1 hour';
  if v_recent_count >= 5 then
    raise exception 'Too many searches this hour — try again shortly.';
  end if;

  if coalesce(btrim(p_mobile), '') <> '' then
    v_cleaned_mobile := right(regexp_replace(p_mobile, '[^0-9]', '', 'g'), 10);
    if length(v_cleaned_mobile) <> 10 then
      raise exception 'Enter a valid 10-digit mobile number.';
    end if;
    v_query_type := 'mobile';
    v_query_masked := 'xxxxxx' || right(v_cleaned_mobile, 4);
  elsif coalesce(btrim(p_pan), '') <> '' then
    v_cleaned_pan := upper(btrim(p_pan));
    if v_cleaned_pan !~ '^[A-Z]{5}[0-9]{4}[A-Z]$' then
      raise exception 'Enter a valid PAN, e.g. ABCDE1234F.';
    end if;
    v_query_type := 'pan';
    v_query_masked := 'xxxxxx' || right(v_cleaned_pan, 4);
  else
    raise exception 'Enter a mobile number or PAN to search.';
  end if;

  select count(*) into v_match_count
    from public.leads l
   where (v_cleaned_mobile is not null
          and right(regexp_replace(l.mobile, '[^0-9]', '', 'g'), 10) = v_cleaned_mobile)
      or (v_cleaned_pan is not null and upper(l.pan) = v_cleaned_pan);

  insert into public.lead_search_log (agent_id, query_type, query_masked, match_count)
  values (auth.uid(), v_query_type, v_query_masked, v_match_count);

  return query
  select l.* from public.leads l
   where (v_cleaned_mobile is not null
          and right(regexp_replace(l.mobile, '[^0-9]', '', 'g'), 10) = v_cleaned_mobile)
      or (v_cleaned_pan is not null and upper(l.pan) = v_cleaned_pan)
   order by l.created_at desc
   limit 20;
end;
$$;


-- ---------------------------------------------------------------------
-- 2. claim_next_lead(): one claim per 30 seconds
-- ---------------------------------------------------------------------
-- This costs a legitimate agent nothing: save_disposition() already
-- refuses an outcome until 30s after the logged call attempt (migration
-- 23), and a lead is only released back to the pool through a
-- disposition — so the fastest two DIFFERENT leads can honestly be
-- claimed back-to-back is already >=30s. This cooldown only blocks what
-- was already impossible to do honestly: claim -> release -> claim in
-- rapid succession with no real call in between.
--
-- leads.locked_at is not a reliable signal for this — save_disposition()
-- clears it to null on every disposition regardless of outcome, so it
-- doesn't survive across a claim/dispose cycle. A dedicated timestamp
-- that's stamped only on a successful claim (and never cleared) is
-- needed instead.

alter table public.profiles add column if not exists last_claim_at timestamptz;

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
       c.attempts asc,
       c.last_called_at asc nulls first,
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
  select * into v_claimed from claimed;

  if v_claimed.id is not null then
    update public.profiles set last_claim_at = now() where id = auth.uid();
    return next v_claimed;
  end if;

  return;
end;
$$;
