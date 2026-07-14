# Sleep factors and literature

**CONTINUED 2026-07-12 sleep-export.** Code-truth map of what feeds NOOP Sleep (detection, staging, totals, UI), how each input moves the readout, peer-reviewed context, and honesty limits versus WHOOP’s proprietary Sleep Coach. Does **not** invent WHOOP stage math. MAIN builds still hide algo-vs-WHOOP compare.

Export compared: `Downloads/Noop sleep -20260712T215506Z-2-001/Noop sleep/` — **8 JPGs only** (7 WHOOP + 1 NOOP); **no CSV/JSON**.

---

## Export inventory

| File | Surface | Headline numbers |
|------|---------|------------------|
| `…175042_WHOOP.jpg` | Last night (Today) | Hours asleep **5:53**; window **6:21–12:57**; Duration (typical) **6:36**; Awake 0:22 / Light 2:23 / SWS 1:53 / REM 1:37; restorative **3:30** |
| `…175059_WHOOP.jpg` | Today cards | Hours vs needed **61%** (5:53 / 9:41); efficiency **89%**; awake **0:21**; 16 wake events |
| `…175104`–`175123` | Supporting WHOOP | Consistency / stress / need breakdown (same session family) |
| `…175137_WHOOP.jpg` | Sat Jul 11 | Hours asleep **6:08**; window **2:14–9:57**; Duration **7:43**; Awake 1:07 / Light 2:45 / SWS 2:29 / REM 0:54 |
| `…175149_NOOP.jpg` | Sleep tab | Compare NOOP **3h 44m** vs “WHOOP” **6h 36m**; bed **02:33–11:05**; **8h 32m in bed · 56%**; Hours of sleep **4h 45m**; restorative **0m**; Awake **3h 48m** / Light **4h 45m** / Deep **0** / REM **0** |

---

## Why NOOP looked so different (root causes)

| Observation | Cause in code | Fix (this continuation) |
|-------------|---------------|-------------------------|
| Compare **3h 44m** vs stage **4h 45m** | Strip used `days.last.totalSleepMin`; chart used session segments | Strip + hero use **navigated night** stage-sum asleep |
| “WHOOP app **6h 36m**” | HC fallback = **time in bed** when stages missing; labeled as WHOOP hours | Label **HC asleep** vs **HC in bed**; don’t call TIB “WHOOP hours” |
| Window **02:33–11:05** vs WHOOP Today **6:21–12:57** | Different primary nights / day attribution (WHOOP “Today” vs NOOP last overnight) | Honesty: compare only same wake-day; docs note mismatch |
| Sat Jul 11 WHOOP **6:08** with real SWS/REM vs NOOP **0 Deep/REM** | On-device V1 stager collapsed night to **wake + light** (EEG-free actigraphy/HR); V2 opt-in + **WHOOP5-only** family gate | **No invented Deep/REM**; wake/light-only banner under iPhone chart |
| Efficiency **56%** vs WHOOP **89%** | Efficiency = TST/TIB from on-device wake load; inflated WASO when stages are coarse | Keep formula; honesty copy on approx stages |
| Chart shape (HR line + empty Deep/REM rows) | iPhone 8.7 path correct; minutes could disagree with bars / DailyMetric | Chart minutes from **smoothed timed segments** |

Hard rule: **MAIN never shows algo-vs-WHOOP**. DEBUG may still surface compare tools elsewhere.

---

## Pipeline overview

```
Gravity + HR + R-R (+ optional resp / band sleep state)
        │
        ▼
SleepStager.detectSleep  ──► windows (onset/wake)
        │
        ├── V1 stageSession (default)  → {wake, light, deep, rem} segments
        └── V2 SleepStagerV2 (opt-in Puffin; WHOOP5/MG only via sleepStagerV2ForFamily)
        │
        ▼
stagesJSON + efficiency + DailyMetric (totalSleepMin, deep/rem/light, efficiency)
        │
        ▼
SleepStageTotals / analyzeDay  ──► Rest composite, debt, need tiles
        │
        ▼
SleepScreen: selectNight → heroDisplay → iPhone StageTimeline (#988 / 8.7) or legacy hypnogram
```

| Lane | Feeds UI? | Source |
|------|-----------|--------|
| **A. Detected sessions** | Bed/wake, hypnogram, asleep | `SleepStager` / `SleepStagerV2` |
| **B. DailyMetric rollup** | Trends, debt, Rest, compare (was buggy) | `AnalyticsEngine` + `SleepStageTotals` |
| **C. Health Connect / import** | Dual strip; optional blend `HcNoopAlign` | `health-connect` sessions + WHOOP export |
| **D. Display smoothing** | Bar coalescing only (≥90s) | `displaySmoothedSegments` — must not invent stages |

---

## Factor → effect table

| Input | Where | Effect on readout | Gate / notes | Literature / rationale | Export link |
|-------|-------|-------------------|--------------|------------------------|-------------|
| **Gravity / jerk** | V1 Cole–Kripke + V2 jerk wake gate | Wake vs sleep; sparse gravity → whole night **light** fallback | `gSeg.size < 2` → single light segment | Actigraphy wake detection (Cole–Kripke tradition) | Empty Deep/REM more likely with sparse motion |
| **HR / HR flatness** | V1 features; V2 11-min HR-flatness deep gate | Deep eligibility; high variability → rem/wake lean | V2 deep gate ≈ lowest 20% flatness | HR slowing / reduced variance in SWS (literature, not EEG) | WHOOP SWS present; NOOP 0 deep |
| **RMSSD / R-R** | Staging features + session avg HRV | Soft rem/light cues; Rest drivers | Clean R-R after ectopic filter | Task Force HRV; sleep staging from PPG is limited | — |
| **Resp / RSA regularity** | V2 | Regular → deep; irregular → rem | Optional stream | RSA / breathing regularity in deep sleep | V2 off by default |
| **Cycle prior** | V2 | Deep early; rem suppressed first ~12% | Soft prior, not hard wipe | Ultrashort sleep-cycle architecture (AASM framing) | — |
| **Family gate** | `sleepStagerV2ForFamily` | V2 **never** on WHOOP4 even if toggle on | `enabled && family != WHOOP4` | Hardware/signal differences | User may be 4.0 → stuck on V1 |
| **Experimental V2 toggle** | `PuffinExperiment.experimentalSleepV2` | Default **false** → V1 | Settings / Test Centre | Opt-in experiment | Export night likely V1 |
| **Efficiency** | `asleep / inBed` | % in subtitle; Rest; H9 badge | Session stages define wake | AASM SE = TST/TIB | 56% vs WHOOP 89% |
| **totalSleepMin** | DailyMetric | Trends / debt / (was) compare | Should match stage sum | TST | Stale 224 vs stage 285 |
| **HC stages JSON** | Compare + `HcNoopAlign` | Dual strip; optional blend ≥25 min gap | Prefer staged light+deep+rem | Phone/WHOOP app via HC ≠ open BLE | 6h36 TIB mislabeled |
| **Display 90s coalesce** | `displaySmoothedSegments` | Visual only | Absorbs brief blips | Presentation, not rescoring | Bars must drive minutes |
| **Main-night group** | `SleepStageTotals` / `selectNight` | Which window is “last night” | Habitual midsleep; skip pre-onset stubs | Shift-worker / biphasic nights | Window vs WHOOP Today |

### Dominance rules (code)

1. **Hero chart minutes:** smoothed timed segments → `stagesFromSegments` (post sleep-export).
2. **Hero when segments exist:** `heroDisplay` prefers night segment sums over DailyMetric-derived model stages.
3. **Tiles / debt / trends:** still `totalSleepMin` over full `days` (iOS parity) — not TIB.
4. **Never invent Deep/REM** when the stager emitted none.
5. **MAIN:** no algo-vs-WHOOP compare UI.

---

## Key papers & standards

1. **Iber C et al. / AASM** — *The AASM Manual for the Scoring of Sleep and Associated Events.* Gold-standard **EEG/EOG/EMG** staging; wearable proxies are not clinical PSG.
2. **Cole RJ, Kripke DF, et al.** (1992). *Automatic sleep/wake identification from wrist activity.* Sleep 15:461–469. **PMID: 1455130**. Actigraphy sleep/wake — ancestor of NOOP V1 motion path.
3. **Sadeh A** (2011). *The role and validity of actigraphy in sleep medicine.* Sleep Med Rev 15:259–267. **PMID: 21237680**. Actigraphy good for sleep/wake, weak for stage architecture.
4. **Fonseca P et al.** (2017). *Validation of photoplethysmography-based sleep staging.* Sleep / related PPG work — shows **HRV+motion** can approximate stages but with large deep/REM errors vs PSG.
5. **Beattie Z et al.** (2017). *Estimation of sleep stages using cardiac and movement data.* — consumer multi-sensor staging limits (Fitbit-era validation literature).
6. **Task Force ESC/NASPE** (1996). HRV standards — **PMID: 8598068** (RR features used in staging recipes).
7. **Billman GE** (2013). LF/HF caveats — **PMID: 23431279** (adjacent honesty; Stress doc).

Honesty: NOOP on-device stages are an **engineering hypnogram**, not PSG and **not** WHOOP’s Sleep Coach algorithm.

---

## Code fix checklist (this continuation)

- [x] Compare strip = navigated night asleep (not `days.last.totalSleepMin`)
- [x] HC TIB not labeled as WHOOP hours asleep
- [x] `buildSleepModel` / `heroDisplay` prefer session/segment stage sums for awake+asleep
- [x] iPhone timeline minutes from smoothed segments; wake/light-only honesty note
- [x] Hero asleep never falls back to time-in-bed
- [x] Unit test `SleepExportConsistencyTest`
- [x] Docs + Fable tags + ANY_MODEL_CONTINUE sleep marker
- [x] Fold honesty-strip audit via uiautomator (2026-07-12) — see Fold visual audit table
- [ ] Optional: user enable V2 on WHOOP 5/MG and re-stage (still approximate)
- [x] Re-verify Deep/REM banner mid-scroll without WHOOP stealing focus

---

## Fable audit — pass criteria vs still lacking

Use the export folder side-by-side with Sleep on Fold after install.

| Check | Pass (= STANDARD) | Still lacking (= IMPROVED_STILL_LACKING) |
|-------|-------------------|------------------------------------------|
| Compare NOOP figure | Equals stage **Hours of sleep** (±1 min) for the same navigated night | Still shows a different minute total than the chart |
| Right-hand phone figure | Labeled **HC asleep**, **HC in bed**, or **WHOOP sleep %** correctly | Still says “WHOOP app” for TIB/duration |
| Deep/REM zeros | Banner: wake/light only; no fake restorative bars | Silent 0% Deep/REM looking like a real drought |
| iPhone chart path | Four rows + sleeping HR when segments exist (8.7) | Falls back to legacy hypnogram unexpectedly |
| Window honesty | User can see NOOP bed/wake may ≠ WHOOP “Today” night | UI implies same night when windows differ by hours |
| Efficiency | Shown with “approx. stages (on-device)” when computed | Reads as WHOOP-grade SE |
| MAIN build | No algo-vs-WHOOP lab | Lab visible on store APK |
| Staging richness | (Aspirational) Deep/REM appear when signal supports V1/V2 | Wake/light-only persists — **algo limit**, not a UI bug |

### Update — 2026-07-12 Fold visual audit (8.6.30 → documented on 8.6.31)

**Method:** `uiautomator dump` on Fold cover while Sleep tab open (`com.noop.whoop`); WHOOP app was force-stopped so NOOP stayed foreground. Full stage-chart scroll interrupted by WHOOP reclaiming focus — top-of-Sleep honesty strip verified.

| Check | Fold observation | Tag |
|-------|------------------|-----|
| Compare NOOP = Hours of sleep | NOOP asleep **3h 44m**; efficiency 47% × 7h 58m TIB ≈ 3h45 — strip matches construct | `STANDARD` |
| HC captions | **HC in bed** · **6h 36m** + TIB not-comparable footnote | `STANDARD` |
| Wake-day + NOOP window | **Wake day · Sun 12 Jul · NOOP 04:56–12:54** | `STANDARD` |
| Efficiency honesty | **7h 58m in bed · 47% efficiency · on-device stages (approx.)** | `STANDARD` |
| Source badge | **NOOP** on hero | `STANDARD` |
| Window vs WHOOP export Today | NOOP **04:56–12:54** vs export WHOOP **6:21–12:57** — closer than old 02:33–11:05; onset still differs | `IMPROVED_STILL_LACKING` |
| Deep/REM zeros banner | Re-checked below (Jul 11) → `STANDARD` | see next table |
| Staging richness | Algo limit | `IMPROVED_STILL_LACKING` |

### Update — 2026-07-12 Fold Deep/REM banner re-check (8.6.31 → tagged on 8.6.32)

**Method:** force-stop `com.whoop.android`; Sleep → Previous night (Sat 11 Jul export night); scroll Stage breakdown.

| Check | Fold observation | Tag |
|-------|------------------|-----|
| Banner when Deep+REM = 0 | **On-device staging found no Deep or REM — wake/light only. Not WHOOP’s SWS/REM split…** visible under legend | `STANDARD` |
| Banner when Deep > 0 | Sun 12 Jul Deep **5m** / REM **0m** — banner correctly **absent** | `STANDARD` |
| No invented stages | Chart shows 0m Deep/REM rows; totals kept; no fake restorative fill | `STANDARD` |
| Staging richness vs WHOOP SWS/REM | Still wake/light collapse on this night — **algo**, not UI | `IMPROVED_STILL_LACKING` |

Code checklist: mark Fold re-verify done for honesty strip.

---

**Audit tags for Fable:** `STANDARD` · `IMPROVED_STILL_LACKING` · `NEEDS_FABLE_AUDIT`

### Update — 2026-07-12 Rest calibration + onset honesty

| Issue | Root cause | Fix / tag |
|-------|------------|-----------|
| Rest vessel “Calibrating / needs a tracked night” with Charge scored | **Not** the 4-night Charge gate — `RingNeedsTrackedNight` when `recovery != null` but `sleep_performance` null (#898) | Copy → **“No scored sleep / wear overnight”** `STANDARD` |
| Bare HC sleep (duration, no efficiency) → Rest null | `restFromDaily` required efficiency | Duration-only Rest via `HcNoopAlign.durationAsSleepPerf` — **no invented Deep/REM** `STANDARD` |
| Onset vs WHOOP Today | Different primary nights | Footnote on compare strip `STANDARD` |
| Staging richness | EEG-free actigraphy | `IMPROVED_STILL_LACKING` (algo) |

Sleep-70 ideas appended to `docs/FABLE_200_UI_IMPROVEMENTS.md`.

---

## Update — 2026-07-13 “Updated noop state” export (JPG-only)

**Folder:** `Downloads/Updated noop state -20260713T014231Z-2-001/Updated noop state/`  
**Contents:** **21 JPGs** (15 NOOP + 6 WHOOP). **No CSV/JSON/SQLite** — graph series below are read from screenshots only.

### Inventory (filenames → surface)

| File | App | Surface / headline |
|------|-----|-------------------|
| `…213136–213159_NOOP` | NOOP | Trends week / Charge+HRV / RHR+Effort / calendar empty Stress |
| `…213205–213220_NOOP` | NOOP | **Today** Sun 12 Jul: Charge **35**, Effort **No Data**, Rest **No scored / wear overnight**; Health HR **87**, HRV **53**, SpO₂ **97%**, Stress **2.4**; HR chart min/avg/max **53 / 68 / 91**; steps tile **615** |
| `…213230–213238_NOOP` | NOOP | **Stress** tip **2.4 HIGH**; load **1.9**; timeline avg **0.8 · 18h**; peak **2.5 · 9 pm**; time-in-band Calm **14h** / Mod **2h 30m** / High **1h 45m**; “Wear 2 more nights of 4” |
| `…213251–213301_NOOP` | NOOP | Resp **18.7** (9 readings); Steps history empty (“need 2 readings”); Skin temp latest **+1.2°F** |
| `…213309–213313_NOOP` | NOOP | **Sleep** wake day Sun 12 Jul: NOOP asleep **3h 44m**, window **04:56–12:54**; HC in bed **6h 36m**; in bed **7h 58m** · eff **47%**; Awake **4h 15m** / Light **3h 39m** / Deep **5m** / REM **0**; **“No movement detail for this night.”** |
| `…213922–213933_WHOOP` | WHOOP | Home: Sleep activity **5:53**, **6:21 AM–12:57 PM**; Stress Monitor tip **2.1 HIGH**; calib “2 nights to unlock” |
| `…213938–213950_WHOOP` | WHOOP | Dashboard HRV **40**, RHR **58**, steps **2,831**; Strain/Recovery week Fri **14.8** / Sat **9.7** / Sun **2.8**, Recovery Sat **32%** Sun **37%**; “Wear 2 nights to calibrate” |

### Why Today Rest says “no sleep / wear overnight” despite Sleep tab having a night

| Observation | Cause | Fix plan (do not invent Deep/REM) |
|-------------|-------|-----------------------------------|
| Sleep tab shows **04:56–12:54** with stages | On-device session **exists** for wake-day Sun 12 | Session path works |
| Today Rest vessel: **No scored sleep / wear overnight** | Rest ring uses **`sleep_performance`** (`RingNeedsTrackedNight` when Charge exists but Rest null) — not “any session rows” | Ensure `analyzeDay` writes `sleep_performance` for this wake-day from stage-sum / duration; if gate requires “overnight” clock hours, widen to **longest sleep block ending on selected wake-day** (shift-work / late wake) |
| Copy says “wear overnight” | Misleading for daytime/late-morning sleep (WHOOP also **6:21–12:57**) | Copy → **“No Rest score yet / open Sleep”** when a session exists but score null; keep “wear overnight” only when **zero** sessions |
| NOOP **3h 44m** vs WHOOP **5h 53m** | V1 wake-heavy staging + **“No movement detail”** → inflated Awake; onset **04:56** vs WHOOP **6:21** | Prefer denser gravity/accel when present; don’t treat HC **6h 36m TIB** as asleep; time-align by **wake-day + overlapping window**, not wall-clock onset alone |
| Deep **5m** / REM **0** vs WHOOP SWS/REM | EEG-free actigraphy | Honesty only — `IMPROVED_STILL_LACKING` algo |
| HC strip **6h 36m** | Phone TIB without stages | Already labeled HC in bed — keep; never fill Rest from TIB alone without durationAsSleepPerf honesty |

### Band streams to use for sleep match (priority)

1. **Gravity / motion** (jerk) — wake vs sleep; this export night lacked movement detail → wake inflate  
2. **HR / quiet HR** — onset/wake + deep flatness (V2)  
3. **R-R / RMSSD** — soft rem/light; Rest drivers  
4. **Optional:** sleep-state samples, skin temp, resp (soft priors only)  
5. **HC / WHOOP-app import** — dual strip + Rest duration fallback; **never** invent Deep/REM %

### Time-align checklist (same night)

1. Pick **wake calendar day** (Sun 12 Jul) shared by both apps.  
2. Align windows: WHOOP **06:21–12:57** vs NOOP **04:56–12:54** (overlap midday; NOOP earlier onset).  
3. Compare **asleep minutes** (stage-sum), not TIB / HC in-bed.  
4. MAIN: **no algo-vs-WHOOP UI**; DEBUG/docs only for δ tables.

**Audit tags:** Rest gate copy/score wiring → `NEEDS_FABLE_AUDIT` on Fold after fix; staging richness → `IMPROVED_STILL_LACKING`.

---

## RESOLVED — 2026-07-12 (Fable 5, shipped in 8.6.50-fable / 319)

Root causes found by pulling the Fold debug DB + replaying the real streams through the real code
(`FoldSun12NightReplay` diagnostic). THREE distinct defects stacked into "Sleep tab has a night, Today
Rest says no scored sleep":

1. **Cross-day edited-block fold ("Rest repeats across days", the community 721-min report).**
   `IntelligenceEngine.sleepEditedDaily` passed the WHOLE recompute window's `editsByStart` and folded
   every twinless edited block into EVERY scored day via the `manual` union channel. The Fold DB carried
   `totalSleepMin=391.5` / `sleep_performance=82.33` byte-identical on 07-10/07-11/07-12; 07-11/07-12
   genuinely had no scored night. **Fix:** a twinless block folds ONLY into the local day its effective
   END falls on (same attribution as `analyzeDay`). Regression test `SleepEditWakeDayScopeTest`.
   **iOS has the same bug** (Strand IntelligenceEngine.swift:1462 `manualTuples`) — parity fix pending. `NEEDS_FABLE_AUDIT` (iOS)
2. **Upsert-only `sleep_performance`.** The engine deleted+rebuilt computed dailies per pass but only
   UPSERTED the Rest series, so poisoned points pinned forever. **Fix:** `deleteComputedSeriesInRange`
   (computed source, key-scoped) before re-upsert — the recompute now fully owns its window. `STANDARD`
3. **Zero detection on gravity-dead nights.** The 04:56–12:54 night had dense 1 Hz HR + R-R and ZERO
   gravity records 04:46→15:00 — `buildRuns` (gravity spine) seeded nothing; the #308 sparse branches
   can only bridge existing runs. **Fix:** HR-only spine (`hrOnlySleepRuns`) inside gravity-dead holes
   (≥120 min, HR-dense end-to-end so off-wrist can't slip in): 5-min sleep-band buckets, dip-entry
   hysteresis (open ≤0.95×baseline sustained 15 min; continue ≤1.05×), then the EXISTING full gate
   ladder. Sparse-gated; dense-gravity nights byte-identical. On-device result: Sat 02:37→10:12 (WHOOP
   02:14–09:57), Sun 04:29→13:19 (WHOOP 06:21–12:57), Rest 92.39/95.0 per-day. `STANDARD` (detection)
   — staging on such nights is cardiac-only (eff reads 1.0, no wake detail): `IMPROVED_STILL_LACKING`.
   iOS parity for the HR-only spine: pending. `NEEDS_FABLE_AUDIT` (iOS)

Copy fix (#202/#317): Rest vessel with a session but no score now reads **"No Rest score yet · open
Sleep"**; "wear overnight" only when zero sessions exist. `STANDARD`

Pre-existing test failures on this tree (NOT from these changes; verified by disabling them):
`StepsAnalyticsTest` ×3, `LocalDayBucketingTest`, `GermanLocalizationTest` (missing
whoop_app_a11y_description / nav_whoop_algo_compare), `SleepImportedFiguresTest.uncoveredDaysFallBackToApproximation`.

---

## Update — 2026-07-14 Noop mg export (docs-only pass, nothing coded)

Export: `Downloads/Noop mg-20260714T022949Z-1-001/Noop mg/` — 16 JPGs (6 WHOOP + 10 NOOP), same night
Mon 13 Jul, phone captured 22:14–22:27.

### Point-by-point decode, same night

| Field | WHOOP | NOOP (hero card) | NOOP (on-device stage breakdown) | Note |
|---|---|---|---|---|
| Asleep / woke | 3:48 AM → 1:08 PM | 02:31 → 13:11 | 02:31 → 13:11 (same span, different totals below) | onset ~77 min apart |
| Hours of sleep | 7:06 (duration card) | 7:06 | **7h 19m** | NOOP's own two screens disagree by 13 min on the SAME night |
| In bed / TIB | — | 9:19 (duration row) | 10h 39m | also disagree |
| Efficiency | 89% (Today card, different night) | — | 69% | not directly comparable (different nights on WHOOP side) |
| Awake | 2:13 (23%) | 2:13 (23%) | 3h 20m (31%) | hero matches WHOOP shape; stage breakdown does NOT match the hero |
| Light | 2:41 (28%) | 2:41 (28%) | 7h 9m (67%) | stage breakdown massively over-assigns light |
| Deep (SWS) | 2:53 (33%) | 2:53 (33%) shown in "Stages vs typical" as target line | **10m (2%)** | this is the number that actually lands in Rest |
| REM | 1:32 (16%) | shown as −13m vs typical | **0m (0%)** | REM never fires |
| Restorative sleep | — | 4:25 | 10m | 26× apart between NOOP's own two cards |

The **hero card and the on-device stage breakdown are computing sleep for the same [start,end] window and
landing on materially different totals** (7:06 vs 7h19m hours, 9:19 vs 10h39m TIB, 23%/28%/33%/16% split vs
31%/67%/2%/0%). One of these is reading `DailyMetric.totalSleepMin` / stage minutes directly; the other
looks like it's re-deriving from `SleepStageTotals` or a smoothed timeline over the same segments. They
should be the SAME computation surfaced twice — find the two call sites and make them share one source of
truth before touching staging accuracy itself, otherwise any staging fix will only fix one of the two
screens.

### Deep/REM gap — likely mechanism (V1 vs V2)

The 2%/0% split (vs WHOOP 33%/16%) is consistent with **V1 `SleepStager`** actigraphy-only percentile bands
collapsing a quiet, low-movement night to mostly "light" + some "awake", exactly the documented ceiling
in this file's own header ("EEG-free 4-class ceiling ~65–73% epoch agreement", "light/deep separation is
the weakest link"). `SleepStagerV2` (opt-in, WHOOP5/MG-only gate via `sleepStagerV2ForFamily`) uses the
11-min HR-flatness percentile deep gate + RSA respiration regularity + Viterbi smoothing specifically
built to recover deep/REM better — worth confirming this MG session actually routed through V2 rather than
V1, since a 2%/0% split reads exactly like the V1 failure mode V2 was built to fix.

### Onset gap (~77 min)

WHOOP anchors onset later (3:48 AM) than NOOP (02:31). Per the 2026-07-12 RESOLVED section above, NOOP has
an `hrOnlySleepRuns` HR-only spine that opens on a sustained HR dip (≤0.95× baseline, 15 min sustained) when
gravity is dead — that spine is tuned to be more sensitive than a motion-based onset and will legitimately
fire earlier than a device that waits for stillness. If gravity data existed for this session (unclear from
JPGs alone), check whether the HR-only spine fired even with motion data present — it should only activate
in gravity-dead holes, not always-on.

---

## Sleep-staging improvement backlog (grounded, non-padded — pick items, don't just count them)

Organized by pipeline stage. Each references an actual file/constant so a future pass can act directly.

**Onset/wake detection (`SleepStager.kt` Stage 0)**
1. Log which path (gravity spine vs `hrOnlySleepRuns`) produced each session's onset, surfaced in a debug
   caption — right now there's no way to tell which fired without reading the DB.
2. Add a per-night onset-confidence flag (gravity-confirmed vs HR-only) and show it quietly in the Sleep
   hero so a HR-only-derived night doesn't read as equally precise as a motion-confirmed one.
3. Cross-validate `hrOnlySleepRuns`'s dip-entry hysteresis (0.95×/1.05× baseline) against this export's
   ~77 min gap — if HR-only spines consistently open early, the entry threshold may need tightening
   (e.g. 0.92× or requiring a longer sustained window than 15 min).
4. Te Lindert/Cole-Kripke cross-check is computed but described as "citable" only — actually surface a
   disagreement between the two as a confidence signal instead of silently keeping just one.
5. Respiration-rate-based onset confirmation (resp channel already ingested for V2 respReg) as a THIRD
   independent onset vote, not just cardiorespiratory + gravity.

**Deep/light separation (weakest link per file header)**
6. Verify `sleepStagerV2ForFamily` gate is actually selecting V2 for WHOOP MG sessions — if this export
   routed through V1, that alone explains most of the deep/REM gap; confirm before tuning anything.
7. V2's `deepGateThresh = 0.20` (deep eligible only in the bottom 20% HR-flatness percentile) may be too
   strict for a night with genuinely poor overall HR stability — consider a per-night adaptive threshold
   instead of a fixed population percentile.
8. Add an SpO2-desaturation-dip cross-check where available (deep sleep correlates with the most stable
   SpO2 stretches) — MG hardware exposes SpO2; not currently fed into either stager.
9. Actigraphy literature (Cole-Kripke 1992, te Lindert 2013, Walch 2019) caps EEG-free 4-class agreement
   at ~65–73%; consider whether NOOP should report a **3-class** (wake/light/deep+REM combined) fallback
   on nights where the deep-gate percentile spread is degenerate, rather than forcing a 4-way split that's
   likely to read as 0% on one class.
10. RR-RSA respiratory regularity (V2's `respRegularity`) is a genuine deep/REM discriminator in the
    literature (Sleep Medicine Reviews, cardiorespiratory coupling studies) — confirm `resp.size >= 12`
    gate isn't silently dropping the term on sparse-resp nights, since a dropped RSA term pushes weight
    back onto HRV/motion alone, i.e. back toward the V1 failure mode.

**REM detection (0% this night — total miss)**
11. V2's `cyclePrior` suppresses REM entirely in the first 12% of the night (REM latency) — confirm this
    night's REM-eligible window (after ~12% clock fraction) actually had any epochs; if the whole night
    fell in the suppressed window (very short/fragmented sleep), REM=0% may be a real consequence of a
    short night rather than an algorithm miss — worth distinguishing in the UI copy ("too short to reach
    REM" vs "REM undetected").
12. Malik-ectopic-cleaned RR gaps: if `HrvAnalyzer.cleanRR` rejected too many beats this night (motion
    artifact, loose strap), REM's RSA signal would be starved — surface clean-beat yield per night as a
    staging-confidence input, not just an HRV-analysis internal.
13. Cross-check REM against the R-R "irregular breathing → REM" prior with the deep-gate's stability
    percentile — a night where HR-flatness never drops into a low-enough regime for deep ALSO likely never
    produces the irregular-breathing signature V2 wants for REM; these two misses may share one root cause
    (a globally elevated, non-differentiated HR profile this specific night) rather than being independent.

**Awake overcounting (31% vs WHOOP's 23%)**
14. V2's jerk-based wake gate (`jerkFloorGateMult = 55`) self-calibrates to the night's own quiescent-jerk
    median — on a night with an unusually still baseline (low median jerk), the multiplier threshold
    shrinks in absolute terms, making ordinary small movements register as wake-triggering jerks; consider
    a floor on the absolute jerk scale so an extremely still night doesn't become hyper-sensitive to noise.
15. `moveFrac` (fraction of per-second jerks above `jerkFloorMoveMult × jerkScale`) feeds both the deep
    emission (negative weight) and the awake emission (positive weight) — on a noisy accelerometer decode,
    the same noise floor issue would simultaneously suppress deep AND inflate awake, which is exactly this
    night's pattern (2% deep + 31% awake). Worth checking raw jerk distribution for this session for a
    decode/calibration artifact before assuming it's a true restless night.
16. Add hysteresis on the awake→light transition (currently governed only by the Viterbi transition matrix
    row `"awake" to "light" = 0.27`) — a jerk-related false wake should require 2+ consecutive high-jerk
    epochs to open a wake segment, closing it on the SAME bar it takes to open, to avoid single-epoch noise
    spikes fragmenting into visible wake time.

**Cross-cutting / consistency**
17. Fix the hero-card vs stage-breakdown duration mismatch (7:06 vs 7h19m) — likely two different
    aggregation paths reading the same segments; unify before any further staging work, since a fix to one
    path won't visibly help if the OTHER screen is what the user is looking at.
18. Consolidate the sleep-debt ledger (−7h31m) against the Trend card's own Sleep Debt chart (0.0h) for the
    identical 10-night window — these cannot both be true; find whether one uses `SleepDebt.kt`'s ledger
    balance and the other recomputes independently from `totalSleepMin` deltas.
19. Add a single "why these numbers differ" note anywhere NOOP and WHOOP durations are shown side by side,
    since even a CORRECT algorithm will diverge from WHOOP's proprietary staging by design — right now a
    user has no way to tell an honest approximation gap from a bug.
20. Persist per-night stager version (V1/V2) and onset-source (gravity/HR-only) as durable `DailyMetric`
    fields, not just derivable-from-logs — needed for any future audit like this one to be a query instead
    of a screenshot hunt.

---

## Related paths

- `android/app/src/main/java/com/noop/ui/SleepScreen.kt`
- `android/app/src/main/java/com/noop/analytics/SleepStager.kt` / `SleepStagerV2.kt`
- `android/app/src/main/java/com/noop/analytics/SleepStageTotals.kt` / `HcNoopAlign.kt`
- `android/app/src/main/java/com/noop/analytics/IntelligenceEngine.kt` (`sleepStagerV2ForFamily`)
- `Strand/Screens/SleepView.swift` (segment-first merge; H9 low-confidence when restorative &gt; 0 but implausibly low)
- Fable: `docs/FABLE_200_UI_IMPROVEMENTS.md`, `docs/FABLE5_300_NOT_INTUITIVE.md`
- Handoff: `ANY_MODEL_CONTINUE.md` → **Sleep continuation marker**
