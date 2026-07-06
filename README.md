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
3. The app scans and connects; confirm Android's pairing dialog. A fresh
   board pairs with the first phone that connects — no pairing window
   needed for the very first phone.
4. Press the big button — the door triggers.

**Daily use**
1. Open the app; it auto-connects to the bonded board.
2. Press the button; the door triggers (2s firmware cooldown between
   presses).

**Add another phone** (up to 3 total)
1. Open the 30-second pairing window: short-press the board's BOOT button,
   or on an already-paired phone use Settings → Pair another phone. The
   LED blinks slowly while the window is open.
2. On the new phone: install the app, grant permissions, and connect
   within 30 seconds; confirm the pairing dialog.
3. The window closes as soon as one phone pairs (or after 30s). Every
   paired phone can trigger the door and manage the phone list.

**Remove a phone**
- Settings → Phones on any paired phone lists everyone; Remove unpairs
  the chosen phone (including this one). Also remove "GarageRemote" from
  the removed phone's Bluetooth settings.

**Reconnecting**
- If the board is off or out of range, the scan times out after 15s and
  shows a status message; tap Reconnect to retry.

**Factory reset**
1. Hold the board's BOOT button ~3s while it's running — the LED blinks
   to confirm ALL pairings and names are wiped. (Not the RST button,
   which just reboots. USB alternative: `pio run -t erase`, then
   `pio run -t upload` in `firmware/`.)
2. Remove the pairing from each old phone's Bluetooth settings.
3. The next phone to connect becomes the first owner again.

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
low first thing at boot.

LED legend (onboard yellow LED):
- **Solid 400ms** — trigger pulse.
- **Slow blink** — pairing window open (30s).
- **Rapid blinking (~2s)** — factory reset confirmed.
- **Three fast blinks** — pairing window refused (phone list full).

## Security model

The encrypted bond IS the credential — the board keeps an allowlist of up
to **3** bonded phones:

- **Bonded, encrypted links only** — "Just Works" BLE pairing (no PIN).
  The trigger and management characteristics reject writes on unencrypted
  links and from anyone not on the allowlist, so only paired phones can
  operate the door.
- **Deliberate pairing window** — a new phone is accepted only during a
  30-second window opened by a short press of the BOOT button (physical
  access) or by an already-paired phone (owner authorization). The window
  closes after ONE new phone pairs, or on timeout. Exception: a board
  with zero bonds (fresh, factory-reset, or last phone removed) accepts
  its first phone directly — trust on first use, as before. Outside the
  window, pairing attempts are rejected and their bonds deleted.
- **Mutual management** — any paired phone can list and unpair the
  others (or itself) from the app. Each phone registers a display name
  with the board after pairing.
- **Persistence** — bond keys and names live in the board's flash
  (NimBLE/NVS) and Android's Bluetooth stack, surviving reboots and power
  loss on both ends. The app also remembers the board's MAC for a fast
  direct reconnect.
- **Concurrent connections** — the board advertises while a free slot
  exists (max 3 connections), so one phone using the app never blocks
  another. Connections that don't authenticate within ~25s are dropped.

The trade-off vs. passkey pairing: no MITM protection while pairing is
possible — i.e. during the very first pairing of a fresh board and during
any deliberately opened 30s window. Open windows at home; the LED shows
when one is open.

### Factory reset

Hold the XIAO's onboard BOOT button (wired to GPIO9) for ~3s while the
board is running; the LED blinks to confirm ALL bonds and names are
wiped. Don't confuse it with the RST button next to it — RST just reboots
and leaves the bonds intact. (A short press of the same button opens the
pairing window instead.)

No-button alternative, over USB:

```sh
cd firmware
pio run -t erase     # wipes all bonds and names
pio run -t upload    # re-flash the firmware
```

Then remove the old pairing from each phone's Bluetooth settings.

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
   dialog. A fresh board bonds to the first phone that connects.
4. Status becomes "Connected" — the big button is live.

Afterwards it reconnects automatically on app launch. Additional phones
pair through the 30-second pairing window (see "Add another phone").

## Protocol (for reference)

BLE service `4090b92d-a8da-471a-85a8-aee612b68bad`. All writes/reads
require an encrypted bonded link from an allowlisted phone; the bond
itself is the credential.

- **Trigger** `588a322e-4b88-4197-8f4e-a5f48417c8b7` (write): any write
  pulses the door (the app writes a single `0x01` byte).
- **Management** `f34dd3a3-ed37-4822-9d08-f96ee2974856` (write):
  - `0x01` — open the 30s pairing window.
  - `0x02` + 6-byte address (display order) + 1-byte address type —
    unpair that phone (echo a bond-list entry's first 7 bytes verbatim).
  - `0x03` + 0–24 bytes UTF-8 — register/replace the calling phone's
    display name (empty = clear).
- **Bond list** `8d3a66f6-42a0-4d0b-8a56-3a9f2f8e5c01` (read):
  `[version=1][count]`, then per entry `[6B address, display order]
  [1B address type][1B flags, bit0 = the reading phone][1B name length]
  [name]`. Non-allowlisted readers get an empty list.
