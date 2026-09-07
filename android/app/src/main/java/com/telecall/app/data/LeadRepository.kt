package com.telecall.app.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class LeadRepository(private val client: SupabaseClient) {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    val currentUserId: String? get() = client.currentUserId
    val isSignedIn: Boolean get() = client.isSignedIn

    suspend fun signIn(loginId: String, password: String) = client.signIn(loginId, password)

    fun signOut() = client.signOut()

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
     * Fire-and-forget: a logging failure must never block a call.
     */
    suspend fun logLeadView(leadId: Long) {
        client.rpc("log_lead_view", buildJsonObject { put("p_lead_id", leadId) }.toString())
    }
}
