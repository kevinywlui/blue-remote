package com.kevinywlui.garageremote

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.kevinywlui.garageremote.ui.theme.AppTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

data class ErrorInfo(val cause: ErrorCause) {
    val securityRelevant: Boolean
        get() = cause == ErrorCause.STALE_BOND ||
            cause == ErrorCause.PAIRING_REJECTED ||
            cause == ErrorCause.UNPAIRED_SELF
}

/** Errors are IDLE plus a payload (plan §3) — never message-string sniffing. */
sealed interface UiState {
    /**
     * [error] is the current, actionable cause (chip label + button routing
     * key off it); [securityDetail] is persisted security guidance shown on
     * the detail line — it must never displace a newer actionable cause.
     */
    data class Idle(
        val error: ErrorInfo? = null,
        val securityDetail: ErrorInfo? = null,
    ) : UiState
    data object Scanning : UiState
    data object Connecting : UiState
    data object Bonding : UiState
    data class Ready(val securityDetail: ErrorInfo? = null) : UiState
    data object Triggering : UiState
    data object Cooldown : UiState
}

data class SnackEvent(val message: String, val retry: Boolean = false)

class GarageViewModel(app: Application) :
    AndroidViewModel(app), DefaultLifecycleObserver, GarageBleClient.Listener {

    companion object {
        // Firmware cooldown is 2s; padded so our window never ends first (§3).
        const val COOLDOWN_MS = 2200L
        private const val GRACE_MS = 2500L
        private const val PHONES_POLL_MS = 5_000L
        // The firmware executes unpairs from its loop; re-read shortly after.
        private const val PHONES_REFRESH_DELAY_MS = 400L
        // A fresh bond that drops this soon with nothing confirmed was
        // rejected by the board (window closed / list full).
        private const val REJECTED_BOND_WINDOW_MS = 5_000L
        const val MAX_BONDS = 3
        private const val TEARDOWN_HARD_CAP_MS = 35_000L
        private const val SYSTEM_ACTIVITY_GUARD_TIMEOUT_MS = 60_000L

        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= 31) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        fun hasPermissions(context: Context): Boolean = requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private val appContext: Context get() = getApplication()
    private val client = GarageBleClient(appContext, this)

    // Read synchronously before setContent seeds this flow — no one-frame
    // default-theme flash on cold start (§1).
    private val _theme = MutableStateFlow(AppPrefs.theme(appContext))
    val theme: StateFlow<AppTheme> = _theme.asStateFlow()

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Whether the last-connected firmware exposes the pairing/bond-list
    // protocol; drives whether the Phones section exists at all (old
    // firmware hides it). Kept across disconnects, refreshed on each Ready.
    private val _phonesAvailable = MutableStateFlow(false)
    val phonesAvailable: StateFlow<Boolean> = _phonesAvailable.asStateFlow()

    // Last-read bond allowlist; kept across disconnects (actions are gated
    // on Ready, the cached list is display-only).
    private val _pairedDevices = MutableStateFlow<List<PairedDevice>>(emptyList())
    val pairedDevices: StateFlow<List<PairedDevice>> = _pairedDevices.asStateFlow()

    // One-shot events; a Channel buffers across recreation (plan §3).
    private val snackChannel = Channel<SnackEvent>(
        capacity = Channel.BUFFERED, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val snackbars: Flow<SnackEvent> = snackChannel.receiveAsFlow()

    private var cooldownJob: Job? = null

    // Persisted security guidance; survives stop/start, shown as READY's
    // detail line after reconnect until the user acts.
    private var lastSecurityError: ErrorInfo? = null

    // Set when the user confirmed unpairing THIS phone; the resulting
    // disconnect is expected and must not trigger the silent reconnect.
    // Cleared on every path that proves the unpair didn't happen (write
    // failure, a later Ready, a bond list still containing us, a
    // client-initiated teardown that may have dropped the queued write) so
    // a stale flag can't relabel an ordinary link loss as "access removed".
    private var expectSelfUnpair = false
    private var phonesPollJob: Job? = null

    // Rejected-pairing detector (§ firmware rejects AFTER auth completes):
    // a session that freshly bonded but never completed any operation and
    // then dropped remotely within seconds was rejected by the board — the
    // bond it just made was deleted board-side. Without this, the phone
    // lands in the stale-bond loop whose advice sends the user in circles.
    private var sessionNewlyBonded = false
    private var sessionConfirmed = false
    private var readyAt = 0L

    private var started = false
    private var graceJob: Job? = null
    private var hardCapJob: Job? = null
    private var teardownPending = false
    private var systemActivityInFlight = false
    private var systemActivityTimeoutJob: Job? = null
    private var midSessionRetryUsed = false

    // ------------------------------------------------------------ public API

    fun setTheme(theme: AppTheme) {
        _theme.value = theme
        AppPrefs.setTheme(appContext, theme)
    }

    /** User-initiated connect (button/chip retry). */
    fun connect() {
        if (!hasPermissions(appContext)) {
            setIdle(ErrorInfo(ErrorCause.PERMISSION_MISSING))
            return
        }
        if (_uiState.value !is UiState.Idle) return
        client.connect(userInitiated = true)
    }

    fun onPermissionsGranted() {
        if (_uiState.value is UiState.Idle) {
            // Leaving a PERMISSION_MISSING error behind.
            setIdle(null)
            client.connect(userInitiated = true)
        }
    }

    /** [permanent] = denied with rationale suppressed ("don't ask again"). */
    fun onPermissionsDenied(permanent: Boolean) {
        setIdle(
            ErrorInfo(
                if (permanent) ErrorCause.PERMISSION_DENIED_PERMANENTLY
                else ErrorCause.PERMISSION_MISSING,
            ),
        )
    }

    fun trigger() {
        if (_uiState.value !is UiState.Ready) return
        client.trigger()
    }

    fun openPairingWindow() {
        if (_uiState.value !is UiState.Ready) return
        client.openPairingWindow()
    }

    fun unpair(device: PairedDevice) {
        if (_uiState.value !is UiState.Ready) return
        if (device.isSelf) expectSelfUnpair = true
        client.unpair(device.rawEntry)
    }

    /** The Settings sheet drives list refresh + a light poll while visible. */
    fun onPhonesSheetVisible(visible: Boolean) {
        phonesPollJob?.cancel()
        if (!visible) return
        refreshPhones()
        phonesPollJob = viewModelScope.launch {
            while (true) {
                delay(PHONES_POLL_MS)
                refreshPhones()
            }
        }
    }

    private fun refreshPhones() {
        if (_phonesAvailable.value && _uiState.value is UiState.Ready) {
            client.readBondList()
        }
    }

    /** Set right before launching ACTION_REQUEST_ENABLE / settings deep links (§4). */
    fun notifySystemActivityLaunched() {
        systemActivityInFlight = true
        systemActivityTimeoutJob?.cancel()
        systemActivityTimeoutJob = viewModelScope.launch {
            delay(SYSTEM_ACTIVITY_GUARD_TIMEOUT_MS)
            systemActivityInFlight = false
            maybeRunPendingTeardown()
        }
    }

    fun notifySystemActivityResult() {
        systemActivityInFlight = false
        systemActivityTimeoutJob?.cancel()
        maybeRunPendingTeardown()
        if (started && hasPermissions(appContext) && _uiState.value is UiState.Idle) {
            client.connect()
        }
    }

    // ------------------------------------------------------------- lifecycle

    override fun onStart(owner: LifecycleOwner) {
        started = true
        graceJob?.cancel()
        hardCapJob?.cancel() // a cap armed in the background must not fire post-return
        teardownPending = false
        if (hasPermissions(appContext) && _uiState.value is UiState.Idle) {
            client.connect()
        } else {
            surfaceMissingPermission()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        started = false
        graceJob?.cancel()
        graceJob = viewModelScope.launch {
            delay(GRACE_MS)
            evaluateTeardown()
        }
    }

    override fun onCleared() {
        // Terminal: no UI left for the bonding flow; a held GATT occupies the
        // board's single connection slot. No guards apply (§4). close()
        // also releases the client's lifetime adapter receiver.
        client.close()
    }

    private fun evaluateTeardown() {
        if (started) return
        val state = _uiState.value
        val blocked = client.phase == GarageBleClient.Phase.BONDING ||
            state is UiState.Triggering || systemActivityInFlight
        if (blocked) {
            teardownPending = true
            hardCapJob?.cancel()
            hardCapJob = viewModelScope.launch {
                delay(TEARDOWN_HARD_CAP_MS)
                doTeardown()
            }
        } else {
            doTeardown()
        }
    }

    private fun maybeRunPendingTeardown() {
        if (teardownPending && !started) doTeardown()
    }

    private fun doTeardown() {
        if (started) return
        teardownPending = false
        hardCapJob?.cancel()
        client.disconnect()
    }

    // ------------------------------------------------------- client events

    override fun onScanStarted() {
        _uiState.value = UiState.Scanning
    }

    override fun onConnecting() {
        _uiState.value = UiState.Connecting
    }

    override fun onBonding() {
        _uiState.value = UiState.Bonding
    }

    override fun onReady(newlyBonded: Boolean, hasManagement: Boolean) {
        if (newlyBonded) lastSecurityError = null // user re-paired: acted
        midSessionRetryUsed = false
        expectSelfUnpair = false // a new session invalidates a stale flag
        sessionNewlyBonded = newlyBonded
        sessionConfirmed = false
        readyAt = android.os.SystemClock.elapsedRealtime()
        _phonesAvailable.value = hasManagement
        _uiState.value = UiState.Ready(lastSecurityError)
        if (hasManagement) refreshPhones()
        maybeRunPendingTeardown()
    }

    override fun onFailed(cause: ErrorCause) {
        cooldownJob?.cancel()
        setIdle(ErrorInfo(cause))
        when (cause) {
            ErrorCause.NOT_FOUND -> snack("Board not found — is it powered and in range?", retry = true)
            ErrorCause.SCAN_FAILED -> snack("Bluetooth scan failed", retry = true)
            ErrorCause.PAIRING_REJECTED ->
                snack("Pairing failed — open the pairing window from a paired phone first")
            ErrorCause.STALE_BOND ->
                snack("Connection keeps failing — remove the old pairing in Bluetooth settings")
            ErrorCause.NO_TRIGGER_CHAR -> snack("Unexpected device — wrong board?")
            else -> Unit // BLUETOOTH_OFF / LOCATION_OFF / PERMISSION render inline
        }
        maybeRunPendingTeardown()
    }

    override fun onStaleBondCleared() {
        snack("Pairing was removed — setting up again")
    }

    override fun onWriteIssued() {
        _uiState.value = UiState.Triggering
    }

    override fun onWriteSuccess() {
        sessionConfirmed = true
        if (_uiState.value !is UiState.Triggering) return
        _uiState.value = UiState.Cooldown
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            delay(COOLDOWN_MS)
            // A successful trigger is the user acting: stale guidance clears.
            lastSecurityError = null
            if (_uiState.value is UiState.Cooldown) _uiState.value = UiState.Ready(null)
        }
        maybeRunPendingTeardown() // COOLDOWN is an explicitly safe teardown state
    }

    override fun onWriteFailed(status: Int, insufficientAuth: Boolean) {
        cooldownJob?.cancel()
        if (insufficientAuth) {
            // Stale bond after a board reset (§3).
            lastSecurityError = ErrorInfo(ErrorCause.STALE_BOND)
            snack("Board refused the write — remove the old pairing in Bluetooth settings")
        } else {
            snack("Sending failed — try again", retry = false)
        }
        if (_uiState.value is UiState.Triggering) {
            _uiState.value = UiState.Ready(lastSecurityError)
        }
        maybeRunPendingTeardown() // a failed write also resolves TRIGGERING (§4)
    }

    override fun onMgmtWriteResult(kind: MgmtOp, success: Boolean) {
        if (success) sessionConfirmed = true
        when (kind) {
            MgmtOp.OPEN_WINDOW ->
                if (success) {
                    snack("Pairing open for 30 seconds — connect from the new phone now")
                } else {
                    snack("Couldn't open pairing — try again")
                }
            MgmtOp.UNPAIR ->
                if (success) {
                    viewModelScope.launch {
                        delay(PHONES_REFRESH_DELAY_MS)
                        refreshPhones()
                    }
                } else {
                    expectSelfUnpair = false
                    snack("Couldn't remove the phone — try again")
                }
            MgmtOp.SET_NAME -> Unit // best-effort; the list just shows the address
        }
    }

    override fun onBondList(devices: List<PairedDevice>) {
        sessionConfirmed = true
        // Still on the board's list: any pending self-unpair never executed
        // (e.g. the firmware dropped the op).
        if (devices.any { it.isSelf }) expectSelfUnpair = false
        _pairedDevices.value = devices
    }

    override fun onBondListFailed(status: Int) = Unit // poll/next refresh retries

    override fun onDisconnected(clientInitiated: Boolean) {
        val state = _uiState.value
        cooldownJob?.cancel()
        if (clientInitiated) {
            // Teardown may have dropped a queued self-unpair write without a
            // result callback; don't let the flag outlive it.
            expectSelfUnpair = false
            setIdle(null)
            return
        }
        // The board dropping us right after we unpaired THIS phone is the
        // expected outcome, not a lost link: land on the persistent
        // "access removed" guidance and don't silently reconnect.
        if (expectSelfUnpair) {
            expectSelfUnpair = false
            AppPrefs.setDeviceMac(appContext, null)
            _pairedDevices.value = emptyList()
            lastSecurityError = ErrorInfo(ErrorCause.UNPAIRED_SELF)
            setIdle(lastSecurityError)
            maybeRunPendingTeardown()
            return
        }
        // Rejected fresh bond: the firmware rejects a phone AFTER pairing
        // completes, so the phone briefly reaches Ready and then gets
        // dropped (its bond deleted board-side). Surface the pairing-window
        // guidance instead of reconnect/stale-bond advice, and forget the
        // MAC of a board that just rejected us.
        if (sessionNewlyBonded && !sessionConfirmed &&
            android.os.SystemClock.elapsedRealtime() - readyAt <= REJECTED_BOND_WINDOW_MS
        ) {
            sessionNewlyBonded = false
            AppPrefs.setDeviceMac(appContext, null)
            setIdle(ErrorInfo(ErrorCause.PAIRING_REJECTED))
            snack("Pairing failed — open the pairing window from a paired phone first")
            maybeRunPendingTeardown()
            return
        }
        // One bounded silent reconnect on a mid-session drop from READY (§4).
        if (state is UiState.Ready && started && !midSessionRetryUsed && hasPermissions(appContext)) {
            midSessionRetryUsed = true
            setIdle(null)
            client.connect(directOnly = true)
            maybeRunPendingTeardown()
            return
        }
        setIdle(ErrorInfo(ErrorCause.LINK_LOST))
        maybeRunPendingTeardown()
    }

    override fun onAdapterOn() {
        if (started && _uiState.value is UiState.Idle) {
            if (hasPermissions(appContext)) client.connect() else surfaceMissingPermission()
        }
    }

    /**
     * A start with revoked permissions must land on the permission recovery
     * UI, not a mute "Disconnected" chip (§4).
     */
    private fun surfaceMissingPermission() {
        if (!hasPermissions(appContext) && _uiState.value is UiState.Idle) {
            setIdle(ErrorInfo(ErrorCause.PERMISSION_MISSING))
        }
    }

    // --------------------------------------------------------------- helpers

    /**
     * The incoming cause is always the primary payload; persisted security
     * guidance rides along as the detail line (§3) — surviving null-payload
     * teardowns without ever masking a newer actionable error.
     */
    private fun setIdle(error: ErrorInfo?) {
        if (error != null && error.securityRelevant) lastSecurityError = error
        val primary = error ?: lastSecurityError
        val detail = lastSecurityError?.takeIf { it != primary }
        _uiState.value = UiState.Idle(primary, detail)
    }

    private fun snack(message: String, retry: Boolean = false) {
        snackChannel.trySend(SnackEvent(message, retry))
    }
}
