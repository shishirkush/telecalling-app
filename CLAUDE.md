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

android/app/src/main/java/com/telecall/app/
  MainActivity.kt              screen routing + the notification deep-link
                                     (EXTRA_OPEN_LEAD_ID, singleTask launch mode)
  AppViewModel.kt              ALL UI state + actions live here
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
  ui/Format.kt                 currency/date/mask helpers  ← masking lives here
  ui/theme/Theme.kt

dashboard/index.html           entire dashboard, one file, no build step.
                                     "Create agent" form calls the Edge Function below
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
www.cardadda.in"` to the same number being called, on the same SIM
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

`sendApplyCardSms()` is fire-and-forget and swallows its own
exceptions — a missing `SEND_SMS` grant or a carrier hiccup must never
surface as if the call itself had failed; the call result is reported
independently in `dial()`.

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
