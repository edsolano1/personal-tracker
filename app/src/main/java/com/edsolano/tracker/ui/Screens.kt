package com.edsolano.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edsolano.tracker.data.Charge
import com.edsolano.tracker.data.DaySnapshot
import com.edsolano.tracker.data.MealAnswer
import com.edsolano.tracker.data.PlanItem
import com.edsolano.tracker.data.PlanType

private val pad = PaddingValues(16.dp)

@Composable
private fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun Muted(
    text: String,
    small: Boolean = false,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier,
) = Text(
    text, modifier, color = color,
    style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
)

// ---------- Today ----------

@Composable
fun TodayScreen(s: DaySnapshot, done: Set<String>, onToggle: (String) -> Unit, onDebug: () -> Unit) {
    LazyColumn(contentPadding = pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (s.isLiftDay) "Lift day" else "Rest day", style = MaterialTheme.typography.headlineSmall)
                    Muted("Target ${Format.int(s.calorieTarget)} cal · ${s.proteinTarget}g protein")
                }
                TextButton(onClick = onDebug) { Text("Raw events") }
            }
        }
        item {
            Section("Timeline") {
                if (s.plan.isEmpty()) Muted("No [Plan] items on the calendar today.")
                val next = s.upcoming(1).firstOrNull()
                s.plan.forEachIndexed { i, it ->
                    TimelineRow(it, isPast = it.endMillis <= s.nowMillis, isNext = it == next,
                        done = it.doneKey in done, onToggle = { onToggle(it.doneKey) })
                    if (i < s.plan.lastIndex) Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun TimelineRow(item: PlanItem, isPast: Boolean, isNext: Boolean, done: Boolean, onToggle: () -> Unit) {
    val alpha = if ((isPast || done) && !isNext) 0.5f else 1f
    val bg = if (isNext) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent
    Row(
        Modifier.fillMaxWidth().background(bg, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(Format.time(item.startMillis), Modifier.width(72.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = alpha), fontWeight = FontWeight.SemiBold)
        Column(Modifier.weight(1f)) {
            Text(item.name, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                fontWeight = if (isNext) FontWeight.SemiBold else FontWeight.Normal)
            val detail = detailOf(item)
            if (detail.isNotEmpty()) Muted(detail, small = true,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
        }
        Checkbox(checked = done, onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary))
    }
}

private fun detailOf(item: PlanItem) = buildList {
    when (item.type) {
        PlanType.MEAL -> add(item.slot ?: "Meal")
        PlanType.OTHER -> {}
        else -> add(item.type.name.lowercase().replaceFirstChar { it.uppercase() })
    }
    item.minutes?.takeIf { it > 0 }?.let { add("$it min") }
    if (item.calories > 0) add("${item.calories} cal")
    if (item.protein > 0) add("${item.protein}g protein")
}.joinToString(" · ")

// ---------- Food ----------

@Composable
fun FoodScreen(
    s: DaySnapshot,
    onAnswer: (PlanItem, MealAnswer) -> Unit,
    onSend: (String) -> Unit,
    sendStatus: String?,
    sent: List<String>,
) {
    LazyColumn(contentPadding = pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Section(if (s.isLiftDay) "Eaten vs target · lift day" else "Eaten vs target · rest day") {
                Bar("Calories", s.caloriesSoFar, s.calorieTarget, "", AccentGreen)
                Spacer(Modifier.height(14.dp))
                Bar("Protein", s.proteinSoFar, s.proteinTarget, "g", ProteinBlue)
                Spacer(Modifier.height(10.dp))
                Muted("Only counts meals you've said yes to.", small = true)
            }
        }
        val toAnswer = s.mealsToAnswer
        if (toAnswer.isNotEmpty()) {
            item {
                Section(if (toAnswer.size == 1) "Did you eat this?" else "Did you eat these?") {
                    toAnswer.forEachIndexed { i, m ->
                        MealPrompt(m) { onAnswer(m, it) }
                        if (i < toAnswer.lastIndex) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
        item {
            Section("Meals") {
                if (s.meals.isEmpty()) Muted("No meals on the plan today.")
                s.meals.forEach { m ->
                    val answer = s.answerFor(m)
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                m.name,
                                color = MaterialTheme.colorScheme.onSurface
                                    .copy(alpha = if (answer == MealAnswer.ATE) 1f else 0.7f),
                            )
                            Muted(
                                "${m.slot ?: "Meal"} · ${Format.time(m.startMillis)}" + when (answer) {
                                    MealAnswer.ATE -> " · eaten"
                                    MealAnswer.SKIPPED -> " · skipped"
                                    MealAnswer.UNANSWERED -> ""
                                },
                                small = true,
                            )
                        }
                        Text("${m.calories} cal · ${m.protein}g")
                        Checkbox(
                            checked = answer == MealAnswer.ATE,
                            onCheckedChange = { onAnswer(m, if (it) MealAnswer.ATE else MealAnswer.UNANSWERED) },
                            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
                        )
                    }
                    m.swaps.forEach { sw -> Muted("   or ${sw.name}: ${sw.calories} cal · ${sw.protein}g", small = true) }
                }
            }
        }
        item {
            Section("Fits what's left") {
                Muted("${Format.int(s.caloriesLeft)} cal and ${s.proteinLeft}g protein left", small = true)
                Spacer(Modifier.height(6.dp))
                val ideas = s.fitsWhatsLeft()
                if (ideas.isEmpty()) Muted("You're at your calorie target.")
                ideas.forEach {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(it.name, Modifier.weight(1f))
                        Muted("${it.calories} cal · ${it.protein}g")
                    }
                }
            }
        }
        item { MessageBox(onSend, sendStatus, sent) }
    }
}

@Composable
private fun MealPrompt(item: PlanItem, onAnswer: (MealAnswer) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(item.name, fontWeight = FontWeight.SemiBold)
        Muted(
            "${item.slot ?: "Meal"} · ${Format.time(item.startMillis)} · " +
                "${item.calories} cal · ${item.protein}g",
            small = true,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onAnswer(MealAnswer.ATE) }) { Text("Ate it") }
            TextButton(onClick = { onAnswer(MealAnswer.SKIPPED) }) { Text("Skipped") }
        }
    }
}

/** A note straight to Claude. It is written onto the calendar, which Claude reads. */
@Composable
private fun MessageBox(onSend: (String) -> Unit, status: String?, sent: List<String>) =
    Section("Message Claude") {
        var text by rememberSaveable { mutableStateOf("") }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Ate something off plan, feeling off, anything…") },
            minLines = 3,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Muted(
                status ?: "Goes onto your calendar, where Claude picks it up.",
                small = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onSend(text); text = "" }, enabled = text.isNotBlank()) { Text("Send") }
        }
        if (sent.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Muted("Sent", small = true)
            sent.forEach { Muted("· $it", small = true) }
        }
    }

@Composable
private fun Bar(label: String, value: Int, target: Int, unit: String, color: Color) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${Format.int(value)}$unit", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Muted(" / ${Format.int(target)}$unit")
    }
    Spacer(Modifier.height(6.dp))
    LinearProgressIndicator(
        progress = { (value.toFloat() / target).coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(8.dp),
        color = color, trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

// ---------- Money ----------

@Composable
fun MoneyScreen(s: DaySnapshot) {
    val m = s.money
    LazyColumn(contentPadding = pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Section("Spending left today") {
                if (m == null) Muted("No [Money] event for today or yesterday.")
                else {
                    Text(Format.money(m.spendingLeft), fontSize = 52.sp, fontWeight = FontWeight.Bold,
                        color = if (m.spendingLeft < 0) WarnRed else MaterialTheme.colorScheme.onSurface)
                    if (s.moneyIsStale) Muted("as of ${Format.date(m.date)}", small = true)
                }
            }
        }
        if (m != null) {
            item { ChargeList("Pending", m.pending) }
            item { ChargeList("Posted", m.posted) }
        }
    }
}

@Composable
private fun ChargeList(title: String, charges: List<Charge>) = Section(title) {
    if (charges.isEmpty()) Muted("None")
    charges.forEach { c ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(c.label)
                if (c.includesTip) Muted("includes 20% tip estimate", small = true)
            }
            Text(Format.money(c.amount))
        }
    }
}

// ---------- Debug ----------

@Composable
fun DebugScreen(s: DaySnapshot, onBack: () -> Unit) {
    LazyColumn(contentPadding = pad, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Raw events", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onBack) { Text("Close") }
            }
            Muted(
                if (s.calendarFound) "Reading the \"Personal Tracker\" calendar."
                else "No \"Personal Tracker\" calendar found, so reading [Plan]/[Money] events from every calendar.",
                small = true,
            )
            Muted("Today and yesterday · ${s.raw.size} events", small = true)
        }
        if (s.raw.isEmpty()) item { Muted("No [Plan] or [Money] events found.") }
        items(s.raw) { r ->
            Section(if (r.allDay) "All day · ${r.calendar}" else "${Format.dateTime(r.begin)} · ${r.calendar}") {
                Text(r.title, fontWeight = FontWeight.SemiBold)
                Text("→ ${r.parsedAs}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                if (r.description.isNotBlank()) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Text(r.description, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
