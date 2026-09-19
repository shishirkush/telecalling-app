# CLAUDE.md — project context

Read this before changing anything. It records what exists, what was
deliberately decided, and — importantly — **what has never been compiled**.

---

## 1. What this is

An outbound telecalling system for an Indian lending/credit-card campaign.
Three parts, all on free tiers:

| Part | Stack | Role |
|---|---|---|
| `android/` | Kotlin, Jetpack Compose, OkHttp | The agent's tool. Shows one lead, dials it, records the outcome. |
| `backend/` | PostgreSQL on Supabase | Lead pool, call history, auth, row-level security. Plain SQL — run it in the Supabase SQL editor. |
| `dashboard/` | Single-file HTML + supabase-js | Supervisor view: KPIs, outcome breakdown, agent table, callback queue, CSV lead import. |

**Agent flow:** sign in → tap *Next lead* → server hands them exactly one
record → tap the number to call → pick a status → save → repeat.

---

## 2. The data contract

Ten read-only fields the agent sees. These names are fixed — they are the
`leads` table columns, the `Lead` data class fields, and the CSV import headers,
and they must stay in sync across all three.

```
name · pan · mobile · email · dob · annual_income_range · company · ici_cr_lmt
address
```

> `company` was renamed from the original spec's "PP Code Company Name".
> Do not rename it back.
>
> `address` was added by `03_login_id_and_address.sql`. Capped at 2000
> characters by a CHECK constraint, not only by the UI — verified rejecting
> 2001. The CSV importer truncates at 2000 so one over-long cell cannot fail
> an entire chunk. It is masked with the other PII when `MASK_SENSITIVE` is on
> (PIN code kept, street hidden).

Five outcomes the agent records (`call_status` enum):

```
SWITCHED_OFF · WRONG_NUMBER · NOT_INTERESTED · CALL_LATER · LEAD
```

Conditional fields, **enforced by CHECK constraints, not just the UI**:

- `LEAD` requires `lead_quality` ∈ {HOT, WARM, COLD}; any other status forbids it.
- `CALL_LATER` requires `callback_at`.
- `remarks` is free text, optional, always visible, ≤ 2000 chars.

---

## 3. File map

```
backend/01_schema.sql          enums, tables, RLS, 3 RPCs, 2 reporting views
backend/02_seed_sample_leads.sql   10 fake leads for testing
backend/03_login_id_and_address.sql  leads.address, profiles.login_id,
                                     updated handle_new_user trigger
backend/04_upsertable_batch_index.sql  makes (batch, mobile) non-partial so
                                     ON CONFLICT can infer it
backend/05_fix_profile_privilege_escalation.sql  SECURITY: stops an agent
                                     promoting themselves to supervisor
backend/06_agent_edit_lead_details.sql  save_lead_details() RPC +
                                     lead_edit_log audit table
backend/07_no_answer_status.sql     adds the NO_ANSWER call outcome
backend/08_fix_queue_reorder.sql    claim_next_lead: don't re-serve a lead
                                     right after it's released
backend/09_manager_scoping.sql      profiles.is_admin, manager_agents,
                                     manages_agent() — see section 4
backend/10_database_report.sql      v_batch_summary (admin-only whole-pool report)
backend/11_random_call_order.sql    claim_next_lead: random pick, not
                                     sequential-by-id, within a priority tier
backend/12_delete_batch.sql          delete_batch() RPC — admin-only,
                                     cascades to that batch's call history too
backend/13_batch_active_toggle.sql  batch_settings (admin-only on/off switch
                                     per batch); claim_next_lead skips an
                                     inactive batch — see section 4
backend/14_sms_delivery_log.sql     sms_delivery_log + v_sms_outcomes — remote
                                     visibility into Apply Card SMS failures
                                     on agents' own phones — see section 4
backend/15_app_version_report.sql   profiles.app_version(_reported_at),
                                     v_app_versions — see section 4
backend/16_lead_search.sql          search_lead() RPC — whole-database
                                     mobile/PAN lookup, exact-match only,
                                     audited + rate-limited — see section 4
backend/17_device_info_report.sql   profiles.device_model — real phone vs.
                                     a desktop Android player — see section 4

android/app/src/main/java/com/telecall/app/
  MainActivity.kt              screen routing + the notification deep-link
                                     (EXTRA_OPEN_LEAD_ID, singleTask launch mode)
  AppViewModel.kt              ALL UI state + actions live here
  DeviceSupport.kt              blocks a device with no telephony radio —
                                     checked before sign-in — see section 4
  TelecallApplication.kt       builds the repository singleton, creates the
                                     "callbacks" notification channel
  data/Models.kt               Lead, CallStatus, LeadQuality, Outcome<T>
  data/SupabaseClient.kt       hand-rolled OkHttp client (auth + PostgREST)
  data/LeadRepository.kt       the only thing the ViewModel talks to
  call/SimManager.kt           SIM enumeration, placing the call, and texting
                                     the Apply Card link alongside it — section 4
  call/CallbackScheduler.kt    AlarmManager schedule/cancel for one lead's
                                     Call Later reminder — see section 4
  call/CallbackAlarmReceiver.kt  fires at the scheduled time, posts the
                                     notification, deep-links back to the lead
  ui/LoginScreen.kt
  ui/LeadQueueScreen.kt        Queue / CallBacks / Apply Card tabs, "Next
                                     lead" FAB — see section 4
  ui/LeadDetailScreen.kt       record view, call button, SIM dialog, outcome form
  ui/SearchScreen.kt           whole-database mobile/PAN lookup, read-only —
                                     see section 4
  ui/UnsupportedDeviceScreen.kt  the hard stop for DeviceSupport.kt — no
                                     bypass, not even an already-signed-in session
  ui/Format.kt                 currency/date/mask helpers  ← masking lives here
  ui/UpdateBanner.kt           shown over Queue/Detail when an update exists
  ui/theme/Theme.kt
  update/UpdateChecker.kt      polls GitHub Releases, compares to
                                     BuildConfig.VERSION_NAME — see section 4
  update/UpdateInstaller.kt    DownloadManager + FileProvider → system
                                     installer — see section 4

dashboard/index.html           entire dashboard, one file, no build step.
                                     "Create agent" form calls the Edge Function below
webapp/index.html               mobile-web agent app for iPhone agents — separate repo
                                     (telecalling-webapp), same one-file/no-build pattern,
                                     same Supabase project/RLS/RPCs as the Android app
supabase/functions/create-agent/index.ts  service_role lives ONLY here —
                                     the one place other than direct SQL access
                                     that key is allowed to exist
.github/workflows/android-build.yml
```

Deploying the Edge Function after an edit:
`npx supabase functions deploy create-agent --project-ref bpzfwdcbzkkvwearyzkh`
(needs `npx supabase login` once — opens a browser, no token ever touches
this repo or any chat transcript).

State management is deliberately plain: one `UiState` data class in a
`MutableStateFlow`, no DI framework, no navigation library. Do not introduce
Hilt/Koin/Navigation-Compose unless the app grows enough to need them.

---

## 4. Decisions that were made on purpose

Do not "fix" these without checking why they are this way.

**Call Later reminders are a local notification, not a server push.**
Saving a Call Later disposition schedules an `AlarmManager` alarm
(`call/CallbackScheduler.kt`) for the exact moment the agent picked;
`call/CallbackAlarmReceiver.kt` fires at that time and posts a
notification that deep-links straight back to the lead
(`MainActivity.EXTRA_OPEN_LEAD_ID`, which is why `MainActivity` is
`singleTask` in the manifest — a tapped notification must land in the
existing instance via `onNewIntent`, not stack a second one).

Two things this trades away, deliberately:
- **No boot receiver.** AlarmManager alarms survive the app being
  killed but not a device reboot. Instead of a `BOOT_COMPLETED`
  receiver, `AppViewModel.refreshQueue()` re-arms a reminder for every
  open callback in the queue on every load — sign-in, manual refresh,
  claiming a lead. This self-heals within one app-open after a reboot
  rather than the instant it happens. Revisit if a real device shows
  reminders silently missing after a restart.
- **Exact-alarm permission is best-effort.** `SCHEDULE_EXACT_ALARM` is
  requested in the manifest, but `CallbackScheduler` checks
  `canScheduleExactAlarms()` and falls back to a plain (inexact) `set()`
  rather than skip the reminder — verified on the emulator, where that
  permission is not auto-granted: the alarm still fired, a bit later
  than the exact instant, well inside Android's own window rules. On a
  real device, walk the agent through **Settings → Apps → Telecalling
  → Alarms & reminders → Allow** for on-time delivery; without it the
  reminder still arrives, just not necessarily at the exact minute.

Verified end-to-end on the emulator, not just by code review: scheduled
a callback ~10 minutes out, confirmed the real alarm registered in
`dumpsys alarm`, waited for it to actually fire, confirmed the posted
notification's title/content in `dumpsys notification`, then tapped it
and confirmed it opened the correct lead.

**Agents sign in with a login ID, never an email.** The supervisor issues the
ID and password out of band and tells the agent. GoTrue is an email/password
service, so `SupabaseClient.loginIdToEmail()` expands `rahul.k` to
`rahul.k@telecall.local` (`LOGIN_DOMAIN` in `app/build.gradle.kts`) before the
request goes out. That synthetic address is an internal detail — never shown,
never mailed. Doing it this way keeps Supabase Auth intact, which means
`auth.uid()`, every RLS policy, session refresh and password hashing all keep
working untouched. Do not replace this with a hand-rolled users table: RLS
depends on `auth.uid()` and would have to be rewritten from scratch.

`profiles.login_id` stores the ID so a supervisor sees `rahul.k`, not the
synthetic address. It is populated by the `handle_new_user` trigger.

The dashboard signs in the same way, via its own `loginIdToEmail()`. **The
domain is written down twice** — `LOGIN_DOMAIN` in `app/build.gradle.kts` and
a `const LOGIN_DOMAIN` near the top of `dashboard/index.html`. They must match.
If they drift, sign-in breaks on one platform and not the other, which is a
confusing failure to debug. Change both together.

**The dashboard's Supabase URL and anon key are hardcoded**, not typed in by
each supervisor. There used to be a one-time "Connect to Supabase" screen
that stored them in `localStorage`; it's gone — a supervisor now sees only
the login form. The anon key is meant to be public (RLS is the actual
control), so baking it into the page source grants nothing new. This is a
**third** place `SUPABASE_URL`/`SUPABASE_ANON_KEY` live, alongside
`android/local.properties` and the `create-agent` function's own env vars —
if the project is ever migrated again (as it was, Sydney → Mumbai), update
the `SUPABASE_URL`/`SUPABASE_ANON_KEY` constants near the top of
`dashboard/index.html`'s `<script>` too, or the dashboard silently keeps
talking to the old project while the app talks to the new one.

**Agents are created from the dashboard's own "Create agent" form** — login
ID and password only, no email field anywhere. Creating a user needs the
`service_role` key, and that key must never reach a client, so the form
doesn't call Supabase directly: it posts `{login_id, password}` to the
`create-agent` Edge Function (`supabase/functions/create-agent/index.ts`),
which does the `service_role` work server-side and returns a plain
success/error. The dashboard itself never holds anything more than the anon
key it already had.

The function re-checks the caller is an active supervisor itself
(`profiles.role = 'supervisor'`) before creating anything — the button being
hidden from agents in the UI is not what enforces that, the function's own
check is. Verified against the live project: no auth header → 401, an agent's
token → 403, a supervisor's token → 200; also a duplicate login ID, a
malformed one, and a short password are all rejected with a specific message
rather than a raw Postgres error.

Supervisor accounts still go through **Authentication → Users** in the
Supabase dashboard by hand — deliberately not self-serve, since a
supervisor's role can read and change every lead in the database. Section 6
covers both paths.

**No Supabase SDK.** `SupabaseClient.kt` talks to the REST API over OkHttp by
hand. This was chosen so a Supabase SDK major-version bump can't break the
build. Adding `supabase-kt` would undo that.

**Agents never read the `leads` table directly for new work.** They call the
`claim_next_lead()` RPC, which uses `FOR UPDATE SKIP LOCKED` so two agents
tapping at the same instant get two different customers. Replacing this with a
`SELECT ... LIMIT 1` reintroduces double-dialling.

**Agents cannot change their own `role`.** They used to be able to. The
original `"update own name"` policy on `profiles` constrained the *row*
(`id = auth.uid()`) but an RLS policy cannot constrain *columns*, so an agent
could `PATCH /rest/v1/profiles?id=eq.<self> {"role":"supervisor"}` and promote
themselves — unlocking read access to every lead and write access to the pool.
Confirmed exploitable on a live project, then fixed in
`05_fix_profile_privilege_escalation.sql` with column-level grants
(`grant update (full_name)` only) plus a defensive trigger. Re-verified after
the fix: self-promotion and toggling `active` both return 403, while renaming
yourself still works. **Never `grant update` on all of `profiles` again.**

**Agents have no write access to `leads` at all.** Outcomes go through
`save_disposition()` (`security definer`), which first verifies the lead is
assigned to the caller. RLS grants agents `SELECT` only on their own rows.

**Agents can correct customer details, but only through an RPC.** Leads now
arrive with as little as a mobile number, so `save_lead_details()` lets the
agent save what they learn on the call. It is the same shape as
`save_disposition`: security definer, ownership checked first, and a fixed
column list. Editable are `name`, `email`, `dob`, `company`,
`annual_income_range`, `address`.

`mobile`, `pan` and `ici_cr_lmt` are **not parameters of the function**, so
they cannot be reached from a client at all — that is the enforcement, not the
UI. Mobile is the dial target and half the `(batch, mobile)` duplicate key; a
bad number is a `WRONG_NUMBER` outcome, not an edit. PAN is bank-supplied KYC
identity and the most damaging field to get wrong. The credit limit is the
bank's offer. Adding any of them means a deliberate decision, not a tweak.

A NULL argument means "leave this column alone", never "clear it" — clearing
is a supervisor action, not something to do mid-call.

Every change is written to **`lead_edit_log`** (field, old value, new value,
who, when). `lead_access_log` answers "who saw this record"; this answers "who
changed it, and what did it say before". Do not drop it: without it an agent's
typo silently overwrites the bank's own data with no way back.

**CSV import upserts, and only the supervisor dashboard can do it.** There is
no import in the Android app, and there never should be: RLS gives agents
`SELECT` on their own rows only, so an agent's token cannot insert leads even
if a file picker were added — and bulk KYC-grade PII on a handset is exactly
what the RLS design exists to avoid.

The import uses `upsert(..., { onConflict: "batch,mobile", ignoreDuplicates:
true })`. The 500-row chunks are **not** one transaction, so a dropped
connection leaves earlier chunks committed; with a plain `insert`, re-uploading
the same file then collided with the unique index and the whole retry failed.
Upserting makes a re-upload safe and resumable. `ignoreDuplicates` (rather than
merge) is deliberate: a lead already being worked keeps its assignment,
`last_status` and call history instead of being reset by a re-import.

This is why `04_upsertable_batch_index.sql` exists — Postgres cannot infer a
*partial* index for `ON CONFLICT`, and the original index was
`... where batch is not null`, which failed with
`42P10: there is no unique or exclusion constraint matching the ON CONFLICT
specification`. Dropping the predicate is semantically neutral (NULLs are
distinct in a unique index anyway). **Don't re-add the `WHERE` clause.**

**`call_dispositions` is append-only history.** `leads.last_status` is just a
cache of the newest row. Never `UPDATE` a disposition; insert another.

**Stale locks expire after 30 minutes** so an agent who kills the app mid-call
doesn't strand records.

**`READ_CALL_LOG` is not requested, on purpose.** Google restricts it to
default-dialer apps and rejects telecalling apps that ask. This is why call
duration is not captured. If duration becomes a requirement, the answer is
server-side calling (Exotel / Knowlarity / Twilio), not a permission.

**PII is shown unmasked** — an explicit product decision, against my
recommendation. The compensating control is `lead_access_log`, written on every
lead open. `MASK_SENSITIVE` in `android/app/build.gradle.kts` flips it; the
masking logic already exists in `ui/Format.kt`.

**`claim_next_lead()`'s final tiebreaker is `random()`, not `id asc`.**
Everything above it in the `ORDER BY` (due callbacks first, then
untouched-before-retried, then least-recently-touched) is unchanged —
only leads that are otherwise equal get shuffled. This stops agents
racing to claim low-id records first and leaving the tail of a batch
under-called. `backend/11_random_call_order.sql`.

**A batch's on/off switch defaults to active, both for existing batches
and new imports.** `batch_settings` (`backend/13_batch_active_toggle.sql`)
holds one row per batch; `claim_next_lead()` skips a batch only when
its row says `is_active = false` explicitly. A batch with no row at
all — including every batch that existed before this migration, via
its backfill — is treated as active by `coalesce(..., true)`, both in
`claim_next_lead()` and in `v_batch_summary`. This was deliberate: an
admin should have to explicitly switch a batch off to stop it being
served, never the other way around, so this migration (or a batch
somehow missing its settings row) cannot silently stop calling that
was already happening. A lead with no batch has no switch to flip and
is always eligible — the dashboard's toggle column shows "always on"
for the `(no batch)` row rather than a button.

The dashboard writes `batch_settings` directly (`sb.from("batch_settings")
.upsert(...)`), same as it writes `leads` directly for CSV import —
RLS (admin-only, matching `leads`) is the enforcement, not an RPC. This
is a reversible settings flip, not a destructive action like
`delete_batch()`, so the confirmation is a plain `confirm()` before
switching OFF (not the typed-name prompt Delete uses), and there's no
confirmation at all for switching back ON.

**Deleting a batch is admin-only and irreversible, so the dashboard
makes it deliberately hard to do by accident.** `delete_batch()`
(`backend/12_delete_batch.sql`) checks `is_admin()` itself — the
button being hidden from agents in the dashboard UI is not what
enforces that. It counts and deletes that batch's `call_dispositions`
before deleting the `leads` rows (which would cascade anyway), returns
both counts, and raises if the batch name matched zero rows rather
than silently no-op'ing. The dashboard button (`#batchTable .delete-batch`)
requires the admin to type the batch's exact name into a `prompt()`
before calling the RPC — there is no plain confirm dialog for this one.

**The CallBacks tab is a client-side split of the same queue, not a
second query.** `myQueue()` already returns every open lead assigned to
the agent in one call; `LeadQueueScreen` just partitions
`state.queue` into `callbackAt != null` vs `callbackAt == null` and
renders whichever `TabRow` tab is selected. Re-dispositioning from
either tab reuses the existing `LeadDetailScreen` + `saveDisposition()`
path unchanged — there is no separate "callback mode". An overdue
callback (`callbackAt` in the past) renders in red via `Format.isPast()`.

**"Apply Card" is a `Tab` that isn't really a tab.** It sits in the same
`TabRow` as Queue/CallBacks for visual consistency, but its `onClick`
just fires `ACTION_VIEW` for `https://www.cardadda.in/` in the device
browser and leaves `selectedTab` untouched — it never renders selected
and there is no third list behind it. Requires the `<queries>` entry
for `https` `ACTION_VIEW` in the manifest (Android 11+ package
visibility) or the intent silently fails to resolve on some OEM builds.

**Every call attempt also texts the Apply Card link, transparently.**
`SimManager.sendApplyCardSms()` fires right after `placeCall()` inside
`AppViewModel.dial()`, sending `"Apply for the best Credit Card,
cardadda.in/sms"` to the same number being called, on the same SIM
when one was chosen. `SEND_SMS` is requested in the same permission
array as `CALL_PHONE`/`READ_PHONE_STATE` (`LeadDetailScreen`'s
`callPerms`), so it's one system prompt, not two. **This was a
deliberate scope decision, not an oversight**: the original ask was
for the SMS to be hidden from the Sent folder and deleted immediately
after sending, which was declined — that pattern (send silently, hide
the evidence from the device's own owner) is indistinguishable from
SMS fraud/spyware, and there is no way to delete from the system SMS
provider without the app becoming the device's default SMS handler, a
disproportionate permission elevation for this. The shipped version is
the transparent alternative the user explicitly approved instead: sent
through the plain `SmsManager` API, lands in the phone's own Sent
folder like any other text. Verified live: `content://sms/sent`
shows the message with the correct number and body after a real test
call. **Do not add code that deletes or hides this message from the
system SMS provider.**

`sendApplyCardSms()` is fire-and-forget — a missing `SEND_SMS` grant or
a carrier hiccup must never surface to the agent as if the call itself
had failed; the call result is reported independently in `dial()`.

**The SMS's actual send result is reported to the backend, not just
logged locally.** Agents are remote and their phones can never be
plugged in for `adb logcat` — a report of "the SMS isn't sending" was
otherwise a dead end. `sendTextMessage()`'s `sentIntent` fires
`SimManager`'s registered `BroadcastReceiver` with the real outcome
(`sent`, `no_service`, `radio_off`, a denied `SEND_SMS` permission,
etc., even a null-number no-op), which flows through a callback
`SimManager` exposes (set once by `AppViewModel`) to
`log_sms_outcome()` — `sms_delivery_log` / `v_sms_outcomes`
(`backend/14_sms_delivery_log.sql`). Same reasoning as
`lead_access_log`: an audit trail written by the agent's own phone is
the only way to see what happened on a device you can't hold. This
reporting call is itself fire-and-forget from `AppViewModel` — it must
never be able to block or fail the call/SMS flow it is only observing.
`SimManager` stays telephony-only and has no network/repo dependency;
`AppViewModel` owns the side effect, same separation as everywhere
else in this app.

**The app checks GitHub Releases for updates itself — there is no other
mechanism, because this app is sideloaded, not Play-distributed.**
`update.UpdateChecker` hits `api.github.com/repos/.../releases/latest`
once per cold launch (`AppViewModel.init`), compares `tag_name` against
`BuildConfig.VERSION_NAME`, and — if newer — surfaces an `UpdateInfo`
that `MainActivity` renders as `UpdateBanner` over Queue/Detail (never
Login, and never blocking: this app's job is placing calls, and a
briefly unreachable GitHub must not gate that). Tapping Update calls
`update.UpdateInstaller`, which downloads the release APK via
`DownloadManager` into the app's own external-files directory (no
storage permission needed on any supported API level) and, on
completion, hands a `FileProvider` content URI to the system installer.

**Critical, easy to silently break: `versionCode` and `versionName` in
`app/build.gradle.kts` must be bumped on every single release, matching
the git tag.** Every release through v1.6.0 shipped with these stuck at
their initial `1` / `"1.0.0"` — nothing could ever have told an
installed app it was behind, which is exactly the "which agents are on
the latest build" question that motivated this feature. Two distinct
failure modes if this slips again:
- Forget to bump `versionName` → `UpdateChecker` never detects the new
  release exists (it looks identical to what's already installed).
- Bump `versionName` but not `versionCode`, to something *lower* than
  what real phones already have installed → the banner correctly shows
  and downloads fine, but the system installer rejects it with
  `INSTALL_FAILED_VERSION_DOWNGRADE`, silently, with no way for the
  agent to explain what happened. Caught exactly this way in testing:
  a disposable test build with a higher `versionCode` than the real
  (buggy, stuck-at-1) v1.6.0 asset reproduced the failure precisely.
  `versionCode` only ever needs to keep increasing release to release;
  it is not otherwise compared to anything.

**Android will never let this app silently replace itself.** Even with
`REQUEST_INSTALL_PACKAGES` declared, the very first install from this
source prompts the agent through Settings → "Allow from this source"
once, then every install (including every future update) still shows
the system's own "Update this app?" confirmation. That is intentional
OS behavior for a sideloaded app, not a bug to route around — the same
tier of one-time friction as the SEND_SMS and exact-alarm permissions
elsewhere in this app.

Verified end-to-end on the emulator: a disposable build reporting
itself as an older version correctly showed the banner, downloaded the
real latest GitHub release via `DownloadManager`, and handed off to the
system installer, which recognized it as a legitimate update to the
same signed app (not a fresh/unknown install) before the version-code
issue above was found and fixed.

**The app reports its own version to the backend — "which agents have
updated" should never again be answered by inference.** Before
`backend/15_app_version_report.sql`, this question got asked three
times and answered three different indirect ways (recent
`call_dispositions` activity, presence of rows in `sms_delivery_log`),
each of which could only say "on some build newer than X," never the
exact version, and said nothing about an agent who simply hadn't
called anyone recently. `report_app_version()` is called once per
sign-in-backed launch (`AppViewModel.loadProfileAndQueue()`, right
alongside `update.UpdateChecker`'s own check) and writes
`BuildConfig.VERSION_NAME` straight to `profiles.app_version` — a
direct, exact, per-agent answer via `v_app_versions`. Fire-and-forget
in its own coroutine, same as `logSmsOutcome`/`logLeadView`: telemetry
must never be able to slow down or fail the profile/queue load it
rides alongside.

**Customer search is a deliberate, narrow, audited exception to "agents
read only their assigned leads" — not a relaxation of that RLS
boundary.** The ask was real: a customer calls an agent back, but the
lead is no longer in that agent's queue (closed weeks ago, or worked by
a different agent originally), and there was no way to pull up their
name/address without already knowing which batch it's in. Letting an
agent browse the whole `leads` table would reopen exactly the
harvesting risk discussed and rejected earlier in this project's
history (see "Is it possible for someone with agent access to steal the
database" in prior conversation — RLS stopping bulk reads was the main
defense). `search_lead()` (`backend/16_lead_search.sql`) is the
narrowest version of "yes, but": **exact match only** (a full 10-digit
mobile or a validly-shaped PAN — no `ILIKE`, no partial/substring
search, so an agent has to already have the customer's real mobile or
PAN in hand, which is exactly the stated scenario), every call **logged**
to `lead_search_log` (masked to the last 4 characters of the query —
enough to correlate a search with its result, not a second PII copy),
and **rate-limited to 30/hour per agent** so even a scripted loop with a
stolen token can only harvest a couple dozen exact-match records an
hour rather than the whole table. `v_search_activity` gives admin an
at-a-glance abuse signal — an agent with far more searches than calls,
or a wall of zero-match searches, reads as guessing rather than looking
up a real callback.

**Read-only at the client, on purpose — not enforced by the RPC.**
`search_lead()` doesn't care whether the returned lead is currently
assigned to the caller; `ui/SearchScreen.kt` simply never renders a
call button, disposition form, or edit affordance for a search result,
only a dismissible read-only detail dialog. The actual write guard is
unchanged and lives where it always has: `save_disposition` /
`save_lead_details` still require `assigned_to = auth.uid()` (or
admin). A search result becomes actionable only if the lead is
separately re-claimed through the normal assignment path — searching
for it does not assign it.

**The three fire-and-forget telemetry calls (`logSmsOutcome`,
`reportAppVersion`, `logLeadView`) retry up to 3 times, 2 seconds apart,
via `LeadRepository.retryRpc()`.** Found by investigating why two real
agents (Manisha, Kishan) who had confirmed-updated to v1.7.1 and were
actively calling showed zero rows in `sms_delivery_log` — including one
call that definitely went through the in-app "tap to call" path (it had
a `sim_slot` recorded), which should have fired an outcome report no
matter what happened. A single dropped packet on a real mobile
connection — never exercised by the emulator's Wi-Fi — was silently
erasing the signal forever, with no UI to retry from the way a call or
a save has.

**Caveat discovered while verifying this fix, not yet resolved:**
placing a call backgrounds the app immediately — the system Phone UI
takes over the instant `dial()` fires, on every call, unconditionally.
Testing found that if the app process gets frozen or killed by Android
while backgrounded during that window (confirmed happening on the
emulator via `ActivityManager: freezing ... com.telecall.app` in
logcat, well within the few seconds the retry needs), the in-flight
retry coroutine dies with it and the report is lost regardless of how
many attempts were configured — this is a fundamentally different,
harder problem than a transient network blip, since `viewModelScope`
offers no persistence across process death. The retry fix is a real,
verified improvement (confirmed landing successfully end-to-end after
a real call with the app staying alive) for the "network hiccuped but
the app kept running" case, which is likely the more common one — but
it may not be the whole story for Manisha/Kishan specifically if their
real phones are aggressively freezing/killing backgrounded apps (lower
RAM, more aggressive OEM battery management than a dev machine's
emulator). If missing SMS reports persist after this ships, the next
step is a `WorkManager`-backed persisted retry rather than a bigger
number here — that survives process death, this doesn't.

**`profiles.device_model` (`Build.MANUFACTURER` + `Build.MODEL`) exists
to settle "is this a real phone" without guessing.** Two real agents
(Kishan, Manisha) showed `sim_slot = null` on every single call, across
every app version, and zero SMS delivery reports even after the retry
fix above shipped and was confirmed working. That specific pattern —
never a resolved SIM, never a successful SMS, but dispositions still
saving normally — is what a desktop Android player (BlueStacks,
LDPlayer, NoxPlayer, MEmu; all common in India, all plausible for a
telecalling desk since they give a bigger screen and physical keyboard)
looks like from the backend: no real cellular radio, so `dial()`'s
`CallResult.Dialled` branch and `sendApplyCardSms()` both have nothing
to attach to. Reported alongside the existing version report
(`report_app_version()`, same fire-and-forget + retry treatment) rather
than as a separate call. The dashboard's App versions table flags a
`device_model` containing `bluestacks`/`nox`/`ldplayer`/`memu`/
`genymotion`/`generic`/`sdk_gphone` in red — that list is Android
emulator/player signatures, not an exhaustive detector, and exists to
make the answer visible at a glance rather than to gate anything.

**`DeviceSupport.kt` turns that visibility into an actual hard block —
a device with no telephony radio cannot sign in at all.** Checked first
in `AppViewModel.init`, before sign-in restore, before the update
check, before `SimManager`'s SMS-outcome wiring — a blocked device does
none of that, it only ever sees `Screen.UNSUPPORTED_DEVICE`
(`ui/UnsupportedDeviceScreen.kt`), no bypass, even with an already
signed-in session already persisted on disk (verified: an already
logged-in test session hit the block screen immediately, not the
queue). AndroidManifest's `<uses-feature android:name=
"android.hardware.telephony" required="true">` looks like it should
already do this and does not — that attribute is Play Store install
filtering only, never checked by the OS installer for a sideloaded
APK, which is exactly how this app ships. `PackageManager
.hasSystemFeature(FEATURE_TELEPHONY)` is the real, only enforcement;
the manufacturer/model string list is a second signal in case a player
ever fakes that feature. Verified on the emulator by temporarily
adding its own model string to the blocklist (it does have real
telephony normally) — confirmed the block screen render and confirmed
nothing else in the app is reachable past it — then reverted.

**Known, accepted consequence of shipping this: any agent currently
working from a desktop Android player loses access entirely, the
moment they update.** Not a bug — it's what "block desktop installs"
means — but real leads may be sitting assigned to that agent's account
when it happens, inaccessible to them until they move to an actual
phone. Worth confirming with anyone on a suspected desktop setup
before pushing this update to them, not after.

**The Queue tab shows one lead at a time, not the whole assigned
batch — `ui/LeadQueueScreen.kt`'s `visibleLeads.first()`, not a
`LazyColumn` of all of them.** Until this, every currently-assigned
lead's name and mobile number rendered on screen simultaneously — a
supervisor's concern, not a bug report: seeing (and so screenshotting,
copying, photographing) many customers' contact details in one glance
is a materially bigger exposure than seeing one at a time, even though
both are already scoped by RLS to just that agent's own leads. Ordering
is unchanged — `visibleLeads` is still the server's priority order
(due callbacks first, then least-recently-touched), so `.first()` is
genuinely the next lead to work, not an arbitrary one. Saving a
disposition still refreshes the queue and reveals the next one in its
place; a plain count ("N more waiting") is the only hint given about
the rest of the batch — no names, no numbers.

**The CallBacks tab was deliberately reverted back to a full list
(v1.9.1)** — the same one-at-a-time restriction applied there for one
release, but callbacks aren't the fresh, never-touched pool the
restriction was built for: every lead on that tab is one the agent has
already spoken to and explicitly committed to calling back, often
needing several reattempts before it resolves, so full visibility is
what lets them triage which overdue callback to prioritize rather than
paging through one at a time. Same `LeadCard`, same tap-through to
`LeadDetailScreen`; only the Queue tab (fresh, unattempted leads) keeps
the single-card `visibleLeads.first()` view. Verified on-device: two
leads dispositioned as Call Later both render as an unbroken
`LazyColumn` on the CallBacks tab (`CallBacks (2)`), while the Queue
tab for a third, freshly-claimed lead still shows only "Your next
lead" as a single card.

**`LeadCard`'s mobile-number `Text` was replaced with a "Call" button
(v1.9.2)** — same PII-minimization reasoning as the one-at-a-time
change above, applied one level deeper: even a single visible lead
card was still rendering the raw digits in plain text on both the
Queue and CallBacks tabs. The button doesn't dial anything itself —
tapping it (or the rest of the card) still just opens
`LeadDetailScreen`, where the actual number and the real tap-to-call
banner already live; this only changes what's visible at the list
level. Verified on-device: neither tab shows a raw number anymore, and
tapping "Call" opens the correct lead's detail screen with its real
number intact.

**`LeadDetailScreen`'s own call banner (`CallButton`) had the same raw
number headline, and got the same treatment (v1.9.3)** — the big blue
"tap to call" card at the top of the detail screen used to headline
the actual digits; it now just says "Call" / "Tap to call". Tapping it
is unchanged — same `onCall`/`onMobileTapped` wiring, same SIM picker,
still dials the real number — this is purely the visible label.
Verified on-device: the banner renders "Call" with no digits anywhere
on the card, and tapping it still reaches the SIM picker / dialer as
before.

**Migration 18 adds `device_id` (`Settings.Secure.ANDROID_ID`) alongside
the existing `device_model` (v1.9.4)** — prompted by manisha, kishan,
and dilkhush all reporting the identical `device_model` ("samsung
SM-M315F"). `device_model` is `Build.MANUFACTURER + Build.MODEL`, a
*model* string identical across every unit of that model ever made
(plausibly just the phone the company bulk-issued to its telecalling
staff), so it can't settle whether that's three separate phones or one
phone switching between three accounts. Checked the actual disposition
timestamps for those three agents instead: 20 agent-to-agent switches
over two days, median gap 147s, minimum 18s — too fast to be a real
sign-out/sign-in/reload cycle done by hand, repeatedly; consistent with
three people genuinely working concurrently, not one phone reused.
`ANDROID_ID` is generated once per app install and stable across
relaunches (changes only on factory reset or uninstall/reinstall), so
it's the actual per-device signal `device_model` never was. The
dashboard's App versions table (admin-only, same as Device) flags any
`device_id` shared by two agents in red — that would be real proof of
one phone under two accounts, unlike a shared `device_model`. No
permission needed to read it. Verified on-device: qa_test relaunching
on v1.9.4 reported a `device_id`, immediately visible in
`v_app_versions`.

**"Delete agent" (backend/19_deactivate_agent.sql) is a deactivate, not
a real delete, and this is a hard constraint, not a style choice** — a
true `DELETE` on `auth.users` would cascade to `profiles`, but
`call_dispositions.agent_id` is `not null references profiles(id)`
with no `ON DELETE` clause, so Postgres refuses to remove a profile
that has ever logged a single call. That's also exactly the row you'd
want to keep: it's the audit trail every "Recent calls" / "Agent
performance" row on the dashboard depends on. `profiles.active` already
existed and was already load-bearing everywhere (`claim_next_lead`,
`save_disposition`, `search_lead`, ... all self-check `auth.uid() and
active`) — it just had no button, and column-level grants block a
direct client PATCH to it since migration 05, so it needed a
security-definer RPC. `set_agent_active(p_agent_id, p_active)` is one
function, not a one-way delete — same reasoning as the batch
active/inactive toggle: an admin misclick or a rehire both need an
undo. Deactivating also clears `assigned_to`/`locked_by`/`locked_at`
on the agent's open (`is_closed = false`) leads, so a freshly-claimed
lead or a pending `CALL_LATER` doesn't sit stuck assigned to someone
who's gone — `claim_next_lead` already treats `assigned_to is null` as
claimable and leaves `callback_at` untouched, so a pending callback
just gets served to whoever's next when it's due. Reactivating does
not try to hand old leads back — a returning agent just starts
claiming fresh. Wired into the dashboard's existing App versions table
(admin-only) as an Active/Inactive toggle button next to each agent,
reusing `v.id` (added to `v_app_versions`) rather than a new list or
card. Verified directly against the live DB by simulating both an
admin session (deactivate correctly flipped `active` to false and
released qa_test's 3 open leads; reactivate correctly restored `active`
without re-assigning them) and a non-admin agent session (kishan was
correctly refused with `not permitted`).

**`webapp/` (separate repo: `telecalling-webapp`, deployed to
`shishirkush.github.io/telecalling-webapp/`) is a mobile-web agent app
for agents on iPhone, who can't install the Android APK at all.** One
static file, same zero-build pattern as `dashboard/`, hitting the exact
same Supabase project/RLS/RPCs as the Android app — no backend schema
changes needed for it specifically. Login, Queue (one-lead-at-a-time),
CallBacks (full list), lead detail with call status/quality/callback
scheduling, edit details, and whole-database search all mirror the
Android app's behavior field-for-field, including hiding the raw
mobile number behind a "Call" button/banner. Reports itself to
`v_app_versions` as `app_version = "1.9.4-web"`,
`device_model = "Web · <OS> (<browser>)"` — deliberately distinct from
a real APK's version string so the dashboard can always tell the two
channels apart. Three things a browser genuinely cannot do, unlike
Android's `SimManager.kt`: no SIM picker (`tel:` just uses the phone's
default), no silently-sent Apply Card SMS (opens the Messages app
pre-filled instead — the agent still taps Send — logged as its own
`web_compose_opened` outcome so it's never confused with a confirmed
send), and no callback-reminder push notifications (would need a
service worker + push subscription, left out of this pass). Verified
live end-to-end against production (qa_test account): login, claim,
full disposition save with a callback quick-chip, the CallBacks list
picking it up with the right attempt count, and whole-database search
with its result-detail modal.

**Building the web app surfaced a real, pre-existing backend bug,
fixed in migration 20**: `report_app_version` had silently accumulated
THREE separate overloads (1-, 2-, and 3-argument) across migrations
15/17/18, because `CREATE OR REPLACE FUNCTION` only replaces a
function when its argument *type list* is unchanged — adding a new
trailing parameter, even with a default, registers as a new overload
rather than replacing the old one. This was live and broken the whole
time for any caller that didn't send the current exact argument count;
it never surfaced because the Android app always sent every parameter
that existed as of its own build. The web app's report call
deliberately omits `p_device_id` (there's no `ANDROID_ID` equivalent
on the web), which is exactly the shape that hit the ambiguity —
confirmed directly against the live database:
`PGRST203: Could not choose the best candidate function...`. Fixed by
dropping the two stale overloads, leaving one canonical
`report_app_version(p_version, p_device_model, p_device_id)`. Worth
remembering for any future function whose signature grows over
migrations: extending a security-definer RPC's parameter list needs an
explicit `drop function ... (old signature);` alongside the
`create or replace`, not just the replace on its own.

**Migration 21 adds calling hours (9 AM–7 PM IST) and a login/logout
log, both requested together and genuinely connected: the log is partly
what lets an admin verify the hours gate is actually holding.**

`claim_next_lead()` now refuses outside `09:00`–`19:00` IST
(`(now() at time zone 'Asia/Kolkata')::time`, checked server-side since
a phone's own clock can't be trusted — same reasoning as every other
check in this schema). Deliberately scoped to *claiming* only — an
agent already on a call at 7:01 PM can still save that disposition;
this stops new leads being handed out outside the window, not work
already in hand. India has had one fixed UTC+5:30 offset with no DST
since 1945, so `Asia/Kolkata` needs no seasonal handling.

`login_log` + `log_login_event(p_event, p_platform)` record a sign-in
or sign-out from either client. Both Android (`AppViewModel.signIn` /
`.signOut`) and the web app call it — sign-out logs *before* clearing
the session, since there's no valid token left to authenticate the
call afterward; sign-in only on a fresh credential submission, not on
a cold-start that found an already-valid session, so reopening the app
never gets recorded as a new login. This cannot capture every real
logout — an app force-killed, a browser tab just closed — there is no
heartbeat/keep-alive system here, so a login row with no matching
logout later is "we don't know," not "they were signed in the whole
time." Read access scoped by `manages_agent()`, same as the rest of
reporting (recent calls, callbacks, agent performance) — a manager
sees it for their assigned agents, admin sees everyone; not tucked
behind admin-only like App versions/DB stats.

Surfaced on the dashboard's new Login Activity page (separate page,
same client-side swap pattern as Archived Agents, reached from the
main header rather than gated in the admin section), reusing the
existing Today/7 days/30 days/All time filter. Any login or logout
outside 9 AM–7 PM IST is flagged red — the same window the backend now
enforces for claiming, so a supervisor can see at a glance whether
anyone was even active outside calling hours, not just whether they
successfully claimed a lead then. Verified directly against the live
database (real time was ~10:44 PM IST while testing): `claim_next_lead`
correctly raised `P0001: Leads can only be claimed between 9:00 AM and
7:00 PM IST`, and `log_login_event` correctly wrote both a login and a
logout row for the calling account, readable back through
`v_login_log`.

**The web app's own login-event call had a real bug, caught only by
testing it live end to end, not by reading the code**: it was
fire-and-forget, same as the version report right after it — but
unlike `AppViewModel`'s coroutines on Android (which keep running
independent of screen navigation), a browser cancels an in-flight
`fetch` the moment the page navigates away. Signing in and then
immediately navigating elsewhere lost the login row entirely; the
logout call right next to it never had this bug because it was already
`await`ed (losing a logout mattered enough at the time to justify the
extra moment before the sign-out button's UI transition). Fixed by
`await`ing the login call too. Worth remembering generally: an
un-awaited call on the web app is only really fire-and-forget if
nothing is going to navigate the page away immediately after — true
for background telemetry mid-session, not safe to assume around a
screen transition.

**`DeviceSupport.kt`'s device check had a real gap, found by reading
the dashboard's own App versions table**: kishan's `device_model` came
back as "Floydwiz_Technologies Primebook-WiFi" while on v1.9.4 (already
past the hard block) — Primebook is a real, physically-manufactured
Android laptop (keyboard, trackpad, no cellular modem at all on the
WiFi-only SKU this is), not a PC emulator. It got past
`hasSystemFeature(FEATURE_TELEPHONY)` because that flag apparently
comes back `true` on this OEM's build despite there being no modem
behind it — a hardware/firmware quirk of this specific device, not
something the app can detect any other way. Added `"floydwiz"` /
`"primebook"` to the same manufacturer/model substring list already
used for `bluestacks`/`nox`/`ldplayer`/`memu`/`genymotion`, renamed
from `knownDesktopPlayers` to `knownNonPhoneDevices` since a real Android
laptop isn't an emulator — same fix, different category of device.
Mirrored in the dashboard's `emulatorHints` list too, so this device
model keeps flagging red there regardless of app version. The
blocklist update only takes effect once an agent updates to the new
APK; immediately revoking an already-logged-in agent's access needs
`set_agent_active(..., false)` (the dashboard's Archive button) — the
two are complementary, not a substitute for each other.

**The Login Activity page was correct but looked completely broken —
found by the user asking "why is this empty, 3 agents are logged in"
(v1.9.7)**. Two separate causes, confirmed against the live database
rather than guessed at:

1. Real agents (manisha, kishan) were on v1.9.4, which predates
   v1.9.5 — the build that introduced login-event logging at all. They
   had never run code that reports it.
2. A real agent already on the fixed web app (sumit, `1.9.4-web`) still
   had zero rows, because the login-event call was scoped to a fresh
   credential sign-in only (`signInBtn`'s handler / `AppViewModel
   .signIn()`) — and agents sign in once, then stay signed in via a
   persisted session for days. A persisted-session boot never
   resubmits credentials, so that scoping meant the page would stay
   almost permanently empty even once every agent updated.

Fixed by moving the login-event call into the same place
`report_app_version` already fires unconditionally on both Android
(`loadProfileAndQueue()`) and web (the function of the same name) —
"once per app-active moment," not "once per password typed." This
does mean more rows on a day with several relaunches (the OS killing
and restarting a backgrounded app is common on a phone), but that's
still a real presence signal; the alternative was consistently zero
data, which defeats the entire feature.

Separately, the page was also silently inheriting the main dashboard's
global date-range filter, which defaults to "Today" — so even once
real data existed, it would only show if someone happened to log in
that same calendar day. Given its own independent filter
(`loginActivityRangeDays`), defaulting to **Last 7 days** rather than
Today, decoupled from the main dashboard's `rangeDays`/`sinceIso()`.

**Migration 22 requires an actual call attempt before `save_disposition()`
will accept any outcome** (v1.9.8 / webapp) — the suspicion this closes:
an agent claims a lead, sees the customer's name/mobile/PAN/DOB/income/
address, and saves an outcome (or just abandons it) without ever
placing a call, harvesting contact data instead of working the queue.
The Call banner already hides the raw digits behind a button, but that
was only ever a UI affordance — nothing stopped the RPC itself being
called directly. `log_call_attempt(p_lead_id)` is a new, narrow RPC the
app calls the instant the agent taps "Call"; `save_disposition()` now
refuses unless a matching row exists with `attempted_at` *strictly
after* the lead's current `last_called_at` (coalesced to `-infinity`
for a lead's very first disposition, since there's no prior call to
compare against). Admin's existing write-override is exempt — a
correction doesn't require the admin to have personally called.

This does not prove a call *connected*, only that the agent's client
registered an attempt before trying to save — the same class of
imperfect-but-meaningful signal as `hasCalledThisLead`/`logSmsOutcome`
already were. A determined agent could still fake the RPC call without
dialling, but that requires deliberately working around the app rather
than doing nothing, which is the realistic threat here.

`hasCalledThisLead` on both clients now only flips to true once the
server *confirms* `log_call_attempt` landed, not optimistically on tap
— it used to flip immediately in both Android's `dial()` and the
webapp's `onCallTapped()`, which would have let the UI show "you can
save" in a moment where the server would still refuse it. `canSave` /
`canSave()` on both clients now also require it, with a one-line hint
("Call this customer above before you can save an outcome.") next to
the Save button — the only disabled-Save reason that isn't self-evident
just from looking at the form, unlike a missing status/quality/callback.

**Two real bugs found during verification, not by reading the code:**
1. The scoping check first used `>=` rather than `>`. Confirmed live:
   `log_call_attempt()` and `save_disposition()` called back-to-back
   land on the *identical* `now()` in PostgreSQL (fixed per-transaction,
   not per-statement) when run in the same transaction — `>=` let that
   one attempt satisfy two separate dispositions on the same lead.
   Caught by a same-transaction test script, but the fix (strict `>`)
   is correct regardless: an attempt already "spent" on one disposition
   must never also satisfy a different, later one.
2. The webapp's `retryRpc()` never actually returned a usable result —
   callers just fired it and moved on. Needed a real return value here
   specifically because `onCallTapped()` has to know whether the log
   succeeded before flipping `hasCalledThisLead`; fixed to return
   `{ error }`, which is backward-compatible with every existing
   fire-and-forget caller that ignored the return value anyway.

Verified directly against the live database, each case its own request
(not one script/transaction, since that's what surfaced bug #1 above):
no call attempt logged → refused; a fresh attempt logged → accepted; a
stale attempt already used for a previous disposition on the same lead
→ refused. Then verified through the actual webapp UI end to end:
Save stays disabled with the hint visible until Call is tapped, becomes
enabled only after the server confirms, and the disposition saves
successfully.

**Migration 23 adds a 30-second floor between the call attempt and the
save, on top of migration 22's "must have called at all" (v1.9.9 /
webapp)** — raised directly by the user asking "can an agent tap Call,
hang up immediately, and still save No Answer / Switched Off?" Answer:
yes, exactly, and there was no way to close that with a permission-free
check on call *duration* — `log_call_attempt` fires the instant Call is
tapped, before the call even rings; this app has a standing rule never
to add `READ_CALL_LOG` (section 8), and there is no browser API at all
for a web page to observe real call state. A minimum elapsed time is
the honest, permission-free approximation: it can't prove the call was
answered, but it makes the fastest possible fake cycle no quicker than
a real one would realistically take. `save_disposition()` now gives a
distinct error for each failure mode — "Call this customer before
saving an outcome" (no fresh attempt at all) vs. "Wait at least 30
seconds after calling before saving an outcome" (a fresh attempt
exists, just not old enough yet) — so the agent (and the client) knows
which one applies.

Client-side, both apps mirror the 30s floor via `callDwellSecondsRemaining`
(Android) / `callDwellMsRemaining()` (web) so Save doesn't let the agent
try early and bounce off a server error it already knows is coming —
not a live-ticking countdown, a single delayed re-check scheduled the
moment the call attempt is confirmed (`delay(30_000)` on Android,
`setTimeout(..., 30000)` on web) that flips the button straight from
"waiting" to enabled at the 30s mark. Verified directly against the
live database (no attempt → refused; attempt <30s old → refused with
the distinct wait message; attempt ≥30s old → accepted, each its own
request) and through the actual webapp UI: selecting a status, tapping
Call, and immediately checking showed Save still disabled with the
"wait a bit longer" hint (status selection preserved across the tap);
waiting past 30 seconds with no further interaction had it unlock on
its own, and the save then succeeded.

**The dashboard's Agent daily report (dashboard/index.html) turns
migrations 22/23's data into something a supervisor can actually read
at a glance, not just enforce against.** Clicking an agent's name — in
App versions, Agent performance, or Archived agents, all wired to the
same `openAgentReport()` — opens a chronological, per-day timeline
merging `login_log`, `call_attempts`, and `call_dispositions` for that
one agent. The column that matters is **Gap**: the time between
tapping Call and saving that lead's outcome, computed client-side by
matching each disposition to the latest `call_attempts` row for the
same lead at or before the save (not a perfect reconstruction of
`save_disposition()`'s own matching — a lead re-attempted across
multiple callbacks in one day could have more than one candidate — but
right in the overwhelmingly common one-attempt-per-save case, which is
what this view exists to check). A real call varies a lot gap to gap
(a conversation runs long, a re-dial is quick); a gap that's
*consistently* just over the 30-second floor across many leads in a
row is what tapping Call, hanging up immediately, and waiting out the
timer looks like — flagged red under 40s, a 10-second margin past the
floor itself, and surfaced as its own summary tile ("Gap under 40s: N
of M saves") so it doesn't require scanning every row. Mobile numbers
in the lead column are shown only to admin — same `isAdmin` gating as
every other number on this dashboard, not the broader `manages_agent()`
scope the rest of the report uses for read access. Verified directly
against the live database under a simulated admin RLS session: the
`call_attempts` → `leads` join correctly returns names for both
in-progress leads (RLS via `assigned_to`) and already-dispositioned
ones (RLS via the disposition-history clause), matching real same-day
activity generated earlier in this session's own testing.

**Also collapsed every report/form on the main dashboard under its
`<h2>`, expanding on click** — the page had grown into one long scroll
of tables. `initCollapsibleSections()` runs once at script load,
dynamically moving each h2's following siblings (up to the next h2)
into a wrapper div rather than hand-restructuring the markup — table
IDs and existing `getElementById`-based render functions are
unaffected by the extra nesting. A nested control inside a header (App
versions' own "Archived (N)" button) is excluded from the toggle via
`e.target.closest("button")` so it keeps navigating normally instead
of also collapsing its section. Verified via a local static server
(`file://` doesn't execute JS in the preview pane, so a real `http://`
origin was needed) with `#app` forced visible ahead of a real login:
every section starts collapsed, expands independently, and the nested
button behaves correctly.

**`v_sms_outcomes` (migration 24) now breaks the Apply Card SMS
delivery report down by agent, not just by outcome** — raised directly
by the user: the old view (migration 14) totalled every agent's
outcomes together, so one agent stuck on `permission_denied` was
invisible in a fleet-wide total that also included everyone else's
successful sends, and the dashboard "cannot be assessed" for exactly
that reason. `sms_delivery_log` already carried `agent_id`; the view
just never grouped by it. Column list changes shape entirely (3
columns starting with `outcome` → 6 starting with `full_name`), which
`CREATE OR REPLACE VIEW` rejects (42P16 — it only ever allows
appending new columns after the existing ones, never reordering), so
this is a `DROP VIEW` + `CREATE VIEW` instead — safe specifically
because nothing else in the database references this view, only the
dashboard queries it over REST. Confirmed live this immediately
surfaced a real, previously-invisible problem: one agent has 89
`SmsManager` exceptions (a null-object crash, not merely a denied
permission) against only 22 successful sends — exactly the kind of
single-agent failure the fleet-wide total was hiding.

**That exception's actual root cause, once visible: `SimManager.kt`'s
own comment about `getSystemService(SmsManager::class.java)` was wrong
(v1.10.0)** — it claimed the generic class-based lookup was "available
since API 23," but `SmsManager` was not registered for it until API 31
(Android 12); on any earlier Android version `getSystemService`
returns `null`, and the following `.sendTextMessage()` call NPEs on
that null reference — precisely the crash in the log. manisha's real
device (`Xiaomi M2006C3LI`, a Redmi 9A) corroborates this exactly: a
real, well-known budget phone that shipped on Android 10 and was, at
most, updated to Android 11 by Xiaomi — never Android 12, so every
single Apply Card SMS on her phone hit this. Fixed with the standard
pattern: `getSystemService(SmsManager::class.java) ?:
SmsManager.getDefault()` — try the modern lookup first (works on 31+,
avoids the deprecation warning where possible), fall back to the
deprecated-but-still-fully-functional static method when it returns
null (everything below 31). This bug could only ever have been found
this way — from the dashboard's own data, working backward from a
symptom on a real device — not by reading the code in isolation, since
the code's own comment confidently asserted the opposite of what was
actually true.

---

## 5. Verification status — READ THIS

### Verified by actually running it

The schema was executed against a real PostgreSQL instance and exercised:

- Two agents claiming concurrently received **different** leads.
- `LEAD` without quality → rejected. `CALL_LATER` without a time → rejected.
  Quality on `WRONG_NUMBER` → rejected. Remarks > 2000 chars → rejected.
  A valid disposition → accepted.
- Agent A posting an outcome against agent B's lead → rejected.
- Under RLS an agent's token saw **3 of 10** leads (only their own).
- Empty queue returns zero rows, not a row of nulls.

The dashboard was rendered headless in light and dark mode: no console errors,
no layout breakage. Its categorical palette was checked with a CVD validator.

### The app now compiles and runs — corrections to the old predictions

`assembleDebug` succeeds and the app was driven end-to-end on an emulator
(Android 15, `Medium_Phone` AVD) against a live Supabase project. **None of the
five predicted first-compile failures actually occurred.** Two of the
predictions were simply wrong, and the notes are corrected here:

1. `gradlew` / `gradlew.bat` are still missing. Not a blocker — invoke the
   wrapper jar directly:
   `java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain assembleDebug`
   That bootstraps Gradle 8.10.2 normally. Or run `gradle wrapper` once.
2. `security-crypto:1.1.0-alpha06` resolved and compiled fine, and
   `EncryptedSharedPreferences` genuinely works at runtime — `telecall_session.xml`
   on the device holds a Tink AES-SIV keyset with ciphertext values and contains
   no readable JWT. The plaintext fallback is never reached.
3. The experimental Material3 APIs compiled fine against BOM `2024.12.01`, and
   `DatePickerDialog` + `TimePicker` both render and work on device.
4. The `kotlinx.serialization` imports were fine.
5. **WRONG: JDK 21 works.** Built with Android Studio's bundled JBR 21
   (`C:\Program Files\Android\Android Studio\jbr`) on AGP 8.7.3 + Gradle
   8.10.2. JDK 17 is not required. JDK 25 was not tried.

**Also wrong — the `local.properties` example in section 6.** Windows
backslashes are escape sequences to Java's `Properties` parser, so
`sdk.dir=C:\Users\...` silently mangles and AGP fails with
`IOException: Invalid file path`. Use forward slashes. Section 6 is fixed.

### Verified against a live Supabase project

Signed in as an agent and driven through the whole loop on the emulator:
queue → lead detail → outcome → save → next lead. Confirmed in the database
afterwards: disposition row written with `LEAD`/`HOT`/remarks, lead closed and
released, `attempts` incremented, and one `lead_access_log` row — so the
compensating PII control does fire on lead open. RLS held: the agent's token
saw only their own single lead out of ten. Login by login ID (not email)
works, and `profiles.login_id` is populated by the trigger.

The call button was also exercised: it requested `CALL_PHONE` at the right
moment and then actually placed the call through the system dialer.

A second full pass covered the paths the first one missed:

- **`CALL_LATER` end to end.** Selecting it reveals "Call back at"; the custom
  picker opens `DatePickerDialog` then `TimePicker`, and 1:00 PM IST was stored
  as `07:30:00+00:00` — the UTC conversion is right.
- **The lead stayed in the agent's queue** afterwards, tagged "Call Later /
  1 attempt / Callback 03 Sep", i.e. it is reserved to that agent rather than
  released to the pool. `lead_quality` was correctly null.
- **"Next lead" skipped the not-yet-due callback** and claimed a different
  record — and the one it claimed had been locked by an abandoned session, so
  the 30-minute stale-lock recovery works too.
- **Session persistence:** force-stop and relaunch goes straight to the queue,
  no re-login. **Sign-out** returns to the login screen and removes the session
  key from prefs.
- **Profile is read from the DB** — the header showed "Rahul Kumar" after the
  `full_name` was changed server-side.

### Runtime unknowns (need a real handset)

- **SIM chooser.** `SimManager.availableSims()` matches
  `PhoneAccountHandle.id` to `SubscriptionInfo.subscriptionId`, which is how
  AOSP telephony behaves. Some OEM ROMs deviate; there's an index fallback that
  keeps the chooser working but may lose carrier names. **Test on a real
  dual-SIM phone before rollout** — this is the feature most likely to need
  device-specific work.
  The emulator has one SIM, so it took the single-SIM path and the chooser
  never rendered. This is still the top runtime unknown.
- Single-SIM path (dial immediately) is **verified** on the emulator — the
  permission prompt appeared and the call was placed. The permission-denied
  fallback to `ACTION_DIAL` is still untested.
- Token refresh on `grant_type=refresh_token` assumes GoTrue returns a `user`
  object. If refresh throws a deserialization error, that's the assumption.

---

## 6. Getting it running

```bash
# 1. Database — paste into the Supabase SQL Editor in THIS order.
#    Note 02 runs LAST, not second: it seeds `address`, which 03 creates.
#    Numeric order fails with: column "address" ... does not exist.
backend/01_schema.sql
backend/03_login_id_and_address.sql
backend/04_upsertable_batch_index.sql
backend/05_fix_profile_privilege_escalation.sql   # security fix — do not skip
backend/06_agent_edit_lead_details.sql
backend/02_seed_sample_leads.sql                  # demo data, last

# 2. Android — create this file, it is git-ignored.
#    FORWARD SLASHES in sdk.dir. Java's Properties parser treats "\" as an
#    escape, so a Windows path with backslashes fails as "Invalid file path".
cat > android/local.properties <<'EOF'
sdk.dir=C:/Users/you/AppData/Local/Android/Sdk
SUPABASE_URL=https://xxxxx.supabase.co
SUPABASE_ANON_KEY=eyJhbGci...
LOGIN_DOMAIN=telecall.local
EOF

cd android && ./gradlew assembleDebug        # or open in Android Studio
# No gradlew yet? See section 5, item 1.

# 3. Dashboard — just open it. It signs straight to the login form; the
#    Supabase URL and anon key are hardcoded in the script (see section 4)
#    rather than typed in per-supervisor.
open dashboard/index.html
```

### Creating an agent (the supervisor's job)

Agents cannot self-register — sign into the dashboard as a supervisor and use
**Create agent** near the bottom of the page. Type a login ID and a password
(6+ characters); nothing else. The dashboard posts those two fields to the
`create-agent` Edge Function, which does everything email-shaped server-side —
you never see, type, or need to know about it. Then tell the agent the login
ID and password. They never see or type a domain either.

Auto-confirm and the `@telecall.local` expansion both happen inside the
function, so there's no "Auto confirm user?" checkbox to remember and no risk
of an agent getting locked out from an unconfirmed account — the function
always confirms, because there is no mailbox behind that address to confirm
via anyway.

The `handle_new_user` trigger creates the `profiles` row and fills `login_id`
automatically, same as before. To make someone a supervisor:

```sql
update public.profiles set role = 'supervisor' where login_id = 'rahul.k';
```

### Creating a supervisor (still by hand, deliberately)

Supervisor accounts are **not** self-serve — a supervisor can read and change
every lead in the database, so this stays a console action:

1. **Authentication → Users → Add user → Create new user**
2. Email address: `<login_id>@telecall.local` — e.g. `admin@telecall.local`.
   The domain must match `LOGIN_DOMAIN` in `app/build.gradle.kts` and in
   `dashboard/index.html`.
3. Set a password, leave **"Auto confirm user?" ticked** for the same
   no-mailbox reason as agents.
4. Promote with the same SQL as above, using `role = 'supervisor'`.

Do **not** disable email confirmation globally to work around step 3 — that
would also let anyone self-register an unconfirmed account. Better still,
turn signups off entirely (Authentication → Sign In / Providers → Email →
disable "Allow new users to sign up"), since every account is admin-created.

Use the **anon** key everywhere. The `service_role` key must never appear in
the app or the dashboard; it bypasses every RLS policy in section 4.

CI: add `SUPABASE_URL` and `SUPABASE_ANON_KEY` as GitHub Actions secrets and
the workflow produces a debug APK per push. Signing is optional and documented
inline in the workflow.

### Getting an update onto an agent's phone

There's no Play Store, so nothing pushes updates automatically — every new
build has to be installed manually, once per phone. The repo for this is
`https://github.com/shishirkush/telecalling-app` (created 2026-09-07, public
— required so the download link below works with no GitHub login).

To ship a new build:

```bash
cd android && java -cp gradle/wrapper/gradle-wrapper.jar \
  org.gradle.wrapper.GradleWrapperMain assembleDebug
gh release create vX.Y.Z android/app/build/outputs/apk/debug/app-debug.apk \
  --repo shishirkush/telecalling-app --title "vX.Y.Z — <what changed>" \
  --notes "<what changed, and why it's safe to update over the old install>"
```

**Keep the asset filename `app-debug.apk` every release.** The stable link
supervisors give agents depends on it:

```
https://github.com/shishirkush/telecalling-app/releases/latest/download/app-debug.apk
```

That URL always resolves to whatever the *most recent* release's
`app-debug.apk` is — no need to re-share a new link each version. The agent
opens it on their phone, taps the downloaded file, and installs over the
existing app (same package name, same debug signing key, so it's an update
in place — session and all Supabase-side data are untouched either way,
since none of it lives on the device). "Install unknown apps" needs enabling
once for whatever app opens the file, same requirement Bluetooth transfer
already had.

This is separate from the CI workflow above (`.github/workflows/android-build.yml`,
which uploads to Actions artifacts — those require a GitHub login to
download, so they're fine for your own testing but not for handing an agent
a link). Wiring CI to publish a Release automatically on push is the natural
next step if manual `gh release create` becomes a chore, not done yet.

---

## 7. Open work, roughly in priority order

1. ~~Compile the app.~~ **Done** — builds and runs, see section 5.
2. **Test the SIM chooser on a real dual-SIM handset.** Now the top item: the
   emulator only ever exercised the single-SIM path.
3. ~~Supervisor lead re-assignment~~ — leads themselves still can't be
   reassigned outside SQL, but **agent-to-manager assignment is done**
   (backend/09_manager_scoping.sql + the dashboard's "Manage agent
   assignments" panel, admin-only).
4. Password resets. An agent who forgets their password cannot self-serve —
   there is no mailbox to send a reset link to. Today the supervisor changes it
   in the Supabase dashboard and tells them. Fine for a pilot; decide something
   better before this scales.
5. ~~Nothing enforces login ID format.~~ **Done for agents** — the
   `create-agent` function rejects anything that isn't
   `^[a-z][a-z0-9._-]{2,31}$` before it reaches Supabase. Supervisor accounts,
   made by hand in the console, still have no such check.
6. Queue pagination. `myQueue()` caps at 100 rows, the dashboard at 2000
   dispositions. Both are silent truncations.
7. Agent's own daily stats screen (they currently get no feedback on their
   numbers).
8. Duplicate detection across batches. The unique index is
   `(batch, mobile)`, so the same number in two batches imports twice — which
   may be correct, but nobody has decided.
9. No offline handling. An agent with no signal gets an error, not a queue.
10. No tests of any kind.
11. **Confirm the callback reminder on a real handset**, specifically:
    whether `SCHEDULE_EXACT_ALARM` needs the agent to flip "Alarms &
    reminders" on manually (varies by OEM), and whether a reminder
    survives a real reboot before the agent next opens the app (see the
    no-boot-receiver tradeoff in section 4). Verified thoroughly on the
    emulator; a real handset's battery-optimization/Doze behavior can
    differ.
12. **Confirm the Apply Card SMS actually reaches the customer's phone on a
    real handset.** Verified end-to-end on the emulator (`content://sms/sent`
    shows the message with the correct number/body after a real call), but
    the emulator's modem is virtual — a real device is needed to confirm the
    carrier actually delivers it, and that `sendTextMessage`'s silent
    exception-swallowing (section 4) isn't masking real-world SIM/carrier
    failures an agent should know about.

---

## 8. Hard constraints — don't violate these

- Never put the `service_role` key in a client. It exists in exactly one
  place outside direct SQL access: the `create-agent` Edge Function's runtime
  environment, injected by Supabase itself, never fetched into the dashboard
  or the app. Do not add a second Edge Function or client path that also
  needs it without the same care.
- Never give agents direct write access to `leads`. `save_lead_details()` is
  the only way they may change customer fields, and `mobile`, `pan` and
  `ici_cr_lmt` must stay out of its signature.
- Never remove `lead_edit_log` or stop writing to it. It is the only record of
  what a field said before an agent changed it.
- Never add `READ_CALL_LOG`.
- Never remove the `security definer` ownership check inside
  `save_disposition()` — it is the only thing stopping an agent from posting
  outcomes against arbitrary lead IDs.
- Keep the conditional-field rules in the database, not only in the UI.
- Don't commit `local.properties` or any `.jks`.
- Never surface the synthetic `@LOGIN_DOMAIN` address to an agent, and never
  change `LOGIN_DOMAIN` on a project that already has users — every existing
  login would break at once. It is defined in two files; keep them in sync.
- Never make `leads_batch_mobile_uniq` partial again — CSV import's
  `ON CONFLICT` cannot infer a partial index. See section 4.
- Never `grant update` on the whole `profiles` table to `authenticated`. Only
  `full_name` may be agent-writable; `role` and `active` must not be. See
  section 4 and `05_fix_profile_privilege_escalation.sql`.
- Treat any new RLS policy as row-scoped only. If a rule needs to be
  column-scoped, it belongs in a GRANT or a trigger — RLS cannot express it,
  and assuming otherwise is what caused the privilege-escalation bug.
- Never add lead import to the Android app.
- Always tick "Auto confirm user?" when creating an agent. There is no mailbox
  behind the address, so an unconfirmed account is a permanently locked-out one.

---

## 9. Compliance context (not code, but it constrains the code)

The data is KYC-grade financial PII under India's DPDP Act. Consent records,
purpose limitation, retention policy and breach notification are the operator's
responsibility, not this repo's. Two things that *are* technical:

- The Supabase free tier hosts outside India by default. If data localisation
  applies, choose a region (paid) or self-host.
- The free tier has no SLA, no point-in-time recovery, and pauses after a week
  idle. Acceptable for a pilot; not for a team that depends on it daily.
