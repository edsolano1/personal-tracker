package com.edsolano.tracker

import android.app.Application
import com.edsolano.tracker.notify.AlarmScheduler
import com.edsolano.tracker.work.RefreshScheduler

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AlarmScheduler.createChannel(this)
        RefreshScheduler.ensureScheduled(this)
    }
}
