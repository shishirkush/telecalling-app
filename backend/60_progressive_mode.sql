-- =====================================================================
--  MIGRATION 60 — Progressive calling mode (Android app 1.20.0)
--  Run this in: Supabase Dashboard → SQL Editor → New query → Run
--  Safe to re-run.
-- =====================================================================
--
-- An agent now works in one of two modes (profiles.calling_mode):
--
--   normal       exactly as before — one lead at a time, save its outcome
--                before taking the next.
--   progressive  the app dials leads back to back; each finished call joins a
--                "to save" list and outcomes are saved later, in the list.
--                After every 10 calls the agent must save them all before the
--                next 10 are dialled.
--
-- What changes on the server, for progressive agents only (normal agents see
-- no difference):
--   * claim_next_lead(): the 30-second claim cooldown becomes 3 seconds, and
--     the "save the outcome of the lead you called first" block (10-minute
--     grace) becomes a count: at most 10 called-but-unsaved leads at a time,
--     enforced here so a modified app can't go past it.
--   * refresh_my_locks(): the app calls this every few minutes while calls
--     are waiting to be saved, so the 30-minute stale-lock release (which
--     parks called-but-unsaved leads, migration 55/56) doesn't take them away
--     from an agent who is still working through the list.
--   * my_unsaved_calls(): the list itself, with the phone's talk time per call.
--   * set_calling_mode(): the only way the mode changes; refused while calls
--     are waiting to be saved.

alter table public.profiles
  add column if not exists calling_mode text not null default 'normal';

alter table public.profiles drop constraint if exists profiles_calling_mode_check;
alter table public.profiles
  add constraint profiles_calling_mode_check check (calling_mode in ('normal', 'progressive'));

-- Called-but-unsaved leads of one agent: assigned to them, still open, with a
-- call attempt newer than the last saved outcome.
create or replace function public.my_unsaved_calls()
returns table (lead_id bigint, last_attempt_at timestamptz, talk_secs int, dial_secs int)
language sql
stable
security definer set search_path = public
as $$
  select l.id, a.attempted_at, a.duration_secs, a.dial_secs
    from public.leads l
    join lateral (
      select x.attempted_at, x.duration_secs, x.dial_secs
        from public.call_attempts x
       where x.lead_id = l.id and x.agent_id = auth.uid()
       order by x.attempted_at desc
       limit 1
    ) a on true
   where l.assigned_to = auth.uid()
     and not l.is_closed
     and a.attempted_at > coalesce(l.last_called_at, '-infinity'::timestamptz)
   order by a.attempted_at;
$$;

grant execute on function public.my_unsaved_calls() to authenticated;

create or replace function public.refresh_my_locks()
returns void
language sql
security definer set search_path = public
as $$
  update public.leads
     set locked_at = now()
   where assigned_to = auth.uid()
     and locked_at is not null
     and not is_closed;
$$;

grant execute on function public.refresh_my_locks() to authenticated;

create or replace function public.set_calling_mode(p_mode text)
returns void
language plpgsql
security definer set search_path = public
as $$
begin
  if p_mode is null or p_mode not in ('normal', 'progressive') then
    raise exception 'unknown calling mode %', p_mode;
  end if;

  if not exists (select 1 from public.profiles where id = auth.uid() and active) then
    raise exception 'inactive or unknown user';
  end if;

  if exists (select 1 from public.my_unsaved_calls()) then
    raise exception 'Save the outcomes of your pending calls before switching mode.';
  end if;

  update public.profiles set calling_mode = p_mode where id = auth.uid();
end;
$$;

grant execute on function public.set_calling_mode(text) to authenticated;

-- claim_next_lead(): migration 55/56's version plus the progressive-mode rules.
create or replace function public.claim_next_lead()
returns setof public.leads
language plpgsql
security definer set search_path = public
as $$
declare
  stale_after      constant interval := '30 minutes';
  unresolved_grace constant interval := '10 minutes';
  max_unsaved      constant int := 10;
  claim_cooldown   interval := '30 seconds';
  ist_time         time := (now() at time zone 'Asia/Kolkata')::time;
  blocking_lead    record;
  v_campaign_id    bigint;
  v_mode           text;
  v_unsaved        int;
  v_claimed        public.leads;
begin
  if not exists (select 1 from public.profiles where id = auth.uid() and active) then
    raise exception 'inactive or unknown user';
  end if;

  if ist_time < time '09:00' or ist_time >= time '19:00' then
    raise exception 'Leads can only be claimed between 9:00 AM and 7:00 PM IST.';
  end if;

  select calling_mode into v_mode from public.profiles where id = auth.uid();
  if v_mode = 'progressive' then
    claim_cooldown := interval '3 seconds';
  end if;

  if not public.is_admin() then
    if exists (
      select 1 from public.profiles
       where id = auth.uid() and last_claim_at > now() - claim_cooldown
    ) then
      raise exception 'Wait a moment before claiming another lead.';
    end if;

    if v_mode = 'progressive' then
      select count(*) into v_unsaved from public.my_unsaved_calls();
      if v_unsaved >= max_unsaved then
        raise exception 'Save the outcomes of your last % calls before dialling more.', max_unsaved;
      end if;
    else
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
  end if;

  select current_campaign_id into v_campaign_id from public.profiles where id = auth.uid();

  if not public.is_admin() and v_campaign_id is null then
    raise exception 'Pick a campaign before claiming leads.';
  end if;

  -- Release locks left behind for 30+ minutes. A Call-later lead only unlocks
  -- (it stays with its agent); any other lead goes back to the pool, and the
  -- trigger from migration 55 parks it if it was called but never saved.
  update public.leads
     set assigned_to = case when callback_at is null then null else assigned_to end,
         locked_by   = null,
         locked_at   = null
   where locked_at < now() - stale_after
     and is_closed = false;

  with picked as (
    select c.id
      from public.leads c
     where c.is_closed = false
       -- A progressive agent holds several claimed leads at once; never hand
       -- one of those back as "next" (only their own unlocked Call-later leads).
       and (c.assigned_to is null
            or (c.assigned_to = auth.uid()
                and (v_mode is distinct from 'progressive' or c.locked_at is null)))
       and (c.callback_at is null or c.callback_at <= now())
       and c.deferred_at is null
       and (
         public.is_admin()
         or exists (
              select 1 from public.batch_settings bs
               where bs.batch = c.batch
                 and bs.campaign_id = v_campaign_id
                 and bs.is_active
            )
       )
     order by
       (c.callback_at is not null) desc,
       c.callback_at asc nulls last,
       (coalesce(c.last_status = 'NO_ANSWER', false) or c.deferred_at is not null) asc,
       c.attempts asc,
       c.last_called_at asc nulls first,
       c.random_rank
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
  select * into v_claimed from claimed;

  if v_claimed.id is not null then
    update public.profiles set last_claim_at = now() where id = auth.uid();
    return next v_claimed;
  end if;

  return;
end;
$$;
