package com.kevinywlui.garageremote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.location.LocationManagerCompat
import java.util.UUID

/** Why a connection attempt or session ended. Keyed by the UI, never sniffed from strings. */
enum class ErrorCause {
    NOT_FOUND, BLUETOOTH_OFF, LOCATION_OFF, PERMISSION_MISSING,
    PERMISSION_DENIED_PERMANENTLY, SCAN_FAILED,
    PAIRING_REJECTED, STALE_BOND, LINK_LOST, NO_TRIGGER_CHAR,
}

/**
 * Connection engine. Owns the GATT plumbing and reports what happened via
 * [Listener] events (delivered on the main thread); the ViewModel owns the
 * user-facing state machine and cooldown.
 *
 * Connect strategy per the UX plan §4: direct connectGatt to the persisted
 * bonded MAC (~10s timeout) first; scan is the setup-only fallback, filtered
 * to the bonded MAC while a bond exists (never accept an arbitrary
 * advertiser of our service UUID while bonded — spoof resistance).
 */
@SuppressLint("MissingPermission") // every public entry point is permission-gated by the ViewModel
class GarageBleClient(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onScanStarted()
        fun onConnecting()
        fun onBonding()
        fun onReady(newlyBonded: Boolean)
        /** Terminal failure of a connect attempt or session; client is now idle. */
        fun onFailed(cause: ErrorCause)
        /** "Pairing was removed — setting up again" note before re-scan. */
        fun onStaleBondCleared()
        fun onWriteIssued()
        fun onWriteSuccess()
        fun onWriteFailed(status: Int, insufficientAuth: Boolean)
        /** Link ended. [clientInitiated] includes adapter-off cleanup. */
        fun onDisconnected(clientInitiated: Boolean)
        fun onAdapterOn()
    }

    enum class Phase { IDLE, SCANNING, CONNECTING, BONDING, CONNECTED }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("4090b92d-a8da-471a-85a8-aee612b68bad")
        val TRIGGER_UUID: UUID = UUID.fromString("588a322e-4b88-4197-8f4e-a5f48417c8b7")
        // The bond is the credential; the payload just says "pulse".
        private val TRIGGER_PAYLOAD = byteArrayOf(0x01)
        private const val SCAN_TIMEOUT_MS = 15_000L
        private const val DIRECT_CONNECT_TIMEOUT_MS = 10_000L
        private const val GATT_INSUFFICIENT_AUTHENTICATION = 0x5
        private const val GATT_INSUFFICIENT_ENCRYPTION = 0xf
        // Stale-bond loop heuristic (§3): N pre-discovery disconnects against
        // the bonded MAC within the window.
        private const val LOOP_WINDOW_MS = 30_000L
        private const val LOOP_LIMIT = 3
    }

    var phase = Phase.IDLE
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null
    private var triggerChar: BluetoothGattCharacteristic? = null
    private var device: BluetoothDevice? = null
    private var scanning = false
    private var directAttempt = false
    private val orphanedGatts = mutableSetOf<BluetoothGatt>()

    private var bondReceiverRegistered = false
    private var adapterReceiverRegistered = false
    // True only when the bond receiver observed BOND_BONDED this session —
    // NOT when the session started already-bonded. The app-level write retry
    // exists solely for the fresh-bond/encryption race; in the stale-bond
    // case (board reset, link never encrypts) a retry can't succeed (§3).
    private var newlyBondedThisSession = false
    private var writeRetried = false
    private var discoveryCompleted = false

    // Pre-discovery disconnect timestamps for the stale-bond loop heuristic.
    private val earlyDisconnects = ArrayDeque<Long>()

    private var directOnlyRequest = false

    private val directTimeout = Runnable { onDirectConnectTimeout() }
    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            toIdle()
            listener.onFailed(ErrorCause.NOT_FOUND)
        }
    }

    /** [userInitiated] retries reset the stale-bond loop counter. */
    fun connect(userInitiated: Boolean = false, directOnly: Boolean = false) {
        if (phase != Phase.IDLE) return
        if (userInitiated) earlyDisconnects.clear()
        directOnlyRequest = directOnly

        val adapter = adapter
        if (adapter == null || !adapter.isEnabled) {
            listener.onFailed(ErrorCause.BLUETOOTH_OFF)
            return
        }
        newlyBondedThisSession = false
        discoveryCompleted = false

        val storedMac = AppPrefs.deviceMac(context)
        if (storedMac != null) {
            val stored = adapter.getRemoteDevice(storedMac)
            if (stored.bondState == BluetoothDevice.BOND_NONE) {
                // User removed the pairing in system settings; a direct
                // connect would succeed then fail on auth. Clear and rebond.
                AppPrefs.setDeviceMac(context, null)
                listener.onStaleBondCleared()
                if (directOnly) {
                    // The bounded mid-session retry never scans (§4).
                    listener.onFailed(ErrorCause.NOT_FOUND)
                    return
                }
                startScan(macFilter = null)
                return
            }
            directConnect(stored)
            return
        }
        if (directOnly) {
            listener.onFailed(ErrorCause.NOT_FOUND)
            return
        }
        startScan(macFilter = null)
    }

    fun trigger() {
        val gatt = gatt
        val characteristic = triggerChar
        if (phase != Phase.CONNECTED || gatt == null || characteristic == null) return
        writeRetried = false
        if (issueWrite(gatt, characteristic)) {
            listener.onWriteIssued()
        } else {
            listener.onWriteFailed(-1, insufficientAuth = false)
        }
    }

    /** Full teardown; never raises error events beyond onDisconnected(clientInitiated=true). */
    fun disconnect() {
        val hadLink = gatt != null || scanning || directAttempt
        cleanup()
        if (hadLink) listener.onDisconnected(clientInitiated = true)
    }

    // ------------------------------------------------------------- internals

    private fun toIdle() {
        phase = Phase.IDLE
    }

    private fun cleanup() {
        handler.removeCallbacks(directTimeout)
        handler.removeCallbacks(scanTimeout)
        stopScan()
        unregisterBondReceiver()
        gatt?.let { orphanedGatts.remove(it); it.close() }
        gatt = null
        triggerChar = null
        device = null
        directAttempt = false
        toIdle()
    }

    private fun directConnect(target: BluetoothDevice) {
        phase = Phase.CONNECTING
        directAttempt = true
        device = target
        listener.onConnecting()
        gatt = target.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        handler.postDelayed(directTimeout, DIRECT_CONNECT_TIMEOUT_MS)
    }

    private fun onDirectConnectTimeout() {
        if (!directAttempt || phase != Phase.CONNECTING) return
        // Bluedroid keeps a pending direct connect alive well past our
        // timeout; close and orphan it so a late callback can't hand us a
        // second connection the state machine doesn't own (§4).
        gatt?.let { stale ->
            orphanedGatts.add(stale)
            stale.close()
        }
        gatt = null
        directAttempt = false
        phase = Phase.IDLE
        if (directOnlyRequest) {
            // "One silent direct attempt" means exactly one (§4) — no scan.
            listener.onFailed(ErrorCause.NOT_FOUND)
            return
        }
        val mac = AppPrefs.deviceMac(context)
        startScan(macFilter = mac) // bonded → only accept that address
    }

    private fun startScan(macFilter: String?) {
        val adapter = adapter ?: run { listener.onFailed(ErrorCause.BLUETOOTH_OFF); return }
        val scanner = adapter.bluetoothLeScanner ?: run {
            toIdle(); listener.onFailed(ErrorCause.BLUETOOTH_OFF); return
        }
        // API 26–30: scans silently return nothing with Location Services off.
        if (Build.VERSION.SDK_INT < 31) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (!LocationManagerCompat.isLocationEnabled(lm)) {
                toIdle(); listener.onFailed(ErrorCause.LOCATION_OFF); return
            }
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .apply { if (macFilter != null) setDeviceAddress(macFilter) }
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        phase = Phase.SCANNING
        scanning = true
        listener.onScanStarted()
        scanner.startScan(listOf(filter), settings, scanCallback)
        handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        handler.removeCallbacks(scanTimeout)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            stopScan()
            phase = Phase.CONNECTING
            device = result.device
            listener.onConnecting()
            gatt = result.device.connectGatt(
                context, false, gattCallback, BluetoothDevice.TRANSPORT_LE,
            )
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            handler.removeCallbacks(scanTimeout)
            toIdle()
            listener.onFailed(ErrorCause.SCAN_FAILED)
        }
    }

    private fun issueWrite(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ): Boolean = if (Build.VERSION.SDK_INT >= 33) {
        g.writeCharacteristic(
            characteristic, TRIGGER_PAYLOAD, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        ) == BluetoothGatt.GATT_SUCCESS
    } else {
        @Suppress("DEPRECATION")
        run {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = TRIGGER_PAYLOAD
            g.writeCharacteristic(characteristic)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (g in orphanedGatts) {
                    // Late callback from a timed-out direct attempt.
                    orphanedGatts.remove(g)
                    runCatching { g.close() }
                    return@post
                }
                if (gatt !== g) return@post
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        handler.removeCallbacks(directTimeout)
                        directAttempt = false
                        g.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> handleDisconnect(g)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (gatt !== g) return@post
                val characteristic = g.getService(SERVICE_UUID)?.getCharacteristic(TRIGGER_UUID)
                if (characteristic == null) {
                    cleanup()
                    listener.onFailed(ErrorCause.NO_TRIGGER_CHAR)
                    return@post
                }
                discoveryCompleted = true
                earlyDisconnects.clear()
                triggerChar = characteristic
                val dev = g.device
                when (dev.bondState) {
                    BluetoothDevice.BOND_BONDED -> onLinkReady(newlyBonded = false)
                    else -> {
                        phase = Phase.BONDING
                        registerBondReceiver()
                        listener.onBonding()
                        if (dev.bondState == BluetoothDevice.BOND_NONE) dev.createBond()
                    }
                }
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            handler.post {
                if (gatt !== g) return@post
                val insufficient = status == GATT_INSUFFICIENT_AUTHENTICATION ||
                    status == GATT_INSUFFICIENT_ENCRYPTION
                when {
                    status == BluetoothGatt.GATT_SUCCESS -> listener.onWriteSuccess()
                    // One silent retry, only for the bond-complete-before-
                    // encryption race, only after the bond receiver observed
                    // BOND_BONDED this session (§3).
                    insufficient && newlyBondedThisSession && !writeRetried -> {
                        writeRetried = true
                        val chr = triggerChar
                        val cur = gatt
                        if (chr != null && cur != null && issueWrite(cur, chr)) return@post
                        listener.onWriteFailed(status, insufficientAuth = true)
                    }
                    else -> listener.onWriteFailed(status, insufficientAuth = insufficient)
                }
            }
        }
    }

    private fun onLinkReady(newlyBonded: Boolean) {
        phase = Phase.CONNECTED
        device?.let { AppPrefs.setDeviceMac(context, it.address) }
        listener.onReady(newlyBonded)
    }

    private fun handleDisconnect(g: BluetoothGatt) {
        val preDiscovery = !discoveryCompleted
        val wasBonded = AppPrefs.deviceMac(context) != null
        // A direct attempt failing in the connect phase (fast status-133) is
        // a fallback trigger, not a lost link (§4).
        val failedDirectAttempt = directAttempt && phase == Phase.CONNECTING
        g.close()
        val wasConnectingOrUp = phase != Phase.IDLE
        gatt = null
        triggerChar = null
        unregisterBondReceiver()
        handler.removeCallbacks(directTimeout)
        directAttempt = false
        toIdle()
        if (!wasConnectingOrUp) return

        if (preDiscovery && wasBonded) {
            val now = android.os.SystemClock.elapsedRealtime()
            earlyDisconnects.addLast(now)
            while (earlyDisconnects.isNotEmpty() && now - earlyDisconnects.first() > LOOP_WINDOW_MS) {
                earlyDisconnects.removeFirst()
            }
            if (earlyDisconnects.size >= LOOP_LIMIT) {
                earlyDisconnects.clear()
                listener.onFailed(ErrorCause.STALE_BOND)
                return
            }
        }
        if (failedDirectAttempt) {
            if (directOnlyRequest) {
                listener.onFailed(ErrorCause.NOT_FOUND)
            } else {
                startScan(macFilter = AppPrefs.deviceMac(context))
            }
            return
        }
        listener.onDisconnected(clientInitiated = false)
    }

    // ------------------------------------------------------- bond receiver

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val changed: BluetoothDevice? =
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            if (changed?.address != device?.address) return
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                BluetoothDevice.BOND_BONDED -> {
                    unregisterBondReceiver()
                    newlyBondedThisSession = true
                    if (phase == Phase.BONDING) onLinkReady(newlyBonded = true)
                }
                BluetoothDevice.BOND_NONE -> {
                    unregisterBondReceiver()
                    if (phase == Phase.BONDING) {
                        cleanup()
                        listener.onFailed(ErrorCause.PAIRING_REJECTED)
                    }
                }
            }
        }
    }

    private fun registerBondReceiver() {
        if (bondReceiverRegistered) return
        bondReceiverRegistered = true
        context.registerReceiver(
            bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
        )
    }

    private fun unregisterBondReceiver() {
        if (!bondReceiverRegistered) return
        bondReceiverRegistered = false
        runCatching { context.unregisterReceiver(bondReceiver) }
    }

    // ---------------------------------------------------- adapter receiver
    // Registered while we hold any radio resource (§4): the deferred-teardown
    // path can hold a GATT while stopped, and OEM stacks don't reliably
    // deliver DISCONNECTED when the adapter dies — the receiver, not the GATT
    // callback, drives adapter-off cleanup.

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    val hadLink = gatt != null || scanning || directAttempt
                    cleanup()
                    if (hadLink) {
                        listener.onDisconnected(clientInitiated = true)
                        listener.onFailed(ErrorCause.BLUETOOTH_OFF)
                    }
                }
                BluetoothAdapter.STATE_ON -> listener.onAdapterOn()
            }
        }
    }

    private fun registerAdapterReceiver() {
        if (adapterReceiverRegistered) return
        adapterReceiverRegistered = true
        context.registerReceiver(
            adapterReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
        )
    }

    private fun unregisterAdapterReceiver() {
        if (!adapterReceiverRegistered) return
        adapterReceiverRegistered = false
        runCatching { context.unregisterReceiver(adapterReceiver) }
    }

    /** Terminal teardown (ViewModel.onCleared): releases the lifetime receiver too. */
    fun close() {
        cleanup()
        // gatt.close() usually suppresses the late callback that would have
        // pruned these; don't let them accumulate for the process lifetime.
        orphanedGatts.forEach { runCatching { it.close() } }
        orphanedGatts.clear()
        unregisterAdapterReceiver()
    }

    init {
        // Registered for the client's lifetime (until close()): STATE_ON must
        // be observable even when connect() bailed early on a disabled
        // adapter, and after an adapter-off cleanup — otherwise the "no
        // second tap needed" reconnect (§4) could never fire.
        registerAdapterReceiver()
    }
}
