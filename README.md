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
3. The app scans and connects; confirm Android's pairing dialog. Your
   phone is now the only device that can ever pair with the board.
4. Press the big button — the door triggers.

**Daily use**
1. Open the app; it auto-connects to the bonded board.
2. Press the button; the door triggers (2s firmware cooldown between
   presses).

**Reconnecting**
- If the board is off or out of range, the scan times out after 15s and
  shows a status message; tap Connect to retry.

**Switch phones / factory reset**
1. Hold the board's BOOT button ~3s while it's running — the LED blinks
   to confirm the bond is wiped. (Not the RST button, which just
   reboots. USB alternative: `pio run -t erase`, then
   `pio run -t upload` in `firmware/`.)
2. Remove the pairing from the old phone's Bluetooth settings.
3. On the new phone: install the app and connect — it becomes the new
   owner.

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

Trust on first use — the first phone to pair becomes the owner, and the
bond IS the credential:

- **Single bonded, encrypted link** — "Just Works" BLE pairing (no PIN).
  The firmware accepts exactly **one** bonded device; pairing attempts
  from any other device are rejected and their bonds deleted. The trigger
  characteristic rejects writes on unencrypted links, so only the bonded
  phone can trigger the door.
- **Persistence** — the bond keys live in the board's flash (NimBLE/NVS)
  and in Android's Bluetooth stack, so ownership survives reboots and
  power loss on both ends. The app also remembers the board's MAC for a
  fast direct reconnect on launch.

The trade-off vs. passkey pairing: there is no MITM protection during the
very first pairing, and whoever pairs first owns the device. Do the first
connection at home with the board freshly powered, and the window closes
permanently.

### Switching phones / factory reset

Hold the XIAO's onboard BOOT button (wired to GPIO9) for ~3s while the
board is running; the LED blinks to confirm the bond is wiped. Don't
confuse it with the RST button next to it — RST just reboots and leaves
the bond intact.

No-button alternative, over USB:

```sh
cd firmware
pio run -t erase     # wipes the bond
pio run -t upload    # re-flash the firmware
```

Then remove the old pairing from the previous phone's Bluetooth settings
and connect from the new phone; it becomes the new owner.

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
2. Open the app and grant the Bluetooth permissions when prompted.
3. The app scans, connects, and starts pairing; confirm Android's pairing
   dialog. Your phone is now the only device that can bond.
4. Status becomes "Connected" — the big button is live.

Afterwards it reconnects automatically on app launch.

## Trigger protocol (for reference)

BLE service `4090b92d-a8da-471a-85a8-aee612b68bad`, characteristic
`588a322e-4b88-4197-8f4e-a5f48417c8b7` (write, encrypted bonded link
required). Any write on the encrypted bonded link pulses the door (the
app writes a single `0x01` byte); the bond itself is the credential, so
writes from anything but the one bonded phone are impossible at the link
layer.
