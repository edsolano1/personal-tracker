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
import com.edsolano.tracker.data.DoneStore
import com.edsolano.tracker.data.MealAnswer
import com.edsolano.tracker.data.PlanItem
import com.edsolano.tracker.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

object AlarmScheduler {
    const val CHANNEL = "plan-items"
    const val MEAL_CHANNEL = "meal-checks"
    private const val PREFS = "alarms"
    private const val KEY = "codes"
    private const val KEY_MEALS = "meal-codes"

    const val ACTION_START = "com.edsolano.tracker.PLAN_START"
    const val ACTION_CHECK = "com.edsolano.tracker.MEAL_CHECK"
    const val ACTION_ATE = "com.edsolano.tracker.MEAL_ATE"
    const val ACTION_SKIPPED = "com.edsolano.tracker.MEAL_SKIPPED"

    fun createChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Plan items", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "A heads-up when each plan item starts" }
        )
        nm.createNotificationChannel(
            NotificationChannel(MEAL_CHANNEL, "Meal checks", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Asks whether you ate each meal, so nothing is logged for you" }
        )
    }

    /**
     * Cancels the alarms set last time, then sets, for the next ~24h, one alarm per
     * plan item at its start and one per meal at the end of its slot. The meal alarm
     * is what asks whether it was eaten; nothing is counted until that is answered.
     */
    fun scheduleUpcoming(context: Context) {
        val repo = CalendarRepository(context)
        val now = System.currentTimeMillis()
        val all = (repo.load(LocalDate.now(), now).plan + repo.load(LocalDate.now().plusDays(1), now).plan)
            .distinctBy { it.id to it.startMillis }

        val am = context.getSystemService(AlarmManager::class.java)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // The action is part of PendingIntent matching, so each kind of alarm has to
        // be cancelled with an intent carrying its own action.
        prefs.getStringSet(KEY, emptySet())!!
            .forEach { am.cancel(alarmIntent(context, it.toInt(), ACTION_START, null)) }
        prefs.getStringSet(KEY_MEALS, emptySet())!!
            .forEach { am.cancel(alarmIntent(context, it.toInt(), ACTION_CHECK, null)) }

        val startCodes = mutableSetOf<String>()
        all.filter { it.startMillis > now }.forEach { item ->
            val code = codeFor(item, "start")
            set(am, item.startMillis, alarmIntent(context, code, ACTION_START, item))
            startCodes += code.toString()
        }

        val mealCodes = mutableSetOf<String>()
        all.filter { it.isFood && it.endMillis > now }.forEach { item ->
            val code = codeFor(item, "check")
            set(am, item.endMillis, alarmIntent(context, code, ACTION_CHECK, item))
            mealCodes += code.toString()
        }

        prefs.edit().putStringSet(KEY, startCodes).putStringSet(KEY_MEALS, mealCodes).apply()
    }

    private fun set(am: AlarmManager, at: Long, pi: PendingIntent) {
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    private fun codeFor(item: PlanItem, kind: String) = "$kind:${item.doneKey}".hashCode()

    private fun alarmIntent(context: Context, code: Int, action: String, item: PlanItem?): PendingIntent {
        val intent = Intent(context, PlanAlarmReceiver::class.java).apply {
            this.action = action
            if (item != null) {
                putExtra("name", item.name)
                putExtra("cal", item.calories)
                putExtra("protein", item.protein)
                putExtra("workout", item.isWorkout)
                putExtra("key", item.doneKey)
                putExtra("slot", item.slot)
            }
        }
        return PendingIntent.getBroadcast(
            context, code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** The button on the "Did you eat it?" notification, answered without opening the app. */
    private fun answerIntent(context: Context, key: String, action: String): PendingIntent {
        val intent = Intent(context, PlanAlarmReceiver::class.java).apply {
            this.action = action
            putExtra("key", key)
        }
        return PendingIntent.getBroadcast(
            context, "$action:$key".hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    internal fun ateIntent(context: Context, key: String) = answerIntent(context, key, ACTION_ATE)
    internal fun skippedIntent(context: Context, key: String) = answerIntent(context, key, ACTION_SKIPPED)
}

class PlanAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AlarmScheduler.ACTION_START -> planStarting(context, intent)
            AlarmScheduler.ACTION_CHECK -> askAboutMeal(context, intent)
            AlarmScheduler.ACTION_ATE -> record(context, intent, MealAnswer.ATE)
            AlarmScheduler.ACTION_SKIPPED -> record(context, intent, MealAnswer.SKIPPED)
        }
    }

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun planStarting(context: Context, intent: Intent) {
        val name = intent.getStringExtra("name") ?: return
        val cal = intent.getIntExtra("cal", 0)
        val protein = intent.getIntExtra("protein", 0)
        val text = when {
            intent.getBooleanExtra("workout", false) -> "Workout time"
            cal > 0 || protein > 0 -> "$cal cal · ${protein}g protein"
            else -> "Starting now"
        }
        val n = NotificationCompat.Builder(context, AlarmScheduler.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(name)
            .setContentText(text)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        post(context, name.hashCode(), n)
    }

    /** Asks once the slot is over. Never assumes; an unanswered meal simply does not count. */
    private fun askAboutMeal(context: Context, intent: Intent) {
        val key = intent.getStringExtra("key") ?: return
        val name = intent.getStringExtra("name") ?: return
        val store = DoneStore(context)
        if (key in store.done() || key in store.skipped()) return  // already answered in the app

        val cal = intent.getIntExtra("cal", 0)
        val protein = intent.getIntExtra("protein", 0)
        val slot = intent.getStringExtra("slot")
        val detail = buildList {
            if (slot != null) add(slot)
            if (cal > 0) add("$cal cal")
            if (protein > 0) add("${protein}g protein")
        }.joinToString(" · ")

        val n = NotificationCompat.Builder(context, AlarmScheduler.MEAL_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Did you eat it?")
            .setContentText(if (detail.isEmpty()) name else "$name · $detail")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(if (detail.isEmpty()) name else "$name\n$detail")
            )
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(R.drawable.ic_stat, "Ate it", AlarmScheduler.ateIntent(context, key))
            .addAction(R.drawable.ic_stat, "Skipped", AlarmScheduler.skippedIntent(context, key))
            .build()
        post(context, key.hashCode(), n)
    }

    private fun record(context: Context, intent: Intent, answer: MealAnswer) {
        val key = intent.getStringExtra("key") ?: return
        DoneStore(context).mark(key, answer)
        NotificationManagerCompat.from(context).cancel(key.hashCode())
        val finish = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                WidgetUpdater.updateAll(context)
            } finally {
                finish.finish()
            }
        }
    }

    private fun post(context: Context, id: Int, n: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) { /* notifications not allowed */ }
    }
}
