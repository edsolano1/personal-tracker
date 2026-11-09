package com.edsolano.tracker.data

import android.content.Context

/**
 * What you did and didn't do, kept only on the phone (nothing is written to the calendar).
 *
 * Meals are three-state on purpose. "Unanswered" is not the same as "skipped": the
 * app used to treat a meal as eaten the moment its start time passed, which quietly
 * logged food Eddie never ate. Nothing counts now until he says so.
 */
class DoneStore(context: Context) {
    private val prefs = context.getSharedPreferences("done", Context.MODE_PRIVATE)

    fun done(): Set<String> = prefs.getStringSet(DONE, emptySet())!!.toSet()
    fun skipped(): Set<String> = prefs.getStringSet(SKIPPED, emptySet())!!.toSet()

    /** Checkbox behaviour: ticking means done, unticking goes back to unanswered. */
    fun toggle(key: String) =
        if (key in done()) mark(key, MealAnswer.UNANSWERED) else mark(key, MealAnswer.ATE)

    fun mark(key: String, answer: MealAnswer) {
        val d = done()
        val s = skipped()
        when (answer) {
            MealAnswer.ATE -> write(d + key, s - key)
            MealAnswer.SKIPPED -> write(d - key, s + key)
            MealAnswer.UNANSWERED -> write(d - key, s - key)
        }
    }

    private fun write(done: Set<String>, skipped: Set<String>) {
        prefs.edit().putStringSet(DONE, done).putStringSet(SKIPPED, skipped).apply()
    }

    private companion object {
        // "keys" is the original name, kept so existing checks survive the update.
        const val DONE = "keys"
        const val SKIPPED = "skipped"
    }
}
