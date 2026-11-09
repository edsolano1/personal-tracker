package com.edsolano.tracker.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import java.time.ZoneId

/**
 * Sends a note from the phone to Claude by writing a "[Msg]" event onto the
 * calendar. The calendar is already the channel between the two: Claude writes
 * [Plan] and [Money] events and the app reads them, so this just runs the same
 * pipe backwards. Nothing new to host, and the note survives on the calendar
 * whether or not Claude is running.
 */
class MessageWriter(private val context: Context) {

    fun hasPermission() = context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED

    private data class Candidate(val id: Long, val name: String, val primary: Boolean)

    /**
     * Where to write: the dedicated calendar if it has reached the phone, otherwise
     * the main account's own calendar, which always syncs.
     */
    private fun target(): Candidate? {
        val projection = arrayOf(
            Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.CALENDAR_ACCESS_LEVEL, Calendars.IS_PRIMARY,
        )
        val found = mutableListOf<Candidate>()
        runCatching {
            context.contentResolver.query(
                Calendars.CONTENT_URI, projection, "${Calendars.VISIBLE} = 1", null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    // Writing needs at least contributor access; read-only calendars reject inserts.
                    if (c.getInt(2) < Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                    found += Candidate(c.getLong(0), c.getString(1).orEmpty(), c.getInt(3) == 1)
                }
            }
        }
        return found.firstOrNull { it.name.equals(Targets.CALENDAR_NAME, ignoreCase = true) }
            ?: found.firstOrNull { it.primary }
            ?: found.firstOrNull()
    }

    /** Writes the note. Returns the calendar it landed on, or null if it could not be sent. */
    fun send(text: String, now: Long = System.currentTimeMillis()): String? {
        val body = text.trim()
        if (body.isEmpty() || !hasPermission()) return null
        val cal = target() ?: return null
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, cal.id)
            put(Events.TITLE, "[Msg] " + body.lineSequence().first().take(80))
            put(Events.DESCRIPTION, body)
            put(Events.DTSTART, now)
            put(Events.DTEND, now + 15 * 60 * 1000)
            put(Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
            put(Events.AVAILABILITY, Events.AVAILABILITY_FREE)
        }
        val uri = runCatching { context.contentResolver.insert(Events.CONTENT_URI, values) }.getOrNull()
        return if (uri == null) null else cal.name
    }
}

/** The last few notes sent, so the screen can show that they actually went. */
class MessageLog(context: Context) {
    private val prefs = context.getSharedPreferences("messages", Context.MODE_PRIVATE)

    fun recent(): List<String> =
        prefs.getString(KEY, "").orEmpty().split('\n').filter { it.isNotBlank() }

    fun add(line: String) {
        val next = (listOf(line.replace('\n', ' ')) + recent()).take(5)
        prefs.edit().putString(KEY, next.joinToString("\n")).apply()
    }

    private companion object { const val KEY = "sent" }
}
