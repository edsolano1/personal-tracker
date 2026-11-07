package com.edsolano.tracker.notify

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.edsolano.tracker.MainActivity
import com.edsolano.tracker.R
import com.edsolano.tracker.data.CalendarRepository
import com.edsolano.tracker.data.PlanItem
import java.time.LocalDate

object AlarmScheduler {
    const val CHANNEL = "plan-items"
    private const val PREFS = "alarms"
    private const val KEY = "codes"

    fun createChannel(context: Context) {
        val ch = NotificationChannel(CHANNEL, "Plan items", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "A heads-up when each plan item starts" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    /** Cancels the alarms set last time, then sets one per plan item starting in the next ~24h. */
    fun scheduleUpcoming(context: Context) {
        val repo = CalendarRepository(context)
        val now = System.currentTimeMillis()
        val items = (repo.load(LocalDate.now(), now).plan + repo.load(LocalDate.now().plusDays(1), now).plan)
            .filter { it.startMillis > now }
            .distinctBy { it.id to it.startMillis }

        val am = context.getSystemService(AlarmManager::class.java)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getStringSet(KEY, emptySet())!!.forEach { code ->
            am.cancel(pending(context, code.toInt(), null))
        }
        val codes = mutableSetOf<String>()
        items.forEach { item ->
            val code = (item.id * 31 + item.startMillis / 60_000).hashCode()
            val pi = pending(context, code, item)
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.startMillis, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.startMillis, pi)
            }
            codes += code.toString()
        }
        prefs.edit().putStringSet(KEY, codes).apply()
    }

    private fun pending(context: Context, code: Int, item: PlanItem?): PendingIntent {
        val intent = Intent(context, PlanAlarmReceiver::class.java).apply {
            action = "com.edsolano.tracker.PLAN_START"
            if (item != null) {
                putExtra("name", item.name)
                putExtra("cal", item.calories)
                putExtra("protein", item.protein)
                putExtra("workout", item.isWorkout)
            }
        }
        return PendingIntent.getBroadcast(
            context, code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

class PlanAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("name") ?: return
        val cal = intent.getIntExtra("cal", 0)
        val protein = intent.getIntExtra("protein", 0)
        val text = when {
            intent.getBooleanExtra("workout", false) -> "Workout time"
            cal > 0 || protein > 0 -> "$cal cal · ${protein}g protein"
            else -> "Starting now"
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, AlarmScheduler.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(name)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(name.hashCode(), n)
        } catch (_: SecurityException) { /* notifications not allowed */ }
    }
}
