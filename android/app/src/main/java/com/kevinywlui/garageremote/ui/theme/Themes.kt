package com.kevinywlui.garageremote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Palettes were generated offline from a seed color per theme following the
// M3 tonal system (tone ~40 primaries on light, ~80 on dark), then
// hand-adjusted for each theme's character; committed as static constants
// per the plan — material-color-utilities is not a runtime dependency.
// on-X/X pairs keep the tonal spacing that guarantees WCAG AA.

// Darkness lives on ThemeSpec.isDark only — a second source here would drift.
enum class AppTheme(val displayName: String) {
    FOLLOW_SYSTEM("Follow system"),
    PORCELAIN("Porcelain"),
    SUNRISE("Sunrise"),
    MINT("Mint"),
    MIDNIGHT("Midnight"),
    EMBER("Ember"),
    FOREST("Forest");

    companion object {
        fun fromName(name: String?): AppTheme =
            entries.firstOrNull { it.name == name } ?: FOLLOW_SYSTEM
    }
}

/** M3 ColorScheme has no success roles; each palette ships its own. */
data class ExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
)

val LocalExtendedColors = staticCompositionLocalOf {
    ExtendedColors(Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified)
}

data class ThemeSpec(
    val theme: AppTheme,
    val scheme: ColorScheme,
    val extended: ExtendedColors,
    val isDark: Boolean,
)

// ---------------------------------------------------------------- Porcelain
private val PorcelainScheme = lightColorScheme(
    primary = Color(0xFF00639B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDE5FF),
    onPrimaryContainer = Color(0xFF001D32),
    secondary = Color(0xFF51606F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD4E4F6),
    onSecondaryContainer = Color(0xFF0D1D2A),
    background = Color(0xFFF8F9FC),
    onBackground = Color(0xFF191C1E),
    surface = Color(0xFFFCFCFF),
    onSurface = Color(0xFF191C1E),
    surfaceVariant = Color(0xFFDEE3EB),
    onSurfaceVariant = Color(0xFF42474E),
    outline = Color(0xFF72777F),
)
private val PorcelainExtended = ExtendedColors(
    success = Color(0xFF276A49),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFADF2C7),
    onSuccessContainer = Color(0xFF002111),
)

// ------------------------------------------------------------------ Sunrise
// Primary held at tone ~40 (reads amber/brown) so white button text passes
// 4.5:1 — saturated orange fails; flagged explicitly by the panel.
private val SunriseScheme = lightColorScheme(
    primary = Color(0xFF8B5000),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCBE),
    onPrimaryContainer = Color(0xFF2D1600),
    secondary = Color(0xFF705B41),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFBDEBC),
    onSecondaryContainer = Color(0xFF271905),
    background = Color(0xFFFFF8F4),
    onBackground = Color(0xFF201B16),
    surface = Color(0xFFFFF8F4),
    onSurface = Color(0xFF201B16),
    surfaceVariant = Color(0xFFF1E0D0),
    onSurfaceVariant = Color(0xFF504539),
    outline = Color(0xFF827568),
)
private val SunriseExtended = ExtendedColors(
    // Cool green kept far from the warm primary and from error red.
    success = Color(0xFF1B6D3E),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFA4F4BB),
    onSuccessContainer = Color(0xFF00210D),
)

// --------------------------------------------------------------------- Mint
private val MintScheme = lightColorScheme(
    primary = Color(0xFF006C51),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF85F8D0),
    onPrimaryContainer = Color(0xFF002117),
    secondary = Color(0xFF4C6358),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCEE9DA),
    onSecondaryContainer = Color(0xFF092017),
    background = Color(0xFFF5FBF6),
    onBackground = Color(0xFF171D1A),
    surface = Color(0xFFFBFDF9),
    onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFDBE5DE),
    onSurfaceVariant = Color(0xFF404944),
    outline = Color(0xFF707974),
)
private val MintExtended = ExtendedColors(
    // Hue-shifted toward yellow-green so READY doesn't read as
    // just-another-theme-color next to the teal-green primary.
    success = Color(0xFF4C6706),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFCDEF83),
    onSuccessContainer = Color(0xFF141F00),
)

// ----------------------------------------------------------------- Midnight
private val MidnightScheme = darkColorScheme(
    primary = Color(0xFF98CBFF),
    onPrimary = Color(0xFF003353),
    primaryContainer = Color(0xFF004A76),
    onPrimaryContainer = Color(0xFFCFE5FF),
    secondary = Color(0xFFB9C8DA),
    onSecondary = Color(0xFF233240),
    secondaryContainer = Color(0xFF394857),
    onSecondaryContainer = Color(0xFFD5E4F6),
    background = Color(0xFF0E1216),
    onBackground = Color(0xFFE1E2E6),
    surface = Color(0xFF111418),
    onSurface = Color(0xFFE1E2E6),
    surfaceVariant = Color(0xFF42474E),
    onSurfaceVariant = Color(0xFFC2C7CF),
    outline = Color(0xFF8C9199),
)
private val MidnightExtended = ExtendedColors(
    success = Color(0xFF7BDB9C),
    onSuccess = Color(0xFF003920),
    successContainer = Color(0xFF005231),
    onSuccessContainer = Color(0xFF97F7B7),
)

// -------------------------------------------------------------------- Ember
private val EmberScheme = darkColorScheme(
    primary = Color(0xFFFFB77C),
    onPrimary = Color(0xFF4A2800),
    primaryContainer = Color(0xFF6A3C00),
    onPrimaryContainer = Color(0xFFFFDCBE),
    // Warm orange IS Ember's character (§1); separation from failure comes
    // from shifting the error roles toward crimson/pink below, not from
    // desaturating secondary.
    secondary = Color(0xFFE5BF7B),
    onSecondary = Color(0xFF402D00),
    secondaryContainer = Color(0xFF5C4200),
    onSecondaryContainer = Color(0xFFFFDEA6),
    error = Color(0xFFFFB1C8),
    onError = Color(0xFF650033),
    errorContainer = Color(0xFF8E004A),
    onErrorContainer = Color(0xFFFFD9E2),
    background = Color(0xFF17130E),
    onBackground = Color(0xFFEBE1D9),
    surface = Color(0xFF1B1610),
    onSurface = Color(0xFFEBE1D9),
    surfaceVariant = Color(0xFF4F4539),
    onSurfaceVariant = Color(0xFFD3C4B4),
    outline = Color(0xFF9C8E80),
)
private val EmberExtended = ExtendedColors(
    success = Color(0xFF86D993),
    onSuccess = Color(0xFF003912),
    successContainer = Color(0xFF005224),
    onSuccessContainer = Color(0xFFA1F6AD),
)

// ------------------------------------------------------------------- Forest
private val ForestScheme = darkColorScheme(
    // Teal-leaning primary so the pure-green success roles stay distinct.
    primary = Color(0xFF4FDBC2),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005045),
    onPrimaryContainer = Color(0xFF71F8DE),
    secondary = Color(0xFFB1CCC2),
    onSecondary = Color(0xFF1D352D),
    secondaryContainer = Color(0xFF334B43),
    onSecondaryContainer = Color(0xFFCDE9DD),
    background = Color(0xFF0E1512),
    onBackground = Color(0xFFDDE4DF),
    surface = Color(0xFF111814),
    onSurface = Color(0xFFDDE4DF),
    surfaceVariant = Color(0xFF3F4944),
    onSurfaceVariant = Color(0xFFBFC9C2),
    outline = Color(0xFF89938D),
)
private val ForestExtended = ExtendedColors(
    success = Color(0xFF8CD98F),
    onSuccess = Color(0xFF00390E),
    successContainer = Color(0xFF105121),
    onSuccessContainer = Color(0xFFA7F6A9),
)

private val Specs: Map<AppTheme, ThemeSpec> = mapOf(
    AppTheme.PORCELAIN to ThemeSpec(AppTheme.PORCELAIN, PorcelainScheme, PorcelainExtended, false),
    AppTheme.SUNRISE to ThemeSpec(AppTheme.SUNRISE, SunriseScheme, SunriseExtended, false),
    AppTheme.MINT to ThemeSpec(AppTheme.MINT, MintScheme, MintExtended, false),
    AppTheme.MIDNIGHT to ThemeSpec(AppTheme.MIDNIGHT, MidnightScheme, MidnightExtended, true),
    AppTheme.EMBER to ThemeSpec(AppTheme.EMBER, EmberScheme, EmberExtended, true),
    AppTheme.FOREST to ThemeSpec(AppTheme.FOREST, ForestScheme, ForestExtended, true),
)

/** Resolve FOLLOW_SYSTEM to Porcelain/Midnight; concrete themes to themselves. */
fun resolveTheme(theme: AppTheme, systemDark: Boolean): ThemeSpec =
    Specs.getValue(
        when (theme) {
            AppTheme.FOLLOW_SYSTEM -> if (systemDark) AppTheme.MIDNIGHT else AppTheme.PORCELAIN
            else -> theme
        }
    )

fun themeSpec(theme: AppTheme): ThemeSpec = Specs.getValue(
    if (theme == AppTheme.FOLLOW_SYSTEM) AppTheme.PORCELAIN else theme
)

@Composable
fun GarageTheme(theme: AppTheme, content: @Composable () -> Unit) {
    val spec = resolveTheme(theme, isSystemInDarkTheme())
    CompositionLocalProvider(LocalExtendedColors provides spec.extended) {
        MaterialTheme(colorScheme = spec.scheme, content = content)
    }
}
