package com.edsolano.tracker.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.edsolano.tracker.notify.AlarmScheduler
import com.edsolano.tracker.widget.WidgetUpdater
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Redraws all widgets and re-arms plan-item notifications. */
suspend fun refreshEverything(context: Context) {
    WidgetUpdater.updateAll(context)
    AlarmScheduler.scheduleUpcoming(context)
}

class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        refreshEverything(applicationContext)
        return Result.success()
    }
}

/**
 * Runs when anything in the calendar provider changes. Content-URI triggers
 * only fire once, so the worker queues a fresh watcher before finishing.
 */
class CalendarWatchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        refreshEverything(applicationContext)
        RefreshScheduler.watchCalendar(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }
}

object RefreshScheduler {
    private const val PERIODIC = "refresh-30m"
    private const val WATCH = "calendar-watch"

    fun ensureScheduled(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<RefreshWorker>(30, TimeUnit.MINUTES).build(),
        )
        watchCalendar(context, ExistingWorkPolicy.KEEP)
    }

    fun watchCalendar(context: Context, policy: ExistingWorkPolicy) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(CalendarContract.Events.CONTENT_URI, true)
            .addContentUriTrigger(CalendarContract.Instances.CONTENT_URI, true)
            .setTriggerContentUpdateDelay(Duration.ofSeconds(5))
            .setTriggerContentMaxDelay(Duration.ofSeconds(30))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WATCH, policy,
            OneTimeWorkRequestBuilder<CalendarWatchWorker>().setConstraints(constraints).build(),
        )
    }

    fun refreshNow(context: Context) {
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<RefreshWorker>().build())
    }
}

/** Re-arms everything after a reboot, a time zone change or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RefreshScheduler.ensureScheduled(context)
        RefreshScheduler.refreshNow(context)
    }
}
