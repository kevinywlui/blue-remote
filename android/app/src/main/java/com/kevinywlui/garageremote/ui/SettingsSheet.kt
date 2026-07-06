package com.kevinywlui.garageremote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kevinywlui.garageremote.GarageViewModel
import com.kevinywlui.garageremote.PairedDevice
import com.kevinywlui.garageremote.ui.theme.AppTheme
import com.kevinywlui.garageremote.ui.theme.ThemeSpec
import com.kevinywlui.garageremote.ui.theme.themeSpec

private val LightThemes = listOf(AppTheme.PORCELAIN, AppTheme.SUNRISE, AppTheme.MINT)
private val DarkThemes = listOf(AppTheme.MIDNIGHT, AppTheme.EMBER, AppTheme.FOREST)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    currentTheme: AppTheme,
    onThemeSelected: (AppTheme) -> Unit,
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
                "Settings",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(16.dp))
            Text("Theme", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            // "Follow system" full-width on top, then a 2×3 grid (§1).
            Column(modifier = Modifier.selectableGroup()) {
                FollowSystemCard(
                    selected = currentTheme == AppTheme.FOLLOW_SYSTEM,
                    onClick = { onThemeSelected(AppTheme.FOLLOW_SYSTEM) },
                )
                Spacer(Modifier.height(12.dp))
                (LightThemes zip DarkThemes).forEach { (light, dark) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ThemeCard(
                            themeSpec(light), currentTheme == light,
                            Modifier.weight(1f),
                        ) { onThemeSelected(light) }
                        ThemeCard(
                            themeSpec(dark), currentTheme == dark,
                            Modifier.weight(1f),
                        ) { onThemeSelected(dark) }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            if (phonesAvailable) {
                Text("Phones", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                if (!connected) {
                    Text(
                        "Connect to the board to manage phones.",
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

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
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
        val displayName = target.name ?: target.address
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

/** One bond-list row: name (or address), address caption, self badge, Remove. */
@Composable
private fun PhoneRow(device: PairedDevice, removeEnabled: Boolean, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(device.name ?: device.address, style = MaterialTheme.typography.bodyLarge)
                if (device.isSelf) {
                    Spacer(Modifier.width(8.dp))
                    ThisPhoneBadge()
                }
            }
            if (device.name != null) {
                Text(
                    device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

// Mid-tone that holds ≥3:1 against BOTH halves of the split swatch — the
// active theme's onSurface is invisible against the opposite-darkness half.
private val SplitCardIndicator = Color(0xFF72777F)

@Composable
private fun SelectedBorder(selected: Boolean, selectedColor: Color? = null): Modifier {
    // Selected indicator: ≥3:1 against the card surface it outlines (§1).
    return if (selected) {
        Modifier.border(
            3.dp, selectedColor ?: MaterialTheme.colorScheme.onSurface,
            RoundedCornerShape(16.dp),
        )
    } else {
        Modifier.border(
            1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp),
        )
    }
}

@Composable
private fun FollowSystemCard(selected: Boolean, onClick: () -> Unit) {
    val porcelain = themeSpec(AppTheme.PORCELAIN)
    val midnight = themeSpec(AppTheme.MIDNIGHT)
    Surface(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(SelectedBorder(selected, selectedColor = SplitCardIndicator))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) {
                // selectable() already reports selection state — a manual
                // ", selected" suffix would announce it twice.
                contentDescription =
                    "Follow system, automatic light and dark, switches with your phone's dark mode"
            },
    ) {
        Row(Modifier.height(84.dp)) {
            // Split swatch rendered from real tokens: half Porcelain, half Midnight (§1).
            SwatchHalf(
                porcelain, Modifier.weight(1f),
                label = "Follow system",
                // Visible at the moment of choice (§1, Decision 1).
                caption = "Switches with your phone's dark mode",
            )
            SwatchHalf(midnight, Modifier.weight(1f), label = null, badge = "Auto", check = selected)
        }
    }
}

@Composable
private fun SwatchHalf(
    spec: ThemeSpec,
    modifier: Modifier,
    label: String?,
    caption: String? = null,
    badge: String? = null,
    check: Boolean = false,
) {
    Box(modifier = modifier.background(spec.scheme.surface).height(84.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp),
        ) {
            Dot(spec.scheme.primary)
            Spacer(Modifier.width(6.dp))
            Dot(spec.scheme.secondary)
            if (label != null) {
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(label, color = spec.scheme.onSurface, style = MaterialTheme.typography.bodyLarge)
                    if (caption != null) {
                        Text(
                            caption,
                            color = spec.scheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        if (badge != null) {
            Badge(badge, spec, Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        if (check) {
            Icon(
                Icons.Filled.Check, contentDescription = null,
                tint = spec.scheme.onSurface,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(18.dp),
            )
        }
    }
}

@Composable
private fun ThemeCard(
    spec: ThemeSpec,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val badge = if (spec.isDark) "Dark" else "Light"
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = spec.scheme.surface, // swatch = the theme's actual tokens (§1)
        modifier = modifier
            .then(SelectedBorder(selected))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "${spec.theme.displayName}, $badge theme"
            },
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(spec.scheme.primary)
                Spacer(Modifier.width(6.dp))
                Dot(spec.scheme.secondary)
                Spacer(Modifier.weight(1f))
                if (selected) {
                    Icon(
                        Icons.Filled.Check, contentDescription = null,
                        tint = spec.scheme.onSurface, modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                spec.theme.displayName,
                color = spec.scheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(4.dp))
            Badge(badge, spec, Modifier)
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(16.dp).background(color, CircleShape))
}

@Composable
private fun Badge(text: String, spec: ThemeSpec, modifier: Modifier) {
    Surface(
        color = spec.scheme.surfaceVariant,
        contentColor = spec.scheme.onSurfaceVariant,
        shape = RoundedCornerShape(8.dp),
        // The tonal chip itself is in the 3:1 non-text check (§5); an
        // outline border carries it on dark surfaces.
        border = BorderStroke(1.dp, spec.scheme.outline),
        modifier = modifier,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
