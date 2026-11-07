package com.edsolano.tracker.data

import android.content.Context

/** Done checks for non-food plan items, kept only on the phone (nothing is written to the calendar). */
class DoneStore(context: Context) {
    private val prefs = context.getSharedPreferences("done", Context.MODE_PRIVATE)
    fun all(): Set<String> = prefs.getStringSet(KEY, emptySet())!!.toSet()
    fun toggle(key: String): Set<String> {
        val next = all().let { if (key in it) it - key else it + key }
        prefs.edit().putStringSet(KEY, next).apply()
        return next
    }
    private companion object { const val KEY = "keys" }
}
