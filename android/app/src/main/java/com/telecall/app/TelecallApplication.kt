package com.telecall.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.telecall.app.call.CallbackAlarmReceiver
import com.telecall.app.data.LeadRepository
import com.telecall.app.data.SupabaseClient

class TelecallApplication : Application() {

    lateinit var repository: LeadRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = LeadRepository(SupabaseClient(this))
        createCallbackNotificationChannel()
    }

    // A channel must exist before CallbackAlarmReceiver can post to it —
    // created once here rather than lazily in the receiver so it shows up
    // in Settings even before the agent's first callback fires.
    private fun createCallbackNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CallbackAlarmReceiver.CHANNEL_ID,
            "Callback reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts you at the time you scheduled a Call Later for."
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
