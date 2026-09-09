-- =====================================================================
--  MIGRATION 09 — manager-scoped reporting
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- Until now every supervisor account saw every agent's data — one flat
-- tier. This adds a second tier: `profiles.is_admin` marks the accounts
-- that keep seeing everything and can assign agents to the rest. Every
-- other supervisor ("manager") sees NOTHING until admin explicitly
-- assigns agents to them via `manager_agents`.
--
-- Scope, deliberately: this restricts REPORTING reads (agent
-- performance, recent calls, callbacks, access/edit logs) and locks
-- "Create agent" + CSV import to admin. It does NOT touch the Android
-- app — an agent's view of their own queue is unaffected either way,
-- and the write-override that let a supervisor fix any lead directly
-- (save_disposition / save_lead_details called on a lead that isn't
-- theirs) narrows from "any supervisor" to "admin only," same as
-- Create Agent and CSV import — a scoped manager reads their agents'
-- reports but doesn't get new write powers over agents' own leads.

-- ---------------------------------------------------------------------
-- 1. Admin flag
-- ---------------------------------------------------------------------

alter table public.profiles add column if not exists is_admin boolean not null default false;

-- The account already used for admin-level dashboard/API work.
update public.profiles set is_admin = true where login_id = 'admin';


-- ---------------------------------------------------------------------
-- 2. is_admin() — defined first; the assignment table's own RLS policy
--    below needs it.
-- ---------------------------------------------------------------------

create or replace function public.is_admin()
returns boolean
language sql
stable
security definer set search_path = public
as $$
  select exists (
    select 1 from public.profiles
    where id = auth.uid() and role = 'supervisor' and is_admin and active
  );
$$;


-- ---------------------------------------------------------------------
-- 3. The assignment table
-- ---------------------------------------------------------------------

create table if not exists public.manager_agents (
  manager_id uuid        not null references public.profiles(id) on delete cascade,
  agent_id   uuid        not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (manager_id, agent_id)
);

-- Catch an obviously wrong assignment (wrong role either side) at write
-- time instead of silently accepting a meaningless row.
create or replace function public.check_manager_agents_roles()
returns trigger
language plpgsql
security definer set search_path = public
as $$
begin
  if not exists (select 1 from public.profiles where id = new.manager_id and role = 'supervisor') then
    raise exception 'manager_id % is not a supervisor', new.manager_id;
  end if;
  if not exists (select 1 from public.profiles where id = new.agent_id and role = 'agent') then
    raise exception 'agent_id % is not an agent', new.agent_id;
  end if;
  return new;
end;
$$;

drop trigger if exists manager_agents_role_check on public.manager_agents;
create trigger manager_agents_role_check
  before insert or update on public.manager_agents
  for each row execute function public.check_manager_agents_roles();

alter table public.manager_agents enable row level security;

drop policy if exists "admin manages assignments" on public.manager_agents;
create policy "admin manages assignments"
  on public.manager_agents for all
  using (public.is_admin())
  with check (public.is_admin());


-- ---------------------------------------------------------------------
-- 4. manages_agent() — defined after the table above, which it queries.
-- ---------------------------------------------------------------------
-- True if the caller may see/report on this agent: the agent
-- themselves, admin, or an explicitly assigned manager.

create or replace function public.manages_agent(p_agent_id uuid)
returns boolean
language sql
stable
security definer set search_path = public
as $$
  select p_agent_id = auth.uid()
    or public.is_admin()
    or exists (
      select 1 from public.manager_agents
      where manager_id = auth.uid() and agent_id = p_agent_id
    );
$$;


-- ---------------------------------------------------------------------
-- 5. Re-scope the existing policies
-- ---------------------------------------------------------------------
-- security_invoker views (v_agent_performance, v_daily_summary) run
-- under the caller's own RLS, so scoping the tables below is enough —
-- neither view needs to change.

-- --- profiles: see yourself, or (if admin) everyone, or (if manager)
--     your assigned agents. manages_agent() is self-inclusive.
drop policy if exists "read own profile" on public.profiles;
create policy "read own profile"
  on public.profiles for select
  using (public.manages_agent(id) or public.is_admin());

-- --- leads: an agent's own assigned lead, or admin, or a lead
--     currently assigned to (or ever worked by) one of your agents.
drop policy if exists "agents read assigned leads" on public.leads;
create policy "agents read assigned leads"
  on public.leads for select
  using (
    assigned_to = auth.uid()
    or public.is_admin()
    or (assigned_to is not null and public.manages_agent(assigned_to))
    or exists (
      select 1 from public.call_dispositions cd
      where cd.lead_id = leads.id and public.manages_agent(cd.agent_id)
    )
  );

-- Managing the raw pool (CSV import, bulk edits) is admin-only now —
-- same tier as Create Agent.
drop policy if exists "supervisors manage leads" on public.leads;
create policy "admin manages leads"
  on public.leads for all
  using (public.is_admin())
  with check (public.is_admin());

-- --- call_dispositions: read your own, or your assigned agents', or
--     everything if admin.
drop policy if exists "read own dispositions" on public.call_dispositions;
create policy "read own dispositions"
  on public.call_dispositions for select
  using (public.manages_agent(agent_id));

-- --- lead_access_log / lead_edit_log: same reporting scope.
drop policy if exists "supervisors read access log" on public.lead_access_log;
create policy "read access log"
  on public.lead_access_log for select
  using (public.manages_agent(agent_id));

drop policy if exists "supervisors read edit log" on public.lead_edit_log;
create policy "read edit log"
  on public.lead_edit_log for select
  using (public.manages_agent(agent_id));


-- ---------------------------------------------------------------------
-- 6. Narrow the "supervisor can fix any lead" override to admin
-- ---------------------------------------------------------------------
-- save_disposition() and save_lead_details() let a supervisor act on a
-- lead that isn't assigned to them. That write power now requires
-- is_admin(), not just any supervisor role — a scoped manager can read
-- their agents' history but doesn't gain new write access over leads.

create or replace function public.save_disposition(
  p_lead_id      bigint,
  p_status       call_status,
  p_lead_quality lead_quality default null,
  p_remarks      text         default null,
  p_callback_at  timestamptz  default null,
  p_sim_slot     int          default null
)
returns bigint
language plpgsql
security definer set search_path = public
as $$
declare
  new_id bigint;
begin
  if not exists (
    select 1 from public.leads
     where id = p_lead_id
       and (assigned_to = auth.uid() or public.is_admin())
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
         is_closed      = p_status in ('WRONG_NUMBER', 'NOT_INTERESTED', 'LEAD'),
         locked_by      = null,
         locked_at      = null,
         assigned_to    = case when p_status = 'CALL_LATER' then assigned_to else null end
   where id = p_lead_id;

  return new_id;
end;
$$;

create or replace function public.save_lead_details(
  p_lead_id              bigint,
  p_name                 text default null,
  p_email                text default null,
  p_dob                  date default null,
  p_company              text default null,
  p_annual_income_range  text default null,
  p_address              text default null
)
returns setof public.leads
language plpgsql
security definer set search_path = public
as $$
declare
  before public.leads%rowtype;
begin
  select * into before
    from public.leads
   where id = p_lead_id
     and (assigned_to = auth.uid() or public.is_admin());

  if not found then
    raise exception 'lead % is not assigned to you', p_lead_id;
  end if;

  update public.leads set
      name                = coalesce(nullif(btrim(p_name), ''),                name),
      email               = coalesce(nullif(btrim(p_email), ''),               email),
      dob                 = coalesce(p_dob,                                    dob),
      company             = coalesce(nullif(btrim(p_company), ''),             company),
      annual_income_range = coalesce(nullif(btrim(p_annual_income_range), ''), annual_income_range),
      address             = coalesce(nullif(btrim(p_address), ''),             address)
   where id = p_lead_id;

  -- One row per field that actually changed.
  insert into public.lead_edit_log (lead_id, agent_id, field, old_value, new_value)
  select p_lead_id, auth.uid(), f.field, f.old_value, f.new_value
    from public.leads l,
    lateral (values
      ('name',                before.name,                l.name),
      ('email',               before.email,               l.email),
      ('dob',                 before.dob::text,           l.dob::text),
      ('company',             before.company,             l.company),
      ('annual_income_range', before.annual_income_range, l.annual_income_range),
      ('address',             before.address,             l.address)
    ) as f(field, old_value, new_value)
   where l.id = p_lead_id
     and f.old_value is distinct from f.new_value;

  return query select * from public.leads where id = p_lead_id;
end;
$$;
