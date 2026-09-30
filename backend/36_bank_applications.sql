-- =====================================================================
--  MIGRATION 36 — bank card application tracking (the daily bank MIS)
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run (guards each create with if not exists / or replace).
-- =====================================================================
--
-- Until now, what happens to a lead AFTER an agent gets them to apply
-- for a card lived only in a manually-maintained Excel file (bank,
-- date, application id, VKYC status, approval, activation), re-typed by
-- hand every day. This gives that funnel a real table.
--
-- Field split mirrors who can actually know each fact: an agent knows
-- what they submitted (bank, application id, phone, customer, card,
-- VKYC status) the moment they submit it. Only the bank's own back
-- office produces the approval decision and activation state, so only
-- admin/manager write those — see update_bank_application_status()
-- below. applied_at is NOT an agent-entered field: it's stamped
-- server-side at insert time, same reasoning as the rest of this app's
-- telemetry (agent_id from auth.uid(), never typed) — a hand-typed date
-- is exactly what produced the Excel file's mix of real date cells and
-- pasted-as-text strings in the same column.

do $$ begin
  create type bank_app_vkyc_status as enum ('PENDING','BIO_KYC','BIO_KYC_DONE','DONE','DECLINED');
exception when duplicate_object then null; end $$;

do $$ begin
  create type bank_app_approval_status as enum ('PENDING','APPROVED','DECLINED','DONE');
exception when duplicate_object then null; end $$;

do $$ begin
  create type bank_app_activation_status as enum ('PENDING','ACTIVE','INACTIVE');
exception when duplicate_object then null; end $$;

create table if not exists public.bank_applications (
  id                 bigserial   primary key,

  -- Best-effort link back to the calling queue — never required. A
  -- phone match at insert time (see log_bank_application()) or the
  -- lead_id the agent already has open; null for a walk-in/referral
  -- that never went through the queue at all.
  lead_id            bigint      references public.leads(id) on delete set null,

  -- ---- Set by the agent at submission time ----
  bank               text        not null,
  application_id     text,                 -- some banks (INDUS/SBI/YES) don't issue one up front
  phone              text        not null, -- digits only, normalised server-side
  customer_name      text        not null,
  agent_id           uuid        not null references public.profiles(id),
  card_name          text,                 -- blank where the bank has no named product
  vkyc_status        bank_app_vkyc_status  not null default 'PENDING',

  -- ---- Set by admin/manager from the bank's own report ----
  approval_status    bank_app_approval_status   not null default 'PENDING',
  activation_status  bank_app_activation_status not null default 'PENDING',
  -- Free-text for bank-specific notes the enum can't capture, e.g. the
  -- original sheet's "TXN ACTIVE-100RS" / "V+ACTIVE" activation notes.
  activation_note    text,

  applied_at         date        not null default current_date,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now(),
  updated_by         uuid        references public.profiles(id),

  constraint bank_applications_bank_length           check (char_length(bank) <= 60),
  constraint bank_applications_customer_name_length   check (char_length(customer_name) <= 200),
  constraint bank_applications_card_name_length       check (card_name is null or char_length(card_name) <= 120),
  constraint bank_applications_application_id_length  check (application_id is null or char_length(application_id) <= 60),
  constraint bank_applications_activation_note_length check (activation_note is null or char_length(activation_note) <= 500)
);

create index if not exists bank_applications_agent_idx on public.bank_applications (agent_id, applied_at desc);
create index if not exists bank_applications_lead_idx  on public.bank_applications (lead_id);
create index if not exists bank_applications_phone_idx on public.bank_applications (phone);

-- Dedupe key for the admin bulk-upload re-sync (see dashboard's Bank
-- applications bulk upload) — only when the bank actually issued an
-- application id; rows without one (INDUS/SBI/YES POP in the sample
-- file) always insert fresh since there's nothing reliable to match on.
create unique index if not exists bank_applications_bank_appid_uniq
  on public.bank_applications (bank, application_id)
  where application_id is not null;


-- ---------------------------------------------------------------------
-- RLS
-- ---------------------------------------------------------------------

alter table public.bank_applications enable row level security;

drop policy if exists "read own or managed bank applications" on public.bank_applications;
create policy "read own or managed bank applications"
  on public.bank_applications for select
  using (agent_id = auth.uid() or public.is_admin() or public.manages_agent(agent_id));

-- Admin-only direct table access — this is what the dashboard's bulk
-- upload writes through (mirrors "admin manages leads" in
-- backend/09_manager_scoping.sql for the CSV lead importer). Agents
-- never get a table-level write policy: their inserts/updates go
-- through the security-definer RPCs below only.
drop policy if exists "admin manages bank applications" on public.bank_applications;
create policy "admin manages bank applications"
  on public.bank_applications for all
  using (public.is_admin())
  with check (public.is_admin());


-- ---------------------------------------------------------------------
-- RPC: agent logs a new application
-- ---------------------------------------------------------------------

create or replace function public.log_bank_application(
  p_bank           text,
  p_customer_name  text,
  p_phone          text,
  p_application_id text default null,
  p_card_name      text default null,
  p_vkyc_status    bank_app_vkyc_status default 'PENDING',
  p_lead_id        bigint default null
)
returns bigint
language plpgsql
security definer set search_path = public
as $$
declare
  new_id bigint;
  v_phone_digits text := regexp_replace(coalesce(p_phone, ''), '[^0-9]', '', 'g');
  v_lead_id bigint := p_lead_id;
begin
  if not exists (select 1 from public.profiles where id = auth.uid() and active) then
    raise exception 'inactive or unknown user';
  end if;
  if btrim(coalesce(p_bank, '')) = '' then
    raise exception 'bank is required';
  end if;
  if v_phone_digits = '' then
    raise exception 'a valid phone number is required';
  end if;
  if btrim(coalesce(p_customer_name, '')) = '' then
    raise exception 'customer name is required';
  end if;

  -- Best-effort link to an existing lead by phone — never blocks the
  -- save if nothing matches (walk-in, referral, repeat customer whose
  -- original lead has since been closed and isn't found by this query).
  if v_lead_id is null then
    select id into v_lead_id from public.leads
     where mobile = v_phone_digits
     order by created_at desc
     limit 1;
  end if;

  insert into public.bank_applications
    (lead_id, bank, application_id, phone, customer_name, agent_id, card_name, vkyc_status)
  values
    (v_lead_id, btrim(p_bank), nullif(btrim(p_application_id), ''), v_phone_digits,
     btrim(p_customer_name), auth.uid(), nullif(btrim(p_card_name), ''), p_vkyc_status)
  returning id into new_id;

  return new_id;
end;
$$;

grant execute on function public.log_bank_application(text, text, text, text, text, bank_app_vkyc_status, bigint) to authenticated;


-- ---------------------------------------------------------------------
-- RPC: agent edits their own entry — any time, no lock after admin has
-- recorded a decision. Deliberately narrow: name, application id, card
-- and mobile number only. NOT bank (that's the application's identity —
-- changing it would silently turn this into a different application)
-- and NOT VKYC status (tracked at creation time only; see
-- log_bank_application() — this app's whole reason to exist is an
-- honest paper trail, so letting an agent quietly rewrite their own
-- VKYC/bank after the fact would undermine that same as letting them
-- touch approval/activation). Admin can also use this to fix a typo.
-- ---------------------------------------------------------------------

drop function if exists public.update_bank_application(bigint, text, text, text, text, text, bank_app_vkyc_status);

create or replace function public.update_bank_application(
  p_id             bigint,
  p_customer_name  text,
  p_phone          text,
  p_application_id text default null,
  p_card_name      text default null
)
returns void
language plpgsql
security definer set search_path = public
as $$
declare
  v_phone_digits text := regexp_replace(coalesce(p_phone, ''), '[^0-9]', '', 'g');
begin
  if not exists (
    select 1 from public.bank_applications
     where id = p_id and (agent_id = auth.uid() or public.is_admin())
  ) then
    raise exception 'bank application % not found or not yours', p_id;
  end if;
  if v_phone_digits = '' then
    raise exception 'a valid phone number is required';
  end if;
  if btrim(coalesce(p_customer_name, '')) = '' then
    raise exception 'customer name is required';
  end if;

  update public.bank_applications
     set application_id = nullif(btrim(p_application_id), ''),
         phone          = v_phone_digits,
         customer_name  = btrim(p_customer_name),
         card_name      = nullif(btrim(p_card_name), ''),
         updated_at     = now(),
         updated_by     = auth.uid()
   where id = p_id;
end;
$$;

grant execute on function public.update_bank_application(bigint, text, text, text, text) to authenticated;


-- ---------------------------------------------------------------------
-- RPC: admin records the bank's approval + activation decision.
-- Admin ONLY — deliberately not extended to manager_agents. Unlike
-- every other manager-visible thing in this app, approval/activation is
-- the commission-bearing decision, so it stays with the single master
-- admin account rather than the wider set of manager logins.
-- ---------------------------------------------------------------------

create or replace function public.update_bank_application_status(
  p_id                bigint,
  p_approval_status   bank_app_approval_status,
  p_activation_status bank_app_activation_status,
  p_activation_note   text default null
)
returns void
language plpgsql
security definer set search_path = public
as $$
declare
  v_agent_id uuid;
begin
  select agent_id into v_agent_id from public.bank_applications where id = p_id;
  if v_agent_id is null then
    raise exception 'bank application % not found', p_id;
  end if;
  if not public.is_admin() then
    raise exception 'not permitted: only admin may update approval/activation status';
  end if;

  update public.bank_applications
     set approval_status   = p_approval_status,
         activation_status = p_activation_status,
         activation_note   = nullif(btrim(coalesce(p_activation_note, '')), ''),
         updated_at        = now(),
         updated_by        = auth.uid()
   where id = p_id;
end;
$$;

grant execute on function public.update_bank_application_status(bigint, bank_app_approval_status, bank_app_activation_status, text) to authenticated;


-- ---------------------------------------------------------------------
-- Reporting view — an agent's own rows, or admin sees all, or a manager
-- sees their assigned agents', same scoping as everywhere else
-- (security_invoker runs it under the caller's own RLS above).
-- ---------------------------------------------------------------------

create or replace view public.v_bank_applications
with (security_invoker = true) as
select
  ba.id, ba.bank, ba.applied_at, ba.application_id, ba.phone, ba.customer_name,
  ba.agent_id, p.full_name as agent_name, ba.card_name, ba.vkyc_status,
  ba.approval_status, ba.activation_status, ba.activation_note,
  ba.lead_id, ba.created_at, ba.updated_at
from public.bank_applications ba
join public.profiles p on p.id = ba.agent_id
order by ba.applied_at desc, ba.id desc;
