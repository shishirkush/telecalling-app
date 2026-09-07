package com.telecall.app.call

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

/**
 * One dialable SIM.
 *
 * @param slotIndex 1-based slot number as printed on the handset ("SIM 1").
 * @param handle    the telecom account used to force the call onto this SIM.
 */
data class SimOption(
    val slotIndex: Int,
    val label: String,
    val carrier: String?,
    val handle: PhoneAccountHandle
)

/**
 * Handles the SIM-selection requirement.
 *
 * Android has no "show me the SIM chooser" API. What it exposes is the list of
 * call-capable telecom accounts; picking one and attaching it to the call
 * intent as EXTRA_PHONE_ACCOUNT_HANDLE is what actually routes the call out of
 * a specific SIM. So on a dual-SIM handset the app renders its own chooser
 * populated from the OS, and on a single-SIM handset it skips straight to
 * dialling — which is exactly the behaviour in the spec.
 */
class SimManager(private val context: Context) {

    fun hasCallPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

    fun hasPhoneStatePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Every SIM that can place a call, ordered by physical slot.
     *
     * Returns an empty list when READ_PHONE_STATE has not been granted or the
     * device exposes no telecom accounts — callers should fall back to handing
     * the number to the system dialer.
     */
    @SuppressLint("MissingPermission")
    fun availableSims(): List<SimOption> {
        if (!hasPhoneStatePermission()) return emptyList()

        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            ?: return emptyList()

        val handles: List<PhoneAccountHandle> = try {
            telecom.callCapablePhoneAccounts
        } catch (e: SecurityException) {
            return emptyList()
        }
        if (handles.isEmpty()) return emptyList()

        val subs: List<SubscriptionInfo> = try {
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                as? SubscriptionManager
            sm?.activeSubscriptionInfoList.orEmpty()
        } catch (e: SecurityException) {
            emptyList()
        }

        return handles.mapIndexedNotNull { index, handle ->
            // On AOSP-derived telephony the PhoneAccountHandle id is the
            // subscription id, which is how we recover the slot number and
            // carrier name. Some OEMs deviate, hence the index fallback.
            val sub = subs.firstOrNull { it.subscriptionId.toString() == handle.id }

            val slot = sub?.simSlotIndex?.plus(1) ?: (index + 1)
            val carrier = sub?.carrierName?.toString()?.takeIf { it.isNotBlank() }
                ?: runCatching { telecom.getPhoneAccount(handle)?.label?.toString() }
                    .getOrNull()?.takeIf { it.isNotBlank() }

            SimOption(
                slotIndex = slot,
                label = if (carrier != null) "SIM $slot · $carrier" else "SIM $slot",
                carrier = carrier,
                handle = handle
            )
        }.distinctBy { it.handle }.sortedBy { it.slotIndex }
    }

    /** True when the handset has more than one usable SIM. */
    fun isDualSim(): Boolean = availableSims().size > 1

    /**
     * Place the call.
     *
     * With CALL_PHONE granted the call is dialled immediately, on [sim] if one
     * was chosen. Without it we fall back to ACTION_DIAL, which opens the
     * system dialer pre-filled — the agent presses the green button. The
     * fallback cannot target a SIM; that is an OS restriction, not a bug.
     */
    fun placeCall(number: String, sim: SimOption?): CallResult {
        val cleaned = sanitize(number)
        if (cleaned.isBlank()) return CallResult.InvalidNumber

        val uri = Uri.fromParts("tel", cleaned, null)

        return if (hasCallPermission()) {
            val intent = Intent(Intent.ACTION_CALL, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                sim?.let { putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it.handle) }
            }
            try {
                context.startActivity(intent)
                CallResult.Dialled(sim?.slotIndex)
            } catch (e: SecurityException) {
                CallResult.Failed("Calling permission was revoked.")
            } catch (e: Exception) {
                CallResult.Failed(e.message ?: "Could not start the call.")
            }
        } else {
            val intent = Intent(Intent.ACTION_DIAL, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                CallResult.OpenedDialer
            } catch (e: Exception) {
                CallResult.Failed("No dialer app available on this device.")
            }
        }
    }

    /** Strip formatting the CRM may carry; keep digits, +, *, # and pauses. */
    private fun sanitize(raw: String): String =
        raw.filter { it.isDigit() || it in "+*#,;" }

    sealed class CallResult {
        data class Dialled(val simSlot: Int?) : CallResult()
        data object OpenedDialer : CallResult()
        data object InvalidNumber : CallResult()
        data class Failed(val message: String) : CallResult()
    }
}
