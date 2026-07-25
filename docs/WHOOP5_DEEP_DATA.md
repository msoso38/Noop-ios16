# WHOOP 5.0 / MG deep data — the "R22" unlock

**Status:** experimental, opt-in, awaiting on-hardware confirmation.
**Tracking:** [#103](https://github.com/ryanbr/noop/issues/103) (raw HCI captures + new deep-record layouts).

## The problem

A WHOOP 5.0 / MG strap hands a freshly-connected third-party client **only live heart rate** (over the
standard `0x2A37` profile, which needs no bond). Recovery, strain, sleep, motion and history don't come
through. This is the single biggest gap in NOOP's 5/MG support, and it affects every independent WHOOP
app equally.

## Why — the feature-flag gate

The official app switches on the deeper streams by writing a short burst of **persistent feature-flag
config values** to the strap right after the hello handshake. The most load-bearing of these is
`enable_r22_packets`; "R22" is the strap's **optical/PPG data-product packet format** (versions v1–v8),
not a hardware revision. Until those flags are set, the strap keeps the deep streams to itself.

This was reached independently three ways, which is why we trust it:

| Source | Method | What it gives |
|---|---|---|
| [judes.club — "Cracking the WHOOP 5 Bluetooth Protocol"](https://judes.club/writing/cracking-the-whoop-5-bluetooth-protocol/) + [interactive spec](https://judes.club/experiments/whoop5/) | iOS HCI capture of the official app | The full frame format + the exact 15-flag enable sequence **with values**. Our `Whoop5Config` golden test is validated byte-for-byte against its frame-builder. |
| [Asherlc/dofek](https://github.com/Asherlc/dofek/blob/main/docs/whoop-ble-protocol.md) | Android APK decompilation | The config opcodes (`0x73 START_DEVICE_CONFIG_KEY_EXCHANGE`, `0x78 SET_FF_VALUE`) and the same key names/values. |
| A community BTSnoop capture ([#103](https://github.com/ryanbr/noop/issues/103)) | Bluetooth HCI log of the official app on a real strap | Independently surfaced the same `enable_r22_*` console report + the channel layout. |

## Channel layout (5.0 / MG)

| Channel (UUID suffix on `fd4b0001-…`) | Direction | Carries | NOOP |
|---|---|---|---|
| `0x2A37` standard HR | strap → app | live heart rate | subscribed ✅ |
| `fd4b0002` | app → strap | `0xAA`-framed commands | writes here ✅ |
| `fd4b0003/4/5/7` | strap → app | `0xAA`-framed responses + data + console | subscribes to all four ✅ |

NOOP already writes commands **and** subscribes to every data channel. So the blocker is not that NOOP
isn't listening — the strap simply doesn't *start* the deep streams for a session that hasn't set the
flags.

## The frame format

Commands use the maverick/puffin envelope NOOP already implements
(`Framing.puffinCommandFrame` / `crc16Modbus` + `crc32`):

```
[0xAA][0x01][declLen u16 LE][field=0x0100][CRC16-MODBUS of the 6 header bytes]
  [inner: 0x23 type][seq][cmd][b3][payload…]
[CRC32 of inner, u32 LE]
```

- **`b3` (4th inner byte)** matters: GET_HELLO / SET_CONFIG want `0x01`; GET_DATA_RANGE /
  SEND_HISTORICAL want `0x00`. NOOP carries `b3` as the first payload byte (so `sendHistoricalData`
  with `[0x00]` is correct).
- **Write WITH RESPONSE** — write-no-response is silently dropped by the strap.

## The enable sequence (`Whoop5Config`)

One `SET_CONFIG` (cmd `0x78`) per flag; the 40-byte body is the flag name as ASCII NUL-padded to 32
bytes, the value byte (an ASCII `'1'`/`'2'`) at offset 32, then 7 zeros. The exact ordered set, with
values, is in [`Whoop5Config.swift`](../Packages/WhoopProtocol/Sources/WhoopProtocol/Whoop5Config.swift)
and [`Whoop5Config.kt`](../android/app/src/main/java/com/noop/protocol/Whoop5Config.kt), golden-tested on
both platforms. `enable_r22_packets` is the one that opens the type-`0x2F` biometric stream; the rest
tune channel selection, wear detection and sleep behaviour. Flags 1–15 come from judes.club's
frame-builder; the 16th, `enable_sig12`, was added from a real on-strap HCI capture ([#103](https://github.com/ryanbr/noop/issues/103))
that otherwise reproduced flags 1–15 byte-for-byte in this order.

## How NOOP uses it (opt-in, reversible)

- A **default-off** Settings → Experimental toggle, separate from the read-only probes because this one
  *writes* to the strap.
- A manual **"Send enable sequence to strap"** button (not auto-run on connect), enabled only when a
  5/MG is **bonded and worn** (the R22 stream is on-wrist gated).
- The 16 flags are written with-response, ~80 ms apart.
- It's **reversible** — it only changes which data the strap chooses to emit — and is the same thing the
  official app does on every connect.
- **iOS / Android only on real hardware:** macOS CoreBluetooth can't complete the authenticated SMP bond
  the command characteristic requires, so the write path is unavailable on Mac.

## Honest limits

- **No cloud scores.** Recovery/strain/sleep *scores* are computed in WHOOP's cloud and no public
  project has reproduced them. What the unlock buys is the **raw inputs** (high-rate HR, motion, fuller
  history) — which is exactly what NOOP needs, since NOOP computes its own scores on-device.
- **It may not even be necessary.** [goose #24](https://github.com/b-nnett/goose/issues/24) shows a Gen5
  band streaming type-47 history to a third-party app *without* any config write. So the first thing to
  confirm is whether a clocked 5/MG already returns deep history through the plain
  `get_data_range`/`send_historical_data` loop NOOP already runs. If it does, the write path is belt-and-
  suspenders.
- **The decode of what comes back is the next step.** Once a tester confirms deep records start arriving,
  we map the type-`0x2F` layout (documented as HR @ byte 14, accel x/y/z float32 @ 37/41/45) and feed the
  motion into NOOP's existing v25-style sleep stager.

## Why SpO₂ (and the raw respiration track) aren't available on 5.0

This is the single most common "is it broken?" report (e.g. [#623](https://github.com/ryanbr/noop/issues/623)),
so the reasoning in one place:

**It is not an encryption problem.** NOOP decodes the entire 5.0 (v18) record in plaintext — HR, R-R,
sleep, and the whole optical tail. Nothing on the wire is hidden behind a cipher NOOP would need a key
for. The barrier is that a **shippable, calibrated SpO₂ %** is not yet confirmed in the stream NOOP
reads — not that the bytes are locked.

### What the strap actually does (protocol facts)

Independent firmware / app reverse-engineering (community + official-app behaviour) agrees on several
facts that shape how NOOP treats SpO₂ on 5.0 / MG:

| Fact | Implication for NOOP |
|------|----------------------|
| There is **no** BLE command such as `GET_SPO2` / `GET_BLOOD_OXYGEN` | Live SpO₂ is not a pollable gauge; looking for a missing opcode is a dead end |
| SpO₂ is computed **on-device during sleep** (optical / signal-processing path, sleep-gated) | Expect values only in overnight / sleep windows — not continuous 24/7 samples |
| Integer on-device samples are later **aggregated** for the official app / CSV export (`blood_oxygen_pct`, often one value per recovery cycle) | Nightly export % can differ slightly from a simple mean of raw wire candidates (rounding, quality gates, incomplete nights) |
| Incomplete nights / naps often have **no** SpO₂ in the export | Missing SpO₂ is a real data gap, not a NOOP bug |

So the research target is: **find the banked on-device sample (or its aggregate) in historical
type-47 records**, not invent a red/IR ratio or reverse a proprietary calibration curve.

### Wire status today

- **No *confirmed* SpO₂ field on the 5.0 wire for shipped metrics — but there is a candidate.** The
  raw optical tail (`@106` baseline, `@108/@109` amplitude pair, `@113` float) was checked against
  WHOOP-app SpO₂ across 18,602 real records — it does not match; those channels track HR/motion, and
  there is no identifiable red/IR pair. Pulse oximetry fundamentally needs two wavelengths; the
  5.0's decodable stream doesn't expose them for a third-party ratio (the v26 PPG waveform is
  single-channel, HR only). However, a decompile-sourced decode
  ([#103](https://github.com/ryanbr/noop/issues/103)) reads v18 byte `@82` as a **strap-computed SpO₂ %
  scalar** (tri-mode: 70–100 = real %, bit-7 = saturation sentinel, other sub-70 = diagnostic code;
  populated when the band sleep flag is asleep). The evidence is currently **split**: an 8-night
  independent validation with real spread (corr +0.99, ~0.4 %/night) clears the cross-night bar, but
  the two nights checked on the original #103 capture device moved *opposite* to the app value —
  unresolved device/firmware variance or an extraction error on one side. NOOP therefore decodes
  `@82` as `spo2_candidate_82` (deep-timeline instrumentation only, in-band values only) so more
  devices can correlate it against the app's nightly SpO₂; it does **not** populate `spo2Pct` or any
  card/score until the contradiction is resolved.
- **A calibrated % needs WHOOP's proprietary curve if only raw optical were available.** Even where
  raw optical exists, turning a red/IR ratio into a real SpO₂ % requires a device-specific
  calibration NOOP does not have — and NOOP will not fabricate one from unvalidated optical (the
  withdrawn #194 PPG→HR estimate is the cautionary precedent). Prefer a **strap-computed scalar**
  already on the wire over inventing a curve. `spo2Pct` is therefore nulled for *every* WHOOP live
  path; only an import (or a future multi-device-validated `@82` promote) writes it.

**WHOOP 4.0 differs.** The 4.0 **v24** historical layout *does* bank raw SpO₂ channels (`spo2_red@68` /
`spo2_ir@70`), so NOOP decodes the raw red/IR there (still not a calibrated %). The 5.0's v18 layout
dropped those dual-wavelength channels — SpO₂ moved to an **on-device computed product** (and the
official app's cycle export) rather than banking red/IR for the third-party client. NOOP
reverse-engineers what the strap actually sends; if a decodable SpO₂ isn't confirmed, there is
nothing safe to ship as a percentage.

**Respiration is a partial exception.** The 5.0 sends no raw respiration ADC stream either (also
4.0-v24-only), so the deep-timeline *track* is empty — but respiration is still estimated on-device from
the R-R interval stream (RSA) and shown on the Health screen when enough overnight R-R is captured.

**To see SpO₂ in NOOP on a 5.0 today:** import it. A WHOOP data export carries `blood_oxygen_pct`, and
Health Connect import works too — both populate the Blood Oxygen card with WHOOP's own computed
values.

### `@82` validation checklist (what would promote the candidate)

Only research — never a silent UI flip. A promote of `spo2_candidate_82` → `spo2Pct` needs:

1. **Multiple devices / firmwares** (not one lucky strap): nightly mean of in-band (70–100) `@82`
   samples during `sleep_state = asleep` tracks the official app or CSV `blood_oxygen_pct` with
   real night-to-night spread (not a flat 98 %).
2. **Offset specificity:** nearby bytes (e.g. 74–92 scan) must *not* track better than `@82`.
3. **Incomplete nights:** when the export omits SpO₂, the wire candidate should be empty or
   out-of-band — not invent a number.
4. **Resolution of the #103 contradiction** on the original capture device (or a documented
   extraction error).
5. **No recovery/illness gating** on the candidate until (1)–(4) clear — same rule as other
   derived biosignals.

Until that bar is met, SpO₂ stays **import-only** on the 5.0, with `@82` available as
instrumentation for owners who opt into deep-timeline / experimental logging.

Related capability / UX roadmap: [#761](https://github.com/ryanbr/noop/issues/761) (honest labels
when SpO₂ / skin temp / stages are unavailable vs experimental).

## Mapping the layout — ground-truth correlation

An HCI capture on its own is a pile of un-labelled bytes. The fast way to label them is *known
plaintext*: a tester's own **WHOOP data export** (app.whoop.com → Data Export) lists the official
per-night values — HRV, resting HR, skin temperature, SpO₂, respiratory rate — for exactly the nights
in the capture. Searching each record type for the byte offset + encoding that reproduces those known
values across every night pins the field without guesswork.

Two stdlib tools in [`Tools/linux-capture/`](../Tools/linux-capture/) do this:

- **`hci_extract.py`** converts a phone HCI log (iOS `.pklg` / Android `btsnoop_hci.log`) of the
  official app into the project's `capture.json` frame format — so an official-app full-sync capture
  feeds the same decoder as a Linux capture. It keeps only CRC-valid WHOOP frames.
- **`correlate_ground_truth.py`** cross-references those frames against the CSV export and reports
  candidate `(record type, offset, encoding, scale)` tuples, requiring both breadth and a
  distribution match so constants and coincidences don't score.

Crucially this is **privacy-preserving**: both tools run locally and the correlation output is only
offsets/encodings, never health values — so a 5/MG owner can contribute a confirmed field mapping to
[#103](https://github.com/ryanbr/noop/issues/103) without posting their capture or their data export.
A mapped offset still follows the project rule — *real captures, never invented offsets* — before it
lands in `parseFrameWhoop5` / `whoop_protocol.json`.

## How to help (5.0 / MG owners)

1. Update to the latest NOOP, **Settings → Experimental → "Unlock WHOOP 5/MG deep data (R22)"**.
2. With the strap **on and bonded**, tap **Send enable sequence to strap**.
3. Keep wearing it, let it sync, then **share your strap log** on [#103](https://github.com/ryanbr/noop/issues/103) — we're looking for new deep
   records (type `0x2F`) to start arriving.
4. Even better: a Bluetooth HCI capture of the **official app syncing a full night's history** shows the deep
   packets actually flowing and their layout. Method: iOS **PacketLogger** (Bluetooth diagnostic profile → `.pklg`)
   or Android **Developer Options → Bluetooth HCI snoop log** → `btsnoop_hci.log`, opened in Wireshark — the
   same iOS-HCI approach the [judes.club write-up](https://judes.club/writing/cracking-the-whoop-5-bluetooth-protocol/)
   used. Filter to just the WHOOP peripheral and attach it to [#103](https://github.com/ryanbr/noop/issues/103).
5. **SpO₂ / skin-temp field mapping without sharing health data:** run
   `Tools/linux-capture/correlate_ground_truth.py` locally against your own export + capture, then post
   only the winning `(offset, encoding, scale, corr)` lines on [#103](https://github.com/ryanbr/noop/issues/103).
   Especially useful: nightly mean of in-band `spo2_candidate_82` vs CSV `blood_oxygen_pct` across
   several nights (see the checklist above).

### Band sleep flag vs hypnogram (quick reference)

The v18 byte `@81` high nibble is the strap's **coarse sleep flag** (wake / still / asleep / up). It
is useful for sleep *detection* and for gating sleep-only products (including SpO₂ candidates). It is
**not** Light / SWS / REM. Full field table: [`PROTOCOL.md` §8.1](PROTOCOL.md#81-whoop-50--mg-type-47-v18-field-map-summary).

Credit to **judes.club**, **Asherlc/dofek**, and **b-nnett/goose** for the public protocol work this
builds on.
