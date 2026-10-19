-- =====================================================================
--  MIGRATION 43 — admin call-time totals: talk + ring + disposition time
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- Per agent, over a period, for the calls the Android app (1.17.1+) could
-- time (call_attempts.dial_secs is set — backend/40_call_timing_ring.sql):
--
--   talk_secs = the phone's own recorded talk time (duration_secs)
--   ring_secs = dial_secs - talk_secs (dialling until answered / hung up)
--   disp_secs = from the end of the call to the saved outcome:
--               (called_at - attempted_at) - dial_secs, floored at 0 and
--               capped at 30 minutes so one walked-away lead can't skew it.
--
-- Web-app agents (and older app builds) have no dial_secs, so their calls
-- are not counted here; n_calls says how many calls each total covers.
-- Admin only — the dashboard calls this only for the admin login, and the
-- function refuses anyone else.

create or replace function public.admin_call_time_totals(p_since timestamptz default null)
returns table (agent_id uuid, n_calls bigint, talk_secs bigint, ring_secs bigint, disp_secs bigint)
language plpgsql
stable
security definer set search_path = public
as $$
begin
  if not public.is_admin() then
    raise exception 'not permitted: admin only';
  end if;

  return query
    select a.agent_id,
           count(*)::bigint,
           sum(coalesce(a.duration_secs, 0))::bigint,
           sum(greatest(a.dial_secs - coalesce(a.duration_secs, 0), 0))::bigint,
           sum(least(greatest(extract(epoch from d.called_at - a.attempted_at) - a.dial_secs, 0), 1800))::bigint
      from public.call_attempts a
      join lateral (
        select x.called_at
          from public.call_dispositions x
         where x.lead_id = a.lead_id
           and x.agent_id = a.agent_id
           and x.called_at >= a.attempted_at
         order by x.called_at
         limit 1
      ) d on true
     where a.dial_secs is not null
       and (p_since is null or a.attempted_at >= p_since)
     group by a.agent_id;
end;
$$;

grant execute on function public.admin_call_time_totals(timestamptz) to authenticated;
