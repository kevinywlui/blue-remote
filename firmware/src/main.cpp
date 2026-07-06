// Garage remote trigger — Seeed XIAO ESP32-C6.
//
// BLE GATT server with three characteristics:
//   - trigger:   any write on an authorized link pulses TRIGGER_PIN, which
//                drives the optocoupler LED that "presses" the garage
//                remote's button.
//   - management (write): opcode 0x01 opens a 30s pairing window;
//                0x02 + 6-byte address + 1-byte type unpairs that phone;
//                0x03 + up to 24 bytes of UTF-8 names the calling phone.
//   - bond list (read): the current allowlist — per entry a 6-byte address
//                (display order), address type, flags (bit0 = the reading
//                phone itself), and the registered name.
//
// Security model (see README): the encrypted bond IS the credential.
//   "Just Works" pairing; the board keeps an allowlist of up to MAX_BONDS
//   phones. A new bond is accepted only while the pairing window is open
//   (exception: a board with zero bonds accepts its first phone directly,
//   so first-time setup needs no window). The window is opened by a short
//   press of the onboard BOOT button or by a bonded phone writing 0x01 to
//   the management characteristic; it closes after one successful bond or
//   30 seconds. Outside the window, pairing attempts are rejected and
//   their bonds deleted. Connections that never authenticate are dropped
//   after UNAUTH_TIMEOUT_MS so strangers can't squat connection slots.
//
// Factory reset (wipes ALL bonds and names): hold the BOOT button ~3s
// while the board runs (the LED blinks to confirm), or erase and re-flash
// over USB — `pio run -t erase` then `pio run -t upload`.
//
// LED legend: solid 400ms = trigger pulse; slow blink = pairing window
// open; rapid blinking = factory reset; triple fast blink = pairing window
// refused (allowlist full).

#include <Arduino.h>
#include <NimBLEDevice.h>
#include <Preferences.h>

static const char* DEVICE_NAME = "GarageRemote";
static const char* SERVICE_UUID = "4090b92d-a8da-471a-85a8-aee612b68bad";
static const char* TRIGGER_CHAR_UUID = "588a322e-4b88-4197-8f4e-a5f48417c8b7";
static const char* MGMT_CHAR_UUID = "f34dd3a3-ed37-4822-9d08-f96ee2974856";
static const char* BOND_LIST_CHAR_UUID = "8d3a66f6-42a0-4d0b-8a56-3a9f2f8e5c01";

static const uint8_t TRIGGER_PIN = D0;
// Factory-reset / pairing button: the XIAO's onboard BOOT button (GPIO9 to
// GND, active low). Short press = open the pairing window; held ~3s =
// factory reset. Not to be confused with the RST button next to it, which
// just reboots.
static const uint8_t RESET_BUTTON_PIN = 9;
static const uint32_t PULSE_MS = 400;          // how long the "button" is held
static const uint32_t COOLDOWN_MS = 2000;      // min gap between pulses
static const uint32_t RESET_HOLD_MS = 3000;
static const uint32_t BUTTON_DEBOUNCE_MS = 50; // short-press lower bound
static const uint32_t PAIRING_WINDOW_MS = 30000;
// Longer than the pairing window so a phone that connects as the window
// opens is never cut off mid-consent-dialog; the window-close sweep evicts
// unpaired window connections earlier anyway.
static const uint32_t UNAUTH_TIMEOUT_MS = 35000;
// Must not exceed MYNEWT_VAL_BLE_STORE_MAX_BONDS (platformio.ini).
static const size_t MAX_BONDS = 3;
static const size_t MAX_CONNECTIONS = 3;  // == MYNEWT_VAL_BLE_MAX_CONNECTIONS
static const size_t MAX_NAME_LEN = 24;

// Management opcodes.
static const uint8_t OP_OPEN_WINDOW = 0x01;
static const uint8_t OP_UNPAIR = 0x02;
static const uint8_t OP_SET_NAME = 0x03;

static NimBLEServer* server = nullptr;
static Preferences namesPrefs;  // identity-address hex -> registered name

// Set from BLE callbacks, consumed in loop() so the radio task never blocks
// on the 400ms pulse or on NVS writes.
static volatile bool triggerRequested = false;
static volatile bool pairWindowOpenRequested = false;
static uint32_t lastPulseAt = 0;

// Pairing window. Written by loop() (open/expiry) and by the host task
// (close-on-first-bond in onAuthenticationComplete); read by both.
// Single-byte stores are atomic here — same convention as triggerRequested.
static volatile bool pairWindowOpen = false;
static uint32_t pairWindowOpenedAt = 0;  // loop-owned

// Identity addresses of bonded peers (the allowlist), capped at MAX_BONDS.
// Mutated on the NimBLE host task (bond acceptance) AND in loop() (unpair,
// factory reset), and read from both — every access goes through bondsMux.
// setup() reserves MAX_BONDS so push_back never allocates inside a critical
// section; erase/clear don't allocate either.
static std::vector<NimBLEAddress> knownBonds;
static portMUX_TYPE bondsMux = portMUX_INITIALIZER_UNLOCKED;

// Management ops that need NVS or multi-step teardown run in loop(), not on
// the NimBLE host task. Fixed ring; overflow drops the op (logged).
struct PendingOp {
    uint8_t op;
    uint8_t addr[7];  // 6B display-order address + 1B type
    uint8_t nameLen;
    char name[MAX_NAME_LEN];
};
static const size_t OP_QUEUE_LEN = 4;
static PendingOp opQueue[OP_QUEUE_LEN];
static volatile size_t opHead = 0;  // next slot to write (host task)
static volatile size_t opTail = 0;  // next slot to read (loop)
static portMUX_TYPE opMux = portMUX_INITIALIZER_UNLOCKED;

static bool enqueueOp(const PendingOp& op) {
    bool ok = false;
    portENTER_CRITICAL(&opMux);
    size_t next = (opHead + 1) % OP_QUEUE_LEN;
    if (next != opTail) {
        opQueue[opHead] = op;
        opHead = next;
        ok = true;
    }
    portEXIT_CRITICAL(&opMux);
    return ok;
}

static bool dequeueOp(PendingOp& out) {
    bool ok = false;
    portENTER_CRITICAL(&opMux);
    if (opTail != opHead) {
        out = opQueue[opTail];
        opTail = (opTail + 1) % OP_QUEUE_LEN;
        ok = true;
    }
    portEXIT_CRITICAL(&opMux);
    return ok;
}

// Connections that never reach an encrypted bonded state are dropped after
// UNAUTH_TIMEOUT_MS. Slots keyed by connection handle; written from the
// host task (connect/auth/disconnect) and read/expired from loop(), so
// accesses share bondsMux.
struct PeerAuth {
    bool used;
    bool authed;
    uint16_t handle;
    uint32_t since;
};
static PeerAuth authTable[MAX_CONNECTIONS];

static void authTableAdd(uint16_t handle) {
    portENTER_CRITICAL(&bondsMux);
    for (auto& slot : authTable) {
        if (!slot.used) {
            slot = {true, false, handle, millis()};
            break;
        }
    }
    portEXIT_CRITICAL(&bondsMux);
}

static void authTableMark(uint16_t handle) {
    portENTER_CRITICAL(&bondsMux);
    for (auto& slot : authTable) {
        if (slot.used && slot.handle == handle) slot.authed = true;
    }
    portEXIT_CRITICAL(&bondsMux);
}

static void authTableRemove(uint16_t handle) {
    portENTER_CRITICAL(&bondsMux);
    for (auto& slot : authTable) {
        if (slot.used && slot.handle == handle) slot.used = false;
    }
    portEXIT_CRITICAL(&bondsMux);
}

static bool isKnownBond(const NimBLEAddress& addr) {
    bool known = false;
    portENTER_CRITICAL(&bondsMux);
    for (const auto& bond : knownBonds) {
        if (bond == addr) {
            known = true;
            break;
        }
    }
    portEXIT_CRITICAL(&bondsMux);
    return known;
}

static size_t bondCount() {
    portENTER_CRITICAL(&bondsMux);
    size_t n = knownBonds.size();
    portEXIT_CRITICAL(&bondsMux);
    return n;
}

/** Copy the allowlist into [out] (sized MAX_BONDS); no heap work under the lock. */
static size_t bondsSnapshot(NimBLEAddress out[]) {
    portENTER_CRITICAL(&bondsMux);
    size_t n = knownBonds.size();
    for (size_t i = 0; i < n; i++) out[i] = knownBonds[i];
    portEXIT_CRITICAL(&bondsMux);
    return n;
}

// NVS key: the identity address as 12 hex chars (NVS keys max 15 chars).
static String nameKey(const NimBLEAddress& addr) {
    std::string s = addr.toString();
    String key;
    for (char c : s) {
        if (c != ':') key += c;
    }
    return key;
}

static void blinkLed(int times, uint32_t periodMs) {
    for (int i = 0; i < times; i++) {
        digitalWrite(LED_BUILTIN, LOW);
        delay(periodMs / 2);
        digitalWrite(LED_BUILTIN, HIGH);
        delay(periodMs / 2);
    }
}

static void factoryReset() {
    Serial.println("Factory reset: erasing all bonds and names");
    NimBLEDevice::deleteAllBonds();
    portENTER_CRITICAL(&bondsMux);
    knownBonds.clear();
    portEXIT_CRITICAL(&bondsMux);
    namesPrefs.clear();
    pairWindowOpen = false;
    // Formerly bonded phones must not keep a live session on wiped keys.
    for (int i = server->getConnectedCount() - 1; i >= 0; i--) {
        NimBLEConnInfo peer = server->getPeerInfo(i);
        if (peer.getAddress().isNull()) continue;  // raced with a disconnect
        server->disconnect(peer.getConnHandle());
    }
    blinkLed(6, 300);  // acknowledge
}

class ServerCallbacks : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer*, NimBLEConnInfo& connInfo) override {
        Serial.printf("Connected: %s\n", connInfo.getAddress().toString().c_str());
        authTableAdd(connInfo.getConnHandle());
    }

    void onDisconnect(NimBLEServer*, NimBLEConnInfo& connInfo, int reason) override {
        Serial.printf("Disconnected: %s (reason %d)\n",
                      connInfo.getAddress().toString().c_str(), reason);
        authTableRemove(connInfo.getConnHandle());
    }

    void onAuthenticationComplete(NimBLEConnInfo& connInfo) override {
        if (!connInfo.isEncrypted() || !connInfo.isBonded()) {
            Serial.println("Auth finished without a bonded encrypted link; dropping");
            server->disconnect(connInfo.getConnHandle());
            return;
        }

        NimBLEAddress peer = connInfo.getIdAddress();
        if (isKnownBond(peer)) {
            Serial.printf("Authenticated: %s\n", peer.toString().c_str());
            authTableMark(connInfo.getConnHandle());
            return;
        }

        // New bond: allowed while the allowlist is empty (first-time setup)
        // or the pairing window is open, and only below the cap.
        bool accepted = false;
        size_t total = 0;
        portENTER_CRITICAL(&bondsMux);
        if ((knownBonds.empty() || pairWindowOpen) && knownBonds.size() < MAX_BONDS) {
            knownBonds.push_back(peer);  // capacity reserved: no alloc here
            accepted = true;
        }
        total = knownBonds.size();
        portEXIT_CRITICAL(&bondsMux);
        if (accepted) {
            pairWindowOpen = false;  // one new phone per window
            authTableMark(connInfo.getConnHandle());
            Serial.printf("Bonded: %s (%d/%d)\n", peer.toString().c_str(),
                          (int)total, (int)MAX_BONDS);
            return;
        }

        Serial.printf("Rejecting %s (window closed or allowlist full)\n",
                      peer.toString().c_str());
        server->disconnect(connInfo.getConnHandle());
        // A single NVS erase on the host task — same as NimBLE's own store
        // writes during bonding; not worth routing through the op ring.
        NimBLEDevice::deleteBond(peer);
    }
};

// Shared guard for the trigger and management characteristics: the write
// must arrive on an encrypted bonded link from an allowlisted phone.
// WRITE_ENC already gates encryption at the ATT layer; re-check anyway so a
// stack quirk can't turn an unauthorized write into a door pulse.
static bool writeAuthorized(NimBLEConnInfo& connInfo) {
    if (connInfo.isEncrypted() && connInfo.isBonded() &&
        isKnownBond(connInfo.getIdAddress())) {
        return true;
    }
    Serial.println("Write on unauthorized link; dropping");
    server->disconnect(connInfo.getConnHandle());
    return false;
}

class TriggerCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic*, NimBLEConnInfo& connInfo) override {
        if (!writeAuthorized(connInfo)) return;
        triggerRequested = true;
    }
};

class MgmtCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* chr, NimBLEConnInfo& connInfo) override {
        if (!writeAuthorized(connInfo)) return;
        NimBLEAttValue value = chr->getValue();
        // Malformed payloads from a bonded phone are logged and ignored —
        // don't drop an owner over an app bug.
        if (value.size() < 1) {
            Serial.println("Mgmt write: empty; ignored");
            return;
        }
        switch (value[0]) {
            case OP_OPEN_WINDOW:
                if (value.size() == 1) {
                    pairWindowOpenRequested = true;
                } else {
                    Serial.println("Mgmt write: bad open-window length; ignored");
                }
                break;
            case OP_UNPAIR: {
                if (value.size() != 8) {
                    Serial.println("Mgmt write: bad unpair length; ignored");
                    break;
                }
                PendingOp op = {};
                op.op = OP_UNPAIR;
                memcpy(op.addr, value.data() + 1, 7);
                if (!enqueueOp(op)) Serial.println("Mgmt op queue full; dropped");
                break;
            }
            case OP_SET_NAME: {
                if (value.size() > 1 + MAX_NAME_LEN) {
                    Serial.println("Mgmt write: name too long; ignored");
                    break;
                }
                PendingOp op = {};
                op.op = OP_SET_NAME;
                // The name applies to the CALLER; capture its identity
                // address (display order + type) for the loop.
                NimBLEAddress peer = connInfo.getIdAddress();
                for (int i = 0; i < 6; i++) op.addr[i] = peer.getBase()->val[5 - i];
                op.addr[6] = peer.getBase()->type;
                op.nameLen = value.size() - 1;
                memcpy(op.name, value.data() + 1, op.nameLen);
                if (!enqueueOp(op)) Serial.println("Mgmt op queue full; dropped");
                break;
            }
            default:
                Serial.printf("Mgmt write: unknown opcode 0x%02x; ignored\n", value[0]);
        }
    }
};

class BondListCallbacks : public NimBLECharacteristicCallbacks {
    void onRead(NimBLECharacteristic* chr, NimBLEConnInfo& connInfo) override {
        // Serve the real list only to allowlisted phones; others get an
        // empty (but well-formed) list. Known limitation: the serialized
        // value persists in the characteristic for ATT blob continuations,
        // so an encrypted peer in the brief rejected-but-not-yet-dropped
        // state issuing a first read at offset>0 could see the previous
        // reader's list — addresses and names only, no keys.
        uint8_t buf[2 + MAX_BONDS * (9 + MAX_NAME_LEN)];
        buf[0] = 1;  // format version
        buf[1] = 0;
        size_t len = 2;
        if (connInfo.isEncrypted() && connInfo.isBonded() &&
            isKnownBond(connInfo.getIdAddress())) {
            NimBLEAddress self = connInfo.getIdAddress();
            NimBLEAddress bonds[MAX_BONDS];
            size_t count = bondsSnapshot(bonds);  // NVS reads stay unlocked
            buf[1] = (uint8_t)count;
            for (size_t b = 0; b < count; b++) {
                const NimBLEAddress& bond = bonds[b];
                for (int i = 0; i < 6; i++) buf[len + i] = bond.getBase()->val[5 - i];
                buf[len + 6] = bond.getBase()->type;
                buf[len + 7] = (bond == self) ? 0x01 : 0x00;
                String name = namesPrefs.getString(nameKey(bond).c_str(), "");
                uint8_t nameLen = min((size_t)name.length(), MAX_NAME_LEN);
                buf[len + 8] = nameLen;
                memcpy(buf + len + 9, name.c_str(), nameLen);
                len += 9 + nameLen;
            }
        }
        chr->setValue(buf, len);
    }
};

static void executeUnpair(const uint8_t* entry) {  // 6B display-order + 1B type
    NimBLEAddress target(entry, entry[6]);
    bool found = false;
    portENTER_CRITICAL(&bondsMux);
    for (auto it = knownBonds.begin(); it != knownBonds.end(); ++it) {
        if (*it == target) {
            knownBonds.erase(it);  // shift only, no alloc
            found = true;
            break;
        }
    }
    portEXIT_CRITICAL(&bondsMux);
    if (!found) {
        Serial.printf("Unpair: %s not in allowlist; ignored\n", target.toString().c_str());
        return;
    }
    NimBLEDevice::deleteBond(target);
    namesPrefs.remove(nameKey(target).c_str());
    for (int i = server->getConnectedCount() - 1; i >= 0; i--) {
        NimBLEConnInfo peer = server->getPeerInfo(i);
        if (peer.getAddress().isNull()) continue;  // raced with a disconnect
        if (peer.getIdAddress() == target) server->disconnect(peer.getConnHandle());
    }
    Serial.printf("Unpaired %s (%d bonds left)\n", target.toString().c_str(),
                  (int)bondCount());
}

static void executeSetName(const PendingOp& op) {
    NimBLEAddress target(op.addr, op.addr[6]);
    if (!isKnownBond(target)) return;  // unpaired while queued
    String key = nameKey(target);
    if (op.nameLen == 0) {
        namesPrefs.remove(key.c_str());
    } else {
        char name[MAX_NAME_LEN + 1];
        memcpy(name, op.name, op.nameLen);
        name[op.nameLen] = '\0';
        namesPrefs.putString(key.c_str(), name);
        Serial.printf("Named %s \"%s\"\n", target.toString().c_str(), name);
    }
}

void setup() {
    // Make the output state deterministic before anything else runs.
    pinMode(TRIGGER_PIN, OUTPUT);
    digitalWrite(TRIGGER_PIN, LOW);

    pinMode(LED_BUILTIN, OUTPUT);
    digitalWrite(LED_BUILTIN, HIGH);  // XIAO user LED is active-low
    pinMode(RESET_BUTTON_PIN, INPUT_PULLUP);

    Serial.begin(115200);

    namesPrefs.begin("grnames", false);

    NimBLEDevice::init(DEVICE_NAME);
    // Just Works pairing: bonding + LE Secure Connections, no passkey.
    NimBLEDevice::setSecurityAuth(/*bonding=*/true, /*mitm=*/false, /*sc=*/true);
    NimBLEDevice::setSecurityIOCap(BLE_HS_IO_NO_INPUT_OUTPUT);
    // NimBLE's default store-status handler resolves a FULL bond store by
    // evicting the oldest bond — which would let a stranger pairing outside
    // the window silently de-key a legitimate phone before our post-auth
    // rejection runs. The store fills only when the allowlist is full, so
    // the right answer is to refuse: the extra pairing fails, real bonds
    // stay intact.
    ble_hs_cfg.store_status_cb = [](struct ble_store_status_event* event, void*) -> int {
        if (event->event_code == BLE_STORE_EVENT_FULL ||
            event->event_code == BLE_STORE_EVENT_OVERFLOW) {
            Serial.println("Bond store full; refusing eviction");
            return BLE_HS_EUNKNOWN;
        }
        return 0;
    };

    knownBonds.reserve(MAX_BONDS);  // push_back must never allocate under bondsMux
    for (int i = 0; i < NimBLEDevice::getNumBonds(); i++) {
        knownBonds.push_back(NimBLEDevice::getBondedAddress(i));
    }

    server = NimBLEDevice::createServer();
    server->setCallbacks(new ServerCallbacks());
    // Advertising is reconciled in loop() (we advertise while a free
    // connection slot exists), not tied to disconnects.
    server->advertiseOnDisconnect(false);

    NimBLEService* service = server->createService(SERVICE_UUID);
    NimBLECharacteristic* trigger = service->createCharacteristic(
        TRIGGER_CHAR_UUID,
        NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_ENC);
    trigger->setCallbacks(new TriggerCallbacks());
    NimBLECharacteristic* mgmt = service->createCharacteristic(
        MGMT_CHAR_UUID,
        NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_ENC);
    mgmt->setCallbacks(new MgmtCallbacks());
    NimBLECharacteristic* bondList = service->createCharacteristic(
        BOND_LIST_CHAR_UUID,
        NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::READ_ENC);
    bondList->setCallbacks(new BondListCallbacks());
    service->start();

    NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
    adv->addServiceUUID(SERVICE_UUID);
    adv->enableScanResponse(true);  // name won't fit next to a 128-bit UUID
    adv->setName(DEVICE_NAME);
    adv->start();

    Serial.printf("Advertising as %s; bonds=%d\n", DEVICE_NAME,
                  (int)bondCount());
}

// BOOT button state: millis timestamp when first seen held (0 = not held),
// plus a latch so a 3s hold factory-resets once and its release doesn't
// count as a short press.
static uint32_t buttonHeldSince = 0;
static bool resetFiredThisHold = false;
static bool prevWindowOpen = false;

void loop() {
    uint32_t now = millis();

    // --- BOOT button: short press = pairing window, 3s hold = factory reset
    if (digitalRead(RESET_BUTTON_PIN) == LOW) {
        if (buttonHeldSince == 0) {
            buttonHeldSince = now;
        } else if (!resetFiredThisHold && now - buttonHeldSince >= RESET_HOLD_MS) {
            factoryReset();
            resetFiredThisHold = true;  // once per hold
        }
    } else {
        if (buttonHeldSince != 0 && !resetFiredThisHold &&
            now - buttonHeldSince >= BUTTON_DEBOUNCE_MS) {
            pairWindowOpenRequested = true;
        }
        buttonHeldSince = 0;
        resetFiredThisHold = false;
    }

    // --- Drain management ops (NVS writes / teardown off the host task)
    PendingOp op;
    while (dequeueOp(op)) {
        if (op.op == OP_UNPAIR) executeUnpair(op.addr);
        else if (op.op == OP_SET_NAME) executeSetName(op);
    }

    // --- Pairing window lifecycle
    if (pairWindowOpenRequested) {
        pairWindowOpenRequested = false;
        if (bondCount() >= MAX_BONDS) {
            Serial.println("Pairing window refused: allowlist full");
            blinkLed(3, 200);
        } else {
            pairWindowOpen = true;
            pairWindowOpenedAt = now;  // re-open restarts the 30s
            Serial.println("Pairing window open (30s)");
        }
    }
    if (pairWindowOpen && now - pairWindowOpenedAt >= PAIRING_WINDOW_MS) {
        pairWindowOpen = false;
        Serial.println("Pairing window timed out");
    }
    if (pairWindowOpen) {
        // Slow blink while open (LED is active-low).
        digitalWrite(LED_BUILTIN, ((now - pairWindowOpenedAt) % 500 < 250) ? LOW : HIGH);
    } else if (prevWindowOpen) {
        digitalWrite(LED_BUILTIN, HIGH);
        // Evict peers that connected during the window but never paired.
        for (int i = server->getConnectedCount() - 1; i >= 0; i--) {
            NimBLEConnInfo peer = server->getPeerInfo(i);
            if (peer.getAddress().isNull()) continue;  // raced with a disconnect
            if (!isKnownBond(peer.getIdAddress())) server->disconnect(peer.getConnHandle());
        }
    }
    prevWindowOpen = pairWindowOpen;

    // --- Unauthenticated-peer watchdog. Collect stale handles under the
    // lock, disconnect outside it (host calls must not run in a critical
    // section).
    uint16_t staleHandles[MAX_CONNECTIONS];
    size_t staleCount = 0;
    portENTER_CRITICAL(&bondsMux);
    for (auto& slot : authTable) {
        if (slot.used && !slot.authed && now - slot.since >= UNAUTH_TIMEOUT_MS) {
            staleHandles[staleCount++] = slot.handle;
            slot.used = false;  // onDisconnect would also clear it
        }
    }
    portEXIT_CRITICAL(&bondsMux);
    for (size_t i = 0; i < staleCount; i++) {
        Serial.printf("Dropping unauthenticated connection (handle %d)\n", staleHandles[i]);
        server->disconnect(staleHandles[i]);
    }

    // --- Advertising reconciler: advertise while a free slot exists
    NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
    bool wantAdv = server->getConnectedCount() < MAX_CONNECTIONS;
    if (wantAdv && !adv->isAdvertising()) adv->start();
    else if (!wantAdv && adv->isAdvertising()) adv->stop();

    // --- Trigger pulse
    if (triggerRequested) {
        triggerRequested = false;
        if (now - lastPulseAt >= COOLDOWN_MS) {
            lastPulseAt = now;
            Serial.println("Pulsing trigger");
            digitalWrite(LED_BUILTIN, LOW);
            digitalWrite(TRIGGER_PIN, HIGH);
            delay(PULSE_MS);
            digitalWrite(TRIGGER_PIN, LOW);
            digitalWrite(LED_BUILTIN, HIGH);
        } else {
            Serial.println("Trigger ignored (cooldown)");
        }
    }
    delay(10);
}
