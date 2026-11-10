package com.edsolano.tracker.data

import android.Manifest
import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import java.time.ZoneId

/**
 * Adds a meal that was never on the plan, and asks the phone to sync the calendar.
 *
 * An added meal is written as an ordinary "[Plan]" event that already says
 * "eaten: yes", so it counts at once, shows up next to the planned meals, and
 * reaches Claude through the same calendar everything else travels on.
 */
class PlanWriter(private val context: Context) {

    fun hasPermission() = context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED

    private data class Target(val id: Long, val name: String, val account: String?, val accountType: String?)

    /** The dedicated calendar if it is on the phone, otherwise the main account's own. */
    private fun target(): Target? {
        val projection = arrayOf(
            Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.CALENDAR_ACCESS_LEVEL,
            Calendars.IS_PRIMARY, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE,
        )
        val found = mutableListOf<Pair<Target, Boolean>>()
        runCatching {
            context.contentResolver.query(
                Calendars.CONTENT_URI, projection, "${Calendars.VISIBLE} = 1", null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(2) < Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                    found += Target(c.getLong(0), c.getString(1).orEmpty(), c.getString(4), c.getString(5)) to
                        (c.getInt(3) == 1)
                }
            }
        }
        return found.firstOrNull { it.first.name.equals(Targets.CALENDAR_NAME, ignoreCase = true) }?.first
            ?: found.firstOrNull { it.second }?.first
            ?: found.firstOrNull()?.first
    }

    /** Writes the meal at [now]. True when the event was created. */
    fun addMeal(edit: FoodEdit, now: Long = System.currentTimeMillis()): Boolean {
        if (!hasPermission() || edit.name.isBlank()) return false
        val cal = target() ?: return false
        val zone = ZoneId.systemDefault()
        val hour = java.time.Instant.ofEpochMilli(now).atZone(zone).hour
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, cal.id)
            put(Events.TITLE, "[Plan] " + edit.name.trim().replace('\n', ' '))
            put(Events.DESCRIPTION, PlanText.addedMeal(edit, PlanText.slotFor(hour)))
            put(Events.DTSTART, now)
            put(Events.DTEND, now + 15 * 60 * 1000)
            put(Events.EVENT_TIMEZONE, zone.id)
            put(Events.AVAILABILITY, Events.AVAILABILITY_FREE)
        }
        return runCatching { context.contentResolver.insert(Events.CONTENT_URI, values) }.getOrNull() != null
    }

    /**
     * Asks Android to sync the calendar's account now, both ways. Google only syncs
     * this calendar by itself about once a day, so without this a plan Claude just
     * wrote can sit unseen for hours. Best effort: false when it could not even ask.
     */
    fun requestSync(): Boolean {
        val cal = target() ?: return false
        val name = cal.account ?: return false
        val type = cal.accountType ?: return false
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        return runCatching {
            ContentResolver.requestSync(Account(name, type), CalendarContract.AUTHORITY, extras)
        }.isSuccess
    }
}

/** The text of an added meal, kept pure so the tests can read it back through the parser. */
internal object PlanText {
    fun slotFor(hour: Int) = when {
        hour < 11 -> "Breakfast"
        hour < 15 -> "Lunch"
        hour < 17 -> "Snack"
        hour < 21 -> "Dinner"
        else -> "Evening snack"
    }

    fun addedMeal(edit: FoodEdit, slot: String) = buildList {
        add("type: meal")
        add("slot: $slot")
        add("mins: 15")
        add("cal: ${edit.calories}")
        add("protein: ${edit.protein}")
        edit.portion?.let { add("portion: ${it.label}") }
        add("detail: Added on the phone")
        add("eaten: yes")
    }.joinToString("\n")
}
