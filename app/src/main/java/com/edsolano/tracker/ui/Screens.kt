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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edsolano.tracker.data.DaySnapshot
import com.edsolano.tracker.data.PlanItem

private val pad = PaddingValues(16.dp)

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
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
fun EmptyNote(text: String) = Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun TodayScreen(s: DaySnapshot) {
    LazyColumn(contentPadding = pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(if (s.isLiftDay) "Lift day" else "Rest day", style = MaterialTheme.typography.headlineSmall)
            Text("Target ${Format.int(s.calorieTarget)} cal · ${s.proteinTarget}g protein",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (s.plan.isEmpty()) item { Section("Timeline") { EmptyNote("No [Plan] items on the calendar today.") } }
        else item {
            Section("Timeline") {
                val next = s.upcoming(1).firstOrNull()
                s.plan.forEachIndexed { i, it ->
                    TimelineRow(it, isPast = it.endMillis <= s.nowMillis, isNext = it == next)
                    if (i < s.plan.lastIndex) Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun TimelineRow(item: PlanItem, isPast: Boolean, isNext: Boolean) {
    val dim = if (isPast) 0.45f else 1f
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(
            if (isNext) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, CircleShape))
        Spacer(Modifier.width(12.dp))
        Text(Format.time(item.startMillis), Modifier.width(76.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = dim), fontWeight = FontWeight.SemiBold)
        Column(Modifier.weight(1f)) {
            Text(item.name, color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim))
            val detail = buildList {
                if (item.isWorkout) add("Workout")
                if (item.calories > 0) add("${item.calories} cal")
                if (item.protein > 0) add("${item.protein}g protein")
            }.joinToString(" · ")
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim))
        }
    }
}

@Composable
fun FoodScreen(s: DaySnapshot) {
    LazyColumn(contentPadding = pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Section("Calories") {
                Totals(s.caloriesSoFar, s.caloriesPlanned, s.calorieTarget, "", AccentGreen)
                Text(if (s.isLiftDay) "Lift day target" else "Rest day target",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { Section("Protein") { Totals(s.proteinSoFar, s.proteinPlanned, s.proteinTarget, "g", ProteinBlue) } }
        val food = s.plan.filter { it.calories > 0 || it.protein > 0 }
        item {
            Section("Items") {
                if (food.isEmpty()) EmptyNote("No food plan items today.")
                food.forEach {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(it.name, Modifier.weight(1f))
                        Text("${it.calories} cal · ${it.protein}g", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Totals(soFar: Int, planned: Int, target: Int, unit: String, color: Color) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text("${Format.int(soFar)}$unit", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("  / ${Format.int(target)}$unit", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(8.dp))
    LinearProgressIndicator(
        progress = { (soFar.toFloat() / target).coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(6.dp),
        color = color,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    val gap = target - planned
    Text(
        "Planned ${Format.int(planned)}$unit · " +
            if (gap > 0) "${Format.int(gap)}$unit short of target" else "target covered",
        style = MaterialTheme.typography.bodySmall,
        color = if (gap > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun MoneyScreen(s: DaySnapshot) {
    LazyColumn(contentPadding = pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val m = s.money
        item {
            Section("Spending left today") {
                if (m == null) EmptyNote("No [Money] event on the calendar today.")
                else {
                    Text(Format.money(m.adjustedLeft), fontSize = 48.sp, fontWeight = FontWeight.Bold,
                        color = if (m.adjustedLeft < 0) WarnRed else MaterialTheme.colorScheme.onSurface)
                    if (m.tipEstimates > 0) Text(
                        "${Format.money(m.spendingLeft)} minus ${Format.money(m.tipEstimates)} estimated tips",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Unspent money carries over to tomorrow.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (m != null && m.charges.isNotEmpty()) item {
            Section("Charges") {
                m.charges.forEach { c ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(c.label)
                            val tags = buildList {
                                add(if (c.isPending) "Pending" else "Posted")
                                if (c.tipEstimate > 0) add("+${Format.money(c.tipEstimate)} tip est.")
                            }.joinToString(" · ")
                            Text(tags, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(Format.money(c.amount + c.tipEstimate))
                    }
                }
            }
        }
    }
}
