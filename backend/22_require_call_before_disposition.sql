-- =====================================================================
--  MIGRATION 22 — require an actual call attempt before a disposition
--  can be saved, so a lead can't be claimed, its contact details
--  viewed, and dispositioned again without ever calling the customer.
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- The suspicion: an agent claims a lead, sees the customer's name,
-- mobile, PAN, DOB, income and address on the detail screen, and saves
-- an outcome (or just leaves it, claims the next) without ever placing
-- a call — harvesting contact data rather than working the queue. The
-- Android/web "Call" banner already hides the raw digits behind a
-- button (v1.9.2+ / webapp from day one), but that alone doesn't stop
-- anyone from skipping the tap entirely and saving straight away — a
-- UI affordance is not an enforcement point.
--
-- This is enforced where every other rule in this file already is:
-- server-side, inside save_disposition() itself, not as a client-side
-- disabled button (which only ever gates the UI, never the actual
-- write — the RPC is reachable directly with any valid session token).
-- log_call_attempt() is a new, narrow RPC the app calls the moment the
-- agent taps "Call" — it does not (and cannot) prove the call
-- connected, only that the agent's own client registered a call
-- attempt for this lead before trying to save. That is the same class
-- of imperfect-but-meaningful signal already used for logSmsOutcome/
-- hasCalledThisLead; a determined agent could still fake the RPC call
-- without actually dialling, but that requires deliberately working
-- around the app rather than doing nothing at all, which is the
-- realistic threat here.

create table if not exists public.call_attempts (
  id           bigserial   primary key,
  lead_id      bigint      not null references public.leads(id) on delete cascade,
  agent_id     uuid        not null references public.profiles(id),
  attempted_at timestamptz not null default now()
);

create index if not exists call_attempts_lead_agent_idx
  on public.call_attempts (lead_id, agent_id, attempted_at desc);

alter table public.call_attempts enable row level security;

drop policy if exists "agents insert own call attempts" on public.call_attempts;
create policy "agents insert own call attempts"
  on public.call_attempts for insert
  with check (agent_id = auth.uid());

drop policy if exists "read own or managed call attempts" on public.call_attempts;
create policy "read own or managed call attempts"
  on public.call_attempts for select
  using (public.manages_agent(agent_id));

create or replace function public.log_call_attempt(p_lead_id bigint)
returns void
language plpgsql
security definer set search_path = public
as $$
begin
  if not exists (
    select 1 from public.leads
     where id = p_lead_id
       and (assigned_to = auth.uid() or public.is_admin())
  ) then
    raise exception 'lead % is not assigned to you', p_lead_id;
  end if;

  insert into public.call_attempts (lead_id, agent_id) values (p_lead_id, auth.uid());
end;
$$;

grant execute on function public.log_call_attempt(bigint) to authenticated;


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
  lead_last_called_at timestamptz;
begin
  select last_called_at into lead_last_called_at
    from public.leads
   where id = p_lead_id
     and (assigned_to = auth.uid() or public.is_admin());

  if not found then
    raise exception 'lead % is not assigned to you', p_lead_id;
  end if;

  -- Admin's write-override (fixing a lead directly) is exempt on
  -- purpose — same tier as the ownership check just above, a
  -- correction doesn't require the admin to have personally called.
  -- coalesce'd against '-infinity' rather than requiring last_called_at
  -- to be non-null: a lead's very first disposition has no prior
  -- last_called_at to compare against, so any logged attempt counts;
  -- a re-disposition (calling a CALL_LATER lead back) requires a call
  -- attempt logged *strictly after* the previous one (>, not >=) so an
  -- attempt already "spent" on satisfying one disposition can never
  -- also satisfy a second, separate one — confirmed live this matters:
  -- log_call_attempt() and save_disposition() called back-to-back in
  -- the same transaction land on the identical now() value in
  -- PostgreSQL (now() is fixed per-transaction, not per-statement), so
  -- >= let that one attempt justify two different dispositions.
  if not public.is_admin() and not exists (
    select 1 from public.call_attempts
     where lead_id = p_lead_id
       and agent_id = auth.uid()
       and attempted_at > coalesce(lead_last_called_at, '-infinity'::timestamptz)
  ) then
    raise exception 'Call this customer before saving an outcome.';
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
