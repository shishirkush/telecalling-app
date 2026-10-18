-- =====================================================================
--  MIGRATION 41 — admin can change an agent's campaign directly
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- Until now an agent's campaign (profiles.current_campaign_id) could only
-- be changed by the agent themselves, through switch_campaign() — which
-- acts on auth.uid() — so an admin who wanted an agent moved had to ask
-- the agent to open the app and switch.
--
-- admin_set_agent_campaign() lets an admin do it for any agent from the
-- dashboard. claim_next_lead() reads current_campaign_id on every call, so
-- the change takes effect on the agent's very next "Next lead" with no
-- action on their side.
--
-- Mirrors switch_campaign()'s clean-switch behaviour (open leads assigned
-- to the agent go back to the pool, since they belong to the old
-- campaign's batches) with one difference: a lead the agent has called in
-- the last 10 minutes and not yet saved is left with them, so a call in
-- progress isn't pulled out from under them. Saving it clears the
-- assignment as usual.
--
-- p_campaign_id = null clears the campaign: the agent's app then shows
-- the "Pick a campaign" screen on next open, as for a brand-new agent.

create or replace function public.admin_set_agent_campaign(
  p_agent_id    uuid,
  p_campaign_id bigint
)
returns void
language plpgsql
security definer set search_path = public
as $$
begin
  if not public.is_admin() then
    raise exception 'not permitted: only an admin may change an agent''s campaign';
  end if;

  if not exists (
    select 1 from public.profiles where id = p_agent_id and role = 'agent'
  ) then
    raise exception 'agent % not found', p_agent_id;
  end if;

  if p_campaign_id is not null and not exists (
    select 1 from public.campaigns where id = p_campaign_id and is_active
  ) then
    raise exception 'campaign % is not active', p_campaign_id;
  end if;

  update public.leads l
     set assigned_to = null,
         locked_by   = null,
         locked_at   = null
   where l.assigned_to = p_agent_id
     and l.is_closed = false
     and not exists (
       select 1
         from public.call_attempts a
        where a.lead_id  = l.id
          and a.agent_id = p_agent_id
          and a.attempted_at > coalesce(l.last_called_at, '-infinity'::timestamptz)
          and a.attempted_at > now() - interval '10 minutes'
     );

  update public.profiles
     set current_campaign_id = p_campaign_id
   where id = p_agent_id;
end;
$$;

grant execute on function public.admin_set_agent_campaign(uuid, bigint) to authenticated;
