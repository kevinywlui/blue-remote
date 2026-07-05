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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
    onDismiss: () -> Unit,
) {
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

            Text("How pairing works", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "The board pairs with exactly one phone — the first one to " +
                    "connect owns it, permanently. Do the first connection at home " +
                    "with the board freshly powered; that first pairing has no " +
                    "protection against an active interceptor, but once it's made, " +
                    "no other phone can ever pair.\n\n" +
                    "The pairing is remembered on both ends, so the app reconnects " +
                    "automatically — no PIN or password involved.\n\n" +
                    "To switch phones: factory-reset the board (hold its reset " +
                    "button ~3s, or erase and re-flash the firmware over USB — see " +
                    "the project README), and remove the old pairing in the old " +
                    "phone's Bluetooth settings. The next phone to connect becomes " +
                    "the new owner.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
