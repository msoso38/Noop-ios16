# Stress factors and literature

**CONTINUED 2026-07-12 WHOOP Stress Monitor match pass (8.6.34-fable).** Code-truth map of what feeds NOOP Stress, how WHOOP’s UI behaves from export screenshots, band streams used, and remaining irreducible gaps.

Exports: `Downloads/Noop stresd-20260712T213610Z-2-001/Noop stresd/` (JPG only).

### WHOOP Stress Monitor (from SS — reverse-engineered behavior)

| Observation | Inference |
|-------------|-----------|
| Tip **0.5 LOW** at night/early morning (Fri 2:10 AM, Sat 6:20 AM) | Continuous monitor; **sleep floor near 0–0.5**, not mid-scale |
| Daytime tip **0.9 LOW** (Sun 5:34 PM) | Calm desk afternoon stays LOW |
| Daytime spikes to **~2.0–2.8** with activity glyphs | Motion/effort raises stress; peaks are sparse |
| High-zone minutes (e.g. 2h 8m / 3h 2m) | High zone ≈ level ≥ ~2.0 |
| “Wear 2 more nights” of **4** | Personalization/calibration separate from live tip |
| Chart includes **full sleep window** shaded | Night is on the series at near-zero — not omitted |

NOOP export-era (pre calm-anchor): logistic raw=0 → **1.5** MEDIUM + activity mean HR → hero **1.9** / avg **2.3** / **0h calm · 9h high**.

### Per-hour / tip match table (SS + unit expectation)

| Anchor (WHOOP SS) | WHOOP tip / shape | NOOP export SS (pre) | Post 8.6.34 (algo / unit) | Tag |
|-------------------|-------------------|----------------------|---------------------------|-----|
| Fri Jul 10 · 2:10 AM | **0.5 LOW**; sleep band after midnight | (not tip-compared) | Night bias → ~0.5 | `STANDARD` (unit) |
| Sat Jul 11 · 6:20 AM | **0.5 LOW**; sleep ~0.2–0.7 | — | Night bias → ~0.5 | `STANDARD` (unit) |
| Sun · 5:34 PM tip | **0.9 LOW** | Hero **1.9** / avg **2.3** | Flat 62–66 bpm dayMean **&lt;1.2**, tip **&lt;1.5** | `STANDARD` (unit) |
| Fri high-zone | **2h 8m** | Timeline **9h high** (rounded hours) | Exact bucket×15 caption + compact labels; Fold δ TBD | `IMPROVED_STILL_LACKING` → Fold `NEEDS_FABLE_AUDIT` |
| Sat high-zone | **3h 2m** | — | Same | `IMPROVED_STILL_LACKING` → Fold `NEEDS_FABLE_AUDIT` |
| Sub-hour jagged curve | Continuous PPG | 1h stairs | **15-min** buckets | `IMPROVED_STILL_LACKING` vs continuous |
| Activity peaks | Glyphs @ ~2.8 | Mean-HR inflated | Step walk/run + gravity L2 damp | `STANDARD` (wired) |
| Desk calm | Tip ~0.5–0.9 | False HIGH | Sedentary + still class −0.55 raw; SD floor 3 bpm | `STANDARD` (wired) |

Fold live tip-at-clock dump after install remains **`NEEDS_FABLE_AUDIT`** (no CSV export — JPG tips only).

### Match pass (this ship)

| Change | Effect |
|--------|--------|
| `calmAnchorOffset = 1.55` | raw=0 → ~**0.52 LOW** |
| Quiet HR **p10** (dense) / p25 | Still quieter than mean under spikes |
| Gravity **L2** + step **walk/run** | Stiller BPM half when busy |
| Sedentary bout / still class | −0.55 raw calm bias |
| **15-min buckets** | Closer tip/peak shape vs 1h |
| **SD floor** HR≥3 / RMSSD≥5 | Flat calm days no longer explode z |
| Night hours scored −0.85 | Sleep band near floor |
| Sustained-high waking-only | Breathe nudge not from sleep |
| High-zone minutes caption | WHOOP-style summary under totals |
| Methodology copy | Calm floor ~0.5 (not 1.5 baseline) |

### Match pass (8.6.42)

| Change | Effect |
|--------|--------|
| Sleep-state gate (#22) | Band asleep ≥3 samples/bucket → night bias; skip sustained |
| Skin / resp soft bumps (#19/#21) | Day vitals elevate waking raw when flagged |
| Calibration footnote (#50) | “Wear N more nights of 4” on Stress timeline |
| Persist daytime tip (#42) | `metricSeries.daytime_stress` when scored |
| Widget Now tip (#43) | Glance footer `Stress X.X` from tip tenths |
| Auto-workout damp (#63) | Detected peaks join logged workout windows |

### Remaining gaps (evidence)

| Gap | Why irreducible / next |
|-----|------------------------|
| Continuous WHOOP curve vs 15-min | Band banks discrete samples; no secret continuous PPG stream |
| Exact WHOOP proprietary HRV model | Not published |
| Fold tip@clock vs WHOOP tip | `NEEDS_FABLE_AUDIT` after install ≥ 8.6.36 |
| High-zone minutes δ vs 2h8m / 3h2m | Computation honest (bucket×15, no hour round-up) in **8.6.37**; Fold δ still `NEEDS_FABLE_AUDIT` |
| Multi-day personalization (“2 of 4 nights”) | **STANDARD** in 8.6.42 — `priorCalmDayCount` + Stress “Wear N more nights” footnote (#50) |

### High-zone minutes (8.6.37)

| Piece | Behavior |
|-------|----------|
| Formula | `scored.count(level ≥ 2.0) × 15 min` — same as WHOOP “You spent X hr Y min…” wording |
| Time-in-band labels | Compact `2h 8m` / `45m` (no `coerceAtLeast(1h)` inflation) |
| Sparse coverage note | If scored &lt; ~8 h, caption appends window count |
| Pre-fix inflation | Rounded “9h high” came from hour labels, not continuous PPG |
| Remaining δ | Fold live day vs WHOOP SS 2h8m / 3h2m — fill tip@clock + high-zone rows on return |

**Expected residual vs WHOOP:** continuous PPG can still accumulate more (or less) high minutes than 15-min banked buckets; within ~30% when coverage is dense is the Fold pass heuristic.

---

## Pipeline overview (three lanes)

| Lane | Feeds UI score? | Source files |
|------|-----------------|--------------|
| **A. Daily load (0–3)** | Secondary when daytime exists | `StressModel.build` |
| **B. Daytime / Now (0–3)** | Hero “Now”; timeline | `DaytimeStress.analyze(hr, rr, tz, motion, steps, sedentary)` |
| **C. Advanced HRV** | Display only | `StressIndex`, `HrvFreqDomain` |

```
HR + R-R + gravity→ActivityPoint + steps@63 + sedentary bouts
  ──► DaytimeStress (15-min) ──► 0–3 (calm≈0.5)
daily RHR + avgHRV ──► StressModel ──► daily 0–3 (secondary)
```

---

## Factor → effect table

| Input | Where | How much | Gate | Notes |
|-------|-------|----------|------|-------|
| Quiet HR (p10/p25) | `DaytimeStress` | +1 z vs waking calm | ≥75 samples / 15-min | Motion-busy → lower half BPMs |
| Bucket RMSSD | same | +1 z when low | clean R-R | Optional enrich |
| Calm anchor offset | `squash` | calm→~0.5 | const 1.55 | WHOOP floor |
| Night bias | −0.85 raw | sleep hours | hour ∉ 06–22 | WHOOP sleep band |
| Sedentary / still | −0.55 raw | bout or still&gt;walk | gravity + @63 | Desk calm |
| Gravity L2 / walk-run | busy gate | damp via quiet pool | mean ≥0.035 or walk≥still | Ambulation |
| SD floor | z denom | min 3 bpm / 5 ms | always | Anti-jitter explode |
| Daily RHR/HRV | `StressModel` | daily load | 30d baseline | Secondary to Now |
| Baevsky / LF/HF | Advanced | **0 weight** | — | Honesty only |

---

## Code fix checklist

- [x] Quiet HR p25/p10
- [x] Calm anchor + motion + night score
- [x] Step activityClass + sedentary gates
- [x] 15-min buckets + SD floor
- [x] Hero Now + high-zone caption + methodology
- [x] Unit tests calm day / step / sedentary / 15-min
- [x] High-zone minutes = bucket×15 + compact labels (8.6.37); unit tests
- [ ] Fold tip@clock δ table (`NEEDS_FABLE_AUDIT`) — prep below
- [ ] High-zone minutes vs WHOOP 2h8m / 3h2m on Fold (`NEEDS_FABLE_AUDIT` — computation shipped)

### Fold return — tip@clock δ audit (`NEEDS_FABLE_AUDIT`)

**Goal:** same wall-clock tip on Fold Stress hero vs WHOOP Stress Monitor SS (JPG anchors above).

**Prep (do on Fold after AI Store / adb install ≥ 8.6.36):**

1. Wear MG; open NOOP Stress with live link; note **Now** tip + local clock (screenshot).
2. Open WHOOP Stress Monitor at the **same minute**; screenshot tip + clock.
3. Fill one row per check (aim ≥4 across day: morning calm, desk afternoon, post-walk spike, late evening):

| Local clock | WHOOP tip / band | NOOP Now / band | |δ| | Tag |
|-------------|------------------|-----------------|-----|-----|
| _e.g. 17:34_ | 0.9 LOW | _fill_ | _|_ | `NEEDS_FABLE_AUDIT` → `STANDARD` if \|δ\| ≤ 0.3 in LOW |

4. Also note **High zone** caption minutes vs WHOOP day summary (2h8m / 3h2m anchors).
5. Paste the filled table back into this section; retag rows.

**Pass heuristic:** calm desk LOW within ~0.3 of WHOOP; spikes same direction; high-zone within ~30% of WHOOP minutes when coverage is dense.

---

## Update — 2026-07-13 “Updated noop state” export (JPG-only)

Same folder as Sleep update: `Downloads/Updated noop state -20260713T014231Z-2-001/Updated noop state/` (21 JPGs, no CSV).

### Stress tip@clock (Sun 12 Jul · ~21:39)

| Anchor | WHOOP | NOOP | δ | Tag |
|--------|-------|------|---|-----|
| ~21:32–21:39 tip | **2.1 HIGH** | **2.4 HIGH** | +0.3 | `IMPROVED_STILL_LACKING` → Fold re-check `NEEDS_FABLE_AUDIT` |
| Today’s load / day mean | (not on SS) | load **1.9**; timeline avg **0.8 · 18h** | — | direction OK (day quieter than tip) |
| Peak | visible evening rise | peak **2.5 · 9 pm** | same direction | `STANDARD` shape |
| High-zone minutes (this day) | (not labeled on this SS; prior Fri/Sat anchors **2h8m / 3h2m**) | **1h 45m** high / **2h 30m** mod / **14h** calm | fill Fri/Sat on Fold | `NEEDS_FABLE_AUDIT` |
| Calibration copy | “Wear 2 nights to unlock” (WHOOP) | “Wear 2 more nights of 4” | parity OK | `STANDARD` |

### Band streams for Stress match

| Stream | Role |
|--------|------|
| Quiet HR (15-min buckets) | Tip / curve body |
| RMSSD | Quiet-HR companion |
| Step class still/walk/run + gravity L2 | Damp false highs |
| Sedentary / still | Calm pull |
| Sleep-state / night hours | Floor near ~0.5 |
| Skin / resp soft bumps | Day elevate only when flagged |

### Fix plan (precise, no micro-APK this tick)

1. On Fold with **8.6.49 / 318**: at same clock as WHOOP tip, dump NOOP tip + high-zone caption; fill table rows.  
2. If δ tip stays >0.3 in HIGH evening, tune calm floor / activity damp — document in this file before code.  
3. High-zone: compare Fri/Sat WHOOP **2h8m / 3h2m** against NOOP same wake-days (not Sun-only).

---

## Fold replay audit — 2026-07-12 (Fable 5, offline replay of real banked streams)

Method: `FoldStressReplay` JVM diagnostic — the REAL `DaytimeStress.analyze` pipeline (same aux inputs
as `loadDaytimeStressShared`: activity series, sedentary bouts, Baevsky SI, HF share, auto-workout damp
windows, band sleep state, prior-day calm) over the Fold's banked HR/R-R/gravity/step streams for
Fri 10 / Sat 11 / Sun 12 Jul. Sun tip additionally replayed with a hard 21:40 data cutoff so the calm
reference matches what the live app could have known at the WHOOP screenshot minute.

| Anchor | WHOOP | NOOP replay (fixed pipeline) | δ | Tag |
|--------|-------|------------------------------|---|-----|
| Fri 10 high-zone | **2h 8m** | **1h 0m** (peak 2.81 @ 12h damped by auto-workout window — Fri was the strain-14.8 day) | −1h 8m | `IMPROVED_STILL_LACKING` — deliberate design divergence: NOOP damps workout windows (Fable #62) so Effort doesn't double as Stress; WHOOP counts exercise stress in its high zone. Decide product stance before tuning. |
| Sat 11 high-zone | **3h 2m** | **3h 15m** | +13m (~7%) | `STANDARD` (pass heuristic ≤30%) |
| Sun 12 tip @21:39 (live-cutoff) | **2.1 HIGH** (calibrating: "2 nights to unlock") | **~1.3–1.7** falling edge (21:00 bucket 2.41 → 21:15 1.73 → 21:30 1.25 partial); evening peak 2.6 @ 20h | −0.4..−0.8 | `NEEDS_FABLE_AUDIT` — the UI's 2.4 that evening was fed by the #316 poisoned daily rows (fixed in 8.6.50); vs a still-calibrating WHOOP one night is NOT tuning evidence. Re-check live at same clock on 8.6.50. |
| Sun 12 zone split (full day) | (1h45m high shown at 21:40 on 318) | high 2h30m / mod 4h45m / calm 15h45m | — | direction OK; day extends past the screenshot |

**Verdict:** no constant tuning this tick — Sat matches, Fri's gap is a documented design choice
(workout damp), and the Sun tip anomaly traces to the sleep-side data corruption fixed in 8.6.50.
Tune only if the 8.6.50 live tip@clock re-check still misses in a clean-DB week.

---

## Update — 2026-07-14 Noop mg export (docs-only pass, nothing coded)

Export: `Downloads/Noop mg-20260714T022949Z-1-001/Noop mg/`, same night/window as the Sleep update above
(Mon 13 Jul, phone captured 22:14–22:27).

### Tip@clock, same window

| Anchor | WHOOP | NOOP | δ |
|---|---|---|---|
| ~10:14–10:15 PM tip | **1.4 MEDIUM** (both WHOOP screenshots, stable) | **1.8 / 3** (Today Health card, same minute) | +0.4, same MEDIUM band but NOOP reads meaningfully hotter |
| Today card stress row (separate capture) | — | **1.8/3** listed under Health alongside HR 85 bpm / HRV 50 / SpO2 98% | consistent within NOOP itself this time — good |
| WHOOP curve shape (24h) | clear night dip to ~0.3–0.5 between the two shaded night blocks, sharp rise after wake, plateau ~1.5–2.2 daytime, evening dip then climb to tip | not captured in this export (NOOP stress chart wasn't screenshotted) | can't compare shape this round — only the live tip |

### What's grounded vs what needs a live re-check

The +0.4 gap alone is not damning — WHOOP's own chart shows a MEDIUM band that spans roughly 1.0–2.0, so
1.4 vs 1.8 is a real but small miss, not a category error (both land MEDIUM). Per the existing Fold replay
audit above (2026-07-12), the previous large stress anomaly traced back to a SLEEP-side data-corruption bug
(#316, fixed 8.6.50), not the stress pipeline itself — the same caution applies here: don't retune
`calmAnchorOffset` from one clock-matched sample. Do a live tip-at-clock dump against a few more matched
WHOOP timestamps before touching constants.

### What IS worth investigating regardless of the tip gap

Since the SAME night's sleep scored 2% deep / 0% REM (see SLEEP_FACTORS update above) and this stress
reading was captured at 10:14–10:15 PM — i.e. BEFORE that night's sleep even started — the two aren't
causally linked for this particular sample. But the broader stress-sleep coupling deserves scrutiny:
`nightCalmBias = 0.85` and `overnightAnchorSlackBpm = 5.0` in `DaytimeStress.kt` both assume a night with
a clean quiet-HR floor; a night that stages mostly "light"/"awake" instead of deep would produce a NOISIER
overnight quiet-HR pool feeding into `overnightQuiet` (used for the next day's `overnightCalmBias` pull),
which could inflate the FOLLOWING day's tips after a poorly-staged night — worth checking the day AFTER
this one for a stress readout that runs hot, tracing back to a contaminated overnight quiet reference.

---

## Stress-algorithm improvement backlog (grounded, non-padded)

**Calm reference / baseline (`DaytimeStress.kt`)**
1. `calmReference` uses a flat p25 (waking) / p75 (overnight) quantile regardless of sample count beyond
   the `size < 4` fallback — consider a wider quantile window (p10 dense / p25 sparse, mirroring
   `quietHourHr`'s own density-adaptive logic) so the calm reference doesn't get dragged by a single busy
   bucket on a low-sample day.
2. `priorCalmBlendMax = 14` blends up to 14 prior days flat — no recency weighting. An EWMA-style decay
   (mirroring `RecoveryScorer`'s baseline spread) would let the calm reference adapt faster after a genuine
   fitness/health change instead of being anchored to a 2-week-old baseline.
3. `overnightAnchorSlackBpm = 5.0` is a fixed absolute bpm slack regardless of the person's HR variability
   range — a low-HRV person's whole day might sit within 5 bpm of their overnight quiet HR by chance,
   permanently pulling them toward calm; scale the slack by the person's own HR spread (`sdHr`) instead of
   a flat constant.
4. No cross-day contamination guard: a poorly-staged sleep night (this export's 2%-deep night) could feed
   a noisy `overnightQuiet` into the FOLLOWING day's calm anchor — add a sanity gate that rejects an
   overnight quiet-HR estimate derived from a night with abnormally high `awake` fraction (>25%, per this
   export) rather than trusting every night's overnight pool equally.

**Motion / activity damping**
5. `motionBusyFloor = 0.035` and `stepBusy`/`stepStill` are binary gates — no partial credit for "somewhat
   busy" (e.g. slow walking vs running should damp differently, not both flip the same boolean).
6. `workoutOverlapBias = 0.40` is a flat damp for ANY logged workout regardless of intensity — a max-effort
   interval session and an easy recovery walk both get the same stress suppression during the window;
   scale the damp by the workout's own Effort/strain contribution instead of a constant.
7. Gravity L2 + step class are independent gates (`stepBusy`, `motionMean >= motionBusyFloor`) that OR
   together — consider requiring agreement between the two sensors before applying the busy classification,
   since a phone-in-pocket walk and a wrist-only gesture can disagree on which sensor sees "motion".

**Autonomic-signal fusion**
8. Baevsky SI (`daySi`) is a single day-level scalar folded in with fixed thresholds
   (`baevskyCalmSi = 80`, `baevskyHighSi = 200`) — SI is already implemented per-night in `StressIndex.kt`
   but not fed in per-bucket; a rolling SI over a shorter window (e.g. 2-hour) would let SI contribute to
   the SHAPE of the day's curve, not just a single soft bump/damp applied uniformly.
9. HF-vagal trust (`hfVagalTrusted`) is boolean — no magnitude scaling. A day with HF share at 41% (barely
   over the `hfCalmShare = 0.40` bar) gets the same calm bias as one at 70%; scale `hfCalmBias` by how far
   above the threshold the HF share actually sits.
10. Respiration-elevated bump (`respElevatedBpm = 18.0`) doesn't distinguish exercise-driven respiratory
    rate elevation (already damped by `workoutOverlapBias`) from anxiety/illness-driven elevation outside a
    workout window — the two should probably not share one flat `respElevatedBias`.
11. Skin-temperature bump (`skinElevatedAbsC = 0.5`) is symmetric-agnostic in Stress (only "elevated" is
    checked) while `RecoveryScorer`'s skin-temp term is symmetric (illness OR overreach both matter) —
    consider whether a below-baseline skin-temp deviation should also nudge Stress, for consistency with
    how Charge already treats temperature deviation.

**Calibration / cold-start honesty**
12. `calibrationNightsTarget = 4` gates personalization but the live tip still renders during calibration —
    confirm the UI clearly distinguishes "still calibrating, tip may be noisy" from a fully personalized
    tip, since this export's WHOOP screen explicitly showed "Wear WHOOP to sleep tonight to calibrate" while
    NOOP showed a bare tip number with no equivalent caveat visible in this capture.
13. `priorCalmDayCount` is tracked but only exposed via `calibrationNightsRemaining` — surface this
    countdown directly under the live tip (mirroring WHOOP's own "Wear 2 more nights" copy already matched
    per the 2026-07-12 pass) rather than only in the Stress Monitor detail screen.

**Chart / UI fidelity (ties to Fable list, not just math)**
14. WHOOP's chart is visually continuous (sub-minute PPG); NOOP's 15-min buckets (`bucketSeconds = 900`)
    are a deliberate compromise already documented above — consider whether a THINNER bucket (5-min) is now
    affordable given the #707 OOM fix pattern already applied to `SleepStagerV2` (prefix-sum / cache
    memoization) was specifically built to make more expensive per-epoch recipes affordable again.
15. Show the live tip alongside the SAME MEDIUM/HIGH/LOW color band language WHOOP uses at the exact
    number (WHOOP: "MEDIUM" label directly under the big digit) — confirm NOOP's Health-card stress row
    (this export's "1.8 / 3") carries an equivalent qualitative label, not just the raw number, since a bare
    fraction reads as more precise than the metric actually is.

---

## Related paths

- `android/.../DaytimeStress.kt`, `DaytimeStressLoader.kt`, `StressScreen.kt`, `TrendsScreen.kt`, `TodayScreen.kt`
- `android/.../StressIndex.kt` (Baevsky SI, feeds `daySi` above)
- `Packages/StrandAnalytics/.../DaytimeStress.swift`
- Fable Stress-70 in `docs/FABLE_200_UI_IMPROVEMENTS.md`
