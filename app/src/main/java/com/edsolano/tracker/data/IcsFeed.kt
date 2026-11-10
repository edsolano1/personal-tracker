package com.edsolano.tracker.data

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Reads the "Personal Tracker" calendar straight from Google through its secret iCal
 * address, so the app no longer waits on the phone's calendar sync. On this phone that
 * sync runs about once a day and ignores every request to run sooner, even from adb.
 *
 * The address is a password in all but name: it lives only in this app's private
 * preferences, typed in on the phone, and is never logged or written anywhere else.
 */
class IcsFeed(private val context: Context) {
    private val prefs = context.getSharedPreferences("ics-feed", Context.MODE_PRIVATE)
    private val cache get() = File(context.filesDir, "feed.ics")

    var url: String?
        get() = prefs.getString("url", null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().putString("url", value?.trim()?.takeIf { it.isNotBlank() })
                .remove("fetched").remove("error").apply()
            cache.delete()
        }

    /** When the feed was last downloaded, or 0. */
    val fetchedAt get() = prefs.getLong("fetched", 0L)
    val lastError get() = prefs.getString("error", null)

    /** Downloads the feed. Call off the main thread. True when fresh data was saved. */
    fun fetch(): Boolean {
        val address = url ?: return false
        val result = runCatching {
            val conn = (URL(address.replaceFirst("webcal://", "https://")).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Cache-Control", "no-cache")
            }
            try {
                if (conn.responseCode != 200) error("Google answered ${conn.responseCode}")
                val text = conn.inputStream.bufferedReader().readText()
                if (!text.contains("BEGIN:VCALENDAR")) error("That link didn't return a calendar")
                val tmp = File(context.filesDir, "feed.ics.tmp")
                tmp.writeText(text)
                tmp.renameTo(cache) || run { cache.writeText(text); true }
            } finally {
                conn.disconnect()
            }
        }
        prefs.edit().apply {
            if (result.isSuccess) putLong("fetched", System.currentTimeMillis()).remove("error")
            else putString("error", result.exceptionOrNull()?.message ?: "Couldn't reach Google")
        }.apply()
        return result.isSuccess
    }

    /** The events from the last download, or null when there is no usable copy. */
    fun events(): List<IcsEvent>? {
        if (url == null) return null
        val text = runCatching { cache.readText() }.getOrNull() ?: return null
        return Ics.parse(text)
    }
}

data class IcsEvent(
    /** Google's own event id: the UID minus "@google.com". */
    val googleId: String,
    val title: String,
    val description: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
)

/** A small iCalendar reader: enough for the events Claude writes, nothing more. */
internal object Ics {
    fun parse(text: String, zone: ZoneId = ZoneId.systemDefault()): List<IcsEvent> {
        val lines = unfold(text)
        val out = mutableListOf<IcsEvent>()
        var cur: MutableMap<String, Pair<String, String>>? = null
        for (line in lines) {
            when {
                line == "BEGIN:VEVENT" -> cur = mutableMapOf()
                line == "END:VEVENT" -> {
                    cur?.let { toEvent(it, zone) }?.let { out += it }
                    cur = null
                }
                cur != null -> {
                    val colon = colonIndex(line)
                    if (colon <= 0) continue
                    val head = line.substring(0, colon)
                    val name = head.substringBefore(';').uppercase()
                    val params = head.substringAfter(';', "")
                    if (name !in cur) cur[name] = params to line.substring(colon + 1)
                }
            }
        }
        return out
    }

    /** RFC 5545 folds long lines; a line starting with a space or tab continues the last. */
    private fun unfold(text: String): List<String> {
        val out = mutableListOf<StringBuilder>()
        for (raw in text.split("\r\n", "\n")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && out.isNotEmpty()) out.last().append(raw, 1, raw.length)
            else out += StringBuilder(raw)
        }
        return out.map { it.toString() }
    }

    /** The first colon outside a quoted parameter value. */
    private fun colonIndex(line: String): Int {
        var quoted = false
        line.forEachIndexed { i, c ->
            if (c == '"') quoted = !quoted
            else if (c == ':' && !quoted) return i
        }
        return -1
    }

    private fun toEvent(p: Map<String, Pair<String, String>>, zone: ZoneId): IcsEvent? {
        if (p["STATUS"]?.second.equals("CANCELLED", ignoreCase = true)) return null
        val start = p["DTSTART"] ?: return null
        val (begin, allDay) = time(start.first, start.second, zone) ?: return null
        val end = p["DTEND"]?.let { time(it.first, it.second, zone)?.first }
            ?: if (allDay) begin + 86_400_000L else begin
        val uid = p["UID"]?.second.orEmpty()
        return IcsEvent(
            googleId = uid.substringBefore("@google.com"),
            title = unescape(p["SUMMARY"]?.second.orEmpty()),
            description = unescape(p["DESCRIPTION"]?.second.orEmpty()),
            begin = begin, end = end, allDay = allDay,
        )
    }

    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val day = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** Millis and whether it is a whole-day value. All-day dates sit at UTC midnight, like the provider. */
    fun time(params: String, value: String, zone: ZoneId): Pair<Long, Boolean>? = runCatching {
        val v = value.trim()
        when {
            params.contains("VALUE=DATE", ignoreCase = true) && !params.contains("DATE-TIME", ignoreCase = true) ||
                v.length == 8 ->
                LocalDate.parse(v, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() to true
            v.endsWith("Z") ->
                LocalDateTime.parse(v.dropLast(1), stamp).toInstant(ZoneOffset.UTC).toEpochMilli() to false
            else -> {
                val tz = Regex("TZID=\"?([^;\"]+)\"?", RegexOption.IGNORE_CASE).find(params)?.groupValues?.get(1)
                val z = tz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: zone
                LocalDateTime.parse(v, stamp).atZone(z).toInstant().toEpochMilli() to false
            }
        }
    }.getOrNull()

    fun unescape(s: String): String {
        val b = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n', 'N' -> b.append('\n')
                    else -> b.append(n)
                }
                i += 2
            } else { b.append(c); i++ }
        }
        return b.toString()
    }
}
