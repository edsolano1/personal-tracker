package com.edsolano.tracker.ui

import android.content.Context
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edsolano.tracker.data.DaySnapshot
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val MoneyColor = Color(0xFF3FB97A)
private val FoodColor = Color(0xFFF08A3C)
private val TodayColor = Color(0xFF5B8DEF)

/** Section ids, matching MainActivity's tabs. */
const val HOME = -1
const val TODAY = 0
const val FOOD = 1
const val MONEY = 2

/**
 * Each section opens its own way: Money zooms up out of its card, Food springs up from
 * the bottom, Today slides in from the side. Going home plays it backwards.
 */
fun AnimatedContentTransitionScope<Int>.sectionTransition(): ContentTransform {
    val opening = targetState != HOME
    val enter = scaleIn(spring(0.62f, Spring.StiffnessMediumLow), initialScale = 0.6f) + fadeIn(tween(220))
    val exit = scaleOut(tween(220), targetScale = 0.6f) + fadeOut(tween(180))
    val behind = scaleIn(tween(300), initialScale = 1.08f) + fadeIn(tween(300))
    val away = scaleOut(tween(250), targetScale = 1.08f) + fadeOut(tween(200))
    return if (opening) enter togetherWith away else behind togetherWith exit
}

private data class WalletGlance(val big: String, val eyebrow: String, val pill: String)

private fun readWallet(context: Context): WalletGlance {
    val p = context.getSharedPreferences("wallet", Context.MODE_PRIVATE)
    return WalletGlance(p.getString("big", "") ?: "", p.getString("eyebrow", "") ?: "", p.getString("pill", "") ?: "")
}

@Composable
fun HomeScreen(snapshot: DaySnapshot, open: (Int) -> Unit) {
    val context = LocalContext.current
    var wallet by remember { mutableStateOf(readWallet(context)) }
    LaunchedEffect(Unit) { while (true) { wallet = readWallet(context); delay(2000) } }

    val hour = LocalTime.now().hour
    val hello = when { hour < 12 -> "Good morning"; hour < 17 -> "Good afternoon"; else -> "Good evening" }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Entrance(0) {
            Column(Modifier.padding(start = 4.dp, bottom = 6.dp)) {
                Text(snapshot.date.format(DateTimeFormatter.ofPattern("EEEE, MMM d")).uppercase(),
                    style = MaterialTheme.typography.labelMedium, letterSpacing = 1.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("$hello, Eddie", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
        }
        Entrance(1) { MoneyCard(wallet) { open(MONEY) } }
        Entrance(2) { FoodCard(snapshot) { open(FOOD) } }
        Entrance(3) { TodayCard(snapshot) { open(TODAY) } }
    }
}

/** Set once the cards have risen in, so coming back home shows them already in place. */
private var introPlayed = false

/** Cards rise into place one after another, only the first time home shows after launch. */
@Composable
private fun Entrance(index: Int, content: @Composable () -> Unit) {
    val t = remember { Animatable(if (introPlayed) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (t.value < 1f) { delay(70L * index); t.animateTo(1f, spring(0.7f, Spring.StiffnessLow)) }
        introPlayed = true
    }
    Box(Modifier.graphicsLayer { alpha = t.value.coerceIn(0f, 1f); translationY = (1 - t.value) * 120f }) { content() }
}

@Composable
private fun GlanceCard(color: Color, icon: ImageVector, label: String, onClick: () -> Unit, body: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(0.5f, Spring.StiffnessMedium), label = "press")
    Column(
        Modifier.fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(color.copy(alpha = 0.32f), color.copy(alpha = 0.08f))))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(color.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("›", fontSize = 26.sp, color = color)
        }
        body()
    }
}

@Composable
private fun MoneyCard(w: WalletGlance, onClick: () -> Unit) {
    val out = w.eyebrow.startsWith("Out")
    val tone = if (out) Color(0xFFF07A70) else MoneyColor
    GlanceCard(MoneyColor, Icons.Filled.ShoppingCart, "Money", onClick) {
        Text(if (w.big.isBlank()) "$—" else w.big, fontSize = 64.sp, fontWeight = FontWeight.Black, color = tone, lineHeight = 64.sp)
        Text(
            when { w.big.isBlank() -> "Open to load your wallet"; w.pill.isNotBlank() -> w.pill; else -> w.eyebrow.ifBlank { "Left until payday" } },
            color = if (out) tone else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp,
        )
    }
}

@Composable
private fun Meter(label: String, value: Int, target: Int, unit: String, color: Color) {
    val frac by animateFloatAsState((value.toFloat() / target.coerceAtLeast(1)).coerceIn(0f, 1f), tween(900), label = "meter")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text("%,d".format(value), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(" / %,d$unit".format(target), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        }
        Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(color.copy(alpha = 0.15f))) {
            Box(Modifier.fillMaxWidth(frac).height(10.dp).clip(CircleShape).background(color))
        }
    }
}

@Composable
private fun FoodCard(s: DaySnapshot, onClick: () -> Unit) {
    GlanceCard(FoodColor, Icons.Filled.Favorite, "Food", onClick) {
        Meter("Calories", s.caloriesSoFar, s.calorieTarget, "", FoodColor)
        Meter("Protein", s.proteinSoFar, s.proteinTarget, "g", Color(0xFFE85D75))
    }
}

@Composable
private fun TodayCard(s: DaySnapshot, onClick: () -> Unit) {
    val now = System.currentTimeMillis()
    val next = s.events.filter { it.allDay || it.end > now }.take(4)
    val fmt = DateTimeFormatter.ofPattern("h:mm a")
    GlanceCard(TodayColor, Icons.Filled.DateRange, "Today", onClick) {
        if (next.isEmpty()) Text("Nothing else on the calendar today", color = MaterialTheme.colorScheme.onSurfaceVariant)
        next.forEach { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (e.allDay) "All day" else Instant.ofEpochMilli(e.begin).atZone(ZoneId.systemDefault()).format(fmt),
                    color = TodayColor, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.width(76.dp),
                )
                Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, modifier = Modifier.weight(1f))
                if (e.calendar.contains("shared", ignoreCase = true))
                    Text("Shared", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}
