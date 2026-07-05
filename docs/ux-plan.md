# Garage Remote app — UX improvement proposal

Status: REVISED after panel review round 3. Reviewers: read README.md (user
flows, security model) and the current app sources in
`android/app/src/main/java/com/kevinywlui/garageremote/`
(MainActivity.kt, GarageBleClient.kt, PinStore.kt) before judging.

Constraints:
- Single-purpose app: connect to one BLE garage board, press one button.
- Security flows (PIN, TOFU provisioning, single bond) are fixed; UX may
  present them better but not change the protocol.
- Kotlin + Jetpack Compose, Material 3, minSdk 26. No new libraries
  unless strongly justified.

## 1. Theming (required: 6 themes — 3 light, 3 dark)

- Six Material 3 color schemes:
  - Light: **Porcelain** (cool blue on white), **Sunrise** (warm
    orange/peach), **Mint** (fresh green).
  - Dark: **Midnight** (deep blue), **Ember** (charcoal + warm orange),
    **Forest** (dark green).
- **Palette generation is a build-time step, not a runtime dependency**:
  do not hand-tune ~30 roles × 6 schemes. Run Material Theme Builder /
  material-color-utilities **offline** from a seed color per theme (AA
  holds for on-X/X pairs by construction) and **commit the exported
  static `Color` / `ColorScheme` constants** into the source tree —
  material-color-utilities is never added as a runtime library. Then
  hand-adjust only the few roles that define each theme's character —
  and **any hand-adjusted role re-enters the §5 contrast checklist**
  before the palette is considered done: the "AA by construction"
  guarantee covers only untouched generator output. Watch **Sunrise** specifically: saturated orange/peach primaries on
  white fail 4.5:1 with white button text — keep primary at tone ~40
  (reads amber/brown) or use container-style buttons.
- **Extended color roles** (M3 `ColorScheme` has no success/positive
  role): each theme ships a small extension data class alongside its
  `ColorScheme` with `success`, `onSuccess`, `successContainer`, and
  `onSuccessContainer`, defined and contrast-checked (AA) for all 6
  palettes as part of the palette deliverable, exposed via a
  `CompositionLocal` provided next to `MaterialTheme`. **Hue
  distinctness requirement**: per theme, adjust hue/chroma so the
  READY, in-progress, and error chips are visually separable at a
  glance. Acceptance criterion: **≥ ~30° HCT hue separation between
  `success`, `secondary`, and `error` within each theme**. In Mint and
  Forest a green `success` must not read as just-another-theme-color
  (shift `success`). In Ember the warm-orange `secondary` collides with
  error red/orange — and since that orange *is* the theme's character,
  the fix there is explicitly to **shift `error` toward crimson/pink**,
  not to touch `secondary`. The mandatory text labels keep this
  WCAG-safe regardless; this is a glanceability requirement on the
  palette deliverable. State→role
  mapping is fixed here so implementers never invent colors ad hoc:
  - ready/connected → `success` roles (extension)
  - scanning/connecting/bonding/triggering/cooldown → `secondary`
    roles (TRIGGERING and COOLDOWN share one chip presentation, §3)
  - error → `error` roles
  - idle/disabled → standard muted `onSurfaceVariant`/disabled alphas
  - **success flash on the big button** → `successContainer` /
    `onSuccessContainer`; its reduced-motion replacement (the static
    color/label change in §5) uses the **same** roles. Both are included
    in the AA / 3:1 checks.
- **"Follow system" is an explicit, persistent 7th picker option** and
  the initially-selected default. It maps to Porcelain (light) /
  Midnight (dark) and switches live with the system dark-mode setting.
  Picking a specific theme is therefore always reversible — the user can
  return to automatic dark/light switching at any time. (Resolves former
  open question 1; matters for night use in a car and for users who rely
  on system dark mode for photophobia / low vision / OLED battery.)
- **Theme state has one owner**: the same ViewModel that owns the BLE
  client (§4) holds the theme selection as a `StateFlow` — the single
  source of truth for the picker, persistence, and the theme applied in
  `setContent`. The persisted enum name (one SharedPreferences key) is
  read **synchronously before `setContent`** to seed that flow so there
  is no one-frame flash of the default theme on cold start — and because
  the ViewModel survives the uiMode-change recreation that follow-system
  makes routine, each recreated activity re-reads from the flow, not
  from disk. No DataStore or new libraries. **Picking a theme card
  applies the theme live**, immediately visible behind the settings
  sheet — the flow *is* the applied theme, never a staged choice
  awaiting confirmation; the instant preview is deliberate.
- Theme picker layout in the settings bottom sheet: **"Follow system" as
  a full-width row on top, then a 2×3 grid of the six theme cards** — no
  dangling seventh cell, and the recommended default gets visual
  hierarchy over the specific themes. Swatches are rendered from each
  scheme's **actual tokens** (surface background, primary + secondary
  dots, onSurface label + light/dark badge) so the picker cannot drift
  from the real theme. **The "Follow system" card renders a split
  swatch** — half Porcelain (surface + primary dot), half Midnight —
  with an "Auto" badge, so it too is drawn from real tokens rather than
  improvised as a token-less gray card. The badge sits on **its own
  contained tonal chip background** — no single text color meets
  contrast against both the light and dark halves of the split swatch —
  and is included in the 3:1 non-text check alongside the selected
  indicator. The row also carries a one-line caption, **"switches with
  your phone's dark mode"**, so the trade-off of picking a fixed theme
  (losing automatic dark switching) is visible at the moment of choice
  rather than discovered at night in the car. The selected indicator
  (border + check) must meet 3:1 non-text contrast on every card. Picker
  semantics: the grid is a `selectableGroup`, each card
  `Role.RadioButton` with `selected` state and
  `Modifier.semantics(mergeDescendants = true)` so TalkBack announces
  each card as one item ("Sunrise, light theme, selected") instead of
  stepping through swatch dots and badge separately.
- **Dynamic color (Material You) is deliberately skipped**, not
  forgotten: the assignment is 6 fixed themes, and a wallpaper-derived
  8th scheme would complicate the extended-role contrast guarantees for
  no benefit in a single-purpose hobbyist app.
- Edge-to-edge rendering; status/navigation bar icon contrast follows
  the active theme's darkness.
- **Typography**: themes vary color only, not type. Fixed type-scale
  mapping: status chip = `labelLarge`, secondary detail = `bodyMedium`,
  sheet title = `titleLarge`, button = icon (garage / swap-vertical
  glyph) + one short **`titleLarge`** label (on a 220dp circle a 16sp
  `titleMedium` label reads proportionally small). **The button's
  visible label is pinned here** — the most-seen text in the app is not
  left to the implementer: the single line **"Open / Close"** in
  READY/TRIGGERING/COOLDOWN, and **"Reconnect"** in IDLE/error (§3);
  the accessible `onClickLabel` stays "trigger the garage door" (§3).
  The current 32sp two-line "OPEN / CLOSE" clips inside the 220dp
  circle at large accessibility font scales; the icon + single-line
  `titleLarge` label replaces it (do not cap the user's font scale),
  and a **`@Preview(fontScale = 2f)` per theme** verifies the icon +
  label composition still fits the 220dp circle at the top of the
  scale.

## 2. Screen structure

- Keep a single main screen. Add a settings icon (top-right,
  `IconButton` at its default 48dp target, with a contentDescription)
  that opens a **bottom sheet** with: theme picker, Change PIN, and a
  short "how pairing works" help text. Decision (former open question
  2): the bottom sheet is right for settings in a one-screen app; Change
  PIN does **not** get its own settings screen, but the PIN field is
  never embedded in the sheet (IME-inside-ModalBottomSheet resizing is
  janky) — the sheet row launches the existing full-screen
  `PinEntryScreen`. **The sheet dismisses before `PinEntryScreen`
  opens**, so Cancel/back from the PIN screen lands on the main screen
  (not a stale sheet), with TalkBack focus returning to the main
  screen's top-level content. When the sheet opens, initial TalkBack
  focus lands on the sheet title, not a swatch; M3 `ModalBottomSheet`
  already exposes a dismiss action.
- **First-launch sequencing is explicit** (the current code fires the
  runtime-permission dialog on top of PinEntryScreen in `onCreate`):
  on a fresh install the order is **PIN entry first → then the runtime
  permission request → then scan/connect**. The permission dialog must
  never cover the PIN field.
- **PIN entry screen** (first launch and Change PIN reuse the same
  screen):
  - **Cancellable**: an explicit Cancel action plus system back
    (including predictive back) returns to the main screen **without
    touching the stored secret**. This removes the current trap in
    MainActivity.kt where the only exits from PinEntryScreen are saving
    (silently desyncing the app from a provisioned board) or leaving the
    app. First launch may keep Save-only if no PIN exists yet, since
    there is nothing to preserve — but back must still exit the app
    cleanly rather than loop.
  - **Warning placement**: when a PIN is already set, the factory-reset
    warning ("a provisioned board must be factory-reset — hold BOOT ~3s
    — before a new PIN works, **and the old pairing removed in the
    phone's system Bluetooth settings** — the phone keeps the stale
    bond otherwise") is shown inline on the PIN screen,
    **above the entry field in reading order**, so both sighted and
    TalkBack users encounter it at the point of commitment — not only as
    text in the settings sheet.
  - **Confirmation on Save** (outside first launch): tapping Save shows
    an explicit confirmation dialog. Wording is **hedged**, because the
    app cannot know whether the board is provisioned (the one legitimate
    Change PIN use case is a freshly reset board): "If the board was
    already set up with your current PIN, it will stop responding until
    you factory-reset it (hold BOOT ~3s). The old PIN cannot be
    recovered. Afterwards, also remove the old pairing in your phone's
    Bluetooth settings." Naming the phone-side step at the point of
    commitment is deliberate: the 3-rapid-cycles stale-bond heuristic
    (§3) is the reactive fallback, not the primary path. **Cancel is
    the default action — concretely**: Cancel is the emphasized/filled
    button in the confirming (end) position; the confirming action is a
    de-emphasized text button labelled **"Replace PIN"** — an explicit
    destructive verb, never a generic "Confirm"/"OK", so neither
    habituated end-position tapping nor label vagueness causes a wrong
    tap. Dismissing via back or outside-tap maps to Cancel. Only
    "Replace PIN" calls `PinStore.set()`.
  - PIN field: NumberPassword + `PasswordVisualTransformation`; a
    supporting error text tied to the field via `semantics { error() }`
    for "too short", rather than relying only on the disabled Save
    button.
- **Help text ("how pairing works")** states the TOFU caveat honestly,
  mirroring the README: do the first connection at home with the board
  freshly powered — whoever connects first owns the device, and there is
  no MITM protection during that first pairing. It also explains that
  the **first button press** (not saving the PIN) is what provisions the
  secret into the board, and covers the **stale-bond edge**: after a
  board factory reset the phone still holds its old bond, and reconnect
  will loop or fail until the pairing is removed in system Bluetooth
  settings. One honest sentence on the PIN itself: the stored secret is
  a truncated SHA-256 of a short numeric PIN, so anyone who obtains it
  can trivially recover the PIN — **don't reuse a PIN you use
  elsewhere, and use 6+ digits** (a 4-digit PIN falls to brute force in
  milliseconds once the secret leaks). Accordingly, `MIN_PIN_LENGTH` is
  raised to 6 **for newly entered PINs** — a client-side policy change
  only; the protocol and already-provisioned secrets are unaffected.
- **First-press hint**: before the first successful trigger, a one-time
  inline hint under the button — copy hedged because after an app
  reinstall against an already-provisioned board the claim would be
  false: **"Your first press registers this PIN with a fresh board."**
  The wrong-PIN chip (§3) corrects the reinstall edge if it bites. The
  "seen" flag is a persisted boolean that **re-arms whenever the PIN is
  changed** (the post-factory-reset flow re-provisions on the next
  press, so the hint is true again).

## 3. Main screen polish

- **Status chip**: replaces the raw message string: icon + label +
  theme-aware color per state (see the role mapping in §1). The label is
  mandatory in every state — never icon-only — so no state is
  color-only. Detailed message shown as secondary text. Chip states
  cover the **full** `GarageBleClient.State` machine plus the new
  cooldown:
  - **IDLE / error**: muted (or `error` roles for failures) with a retry
    affordance. Sizing note: M3 chips are 32dp tall, so a naive trailing
    icon violates the ≥48dp target — in error states **the whole chip is
    the retry tap target** (or the trailing affordance gets
    `Modifier.minimumInteractiveComponentSize`), with
    contentDescription "Retry connection".
  - **SCANNING**: animated pulse, "Scanning…".
  - **CONNECTING**: spinner, "Connecting…".
  - **BONDING**: spinner, "Confirm pairing — check the dialog or your
    notification shade" (the consent UI is a separate Settings activity
    on many OEMs and lands in the shade on Samsung). On some OEM builds
    Android auto-confirms Just Works pairing with no visible dialog, so
    this state may resolve to READY in under a second with no user
    action — the copy tolerates that; **do not** later add "waiting for
    you"-style phrasing. If the ~30s bond times out or `BOND_NONE`
    comes back, the chip moves to an error state with a retry
    affordance.
  - **READY**: `success` roles, labelled **"Connected"** — honest
    wording: bonded + services discovered means an encrypted link, not
    that the door has verified the PIN (the firmware only validates the
    secret on write, and before first provisioning nothing is verified).
  - **TRIGGERING**: in-flight state, labelled **"Sending…"**, feeding
    the success flash below.
  - **COOLDOWN**: keeps the **same "Sending…" label and chip
    presentation as TRIGGERING** — the countdown ring on the button
    conveys the cooldown visually. One shared label means the chip's
    liveRegion naturally fires once across TRIGGERING → COOLDOWN, with
    no visible-label/accessible-text divergence to maintain (see the
    announcement-collapsing note below and §5). Cooldown mechanics: see
    below.
- **Error model (load-bearing for every error chip below)**: the UI
  state exposed by the ViewModel (§4) is **not** today's
  `GarageBleClient.State` IDLE-plus-message-string. Errors are modelled
  as **IDLE plus a first-class error payload** carrying a cause enum
  (`PAIRING_REJECTED`, `WRONG_PIN_SUSPECTED`, `STALE_BOND`,
  `NOT_FOUND`, `BLUETOOTH_OFF`, `LOCATION_OFF`, `LINK_LOST`, …); chip
  roles and guidance copy are keyed off the enum, never off
  message-string sniffing. Three consequences are pinned here:
  - `onStart`'s auto-reconnect (§4) fires from IDLE **with or without**
    an error payload — an app reopened onto an error chip still
    reconnects automatically.
  - Teardown paths (`disconnect()`, the onStop grace timer, adapter-off
    cleanup) must **not** clobber a security-relevant payload
    (`WRONG_PIN_SUSPECTED`, `STALE_BOND`, `PAIRING_REJECTED`) with a
    generic "Disconnected" — the payload is preserved across a
    stop/start cycle.
  - After a background disconnect → reconnect cycle, a successful
    return to READY shows "Connected" as the chip label but keeps the
    last security-relevant message as the chip's **secondary detail
    line** until the user acts (changes the PIN, re-pairs, or triggers
    successfully) — the next press will fail again, and the guidance
    must not silently vanish.
- **Security-relevant failures land in the persistent chip, not (only) a
  snackbar**, with guidance:
  - Pairing failed/rejected → usually another phone owns the bond slot →
    point to factory reset (help text in the sheet).
  - **Wrong-PIN detection**: the firmware drops the connection when the
    secret doesn't match, which today surfaces as a bare "Disconnected".
    Detection is keyed on **state, not wall-clock**, and covers **both
    orderings** of write-response vs. disconnect: an unexpected
    disconnect while `state == TRIGGERING` (write response still
    outstanding — on a bad secret `onCharacteristicWrite` frequently
    never fires, so no write callback can be assumed to precede the
    disconnect, and TRIGGERING survives slow supervision timeouts)
    **or while `state == COOLDOWN` with the cooldown timer still
    running** (the write reported `GATT_SUCCESS` and the state machine
    already advanced — per the honesty rule below, the firmware can
    drop the link *after* the write reports success, so TRIGGERING-only
    detection would miss that acknowledged ordering entirely).
    Implementation shape: a `triggerInFlight` flag set when the write
    is issued and cleared on **every** exit from TRIGGERING/COOLDOWN —
    the cooldown elapsing, the write failing to issue,
    `onCharacteristicWrite` reporting any non-success status, and any
    state transition out of TRIGGERING/COOLDOWN that is not the
    unexpected disconnect itself. The detector fires only when an
    unexpected disconnect is what ends TRIGGERING/COOLDOWN; a stale
    flag must never survive into READY where a later routine
    range-loss disconnect would falsely fire it. Still no wall-clock
    heuristics — the flag's lifetime *is* the state machine's. **Exclusions — client-initiated disconnects never raise
    this chip**: the onStop grace-timer teardown from COOLDOWN (§4),
    user-initiated disconnect, and adapter-off cleanup all clear
    `triggerInFlight` without firing the detector, so pocketing the
    phone right after a press never shows the wrong-PIN chip. Chip
    copy — the hedge is **mandatory** in the final copy, because a
    genuine trigger followed by immediate link loss inside the ~2.2s
    window (driving away, board power cut, supervision timeout)
    matches the same signature: "Board rejected the PIN — it **may**
    have been provisioned with a different one", linking to the
    factory-reset help text. If the disconnect status codes prove
    reliable enough, suppress the wrong-PIN message when the status
    indicates a local timeout/supervision loss rather than a
    remote-initiated disconnect. **Honesty rule**: the ATT write can
    report `GATT_SUCCESS` before the firmware drops the link, so the
    success flash/haptic may already have fired — that transient is
    acceptable, but when the detector fires (from either state) the
    client **cancels COOLDOWN immediately** and the error chip
    supersedes the success UI; the app must not sit in a "triggered,
    cooling down" state for a press the door rejected.
  - A write failing with `GATT_INSUFFICIENT_AUTHENTICATION/ENCRYPTION`
    (stale bond after board reset) → point the user to remove the
    pairing in system Bluetooth settings. **The same edge more commonly
    presents as a connect → encryption-failure → disconnect loop before
    service discovery completes**, so a loop heuristic triggers the
    same "remove the pairing in system Bluetooth settings" guidance.
    **Heuristic, defined concretely**: 3 disconnects that each occur
    **before service discovery completes**, against the stored bonded
    MAC, **within a ~30s window**. Counter lifecycle: the counter
    resets on any **successful service discovery (reaching READY)** and
    on any **user-initiated retry**; the window plus the reset rules
    mean three unrelated flaky connects spread across days can never
    trip the "remove the pairing" guidance falsely. Additionally,
    silently retry the first write after bonding **once** — some stacks
    report bond complete slightly before link encryption is up. This
    retry is safe from double-pulsing the door **only** because the
    firmware ignores repeat triggers for 2s: it must fire well inside
    that 2s window; only for
    `GATT_INSUFFICIENT_AUTHENTICATION/ENCRYPTION` statuses — never for
    a timed-out or unknown-status write; and **only after the bond
    receiver has observed `BOND_BONDED`** (confirmed bond state, not
    merely "`createBond()` was called") — an ATT write request carries
    the secret bytes before the peripheral can reject it, so a retry
    issued while the link is genuinely unencrypted would retransmit the
    secret in the clear; the bond-confirmed gate confines the retry to
    the "bond reported complete slightly before encryption is up" race
    it exists for. Do not generalize it into a retry policy.
- **The button**: large circular button; scale-down animation on press.
  Visible label pinned in §1: single-line **"Open / Close"** (and
  "Reconnect" in IDLE/error, below). **Accessible name**: the glyph is
  decorative (`contentDescription =
  null` inside the button), the button exposes a single merged label,
  and `onClickLabel = "trigger the garage door"` so TalkBack announces
  action intent rather than an ambiguous "Open/Close". Haptics:
  `KEYBOARD_TAP` on press, `CONFIRM` on success, **with a fallback below
  API 30** (`CONFIRM` requires API 30; minSdk is 26): on 26–29 use
  `View.performHapticFeedback(VIRTUAL_KEY)`, which needs **no**
  permission — not the `Vibrator`/`VibrationEffect.EFFECT_CLICK` path,
  which requires the VIBRATE manifest permission and would otherwise
  ship as a silent no-op. Brief success flash
  (`successContainer`/`onSuccessContainer`, §1) when the write
  completes.
- **The button doubles as the primary recovery affordance in IDLE and
  error states**: for the common "Device not found" outcome, the 32dp
  status chip's whole-chip tap target is accessible (per the sizing
  note above) but visually weak as the *sole* recovery action on an
  otherwise empty screen. In IDLE/error the button is **enabled**,
  relabelled **"Reconnect"** (refresh glyph, `onClickLabel =
  "reconnect to the garage door"`), and calls `connect()` — never
  `trigger()`, which stays unreachable outside READY. The chip remains
  tappable as a secondary retry for transient errors.
- **Cooldown (mirrors the firmware's 2s trigger cooldown)**:
  - **State ownership**: `COOLDOWN` is a value in the client/ViewModel
    state machine (§4), not a composable-local timer, so it survives
    recreation.
  - **Timing**: the local timer starts on `onCharacteristicWrite`
    `GATT_SUCCESS` (never on press-down), padded to **~2.2s** so the app
    window never ends before the firmware's. The ring is cosmetic — the
    firmware cooldown stays the source of truth.
  - **Success-feedback rule (implementable as stated — no race detector
    exists or is needed)**: success flash + `CONFIRM` haptic fire
    **only for writes issued from READY**; `trigger()` is a synchronous
    no-op in TRIGGERING and COOLDOWN (and the button is disabled in
    both). This is what the state machine already guarantees — no
    GATT-level detection of "raced" writes is possible or required. A
    press that slips through clock skew after the local window ends is
    ignored by the firmware and must not be surfaced as an error.
  - **Visuals**: during cooldown the button keeps its size and prominent
    color with a circular countdown ring overlay and slightly dimmed
    content — reading as "busy, wait", visually distinct from the muted
    disabled look used while scanning/connecting/bonding (two
    identical-looking disabled states meaning different things is a
    known confusion point). **The muted disabled look is pinned to the
    standard M3 disabled recipe**: container = `onSurface` at 12%
    alpha, content = `onSurface` at 38% alpha — a concrete target for
    §5's "disabled button still perceivable in all 6 themes" check, so
    the cooldown-vs-disabled distinction can't erode during
    implementation. **Implementation note**: this requires custom
    `ButtonColors` — M3 `Button` applies one `disabledContainerColor`
    for every disabled state, so the disabled colors must be keyed off
    the state machine (prominent for COOLDOWN, the pinned 12%/38%
    recipe otherwise) or the two states ship looking identical.
  - **Accessibility (no silent no-op)**: the button is set
    `enabled = false` during cooldown so Compose announces "disabled" to
    TalkBack and switch access, with semantics
    `stateDescription = "Cooling down"`. The countdown ring is **purely
    visual** — no `progressBarRangeInfo` on the button (range-info on a
    disabled-button node can make TalkBack read it as a progress bar,
    and at ~2.2s the value is stale before a user can refocus). The
    return to ready is announced via the status chip's liveRegion.
    **Announcement collapsing**: TRIGGERING and COOLDOWN share the
    **same chip label** ("Sending…" persists through cooldown; the ring
    conveys the rest visually, per the chip-state list above), so the
    liveRegion naturally fires once — no visible-label/accessible-text
    divergence to maintain — and a routine successful press produces
    one announcement plus the READY return, not three, matching §5's
    "each event is announced once".
- **Transient events via snackbar — errors only** (resolves former open
  question 3): "Triggered!" gets **no snackbar** — success already has
  the flash + CONFIRM haptic + countdown ring; a third daily signal is
  noise, and dropping it also avoids double TalkBack announcements
  (chip liveRegion + snackbar). Error snackbars:
  - fix the current defect where `onCharacteristicWrite` overwrites the
    persistent status string with "Triggered!", leaving stale text;
  - are modelled as one-shot events — specifically a
    **`Channel(BUFFERED).receiveAsFlow()`** collected into
    `SnackbarHostState` (not a `SharedFlow`, whose replay=0 drops
    emissions when no collector is active, e.g. an error arriving
    mid-recreation; a Channel buffers until collected) — separate from
    persistent state, so config changes don't replay them; a new
    snackbar dismisses the previous one so rapid retries don't queue;
  - carry a **Retry** action where applicable (Compose extends snackbar
    timeouts under accessibility services, further when the snackbar has
    controls);
  - the host is inset above the navigation bar (edge-to-edge).
    **Occlusion while the settings sheet is open is accepted by
    design**: M3 `ModalBottomSheet` renders in its own window, so a
    `SnackbarHost` in the activity's Scaffold physically cannot appear
    above the sheet or its scrim — no anchoring requirement is stated,
    and no custom sheet is to be built to work around it. The already-
    planned chip mirroring is the coverage: security-relevant failures
    persist in the status chip, which the user sees on sheet dismissal;
  - security-relevant failures also persist in the status chip (see
    above) so the information is never only time-limited.

## 4. Lifecycle & connection behavior

- **State ownership**: `GarageBleClient` and all UI state move out of
  MainActivity fields into a **ViewModel** — the ViewModel owns the
  client; state is exposed as `StateFlow` with client callbacks updating
  it. Configuration changes (including uiMode dark/light flips, which
  the follow-system default makes routine) then recreate the activity
  **without** killing the connection or losing the cooldown timer /
  countdown-ring state. The lifecycle hooks below operate on this
  retained client.
- **Terminal teardown — `onCleared()`**: the ViewModel's `onCleared()`
  calls `client.disconnect()` **unconditionally** — no BONDING or
  system-activity guard applies there. Once the ViewModel is cleared
  (activity finished via back, task removal, etc.) there is no UI left
  for the TOFU flow anyway, and without this the bond BroadcastReceiver
  (registered on applicationContext) and the open GATT would leak with
  no future `onStart` to clean them up — and a held GATT occupies the
  board's **single** connection slot (the firmware only advertises while
  disconnected), making the board unreachable and breaking the next
  launch's fast reconnect.
- **Symmetric lifecycle pairing via `DefaultLifecycleObserver`**:
  connect in **onStart**, disconnect in **onStop** — with guards:
  - **Never disconnect while `state == BONDING` or `TRIGGERING`**, or
    while a system activity the app launched is in flight (the
    `ACTION_REQUEST_ENABLE` prompt, the app-settings deep link). The
    pairing consent UI is a separate activity on many OEM builds (or
    lands in the notification shade on Samsung), so it stops
    MainActivity mid-bond; disconnecting there closes the GATT,
    unregisters the bond receiver, and cancels `createBond()` — breaking
    the first-run TOFU flow the plan depends on (the client only reaches
    READY via the bond receiver). Teardown happens only from safe
    states: READY, IDLE, SCANNING, CONNECTING, error, and **COOLDOWN**
    (explicitly safe — the write has already completed; do not hold the
    link for the ring's sake when the user pockets the phone right
    after a press. This teardown is client-initiated: it clears
    `triggerInFlight` and must never raise the wrong-PIN chip, per §3's
    exclusion list).
  - **Deferred teardown must re-arm — the guard never leaks the
    connection**: when the onStop disconnect is skipped by the
    BONDING/TRIGGERING/system-activity guard, set a `teardownPending`
    flag. The deferred disconnect then executes as soon as the blocking
    condition clears **while the activity is still stopped**: when the
    client leaves BONDING (`BOND_BONDED`, `BOND_NONE`, or bond timeout —
    including a bond that resolves to READY or error in the background,
    e.g. the user confirms pairing from the Samsung notification shade
    and never returns, or presses home and never confirms), when
    TRIGGERING resolves, or when the launched system activity's result
    returns. **Hard cap**: after ~35s (just over the stack's ~30s bond
    timeout) the guard yields and teardown runs regardless. `onStart`
    clears `teardownPending` **and cancels the ~35s hard-cap timer** —
    otherwise a cap armed in the background fires a disconnect shortly
    after the user returns. Without the deferred teardown, the app
    holds an open GATT and a registered bond receiver in the background
    until process death, occupying the board's single connection slot.
  - **System-activity guard mechanism**: a flag set immediately before
    launching `ACTION_REQUEST_ENABLE` / the settings deep link, cleared
    in the ActivityResult callback, **with a timeout** so a swallowed
    result cannot wedge the guard permanently.
  - **Grace timer, and its ordering with the guard**: the onStop
    disconnect runs on a short handler delay (~2–3s), cancelled in
    onStart, so quick app switches and system dialogs don't drop the
    link. **Sequencing — one pipeline, not two competing timers**: the
    grace timer fires → the guard is evaluated *at that moment* → if
    state is BONDING/TRIGGERING or a system activity is in flight, set
    `teardownPending` (arming the ~35s hard cap); otherwise disconnect
    immediately. `teardownPending` and the hard cap are only ever armed
    from that evaluation, never in parallel with the grace timer. (An acceptable later simplification:
    hang connect/disconnect off `ProcessLifecycleOwner` instead, which
    makes config-change churn structurally impossible — noted, not
    required, since `lifecycle-process` may be a new artifact and the
    grace-timer design is sufficient.)
- **Reconnect definition**: onStart connects only when
  `state == IDLE` — **including IDLE with an error payload** (§3's
  error model: errors *are* IDLE plus a payload, so reopening the app
  onto an error chip still auto-reconnects; the security-relevant
  detail line survives the cycle per §3). "Not already connected" means
  exactly that: a start during SCANNING/CONNECTING/BONDING/READY is a
  no-op (prevents double-scan bugs).
- **Every auto-connect entry point is gated on preconditions**: the
  onStart observer, the adapter `STATE_ON` receiver, and the one
  bounded mid-session reconnect are all no-ops unless (a) a PIN is
  stored and (b) all required runtime permissions are currently
  granted — checked at fire time, not cached, because the user can
  revoke permissions in system settings while the app is backgrounded
  (on API 31+ `startScan`/`connectGatt` without BLUETOOTH_SCAN/CONNECT
  throws SecurityException). On a fresh install the first connect
  fires from the permission-grant callback (matching §2's
  PIN → permission → connect order); a start with revoked permissions
  lands on the permission explainer panel instead of calling into the
  adapter.
- **One bounded auto-reconnect on an unexpected mid-session drop**: on
  a remote-initiated disconnect while `state == READY` and the activity
  is started (board power blip, momentary range loss — routine in
  daily driveway use), the client makes **one** silent direct-reconnect
  attempt to the stored MAC before settling into the error chip and the
  retry affordances (§3), so a routine link drop doesn't cost a manual
  tap. Strictly bounded: one attempt, never a loop, never while
  stopped, and **never when wrong-PIN detection has fired** (§3) — a
  rejected press lands on the error chip immediately. The stale-bond
  loop heuristic (§3) counts these attempts like any other, capping
  loop risk.
- **Fast reconnect, scan as setup-only path**: persist the bonded
  device's MAC after the first successful bond. On start, call
  `connectGatt(autoConnect = false, TRANSPORT_LE)` directly on
  `adapter.getRemoteDevice(address)` with a ~10s connect timeout; fall
  back to scanning only when there is no stored device or the direct
  connect fails. This avoids Android's silent scan-start throttle (>4
  starts per 30s per app → false "Device not found") and the needless
  up-to-15s scan on every open. Three required details:
  - **Cancel before falling back**: on Bluedroid a direct
    `connectGatt(autoConnect = false)` stays pending in the stack well
    past 10s. On direct-connect timeout the client **must call
    `gatt.close()` on the pending attempt and ignore any late callback
    from it** (the `gatt === g` identity check alone is not sufficient
    protection) **before** starting the scan fallback — otherwise the
    board coming into range later completes the orphaned direct connect
    and races the scan-path connection, leaving two GATT objects / a
    stray connection the state machine doesn't own. Likewise, close the
    pending attempt if the user leaves the screen during it.
  - **Bonded-MAC scan filter (security-relevant)**: when the direct
    connect times out but the adapter still reports `BOND_BONDED` for
    the persisted address, the fallback scan **filters results to that
    address** — never accept an arbitrary advertiser of `SERVICE_UUID`
    while a bond exists. Otherwise a spoofed device advertising the
    same UUID can win the scan, get bonded via Just Works (the user
    will reflexively confirm the pairing dialog), and receive the
    PIN-derived secret on the next press — from which the short numeric
    PIN is trivially brute-forced. Arbitrary addresses are accepted
    only when no MAC is persisted, or after the stale-bond path below
    has cleared it. (A pre-existing exposure in the current code,
    closed here because this exact path is being restructured anyway.)
  - **Stale-bond skip**: before direct-connecting, check the
    adapter-reported bond state for the stored address; if it is
    `BOND_NONE` (user removed the pairing in system settings), clear
    the persisted MAC, surface a brief note — **"Pairing was removed —
    setting up again"** — so the reappearing system pairing dialog
    isn't a surprise, and go straight to scan-and-rebond — otherwise
    the direct connect succeeds, the write fails with insufficient
    authentication, and the user loops through the error path when a
    plain scan would have self-healed.
- **Bluetooth off**: inline action instead of a dead-end message, with
  full plumbing:
  - Gate on `BLUETOOTH_CONNECT` (API 31+): if not granted (including the
    denied-once case the permanently-denied panel doesn't cover),
    request the permission first — firing `ACTION_REQUEST_ENABLE`
    without it throws SecurityException.
  - Launch the enable intent via an **ActivityResult launcher** (this
    prompt is a separate activity — covered by the onStop guard above,
    via the system-activity flag).
  - Register a `BluetoothAdapter.ACTION_STATE_CHANGED` receiver
    **whenever the client holds a GATT or an active connect/scan** —
    register/unregister is keyed off client state, not activity
    visibility, because the deferred-teardown path (above) deliberately
    holds a live GATT for up to ~35s while stopped, and a Bluetooth
    toggle in that window would otherwise be seen by neither a
    visibility-scoped receiver nor the unreliable DISCONNECTED
    callback. While the screen is visible it also drives auto-calling
    `connect()` when the adapter reaches `STATE_ON` — no second tap
    needed. **The receiver, not the GATT callback, drives adapter-off
    cleanup**: on `STATE_TURNING_OFF`/`STATE_OFF` proactively call
    `gatt.close()` and move to IDLE — on several OEM stacks the
    `onConnectionStateChange` DISCONNECTED callback is not reliably
    delivered when the adapter dies, so the "BT toggling off flips the
    status chip" behavior cannot depend on it.
- **Location Services check on API 26–30**: on Android 8–11 BLE scans
  silently return zero results when the system Location Services toggle
  is off, even with `ACCESS_FINE_LOCATION` granted — indistinguishable
  from "Device not found". The direct-reconnect path masks this for
  daily use, but first-time setup on an older phone hits it: the scan
  path checks `LocationManager` and shows a specific "turn on Location"
  message (with a settings link) instead of the generic not-found error.
- Permission permanently denied: explainer panel with a deep link to app
  settings (deep link stop is covered by the onStop guard).
- Scan timeout stays 15s but the countdown state is visible (scanning
  animation) and the retry is one tap.

## 5. Accessibility

- All interactive elements ≥ 48dp targets and content descriptions
  (settings icon, chip retry affordance per §3's sizing note, sheet rows
  included). The main button's accessible name and `onClickLabel` per
  §3; its glyph is decorative (`contentDescription = null`).
- State changes announced for TalkBack: liveRegion on the status chip,
  **Polite** (not Assertive). Back-to-back scan→connect→bond→ready
  announcements are acceptable; avoid duplicating any event that also
  fires a snackbar — each event is announced once. TRIGGERING and
  COOLDOWN share one chip label per §3, so the liveRegion fires once
  across a press. Cooldown
  semantics per §3 (disabled + `stateDescription = "Cooling down"`; the
  ring is purely visual, no range-info semantics).
- Contrast: all 6 palettes checked for WCAG AA on text/status colors
  **including the extended success roles and the success-flash
  container pair** (§1), plus WCAG 1.4.11 non-text contrast (3:1) for
  status-chip icons, the big button's fill against background, **the
  countdown ring against the button fill it overlays** (not just the
  screen background), outlined button borders, and the picker's
  selected indicator, **and the "Auto" badge's tonal chip on the split
  follow-system swatch** (§1) — in all 6 themes. The main CTA spends
  significant time disabled (scanning/connecting/bonding/triggering/
  cooldown; READY and IDLE/error enable it, as trigger and "Reconnect"
  respectively, §3): disabled state is exempt from contrast rules, but
  verify the disabled button is still perceivable in all 6 themes,
  especially the dark ones — the muted look is pinned to `onSurface`
  12% container / 38% content (§3), and the two distinct disabled looks
  (cooldown vs. muted) require custom `ButtonColors` per §3. Include
  the `@Preview(fontScale = 2f)` per-theme check from §1 for the
  button's icon + label composition inside the 220dp circle.
- Reduced motion: gate the custom infinite pulse, the press scale-down,
  and the success flash behind the system animator duration scale (and
  transition scale) == 0 check — Compose 1.4+ applies
  `MotionDurationScale` to frame-based animations, so unguarded infinite
  loops snap/strobe at scale 0 rather than stop; modern Compose
  animation APIs largely honor the scale already, so verify before
  adding blanket gating. Replace the success flash with a static
  color/label change (same `successContainer`/`onSuccessContainer`
  roles, §1) so success keeps non-motion visual feedback. Keep the
  connecting spinner and render the cooldown ring as a static progress
  indicator (they convey state, not decoration).
- Theme picker and follow-system semantics per §1 (merged card
  descendants included); PIN field error semantics per §2; sheet initial
  focus per §2.

## 6. Explicitly out of scope

- Widgets/quick-settings tile, wear support, multiple doors/boards,
  auto-open geofencing, notification actions, dynamic color / Material
  You (see §1 for rationale). (Candidates for later.)
- Secret storage stays as-is: the derived secret sits in plain
  SharedPreferences, which is acceptable for this threat model
  (device-local, single-purpose hobbyist app).
  `EncryptedSharedPreferences` is **not** recommended even as a
  ride-along — androidx.security-crypto was deprecated in 2024 with no
  maintained replacement. If hardening ever happens, wrap the 16-byte
  secret with an AndroidKeyStore AES key directly. The user-facing
  mitigation that *is* in scope is the help-text advice in §2: don't
  reuse a PIN used elsewhere.

## Decisions (formerly open questions — resolved with the panel)

1. **"Follow system" is an explicit, visible 7th option** in the picker
   (a full-width row above the 2×3 theme grid, rendered as a split
   Porcelain/Midnight swatch with an "Auto" badge, §1) and the
   pre-selected default. Defaulting-until-first-choice was a one-way
   door: one swatch tap would permanently lose automatic dark/light
   switching — an accessibility regression for users relying on system
   dark mode.
2. **Bottom sheet stays for settings; Change PIN launches the existing
   full-screen `PinEntryScreen` from the sheet** (§2), the sheet
   dismissing first so Cancel/back lands on the main screen. The PIN
   screen is now cancellable, with the factory-reset warning inline
   above the field and an explicit Cancel-default confirmation dialog
   (hedged wording; filled Cancel vs. a de-emphasized "Replace PIN"
   text button) on Save. The PIN field is
   never embedded in the sheet; no dedicated settings screen.
3. **Snackbars for errors only; no "Triggered!" snackbar** (§3). Success
   is conveyed by flash + haptic + countdown ring. Error snackbars are
   one-shot buffered-Channel events with a Retry action, deduplicated,
   inset above the nav bar, and mirrored in the persistent status chip
   when security-relevant. Snackbar occlusion while the modal settings
   sheet is open is accepted (the sheet renders in its own window; the
   chip mirroring covers it) — no above-scrim anchoring and no custom
   sheet.
