-- =====================================================================
--  MIGRATION 42 — one aggregated query for the dashboard's call counts
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- The dashboard home page counted calls by downloading every
-- call_dispositions row (paged 1000 at a time, because one request is
-- capped at 1000 rows) and tallying in the browser. The table's row-level
-- policy ("read own dispositions", backend/09_manager_scoping.sql) calls
-- manages_agent() for EVERY row, and deep pages (offset 8000+) force
-- Postgres to evaluate it for all the rows before the offset too — so
-- "All time" (13,000+ calls and growing) ran into the statement timeout:
-- "canceling statement due to statement timeout".
--
-- This returns the same information already grouped — one row per
-- (agent, outcome, lead quality) with a count — in a single statement.
-- The scoping rule is applied once, not per row: an admin sees everyone,
-- a manager sees themselves and their assigned agents (the same rule
-- manages_agent() implements), and anyone who isn't an active supervisor
-- gets nothing.

create or replace function public.dashboard_call_counts(p_since timestamptz default null)
returns table (agent_id uuid, status text, lead_quality text, n bigint)
language plpgsql
stable
security definer set search_path = public
as $$
begin
  if not exists (
    select 1 from public.profiles pr
     where pr.id = auth.uid() and pr.role = 'supervisor' and pr.active
  ) then
    raise exception 'not permitted: supervisors only';
  end if;

  if public.is_admin() then
    return query
      select d.agent_id, d.status::text, d.lead_quality::text, count(*)
        from public.call_dispositions d
       where p_since is null or d.called_at >= p_since
       group by d.agent_id, d.status, d.lead_quality;
  else
    return query
      select d.agent_id, d.status::text, d.lead_quality::text, count(*)
        from public.call_dispositions d
       where (p_since is null or d.called_at >= p_since)
         and (
           d.agent_id = auth.uid()
           or d.agent_id in (
             select ma.agent_id from public.manager_agents ma where ma.manager_id = auth.uid()
           )
         )
       group by d.agent_id, d.status, d.lead_quality;
  end if;
end;
$$;

grant execute on function public.dashboard_call_counts(timestamptz) to authenticated;
