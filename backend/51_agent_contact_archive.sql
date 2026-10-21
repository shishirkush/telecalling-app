-- =====================================================================
--  MIGRATION 51 — keep every agent's calling number, even after the
--  agent is deleted, renamed or changes their number
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- An agent's calling number (profiles.contact_number, migration 26) lives on
-- their profile row, so it was lost when the profile was deleted, overwritten
-- when an agent changed it, and left attached to the wrong person when an
-- archived seat was renamed for a new hire.
--
-- agent_contact_archive keeps one row per (agent id, login ID, number) ever
-- seen. It has NO foreign key to profiles, so deleting a profile cannot touch
-- it. A trigger on profiles keeps it up to date; existing numbers are
-- backfilled below. Admin-only: read through admin_agent_contact_history().

create table if not exists public.agent_contact_archive (
  id             bigserial   primary key,
  agent_id       uuid        not null,          -- deliberately NOT a foreign key
  login_id       text        not null,
  full_name      text        not null,
  contact_number text        not null,
  first_seen     timestamptz not null default now(),
  last_seen      timestamptz not null default now(),
  unique (agent_id, login_id, contact_number)
);

alter table public.agent_contact_archive enable row level security;
revoke all on public.agent_contact_archive from anon, authenticated;
-- No policies: reachable only through the admin function below.

-- Existing numbers.
insert into public.agent_contact_archive (agent_id, login_id, full_name, contact_number)
select p.id, p.login_id, p.full_name, p.contact_number
  from public.profiles p
 where p.role = 'agent' and p.contact_number is not null
on conflict (agent_id, login_id, contact_number) do nothing;

-- Keep it current: on every new/changed number, rename, and on deletion.
create or replace function public.snapshot_agent_contact()
returns trigger
language plpgsql
security definer set search_path = public
as $$
declare
  r public.profiles;
begin
  r := case when tg_op = 'DELETE' then old else new end;
  if r.role = 'agent' and r.contact_number is not null then
    insert into public.agent_contact_archive (agent_id, login_id, full_name, contact_number)
    values (r.id, r.login_id, r.full_name, r.contact_number)
    on conflict (agent_id, login_id, contact_number)
    do update set full_name = excluded.full_name, last_seen = now();
  end if;
  return r;
end;
$$;

drop trigger if exists snapshot_agent_contact_upd on public.profiles;
create trigger snapshot_agent_contact_upd
  after insert or update of contact_number, login_id, full_name on public.profiles
  for each row execute function public.snapshot_agent_contact();

drop trigger if exists snapshot_agent_contact_del on public.profiles;
create trigger snapshot_agent_contact_del
  after delete on public.profiles
  for each row execute function public.snapshot_agent_contact();

-- The numbers that are NOT what an agent's profile shows right now: deleted
-- agents, earlier numbers, and earlier owners of a repurposed seat.
create or replace function public.admin_agent_contact_history()
returns table (agent_id uuid, login_id text, full_name text, contact_number text, status text, last_seen timestamptz)
language plpgsql
stable
security definer set search_path = public
as $$
begin
  if not public.is_admin() then raise exception 'not permitted: admin only'; end if;
  return query
    select a.agent_id, a.login_id, a.full_name, a.contact_number,
           case when p.id is null then 'Deleted' else 'Previous' end,
           a.last_seen
      from public.agent_contact_archive a
      left join public.profiles p on p.id = a.agent_id
     where not exists (
             select 1 from public.profiles c
              where c.id = a.agent_id and c.login_id = a.login_id and c.contact_number = a.contact_number)
     order by lower(a.full_name), a.last_seen desc;
end;
$$;

revoke execute on function public.admin_agent_contact_history() from public, anon;
grant execute on function public.admin_agent_contact_history() to authenticated;
