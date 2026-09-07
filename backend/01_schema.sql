-- =====================================================================
--  TELECALLING APP — DATABASE SCHEMA
--  Target: Supabase (PostgreSQL) free tier
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. ENUMS
-- ---------------------------------------------------------------------

create type user_role as enum ('agent', 'supervisor');

-- The call outcomes. NO_ANSWER added in migration 07 — see backend/07_no_answer_status.sql
-- for why it's a separate `alter type ... add value` on a live database
-- rather than just an edit to this file.
create type call_status as enum (
  'SWITCHED_OFF',
  'NO_ANSWER',
  'WRONG_NUMBER',
  'NOT_INTERESTED',
  'CALL_LATER',
  'LEAD'
);

-- Sub-qualification, only meaningful when call_status = 'LEAD'.
create type lead_quality as enum ('HOT', 'WARM', 'COLD');


-- ---------------------------------------------------------------------
-- 2. PROFILES  (extends Supabase's built-in auth.users)
-- ---------------------------------------------------------------------

create table public.profiles (
  id          uuid primary key references auth.users(id) on delete cascade,
  full_name   text,
  employee_id text,
  role        user_role   not null default 'agent',
  active      boolean     not null default true,
  created_at  timestamptz not null default now()
);

-- Auto-create a profile row whenever a new auth user is created.
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer set search_path = public
as $$
begin
  insert into public.profiles (id, full_name, role)
  values (
    new.id,
    coalesce(new.raw_user_meta_data->>'full_name', split_part(new.email, '@', 1)),
    coalesce((new.raw_user_meta_data->>'role')::user_role, 'agent')
  );
  return new;
end;
$$;

create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();


-- ---------------------------------------------------------------------
-- 3. LEADS  (the data the caller sees)
-- ---------------------------------------------------------------------

create table public.leads (
  id                  bigserial primary key,

  -- ---- Fields visible to the caller (per spec) ----
  name                text        not null,
  pan                 text,
  mobile              text        not null,
  email               text,
  dob                 date,
  annual_income_range text,
  company             text,               -- renamed from "PP Code Company Name"
  ici_cr_lmt          numeric(14,2),      -- ICICI credit limit

  -- ---- Workflow / assignment ----
  assigned_to   uuid references public.profiles(id) on delete set null,
  locked_by     uuid references public.profiles(id) on delete set null,
  locked_at     timestamptz,

  -- ---- Denormalised latest outcome (keeps the queue query cheap) ----
  last_status    call_status,
  last_remarks   text,
  last_called_at timestamptz,
  attempts       int         not null default 0,
  callback_at    timestamptz,             -- set when status = CALL_LATER
  is_closed      boolean     not null default false,

  -- ---- Provenance ----
  batch      text,                        -- e.g. 'ICICI_PREAPPROVED_SEP2026'
  created_at timestamptz not null default now()
);

-- Queue lookups: "give me this agent's open leads, callbacks first".
create index leads_queue_idx
  on public.leads (is_closed, assigned_to, callback_at);

-- Dedupe / search by phone.
create index leads_mobile_idx on public.leads (mobile);

-- Prevent the same number being loaded twice within one batch.
create unique index leads_batch_mobile_uniq
  on public.leads (batch, mobile)
  where batch is not null;


-- ---------------------------------------------------------------------
-- 4. CALL DISPOSITIONS  (the caller's output — one row per attempt)
-- ---------------------------------------------------------------------
-- This is an append-only history. leads.last_status is only a cache of
-- the most recent row here. Never update a disposition; insert a new one.

create table public.call_dispositions (
  id           bigserial primary key,
  lead_id      bigint      not null references public.leads(id) on delete cascade,
  agent_id     uuid        not null references public.profiles(id),

  status       call_status not null,
  lead_quality lead_quality,             -- required iff status = 'LEAD'
  remarks      text,                     -- free-text, always available
  callback_at  timestamptz,              -- required iff status = 'CALL_LATER'

  sim_slot     int,                      -- which SIM placed the call (1 or 2)
  called_at    timestamptz not null default now(),
  created_at   timestamptz not null default now(),

  -- Enforce the conditional fields at the database level rather than
  -- trusting the client to do it.
  constraint lead_quality_only_for_leads check (
    (status = 'LEAD' and lead_quality is not null)
    or (status <> 'LEAD' and lead_quality is null)
  ),
  constraint callback_required_for_call_later check (
    (status = 'CALL_LATER' and callback_at is not null)
    or (status <> 'CALL_LATER')
  ),
  constraint remarks_length check (char_length(remarks) <= 2000)
);

create index dispositions_agent_idx on public.call_dispositions (agent_id, called_at desc);
create index dispositions_lead_idx  on public.call_dispositions (lead_id, called_at desc);
create index dispositions_status_idx on public.call_dispositions (status, called_at desc);


-- ---------------------------------------------------------------------
-- 5. ACCESS LOG
-- ---------------------------------------------------------------------
-- You chose to show PAN / DOB / income / credit limit unmasked. That
-- removes the technical control, so this is the compensating one: every
-- time an agent opens a lead record, it is recorded. If data ever leaks
-- you can answer "who saw this record and when".

create table public.lead_access_log (
  id        bigserial   primary key,
  lead_id   bigint      not null references public.leads(id) on delete cascade,
  agent_id  uuid        not null references public.profiles(id),
  viewed_at timestamptz not null default now()
);

create index lead_access_log_idx on public.lead_access_log (agent_id, viewed_at desc);


-- ---------------------------------------------------------------------
-- 6. HELPER: is the current user a supervisor?
-- ---------------------------------------------------------------------

create or replace function public.is_supervisor()
returns boolean
language sql
stable
security definer set search_path = public
as $$
  select exists (
    select 1 from public.profiles
    where id = auth.uid() and role = 'supervisor' and active
  );
$$;


-- ---------------------------------------------------------------------
-- 7. ROW LEVEL SECURITY
-- ---------------------------------------------------------------------

alter table public.profiles          enable row level security;
alter table public.leads             enable row level security;
alter table public.call_dispositions enable row level security;
alter table public.lead_access_log   enable row level security;

-- --- profiles ---
create policy "read own profile"
  on public.profiles for select
  using (id = auth.uid() or public.is_supervisor());

create policy "update own name"
  on public.profiles for update
  using (id = auth.uid())
  with check (id = auth.uid());

-- --- leads ---
-- An agent may ONLY read leads currently assigned to them. They cannot
-- browse or export the wider database.
create policy "agents read assigned leads"
  on public.leads for select
  using (assigned_to = auth.uid() or public.is_supervisor());

-- Agents never write to leads directly; the RPCs below (security definer)
-- do it for them. Supervisors can manage the pool.
create policy "supervisors manage leads"
  on public.leads for all
  using (public.is_supervisor())
  with check (public.is_supervisor());

-- --- call_dispositions ---
create policy "agents insert own dispositions"
  on public.call_dispositions for insert
  with check (agent_id = auth.uid());

create policy "read own dispositions"
  on public.call_dispositions for select
  using (agent_id = auth.uid() or public.is_supervisor());

-- --- lead_access_log ---
create policy "agents insert own access log"
  on public.lead_access_log for insert
  with check (agent_id = auth.uid());

create policy "supervisors read access log"
  on public.lead_access_log for select
  using (public.is_supervisor());


-- ---------------------------------------------------------------------
-- 8. RPC: claim the next lead  (atomic — no two agents get the same one)
-- ---------------------------------------------------------------------
-- FOR UPDATE SKIP LOCKED is the important bit. Without it, two agents
-- hitting "next lead" at the same instant both read the same row and both
-- call the same customer. SKIP LOCKED makes concurrent callers step over
-- each other's rows instead of blocking or colliding.

-- Returns SETOF so an empty queue is an empty JSON array rather than a row
-- of nulls — unambiguous for the client to interpret.

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
       -- then never-attempted leads before ones already tried once —
       -- see backend/08_fix_queue_reorder.sql for why: without this, a
       -- lead released back to the pool (SWITCHED_OFF, NO_ANSWER) could
       -- be immediately re-served on the very next claim if it had a low id.
       c.attempts asc,
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


-- ---------------------------------------------------------------------
-- 9. RPC: save a disposition  (insert history + update lead, atomically)
-- ---------------------------------------------------------------------

create or replace function public.save_disposition(
  p_lead_id      bigint,
  p_status       call_status,
  p_lead_quality lead_quality default null,
  p_remarks      text         default null,
  p_callback_at  timestamptz  default null,
  p_sim_slot     int          default null
)
returns bigint                 -- id of the disposition row that was written
language plpgsql
security definer set search_path = public
as $$
declare
  new_id bigint;
begin
  -- The agent must actually hold this lead. Without this check any agent
  -- could post outcomes against any lead id they guessed.
  if not exists (
    select 1 from public.leads
     where id = p_lead_id
       and (assigned_to = auth.uid() or public.is_supervisor())
  ) then
    raise exception 'lead % is not assigned to you', p_lead_id;
  end if;

  insert into public.call_dispositions
    (lead_id, agent_id, status, lead_quality, remarks, callback_at, sim_slot)
  values
    (p_lead_id, auth.uid(), p_status, p_lead_quality,
     nullif(btrim(p_remarks), ''), p_callback_at, p_sim_slot)
  returning id into new_id;

  update public.leads
     set last_status    = p_status,
         last_remarks   = nullif(btrim(p_remarks), ''),
         last_called_at = now(),
         attempts       = attempts + 1,
         callback_at    = case when p_status = 'CALL_LATER' then p_callback_at else null end,
         -- Wrong number / not interested / converted lead are terminal.
         -- Switched off stays open for a retry.
         is_closed      = p_status in ('WRONG_NUMBER', 'NOT_INTERESTED', 'LEAD'),
         locked_by      = null,
         locked_at      = null,
         -- release the record unless we owe them a callback
         assigned_to    = case when p_status = 'CALL_LATER' then assigned_to else null end
   where id = p_lead_id;

  return new_id;
end;
$$;


-- ---------------------------------------------------------------------
-- 10. RPC: log that an agent viewed a lead's sensitive fields
-- ---------------------------------------------------------------------

create or replace function public.log_lead_view(p_lead_id bigint)
returns void
language plpgsql
security definer set search_path = public
as $$
begin
  insert into public.lead_access_log (lead_id, agent_id)
  values (p_lead_id, auth.uid());
end;
$$;


-- ---------------------------------------------------------------------
-- 11. SUPERVISOR REPORTING VIEWS
-- ---------------------------------------------------------------------

create or replace view public.v_agent_performance
with (security_invoker = true) as
select
  p.id                        as agent_id,
  p.full_name,
  count(d.id)                                                as total_calls,
  count(*) filter (where d.status = 'LEAD')                  as leads,
  count(*) filter (where d.status = 'NOT_INTERESTED')        as not_interested,
  count(*) filter (where d.status = 'CALL_LATER')            as call_later,
  count(*) filter (where d.status = 'SWITCHED_OFF')          as switched_off,
  count(*) filter (where d.status = 'WRONG_NUMBER')          as wrong_number,
  count(*) filter (where d.status = 'NO_ANSWER')             as no_answer,
  round(
    100.0 * count(*) filter (where d.status = 'LEAD')
    / nullif(count(d.id), 0), 1
  )                                                          as conversion_pct,
  max(d.called_at)                                           as last_activity
from public.profiles p
left join public.call_dispositions d on d.agent_id = p.id
where p.role = 'agent'
group by p.id, p.full_name;


create or replace view public.v_daily_summary
with (security_invoker = true) as
select
  date_trunc('day', called_at)::date as day,
  status,
  count(*) as calls
from public.call_dispositions
group by 1, 2
order by 1 desc, 2;
