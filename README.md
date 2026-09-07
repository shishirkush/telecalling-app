# Telecalling App

Native Android calling app for agents, a Postgres backend, and a supervisor
dashboard. Runs entirely on free tiers.

```
telecalling/
├── backend/            SQL — run once in the Supabase SQL editor
├── android/            Kotlin + Jetpack Compose app (the agent's tool)
├── dashboard/          Single-file HTML supervisor dashboard
└── .github/workflows/  CI that builds the APK for you
```

---

## What the agent sees

| Input field (read-only) | Output the agent records |
|---|---|
| Name | **Call Status** — Switched Off / Wrong No. / Not Interested / Call Later / Lead |
| PAN | **Lead strength** — Hot / Warm / Cold *(shown only when status = Lead)* |
| Mobile No — **tap to call** | **Callback date & time** *(shown only when status = Call Later)* |
| Email | **Remarks** — free text, always available |
| DOB | |
| Annual Income Range | |
| Company | |
| ICI Cr Lmt | |

Tapping the mobile number checks how many SIMs the handset has. One SIM: it
dials immediately. Two SIMs: it shows a chooser listing each SIM with its
carrier name, and routes the call out of the one the agent picks.

---

## Setup

### 1. Database (10 minutes, free)

1. Create a project at [supabase.com](https://supabase.com) — the free tier is
   enough (500 MB, which holds several hundred thousand leads).
2. Open **SQL Editor → New query**, paste `backend/01_schema.sql`, run it.
3. Optionally run `backend/02_seed_sample_leads.sql` for ten fake test records.
4. Create user accounts under **Authentication → Users → Add user** (email +
   password). A `profiles` row is created automatically for each one.
5. Promote yourself to supervisor:

   ```sql
   update public.profiles
      set role = 'supervisor'
    where id = (select id from auth.users where email = 'you@example.com');
   ```

6. Copy **Project Settings → API → Project URL** and the **anon public** key.
   You need both below. The `service_role` key is never used by either client —
   do not put it in the app or the dashboard.

### 2. Supervisor dashboard (2 minutes, free)

Open `dashboard/index.html` in a browser, paste the project URL and anon key
once, and sign in with your supervisor account.

To share it with a team, drop the folder on GitHub Pages, Netlify or Cloudflare
Pages — all free, and the file is fully static.

Use the **Import leads (CSV)** panel to load your calling list. Expected
headers: `Name, PAN, Mobile No, Email, DOB, Annual Income Range, Company,
ICI Cr Lmt`. Dates must be `YYYY-MM-DD` or `DD/MM/YYYY`.

### 3. Android app

**If you have Android Studio:** open the `android/` folder, create
`android/local.properties` containing

```properties
SUPABASE_URL=https://xxxxx.supabase.co
SUPABASE_ANON_KEY=eyJhbGci...
```

then Run. (`local.properties` is git-ignored — the key never lands in the repo.)

**If you do not:** push this repo to GitHub, add `SUPABASE_URL` and
`SUPABASE_ANON_KEY` under *Settings → Secrets and variables → Actions*, and the
included workflow builds a debug APK on every push. Download it from the
Actions run's artifacts. GitHub Actions is free for public repos and gives 2,000
minutes/month on private ones; a build here takes about three minutes.

For a signed release build, follow the commented instructions at the bottom of
`.github/workflows/android-build.yml`.

### 4. Distribution

Sideload the APK, or push it through whatever MDM you use. **This app is not
suitable for the Play Store** — see *Known constraints* below.

---

## How the work is distributed between agents

Agents do not browse the lead database. They tap **Next lead** and the server
hands them exactly one record, using

```sql
... order by callback_at, id limit 1 for update skip locked
```

`SKIP LOCKED` is what stops two agents who tap at the same moment from both
being handed the same customer — the second one steps over the row the first
has locked and gets the next one instead. Leads locked for more than 30 minutes
without a disposition are returned to the pool automatically, so an agent who
closes the app mid-call does not strand records.

Due callbacks jump the queue ahead of fresh leads.

---

## Security posture — read this before going live

The data in this app (PAN, DOB, income band, sanctioned credit limit) is
KYC-grade financial PII. What is and is not protected:

**In place:**

- Row-level security. An agent can read *only* the leads currently assigned to
  them. There is no query an agent's token can make that returns the wider
  database, so a stolen phone leaks at most that agent's active queue.
- Agents cannot write to the `leads` table at all — outcomes go through a
  `security definer` function that verifies the lead is actually theirs.
- Every time an agent opens a lead record it is written to `lead_access_log`
  with a timestamp. If data surfaces somewhere it should not, you can answer
  "who saw this record".
- Auth tokens are held in `EncryptedSharedPreferences`; cloud backup and
  device-to-device transfer are disabled for the app.

**Deliberately not in place, because you asked for it this way:**

- **PAN, DOB, income and credit limit are shown in full.** An agent looking at
  a record has everything needed to impersonate that customer elsewhere, and
  nothing stops a screenshot. Turning this on is one line —
  `MASK_SENSITIVE` in `android/app/build.gradle.kts` → `true` — which renders
  PAN as `ABCXX1234X`, DOB as `••/••/1988` and amounts as `••••••`.
  Agents can still verify identity by asking the customer to confirm the
  digits. I would strongly recommend flipping it before real customer data
  goes in.

**Still your responsibility:**

- India's DPDP Act obligations — consent records, purpose limitation, breach
  notification, and a retention policy. None of that is code.
- Supabase's free tier hosts outside India by default. If data localisation
  applies to you, pick an appropriate region (paid tiers give you the choice)
  or self-host Postgres.
- The free tier has no SLA, no point-in-time recovery, and pauses after a week
  of inactivity. Fine for a pilot. If this becomes the system a team depends on
  daily, the ~$25/month tier buys backups and support — the cost of a day of
  lost call records is higher.

---

## Known constraints

**Play Store.** Capturing call duration or connection status automatically
requires `READ_CALL_LOG`, which Google grants only to default-dialer apps.
Requesting it gets telecalling apps rejected. This app therefore does not
request it, does not auto-capture duration, and is meant for internal
distribution. If you later need call duration, the answer is a cloud telephony
provider (Exotel, Knowlarity, Twilio) placing the call server-side — a different
architecture, not a flag.

**SIM selection.** Android exposes no "show the SIM chooser" API. The app
enumerates the handset's call-capable telecom accounts and renders its own
chooser, then attaches the chosen account to the call. On the small number of
OEM ROMs that do not map telecom accounts to subscriptions in the standard way,
the chooser still works but may label the SIMs "SIM 1 / SIM 2" without carrier
names.

**Permission refusal.** If an agent denies the Phone permission, the app falls
back to opening the system dialer with the number filled in. They can still
work; they just lose one-tap dialling and SIM targeting.

---

## Verifying it works

1. Run the schema, seed the sample leads, create one agent and one supervisor.
2. Sign in to the app as the agent, tap **Next lead** — one of the ten sample
   records should appear.
3. Tap the number. On a dual-SIM phone the chooser appears; on a single-SIM
   phone it dials.
4. Pick **Lead → Hot**, type a remark, save.
5. Open the dashboard as the supervisor — the call appears in *Recent calls*,
   the outcome chart moves, and the agent's row updates.
6. Try tapping **Next lead** on two phones at the same instant: they should get
   two different customers.
