-- =====================================================================
--  MIGRATION 46 — recruitment SMS: a separate pre-loaded contact list
--  that an agent texts from the app in a batch of up to 100 per tap
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- This list is NOT the credit-card leads database: it is its own table,
-- loaded by the admin from the dashboard. Agents never see the numbers in
-- the app — one tap claims the next batch (max 100), the app sends the
-- admin-set message to each and reports the result.
--
-- Guard rails, all enforced here rather than in the app:
--   * agents only (a non-admin supervisor login is refused);
--   * an admin on/off switch and a per-agent DAILY CAP (default 100 — the
--     free-SMS allowance on common prepaid plans);
--   * each number is claimed by one agent only, once; failures are marked
--     failed and not retried automatically.

create table if not exists public.recruit_settings (
  id         int         primary key default 1 check (id = 1),
  enabled    boolean     not null default true,
  daily_cap  int         not null default 100 check (daily_cap between 0 and 1000),
  message    text        not null,
  updated_at timestamptz not null default now(),
  updated_by uuid        references public.profiles(id)
);

insert into public.recruit_settings (id, message)
values (1, 'Work from Home opportunity with commission upto Rs 40,000/- .' || chr(10) || chr(10) || 'Send whatsapp to 9953552591 to know more...Hurry!!')
on conflict (id) do nothing;

create table if not exists public.recruit_contacts (
  id         bigserial   primary key,
  mobile     text        not null unique check (mobile ~ '^[6-9][0-9]{9}$'),
  batch      text,
  status     text        not null default 'pending' check (status in ('pending', 'claimed', 'sent', 'failed')),
  claimed_by uuid        references public.profiles(id),
  claimed_at timestamptz,
  sent_at    timestamptz,
  outcome    text,
  created_at timestamptz not null default now()
);

create index if not exists recruit_contacts_pending_idx on public.recruit_contacts (id) where status = 'pending';
create index if not exists recruit_contacts_claimed_idx on public.recruit_contacts (claimed_by, claimed_at);

alter table public.recruit_settings enable row level security;
alter table public.recruit_contacts enable row level security;
-- No policies: every read/write goes through the functions below.

-- ---------------------------------------------------------------------
-- Agent side
-- ---------------------------------------------------------------------
create or replace function public.recruit_sms_info()
returns jsonb
language plpgsql
stable
security definer set search_path = public
as $$
declare
  s    public.recruit_settings;
  used int;
  pend bigint;
begin
  if not exists (select 1 from public.profiles where id = auth.uid() and role = 'agent' and active) then
    raise exception 'agents only';
  end if;
  select * into s from public.recruit_settings where id = 1;
  select count(*) into used from public.recruit_contacts
   where claimed_by = auth.uid()
     and claimed_at >= (date_trunc('day', now() at time zone 'Asia/Kolkata') at time zone 'Asia/Kolkata');
  select count(*) into pend from public.recruit_contacts where status = 'pending';
  return jsonb_build_object(
    'enabled', s.enabled,
    'message', s.message,
    'remaining_today', greatest(s.daily_cap - used, 0),
    'pending_total', pend
  );
end;
$$;

grant execute on function public.recruit_sms_info() to authenticated;

create or replace function public.claim_recruit_batch(p_count int default 100)
returns table (id bigint, mobile text, message text)
language plpgsql
security definer set search_path = public
as $$
declare
  s    public.recruit_settings;
  used int;
  n    int;
begin
  if not exists (select 1 from public.profiles where id = auth.uid() and role = 'agent' and active) then
    raise exception 'agents only';
  end if;
  -- (the function's OUT column "id" would clash with an unqualified id here)
  select * into s from public.recruit_settings rs where rs.id = 1;
  if not s.enabled then
    raise exception 'Recruitment SMS is switched off.';
  end if;

  -- A batch that was claimed but never reported back (app killed, phone off)
  -- goes back in the pool after 30 minutes (a full batch takes ~5-10 min at the
  -- app's pace; was 3 hours until 2026-10-20).
  update public.recruit_contacts
     set status = 'pending', claimed_by = null, claimed_at = null
   where status = 'claimed' and claimed_at < now() - interval '30 minutes';

  select count(*) into used from public.recruit_contacts c
   where c.claimed_by = auth.uid()
     and c.claimed_at >= (date_trunc('day', now() at time zone 'Asia/Kolkata') at time zone 'Asia/Kolkata');

  n := least(coalesce(p_count, 100), 100, greatest(s.daily_cap - used, 0));
  if n <= 0 then
    raise exception 'Daily limit reached (% messages today).', s.daily_cap;
  end if;

  return query
    with picked as (
      select c.id from public.recruit_contacts c
       where c.status = 'pending'
       order by c.id
       limit n
       for update skip locked
    ),
    u as (
      update public.recruit_contacts c
         set status = 'claimed', claimed_by = auth.uid(), claimed_at = now()
        from picked p
       where c.id = p.id
      returning c.id, c.mobile
    )
    select u.id, u.mobile, s.message from u;
end;
$$;

grant execute on function public.claim_recruit_batch(int) to authenticated;

-- p_results: [{"id": 12, "outcome": "sent"}, ...]
create or replace function public.report_recruit_results(p_results jsonb)
returns int
language plpgsql
security definer set search_path = public
as $$
declare
  n int;
begin
  update public.recruit_contacts c
     set status  = case when r.outcome = 'sent' then 'sent' else 'failed' end,
         outcome = left(r.outcome, 80),
         sent_at = now()
    from jsonb_to_recordset(p_results) as r(id bigint, outcome text)
   where c.id = r.id and c.claimed_by = auth.uid() and c.status = 'claimed';
  get diagnostics n = row_count;
  return n;
end;
$$;

grant execute on function public.report_recruit_results(jsonb) to authenticated;

-- ---------------------------------------------------------------------
-- Admin side
-- ---------------------------------------------------------------------
create or replace function public.admin_add_recruit_contacts(p_numbers text[], p_batch text default null)
returns jsonb
language plpgsql
security definer set search_path = public
as $$
declare
  received int := coalesce(array_length(p_numbers, 1), 0);
  added    int;
begin
  if not public.is_admin() then raise exception 'not permitted: admin only'; end if;
  with cleaned as (
    select distinct right(regexp_replace(x, '[^0-9]', '', 'g'), 10) as m from unnest(p_numbers) as x
  ), ins as (
    insert into public.recruit_contacts (mobile, batch)
    select m, nullif(btrim(p_batch), '') from cleaned where m ~ '^[6-9][0-9]{9}$'
    on conflict (mobile) do nothing
    returning 1
  )
  select count(*) into added from ins;
  return jsonb_build_object('received', received, 'added', added, 'skipped', received - added);
end;
$$;

grant execute on function public.admin_add_recruit_contacts(text[], text) to authenticated;

create or replace function public.admin_recruit_stats()
returns jsonb
language plpgsql
stable
security definer set search_path = public
as $$
declare
  s public.recruit_settings;
begin
  if not public.is_admin() then raise exception 'not permitted: admin only'; end if;
  select * into s from public.recruit_settings where id = 1;
  return jsonb_build_object(
    'enabled', s.enabled, 'daily_cap', s.daily_cap, 'message', s.message,
    'pending',    (select count(*) from public.recruit_contacts where status = 'pending'),
    'claimed',    (select count(*) from public.recruit_contacts where status = 'claimed'),
    'sent',       (select count(*) from public.recruit_contacts where status = 'sent'),
    'failed',     (select count(*) from public.recruit_contacts where status = 'failed'),
    'sent_today', (select count(*) from public.recruit_contacts where status = 'sent'
                    and sent_at >= (date_trunc('day', now() at time zone 'Asia/Kolkata') at time zone 'Asia/Kolkata'))
  );
end;
$$;

grant execute on function public.admin_recruit_stats() to authenticated;

create or replace function public.admin_set_recruit_settings(p_message text, p_daily_cap int, p_enabled boolean)
returns void
language plpgsql
security definer set search_path = public
as $$
begin
  if not public.is_admin() then raise exception 'not permitted: admin only'; end if;
  if p_message is null or btrim(p_message) = '' or char_length(p_message) > 600 then
    raise exception 'message must be 1-600 characters';
  end if;
  update public.recruit_settings
     set message = p_message, daily_cap = p_daily_cap, enabled = p_enabled,
         updated_at = now(), updated_by = auth.uid()
   where id = 1;
end;
$$;

grant execute on function public.admin_set_recruit_settings(text, int, boolean) to authenticated;
