package com.kevinywlui.garageremote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kevinywlui.garageremote.GarageViewModel
import com.kevinywlui.garageremote.PairedDevice

/**
 * "This remote": the board-scoped surface reached by tapping the connected
 * status chip. Holds the paired-phone roster, the pairing-window action, and
 * the pairing explainer — everything about the physical board, kept out of
 * the app-preferences (theme) Settings sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteSheet(
    phonesAvailable: Boolean,
    connected: Boolean,
    pairedDevices: List<PairedDevice>,
    onPairNewPhone: () -> Unit,
    onUnpair: (PairedDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmTarget by remember { mutableStateOf<PairedDevice?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                "This remote",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(16.dp))

            if (phonesAvailable) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Paired phones", style = MaterialTheme.typography.titleMedium)
                    // Capacity is visible before the "Pair another phone"
                    // button disables at the limit.
                    if (connected) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${pairedDevices.size} of ${GarageViewModel.MAX_BONDS}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (!connected) {
                    Text(
                        "Connect to the board to see and manage paired phones.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                pairedDevices.forEach { device ->
                    PhoneRow(
                        device = device,
                        removeEnabled = connected,
                        onRemove = { confirmTarget = device },
                    )
                }
                Spacer(Modifier.height(8.dp))
                // The board refuses to open the window at capacity; don't
                // offer an action that would falsely announce success.
                val listFull = pairedDevices.size >= GarageViewModel.MAX_BONDS
                OutlinedButton(
                    onClick = onPairNewPhone,
                    enabled = connected && !listFull,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Pair another phone")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (listFull) {
                        "The board holds up to ${GarageViewModel.MAX_BONDS} phones — " +
                            "remove one before pairing another."
                    } else {
                        "Opens a 30-second window during which one new phone can pair " +
                            "— the same window a short press of the board's BOOT button opens."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider(Modifier.padding(vertical = 16.dp))
            }

            Text("How pairing works", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "The board pairs with up to 3 phones. A fresh board pairs " +
                    "with the first phone that connects; after that, new phones " +
                    "are accepted only during a 30-second pairing window — " +
                    "short-press the board's BOOT button (the LED blinks slowly " +
                    "while the window is open) or use \"Pair another phone\" above " +
                    "from a connected phone. The window closes after one new phone " +
                    "pairs, or after 30 seconds.\n\n" +
                    "Each pairing is remembered on both ends, so the app reconnects " +
                    "automatically — no PIN or password involved. Any paired phone " +
                    "can remove any other from the list above.\n\n" +
                    "The very first pairing of a fresh board has no protection " +
                    "against an active interceptor, so do it at home. To start " +
                    "over, factory-reset the board: hold its BOOT button ~3 seconds " +
                    "while it's powered (the RST button next to it only reboots) — " +
                    "this removes ALL paired phones.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    confirmTarget?.let { target ->
        val displayName = target.name ?: "this phone"
        val lastPhone = pairedDevices.size == 1
        AlertDialog(
            onDismissRequest = { confirmTarget = null }, // back/outside-tap = Cancel
            title = { Text(if (target.isSelf) "Unpair this phone?" else "Remove $displayName?") },
            text = {
                val base = if (target.isSelf) {
                    "This phone will lose access to the garage door. To pair it " +
                        "again you'll need another paired phone to open the pairing " +
                        "window, or a factory reset of the board. Afterwards, also " +
                        "remove \"GarageRemote\" from this phone's Bluetooth settings."
                } else {
                    "Remove access for $displayName? It can be paired again later."
                }
                val lastNote = if (lastPhone) {
                    "\n\nThis is the last paired phone — the next phone to connect " +
                        "will become the new owner."
                } else {
                    ""
                }
                Text(base + lastNote)
            },
            // Cancel is the filled, end-position action; the destructive verb
            // is a de-emphasized text button (same pattern as the old PIN
            // replace dialog).
            confirmButton = {
                Button(onClick = { confirmTarget = null }) { Text("Cancel") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        confirmTarget = null
                        onUnpair(target)
                    },
                ) {
                    Text(if (target.isSelf) "Unpair" else "Remove")
                }
            },
        )
    }
}

/** One bond-list row: name (or "Unnamed phone"), address caption, self badge, Remove. */
@Composable
private fun PhoneRow(device: PairedDevice, removeEnabled: Boolean, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A friendly name leads; a null name shows "Unnamed phone"
                // rather than a bare, alarming MAC address as the title.
                Text(
                    device.name ?: "Unnamed phone",
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (device.isSelf) {
                    Spacer(Modifier.width(8.dp))
                    ThisPhoneBadge()
                }
            }
            // The address is demoted to a caption — present for disambiguating
            // identical names, but never the primary label.
            Text(
                device.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onRemove,
            enabled = removeEnabled,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Text("Remove")
        }
    }
}

@Composable
private fun ThisPhoneBadge() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(
            "This phone",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
