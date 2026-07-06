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
    PAIRING_REJECTED, STALE_BOND, UNPAIRED_SELF, LINK_LOST, NO_TRIGGER_CHAR,
}

/** Management opcodes the app can send to the board. */
enum class MgmtOp { OPEN_WINDOW, UNPAIR, SET_NAME }

/**
 * One entry of the board's bond allowlist. [rawEntry] is the 7-byte wire
 * form (6B address in display order + 1B address type) echoed verbatim to
 * unpair; [isSelf] is computed by the firmware per connection — the app can
 * never learn its own identity address (Android hides it).
 */
class PairedDevice(
    val address: String,
    val rawEntry: ByteArray,
    val name: String?,
    val isSelf: Boolean,
) {
    override fun equals(other: Any?): Boolean = other is PairedDevice &&
        other.rawEntry.contentEquals(rawEntry) && other.name == name && other.isSelf == isSelf

    override fun hashCode(): Int = rawEntry.contentHashCode() * 31 + (name?.hashCode() ?: 0)
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
        /** [hasManagement] = firmware exposes the pairing/bond-list protocol. */
        fun onReady(newlyBonded: Boolean, hasManagement: Boolean)
        /** Terminal failure of a connect attempt or session; client is now idle. */
        fun onFailed(cause: ErrorCause)
        /** "Pairing was removed — setting up again" note before re-scan. */
        fun onStaleBondCleared()
        fun onWriteIssued()
        fun onWriteSuccess()
        fun onWriteFailed(status: Int, insufficientAuth: Boolean)
        fun onMgmtWriteResult(kind: MgmtOp, success: Boolean)
        fun onBondList(devices: List<PairedDevice>)
        fun onBondListFailed(status: Int)
        /** Link ended. [clientInitiated] includes adapter-off cleanup. */
        fun onDisconnected(clientInitiated: Boolean)
        fun onAdapterOn()
    }

    enum class Phase { IDLE, SCANNING, CONNECTING, BONDING, CONNECTED }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("4090b92d-a8da-471a-85a8-aee612b68bad")
        val TRIGGER_UUID: UUID = UUID.fromString("588a322e-4b88-4197-8f4e-a5f48417c8b7")
        val MGMT_UUID: UUID = UUID.fromString("f34dd3a3-ed37-4822-9d08-f96ee2974856")
        val BOND_LIST_UUID: UUID = UUID.fromString("8d3a66f6-42a0-4d0b-8a56-3a9f2f8e5c01")
        // The bond is the credential; the payload just says "pulse".
        private val TRIGGER_PAYLOAD = byteArrayOf(0x01)
        private const val OP_OPEN_WINDOW: Byte = 0x01
        private const val OP_UNPAIR: Byte = 0x02
        private const val OP_SET_NAME: Byte = 0x03
        private const val MAX_NAME_BYTES = 24
        // Fits the worst-case bond list (101B) and the name write in single
        // ATT ops; Android's transparent blob-read remains the fallback.
        private const val REQUEST_MTU = 247
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
    private var mgmtChar: BluetoothGattCharacteristic? = null
    private var bondListChar: BluetoothGattCharacteristic? = null
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
        if (phase != Phase.CONNECTED || triggerChar == null) return
        listener.onWriteIssued()
        enqueueOp(GattOp.Trigger)
    }

    fun openPairingWindow() {
        if (phase != Phase.CONNECTED || mgmtChar == null) {
            listener.onMgmtWriteResult(MgmtOp.OPEN_WINDOW, success = false)
            return
        }
        enqueueOp(GattOp.MgmtWrite(MgmtOp.OPEN_WINDOW, byteArrayOf(OP_OPEN_WINDOW)))
    }

    /** [rawEntry] must be the 7 wire bytes of a [PairedDevice], verbatim. */
    fun unpair(rawEntry: ByteArray) {
        if (phase != Phase.CONNECTED || mgmtChar == null || rawEntry.size != 7) {
            listener.onMgmtWriteResult(MgmtOp.UNPAIR, success = false)
            return
        }
        enqueueOp(GattOp.MgmtWrite(MgmtOp.UNPAIR, byteArrayOf(OP_UNPAIR) + rawEntry))
    }

    fun readBondList() {
        if (phase != Phase.CONNECTED || bondListChar == null) {
            listener.onBondListFailed(-1)
            return
        }
        enqueueOp(GattOp.ListRead)
    }

    /** Full teardown; never raises error events beyond onDisconnected(clientInitiated=true). */
    fun disconnect() {
        val hadLink = gatt != null || scanning || directAttempt
        cleanup()
        if (hadLink) listener.onDisconnected(clientInitiated = true)
    }

    // ---------------------------------------------------------- gatt op queue
    // GATT allows one outstanding operation; the trigger, management writes,
    // and the bond-list read share a FIFO so they never collide. The
    // in-flight op is completed by onCharacteristicWrite/-Read (dispatched
    // by characteristic UUID) or by onMtuChanged for the MTU exchange.

    private sealed interface GattOp {
        data object Trigger : GattOp
        class MgmtWrite(val kind: MgmtOp, val payload: ByteArray) : GattOp
        data object ListRead : GattOp
    }

    private val opQueue = ArrayDeque<GattOp>()
    private var opInFlight: GattOp? = null
    private var mtuPending = false

    private fun enqueueOp(op: GattOp) {
        opQueue.addLast(op)
        pumpOps()
    }

    private fun pumpOps() {
        if (opInFlight != null || mtuPending || phase != Phase.CONNECTED) return
        val g = gatt ?: return
        while (opQueue.isNotEmpty()) {
            val op = opQueue.removeFirst()
            val started = when (op) {
                is GattOp.Trigger -> {
                    writeRetried = false
                    triggerChar?.let { issueWrite(g, it, TRIGGER_PAYLOAD) } ?: false
                }
                is GattOp.MgmtWrite -> mgmtChar?.let { issueWrite(g, it, op.payload) } ?: false
                is GattOp.ListRead -> bondListChar?.let { g.readCharacteristic(it) } ?: false
            }
            if (started) {
                opInFlight = op
                return
            }
            reportOpFailure(op)
        }
    }

    private fun reportOpFailure(op: GattOp) {
        when (op) {
            is GattOp.Trigger -> listener.onWriteFailed(-1, insufficientAuth = false)
            is GattOp.MgmtWrite -> listener.onMgmtWriteResult(op.kind, success = false)
            is GattOp.ListRead -> listener.onBondListFailed(-1)
        }
    }

    private fun completeOp() {
        opInFlight = null
        pumpOps()
    }

    private fun clearOps() {
        opQueue.clear()
        opInFlight = null
        mtuPending = false
    }

    /** This phone's Bluetooth name, truncated to 24 UTF-8 bytes at a codepoint boundary. */
    private fun localNameBytes(): ByteArray {
        // A blank adapter name would register as a CLEAR (0 bytes) — fall
        // back to the model instead.
        val raw = (runCatching { adapter?.name }.getOrNull() ?: "").trim()
            .ifEmpty { Build.MODEL.trim() }
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val next = i + Character.charCount(raw.codePointAt(i))
            if ((sb.toString() + raw.substring(i, next)).toByteArray(Charsets.UTF_8).size >
                MAX_NAME_BYTES
            ) break
            sb.append(raw, i, next)
            i = next
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** Wire format: [version=1][count] then per entry [6B addr][type][flags][nameLen][name]. */
    private fun parseBondList(data: ByteArray): List<PairedDevice>? {
        if (data.size < 2 || data[0].toInt() != 1) return null
        val count = data[1].toInt() and 0xff
        val out = mutableListOf<PairedDevice>()
        var i = 2
        repeat(count) {
            if (i + 9 > data.size) return null
            val raw = data.copyOfRange(i, i + 7)
            val flags = data[i + 7].toInt()
            val nameLen = data[i + 8].toInt() and 0xff
            if (i + 9 + nameLen > data.size) return null
            val name = if (nameLen > 0) String(data, i + 9, nameLen, Charsets.UTF_8) else null
            val address = raw.take(6).joinToString(":") { "%02X".format(it) }
            out += PairedDevice(address, raw, name, flags and 0x01 != 0)
            i += 9 + nameLen
        }
        return out
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
        mgmtChar = null
        bondListChar = null
        clearOps()
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
        payload: ByteArray,
    ): Boolean = if (Build.VERSION.SDK_INT >= 33) {
        g.writeCharacteristic(
            characteristic, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        ) == BluetoothGatt.GATT_SUCCESS
    } else {
        @Suppress("DEPRECATION")
        run {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = payload
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
                val service = g.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(TRIGGER_UUID)
                if (characteristic == null) {
                    cleanup()
                    listener.onFailed(ErrorCause.NO_TRIGGER_CHAR)
                    return@post
                }
                discoveryCompleted = true
                earlyDisconnects.clear()
                triggerChar = characteristic
                // Optional on older firmware; their absence just hides the
                // phone-management UI.
                mgmtChar = service.getCharacteristic(MGMT_UUID)
                bondListChar = service.getCharacteristic(BOND_LIST_UUID)
                if (bondListChar != null && g.requestMtu(REQUEST_MTU)) {
                    // The exchange is itself a GATT op; hold the queue until
                    // onMtuChanged so the first write/read can't collide.
                    mtuPending = true
                }
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
                when (characteristic.uuid) {
                    TRIGGER_UUID -> onTriggerWriteResult(status)
                    MGMT_UUID -> {
                        (opInFlight as? GattOp.MgmtWrite)?.let {
                            listener.onMgmtWriteResult(it.kind, status == BluetoothGatt.GATT_SUCCESS)
                        }
                        completeOp()
                    }
                }
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            handler.post { handleRead(g, characteristic, value, status) }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT >= 33) return // the value variant delivers on 33+
            @Suppress("DEPRECATION") val value = characteristic.value ?: ByteArray(0)
            handler.post { handleRead(g, characteristic, value, status) }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                if (gatt !== g) return@post
                mtuPending = false
                pumpOps()
            }
        }
    }

    private fun onTriggerWriteResult(status: Int) {
        val insufficient = status == GATT_INSUFFICIENT_AUTHENTICATION ||
            status == GATT_INSUFFICIENT_ENCRYPTION
        when {
            status == BluetoothGatt.GATT_SUCCESS -> {
                listener.onWriteSuccess()
                completeOp()
            }
            // One silent retry, only for the bond-complete-before-encryption
            // race, only after the bond receiver observed BOND_BONDED this
            // session (§3). The op stays in flight across the retry.
            insufficient && newlyBondedThisSession && !writeRetried -> {
                writeRetried = true
                val chr = triggerChar
                val cur = gatt
                if (chr != null && cur != null && issueWrite(cur, chr, TRIGGER_PAYLOAD)) return
                listener.onWriteFailed(status, insufficientAuth = true)
                completeOp()
            }
            else -> {
                listener.onWriteFailed(status, insufficientAuth = insufficient)
                completeOp()
            }
        }
    }

    private fun handleRead(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        if (gatt !== g || characteristic.uuid != BOND_LIST_UUID) return
        if (status == BluetoothGatt.GATT_SUCCESS) {
            val list = parseBondList(value)
            if (list != null) listener.onBondList(list) else listener.onBondListFailed(status)
        } else {
            listener.onBondListFailed(status)
        }
        completeOp()
    }

    private fun onLinkReady(newlyBonded: Boolean) {
        phase = Phase.CONNECTED
        device?.let { AppPrefs.setDeviceMac(context, it.address) }
        if (newlyBonded && mgmtChar != null) {
            // Register this phone's name so it shows up usefully in every
            // phone's paired-device list.
            enqueueOp(GattOp.MgmtWrite(MgmtOp.SET_NAME, byteArrayOf(OP_SET_NAME) + localNameBytes()))
        }
        listener.onReady(newlyBonded, hasManagement = mgmtChar != null && bondListChar != null)
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
        mgmtChar = null
        bondListChar = null
        clearOps()
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
