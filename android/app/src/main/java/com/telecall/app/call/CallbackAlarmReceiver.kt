package com.telecall.app.call

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.telecall.app.MainActivity

/**
 * Fires at the exact moment a callback was scheduled for. Builds and posts
 * the notification directly here rather than starting a service — this is
 * a single, fast, synchronous call (NotificationManagerCompat.notify),
 * exactly what a BroadcastReceiver's short-lived onReceive is for.
 */
class CallbackAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val leadId = intent.getLongExtra(EXTRA_LEAD_ID, -1L)
        if (leadId < 0) return
        val leadName = intent.getStringExtra(EXTRA_LEAD_NAME).orEmpty().ifBlank { "your callback" }
        val mobile = intent.getStringExtra(EXTRA_MOBILE).orEmpty()

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_LEAD_ID, leadId)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            leadId.toInt(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Callback due: $leadName")
            .setContentText(if (mobile.isNotBlank()) "Tap to open · $mobile" else "Tap to open")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(leadId.toInt(), notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS was denied or revoked since the alarm was
            // scheduled. Nothing to show for it and no UI to report it
            // from — the agent will still see the callback at the top of
            // their queue next time they open the app.
        }
    }

    companion object {
        const val EXTRA_LEAD_ID = "lead_id"
        const val EXTRA_LEAD_NAME = "lead_name"
        const val EXTRA_MOBILE = "mobile"
        const val CHANNEL_ID = "callbacks"
    }
}
