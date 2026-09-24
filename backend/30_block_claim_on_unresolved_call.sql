-- =====================================================================
--  MIGRATION 30 — refuse to hand out a new lead while an agent has a
--  called-but-undisposed one sitting in their own queue.
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- THE GAP THIS CLOSES
-- --------------------
-- The Android app already refuses to let an agent leave a called lead
-- without saving an outcome — but every one of those refusals lives in
-- the app itself: back button, system back gesture, opening a different
-- lead. All of it is a client-side convention, and every client-side
-- convention shares the same ceiling — it only runs while the app is
-- running. An agent who signs the same login into a second device force-
-- replaces the first device's session (backend/27_single_session_per_
-- agent.sql); the first device's signOutReplaced() clears its local
-- "resume this lead" marker unconditionally, the exact same call
-- voluntary sign-out makes, and the agent has walked away from a called,
-- unsaved lead with nothing to show for it. Killing the app, force-
-- stopping it from Android Settings, or wiping/reinstalling it entirely
-- would all do the same — none of them run any Kotlin code on the way
-- out, so no Kotlin-side gate can ever catch them.
--
-- The only place a rule survives all of that is the database. This
-- migration moves the enforcement there: claim_next_lead() — the one
-- RPC that hands an agent anything new, whether a fresh lead or a due
-- callback — now refuses outright if the agent already has an assigned,
-- called lead with no disposition saved since that call. It doesn't
-- matter which of the exits above they used to get here; the block is
-- keyed off the data (leads.assigned_to + call_attempts), not off
-- anything the app did or didn't run on the way out.
--
-- WHY 10 MINUTES, NOT INSTANT
-- ---------------------------
-- A real call is still in progress for a little while after the attempt
-- is logged — log_call_attempt() fires the instant "Call" is tapped,
-- before the phone even rings (backend/22_require_call_before_
-- disposition.sql), and save_disposition()'s own floor is only 30
-- seconds (backend/23_minimum_call_dwell_time.sql). Blocking the instant
-- an attempt is logged would lock an agent out of their own queue while
-- legitimately still on the phone. 10 minutes is generous next to how
-- fast a real call-to-save cycle runs in practice, while still closing
-- the gap in one queue-refresh's time rather than leaving it open
-- indefinitely.
--
-- WHAT DOESN'T GET STUCK
-- -----------------------
-- The blocking lead is still assigned to the agent and still open —
-- nothing about this migration hides it. It's exactly where it already
-- was in their queue; this only stops claim_next_lead() from handing
-- out anything ADDITIONAL until that one is resolved one way or another.
-- release_stuck_lead() below is the admin escape hatch for the genuine
-- dead end (lost phone, wrong number nobody can call back) — it puts
-- the lead back in the open pool exactly the way save_disposition()
-- itself already releases a SWITCHED_OFF/NO_ANSWER lead, and logs the
-- release to lead_edit_log so it's visible on the same admin trail as
-- every other manual correction to a lead.
--
-- Admin is exempt from the block itself, same reasoning save_disposition
-- already applies to admin's own write-override: this gate targets an
-- agent's own calling accountability, not admin's.

create or replace function public.claim_next_lead()
returns setof public.leads
language plpgsql
security definer set search_path = public
as $$
declare
  stale_after  constant interval := '30 minutes';
  unresolved_grace constant interval := '10 minutes';
  ist_time     time := (now() at time zone 'Asia/Kolkata')::time;
  blocking_lead record;
begin
  if not exists (select 1 from public.profiles where id = auth.uid() and active) then
    raise exception 'inactive or unknown user';
  end if;

  if ist_time < time '09:00' or ist_time >= time '19:00' then
    raise exception 'Leads can only be claimed between 9:00 AM and 7:00 PM IST.';
  end if;

  if not public.is_admin() then
    select l.id, l.name
      into blocking_lead
      from public.leads l
      join public.call_attempts a
        on a.lead_id = l.id and a.agent_id = auth.uid()
     where l.assigned_to = auth.uid()
       and a.attempted_at > coalesce(l.last_called_at, '-infinity'::timestamptz)
       and a.attempted_at <= now() - unresolved_grace
     order by a.attempted_at asc
     limit 1;

    if blocking_lead.id is not null then
      raise exception 'Save the outcome for % before claiming another lead.',
        coalesce(blocking_lead.name, 'the lead you called');
    end if;
  end if;

  return query
  with picked as (
    select c.id
      from public.leads c
     where c.is_closed = false
       -- unassigned, already mine, or abandoned by someone else
       and (c.assigned_to is null
            or c.assigned_to = auth.uid()
            or c.locked_at < now() - stale_after)
       -- skip callbacks that are not due yet
       and (c.callback_at is null or c.callback_at <= now())
       -- skip leads whose batch admin has switched off — a lead with no
       -- batch has no switch, so it's always eligible; a batch with no
       -- row here yet defaults to active too (see backend/13_batch_active_toggle.sql)
       and (
         c.batch is null
         or coalesce(
              (select bs.is_active from public.batch_settings bs where bs.batch = c.batch),
              true
            )
       )
     order by
       -- due callbacks first
       (c.callback_at is not null) desc,
       c.callback_at asc nulls last,
       -- then never-attempted leads before ones already tried once —
       -- see backend/08_fix_queue_reorder.sql for why: without this, a
       -- lead released back to the pool (SWITCHED_OFF, NO_ANSWER) could
       -- be immediately re-served on the very next claim if it had a low id.
       c.attempts asc,
       c.last_called_at asc nulls first,
       -- random, not sequential-by-id — see backend/11_random_call_order.sql
       random()
     limit 1
     for update skip locked
  ),
  claimed as (
    update public.leads l
       set assigned_to = auth.uid(),
           locked_by   = auth.uid(),
           locked_at   = now()
      from picked p
     where l.id = p.id
    returning l.*
  )
  select * from claimed;
end;
$$;

-- ---------------------------------------------------------------------
-- Admin-only escape hatch for a lead that's genuinely a dead end (lost
-- phone, agent no longer with the company, whatever) rather than one
-- the agent just hasn't gotten to yet — without this, that one lead
-- would block claim_next_lead() for that agent forever. Releases it
-- exactly the way save_disposition() already releases a SWITCHED_OFF/
-- NO_ANSWER lead — back into the open pool, is_closed left alone — and
-- logs the release to lead_edit_log so it shows up on the same trail as
-- every other manual correction to a lead, not as a silent DB edit.
create or replace function public.release_stuck_lead(p_lead_id bigint)
returns void
language plpgsql
security definer set search_path = public
as $$
declare
  prev_assigned_to uuid;
begin
  if not public.is_admin() then
    raise exception 'Only an admin can release a stuck lead.';
  end if;

  select assigned_to into prev_assigned_to from public.leads where id = p_lead_id;
  if not found then
    raise exception 'lead % does not exist', p_lead_id;
  end if;

  update public.leads
     set assigned_to = null,
         locked_by   = null,
         locked_at   = null
   where id = p_lead_id;

  insert into public.lead_edit_log (lead_id, agent_id, field, old_value, new_value)
  values (p_lead_id, auth.uid(), 'assigned_to', prev_assigned_to::text, null);
end;
$$;

grant execute on function public.release_stuck_lead(bigint) to authenticated;
