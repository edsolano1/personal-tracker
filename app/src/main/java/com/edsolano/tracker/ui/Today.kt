package com.edsolano.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edsolano.tracker.data.CalEvent
import com.edsolano.tracker.data.DaySnapshot
import com.edsolano.tracker.data.MealAnswer
import com.edsolano.tracker.data.PlanItem
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter

private val Blue = Color(0xFF5B8DEF)

private fun until(ms: Long): String {
    val min = (ms / 60000).toInt()
    return when {
        min < 1 -> "now"
        min < 60 -> "in $min min"
        min % 60 == 0 -> "in ${min / 60} hr"
        else -> "in ${min / 60} hr ${min % 60} min"
    }
}

/** Today's agenda: what's on now or next up top, then the day as a timeline. */
@Composable
fun TodayScreen(
    s: DaySnapshot,
    onToggle: (PlanItem, Boolean) -> Unit,
    onDebug: () -> Unit,
    onRefresh: () -> Unit,
    refreshing: Boolean,
    freshness: String,
    linkSet: Boolean,
    onLink: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val timed = s.events.filter { !it.allDay }
    val allDay = s.events.filter { it.allDay }
    val current = timed.firstOrNull { it.begin <= now && it.end > now }
    val next = timed.firstOrNull { it.begin > now }
    val tasks = s.plan.filter { !it.isFood }

    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(Modifier.padding(start = 4.dp)) {
                Text(s.date.format(DateTimeFormatter.ofPattern("EEEE")).uppercase(), letterSpacing = 1.5.sp,
                    style = MaterialTheme.typography.labelMedium, color = Blue)
                Text(s.date.format(DateTimeFormatter.ofPattern("MMMM d")), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                val left = timed.count { it.end > now }
                Muted(if (s.events.isEmpty()) "Nothing on your calendars today"
                      else "$left still to go · ${s.events.size} today")
            }
        }
        val hero = current ?: next
        if (hero != null) item {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
                    .background(Brush.linearGradient(listOf(Blue.copy(alpha = 0.40f), Blue.copy(alpha = 0.10f))))
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(if (hero === current) "HAPPENING NOW" else "UP NEXT", letterSpacing = 1.5.sp,
                    style = MaterialTheme.typography.labelMedium, color = Blue, fontWeight = FontWeight.Bold)
                Text(hero.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${Format.time(hero.begin)} – ${Format.time(hero.end)}  ·  " +
                        if (hero === current) "ends ${until(hero.end - now)}" else until(hero.begin - now),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (hero.calendar.contains("shared", true)) Tag("Shared")
            }
        }
        if (allDay.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("All day")
                allDay.forEach { e ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(e.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        if (e.calendar.contains("shared", true)) Tag("Shared")
                    }
                }
            }
        }
        if (timed.isNotEmpty()) item { SectionLabel("Schedule") }
        items(timed) { e -> AgendaRow(e, now, isLast = e === timed.last()) }
        if (tasks.isNotEmpty()) {
            item { SectionLabel("Plan") }
            items(tasks) { t ->
                TimelineRow(t, isPast = t.endMillis <= now, isNext = false,
                    done = s.answerFor(t) == MealAnswer.ATE, onToggle = { on -> onToggle(t, on) })
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Muted(if (refreshing) "Checking Google…" else freshness, small = true, modifier = Modifier.weight(1f))
                TextButton(onClick = onRefresh, enabled = !refreshing) { Text("Refresh") }
            }
            Row {
                TextButton(onClick = onLink) { Text(if (linkSet) "iCal link" else "Add iCal link") }
                TextButton(onClick = onDebug) { Text("Raw events") }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) = Text(
    text.uppercase(), letterSpacing = 1.5.sp, style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 6.dp),
)

@Composable
private fun Tag(text: String) = Text(
    text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Blue,
    modifier = Modifier.clip(CircleShape).background(Blue.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 2.dp),
)

/** One event on the timeline: time on the left, a dot and line, the event on the right. */
@Composable
private fun AgendaRow(e: CalEvent, now: Long, isLast: Boolean) {
    val past = e.end <= now
    val live = e.begin <= now && e.end > now
    val alpha = if (past) 0.45f else 1f
    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
        Column(Modifier.width(68.dp).padding(top = 2.dp)) {
            Text(Format.time(e.begin), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Blue.copy(alpha = alpha))
            Text(Format.time(e.end), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
        }
        Column(Modifier.width(20.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(top = 5.dp).size(if (live) 12.dp else 10.dp).clip(CircleShape)
                .background(if (past) MaterialTheme.colorScheme.outline else Blue))
            if (!isLast) Box(Modifier.padding(top = 4.dp).width(2.dp).weight(1f).background(MaterialTheme.colorScheme.outlineVariant))
        }
        Column(Modifier.weight(1f).padding(start = 8.dp, bottom = 18.dp)) {
            Text(e.title, fontWeight = if (live) FontWeight.Bold else FontWeight.SemiBold, fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Muted(when { live -> "Now · ends ${until(e.end - now)}"; past -> "Done"; else -> until(e.begin - now) }, small = true)
                if (e.calendar.contains("shared", true)) Tag("Shared")
            }
        }
    }
}
