# blue-remote

Open the garage from a phone. A Seeed XIAO ESP32-C6 drives an optocoupler
soldered across the button of an existing garage remote; an Android app
triggers it over Bluetooth Low Energy.

```
blue-remote/
├── firmware/   PlatformIO project for the XIAO ESP32-C6
└── android/    Android app (Kotlin + Compose)
```

## User flows

**First-time setup**
1. Flash the board and power it near the phone.
2. Install the app; grant Bluetooth permissions on first launch.
3. Choose a PIN (6+ digits) and save it.
4. The app scans and connects; confirm Android's pairing dialog.
5. Press the big button — the first press provisions the PIN into the
   board and pulses the door.

**Daily use**
1. Open the app; it auto-connects to the bonded board.
2. Press the button; the door triggers (2s firmware cooldown between
   presses).

**Reconnecting**
- If the board is off or out of range, the scan times out after 15s and
  shows a status message; tap Connect to retry.

**Change PIN**
- Main screen → Change PIN. Only meaningful for an unprovisioned (or
  factory-reset) board — a provisioned board answers only to the PIN it
  was provisioned with.

**Switch phones / factory reset**
1. With the board running, hold BOOT ~3s until the LED blinks (erases
   bond + stored secret).
2. Remove the pairing from the old phone's Bluetooth settings.
3. On the new phone: install the app, enter the same (or a new) PIN,
   connect, and press.

## Wiring

```
XIAO D0 (GPIO0) ──[ 220Ω ]── optocoupler LED anode (pin 1)
XIAO GND ─────────────────── optocoupler LED cathode (pin 2)

optocoupler output (pins 4/3, e.g. collector/emitter on a PC817)
    └── soldered across the garage remote's button pads
        (collector to the button's + side, emitter to its − side)
```

- 220Ω from a 3.3V pin gives ~10mA through the LED — comfortably within
  spec for a PC817-class part and plenty to switch a remote button.
- If the remote doesn't respond, the output side is probably reversed —
  swap collector/emitter.
- D0 on the ESP32-C6 is **not** a strapping pin, so the optocoupler load
  can't interfere with boot.

Firmware behavior: on a valid trigger, D0 goes high for 400ms ("button
press"), then low. Repeat triggers are ignored for 2s. The pin is driven
low first thing at boot. The onboard yellow LED lights during the pulse.

## Security model

Trust on first use — the first phone to connect becomes the owner:

1. **Single bonded, encrypted link** — "Just Works" BLE pairing (no PIN).
   The firmware accepts exactly **one** bonded device; pairing attempts
   from any other device are rejected and their bonds deleted. The trigger
   characteristic rejects writes on unencrypted links.
2. **PIN-derived secret** — on first launch the app asks you to choose a
   PIN and derives a 16-byte secret from it (SHA-256, truncated). The
   firmware stores the first secret it ever receives in flash; from then
   on every write must match it or the connection is dropped.

The trade-off vs. passkey pairing: there is no MITM protection during the
very first pairing, and whoever connects first owns the device. Do the
first connection at home with the board freshly powered, and both windows
close permanently.

### Switching phones / factory reset

With the board **powered and running**, hold the **BOOT button** (the
tiny "B" button) ~3s until the yellow LED blinks. This erases the bond
and the stored secret. Don't hold it while plugging in power — BOOT is a
strapping pin, and holding it through power-up puts the chip in the
serial bootloader instead of running the firmware.
On the new phone, enter the **same PIN** (or a new one — the board is
fresh either way) and connect; it becomes the new owner. Also remove the
old pairing from the previous phone's Bluetooth settings.

Changing the PIN in the app ("Change PIN") only helps *before* the board
is provisioned — after that, the board expects the original PIN, so a PIN
change must be paired with a factory reset of the board.

## Firmware: build & flash

```sh
cd firmware
pio run                 # build
pio run -t upload       # flash (XIAO connected over USB-C)
pio device monitor      # serial log at 115200 baud
```

Uses the [pioarduino](https://github.com/pioarduino/platform-espressif32)
platform fork because the ESP32-C6 needs Arduino core 3.x.

## Android app: build & install

```sh
cd android
gradle assembleDebug    # or open in Android Studio
adb install app/build/outputs/apk/debug/app-debug.apk
```

## First-time pairing

1. Power the XIAO.
2. Open the app, grant the Bluetooth permissions, and choose a PIN
   (6+ digits) when prompted.
3. The app scans, connects, and starts pairing; confirm Android's pairing
   dialog. Your phone is now the only device that can bond.
4. Status becomes "Paired and connected" — the big button is live. The
   first press provisions your PIN's secret into the firmware (and opens
   the door).

Afterwards it reconnects automatically on app launch.

## Trigger protocol (for reference)

BLE service `4090b92d-a8da-471a-85a8-aee612b68bad`, characteristic
`588a322e-4b88-4197-8f4e-a5f48417c8b7` (write, encrypted bonded link
required). Write a 16-byte secret (the app uses the first 16 bytes of
SHA-256 over the PIN string): the first one ever received is stored as
THE secret and honored; afterwards, any write that doesn't match it
disconnects you.
