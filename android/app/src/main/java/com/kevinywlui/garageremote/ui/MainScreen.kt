package com.kevinywlui.garageremote.ui

import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kevinywlui.garageremote.ErrorCause
import com.kevinywlui.garageremote.GarageViewModel
import com.kevinywlui.garageremote.UiState
import com.kevinywlui.garageremote.ui.theme.LocalExtendedColors
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Actions that must be launched by the activity (system prompts, dialogs). */
class MainScreenActions(
    val requestEnableBluetooth: () -> Unit,
    val requestPermissions: () -> Unit,
    val openAppSettings: () -> Unit,
    val openLocationSettings: () -> Unit,
    val openSettingsSheet: () -> Unit,
)

@Composable
fun MainScreen(vm: GarageViewModel, actions: MainScreenActions) {
    val state by vm.uiState.collectAsState()
    val pinSet by vm.pinSet.collectAsState()
    val hintSeen by vm.firstPressHintSeen.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val reducedMotion = remember {
        Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        ) == 0f
    }

    LaunchedEffect(Unit) {
        // collectLatest: a new event cancels the suspended showSnackbar,
        // which removes the visible snackbar — no queueing on rapid retries
        // (a plain collect would suspend inside showSnackbar and queue).
        vm.snackbars.collectLatest { event ->
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = event.message,
                actionLabel = if (event.retry) "Retry" else null,
            )
            if (result == SnackbarResult.ActionPerformed) vm.connect()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }, // Scaffold insets it above the nav bar
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            IconButton(
                onClick = actions.openSettingsSheet,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }

            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                StatusChip(state, reducedMotion, onRetry = { vm.connect() })
                SecondaryDetail(state)
                Spacer(Modifier.height(40.dp))
                BigButton(vm, state, reducedMotion, actions)
                Spacer(Modifier.height(24.dp))
                if (pinSet && !hintSeen) {
                    Text(
                        text = "Your first press registers this PIN with a fresh board.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

private data class ChipSpec(
    val label: String,
    val container: Color,
    val content: Color,
    val spinner: Boolean = false,
    val pulse: Boolean = false,
    val warn: Boolean = false,
    val retryTarget: Boolean = false,
)

@Composable
private fun chipSpec(state: UiState): ChipSpec {
    val scheme = MaterialTheme.colorScheme
    val ext = LocalExtendedColors.current
    return when (state) {
        is UiState.Idle -> when (val cause = state.error?.cause) {
            null -> ChipSpec("Disconnected", scheme.surfaceVariant, scheme.onSurfaceVariant, retryTarget = true)
            else -> ChipSpec(chipErrorLabel(cause), scheme.errorContainer, scheme.onErrorContainer, warn = true, retryTarget = true)
        }
        UiState.Scanning -> ChipSpec("Scanning…", scheme.secondaryContainer, scheme.onSecondaryContainer, pulse = true)
        UiState.Connecting -> ChipSpec("Connecting…", scheme.secondaryContainer, scheme.onSecondaryContainer, spinner = true)
        UiState.Bonding -> ChipSpec(
            "Confirm pairing — check the dialog or your notification shade",
            scheme.secondaryContainer, scheme.onSecondaryContainer, spinner = true,
        )
        is UiState.Ready -> ChipSpec("Connected", ext.successContainer, ext.onSuccessContainer)
        // TRIGGERING and COOLDOWN share one label so the liveRegion fires
        // once per press (§3/§5).
        UiState.Triggering, UiState.Cooldown ->
            ChipSpec("Sending…", scheme.secondaryContainer, scheme.onSecondaryContainer, spinner = true)
    }
}

private fun chipErrorLabel(cause: ErrorCause): String = when (cause) {
    ErrorCause.NOT_FOUND -> "Not found"
    ErrorCause.BLUETOOTH_OFF -> "Bluetooth is off"
    ErrorCause.LOCATION_OFF -> "Location is off"
    ErrorCause.PERMISSION_MISSING -> "Permission needed"
    ErrorCause.PERMISSION_DENIED_PERMANENTLY -> "Permission needed"
    ErrorCause.SCAN_FAILED -> "Scan failed"
    ErrorCause.PAIRING_REJECTED -> "Pairing failed"
    ErrorCause.STALE_BOND -> "Pairing out of date"
    ErrorCause.WRONG_PIN_SUSPECTED -> "Board rejected the PIN"
    ErrorCause.LINK_LOST -> "Connection lost"
    ErrorCause.NO_TRIGGER_CHAR -> "Unexpected device"
}

@Composable
private fun StatusChip(state: UiState, reducedMotion: Boolean, onRetry: () -> Unit) {
    val spec = chipSpec(state)
    val pulseAlpha = if (spec.pulse && !reducedMotion) {
        rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 1f, targetValue = 0.4f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "pulseAlpha",
        ).value
    } else 1f

    // ONE call site for every state: the semantics node carrying liveRegion
    // must keep its identity across state changes or TalkBack never fires the
    // announcement (a recreated node is a subtree change, not a live-region
    // update). Retry states append a clickable to the same node; non-retry
    // states are plain, so nothing ever announces as "disabled".
    Surface(
        color = spec.container,
        contentColor = spec.content,
        shape = CircleShape,
        modifier = Modifier
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .then(
                if (spec.retryTarget) {
                    // The whole chip is the retry target (32dp chip vs 48dp rule).
                    Modifier
                        .minimumInteractiveComponentSize()
                        .clip(CircleShape)
                        .clickable(onClickLabel = "Retry connection", onClick = onRetry)
                } else {
                    Modifier
                },
            ),
    ) {
        ChipContent(spec, pulseAlpha)
    }
}

@Composable
private fun ChipContent(spec: ChipSpec, pulseAlpha: Float) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        when {
            spec.spinner -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = spec.content,
            )
            spec.warn -> Icon(
                Icons.Filled.Warning, contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            spec.pulse -> Icon(
                Icons.Filled.Refresh, contentDescription = null,
                modifier = Modifier.size(16.dp).alpha(pulseAlpha),
            )
            else -> Icon(
                if (spec.retryTarget) Icons.Filled.Info else Icons.Filled.Lock,
                contentDescription = null, modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(spec.label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SecondaryDetail(state: UiState) {
    val detail: String? = when (state) {
        is UiState.Idle -> listOfNotNull(
            state.error?.cause?.let(::detailText),
            // Persisted security guidance rides along without masking the
            // primary cause (§3).
            state.securityDetail?.cause?.let(::detailText),
        ).joinToString("\n\n").ifEmpty { null }
        is UiState.Ready -> state.securityDetail?.cause?.let(::detailText)
        else -> null
    }
    if (detail != null) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun detailText(cause: ErrorCause): String? = when (cause) {
    ErrorCause.WRONG_PIN_SUSPECTED ->
        "The board may have been provisioned with a different PIN. See \"How pairing works\" in Settings for the factory-reset steps."
    ErrorCause.STALE_BOND ->
        "Remove this device's old pairing in your phone's Bluetooth settings, then reconnect."
    ErrorCause.PAIRING_REJECTED ->
        "The board only bonds to one phone. Factory-reset it (see \"How pairing works\" in Settings) to take ownership."
    ErrorCause.NOT_FOUND -> "Make sure the board is powered and within range."
    ErrorCause.BLUETOOTH_OFF -> "Turn on Bluetooth to connect."
    ErrorCause.LOCATION_OFF -> "Android needs Location Services on for BLE scanning on this Android version."
    ErrorCause.PERMISSION_MISSING -> "Grant the Bluetooth permission to connect."
    ErrorCause.PERMISSION_DENIED_PERMANENTLY ->
        "Bluetooth permission was denied. Open the app's settings to grant it."
    ErrorCause.LINK_LOST -> "The connection dropped — press Reconnect to try again."
    else -> null
}

@Composable
private fun BigButton(
    vm: GarageViewModel,
    state: UiState,
    reducedMotion: Boolean,
    actions: MainScreenActions,
) {
    val scheme = MaterialTheme.colorScheme
    val ext = LocalExtendedColors.current
    val view = LocalView.current

    val isReady = state is UiState.Ready
    val isIdle = state is UiState.Idle
    val isCooldown = state is UiState.Cooldown
    val enabled = isReady || isIdle

    // Success flash on entering COOLDOWN (write success). Reduced motion:
    // static successContainer for the whole cooldown — same roles (§1/§5).
    // Container and content animate as a PAIR: onPrimary over successContainer
    // is ~1.3:1 in every theme, so content/ring must ride
    // onSuccessContainer → onPrimary alongside the fill.
    val flashColor = remember { Animatable(Color.Transparent) }
    val flashContent = remember { Animatable(Color.Transparent) }
    var prevWasTriggering by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (isCooldown && prevWasTriggering) {
            if (Build.VERSION.SDK_INT >= 30) {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            } else {
                @Suppress("DEPRECATION")
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            if (!reducedMotion) {
                flashColor.snapTo(ext.successContainer)
                flashContent.snapTo(ext.onSuccessContainer)
                launch { flashColor.animateTo(scheme.primary, tween(700)) }
                launch { flashContent.animateTo(scheme.onPrimary, tween(700)) }
            }
        }
        prevWasTriggering = state is UiState.Triggering
    }

    // Cooldown countdown ring; static indicator under reduced motion.
    val ringProgress = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(isCooldown) {
        if (isCooldown) {
            ringProgress.snapTo(1f)
            if (!reducedMotion) {
                ringProgress.animateTo(
                    0f, tween(GarageViewModel.COOLDOWN_MS.toInt(), easing = LinearEasing),
                )
            }
        } else {
            ringProgress.snapTo(0f)
        }
    }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = if (!reducedMotion && pressed && enabled) 0.96f else 1f

    // Two distinct not-clickable looks (§3): COOLDOWN keeps prominence with
    // the ring; every other disabled state uses the pinned M3 muted recipe.
    val cooldownContainer = when {
        isCooldown && reducedMotion -> ext.successContainer
        isCooldown && flashColor.value != Color.Transparent -> flashColor.value
        else -> scheme.primary
    }
    val cooldownContent = when {
        isCooldown && reducedMotion -> ext.onSuccessContainer
        isCooldown && flashContent.value != Color.Transparent -> flashContent.value
        else -> scheme.onPrimary.copy(alpha = 0.75f)
    }
    // The ring must hold 3:1 against whatever fill it overlays (§5).
    val ringColor = when {
        reducedMotion -> ext.onSuccessContainer
        flashContent.value != Color.Transparent -> flashContent.value
        else -> scheme.onPrimary
    }
    val colors = ButtonDefaults.buttonColors(
        containerColor = scheme.primary,
        contentColor = scheme.onPrimary,
        disabledContainerColor = if (isCooldown) cooldownContainer
        else scheme.onSurface.copy(alpha = 0.12f),
        disabledContentColor = if (isCooldown) cooldownContent
        else scheme.onSurface.copy(alpha = 0.38f),
    )

    // Label and announced action derive from the SAME routing that picks the
    // tap behavior — "Reconnect" must never launch a settings screen.
    val idleCause = (state as? UiState.Idle)?.error?.cause
    val label = when {
        !isIdle -> "Open / Close"
        idleCause == ErrorCause.BLUETOOTH_OFF -> "Turn on Bluetooth"
        idleCause == ErrorCause.PERMISSION_MISSING ||
            idleCause == ErrorCause.PERMISSION_DENIED_PERMANENTLY -> "Grant permission"
        idleCause == ErrorCause.LOCATION_OFF -> "Turn on Location"
        else -> "Reconnect"
    }
    val clickLabel = when {
        !isIdle -> "trigger the garage door"
        idleCause == ErrorCause.BLUETOOTH_OFF -> "turn on Bluetooth"
        idleCause == ErrorCause.PERMISSION_MISSING -> "grant the Bluetooth permission"
        idleCause == ErrorCause.PERMISSION_DENIED_PERMANENTLY -> "open the permission settings"
        idleCause == ErrorCause.LOCATION_OFF -> "open Location settings"
        else -> "reconnect to the garage door"
    }

    Box(contentAlignment = Alignment.Center) {
        Button(
            onClick = {
                @Suppress("DEPRECATION")
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                // Re-derive from the snapshot: the lambda may be a frame
                // stale relative to an auto-reconnect state flip.
                when (val s = state) {
                    is UiState.Ready -> vm.trigger()
                    is UiState.Idle -> when (s.error?.cause) {
                        ErrorCause.BLUETOOTH_OFF -> actions.requestEnableBluetooth()
                        ErrorCause.PERMISSION_MISSING -> actions.requestPermissions()
                        ErrorCause.PERMISSION_DENIED_PERMANENTLY -> actions.openAppSettings()
                        ErrorCause.LOCATION_OFF -> actions.openLocationSettings()
                        else -> vm.connect()
                    }
                    else -> Unit
                }
            },
            enabled = enabled,
            shape = CircleShape,
            colors = colors,
            interactionSource = interaction,
            modifier = Modifier
                .size(220.dp)
                .scale(scale)
                .semantics {
                    onClick(label = clickLabel, action = null)
                    if (isCooldown) stateDescription = "Cooling down"
                },
        ) {
            GarageButtonContent(
                label = label,
                icon = if (isIdle) Icons.Filled.Refresh else GarageGlyph,
            )
        }
        if (isCooldown && ringProgress.value > 0f) {
            Canvas(modifier = Modifier.size(220.dp)) {
                val stroke = 6.dp.toPx()
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * ringProgress.value,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = androidx.compose.ui.geometry.Size(
                        size.width - stroke, size.height - stroke,
                    ),
                )
            }
        }
    }
}

/**
 * Icon + single-line label inside the 220dp circle. The label auto-shrinks
 * instead of clipping and the icon steps down at large accessibility font
 * scales — never cap the user's font scale (§1). Shared with the
 * fontScale-2f previews in Previews.kt.
 */
@Composable
internal fun GarageButtonContent(label: String, icon: ImageVector) {
    val fontScale = LocalDensity.current.fontScale
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = icon,
            contentDescription = null, // decorative; the label names the button
            modifier = Modifier.size(if (fontScale > 1.5f) 32.dp else 48.dp),
        )
        Spacer(Modifier.height(8.dp))
        AutoResizeText(label)
    }
}

/** Single-line text that steps its size down until it fits its max width. */
@Composable
internal fun AutoResizeText(text: String, modifier: Modifier = Modifier) {
    // Re-keyed on fontScale so a mid-session accessibility change un-shrinks.
    var scale by remember(text, LocalDensity.current.fontScale) { mutableStateOf(1f) }
    val base = MaterialTheme.typography.titleLarge
    Text(
        text = text,
        style = base.copy(fontSize = base.fontSize * scale),
        maxLines = 1,
        softWrap = false,
        onTextLayout = { result ->
            if (result.didOverflowWidth && scale > 0.5f) scale *= 0.9f
        },
        modifier = modifier,
    )
}

/** Simple garage-door glyph: roof + three door slats. */
val GarageGlyph: ImageVector by lazy {
    ImageVector.Builder(
        name = "Garage", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(fill = androidx.compose.ui.graphics.SolidColor(Color.White)) {
            // Roof / house outline
            moveTo(12f, 3f)
            lineTo(2f, 9f)
            lineTo(2f, 21f)
            lineTo(5f, 21f)
            lineTo(5f, 10.5f)
            lineTo(19f, 10.5f)
            lineTo(19f, 21f)
            lineTo(22f, 21f)
            lineTo(22f, 9f)
            close()
            // Door slats
            moveTo(6.5f, 12.5f); lineTo(17.5f, 12.5f); lineTo(17.5f, 14.5f); lineTo(6.5f, 14.5f); close()
            moveTo(6.5f, 15.75f); lineTo(17.5f, 15.75f); lineTo(17.5f, 17.75f); lineTo(6.5f, 17.75f); close()
            moveTo(6.5f, 19f); lineTo(17.5f, 19f); lineTo(17.5f, 21f); lineTo(6.5f, 21f); close()
        }
    }.build()
}
