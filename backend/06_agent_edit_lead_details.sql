-- =====================================================================
--  MIGRATION 06 — let an agent correct customer details from the call
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- Leads increasingly arrive with little more than a mobile number, so the
-- agent learns the real details on the call and previously had nowhere to
-- put them.
--
-- Agents still have NO direct write access to `leads` — that rule in
-- section 8 of CLAUDE.md stands. This works exactly like save_disposition:
-- a security-definer RPC that first proves the lead belongs to the caller,
-- then writes a fixed set of columns. An agent cannot reach the table any
-- other way, and cannot widen the column list from the client.


-- ---------------------------------------------------------------------
-- 1. EDIT LOG
-- ---------------------------------------------------------------------
-- lead_access_log records who READ a record. This is its counterpart for
-- writes. The data is KYC-grade PII, so a change an agent makes has to be
-- attributable and reversible — without this, a wrong name silently
-- overwrites the bank's own data with no way to see what it used to be.

create table if not exists public.lead_edit_log (
  id        bigserial   primary key,
  lead_id   bigint      not null references public.leads(id) on delete cascade,
  agent_id  uuid        not null references public.profiles(id),
  field     text        not null,
  old_value text,
  new_value text,
  edited_at timestamptz not null default now()
);

create index if not exists lead_edit_log_lead_idx  on public.lead_edit_log (lead_id, edited_at desc);
create index if not exists lead_edit_log_agent_idx on public.lead_edit_log (agent_id, edited_at desc);

alter table public.lead_edit_log enable row level security;

drop policy if exists "supervisors read edit log" on public.lead_edit_log;
create policy "supervisors read edit log"
  on public.lead_edit_log for select
  using (public.is_supervisor());

drop policy if exists "agents read own edits" on public.lead_edit_log;
create policy "agents read own edits"
  on public.lead_edit_log for select
  using (agent_id = auth.uid());


-- ---------------------------------------------------------------------
-- 2. RPC: save details learned on the call
-- ---------------------------------------------------------------------
-- Editable:     name, email, dob, company, annual_income_range, address
--
-- Deliberately NOT editable, and simply absent from the UPDATE below so it
-- cannot be reached at all:
--   * mobile      — the number being dialled and half of the (batch, mobile)
--                   duplicate key. Changing it would let an agent redirect a
--                   lead and would corrupt de-duplication. A bad number is a
--                   WRONG_NUMBER outcome, not an edit.
--   * pan         — KYC identity supplied by the bank. Typo-prone and the
--                   most damaging field to get wrong. Add it here only if
--                   capturing PAN on the call is a deliberate decision.
--   * ici_cr_lmt  — the bank's offer, not the agent's to change.
--   * every workflow column (assigned_to, is_closed, last_status, batch …)
--
-- NULL means "leave this column as it is". Agents add what they learn;
-- clearing a field is a supervisor action, not something to do mid-call.

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
  -- Same ownership rule as save_disposition. Without it any agent could
  -- rewrite any lead by guessing an id.
  select * into before
    from public.leads
   where id = p_lead_id
     and (assigned_to = auth.uid() or public.is_supervisor());

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
