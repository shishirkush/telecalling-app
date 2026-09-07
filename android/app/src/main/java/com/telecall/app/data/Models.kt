package com.telecall.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The call outcomes. [wire] is the Postgres enum value; [label] is what
 * the agent sees. Keep [wire] in sync with the `call_status` enum in
 * backend/01_schema.sql. Declaration order here is display order — the
 * form on [com.telecall.app.ui.LeadDetailScreen] just iterates [entries].
 */
enum class CallStatus(val wire: String, val label: String) {
    SWITCHED_OFF("SWITCHED_OFF", "Switched Off"),
    NO_ANSWER("NO_ANSWER", "No Answer"),
    WRONG_NUMBER("WRONG_NUMBER", "Wrong No."),
    NOT_INTERESTED("NOT_INTERESTED", "Not Interested"),
    CALL_LATER("CALL_LATER", "Call Later"),
    LEAD("LEAD", "Lead");

    companion object {
        fun fromWire(v: String?): CallStatus? = entries.firstOrNull { it.wire == v }
    }
}

/** Sub-qualification shown only when [CallStatus.LEAD] is selected. */
enum class LeadQuality(val wire: String, val label: String) {
    HOT("HOT", "Hot — ready to convert"),
    WARM("WARM", "Warm — interested, needs follow-up"),
    COLD("COLD", "Cold — long-term prospect");

    companion object {
        fun fromWire(v: String?): LeadQuality? = entries.firstOrNull { it.wire == v }
    }
}

/**
 * One record in the calling queue. Field names match the `leads` table
 * columns exactly so PostgREST JSON maps straight onto this class.
 */
@Serializable
data class Lead(
    val id: Long,

    // --- shown to the caller ---
    val name: String,
    val pan: String? = null,
    val mobile: String,
    val email: String? = null,
    val dob: String? = null,                                   // ISO yyyy-MM-dd
    @SerialName("annual_income_range") val annualIncomeRange: String? = null,
    val company: String? = null,
    @SerialName("ici_cr_lmt") val iciCrLmt: Double? = null,
    val address: String? = null,                               // <= 2000 chars

    // --- workflow ---
    @SerialName("assigned_to")   val assignedTo: String? = null,
    @SerialName("last_status")   val lastStatus: String? = null,
    @SerialName("last_remarks")  val lastRemarks: String? = null,
    @SerialName("last_called_at") val lastCalledAt: String? = null,
    val attempts: Int = 0,
    @SerialName("callback_at")   val callbackAt: String? = null,
    @SerialName("is_closed")     val isClosed: Boolean = false,
    val batch: String? = null
) {
    val status: CallStatus? get() = CallStatus.fromWire(lastStatus)
}

/** Payload posted to the `save_disposition` RPC. */
@Serializable
data class DispositionRequest(
    @SerialName("p_lead_id")      val leadId: Long,
    @SerialName("p_status")       val status: String,
    @SerialName("p_lead_quality") val leadQuality: String? = null,
    @SerialName("p_remarks")      val remarks: String? = null,
    @SerialName("p_callback_at")  val callbackAt: String? = null,
    @SerialName("p_sim_slot")     val simSlot: Int? = null
)

/** Signed-in user. */
@Serializable
data class AuthSession(
    @SerialName("access_token")  val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_at")    val expiresAt: Long = 0,
    val user: AuthUser
)

@Serializable
data class AuthUser(
    val id: String,
    val email: String? = null
)

@Serializable
data class Profile(
    val id: String,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("login_id")  val loginId: String? = null,
    val role: String = "agent",
    val active: Boolean = true
) {
    val isSupervisor: Boolean get() = role == "supervisor"
}

/** Error surface for the UI layer. */
sealed class Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>()
    data class Err(val message: String) : Outcome<Nothing>()
}
