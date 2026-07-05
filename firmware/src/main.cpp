// Garage remote trigger — Seeed XIAO ESP32-C6.
//
// BLE GATT server with one writable characteristic. Any write on the
// encrypted bonded link pulses TRIGGER_PIN, which drives the optocoupler
// LED that "presses" the garage remote's button.
//
// Security model (see README): the encrypted bond IS the credential.
//   "Just Works" pairing, and exactly ONE device may ever bond — the first
//   phone to pair becomes the owner; pairing attempts from any other device
//   are rejected and their bonds deleted. NimBLE persists the bond keys in
//   NVS, so ownership survives power loss on both ends.
//
// Factory reset (new phone): erase and re-flash over USB —
// `pio run -t erase` then `pio run -t upload` — which wipes the bond;
// the next device to pair becomes the owner. Optionally, a momentary
// button wired from GPIO9 to GND (not populated on all boards) held ~3s
// at runtime does the same without a computer.

#include <Arduino.h>
#include <NimBLEDevice.h>

static const char* DEVICE_NAME = "GarageRemote";
static const char* SERVICE_UUID = "4090b92d-a8da-471a-85a8-aee612b68bad";
static const char* TRIGGER_CHAR_UUID = "588a322e-4b88-4197-8f4e-a5f48417c8b7";

static const uint8_t TRIGGER_PIN = D0;
// Optional factory-reset button (GPIO9 to GND, active low). Harmless when
// absent: INPUT_PULLUP keeps the pin high.
static const uint8_t RESET_BUTTON_PIN = 9;
static const uint32_t PULSE_MS = 400;         // how long the "button" is held
static const uint32_t COOLDOWN_MS = 2000;     // min gap between pulses
static const uint32_t RESET_HOLD_MS = 3000;

static NimBLEServer* server = nullptr;

// Set from the BLE callback, consumed in loop() so the radio task never
// blocks on the 400ms pulse.
static volatile bool triggerRequested = false;
static uint32_t lastPulseAt = 0;

// Identity addresses of bonded peers. At most one entry; enforced in
// onAuthenticationComplete.
static std::vector<NimBLEAddress> knownBonds;

static bool isKnownBond(const NimBLEAddress& addr) {
    for (const auto& known : knownBonds) {
        if (known == addr) return true;
    }
    return false;
}

static void factoryReset() {
    Serial.println("Factory reset: erasing bond");
    NimBLEDevice::deleteAllBonds();
    knownBonds.clear();
    for (int i = 0; i < 6; i++) {  // acknowledge with a blink
        digitalWrite(LED_BUILTIN, LOW);
        delay(150);
        digitalWrite(LED_BUILTIN, HIGH);
        delay(150);
    }
}

class ServerCallbacks : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer*, NimBLEConnInfo& connInfo) override {
        Serial.printf("Connected: %s\n", connInfo.getAddress().toString().c_str());
    }

    void onDisconnect(NimBLEServer*, NimBLEConnInfo& connInfo, int reason) override {
        Serial.printf("Disconnected: %s (reason %d)\n",
                      connInfo.getAddress().toString().c_str(), reason);
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
            return;
        }

        // New bond: only allowed while the owner slot is empty.
        if (!knownBonds.empty()) {
            Serial.printf("Rejecting second device %s (already bonded to one)\n",
                          peer.toString().c_str());
            server->disconnect(connInfo.getConnHandle());
            NimBLEDevice::deleteBond(peer);
            return;
        }

        knownBonds.push_back(peer);
        Serial.printf("Bonded to owner: %s\n", peer.toString().c_str());
    }
};

class TriggerCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic*, NimBLEConnInfo& connInfo) override {
        // WRITE_ENC already gates this at the ATT layer, and only the owner
        // can ever hold a bond; re-check anyway so a stack quirk can't turn
        // an unencrypted write into a door pulse.
        if (!connInfo.isEncrypted() || !connInfo.isBonded() ||
            !isKnownBond(connInfo.getIdAddress())) {
            Serial.println("Write on unauthorized link; dropping");
            server->disconnect(connInfo.getConnHandle());
            return;
        }
        triggerRequested = true;
    }
};

void setup() {
    // Make the output state deterministic before anything else runs.
    pinMode(TRIGGER_PIN, OUTPUT);
    digitalWrite(TRIGGER_PIN, LOW);

    pinMode(LED_BUILTIN, OUTPUT);
    digitalWrite(LED_BUILTIN, HIGH);  // XIAO user LED is active-low
    pinMode(RESET_BUTTON_PIN, INPUT_PULLUP);

    Serial.begin(115200);

    NimBLEDevice::init(DEVICE_NAME);
    // Just Works pairing: bonding + LE Secure Connections, no passkey.
    NimBLEDevice::setSecurityAuth(/*bonding=*/true, /*mitm=*/false, /*sc=*/true);
    NimBLEDevice::setSecurityIOCap(BLE_HS_IO_NO_INPUT_OUTPUT);

    for (int i = 0; i < NimBLEDevice::getNumBonds(); i++) {
        knownBonds.push_back(NimBLEDevice::getBondedAddress(i));
    }

    server = NimBLEDevice::createServer();
    server->setCallbacks(new ServerCallbacks());
    server->advertiseOnDisconnect(true);

    NimBLEService* service = server->createService(SERVICE_UUID);
    NimBLECharacteristic* trigger = service->createCharacteristic(
        TRIGGER_CHAR_UUID,
        NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_ENC);
    trigger->setCallbacks(new TriggerCallbacks());
    service->start();

    NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
    adv->addServiceUUID(SERVICE_UUID);
    adv->enableScanResponse(true);  // name won't fit next to a 128-bit UUID
    adv->setName(DEVICE_NAME);
    adv->start();

    Serial.printf("Advertising as %s; bonds=%d\n", DEVICE_NAME,
                  (int)knownBonds.size());
}

// Millis timestamp when the optional reset button was first seen held;
// 0 = not held.
static uint32_t buttonHeldSince = 0;

void loop() {
    if (digitalRead(RESET_BUTTON_PIN) == LOW) {
        uint32_t now = millis();
        if (buttonHeldSince == 0) {
            buttonHeldSince = now;
        } else if (now - buttonHeldSince >= RESET_HOLD_MS) {
            factoryReset();
            buttonHeldSince = 0;
        }
    } else {
        buttonHeldSince = 0;
    }

    if (triggerRequested) {
        triggerRequested = false;
        uint32_t now = millis();
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
