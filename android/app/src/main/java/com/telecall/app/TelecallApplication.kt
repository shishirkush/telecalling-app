package com.telecall.app

import android.app.Application
import com.telecall.app.data.LeadRepository
import com.telecall.app.data.SupabaseClient

class TelecallApplication : Application() {

    lateinit var repository: LeadRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = LeadRepository(SupabaseClient(this))
    }
}
