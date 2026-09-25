// Permanently remove an agent account — only possible when they have no
// history at all (never logged a call, search, SMS outcome, or edit).
//
// call_dispositions.agent_id (and lead_access_log/lead_edit_log/
// lead_search_log/sms_delivery_log.agent_id) are all `not null references
// profiles(id)` with no ON DELETE clause, so Postgres itself refuses to
// delete a profile any of those tables still point to — confirmed directly
// against the live database before writing this function:
//
// ERROR: update or delete on table "profiles" violates foreign key
// constraint "call_dispositions_agent_id_fkey" on table
// "call_dispositions"
//
// That's a hard constraint, not a permission check, and it's exactly the
// audit trail this app is built around — this function does not try to
// work around it (e.g. by deleting the history first). An agent who has
// ever actually worked leads should be archived instead
// (set_agent_active(), backend/19_deactivate_agent.sql), never deleted.
// This exists only for the genuinely history-free case: an account
// created by mistake, or someone who left before their first call.
//
// Restricted to already-archived agents, same reason rename-agent
// (supabase/functions/rename-agent) is: deleting a signed-in agent's
// account out from under them mid-shift is a different, worse problem
// than this function is trying to solve.
//
// Any lead still assigned to them is released back to the pool first —
// set_agent_active(false) already does this on archiving, but this repeats
// it defensively (e.g. a lead re-claimed or re-locked after archiving)
// rather than trusting that step already left nothing behind.
//
// Same service_role-stays-server-side pattern as create-agent/index.ts —
// see CLAUDE.md section 8, "never add a second Edge Function that also
// needs it without the same care."
//
// deno-lint-ignore-file no-explicit-any
import { createClient } from "jsr:@supabase/supabase-js@2";

const ALLOWED_ORIGIN = "https://shishirkush.github.io";

const CORS = {
  "Access-Control-Allow-Origin": ALLOWED_ORIGIN,
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);

  const authHeader = req.headers.get("Authorization") ?? "";
  if (!authHeader.startsWith("Bearer ")) {
    return json({ error: "Missing Authorization header." }, 401);
  }

  let body: { agent_id?: unknown };
  try {
    body = await req.json();
  } catch {
    return json({ error: "Request body must be JSON." }, 400);
  }

  const agentId = typeof body.agent_id === "string" ? body.agent_id : "";
  if (!agentId) return json({ error: "agent_id is required." }, 400);

  const url = Deno.env.get("SUPABASE_URL")!;
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY")!;
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

  // Identify the caller using their own token, same as create-agent — only
  // an admin may permanently delete an account.
  const callerClient = createClient(url, anonKey, {
    global: { headers: { Authorization: authHeader } },
  });

  const { data: userData, error: userErr } = await callerClient.auth.getUser();
  if (userErr || !userData?.user) {
    return json({ error: "Your session has expired. Sign in again." }, 401);
  }

  const { data: callerProfile, error: callerErr } = await callerClient
    .from("profiles")
    .select("role, active, is_admin")
    .eq("id", userData.user.id)
    .single();

  if (
    callerErr || !callerProfile || callerProfile.role !== "supervisor" ||
    !callerProfile.active || !callerProfile.is_admin
  ) {
    return json({ error: "Only an admin can delete agent accounts." }, 403);
  }

  // Admin client — service_role, never exposed beyond this function.
  const admin = createClient(url, serviceKey, {
    auth: { autoRefreshToken: false, persistSession: false },
  });

  const { data: targetProfile, error: targetErr } = await admin
    .from("profiles")
    .select("login_id, role, active")
    .eq("id", agentId)
    .single();

  if (targetErr || !targetProfile) {
    return json({ error: "Agent not found." }, 404);
  }
  if (targetProfile.role !== "agent") {
    return json({ error: "Only agent accounts can be deleted here." }, 400);
  }
  if (targetProfile.active) {
    return json({
      error: "Archive this agent first — deleting a signed-in agent would break their current login.",
    }, 400);
  }

  // Belt-and-suspenders release of anything still assigned to them — see
  // the header comment. Harmless if there was nothing to release.
  const { error: releaseErr } = await admin
    .from("leads")
    .update({ assigned_to: null, locked_by: null, locked_at: null })
    .eq("assigned_to", agentId)
    .eq("is_closed", false);
  if (releaseErr) return json({ error: releaseErr.message }, 400);

  const { error: deleteErr } = await admin.auth.admin.deleteUser(agentId);

  // GoTrue wraps the underlying Postgres error in a generic "Database
  // error deleting user" — it does not pass through the actual "foreign
  // key constraint" text, so matching on that specific wording never
  // fires (confirmed live: that's the exact message this returned).
  // Everything above already ruled out every other failure mode (missing
  // agent, wrong role, still active, lead release itself failing), so at
  // this point any error here is the FK constraint from real history —
  // treat it as that rather than trying to keep guessing GoTrue's wording.
  if (deleteErr) {
    return json({
      error: `"${targetProfile.login_id}" has call history and cannot be permanently deleted — archive them instead.`,
    }, 400);
  }

  return json({ ok: true, login_id: targetProfile.login_id });
});
