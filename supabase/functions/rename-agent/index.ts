// Rename an archived agent's full name and login ID, and optionally reset
// their password — repurposing an already-archived seat for a new hire
// without going through Supabase Auth's admin UI by hand.
//
// login_id lives in two places that must stay in sync: public.profiles
// (what the dashboard/app show) and auth.users.email (the synthetic
// "<login_id>@LOGIN_DOMAIN" address GoTrue actually authenticates
// against — see backend/03_login_id_and_address.sql). A plain SQL RPC
// can rewrite profiles.login_id but cannot touch auth.users, so this
// follows the same shape as create-agent: a caller-identity check
// against the anon-scoped client, then the actual work through the
// service_role Admin API, which never leaves this function's runtime.
//
// Restricted to archived (inactive) agents on purpose — renaming someone
// mid-shift would invalidate the login ID they're actively signed in
// under. Reassigning that agent's past call history to someone else
// (see backend/25_reassign_archived_agent_history.sql) is a separate
// step the dashboard does before calling this, so a renamed seat doesn't
// silently hand its old call history to whoever takes it over next.
//
// deno-lint-ignore-file no-explicit-any
import { createClient } from "jsr:@supabase/supabase-js@2";

const LOGIN_DOMAIN = "telecall.local"; // must match android/app/build.gradle.kts and dashboard/index.html

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

const LOGIN_ID_RE = /^[a-z][a-z0-9._-]{2,31}$/;

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);

  const authHeader = req.headers.get("Authorization") ?? "";
  if (!authHeader.startsWith("Bearer ")) {
    return json({ error: "Missing Authorization header." }, 401);
  }

  let body: { agent_id?: unknown; full_name?: unknown; login_id?: unknown; password?: unknown };
  try {
    body = await req.json();
  } catch {
    return json({ error: "Request body must be JSON." }, 400);
  }

  const agentId = typeof body.agent_id === "string" ? body.agent_id : "";
  const fullName = typeof body.full_name === "string" ? body.full_name.trim() : "";
  const loginIdRaw = typeof body.login_id === "string" ? body.login_id.trim().toLowerCase() : "";
  const password = typeof body.password === "string" ? body.password : "";

  if (!agentId) return json({ error: "Missing agent_id." }, 400);
  if (!fullName || fullName.length > 100) {
    return json({ error: "Name must be 1-100 characters." }, 400);
  }
  if (!LOGIN_ID_RE.test(loginIdRaw)) {
    return json({
      error:
        "Login ID must be 3-32 characters, start with a letter, and contain only lowercase letters, digits, dots, hyphens or underscores.",
    }, 400);
  }
  // Password is optional — leave it unset to keep the account's current
  // password when only fixing a name/login-id typo, not handing the seat
  // to someone new.
  if (password && password.length < 6) {
    return json({ error: "Password must be at least 6 characters." }, 400);
  }

  const url = Deno.env.get("SUPABASE_URL")!;
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY")!;
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

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

  if (callerErr || !callerProfile || callerProfile.role !== "supervisor" || !callerProfile.active || !callerProfile.is_admin) {
    return json({ error: "Only an admin can rename an agent." }, 403);
  }

  const admin = createClient(url, serviceKey, {
    auth: { autoRefreshToken: false, persistSession: false },
  });

  const { data: target, error: targetErr } = await admin
    .from("profiles")
    .select("id, role, active")
    .eq("id", agentId)
    .single();

  if (targetErr || !target) return json({ error: "Agent not found." }, 404);
  if (target.role !== "agent") return json({ error: "Only agent accounts can be renamed here." }, 400);
  if (target.active) {
    return json({ error: "Archive this agent first — renaming a signed-in agent would break their current login." }, 400);
  }

  const { data: clash } = await admin
    .from("profiles")
    .select("id")
    .neq("id", agentId)
    .ilike("login_id", loginIdRaw)
    .maybeSingle();
  if (clash) {
    return json({ error: `Login ID "${loginIdRaw}" is already taken.` }, 400);
  }

  const email = `${loginIdRaw}@${LOGIN_DOMAIN}`;
  const updatePayload: Record<string, unknown> = { email, email_confirm: true };
  if (password) updatePayload.password = password;

  const { error: authUpdateErr } = await admin.auth.admin.updateUserById(agentId, updatePayload);
  if (authUpdateErr) {
    const msg = /already.*registered|already exists/i.test(authUpdateErr.message)
      ? `Login ID "${loginIdRaw}" is already taken.`
      : authUpdateErr.message;
    return json({ error: msg }, 400);
  }

  const { error: profileUpdateErr } = await admin
    .from("profiles")
    .update({ full_name: fullName, login_id: loginIdRaw })
    .eq("id", agentId);

  if (profileUpdateErr) return json({ error: profileUpdateErr.message }, 400);

  return json({ ok: true, agent_id: agentId, full_name: fullName, login_id: loginIdRaw });
});
