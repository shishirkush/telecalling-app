-- =====================================================================
--  MIGRATION 32 — campaigns: one active campaign per agent, one campaign
--  per batch
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- Every batch (leads.batch, backend/13_batch_active_toggle.sql) now
-- belongs to at most one campaign, and every agent works exactly one
-- campaign at a time. This does not replace batch_settings.is_active —
-- a batch can be assigned to a campaign AND switched off; both checks
-- apply.
--
-- Leads with no batch, or a batch not yet assigned to any campaign, are
-- deliberately unallotted — claim_next_lead() will no longer serve them
-- to anyone, reversing migration 13's "no batch = always eligible"
-- default now that campaign assignment is the thing that makes a batch
-- eligible at all. Nothing about existing batches changes their
-- eligibility until an admin explicitly assigns them to a campaign from
-- the dashboard.
--
-- Switching is a security-definer RPC, not a plain profiles UPDATE, for
-- the same reason set_agent_active()/deactivating is one: it has two
-- side effects that must happen atomically and cannot be left to the
-- client — release every other open lead back to the pool (a "clean"
-- switch, so nothing sits assigned to an agent no longer working that
-- campaign), and refuse the switch if the agent has a called-but-
-- undisposed lead, the exact check claim_next_lead() already enforces
-- (backend/30_block_claim_on_unresolved_call.sql) — otherwise switching
-- campaigns becomes a side door around that fix. profiles.current_
-- campaign_id inherits the same protection migration 05 already gives
-- `active`/`role`: authenticated has no UPDATE grant on this table at
-- all except the single column explicitly granted there, so a new
-- column is unwritable by a plain client PATCH with no extra revoke
-- needed.

create table public.campaigns (
  id         bigserial primary key,
  name       text not null unique,
  is_active  boolean not null default true,
  created_at timestamptz not null default now()
);

alter table public.campaigns enable row level security;

-- Every signed-in, active user needs to read this list to pick a
-- campaign — not just admin, unlike batch_settings itself.
create policy "active users read campaigns"
  on public.campaigns for select
  using (exists (select 1 from public.profiles where id = auth.uid() and active));

create policy "admin manages campaigns"
  on public.campaigns for all
  using (public.is_admin())
  with check (public.is_admin());

alter table public.batch_settings
  add column if not exists campaign_id bigint references public.campaigns(id);

alter table public.profiles
  add column if not exists current_campaign_id bigint references public.campaigns(id);

create or replace function public.switch_campaign(p_campaign_id bigint)
returns void
language plpgsql
security definer set search_path = public
as $$
declare
  blocking_lead record;
begin
  if not exists (select 1 from public.profiles where id = auth.uid() and active) then
    raise exception 'inactive or unknown user';
  end if;

  if p_campaign_id is not null and not exists (
    select 1 from public.campaigns where id = p_campaign_id and is_active
  ) then
    raise exception 'campaign % is not active', p_campaign_id;
  end if;

  if not public.is_admin() then
    select l.id, l.name
      into blocking_lead
      from public.leads l
      join public.call_attempts a
        on a.lead_id = l.id and a.agent_id = auth.uid()
     where l.assigned_to = auth.uid()
       and a.attempted_at > coalesce(l.last_called_at, '-infinity'::timestamptz)
       and a.attempted_at <= now() - interval '10 minutes'
     order by a.attempted_at asc
     limit 1;

    if blocking_lead.id is not null then
      raise exception 'Save the outcome for % before switching campaigns.',
        coalesce(blocking_lead.name, 'the lead you called');
    end if;
  end if;

  -- Clean switch — same release set_agent_active(false) already performs
  -- on archiving (backend/19_deactivate_agent.sql).
  update public.leads
     set assigned_to = null,
         locked_by   = null,
         locked_at   = null
   where assigned_to = auth.uid()
     and is_closed = false;

  update public.profiles
     set current_campaign_id = p_campaign_id
   where id = auth.uid();
end;
$$;

grant execute on function public.switch_campaign(bigint) to authenticated;

-- ---------------------------------------------------------------------
-- claim_next_lead(): only serve leads whose batch is assigned to the
-- caller's current campaign (and still switched on). Admin bypasses
-- both the campaign gate and the "pick a campaign first" requirement,
-- same tier as admin's other exemptions in this function.
-- ---------------------------------------------------------------------

create or replace function public.claim_next_lead()
returns setof public.leads
language plpgsql
security definer set search_path = public
as $$
declare
  stale_after      constant interval := '30 minutes';
  unresolved_grace constant interval := '10 minutes';
  ist_time         time := (now() at time zone 'Asia/Kolkata')::time;
  blocking_lead    record;
  v_campaign_id    bigint;
begin
  if not exists (select 1 from public.profiles where id = auth.uid() and active) then
    raise exception 'inactive or unknown user';
  end if;

  if ist_time < time '09:00' or ist_time >= time '19:00' then
    raise exception 'Leads can only be claimed between 9:00 AM and 7:00 PM IST.';
  end if;

  if not public.is_admin() then
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

  return query
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
  select * from claimed;
end;
$$;

-- ---------------------------------------------------------------------
-- v_batch_summary: append campaign_id/campaign_name so the dashboard can
-- show and edit each batch's campaign assignment. Appended at the end
-- on purpose — CREATE OR REPLACE VIEW cannot reorder or insert columns
-- mid-list (42P16).
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
  bool_and(coalesce(bs.is_active, true)) as is_active,
  bs.campaign_id                         as campaign_id,
  camp.name                              as campaign_name
from public.leads l
left join public.batch_settings bs on bs.batch = l.batch
left join public.campaigns camp on camp.id = bs.campaign_id
group by l.batch, bs.campaign_id, camp.name
order by min(l.created_at) desc;
