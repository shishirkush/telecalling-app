-- =====================================================================
--  MIGRATION 33 — don't block an agent's FIRST campaign pick on an
--  unrelated stale call
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- switch_campaign() (backend/32_campaigns.sql) refuses to switch while
-- the agent has a called-but-undisposed lead, so a switch can't quietly
-- release that lead back to the pool out from under them. That's the
-- right protection for a genuine SWITCH — moving away from a campaign
-- whose in-progress leads are about to be released.
--
-- It was wrongly applying to an agent's very FIRST pick too. On a first
-- pick there is no campaign-scoped queue yet to protect — the agent
-- isn't switching away from anything. But every agent's
-- current_campaign_id started NULL when campaigns launched, so any
-- agent who happened to have one old, never-dispositioned call sitting
-- around from before the feature existed got permanently locked out of
-- the campaign picker — the only screen the app shows until a campaign
-- is picked — with no way to recover on their own. Two agents (lokesh,
-- manisha) were found stuck this way on 2026-09-28, one for as long as
-- 2026-09-25 to 2026-09-28 across repeated app_opened retries, before
-- their blocking leads were manually dispositioned.
--
-- Fix: only run the unresolved-call check when the agent already had a
-- campaign (a real switch). The leads-release step right after still
-- runs unconditionally on a first pick too — any pre-campaign leads
-- whose batch isn't in the chosen campaign correctly go back to the
-- pool, same as a real switch.

create or replace function public.switch_campaign(p_campaign_id bigint)
returns void
language plpgsql
security definer set search_path = public
as $$
declare
  blocking_lead  record;
  v_had_campaign boolean;
begin
  if not exists (select 1 from public.profiles where id = auth.uid() and active) then
    raise exception 'inactive or unknown user';
  end if;

  if p_campaign_id is not null and not exists (
    select 1 from public.campaigns where id = p_campaign_id and is_active
  ) then
    raise exception 'campaign % is not active', p_campaign_id;
  end if;

  select current_campaign_id is not null into v_had_campaign
    from public.profiles where id = auth.uid();

  if v_had_campaign and not public.is_admin() then
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
  -- on archiving (backend/19_deactivate_agent.sql). Runs on a first pick
  -- too: any pre-campaign leads not in the chosen campaign's batches
  -- belong back in the pool.
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
