package com.kevinywlui.garageremote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kevinywlui.garageremote.PinStore

/**
 * First launch (no PIN yet): Save-only, back exits the app normally.
 * Change PIN: cancellable via button and system back; a Cancel-default
 * confirmation dialog guards Save (plan §2).
 */
@Composable
fun PinEntryScreen(
    pinAlreadySet: Boolean,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
) {
    // Saveable: the follow-system default makes uiMode recreation routine —
    // it must not eject the user mid-entry or dismiss the confirm dialog.
    var pin by rememberSaveable { mutableStateOf("") }
    var showConfirm by rememberSaveable { mutableStateOf(false) }
    val tooShort = pin.isNotEmpty() && pin.length < PinStore.MIN_PIN_LENGTH

    if (pinAlreadySet) {
        BackHandler { onCancel() } // never touches the stored secret
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Edge-to-edge: keep the field and buttons above the number pad.
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (pinAlreadySet) "Change PIN" else "Choose a PIN for the garage",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))

        if (pinAlreadySet) {
            // Warning above the field in reading order (§2).
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "A board provisioned with your current PIN must be factory-reset " +
                            "(hold BOOT ~3s) before a new PIN works — and the old pairing " +
                            "removed in your phone's Bluetooth settings.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        } else {
            Text(
                "The first PIN used with a fresh board becomes its key. " +
                    "Use 6+ digits you don't use anywhere else.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
        }

        OutlinedTextField(
            value = pin,
            onValueChange = { new -> pin = new.filter { it.isDigit() }.take(12) },
            label = { Text("PIN (${PinStore.MIN_PIN_LENGTH}+ digits)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            isError = tooShort,
            supportingText = {
                if (tooShort) {
                    Text(
                        "At least ${PinStore.MIN_PIN_LENGTH} digits",
                        modifier = Modifier.semantics {
                            error("PIN too short — at least ${PinStore.MIN_PIN_LENGTH} digits")
                        },
                    )
                }
            },
        )
        Spacer(Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (pinAlreadySet) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().weight(1f)) {
                    Text("Cancel")
                }
            }
            Button(
                onClick = { if (pinAlreadySet) showConfirm = true else onSave(pin) },
                enabled = pin.length >= PinStore.MIN_PIN_LENGTH,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                Text("Save PIN")
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false }, // back/outside-tap = Cancel
            title = { Text("Replace the PIN?") },
            text = {
                Text(
                    "If the board was already set up with your current PIN, it will stop " +
                        "responding until you factory-reset it (hold BOOT ~3s). The old PIN " +
                        "cannot be recovered. Afterwards, also remove the old pairing in " +
                        "your phone's Bluetooth settings.",
                )
            },
            // Cancel is the filled, end-position action; the destructive verb
            // is a de-emphasized text button (§2).
            confirmButton = {
                Button(onClick = { showConfirm = false }) { Text("Cancel") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false; onSave(pin) }) {
                    Text("Replace PIN")
                }
            },
        )
    }
}
