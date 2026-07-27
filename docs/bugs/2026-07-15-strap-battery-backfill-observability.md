# 2026-07-15 — strap death, battery, backfill + observability bug sweep

Captured from a live incident: the strap drained and stopped streaming at **2026-07-14 13:27 PDT**
(last battery reading 10.9% @ 13:00, `charging=0`), a night of data went unscored, and the app was
unable to tell the wearer any of it.

> **Triaged 2026-07-15 (second pass).** The root cause below was found AFTER the original sweep and it
> reframes most of it. Several findings were refuted — including two the first pass called "confirmed"
> — and the refuted claims have been **deleted, not annotated**, here and in the code comments that had
> started repeating them. Line numbers are re-derived against `capture-deep` at triage time; where the
> original pointer had drifted, the drift is noted. Findings not re-verified in this pass say so.

> **Third pass, 2026-07-26 — BLE link-recovery port.** An abandoned local `main` line (diverged
> 2026-07-14, never merged) held unique work against **A1, A2, A4, A6 and B1**. It was reconciled onto
> `origin/main` as `ble-link-recovery-port`; those five sections are updated below. Two things in that
> line were **deliberately not ported**, both cases of the same trap — a change `origin/main` had already
> examined and reversed, which a naive merge would silently resurrect:
>
> 1. the **A5 cap raise** (6 → 120), whose rationale is the §0 contaminated measurement; and
> 2. a rival `StrapBatteryDisplay` that let a stale percent outlive a disconnect — i.e. **B2**, which A3's
>    resolver had deliberately fixed.
>
> A third such change in that line (`Offload: stop archiving v20/v21 as "undecodable"`) is **E2**, already
> reverted on `origin/main` as harmful; it was not ported either. Nothing in this pass is strap-validated —
> both app targets compile and the pure tests pass, which per `CLAUDE.md` proves nothing about BLE
> behaviour.

| | Meaning |
|---|---|
| **CONFIRMED** | re-derived from current code; still true |
| **FIXED** | landed; see the commit/lines named |
| **REFUTED** | was wrong — kept only as a "do not re-derive this" marker |
| **OPEN** | real and unfixed |
| **NOT RE-VERIFIED** | first-pass claim, not re-checked this pass — treat as unconfirmed |
| **NOT-IN-THIS-REPO** | about `noop-cloud`; unverifiable/unfixable from here |

---

## 0. Root cause of the incident — an experimental probe left deep-buffer banking ON

**Confirmed from the device's own prefs.** An experimental probe (`noop.probe.rawCensus`, tag
`burst-capture`, `startedAt 2026-07-14T15:36:21Z`) wrote **16 R22 SET_CONFIG flags** to the strap
(`r22Accepted` 0 → 16). Those flags turn on **deep-buffer banking**: from then on the strap banks one
**1244 B** (IMU) + one **2140 B** (optical) buffer for every **second** of history, on top of the 124 B
v18 per-second record.

**124 B/s → 3508 B/s. Every sync had 28x more data to move, for the same wall-clock of history.**

That is why syncs "suddenly got slow", and it is the single fact that explains the incident's headline
symptoms. The strap browned out at **22:51:34** and the flag cleared.

Current state of the tree:
- `noopWhoop5DeepData` is **still `true`** in prefs — but that is only the app-side *permission*
  (`PuffinExperiment.deepDataKey`, `Strand/BLE/PuffinExperiment.swift:21`).
- **The probe code no longer exists in the tree.** `PuffinRawProbe` / `rawCensus` have no source hits.
- `enableWhoop5DeepData()` (`Strand/BLE/BLEManager.swift:2238`) is reachable from **exactly one place**:
  a Settings button (`Strand/Screens/SettingsView.swift:1381`). It is a one-shot manual send — nothing
  re-applies it on connect. It additionally requires whoop5 family + the Experimental toggle +
  connected + encrypted bond + **worn**.
- `2189f255` reverted a force-enable hack that had made the capture log's `isEnabled` return `true`
  unconditionally.

**So the strap cannot re-enter this state by itself.** But the R22 flags live on the *strap*, not the
phone — so the blast radius of that one button is a persistent 28x tax that survives app restarts and is
invisible from the app. **See "Needs a decision" at the bottom.**

**What this costs the rest of this document:** any measurement taken between 2026-07-14 15:36 and the
22:51 brownout was taken at 28x. Findings that *measured a duration* (A5's "12h+") are contaminated;
findings that read *code structure* are not.

---

## A. BLE / battery / connection

### A1. Battery % on a 5/MG — **headline REFUTED, narrower defect CONFIRMED · FIXED (needs strap validation)**

**REFUTED: "Battery % never lands on a 5.0/MG."** The doc's own A6 evidence disproves it — a
`soc=31.6` reading exists for 2026-07-15. Two mechanisms the first pass missed:

1. **0x2A19 is SUBSCRIBED, not just read.** Discovery calls `requestNotify` on it
   (`BLEManager.swift:3502`), and `enableLiveNotifications` re-subscribes it post-bond (`:2664`).
2. **`didUpdateValueFor` (`:3852-3858`) handles read responses AND notifications identically** — both
   land on `state.setBattery`. So the `:3614` `enableLiveNotifications(reason: "post-bond 5/MG")` call
   **does** restore the battery source. Its comment is not the lie the first pass called it.

**The proof was unsound.** `LiveState.batteryPct` is never set to nil *by anything* — `clearBiometrics()`
(`Strand/BLE/LiveState.swift:467-478`) deliberately leaves it. So `batteryPct == nil` proves 0x2A19 never
delivered **since this app process started** — not "never".

**CONFIRMED, and this is the real defect (narrower):** the one-shot `readValue`
(`BLEManager.swift:3497-3499`) fires in `didDiscoverCharacteristicsFor` — **pre-bond**, which an MG
rejects — and **no code path ever re-issues a read**. `enableLiveNotifications` (`:2657-2669`) only
calls `requestNotify`. Standard Battery Service pushes **on change**, so after every connect the first
reading waits for the SoC to actually move. At the measured 1.65 %/h discharge that is **up to ~36 min
of `nil` per connect** — long enough to look like "never" to a wearer, and the origin of this finding.

**Fix 1b CONFIRMED:** `FrameRouter.swift:202-205` receives 5.0 BATTERY_LEVEL events and reads
`battery_charging` while **discarding `battery_pct`** ("Flag only — battery % keeps its family-specific
source (#77)"). The decode exists. The #77 stub-100 hazard is **4.0-only**, so gating on family would
give a 5/MG a self-healing ~8-min source.

**FIXED** (`Strand/BLE/StrapBatteryReadPolicy.swift`, pinned by `StrandTests/StrapBatteryReadPolicyTests.swift`):
`enableLiveNotifications` now also calls `retryBatteryReadIfDue`, so every moment that already
re-subscribes notifies — post-bond 5/MG, post-bond 4.0, keep-alive, manual refresh, realtime start — is
also a moment the read can self-heal. The policy owns the two constraints that make that safe: the **#77
family gate** (never read a 4.0's stub-100 characteristic, so the fix cannot flash a fake 100% over a real
charge) and a 30 s throttle, which is load-bearing because the call site re-enters on **every**
`.withResponse` ack, including every HISTORY_END during a multi-minute offload.

Fix 1b (using the 5.0 BATTERY_LEVEL event's discarded `battery_pct` as a second source) was **not** taken —
the re-issued read closes the same gap without touching `FrameRouter`'s #77 invariant.

**Still needs strap validation:** this is the BLE connect path, and per `CLAUDE.md` compile-success proves
nothing here. Both app targets build and the pure policy tests pass; the end-to-end behaviour is unverified.

### A2. "Active · Live" overstates a raw link — **CONFIRMED · FIXED (needs strap validation)**

`state.connected = true` at `BLEManager.swift:3108` in `didConnect`, **before**
`discoverPrimaryServices` — true the instant the raw link object exists, before any characteristic or
byte. `DevicesView.swift:101` renders "Active · Live" on `device.status == .active && live.connected`.
Combined with iOS state-restoration of a **dead** peripheral, the UI claimed "live" for a strap silent
21 hours.

The card already carries a narrower guard for the *bond-refused* case (`DevicesView.swift:102-108`,
#221) — which shows the shape of the fix — but nothing gates on data actually flowing. `LiveState` has
the ingredient: `lastFrameAtUnix` (`:203-207`, #987).

**FIXED:** `LiveState.linkProven` (`Strand/BLE/LiveState.swift`) — has this link actually carried a byte,
or completed its handshake? BLEManager clears it in `didConnect` and `willRestoreState`, sets it on first
data or a completed handshake, and restores it on disconnect. `DevicePillState.resolve` takes it as a new
`linkProven:` parameter and gates only the "· Live" half, so an unproven link falls back to plain
"Active" — the honest statement — rather than inventing a new pill state.

The parameter **defaults `true`**, which is what keeps this narrow: the four non-WHOOP sources (Oura,
Huami, FTMS, standard-HR) also drive `connected`, and each already sets it only once genuinely subscribed.
They never touch the flag, so they keep an inert `true` instead of being regressed into looking offline.
Priority order is unchanged — the #221 bond-refused pill still wins. Pinned by
`StrandTests/DevicePillStateTests.swift`.

**Still needs strap validation** (connect/restore semantics).

### A3. Charging was structurally unrenderable without a charge % — **CONFIRMED · FIXED**

`charging` and `batteryPct` come from **different sources and land independently**: `charging` rides the
strap's BATTERY_LEVEL event (~every 8 min, `FrameRouter.swift:202-205`), the % rides 0x2A19. The old
`LiquidBatteryButton` nested the `bolt.fill` **inside** `if let pct = live.batteryPct`, so
"charging, no % yet" could not render — it drew `bolt.slash`, which reads as *battery dead*, at a wearer
sitting on the charger.

**This state is reachable, and reachable precisely during the incident:** BATTERY_LEVEL events are live
traffic and keep arriving mid-offload (only offload frames divert to the backfiller,
`BLEManager.swift:3864`; `FrameRouter` notes "this path never sees historical replay"). So the app held
a **fresh** charging bit and rendered a crossed-out bolt.

> **This survives the "designed" refutation.** `guard !backfilling else { return }`
> (`BLEManager.swift:2482`) does sit above the battery poll, so *not polling mid-offload* is by design —
> and on a 5/MG that poll never runs anyway (`guard selectedModel.deviceFamily == .whoop4`, `:2503`).
> But "we deliberately don't have a %" does not license rendering a **known** charging state as its
> opposite. The absence is designed; the *claim* was wrong.

**FIXED:** `LiquidTodayView.StrapBatteryDisplay.resolve(connected:batteryPct:charging:)` — a pure
3-case resolver (`.offline` / `.pending(charging:)` / `.charge(pct:charging:)`), pinned by
`StrandTests/LiquidBatteryDisplayTests.swift`. Charging now renders without a %; "connected but silent"
is now visually distinct from "no link"; the accessibility label no longer says a bare "Strap battery"
for three different states.

### A4. Zombie restored peripheral spins forever — **NOT RE-VERIFIED · BOUNDED (needs strap validation)**

First-pass claim: after iOS state-restoration returns a dead peripheral, the app marks it connected, the
handshake never completes, and it retries the **same dead handle** indefinitely — 40+ minutes of
`Backfill: deferred — connect handshake not done yet` every 1–3 min; only a force-quit recovered it.

Re-verified only that the log line exists on both platforms (`BLEManager.swift:1562`,
`WhoopBleClient.kt:4893`). The restoration/retry behaviour itself was **not** re-traced this pass, and
it is not strap-testable right now. Treat the *claim* as unconfirmed.

**The mechanism WAS since traced, and it is real** (Swift side): `willRestoreState` marks the link up and
re-discovers services, but the CLIENT_HELLO / bond write that follows is `.withResponse`. On a wedged
strap it is never acked, so `didWriteValueFor` never fires — and that is the **only** caller of
`startKeepAlive()`, so the 120 s liveness watchdog inside `keepAliveFire` (the machinery that would have
bounced exactly this link) is never armed. Nothing else bounds the retry.

**BOUNDED:** `Strand/BLE/StalledHandshakePolicy.swift` arms a 90 s fuse at connect **and at restore**, then
cancels the peripheral so the existing 3 s disconnect-rescan recovers the link. It deliberately adds no new
reconnect mechanism. The fuse is picked against the fuses already in the tree — above the 60 s reboot-settle
backstop, below the 120 s liveness fuse — and every input to `shouldRecover` is a reason to stay out of the
way (`sawData`, `handshakeDone`, `intentionalDisconnect`, `autoReconnectPaused`, `bondRefused`), so it fires
only on the narrow case nothing else owns. Pinned by `StrandTests/StalledHandshakePolicyTests.swift`.

This bounds the failure mode whether or not the original 40-minute claim is ever reproduced. **Kotlin twin
not written** — Android has no `willRestoreState` equivalent, so the trigger does not exist there.
**Still needs strap validation.**

### A5. `maxAutoContinues = 6` throttled a healthy recovery — **REFUTED · DECIDED: the cap stays at 6**

**This finding was the sweep's "worst practical impact". It does not survive §0.** It was briefly
"fixed" (6 → 120, `2b968d27`) and then deliberately **reverted** (`d2ec5dc3`, cap back to 6) once the
root cause was known. The reasoning is now recorded at the constant on both platforms so it is not
"fixed" a third time:

- **The severing evidence came from a strap the probe had switched into R22 deep-data banking** —
  3508 B/s of history vs the default **124 B/s**. A default strap banks **28x less**, so one session
  covers ~28x more history and an 18 h backlog drains in **~2 sessions**. The cap never fires.
- **Raising it widens the blast radius of the next guard-2 bug.** #928 and #1012 both burned the *whole
  cap* in EMPTY offloads — the cap is what bounded them.
- **It applies to WHOOP 4.0 too**, where a long drain starves the realtime keep-alive (`guard
  !backfilling`, #160).

> **It was re-raised a third time, and refused (2026-07-26).** The abandoned local `main` line carried a
> commit raising the cap to 120 behind an `isDeepDrain` evidence gate. Its rationale — "each session
> recovers ~29 min of strap history, so an 18-hour backlog needs ~37 sessions" — **is the §0 contaminated
> measurement**, taken at 28x banking. When that line's unique work was ported onto `origin/main`, the cap
> change was dropped and `BackfillContinuation` left byte-identical; `StrandTests/BackfillContinuationTests`
> (21 tests) passes unchanged. Note the gate itself was the shape this section invites ("a conditional
> bypass while provably behind and advancing"), so the *mechanism* is not what was rejected — the
> **evidence** is. A raise still needs a deep-backlog case demonstrated on a **default** strap.

The "~12h+ to recover a night" that made this finding look severe was measured **at 28x** (§0). Remove
the 28x and the arithmetic that condemned the cap evaporates.

**FIXED (the half that was real):** the idle watchdog 60 → **15 s** on both platforms
(`BLEManager.swift:1676`, `WhoopBleClient.kt:565`, `d2ec5dc3`). Every session paid 60 s in full as dead
air on exit, and 60 s was set without measuring the inter-frame tail — a 3,018-buffer trace from a real
drain puts gaps at p50 64 ms / p90 106 ms / p99 273 ms / **max 2,520 ms**. 15 s is ~6x the measured max
and still clears the type-43 flood. (Not the ~5 s the tail alone would justify: one drain, one firmware,
and the risk is asymmetric — firing early is safe but wasteful, firing late only costs idle seconds.)

**REFUTED, do not re-derive:**
- **There is no "~25.4 KB/s airtime ceiling"** and no "~7x real-time". That was a one-window fit; it did
  not survive re-measurement across a dozen windows. The absolute link rate is not a constant of this
  system. Both figures are gone from the tree.
- **The "~1.19x throughput" (and the "~3.7x → ~1.0x collapse") was never a bug.** The strap was **CAUGHT
  UP**. History cannot drain faster than the rate at which it is banked — with no backlog, **~1.0x is
  the floor**, not a symptom.

Drain time is `(bytes banked per second-of-history) / (bytes the link moves per second)` and **both
terms move**. Any single headline multiplier is a property of one capture.

**REFUTED, do not re-derive:**
- **There is no "~25.4 KB/s airtime ceiling"** and no "~7x real-time". That was a one-window fit; it did
  not survive re-measurement across a dozen windows. The absolute link rate is not a constant of this
  system. Both figures have been removed from the cap rationale on both platforms and from
  `PuffinDeepBufferLog.swift`.
- **The "~1.19x throughput" was never a bug.** The strap was **CAUGHT UP**. You cannot drain history
  faster than it is generated — with no backlog, **~1.0x is the floor**, not a symptom.

Drain time is `(bytes banked per second-of-history) / (bytes the link moves per second)` and **both
terms move**. Any single headline multiplier is a property of one capture.

### A6. "Charging flag never lands" — **REFUTED as stated · OPEN (reframed)**

The finding's own evidence contradicts its title. The row was `soc=31.6, charging=0`:

- **`charging=0` is not "never landed" — it is "landed and read false".** A flag that never landed is
  **NULL**: `Streams.swift:374-380` sets `charging` only when the frame actually reported it
  (`charging: Bool?  // only the BATTERY_LEVEL event reports this; nil otherwise`). A non-NULL 0 proves
  the decode ran *and* the strap's own event said not-charging.
- **It was not a "live row".** `battery` table rows are inserted from `streams.battery`
  (`StreamStore.swift:132`) — the **decoded offload stream**. Its `ts` is the strap's RTC stamp on a
  *banked* record, not wall-clock "now". So "charging=0 **while actively charging**" compares a
  historical record's timestamp against an observation made at inspection time. The doc never
  established the charger was on at strap-RTC 10:58:06.

**OPEN (reframed):** whether the 5/MG's BATTERY_LEVEL bit0 tracks charging **at all** is genuinely
unknown and worth settling — but it needs a controlled capture (put it on the charger, watch
`state.charging` change), not a single historical row. A3's fix is what makes that flag visible enough
to observe. **This stays OPEN: nothing below settles the question.**

**MITIGATED, so a wrong bit stops costing the wearer:** `Strand/BLE/StrapChargeInference.swift` stops
treating that one unverified bit as the only witness. A **rising SoC** is direct, family-independent,
decoder-independent evidence of a charge, and this codebase already trusts exactly that inference —
`BatteryEstimator.chargeStepPct` is documented as "a SoC rise larger than this marks a CHARGE", and the
runtime estimate has always restarted its discharge run on it. `LiveState.chargingEffective` resolves the
flag against that second witness; the UI reads it, so a strap on the puck reporting `charging=0` no longer
renders "not charging" **and** no longer shows a "~2 days left" discharge estimate.

Strictly additive, and deliberately so: a confirmed `true` stays `true`, inference can only ever ADD a
charge and never deny one, and with neither witness it answers `nil` (unknown) rather than asserting
"not charging". **`LiveState.charging` remains the raw WIRE value** and is what gets persisted — so no
stored value diverges from Kotlin and no `.noopbak` contract moves. Pinned by
`StrandTests/StrapChargeInferenceTests.swift`.

Because it is display-only, this **does not** substitute for the controlled capture — it makes the app
correct either way while the question stays open. Kotlin twin is a UI-parity item, not a data one.

### A7. `lastSeenAt` frozen at pairing → "last seen 18 days ago" — **CONFIRMED · OPEN (both platforms)**

```
my-whoop | added 2026-06-26 12:06:20 | lastSeen 2026-06-26 12:06:20   ← IDENTICAL
oura-api | added 2026-07-10          | lastSeen 2026-07-13            ← updates fine
```

`lastSeenAt` is stamped **only** on wizard-add and on promote-to-active, never refreshed from a live
link:
- Swift — `Packages/WhoopStore/Sources/WhoopStore/DeviceRegistryStore.swift:40`
  (`UPDATE pairedDevice SET status = 'active', lastSeenAt = ?`) and the upsert at `:156-163`.
- Kotlin — `android/app/src/main/java/com/noop/data/DeviceRegistryDao.kt:34`, same shape
  ("Promote one device to `active` and stamp its lastSeenAt").

**Shared stored-data semantics ⇒ a fix needs the Kotlin twin in the same commit.** Cheap in principle
(stamp on a connect/frame event); left open because *where* to stamp is a connect-path decision.
**Needs the user.**

---

## B. Observability (the wearer's #1 ask)

> "All of this stuff, like backfill progress, should be something you can see on the app. It is kind of
> misleading me right now."

### B1. Backfill progress invisible — **PARTLY REFUTED · iOS half FIXED · remainder now FIXED**

**REFUTED as "the app never surfaces it."** It does, on macOS and in the menu bar:
`SyncingHistoryNote` ("Syncing strap history… N chunks pulled", `Strand/Screens/ScreenScaffold.swift:199`)
is used by Sleep (`SleepView.swift:2621`), Intelligence (`IntelligenceView.swift:326`) and the menu bar
(`MenuBarContent.swift:269`); the classic `TodayView` has `SyncStatusChip` (`:4417-4430`).

**CONFIRMED, precisely, for iOS — which is where the wearer was looking.** `RootTabView.swift:50` picks
`LiquidTodayView()` when the Liquid flag is on, and **`LiquidTodayView` had no backfill indication at
all**. Not a missing feature — a **v8 redesign regression**, the same class as #992, which dropped the
"~X days left" runtime estimate from the strap-battery row (as that row's own comment records,
`LiquidTodayView.swift:1593-1595`).

**FIXED (iOS):** `LiquidSyncStatusRow` in the Data Sources card — "Strap history / Syncing… N chunks"
while `live.backfilling`, else "Synced N ago" from `live.lastSyncedAt`.

**FIXED — the "how far behind" half** (2026-07-26). *"Recovering strap history / Have it through July 14,
14:31 — ~15h behind"* now renders, via `LiquidBackfillProgressRow` over the pure
`Strand/BLE/BackfillProgress.swift`.

The plumbing objection above was right, and this is how it was resolved without a new Repository read:
BLEManager **already** reads the frontier (`collector.latestHRSampleTs()`) once per offload session, for
the auto-continue decision — exactly the cadence the readout wants. It now publishes it there via
`LiveState.setDataFrontier`, so `backfillProgress` computes over `connected` / `backfilling` /
`dataFrontierUnix` / `strapRange`, all already `@Published`. No extra query, no third piece of state.

Two properties worth keeping: the resolver reuses `BackfillContinuation.defaultBehindGapSeconds`, so the
readout can never claim "behind" about a gap the drain has already decided is closed; and a frontier
*ahead* of the strap's reported newest (the #451 stale-epoch and #928 future-clock cases) resolves to
`.unknown` rather than a negative backlog. It also answers **between** sessions — `.pending` — which is the
state the wearer actually misread as "my data is gone". `LiquidSyncStatusRow` is kept and still answers
*that* a drain is running. Pinned by `StrandTests/BackfillProgressTests.swift`.

### B2. Battery shown with no staleness marker — **CONFIRMED · FIXED (worst case)**

A 21-hour-old reading rendered identically to a live one. Mechanism found: **`LiveState.batteryPct` is
never cleared** (§A1), so `LiquidBatteryButton` — which keyed off `batteryPct` alone with **no
`connected` gate** — kept rendering a dead strap's last charge as if current. The
`LiquidStrapBatteryRow` **directly below it** already required `live.connected`
(`LiquidTodayView.swift:1573`), so the two Liquid surfaces actively disagreed; the button was the wrong
one.

**FIXED** by the same `StrapBatteryDisplay.resolve` gate (A3): no link ⇒ `.offline`, never a stale
number. Pinned by `testStaleChargeIsNotShownOnceTheLinkIsGone`.

*Still open (softer):* a **connected** strap whose last reading is old has no age marker. "Last seen 21h
ago at 11%" needs a reading timestamp `LiveState` doesn't currently keep for battery.

### B3. Rest carries but Charge/Effort don't — **CONFIRMED · Charge FIXED (Effort correctly excluded)**

On a dead-strap / unscored-today day the Liquid Today (the DEFAULT screen) showed a plausible Rest —
carried from the last scored night — beside a blank Charge and a blank Effort, and the Charge state pill
still read "Calibrating" at a wearer 14 nights past the seed gate. Root cause: the Liquid heroes read
`displayDay?.recovery` / `.strain` **raw**, while the Rest hero carried (`freshRestScore`, #977) and the
vitals carried (`Repository.lastVitalsDay`) right beside them. Liquid was the ONLY surface that blanked —
classic `TodayView` carries via `lastScoredCharge` (#543), the widget/watch/Live Activity via
`Repository.widgetAnchor` (#911), and Android via `TodayScreen.lastScoredRecoveryDay`.

**FIXED (Charge):** `LiquidTodayView.ChargeDisplay.resolve` now carries the last scored night's REAL
Charge, labelled "Last night · <date>" (or "Latest sleep · <date>" once #779-stale), stamped by the SAME
`TodayView.lastScoredRecoveryDay` + `carriedCaption` the classic screen uses, so the two Today screens
cannot drift. The state pill now tells the truth — only a genuinely calibrating baseline reads
"Calibrating"; a trusted baseline with no scored night reads honest "No data". The Key-Metrics Recovery
tile reads the same resolved value so the tile and hero can't disagree. Pinned by `LiquidChargeCarryTests`
(9 cases, pure resolver). Verified end-to-end in the simulator on the real on-device DB with today's row
nulled to stage the rollover: before = Charge "–" / pill "Calibrating"; after = Charge 47 carried from
Jul 14 / pill "Last night".

**Effort deliberately does NOT carry** — Effort is *today's* accumulation, so painting yesterday's number
onto today would be a false statement, not a stale one (the same reason a blind `COALESCE` on the stored
`strain` column would be wrong). It stays blank until tonight scores.

**Relationship to F1:** this carry SOFTENS F1's user-visible blank-out (the last good Charge shows instead
of "–"), but does NOT fix F1's root cause — the day's OWN row is still nil because the stale HRV baseline
gated it. F1's scoring-side change remains deferred to the user (see F1).

### B4. Battery invisible over MCP — **NOT-IN-THIS-REPO**

The `battery` table (soc/mv/charging) and the `event` table (**11,457 rows**, `BATTERY_LEVEL` carrying
`battery_pct`) were never exposed. `battery_series`, `device_events`, `imu_coverage` are built + tested
in noop-cloud (branch `tz-upload`), pending deploy. Tracked there, not here.

### B5. Burst-capture census + firmware are not in the DB — **PARTLY STALE · OPEN (firmware half)**

**Stale:** the census half described `PuffinRawProbe.finishSession()` writing R22 census JSON to a file +
`UserDefaults(noop.probe.rawCensus)`. **That class no longer exists in the tree** (§0) — there is no
census producer left to migrate.

**CONFIRMED (firmware half):** firmware lives only in `UserDefaults("noop.lastFirmware")`
(`FrameRouter.swift:99`), and neither `device` nor `pairedDevice` has a firmware column. `ingest.ts`
dropping `settings.json` is a noop-cloud fact. ⇒ strap firmware is invisible to the MCP without a
producer-side migration.

---

## C. Alerts — all three CONFIRMED, all OPEN, Kotlin twins match exactly

### C1. Battery alerts fire once, then latch — no escalation — **CONFIRMED · OPEN**

Device state: `batteryLowAlerted = true`, `batteryRuntimeAlerted = true` — **both fired, and the strap
still died.** `BatteryAlertPolicy` is nested in `Strand/System/BatteryNotifier.swift:19` (not its own
file): `lowThreshold = 15` (`:20`), `lowRearmAbove = 25` (`:21`). The latch is real (`:45-51`) —
re-arm is gated **only** on SoC recovering to 25%, so once low fires at ≤15% nothing re-fires before a
charge. The runtime alert fires at `runtimeAlertHours = 24`
(`Packages/StrandAnalytics/Sources/StrandAnalytics/BatteryEstimator.swift:255`) — at the measured
1.65 %/h that is **39.6% SoC, ~18 h early** — then latches the same way (`:271-275`). Net: **~3 hours of
silence from 15% to death.** Both toggles default ON (`Strand/Data/BehaviorStore.swift:100-101`), so
that is not the explanation.

**Correction to the first pass:** it recorded "*Being fixed:* critical 12% alert + bedtime night-guard".
**Neither exists anywhere in the tree, on either platform.** There are exactly three battery
notification ids (`battery-low`, `battery-full`, `battery-runtime`) and three persisted gates. That work
was aspirational, not landed.

### C2. Runtime estimate ignores the strap's cutoff — **CONFIRMED · OPEN**

`BatteryEstimator.swift:99`: `let remaining = max(0, current) / rate` — time to **0%**. The strap appears
to stop near ~10%, so every estimate overstates runway. **No cutoff constant exists on either platform.**
The `1.5x` rated-life clamp at `:104` is a runaway ceiling, not a cutoff — it does not subtract the
unusable tail. *Caveat: the ~10% cutoff is an incident observation, not a documented constant — the
value a fix should use is not derivable from this repo.*

### C3. Rated-path predictive alert is unreachable on a 5.0 — **CONFIRMED · OPEN** (pointer drifted)

`ratedLifeHoursWhoop5 = 288` now lives at `BatteryEstimator.swift:23`; the first pass pointed at the
*selection* site, now `BLEManager.swift:1021-1022`. On the rated path `rate = 100/288 = 0.3472 %/h`, so
`remaining = current × 2.88` and the 24 h line falls at **8.33% SoC — below the cutoff**. The `:104`
clamp is never binding here, so it doesn't rescue it. A 5.0 falling back to the rated path gets **no
predictive alert, ever**. (A 4.0 at `ratedHours = 108` crosses at 22.2%, comfortably reachable — this is
5.0-specific.)

**Parity note (all of C):** the Kotlin twins exist and **every constant matches** —
`com/noop/notif/BatteryAlertNotifier.kt:23`, `com/noop/analytics/BatteryEstimator.kt`,
`com/noop/ble/WhoopConnectionService.kt:262-263`. C2 is mirrored at `BatteryEstimator.kt:94`, C3 via
`ratedLifeHoursWhoop5 = 288.0`. **The bugs are portable, not absent** — any fix lands on both platforms
in the same PR.

---

## D. Cloud sync (fork-local; compile-gated behind `CLOUD_SYNC`, absent from upstream and default builds)

### D1. Upload can never complete from the background — **CONFIRMED · OPEN** ("fix built" was wrong)

`CloudSyncClient.swift:34` still uses `URLSession.shared`. **Zero** `beginBackgroundTask`, **zero**
background `URLSessionConfiguration` in app code. A push grants ~30 s; deflate-zipping ~400 MB
(→ ~81.5 MB) blows past it and in-flight tasks die on suspension.

**Correction: the "*(fix built)*" tag was wrong and is removed.** The working tree's new
`DeepBufferUploadPlan`/`DeepBufferUploader` add **chunking + a resume watermark** (16 MB chunks, 8 per
run, watermark persisted per chunk) to a **new `/deepbuf` lane** — that bounds how much a suspension
destroys, but the chunks still ride `URLSession.shared` and still die on suspension. **The `/ingest`
whole-DB lane D1 actually indicts is untouched** (`CloudSyncClient.swift:78-97` still streams the whole
file in one shot).

`cloudsync.lastStatus` is written on **every** completion, success or failure —
`Self.persistLastStatus(line)` (`CloudSyncModel.swift:260`) sits *outside* the `if succeeded` block
(`:257-259`). It is only reached if `performSync` runs to completion, which is exactly why the device
showed it **frozen** at `"Uploaded 81.5 MB · 12:21 PM"` rather than showing a failure — the two halves of
this finding corroborate each other.

### D2. Warm resume triggers no sync — **CONFIRMED · OPEN**

`autoSyncIfDue` has exactly two call sites: `StrandiOS/App/RootTabView.swift:133` (iOS, inside `.task {}`,
which does not re-run on resume) and `Strand/App/RootView.swift:324` (macOS only — excluded from the iOS
target). `scenePhase == .active` (`StrandiOSApp.swift:205-223`) does not call CloudSync.

*Two sharpenings:* `scenePhase` **does** reach CloudSync on the way **out** —
`CloudSyncBackgroundRefresh.schedule()` in the `.background` branch (`:233-238`) — and
`backgroundSyncIfDue` (`CloudSyncModel.swift:404`, 4 h gate) serves the BGAppRefreshTask/silent-push
lane. The gap is specifically **warm resume**, not background sync in general.

### D3. Server volume ~1 upload from ENOSPC — **NOT-IN-THIS-REPO**

1008 MiB total, ~461 used, ~547 free; an ingest needs ~400 MB staged alongside the ~460 MB mirror.
`MAX_INGEST_BYTES = 768MB` exceeds the entire volume. `/ingest` logs nothing on receipt, success, or the
400 path. **None of this exists in this repo** — no `ingest.ts`, no server sources. Belongs to the
`noop-cloud` checkout.

---

## E. Deep data / MG

### E1. 2140 B optical frames have no home — **diagnosis REFUTED · the real gap is CONFIRMED**

**REFUTED — the stated action does not achieve the stated goal.** The claim was that the DB lacks
`ppgWaveformSample` because the installed build predated upstream's `v27-ppg-waveform` (#415), so
"rebuild + install current fork main to start storing them".

The migration **is in this tree** (`Packages/WhoopStore/Sources/WhoopStore/Database.swift:591`), highest
is `v29-daily-avg-sdnn` (`:619`), and the Room twin is at parity (`WhoopDatabase.kt:550`, pinned by
`PpgWaveformMigrationTest.kt`). The "top out at v28" reading was internally consistent — the fork's local
`v28-phone-timezone` predated the merge, and GRDB keys on the identifier *string*, so the out-of-order
insert applies cleanly.

**But it stores the wrong thing.** `ppgWaveformSample` is fed **only** from `p["ppg_waveform"]`
(`HistoricalStreams.swift:214-217`), emitted **only** by `decodeWhoop5HistoricalV26` — **hist_version 26**
records. The 2140 B buffers are **v20** (`Interpreter.swift:587`), decoded into `channel_b{n}_{half}`
fields that **nothing consumes**. The commit seals it: `ee1de9a7 … persist the WHOOP 5.0 **v26** raw PPG
waveform`.

**CONFIRMED (the real gap):** the 2140 B v20 optical buffers — the SpO2/BP substrate — still have **no DB
table in this tree**. Rebuilding gives *v26 PPG* a home; it does nothing for v20. **Needs the user** (a
real feature, not a rebuild).

**Their only durable path today is the reject archive** (`rejected_history.jsonl`), which
`BLEManager.swift:925-930` replays and **retro-decodes on version bump** — so the bytes survive until a
v20 decoder lands. That is exactly why exempting v20/v21 from that archive destroyed them (§E2), and why
the archive must not be "optimised" away before v20 has a table. The `puffin-deepbuffers.jsonl` research
log is **not** a substitute: it is default-OFF and gated on a different key.

### E2. 1244 B frames logged as "rejected" — **REFUTED (nothing was wrong) · the "fix" for it was harmful and is REVERTED**

**"Rejected" never meant "dropped".** `rejectedHistoricalRecords` (`HistoricalStreams.swift:66`) is an
independent *classification* pass, not a decode gate: decoded rows commit separately
(`Backfiller.swift:554`), and the rejected set only selects frames whose **raw bytes get archived** to
`rejected_history.jsonl` for later RE (`:580-586`) so the ack can't free the strap's only copy of an
unmapped layout. A frame there means "no consumer claimed it — **keep the bytes**", not "data lost".

Why 1244 B appeared: the predicate (`:99-100`) logs anything with no `unix`, or with neither
`heart_rate` nor `gravity_x`. v21 (1244 B) carries `optical_ch0/1/2` and neither of those → logged. No
contradiction with working IMU decode — that runs through `Whoop5RawImu.decode` in the deep-buffer path,
a **different consumer**. Both facts were always true. **"Needs investigation" was the right instinct;
the frames were fine.**

> **Cautionary tale — this finding caused a data-loss bug.** Acting on it, `7942f667` exempted v20/v21
> from the reject archive ("they decode, for the same reason v26 is exempted one line above"). That is
> backwards, and `86d414ad` reverted it. **v26 is safe to exempt because it HAS a durable home**
> (`Streams.ppgWaveform` → `ppgWaveformSample`), so the archive is redundant for it. **v20/v21 have no
> home** (§E1) — `decodeWhoop5HistoricalV2021` writes `optical_ch0..2` / `channel_b*` into an in-memory
> frame with **zero production consumers on either platform**, and the frame is discarded. With the
> exemption: not archived, not persisted, **trim acked — and the strap frees the buffers permanently**.
> `PuffinDeepBufferLog` does not catch them either (default-OFF, and gated on
> `PuffinFrameRecorder.enabledKey`, a *different* key from the deep-data experiment).
> Two things that revert surfaced: `RawHistoryArchive.perVersionFloor` (`:27-32`) names "WHOOP 5 v20/v21"
> as the exact rare-layout case its eviction floor was built for (#344) — the exemption deleted precisely
> what a prior fix exists to preserve — and `BLEManager.swift:925-930` replays the archive and
> **retro-decodes it on version bump**, so for v20 (micro-decode unsolved) that archive is the ONLY path
> by which the data is ever recovered once a decoder lands.
> The exemption's throughput rationale was also the refuted "~3.7x → ~1.0x collapse" (§A5) — the strap
> was caught up.

**Current state: reverted; the archive preserves v20/v21 again.** The real problems it gestured at are
real but live elsewhere: the false "N records couldn't be decoded" banner should stop counting known
layouts (`BLEManager.swift:1801-1803`), and the archive's at-cap read-all + hex-decode + 5 MB atomic
rewrite per chunk should be fixed **in** the archive. **Both OPEN.**

### E3. Deep IMU capture is barely running — **mechanism CONFIRMED · evidence CORRECTED**

**Mechanism CONFIRMED, and §0 explains the 58-minute window.** Banking needs **two** default-off gates
plus a manual tap: the Experimental toggle (`PuffinExperiment.deepDataEnabled`) **and** the capture
toggle (`PuffinFrameRecorder.enabledKey`), plus a one-shot Settings button press
(`SettingsView.swift:1381`) while connected + encrypted-bonded + **worn**. Nothing re-applies R22 on
connect. "One 58-minute window across 8 months" is precisely the shape that produces.

**Evidence corrected:** there is **no `imuActivity` table anywhere in this tree** — not in
`Database.swift`, not in Room. `ImuActivityFeatures` is serialized only as the `"imu"` field inside
`puffin-deepbuffers.jsonl`, and **only for live frames, never offload**
(`PuffinDeepBufferLog.swift`: `let imu = isOffload ? "" : Self.decodedImuField(frame)`). So "2,945 rows"
is a cloud-side/derived artifact. **Reconcile where those rows come from before acting on E3.**

---

## F. Scoring

### F1. Charge silently vanishes after 14 stale nights — **CONFIRMED · comment FIXED · code change needs the user**

All four first-pass pointers were **exact**. `Baselines.swift:40` documented `.stale` as "usable but no
update for > STALE_DAYS nights", while `:69` defines `usable = (status == .provisional || status ==
.trusted)` — **`.stale` excluded** — with `staleDays = 14` (`:102`) and `.stale` assigned at `:223`.
`RecoveryScorer.swift:417` then does `if !hrvBaselineUsable { return nil }`. ⇒ 14 nights without a valid
HRV night ⇒ **Charge silently returns nil**, no explanation surfaced.

**Resolved: the COMMENT was wrong, not the code.** Excluding `.stale` from `usable` is deliberate and
coherent across **every** consumer (`AnalyticsEngine`, `DailyStressEngine`, `ReadinessEngine`,
`ScoreConfidence`, `WatchRecovery`, `DaytimeBaselines`, `AppModel`, `IntelligenceEngine`): `usable` means
"trust this personal baseline", and one nobody has confirmed in >14 nights is exactly one not to trust.
The comment's word "usable" simply collided with the property name. Current behaviour is **pinned by
tests on both platforms** (`VitalBandsTests.testStaleBaselineFallsBackToPopulation`,
`VitalBandsTest.staleBaseline_fallsBackToPopulation`).

**FIXED (comment only, both platforms):** `Baselines.swift` + `AnalyticsModels.kt:179-180` — and the
sibling `usable` doc, which claimed "at least provisionally usable (nValid ≥ MIN_NIGHTS_SEED)"; `.stale`
also clears that bar and is still excluded. **Changes no numbers.**

**The silent-nil half is still OPEN and is a product decision.** Making `.stale` usable would flip
`VitalBands.basis` to personal (breaking pinned tests on both platforms), un-gate `RecoveryScorer`, and
move skinTempDev / Readiness / ScoreConfidence / illness signals — a **stored-value + parity change**.
The narrower fix — *surface* "no Charge: baseline stale" instead of a blank — changes no numbers.
**Needs the user.**

**Escalation the first pass under-stated.** `baselines2.hrv` is folded to a single **end-of-history**
snapshot (`IntelligenceEngine.swift:875`) and that one state gates **every** day re-scored in pass 2.
Dead nights enter the fold as `nil` (`:843`; B3 verified an all-NULL row exists), so 15+ trailing
nil-HRV rows ⇒ `.stale` ⇒ `!usable` ⇒ `recovery` returns nil **for the whole re-scored window**, not just
the current night. It self-heals the instant one valid night lands. **Unanswered — answer this first:**
do those nils get written back over previously-stored non-nil `recovery` values? That is the difference
between "Charge is blank today" and "**Charge history got overwritten**".

---

## Needs a decision

1. **§0 — the R22 button.** Deep-buffer banking is a **persistent 28x tax written to the strap**, it is
   never re-applied *or cleared* on connect, and the app cannot see it. The probe that tripped it is
   gone, but the Settings button remains. Options: leave it (documented), have the app clear R22 on
   connect unless deep data is deliberately on, or surface "deep banking is ON" in the UI. Also:
   `noopWhoop5DeepData` is **still `true`** on the device — decide whether to clear it.
2. **A1 / A2 / A4 / A7** — all BLE/connect-path or shared-registry changes. Each needs strap validation
   (`CLAUDE.md`: compile-success proves nothing here) and A7 needs a Kotlin twin. Not touched while a
   live measurement is running.
3. **C1 / C2 / C3** — the alert policy is a product judgement (what SoC, what escalation, what cutoff),
   and the ~10% cutoff isn't a known constant. All three need Kotlin twins.
4. **F1** — surface the stale-baseline reason (no numbers move) vs. make `.stale` usable (numbers move,
   pinned tests break, needs a twin). And answer the overwrite question first.
5. **E1** — giving the 2140 B v20 optical buffers a home is a migration + decoder feature, not a rebuild.
   Until then the reject archive is their only durable path — do not exempt or evict them (§E2).
6. **E2 fallout, both OPEN** — the false "N records couldn't be decoded" banner should stop counting
   known layouts (`BLEManager.swift:1801-1803`), and the reject archive's at-cap read-all + hex-decode +
   5 MB atomic rewrite per chunk should be fixed **in** the archive. These are the real costs the
   destructive v20/v21 exemption was reaching for.

---

## What this sweep should teach the next one

Three of the sweep's most confident findings — A5 ("worst practical impact"), A6, E2 — were artifacts,
and one of them (E2) produced a **data-destroying "fix"** before the root cause was known. The pattern is
identical each time: **a measurement taken in an unknown state was read as a property of the code.**

- **A5 / §0** — 28x more bytes per second-of-history was read as a duty-cycle bug.
- **A6** — a historical banked record's strap-RTC timestamp was read as "now".
- **E2** — a preservation mechanism ("keep these bytes") was read as a failure ("rejected").
- **A1** — a `nil` that means "not since process start" was read as "never".

Find the state before optimising against the symptom. And when a finding says *"unexplained — needs
investigation; do not guess"* (E2 said exactly that), that instruction outranks a plausible-looking fix.
