# WHOOP ↔ NOOP screenshot compare playbook

**Audience:** any model picking up `ANY_MODEL_CONTINUE.md`.  
**Goal:** turn Gilbert’s JPG dumps into durable, clock-matched anchors — then compare **three** lanes before tuning code:

| Lane | What it is | Trust for tuning? |
|------|------------|-------------------|
| **A. WHOOP app UI** | Official tip / stages / strain from screenshots or adb UI dump | Labels only (ground truth for “what WHOOP showed”) |
| **B. NOOP app UI** | Same wall-clock tip / Rest / stages from screenshots | Labels only (what NOOP showed the user) |
| **C. Band streams** | Fold bank HR / R-R / gravity / steps / sleep-state | Features — replay through real scorers (`FoldStressReplay`, sleep replays) |

Never tune constants from A↔B alone. Prefer A↔C (replay at screenshot minute) or B↔C (UI vs banked truth). JPG-only packs have **no CSV** — fill `manifest.csv` by reading pixels.

Related: `noop-v8.4.0-src/Tools/ingest_export.ps1`, `docs/STRESS_FACTORS_AND_LITERATURE.md`, `docs/SLEEP_FACTORS_AND_LITERATURE.md`, `pairing-logs/whoop-app-labels.jsonl`.

---

## 1. Capture checklist (before Gilbert exports)

### Stress pack (minimum)

Capture **same minute** (±2 min) on both apps. Status-bar clock is the anchor, not the chart tip time alone.

| # | App | Screen | Why |
|---|-----|--------|-----|
| S1 | WHOOP | **Home** with Stress Monitor card visible (tip + band + calibrating) | Live tip without opening full monitor |
| S2 | WHOOP | **Stress Monitor** full page (gauge + 24h chart + high-zone copy + calib bar) | Curve shape, high-zone minutes, night floor |
| S3 | NOOP | **Today** Health / Key Metrics row with Stress tip | Side-by-side with S1 |
| S4 | NOOP | **Stress** hero (Now tip + band + methodology) | Side-by-side with S2 tip |
| S5 | NOOP | **Stress / Trends** intraday timeline (avg · hours · peak · time-in-band) | Shape vs WHOOP chart; high-zone minutes |
| S6 | optional | Same clocks again morning calm / desk / post-walk / evening | ≥4 tip@clock rows before touching constants |

**Do not skip S5.** The 2026-07-14 Noop-mg pack had tip-only NOOP stress — curve shape could not be compared.

### Sleep / Rest pack (minimum)

| # | App | Screen |
|---|-----|--------|
| L1 | WHOOP | Home rings (Sleep % / Recovery / Strain) + sleep activity row |
| L2 | WHOOP | Sleep detail (hours, stage bars Awake/Light/SWS/REM, restorative) |
| L3 | NOOP | Sleep hero (Rest score, wake-day window, “What shaped Rest”) |
| L4 | NOOP | Sleep stages / honesty strip (Deep/REM minutes, movement detail) |
| L5 | NOOP | Today Rest vessel + any debt/trend cards for the **same** wake-day |

### Filename contract (Samsung / Fold)

```text
Screenshot_YYYYMMDD_HHMMSS_<APP>.jpg
```

`<APP>` must be `WHOOP` or `NOOP` (case-insensitive). That stamp is capture time on the phone — use it for pairing, then confirm against the **status-bar clock** in the image (they usually match; if not, trust the status bar for tip@clock).

---

## 2. Import (durable, not Downloads-only)

```powershell
# From AI app store root (or noop-v8.4.0-src):
powershell -File noop-v8.4.0-src\Tools\ingest_export.ps1 `
  -ExportDir "C:\Users\Gilbert\Downloads\Noop stresd-...\Noop stresd" `
  -PackKind stress
```

What ingest does:

1. Copies every matching JPG → `pairing-logs/exports/<stamp>/`
2. Writes `manifest.csv` with `file,app,captured,screen,values,pairFile,pairGapS`
3. Writes `REQUIRED_SHOTS.md` (pack checklist vs what was found)
4. Writes `decode_worksheet.md` (one section per file — fill screen + values here or in CSV)
5. Prints coverage: apps, span, paired count

If Gilbert drops a Google Drive zip, unzip first so leaf folder contains `Screenshot_*` files. Prefer `-ExportDir` over “newest Downloads folder” when multiple packs exist.

**After decode:** append one JSONL row per clock-matched tip to `pairing-logs/whoop-app-labels.jsonl` with `"source":"screenshot"` (see §5).

---

## 3. Identify the chart (screen taxonomy)

Fill `manifest.csv` `screen` with **exactly one** of these IDs (add `|note` only in `values`).

### WHOOP

| `screen` | Visual tells |
|----------|----------------|
| `whoop_home` | Three rings Sleep/Recovery/Strain; Stress Monitor **card** (e.g. 1.2 MEDIUM); My Day activities |
| `whoop_stress_monitor` | Title **STRESS MONITOR**; big tip 0–3 + LOW/MEDIUM/HIGH; 24h jagged chart 0–3; purple “Wear … nights” bar; “You spent X hr Y min in the high stress zone” |
| `whoop_sleep_detail` | **HOURS OF SLEEP**; HR line during sleep; Awake/Light/SWS/REM bars + % |
| `whoop_health_monitor` | Health tiles (HR, SpO2, RHR, HRV…); often “Calibrating” |
| `whoop_strain` / `whoop_recovery` | Strain 0–21 or Recovery % detail |
| `whoop_other` | Anything else — describe in `values` |

### NOOP

| `screen` | Visual tells |
|----------|----------------|
| `noop_today_health` | Today tab; Health / Key Metrics; Stress as **X.X / 3** or similar row |
| `noop_stress_hero` | Stress Monitor / Stress hero: big tip, MEDIUM of 3, Baevsky / LF/HF cards (display-only) |
| `noop_stress_timeline` | **INTRADAY** / “Stress Through The Day”; peak · hour; Calm/Mod/High time-in-band; may sit on Trends |
| `noop_stress_history` | **HISTORY** / Stress Trend daily proxy |
| `noop_sleep_hero` | Sleep tab Rest gauge (e.g. 73 Strong); “What shaped Rest” |
| `noop_sleep_stages` | Stage minutes Deep/REM/Awake/Light; honesty / no-movement strip |
| `noop_trends` | Week charts Charge/HRV/Effort/debt |
| `noop_other` | Describe in `values` |

### Chart vs number (don’t confuse)

| Looking at… | Extract |
|-------------|---------|
| WHOOP Stress **gauge** | `tip`, `band`, `tipClock` (time under gauge, e.g. 2:10 AM) |
| WHOOP Stress **line** | Night floor (~0.3–0.5), wake rise, daytime plateau, activity glyph peaks, tip marker |
| WHOOP high-zone **sentence** | `highZone` as `2h8m` style — not the tip |
| NOOP Stress **hero** | `tip`, `band` — often “MEDIUM of 3”; Advanced HRV is **not** the tip |
| NOOP **intraday** line | `avg`, `scoredHours`, `peak`, `peakHour`, `calmZone`, `modZone`, `highZone` |
| Sleep **% bars** | Stage minutes + %; WHOOP SWS = Deep |
| NOOP Rest **gauge** | Rest 0–100 + label; separate from stage table |

---

## 4. `values` encoding (machine-friendly)

Semicolon-separated `key=val` in one cell. Use these keys when present:

```text
tip=1.4; band=MEDIUM; tipClock=22:14; calibrating=yes; calibNightsLeft=2
highZone=2h8m; modZone=2h30m; calmZone=14h
avg=0.8; scoredHours=18; peak=2.5; peakHour=21
sleepPct=64; recoveryPct=43; strain=10.2
asleep=7:06; onset=03:48; wake=13:08; deep=2:53; rem=1:32; awake=2:13; light=2:41
rest=73; restLabel=Strong; noopAsleep=7:19; deepMin=10; remMin=0
statusBarClock=22:14; note=health_card_not_full_monitor
```

Rules:

- Prefer **statusBarClock** for tip@clock pairing.
- If tip time on chart ≠ status bar, record both (`tipClock` + `statusBarClock`).
- Never invent SpO2 / clinical BP from a blank tile.
- Mark `calibrating=yes` when WHOOP/NOOP show wear-N-nights — tip is a weak tuning signal.

---

## 5. Three-lane compare protocol

### Step A — Pair by clock

From `manifest.csv`, keep rows with `pairGapS` ≤ 120 (2 min) for tip compares; ≤ 600 (10 min) for same-session inventory. Prefer pairs where both `screen` are stress-class (`whoop_stress_monitor`↔`noop_stress_hero` or `whoop_home`↔`noop_today_health`).

### Step B — Tip@clock table (UI only)

| Local clock | WHOOP tip/band | NOOP tip/band | \|δ\| | Sources (files) | Tag |
|-------------|----------------|---------------|------|-----------------|-----|
| 22:14 | 1.4 MEDIUM | 1.8 / 3 | 0.4 | whoop_… ; noop_… | fill |

Pass heuristic (stress): calm LOW within ~0.3; same band for MEDIUM/HIGH; direction of spikes matches.

### Step C — Band replay (required before constant changes)

1. Pull Fold bank for that local day (HR, R-R, gravity, steps, sleep-state).
2. Run `FoldStressReplay` (or sleep replay) with **hard cutoff** at the screenshot minute so calm reference matches what the live app knew.
3. Fill: WHOOP UI tip vs **replay tip** vs NOOP UI tip.

| Clock | WHOOP UI | NOOP UI | Band replay | Action |
|-------|----------|---------|-------------|--------|
| … | … | … | … | If UI≠replay → UI/data bug first. If replay≠WHOOP → algo candidate. |

### Step D — Labels JSONL

Append (one object per matched tip day/minute):

```json
{"day":"2026-07-13","source":"screenshot","stress_tip":1.4,"stress_band":"MEDIUM","clock":"22:14","whoop_file":"Screenshot_…_WHOOP.jpg","noop_file":"Screenshot_…_NOOP.jpg","noop_tip":1.8,"serial":"export:Noop-mg"}
```

Do **not** overwrite recovery/strain fields with nulls from a stress-only shot.

---

## 6. Improve each onward instruction (how to write backlog items)

Every algorithm / UI instruction in CONTINUE or factor docs should be a **ticket**, not a vibe:

| Field | Required |
|-------|----------|
| **ID** | Stable (`STRESS-05`, `REST-01`, `SLEEP-SELF-1`) |
| **Symptom** | What the export showed (numbers + files) |
| **Code pointer** | File + symbol / constant |
| **Lane** | UI-only / replay / both |
| **Acceptance** | Measurable (e.g. \|\|δ tip\| ≤ 0.3 on ≥4 calm anchors) |
| **Do not** | Explicit anti-goal (e.g. “don’t retune from one calibrating tip”) |
| **Verify** | Unit test name and/or Fold tip@clock + ingest stamp |

### Stress onward (rewrite targets)

| ID | Symptom | Code | Acceptance | Do not |
|----|---------|------|------------|--------|
| STRESS-TIP | NOOP 1.8 vs WHOOP 1.4 same minute (mg export) | `DaytimeStress` calm anchor / night bias | ≥4 tip@clock \|\|δ\|≤0.3 in MEDIUM after replay | Retune from single calibrating sample |
| STRESS-SHAPE | Missing NOOP intraday in pack | capture S5 + compare peak/highZone | highZone within ~30% when scoredHours dense | Compare tip-only to WHOOP 24h chart |
| STRESS-ZONE | Fri high-zone gap vs workout damp | `workoutOverlapBias` product stance | Document deliberate δ or intensity-scale damp | Blindly match WHOOP exercise-in-stress |
| STRESS-NIGHT | Overnight quiet after bad staging | `overnightQuiet` sanity gate | Reject overnight pool when awakeFrac high | Blame same-evening tip on that night’s sleep |

### Sleep / Rest onward

| ID | Symptom | Code | Acceptance | Do not |
|----|---------|------|------------|--------|
| SLEEP-SELF-1 | Hero / stages / debt disagree same night | duration writers + debt ledger | One asleep minutes source of truth on Fold | Tune WHOOP match before self-consistency |
| REST-WEIGHT | Rest 73 Strong with 2% deep / 0% REM | `RestScorer` / `deepFloorFactor` | Mediocre WHOOP night not labeled Strong | Invent Deep/REM |
| SLEEP-ONSET | ~77 min early vs WHOOP | `hrOnlySleepRuns` gates | Onset within ~30 min when gravity dense; document when HR-only | Drop HR-only spine without gravity-dead nights |

### Workout onward

| ID | Symptom | Code | Acceptance |
|----|---------|------|------------|
| WORK-SIG | Re-threshold every time | `AutoWorkoutDetector` | Persist signature; re-match ≥1 prior sport class on Fold |

---

## 7. Agent workflow (token-cheap)

1. `ingest_export.ps1 -PackKind stress|sleep|mixed -ExportDir …`
2. Open each JPG (Read tool) → fill `screen` + `values` in `manifest.csv` / worksheet.
3. Write tip@clock + inventory into the matching `*_FACTORS_AND_LITERATURE.md` update section.
4. If Fold online: bank pull + replay with cutoff → three-lane table.
5. Update `ANY_MODEL_CONTINUE.md` **last**: headline δ, ticket IDs, paste block. No code unless the session is explicitly a coding pass.
6. Publish/APK only after self-consistency tickets (SLEEP-SELF-*) and acceptance checks land.

---

## 8. Known pack gaps (do not re-learn the hard way)

| Pack | Gap | Next capture |
|------|-----|--------------|
| Noop stresd 2026-07-12 | Early NOOP Stress (pre calm-anchor era UI) | Re-capture S4+S5 on current MAIN |
| Updated noop state 2026-07-13 | Good tip pair (~21:39); high-zone Fri/Sat not in same pack | Fri/Sat WHOOP high-zone + NOOP timeline |
| Noop mg 2026-07-14 | Stress tip only on NOOP Health card; **no** NOOP stress chart | Always include S4+S5 |
