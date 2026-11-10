package com.edsolano.tracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class IcsFeedTest {
    private val phoenix = ZoneId.of("America/Phoenix")

    // Shaped like Google's private feed: CRLF, folded lines, escaped commas and newlines.
    private val feed = listOf(
        "BEGIN:VCALENDAR",
        "X-WR-CALNAME:Personal Tracker",
        "BEGIN:VEVENT",
        "DTSTART:20261110T150000Z",
        "DTEND:20261110T153000Z",
        "UID:abc123@google.com",
        "SUMMARY:[Plan] Huel Black",
        "DESCRIPTION:type: meal\\nslot: Breakfast\\ncal: 400\\nprotein: 40\\ndetail: Shake",
        "  it\\, then drink",
        "END:VEVENT",
        "BEGIN:VEVENT",
        "DTSTART;TZID=America/Phoenix:20261110T193000",
        "DTEND;TZID=America/Phoenix:20261110T200000",
        "UID:def456@google.com",
        "SUMMARY:[Plan] Dinner",
        "END:VEVENT",
        "BEGIN:VEVENT",
        "DTSTART;VALUE=DATE:20261110",
        "DTEND;VALUE=DATE:20261111",
        "UID:money1@google.com",
        "SUMMARY:[Money] Spending left: \$70.66",
        "END:VEVENT",
        "BEGIN:VEVENT",
        "DTSTART:20261110T200000Z",
        "UID:gone@google.com",
        "STATUS:CANCELLED",
        "SUMMARY:[Plan] Old",
        "END:VEVENT",
        "END:VCALENDAR",
    ).joinToString("\r\n")

    @Test fun readsGooglesFeed() {
        val events = Ics.parse(feed, phoenix)
        assertEquals(3, events.size)
        val huel = events[0]
        assertEquals("abc123", huel.googleId)
        assertEquals("[Plan] Huel Black", huel.title)
        assertTrue(huel.description.endsWith("detail: Shake it, then drink"))
        assertEquals(1794322800000L, huel.begin) // 8:00 AM Phoenix
        assertEquals(1794364200000L, events[1].begin) // 7:30 PM Phoenix, from TZID
        assertTrue(events[2].allDay)
    }

    @Test fun parsedPlanReadsTheNumbers() {
        val e = Ics.parse(feed, phoenix)[0]
        val p = EventParser.parsePlan(1, e.title, e.description, e.begin, e.end)
        assertEquals(400, p.calories)
        assertEquals(40, p.protein)
    }

    private fun row(id: Long, title: String, begin: Long = 1794236400000L, cal: String = Targets.CALENDAR_NAME) =
        Row(id, cal, title, "", begin, begin + 1, false)

    @Test fun googleWinsUnlessThePhoneHasUnsentChanges() {
        val events = Ics.parse(feed, phoenix)
        val device = listOf(
            row(10, "[Plan] Stale old name"),               // synced, renamed on Google since
            row(11, "[Plan] Dinner, ticked on phone", 1794277800000L), // synced, dirty
            row(12, "[Plan] Deleted on Google"),            // synced, not in feed any more
            row(13, "[Plan] Added on the phone"),           // never uploaded
            row(14, "[Plan] Concert", cal = "Other"),       // another calendar
        )
        val sync = mapOf(
            10L to SyncInfo("abc123", false), 11L to SyncInfo("def456", true),
            12L to SyncInfo("zzz", false), 13L to SyncInfo(null, true),
        )
        val out = FeedMerge.merge(device, sync, events, 0, Long.MAX_VALUE).map { it.eventId to it.title }
        assertTrue(10L to "[Plan] Huel Black" in out)            // Google's text, phone's id
        assertTrue(11L to "[Plan] Dinner, ticked on phone" in out)
        assertTrue(out.none { it.first == 12L })
        assertTrue(13L to "[Plan] Added on the phone" in out)
        assertTrue(14L to "[Plan] Concert" in out)
        assertTrue(out.any { it.second.startsWith("[Money]") && it.first < 0 })
        assertEquals(5, out.size)
    }
}
