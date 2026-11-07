package com.edsolano.tracker.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Instances
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class CalendarRepository(private val context: Context) {

    fun hasPermission() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED

    fun load(date: LocalDate = LocalDate.now(), now: Long = System.currentTimeMillis()): DaySnapshot {
        if (!hasPermission()) return DaySnapshot(emptyList(), null, now)
        val zone = ZoneId.systemDefault()
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        // All-day events are stored at UTC midnight, so widen the window by a day each side.
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, dayStart - DAY)
            ContentUris.appendId(it, dayEnd + DAY)
        }.build()
        val projection = arrayOf(
            Instances.EVENT_ID, Instances.TITLE, Instances.DESCRIPTION,
            Instances.BEGIN, Instances.END, Instances.ALL_DAY,
        )
        val utcDayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        val plan = mutableListOf<PlanItem>()
        var money: MoneyDay? = null
        context.contentResolver.query(uri, projection, null, null, "${Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val title = c.getString(1) ?: continue
                val desc = c.getString(2)
                val begin = c.getLong(3)
                val end = c.getLong(4)
                val allDay = c.getInt(5) == 1
                val onDay = if (allDay) begin == utcDayStart else begin < dayEnd && end > dayStart
                if (!onDay) continue
                when {
                    EventParser.isPlan(title) && !allDay ->
                        plan += EventParser.parsePlan(c.getLong(0), title, desc, begin, end)
                    EventParser.isMoney(title) ->
                        money = EventParser.parseMoney(title, desc) ?: money
                }
            }
        }
        return DaySnapshot(plan.sortedBy { it.startMillis }, money, now)
    }

    private companion object { const val DAY = 24 * 60 * 60 * 1000L }
}
