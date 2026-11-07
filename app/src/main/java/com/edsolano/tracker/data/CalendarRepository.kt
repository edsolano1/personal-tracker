package com.edsolano.tracker.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Instances
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class CalendarRepository(private val context: Context) {

    fun hasPermission() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED

    private data class Row(
        val eventId: Long, val calendar: String, val title: String, val desc: String,
        val begin: Long, val end: Long, val allDay: Boolean,
    ) {
        /** The local date this instance belongs to (all-day events are stored at UTC midnight). */
        fun date(zone: ZoneId): LocalDate =
            if (allDay) Instant.ofEpochMilli(begin).atOffset(ZoneOffset.UTC).toLocalDate()
            else Instant.ofEpochMilli(begin).atZone(zone).toLocalDate()
    }

    fun load(date: LocalDate = LocalDate.now(), now: Long = System.currentTimeMillis()): DaySnapshot {
        if (!hasPermission()) return DaySnapshot(date, emptyList(), null, now)
        val zone = ZoneId.systemDefault()
        // Yesterday through tomorrow, padded a day: covers all-day UTC offsets and the
        // "show yesterday's money" fallback.
        val from = date.minusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = date.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        val all = query(from, to).filter { EventParser.isPlan(it.title) || EventParser.isMoney(it.title) }

        // Prefer the dedicated calendar; fall back to any calendar if it doesn't exist yet.
        val calendarFound = all.any { it.calendar.equals(Targets.CALENDAR_NAME, ignoreCase = true) }
        val rows = if (calendarFound) all.filter { it.calendar.equals(Targets.CALENDAR_NAME, ignoreCase = true) } else all

        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val raw = mutableListOf<RawEvent>()
        val plan = mutableListOf<PlanItem>()
        val moneyByDate = mutableMapOf<LocalDate, Pair<Long, MoneyDay>>()

        for (r in rows) {
            val d = r.date(zone)
            val parsed: String = when {
                EventParser.isPlan(r.title) -> {
                    val onDay = !r.allDay && r.begin < dayEnd && r.end > dayStart
                    if (onDay) {
                        val p = EventParser.parsePlan(r.eventId, r.title, r.desc, r.begin, r.end)
                        plan += p
                        "Plan: ${p.type.name.lowercase()}, ${p.calories} cal, ${p.protein}g" +
                            (p.slot?.let { ", $it" } ?: "") + (if (p.swaps.isNotEmpty()) ", ${p.swaps.size} swaps" else "")
                    } else if (r.allDay) "Ignored: plan items need a start time" else "Plan, other day"
                }
                else -> {
                    val m = EventParser.parseMoney(d, r.title, r.desc)
                    if (m == null) "Money: couldn't read the amount"
                    else {
                        // Newest event for a date wins (highest event id = created last).
                        val cur = moneyByDate[d]
                        if (cur == null || r.eventId > cur.first) moneyByDate[d] = r.eventId to m
                        "Money for $d: ${m.spendingLeft}, ${m.charges.size} charges"
                    }
                }
            }
            if (d == date || d == date.minusDays(1)) {
                raw += RawEvent(r.calendar, r.title, r.desc, r.begin, r.allDay, parsed)
            }
        }
        val money = moneyByDate[date]?.second ?: moneyByDate[date.minusDays(1)]?.second
        return DaySnapshot(date, plan.sortedBy { it.startMillis }, money, now, calendarFound, raw.sortedBy { it.begin })
    }

    private fun query(from: Long, to: Long): List<Row> {
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val projection = arrayOf(
            Instances.EVENT_ID, Instances.CALENDAR_DISPLAY_NAME, Instances.TITLE,
            Instances.DESCRIPTION, Instances.BEGIN, Instances.END, Instances.ALL_DAY,
        )
        val out = mutableListOf<Row>()
        context.contentResolver.query(uri, projection, null, null, "${Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                out += Row(
                    c.getLong(0), c.getString(1).orEmpty(), c.getString(2) ?: continue,
                    c.getString(3).orEmpty(), c.getLong(4), c.getLong(5), c.getInt(6) == 1,
                )
            }
        }
        return out
    }
}
