package com.telecall.app.data

import kotlinx.coroutines.delay
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class LeadRepository(private val client: SupabaseClient) {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /**
     * Retries a background telemetry RPC (SMS outcome, app version, lead-view
     * audit) up to [attempts] times, 2 seconds apart, before giving up.
     *
     * These three calls have no UI to retry from if they fail — unlike a
     * call or a save the agent can just tap again — so a single dropped
     * packet on a real mobile connection (which the emulator's Wi-Fi never
     * exercises) used to erase the signal forever. All three RPCs take only
     * app-supplied parameters, never something the agent typed, so there is
     * no validation-error case where retrying would be pointless: every
     * failure here is transient (network, an expired refresh token that a
     * retry re-attempts anyway) or the agent's session is genuinely gone,
     * in which case the wasted ~4 seconds in the background is harmless.
     * Never used for user-facing actions (sign-in, save, search) — those
     * already get retried by the agent themselves, and blind auto-retry
     * there would only delay error feedback they need immediately.
     */
    private suspend fun retryRpc(name: String, body: String, attempts: Int = 3): Outcome<String> {
        var last: Outcome<String> = Outcome.Err("never attempted")
        repeat(attempts) { attempt ->
            when (val r = client.rpc(name, body)) {
                is Outcome.Ok -> return r
                is Outcome.Err -> {
                    last = r
                    if (attempt < attempts - 1) delay(2000)
                }
            }
        }
        return last
    }

    val currentUserId: String? get() = client.currentUserId
    val isSignedIn: Boolean get() = client.isSignedIn

    suspend fun signIn(loginId: String, password: String) = client.signIn(loginId, password)

    fun signOut() = client.signOut()

    /**
     * Stamps a fresh active_session_token onto this agent's profile and
     * remembers it locally — see backend/27_single_session_per_agent.sql.
     * Called right after a fresh sign-in, never on a cold-start resume of
     * an already-persisted session: claiming again there would flip which
     * device is "current" just because it happened to be opened last,
     * instead of because the agent actually signed in again.
     *
     * Best-effort by design: the caller ignores failure here rather than
     * blocking sign-in on it. Worst case, this device isn't correctly
     * tracked as the current one for a moment, no worse than before this
     * feature existed.
     */
    suspend fun claimSession() {
        when (val r = client.rpc("claim_session")) {
            is Outcome.Ok -> client.localSessionToken = r.value.trim('"')
            is Outcome.Err -> Unit
        }
    }

    /**
     * True when this device previously claimed a session and the profile
     * now carries a different token — i.e. some other device has since
     * signed into this same login and claimed it instead. False (never
     * "replaced") until this device has claimed a token of its own, so a
     * session that predates this feature keeps working until its next
     * real sign-in.
     */
    fun sessionWasReplaced(serverToken: String?): Boolean {
        val local = client.localSessionToken ?: return false
        return serverToken != null && serverToken != local
    }

    /**
     * Local-only bookkeeping so a process kill right after the agent taps
     * Call doesn't silently lose the lead they were on — see PendingCall's
     * kdoc and AppViewModel.resumePendingCallOrLoadQueue. Never touches
     * the server: [getLead] on resume is what confirms the lead is still
     * actually theirs to work.
     */
    fun savePendingCall(leadId: Long, hasCalled: Boolean, confirmedAt: Long?) =
        client.savePendingCall(PendingCall(leadId, hasCalled, confirmedAt))

    fun clearPendingCall() = client.clearPendingCall()

    fun getPendingCall(): PendingCall? = client.getPendingCall()

    /** The signed-in agent's own profile row. */
    suspend fun myProfile(): Outcome<Profile> {
        val uid = client.currentUserId ?: return Outcome.Err(SupabaseClient.SESSION_EXPIRED)
        return when (val r = client.get("profiles", "id=eq.$uid&select=*&limit=1")) {
            is Outcome.Err -> r
            is Outcome.Ok -> {
                val list = runCatching { json.decodeFromString<List<Profile>>(r.value) }.getOrNull()
                list?.firstOrNull()?.let { Outcome.Ok(it) }
                    ?: Outcome.Err("Your account has no profile row. Ask your supervisor to activate it.")
            }
        }
    }

    /** Every active campaign an agent may pick (backend/32_campaigns.sql). */
    suspend fun getCampaigns(): Outcome<List<Campaign>> =
        when (val r = client.get("campaigns", "is_active=eq.true&select=*&order=name")) {
            is Outcome.Err -> r
            is Outcome.Ok -> runCatching { json.decodeFromString<List<Campaign>>(r.value) }
                .fold({ Outcome.Ok(it) }, { Outcome.Err("Could not read the campaign list: ${it.message}") })
        }

    /**
     * The only way profiles.current_campaign_id ever changes — never a
     * plain PATCH, since there is no client grant on that column. Refuses
     * if the agent has a called-but-undisposed lead (same check
     * claim_next_lead() enforces) and releases every other open lead back
     * to the pool on success — see backend/32_campaigns.sql.
     */
    suspend fun switchCampaign(campaignId: Long?): Outcome<Unit> {
        val body = buildJsonObject { put("p_campaign_id", campaignId) }.toString()
        return when (val r = client.rpc("switch_campaign", body)) {
            is Outcome.Err -> r
            is Outcome.Ok -> Outcome.Ok(Unit)
        }
    }

    /**
     * The agent's own calling number, self-reported (see
     * backend/26_agent_contact_number.sql — Android can't reliably read a
     * SIM's own number, so this is asked for once instead of guessed at
     * per call). A plain PATCH, not an RPC: the column grant + "update
     * own profile row" RLS policy already scope this to the caller's own
     * row, the same mechanism full_name already relies on.
     */
    suspend fun saveContactNumber(number: String): Outcome<Profile> {
        val uid = client.currentUserId ?: return Outcome.Err(SupabaseClient.SESSION_EXPIRED)
        val body = buildJsonObject { put("contact_number", number) }.toString()
        return when (val r = client.patch("profiles", "id=eq.$uid", body)) {
            is Outcome.Err -> r
            is Outcome.Ok -> runCatching { json.decodeFromString<List<Profile>>(r.value) }
                .fold(
                    { list ->
                        list.firstOrNull()
                            ?.let { Outcome.Ok(it) }
                            ?: Outcome.Err("Could not read the updated profile.")
                    },
                    { Outcome.Err("Saved, but the updated profile could not be read: ${it.message}") }
                )
        }
    }

    /**
     * Leads currently held by this agent and still open — i.e. fresh claims
     * plus any callbacks they committed to. Ordered so overdue callbacks
     * surface first.
     */
    suspend fun myQueue(): Outcome<List<Lead>> {
        val uid = client.currentUserId ?: return Outcome.Err(SupabaseClient.SESSION_EXPIRED)
        val q = "assigned_to=eq.$uid" +
            "&is_closed=eq.false" +
            "&select=*" +
            "&order=callback_at.asc.nullslast,id.asc" +
            "&limit=100"
        return when (val r = client.get("leads", q)) {
            is Outcome.Err -> r
            is Outcome.Ok -> runCatching { json.decodeFromString<List<Lead>>(r.value) }
                .fold({ Outcome.Ok(it) }, { Outcome.Err("Could not read the lead list: ${it.message}") })
        }
    }

    /**
     * One lead by id, for opening a callback reminder notification straight
     * to its detail screen — the app may have been killed since the alarm
     * was scheduled, so there is no guarantee it is already in [myQueue].
     * Returns null (not an error) if the lead is no longer this agent's,
     * e.g. it was re-dispositioned by someone else in the meantime.
     */
    suspend fun getLead(id: Long): Outcome<Lead?> =
        when (val r = client.get("leads", "id=eq.$id&select=*&limit=1")) {
            is Outcome.Err -> r
            is Outcome.Ok -> runCatching { json.decodeFromString<List<Lead>>(r.value) }
                .fold({ Outcome.Ok(it.firstOrNull()) }, { Outcome.Err("Could not read that lead: ${it.message}") })
        }

    /**
     * Pull the next unworked lead from the shared pool. Server-side this uses
     * FOR UPDATE SKIP LOCKED, so two agents tapping this simultaneously get
     * two different customers rather than both dialling the same one.
     *
     * Returns null when the queue is empty.
     */
    suspend fun claimNextLead(): Outcome<Lead?> =
        when (val r = client.rpc("claim_next_lead")) {
            is Outcome.Err -> r
            is Outcome.Ok -> runCatching { json.decodeFromString<List<Lead>>(r.value) }
                .fold({ Outcome.Ok(it.firstOrNull()) }, { Outcome.Err("Could not read the claimed lead: ${it.message}") })
        }

    /** Record the agent's outcome for a call. */
    suspend fun saveDisposition(
        leadId: Long,
        status: CallStatus,
        quality: LeadQuality?,
        remarks: String?,
        callbackAtIso: String?,
        simSlot: Int?
    ): Outcome<Unit> {
        val body = buildJsonObject {
            put("p_lead_id", leadId)
            put("p_status", status.wire)
            quality?.let { put("p_lead_quality", it.wire) }
            remarks?.takeIf { it.isNotBlank() }?.let { put("p_remarks", it.trim()) }
            callbackAtIso?.let { put("p_callback_at", it) }
            simSlot?.let { put("p_sim_slot", it) }
        }
        return when (val r = client.rpc("save_disposition", body.toString())) {
            is Outcome.Err -> r
            is Outcome.Ok -> Outcome.Ok(Unit)
        }
    }

    /**
     * Save customer details the agent learned on the call.
     *
     * Only the fields the agent is allowed to touch are sent, and the RPC is
     * security definer with the same ownership check as save_disposition —
     * mobile, PAN and the credit limit are not parameters at all, so they
     * cannot be changed from here. A blank field means "leave it alone"
     * rather than "clear it".
     *
     * Returns the updated lead so the screen can show what was actually
     * stored rather than what was typed.
     */
    suspend fun saveLeadDetails(
        leadId: Long,
        name: String?,
        email: String?,
        dobIso: String?,
        company: String?,
        annualIncomeRange: String?,
        address: String?
    ): Outcome<Lead> {
        val body = buildJsonObject {
            put("p_lead_id", leadId)
            name?.takeIf { it.isNotBlank() }?.let { put("p_name", it.trim()) }
            email?.takeIf { it.isNotBlank() }?.let { put("p_email", it.trim()) }
            dobIso?.takeIf { it.isNotBlank() }?.let { put("p_dob", it) }
            company?.takeIf { it.isNotBlank() }?.let { put("p_company", it.trim()) }
            annualIncomeRange?.takeIf { it.isNotBlank() }?.let { put("p_annual_income_range", it.trim()) }
            address?.takeIf { it.isNotBlank() }?.let { put("p_address", it.trim()) }
        }
        return when (val r = client.rpc("save_lead_details", body.toString())) {
            is Outcome.Err -> r
            is Outcome.Ok -> runCatching { json.decodeFromString<List<Lead>>(r.value) }
                .fold(
                    { list ->
                        list.firstOrNull()
                            ?.let { Outcome.Ok(it) }
                            ?: Outcome.Err("The lead could not be updated.")
                    },
                    { Outcome.Err("Saved, but the updated record could not be read: ${it.message}") }
                )
        }
    }

    /**
     * Records that this agent opened a lead's full record. Because the app
     * shows PAN / DOB / income / credit limit unmasked, this log is the only
     * way to answer "who saw this customer's data" after the fact.
     * Fire-and-forget: a logging failure must never block a call. Retried
     * (see [retryRpc]) since there is no UI to retry from if it fails silently.
     */
    suspend fun logLeadView(leadId: Long) {
        retryRpc("log_lead_view", buildJsonObject { put("p_lead_id", leadId) }.toString())
    }

    /**
     * Records that this agent tapped "Call" for this lead — the server-
     * side gate save_disposition() checks before accepting any outcome
     * (see backend/22_require_call_before_disposition.sql), so a lead
     * can't be claimed, its details viewed, and dispositioned again
     * without ever actually calling. Unlike [logLeadView]/[logSmsOutcome]
     * this is NOT fire-and-forget: the caller needs to know whether it
     * actually landed, since [dial] only sets [Outcome.Ok] when it did —
     * a UI that optimistically flipped "called" state on tap alone could
     * let the agent try to save when the server would still refuse them.
     * Retried (see [retryRpc]) for the same reason.
     */
    suspend fun logCallAttempt(leadId: Long): Outcome<Unit> {
        return when (val r = retryRpc("log_call_attempt", buildJsonObject { put("p_lead_id", leadId) }.toString())) {
            is Outcome.Err -> r
            is Outcome.Ok -> Outcome.Ok(Unit)
        }
    }

    /**
     * Reports what actually happened when the Apply Card SMS was sent.
     * Agents work remotely — this is the only way to see a real-device
     * failure (denied permission, no service, radio off) without ever
     * touching their phone. Fire-and-forget, same as [logLeadView]: this
     * observes the SMS/call flow, it must never be able to affect it.
     * Retried (see [retryRpc]).
     */
    suspend fun logSmsOutcome(leadId: Long?, mobile: String, outcome: String) {
        retryRpc(
            "log_sms_outcome",
            buildJsonObject {
                put("p_lead_id", leadId)
                put("p_mobile", mobile)
                put("p_outcome", outcome)
            }.toString()
        )
    }

    /**
     * Once per cold launch, so "which agents are on which build" is a
     * direct query instead of
     * inferred from unrelated activity. Fire-and-forget, same reasoning
     * as [logLeadView] and [logSmsOutcome]. Retried (see [retryRpc]).
     *
     * [deviceModel] (Build.MANUFACTURER + Build.MODEL) settles "is this a
     * real phone or a desktop Android player (BlueStacks, LDPlayer, ...)"
     * directly instead of inferring it from an always-missing SIM slot
     * and silent SMS failures — see backend/17_device_info_report.sql.
     *
     * [deviceId] (Settings.Secure.ANDROID_ID) goes further: device_model
     * is a *model* string, identical across every unit of that model, so
     * it can't tell two agents on the same phone model apart from one
     * phone switching between their accounts. ANDROID_ID is generated
     * once per app install and stable across relaunches — see
     * backend/18_android_id_report.sql.
     */
    /**
     * Records a sign-in or sign-out for the dashboard's Login Activity
     * page. Retried (see [retryRpc]) — there is no UI to retry from if
     * either call fails silently, and losing a logout is worse than a
     * few extra seconds on the sign-out tap, so this is awaited rather
     * than truly fire-and-forget on that path (see AppViewModel.signOut).
     */
    suspend fun logLoginEvent(event: String, platform: String = "android") {
        val r = retryRpc(
            "log_login_event",
            buildJsonObject {
                put("p_event", event)
                put("p_platform", platform)
            }.toString()
        )
        // Only a recorded event moves the throttle clock, so a failed call
        // doesn't suppress the next app_opened attempt.
        if (r is Outcome.Ok) client.lastLoginLogAt = System.currentTimeMillis()
    }

    /**
     * Cold start with an existing session. "login" is reserved for a real
     * password sign-in; a relaunch — including one Android forced by
     * killing the app during a call — is logged as "app_opened", and only
     * if at least [APP_OPENED_MIN_GAP_MS] has passed since this device's
     * last logged event, so a day of restarts stays a handful of rows.
     */
    suspend fun logAppOpenedIfDue() {
        if (System.currentTimeMillis() - client.lastLoginLogAt < APP_OPENED_MIN_GAP_MS) return
        logLoginEvent("app_opened")
    }

    suspend fun reportAppVersion(version: String, deviceModel: String, deviceId: String?) {
        retryRpc(
            "report_app_version",
            buildJsonObject {
                put("p_version", version)
                put("p_device_model", deviceModel)
                deviceId?.let { put("p_device_id", it) }
            }.toString()
        )
    }

    /**
     * Whole-database customer lookup by exact mobile or PAN, for a
     * callback whose lead is no longer in this agent's own queue — see
     * backend/16_lead_search.sql for why this is a narrow, audited,
     * rate-limited exception rather than a general search. Pass exactly
     * one of [mobile]/[pan]; the caller (AppViewModel) has already
     * classified which one the agent typed.
     */
    suspend fun searchLeads(mobile: String?, pan: String?): Outcome<List<Lead>> {
        val body = buildJsonObject {
            mobile?.let { put("p_mobile", it) }
            pan?.let { put("p_pan", it) }
        }
        return when (val r = client.rpc("search_lead", body.toString())) {
            is Outcome.Err -> r
            is Outcome.Ok -> runCatching { json.decodeFromString<List<Lead>>(r.value) }
                .fold({ Outcome.Ok(it) }, { Outcome.Err("Could not read search results: ${it.message}") })
        }
    }
}

/** Minimum spacing between two "app_opened" login-log rows from one device. */
private const val APP_OPENED_MIN_GAP_MS = 30 * 60 * 1000L
