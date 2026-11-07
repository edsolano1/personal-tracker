package com.edsolano.tracker.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AccentGreen = Color(0xFF7CC4A4)
val ProteinBlue = Color(0xFF8AB4F8)
val WarnRed = Color(0xFFF28B82)

private val Dark = darkColorScheme(
    primary = AccentGreen,
    secondary = ProteinBlue,
    error = WarnRed,
    background = Color(0xFF0F1115),
    surface = Color(0xFF15171C),
    surfaceVariant = Color(0xFF1E2128),
    onBackground = Color(0xFFECEDEF),
    onSurface = Color(0xFFECEDEF),
    onSurfaceVariant = Color(0xFF9AA0A8),
)

@Composable
fun TrackerTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Dark, content = content)
