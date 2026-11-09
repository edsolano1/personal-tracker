package com.edsolano.tracker.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Events

/**
 * Puts the answer back on the calendar as "eaten: yes" or "eaten: no".
 *
 * The calendar is the record both sides read: Claude writes that line when Eddie
 * tells him in chat, the app writes it when he taps. Without this the phone's
 * answers would be invisible to Claude and the two would quietly drift apart —
 * which is exactly the two-sources-of-truth problem worth not repeating.
 *
 * Only the "eaten" line is ever touched. Everything else Claude wrote in the
 * description is carried across untouched, and a repeating event is left alone
 * because one instance's answer must not be stamped on all of them.
 */
class EatenWriter(private val context: Context) {

    fun hasPermission() = context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED

    /** True once the event says what [answer] says. Failure is not fatal: the tap still stands locally. */
    fun write(eventId: Long, answer: MealAnswer): Boolean {
        if (!hasPermission() || eventId <= 0) return false
        val current = read(eventId) ?: return false
        if (current.repeating) return false
        val next = EatenLine.rewrite(current.description, value(answer))
        if (next == current.description) return true
        val values = ContentValues().apply { put(Events.DESCRIPTION, next) }
        return runCatching {
            context.contentResolver.update(
                ContentUris.withAppendedId(Events.CONTENT_URI, eventId), values, null, null,
            ) == 1
        }.getOrDefault(false)
    }

    private fun value(answer: MealAnswer) = when (answer) {
        MealAnswer.ATE -> "yes"
        MealAnswer.SKIPPED -> "no"
        MealAnswer.UNANSWERED -> null   // removes the line: back to simply planned
    }

    private data class Current(val description: String, val repeating: Boolean)

    private fun read(eventId: Long): Current? = runCatching {
        context.contentResolver.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
            arrayOf(Events.DESCRIPTION, Events.RRULE), null, null, null,
        )?.use { c ->
            if (!c.moveToNext()) null
            else Current(c.getString(0).orEmpty(), !c.getString(1).isNullOrBlank())
        }
    }.getOrNull()
}

/** The line edit itself, kept pure so the tests can hold it to its word. */
internal object EatenLine {
    /** Replaces the "eaten:" line, adds one when it is missing, drops it when [value] is null. */
    fun rewrite(description: String, value: String?): String {
        val kept = description.lines().filterNot { isEaten(it) }
        val body = kept.joinToString("\n").trimEnd()
        return when {
            value == null -> body
            body.isBlank() -> "eaten: $value"
            else -> "$body\neaten: $value"
        }
    }

    private fun isEaten(line: String) =
        line.contains(':') && line.substringBefore(':').trim().equals("eaten", ignoreCase = true)
}
