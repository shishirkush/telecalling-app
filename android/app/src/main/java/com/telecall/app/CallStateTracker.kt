package com.telecall.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * Times the call just placed to a lead, from dialling to hang-up.
 *
 * The call log only records TALK time, so an unanswered call is 0s and says
 * nothing about how long it rang. The phone's call state does: OFFHOOK when
 * dialling starts, IDLE when the call ends. Their difference is [dialSecs];
 * the dashboard subtracts the call log's talk time to get ring time. See
 * backend/40_call_timing_ring.sql.
 *
 * Deliberately narrow: armed only when the agent taps Call on a lead, it
 * records two timestamps and nothing else — no numbers, no call content —
 * and unregisters itself as soon as the call ends. Needs READ_PHONE_STATE
 * (already requested for SIM detection); without it, it quietly does nothing.
 * In-memory only: if the process dies mid-call there is simply no reading.
 */
object CallStateTracker {
    private var leadId: Long? = null
    private var offhookAt = 0L
    private var idleAt = 0L
    private var unregister: (() -> Unit)? = null

    @Synchronized
    fun arm(context: Context, id: Long) {
        release()
        leadId = id
        offhookAt = 0L
        idleAt = 0L
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = onState(state)
                }
                tm.registerTelephonyCallback(ContextCompat.getMainExecutor(context), cb)
                unregister = { tm.unregisterTelephonyCallback(cb) }
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) = onState(state)
                }
                @Suppress("DEPRECATION")
                tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                @Suppress("DEPRECATION")
                unregister = { tm.listen(listener, PhoneStateListener.LISTEN_NONE) }
            }
        } catch (e: Exception) {
            unregister = null
        }
    }

    // The first callback after registering reports the CURRENT state (IDLE),
    // which is ignored here because no OFFHOOK has been seen yet.
    @Synchronized
    private fun onState(state: Int) {
        val now = System.currentTimeMillis()
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> if (offhookAt == 0L) offhookAt = now
            TelephonyManager.CALL_STATE_IDLE ->
                if (offhookAt != 0L && idleAt == 0L) {
                    idleAt = now
                    release()
                }
        }
    }

    @Synchronized
    private fun release() {
        try { unregister?.invoke() } catch (e: Exception) { }
        unregister = null
    }

    /** True while a call to this lead is still being watched (not yet hung up). */
    @Synchronized
    fun isTracking(id: Long): Boolean = leadId == id && unregister != null && idleAt == 0L

    /** Seconds from dialling to hang-up, or null if the call hasn't ended / wasn't observed. */
    @Synchronized
    fun dialSecs(id: Long): Int? =
        if (leadId == id && offhookAt != 0L && idleAt != 0L)
            ((idleAt - offhookAt) / 1000L).toInt().coerceIn(0, 14400)
        else null
}
