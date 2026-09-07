// Create an agent account from a login ID and password only.
//
// Supabase Auth is an email/password system underneath — there is no way to
// remove that from Supabase itself. What this function does is keep that
// detail entirely server-side: the caller sends {login_id, password}, this
// function silently expands login_id to "<login_id>@LOGIN_DOMAIN" (the same
// convention the app and dashboard already use to sign in), and nobody
// downstream of the supervisor ever has to see, type, or think about an
// email address.
//
// The service_role key lives only here, in the Edge Function's runtime
// environment. It is never sent to a browser. This is precisely the
// exception described in CLAUDE.md section 4 for self-serve account
// creation: "an Edge Function holding service_role server-side — not
// putting the key in the page."
//
// deno-lint-ignore-file no-explicit-any
import { createClient } from "jsr:@supabase/supabase-js@2";

const LOGIN_DOMAIN = "telecall.local"; // must match android/app/build.gradle.kts and dashboard/index.html

// Allow the dashboard's own origin only, not "*" — this is a privileged
// endpoint (it mints accounts), so cross-origin callers should not even get
// a CORS-readable response.
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

// login_id shape: lowercase, starts with a letter, dots/underscores/hyphens
// allowed for things like "priya.s" or "rahul_k", 3-32 chars. No "@" — if
// someone types a full address out of habit, reject it here rather than
// silently doubling up on domains later.
const LOGIN_ID_RE = /^[a-z][a-z0-9._-]{2,31}$/;

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);

  const authHeader = req.headers.get("Authorization") ?? "";
  if (!authHeader.startsWith("Bearer ")) {
    return json({ error: "Missing Authorization header." }, 401);
  }

  let body: { login_id?: unknown; password?: unknown };
  try {
    body = await req.json();
  } catch {
    return json({ error: "Request body must be JSON." }, 400);
  }

  const loginIdRaw = typeof body.login_id === "string" ? body.login_id.trim().toLowerCase() : "";
  const password = typeof body.password === "string" ? body.password : "";

  if (!LOGIN_ID_RE.test(loginIdRaw)) {
    return json({
      error:
        "Login ID must be 3-32 characters, start with a letter, and contain only lowercase letters, digits, dots, hyphens or underscores.",
    }, 400);
  }
  if (password.length < 6) {
    return json({ error: "Password must be at least 6 characters." }, 400);
  }

  const url = Deno.env.get("SUPABASE_URL")!;
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY")!;
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

  // Identify the caller using their own token against the anon-scoped
  // client, then check their role with RLS still in force ("read own
  // profile" already permits this). Only a supervisor may create agents.
  const callerClient = createClient(url, anonKey, {
    global: { headers: { Authorization: authHeader } },
  });

  const { data: userData, error: userErr } = await callerClient.auth.getUser();
  if (userErr || !userData?.user) {
    return json({ error: "Your session has expired. Sign in again." }, 401);
  }

  const { data: profile, error: profileErr } = await callerClient
    .from("profiles")
    .select("role, active")
    .eq("id", userData.user.id)
    .single();

  if (profileErr || !profile || profile.role !== "supervisor" || !profile.active) {
    return json({ error: "Only an active supervisor can create agent accounts." }, 403);
  }

  // Admin client — service_role, never exposed beyond this function.
  const admin = createClient(url, serviceKey, {
    auth: { autoRefreshToken: false, persistSession: false },
  });

  const email = `${loginIdRaw}@${LOGIN_DOMAIN}`;

  const { data: created, error: createErr } = await admin.auth.admin.createUser({
    email,
    password,
    email_confirm: true, // no mailbox exists behind this address — confirm immediately
    user_metadata: { full_name: loginIdRaw },
  });

  if (createErr) {
    const msg = /already.*registered|already exists/i.test(createErr.message)
      ? `Login ID "${loginIdRaw}" is already taken.`
      : createErr.message;
    return json({ error: msg }, 400);
  }

  return json({ ok: true, login_id: loginIdRaw, user_id: created.user?.id });
});
