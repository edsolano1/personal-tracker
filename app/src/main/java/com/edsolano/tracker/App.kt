package com.edsolano.tracker

import android.app.Application
import android.app.NotificationManager
import androidx.work.WorkManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        com.edsolano.tracker.wallet.Wallet.createChannel(this)
        com.edsolano.tracker.wallet.Wallet.schedule(this)
        // Finance only since 0.13.0: stop the old calendar jobs and drop the meal channels.
        WorkManager.getInstance(this).apply {
            cancelUniqueWork("refresh-30m")
            cancelUniqueWork("calendar-watch")
        }
        getSystemService(NotificationManager::class.java).apply {
            deleteNotificationChannel("plan-items")
            deleteNotificationChannel("meal-checks")
        }
    }
}
