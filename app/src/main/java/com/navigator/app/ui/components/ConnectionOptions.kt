package com.navigator.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.navigator.app.ble.BccuConnectionProfile
import com.navigator.app.ble.BccuConnectionService
import com.navigator.app.settings.AppSettings
import com.navigator.app.ui.theme.Ktm

/** Shared by discovery and Settings so the option is accessible before first authorization. */
@Composable
fun ConnectionOptionsDialog(settings: AppSettings, address: String, onDismiss: () -> Unit, onConnect: () -> Unit) {
    var profile by remember(address) { mutableStateOf(settings.connectionProfile(address)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connection options") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                Text("Saved only for this bike. Standard keeps the existing KTM connection behavior.")
                BccuConnectionProfile.entries.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().selectable(profile == option, role = Role.RadioButton, onClick = { profile = option }),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = profile == option, onClick = null)
                        Text(option.label, Modifier.padding(vertical = 8.dp))
                    }
                }
                if (profile != BccuConnectionProfile.STANDARD) {
                    Text("Field-tested on one Svartpilen 401 (2025). Close Ride Husqvarna before connecting. A fresh authorization on the dash may be needed; other bikes and firmware remain unverified.")
                }
                ConnectionProblemNotice(settings, address)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                settings.setConnectionProfile(address, profile)
                onDismiss()
                onConnect()
            }) { Text("Save and reconnect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ConnectionProblemNotice(settings: AppSettings, address: String) {
    val problem by BccuConnectionService.connectionProblem.collectAsState()
    // Collect state as well so a deliberate retry refreshes the persisted fallback.
    val state by BccuConnectionService.connectionState.collectAsState()
    val issue = remember(address, problem, state) {
        problem?.takeIf { it.address.equals(address, ignoreCase = true) }?.issue ?: settings.connectionIssue(address)
    }
    if (issue != null && settings.connectionProfile(address) != BccuConnectionProfile.STANDARD) {
        Text(issue.message, color = Ktm.Orange, modifier = Modifier.padding(vertical = 8.dp))
    }
}
