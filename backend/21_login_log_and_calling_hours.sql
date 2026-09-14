-- =====================================================================
--  MIGRATION 21 — calling-hours gate on claiming leads, and a login/
--  logout activity log (Android + web) for the dashboard.
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Calling hours: 9:00 AM – 7:00 PM IST, enforced where a lead is
--    actually handed out. Deliberately scoped to claim_next_lead() only
--    — an agent already mid-call at 7:01 PM can still save that
--    disposition; this only stops NEW leads being allocated outside the
--    window, not their work being cut off mid-call.
--
--    Postgres has no IST zone abbreviation, but 'Asia/Kolkata' is a real
--    IANA zone name and is what `at time zone` expects; India has had a
--    single fixed UTC+5:30 offset with no DST since 1945, so this needs
--    no seasonal adjustment. Checked server-side — the one place a
--    device's own (spoofable, sometimes just wrong) clock can't be
--    trusted — same reasoning as every other security check in this
--    schema.
-- ---------------------------------------------------------------------

create or replace function public.claim_next_lead()
returns setof public.leads
language plpgsql
security definer set search_path = public
as $$
declare
  stale_after constant interval := '30 minutes';
  ist_time    time := (now() at time zone 'Asia/Kolkata')::time;
begin
  if not exists (select 1 from public.profiles where id = auth.uid() and active) then
    raise exception 'inactive or unknown user';
  end if;

  if ist_time < time '09:00' or ist_time >= time '19:00' then
    raise exception 'Leads can only be claimed between 9:00 AM and 7:00 PM IST.';
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
-- 2. Login/logout activity log — Android + web both call this RPC at
--    sign-in and sign-out. There is no way to catch every real logout
--    (app force-killed, tab closed, phone battery dies) without a
--    heartbeat/keep-alive system this app doesn't have; this records
--    every logout the client got a chance to report, not a guarantee of
--    "still signed in" between a login row and its next logout row.
-- ---------------------------------------------------------------------

create table public.login_log (
  id          bigserial   primary key,
  agent_id    uuid        not null references public.profiles(id),
  event       text        not null check (event in ('login', 'logout')),
  platform    text,                       -- 'android' | 'web', free text on purpose
  occurred_at timestamptz not null default now()
);

create index login_log_agent_idx on public.login_log (agent_id, occurred_at desc);

alter table public.login_log enable row level security;

create policy "agents insert own login events"
  on public.login_log for insert
  with check (agent_id = auth.uid());

-- Same reporting scope as recent calls / callbacks — see
-- backend/09_manager_scoping.sql's manages_agent(): a manager sees it
-- for their assigned agents, admin sees everyone.
create policy "read login log"
  on public.login_log for select
  using (public.manages_agent(agent_id));

create or replace function public.log_login_event(
  p_event    text,
  p_platform text default null
)
returns void
language plpgsql
security definer set search_path = public
as $$
begin
  if p_event not in ('login', 'logout') then
    raise exception 'invalid event: %', p_event;
  end if;
  if not exists (select 1 from public.profiles where id = auth.uid()) then
    raise exception 'unknown user';
  end if;

  insert into public.login_log (agent_id, event, platform)
  values (auth.uid(), p_event, p_platform);
end;
$$;

grant execute on function public.log_login_event(text, text) to authenticated;

create or replace view public.v_login_log
with (security_invoker = true) as
select
  l.id,
  p.full_name,
  p.login_id,
  l.event,
  l.platform,
  l.occurred_at
from public.login_log l
join public.profiles p on p.id = l.agent_id
order by l.occurred_at desc;
