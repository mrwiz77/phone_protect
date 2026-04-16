package com.spshin.phone

import android.app.Application

class PhoneProtectApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        UsageSyncScheduler.schedule(this)
    }
}
