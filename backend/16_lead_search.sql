-- =====================================================================
--  MIGRATION 16 — whole-database customer lookup by mobile or PAN
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- The ask: a customer calls an agent back, but the lead is no longer in
-- that agent's queue (closed weeks ago, or originally worked by someone
-- else entirely) — the agent has no way to pull up name/address/etc.
-- without knowing which batch or agent it belongs to.
--
-- This is a deliberate, audited exception to "agents read assigned
-- leads" (backend/01_schema.sql / 09_manager_scoping.sql), not a
-- relaxation of it — RLS on `leads` is untouched. search_lead() is
-- security definer, so it bypasses that RLS on purpose, and everything
-- about its shape is built to keep that bypass narrow:
--
--   * EXACT match only (a full 10-digit mobile or a validly-shaped PAN)
--     — no ILIKE, no partial/substring search. An agent has to already
--     have the customer's actual mobile or PAN in hand (which is
--     exactly the stated use case — the customer just said it on the
--     phone), not be able to fish for records.
--   * Every call is logged to lead_search_log, matched-count included,
--     with only the last 4 characters of the query kept — enough for
--     an admin to correlate "did this search find the right person"
--     without the log itself becoming a second copy of PII.
--   * Rate-limited per agent (30/hour) so even a scripted loop hitting
--     this RPC directly with a stolen token can only harvest a couple
--     dozen exact-match records an hour, not the whole database.
--
-- Read-only by design at the client: the app never lets a search result
-- be edited or dispositioned unless it is separately re-claimed through
-- the normal assignment path. This RPC does not care either way — that
-- restriction lives entirely in the Android client, same as it already
-- does for save_disposition needing assigned_to = auth.uid().

create table public.lead_search_log (
  id           bigserial   primary key,
  agent_id     uuid        not null references public.profiles(id),
  query_type   text        not null,   -- 'mobile' or 'pan'
  query_masked text        not null,   -- e.g. "xxxxxx4321" — last 4 chars only
  match_count  int         not null,
  searched_at  timestamptz not null default now()
);

create index lead_search_log_idx on public.lead_search_log (agent_id, searched_at desc);

alter table public.lead_search_log enable row level security;

-- No insert policy: only the security-definer RPC below ever writes a
-- row, same pattern as call_dispositions being append-only through
-- save_disposition rather than direct client inserts.
drop policy if exists "read search log" on public.lead_search_log;
create policy "read search log"
  on public.lead_search_log for select
  using (public.manages_agent(agent_id));

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
  if v_recent_count >= 30 then
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

-- At-a-glance abuse detection for the dashboard: an agent running far
-- more searches than they have calls, or a wall of zero-match
-- searches, reads as someone guessing rather than looking up a real
-- callback. All-time, admin (and their managers) only via manages_agent.
create or replace view public.v_search_activity
with (security_invoker = true) as
select
  p.login_id,
  p.full_name,
  count(*)                                  as searches,
  count(*) filter (where s.match_count = 0) as zero_match_searches,
  max(s.searched_at)                        as last_search
from public.lead_search_log s
join public.profiles p on p.id = s.agent_id
group by p.login_id, p.full_name
order by searches desc;
