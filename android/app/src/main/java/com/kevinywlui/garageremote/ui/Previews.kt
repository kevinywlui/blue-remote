package com.kevinywlui.garageremote.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevinywlui.garageremote.ui.theme.AppTheme
import com.kevinywlui.garageremote.ui.theme.GarageTheme

/**
 * Plan §1/§5: verify the icon + single-line label fits the 220dp circle at
 * fontScale 2 in every theme (the label auto-shrinks rather than clipping;
 * the user's font scale is never capped).
 */
@Composable
private fun ButtonAtScale(theme: AppTheme) {
    GarageTheme(theme) {
        Surface {
            Box(Modifier.padding(16.dp)) {
                Button(
                    onClick = {},
                    shape = CircleShape,
                    modifier = Modifier.size(220.dp),
                ) {
                    GarageButtonContent(label = "Open / Close", icon = GarageGlyph)
                }
            }
        }
    }
}

@Preview(name = "Porcelain @2x", fontScale = 2f)
@Composable
private fun PreviewPorcelain() = ButtonAtScale(AppTheme.PORCELAIN)

@Preview(name = "Sunrise @2x", fontScale = 2f)
@Composable
private fun PreviewSunrise() = ButtonAtScale(AppTheme.SUNRISE)

@Preview(name = "Mint @2x", fontScale = 2f)
@Composable
private fun PreviewMint() = ButtonAtScale(AppTheme.MINT)

@Preview(name = "Midnight @2x", fontScale = 2f)
@Composable
private fun PreviewMidnight() = ButtonAtScale(AppTheme.MIDNIGHT)

@Preview(name = "Ember @2x", fontScale = 2f)
@Composable
private fun PreviewEmber() = ButtonAtScale(AppTheme.EMBER)

@Preview(name = "Forest @2x", fontScale = 2f)
@Composable
private fun PreviewForest() = ButtonAtScale(AppTheme.FOREST)
