package com.edsolano.tracker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Where he pastes the calendar's secret iCal address. */
@Composable
fun FeedLinkDialog(isSet: Boolean, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val looksRight = text.trim().let {
        (it.startsWith("https://") || it.startsWith("webcal://")) && it.contains(".ics")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Read straight from Google") },
        text = {
            Column {
                Muted(
                    "In Google Calendar on a computer: Settings → Personal Tracker → " +
                        "\"Secret address in iCal format\". Copy it and paste it here. " +
                        "It stays on this phone. Anyone with it can read that calendar.",
                    small = true,
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text(if (isSet) "New link (one is saved)" else "Secret iCal link") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                if (text.isNotBlank() && !looksRight) {
                    Muted("That doesn't look like an iCal link (https://…/basic.ics).", small = true)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }, enabled = looksRight) { Text("Save") }
        },
        dismissButton = {
            Column {
                if (isSet) TextButton(onClick = { onSave(null) }) { Text("Remove link") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
