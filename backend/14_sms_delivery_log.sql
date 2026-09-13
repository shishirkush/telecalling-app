-- =====================================================================
--  MIGRATION 14 — remote visibility into whether the Apply Card SMS
--  actually sent, since agents work off-site and their phones can
--  never be plugged in for adb logcat.
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- backend/13 gave admin remote control over WHICH leads get called.
-- This gives admin remote visibility into WHETHER the SMS that rides
-- along with each call actually went out — every device-specific
-- failure mode (SEND_SMS denied, carrier radio off, no service) that
-- previously only showed up in a local logcat on the agent's own
-- phone now lands here instead, the same way lead_access_log already
-- reports "who saw this record" without needing physical access to
-- the agent's device.

create table public.sms_delivery_log (
  id         bigserial   primary key,
  -- set null rather than cascade: this is a diagnostic trail, not a
  -- record that belongs to the lead, so a lead being deleted later
  -- (delete_batch) should not erase the history of what happened.
  lead_id    bigint      references public.leads(id) on delete set null,
  agent_id   uuid        not null references public.profiles(id),
  mobile     text        not null,
  -- one of: sent, no_service, radio_off, null_pdu, generic_failure,
  -- unknown_code_<n>, permission_denied, exception — see SimManager.kt.
  outcome    text        not null,
  created_at timestamptz not null default now()
);

create index sms_delivery_log_idx on public.sms_delivery_log (agent_id, created_at desc);

alter table public.sms_delivery_log enable row level security;

drop policy if exists "agents insert own sms log" on public.sms_delivery_log;
create policy "agents insert own sms log"
  on public.sms_delivery_log for insert
  with check (agent_id = auth.uid());

-- Same reporting scope as lead_access_log / lead_edit_log: an agent's
-- own manager sees it, admin sees everyone's.
drop policy if exists "read sms log" on public.sms_delivery_log;
create policy "read sms log"
  on public.sms_delivery_log for select
  using (public.manages_agent(agent_id));

create or replace function public.log_sms_outcome(
  p_lead_id bigint,
  p_mobile  text,
  p_outcome text
)
returns void
language plpgsql
security definer set search_path = public
as $$
begin
  insert into public.sms_delivery_log (lead_id, agent_id, mobile, outcome)
  values (p_lead_id, auth.uid(), p_mobile, p_outcome);
end;
$$;

-- At-a-glance breakdown for the dashboard: is this "a few carrier
-- hiccups" or "half the fleet never granted SEND_SMS"? All-time, no
-- date filter — same reasoning as v_batch_summary, this is a small
-- table and a narrow question ("is the feature broken right now"),
-- not a report that needs a time window.
create or replace view public.v_sms_outcomes
with (security_invoker = true) as
select
  outcome,
  count(*)         as total,
  max(created_at)  as most_recent
from public.sms_delivery_log
group by outcome
order by total desc;
