package com.edsolano.tracker.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.edsolano.tracker.MainActivity
import com.edsolano.tracker.data.CalendarRepository
import com.edsolano.tracker.data.DaySnapshot
import com.edsolano.tracker.ui.Format

private val Bg = Color(0xFF15171C)
private val Fg = Color(0xFFECEDEF)
private val Muted = Color(0xFF9AA0A8)
private val Accent = Color(0xFF7CC4A4)
private val Protein = Color(0xFF8AB4F8)
private val Warn = Color(0xFFF28B82)

private fun style(size: Int, color: Color = Fg, bold: Boolean = false) = TextStyle(
    color = ColorProvider(color),
    fontSize = size.sp,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
)

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        GlanceModifier.fillMaxSize().background(Bg).cornerRadius(20.dp).padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) { content() }
}

@Composable
private fun TodayContent(s: DaySnapshot, maxItems: Int) {
    Text(if (s.isLiftDay) "Today · Lift day" else "Today · Rest day", style = style(12, Muted))
    Spacer(GlanceModifier.height(4.dp))
    val items = s.upcoming(maxItems)
    if (items.isEmpty()) Text("Nothing left on the plan", style = style(13, Muted))
    items.forEach { item ->
        Row(GlanceModifier.fillMaxWidth().padding(vertical = 1.dp)) {
            Text(Format.time(item.startMillis), style = style(13, Accent, bold = true),
                modifier = GlanceModifier.width(64.dp))
            Text(item.name, style = style(13), maxLines = 1)
        }
    }
    Spacer(GlanceModifier.height(6.dp))
    Macro("Cal", s.caloriesSoFar, s.calorieTarget, Accent)
    Macro("Protein", s.proteinSoFar, s.proteinTarget, Protein, unit = "g")
}

@Composable
private fun Macro(label: String, value: Int, target: Int, color: Color, unit: String = "") {
    Text("$label  ${Format.int(value)}$unit / ${Format.int(target)}$unit", style = style(12, Muted))
    LinearProgressIndicator(
        progress = (value.toFloat() / target).coerceIn(0f, 1f),
        modifier = GlanceModifier.fillMaxWidth().height(4.dp),
        color = ColorProvider(color),
        backgroundColor = ColorProvider(Color(0xFF2A2E35)),
    )
    Spacer(GlanceModifier.height(3.dp))
}

@Composable
private fun MoneyContent(s: DaySnapshot, big: Int) {
    Text("Spending left today", style = style(12, Muted))
    val m = s.money
    if (m == null) {
        Text("—", style = style(big, Muted, bold = true))
        Text("No [Money] event today", style = style(11, Muted))
    } else {
        Text(Format.money(m.adjustedLeft), style = style(big, if (m.adjustedLeft < 0) Warn else Fg, bold = true))
        if (m.tipEstimates > 0) Text("incl. ${Format.money(m.tipEstimates)} tip est.", style = style(11, Muted))
    }
}

class TodayWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val s = CalendarRepository(context).load()
        provideContent { Card { TodayContent(s, 4) } }
    }
}

class MoneyWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val s = CalendarRepository(context).load()
        provideContent {
            Column(
                GlanceModifier.fillMaxSize().background(Bg).cornerRadius(20.dp).padding(12.dp)
                    .clickable(actionStartActivity<MainActivity>()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { MoneyContent(s, 40) }
        }
    }
}

class ComboWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val s = CalendarRepository(context).load()
        provideContent {
            Card {
                Row(GlanceModifier.fillMaxSize()) {
                    Column(GlanceModifier.defaultWeight()) { TodayContent(s, 4) }
                    Spacer(GlanceModifier.width(10.dp))
                    Column(
                        GlanceModifier.width(110.dp).fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) { MoneyContent(s, 30) }
                }
            }
        }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = TodayWidget() }
class MoneyWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = MoneyWidget() }
class ComboWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = ComboWidget() }

object WidgetUpdater {
    suspend fun updateAll(context: Context) {
        TodayWidget().updateAll(context)
        MoneyWidget().updateAll(context)
        ComboWidget().updateAll(context)
    }
}
