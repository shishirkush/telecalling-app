package com.telecall.app.call

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * One alarm per pending callback, keyed by lead id so re-dispositioning a
 * lead (or picking a new callback time for the same one) replaces rather
 * than stacks a duplicate reminder.
 *
 * Prefers setExactAndAllowWhileIdle so the reminder still fires close to
 * the chosen time under Doze. Exact alarms need either the
 * SCHEDULE_EXACT_ALARM permission — granted automatically to this app on
 * most OEM builds since it is sideloaded, not installed from Play, but not
 * guaranteed everywhere — or the agent enabling "Alarms & reminders" for
 * the app in Settings. Falling back to an inexact alarm rather than
 * scheduling nothing keeps the reminder useful (a few minutes late) even
 * on a device that has withheld the exact-alarm permission.
 */
object CallbackScheduler {

    fun schedule(context: Context, leadId: Long, leadName: String, mobile: String, triggerAtMillis: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pendingIntent(context, leadId, leadName, mobile)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    /** Called before re-dispositioning a lead so a stale reminder cannot fire later. */
    fun cancel(context: Context, leadId: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(pendingIntent(context, leadId, "", ""))
    }

    // PendingIntent equality for AlarmManager's purposes is the target
    // component + request code, not the extras — so a cancel() call can
    // pass empty placeholder extras and still match the original alarm.
    private fun pendingIntent(context: Context, leadId: Long, leadName: String, mobile: String): PendingIntent {
        val intent = Intent(context, CallbackAlarmReceiver::class.java).apply {
            putExtra(CallbackAlarmReceiver.EXTRA_LEAD_ID, leadId)
            putExtra(CallbackAlarmReceiver.EXTRA_LEAD_NAME, leadName)
            putExtra(CallbackAlarmReceiver.EXTRA_MOBILE, mobile)
        }
        return PendingIntent.getBroadcast(
            context,
            leadId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
