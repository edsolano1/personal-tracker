package com.edsolano.tracker.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class CalendarRepository(private val context: Context) {

    fun hasPermission() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED

    private val feed by lazy { IcsFeed(context) }

    fun load(date: LocalDate = LocalDate.now(), now: Long = System.currentTimeMillis()): DaySnapshot {
        // Read the answers here so the widgets and notifications get the same totals
        // as the app without every caller having to wire the store through.
        val store = DoneStore(context)
        val edits = FoodStore(context).edits()
        if (!hasPermission()) return DaySnapshot(date, emptyList(), null, now)
        val zone = ZoneId.systemDefault()
        // Yesterday through tomorrow, padded a day: covers all-day UTC offsets and the
        // "show yesterday's money" fallback.
        val from = date.minusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = date.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        val device = query(from, to)
        // With the iCal link set, Google's copy is the truth and the phone only adds
        // what it has not uploaded yet. Without it, read the phone alone as before.
        val feedEvents = feed.events()
        val merged = if (feedEvents != null) FeedMerge.merge(device, syncInfo(), feedEvents, from, to) else device
        val all = merged.filter { EventParser.isPlan(it.title) || EventParser.isMoney(it.title) }

        // Prefer the dedicated calendar; fall back to any calendar if it doesn't exist yet.
        val calendarFound = feedEvents != null ||
            all.any { it.calendar.equals(Targets.CALENDAR_NAME, ignoreCase = true) }
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
                        val p = applyEdit(
                            EventParser.parsePlan(r.eventId, r.title, r.desc, r.begin, r.end), edits,
                        )
                        plan += p
                        "Plan: ${p.type.name.lowercase()}, ${p.calories} cal, ${p.protein}g" +
                            (p.slot?.let { ", $it" } ?: "") +
                            (if (p.swaps.isNotEmpty()) ", ${p.swaps.size} swaps" else "") +
                            (p.eatenOnCalendar?.let { if (it) ", eaten" else ", skipped" } ?: "") +
                            (if (p.edited) ", edited on the phone" else "")
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
        return DaySnapshot(
            date, plan.sortedBy { it.startMillis }, money, now, calendarFound,
            raw.sortedBy { it.begin }, store.done(), store.skipped(),
        )
    }

    /**
     * Folds in what Eddie changed about this meal. Done here rather than in the UI so
     * the widgets, the notifications and the screens all read the same numbers.
     */
    private fun applyEdit(item: PlanItem, edits: Map<String, FoodEdit>): PlanItem {
        val e = edits[item.doneKey] ?: return item
        return item.copy(
            name = e.name.ifBlank { item.name },
            calories = e.calories,
            protein = e.protein,
            portion = e.portion,
            planned = Swap(item.name, item.calories, item.protein),
        )
    }

    /** Google's id and the unsent-changes flag for each event in the dedicated calendar. */
    private fun syncInfo(): Map<Long, SyncInfo> = runCatching {
        val out = mutableMapOf<Long, SyncInfo>()
        context.contentResolver.query(
            Events.CONTENT_URI, arrayOf(Events._ID, Events._SYNC_ID, Events.DIRTY),
            "${Events.CALENDAR_DISPLAY_NAME} = ?", arrayOf(Targets.CALENDAR_NAME), null,
        )?.use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = SyncInfo(c.getString(1), c.getInt(2) == 1)
        }
        out as Map<Long, SyncInfo>
    }.getOrDefault(emptyMap())

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

internal data class Row(
    val eventId: Long, val calendar: String, val title: String, val desc: String,
    val begin: Long, val end: Long, val allDay: Boolean,
) {
    /** The local date this instance belongs to (all-day events are stored at UTC midnight). */
    fun date(zone: ZoneId): LocalDate =
        if (allDay) Instant.ofEpochMilli(begin).atOffset(ZoneOffset.UTC).toLocalDate()
        else Instant.ofEpochMilli(begin).atZone(zone).toLocalDate()
}

/** Google's event id for a phone event (null until uploaded), and whether it has unsent edits. */
internal data class SyncInfo(val googleId: String?, val dirty: Boolean)

/** Puts Google's copy of the dedicated calendar together with what only the phone knows. */
internal object FeedMerge {
    fun merge(device: List<Row>, sync: Map<Long, SyncInfo>, feed: List<IcsEvent>, from: Long, to: Long): List<Row> {
        val isTracker = { r: Row -> r.calendar.equals(Targets.CALENDAR_NAME, ignoreCase = true) }
        val trackerRows = device.filter(isTracker)
        val byGoogleId = trackerRows.mapNotNull { r -> sync[r.eventId]?.googleId?.let { it to r } }.toMap()
        val out = device.filterNot(isTracker).toMutableList()
        val used = mutableSetOf<Long>()
        for (e in feed) {
            if (e.begin >= to || maxOf(e.end, e.begin + 1) <= from) continue
            val local = byGoogleId[e.googleId]
            if (local != null) used += local.eventId
            // A phone edit Google hasn't received yet (a tick, an added "eaten:") wins.
            out += if (local != null && sync[local.eventId]?.dirty == true) local
            else Row(
                // Keep the phone's id when it has the event, so ticks and "eaten:" writes
                // still land on it; otherwise a stable negative id the writers refuse.
                local?.eventId ?: -(e.googleId.hashCode().toLong() and 0x7fffffffL) - 1,
                Targets.CALENDAR_NAME, e.title, e.description, e.begin, e.end, e.allDay,
            )
        }
        // Events made on the phone that never reached Google yet still count.
        out += trackerRows.filter { it.eventId !in used && sync[it.eventId]?.let { s -> s.googleId == null } == true }
        return out.sortedBy { it.begin }
    }
}
