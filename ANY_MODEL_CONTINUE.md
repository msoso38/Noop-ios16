# ANY MODEL CONTINUE (token-cheap)

**Update this file last.**

## Device
- **USB Fold serial: `<ADB_SERIAL_USB>`** — primary. Deploy: `Tools\deploy_live_edit.ps1 -Serial <ADB_SERIAL_USB>`
- Tailscale: `<FOLD_TAILSCALE_IP>` (`<FOLD_TAILSCALE_HOST>`) — **ping OK, ADB :5555 refused** until `adb tcpip 5555` over USB once
- Pull+calibrate: `Tools\pull_fold_calibration.ps1`
- PC SSH: `ssh desk` → `<DESK_HOSTNAME>` (`<DESK_TAILSCALE_IP>`) works

## Ask-list
- **`docs/ASKING_FOR_BUT_NEVER_GET.md`** — ML calibrate + UI polish **in progress**; Hermes/Gemma stop noted done; deploy blocked on metric gate

## This session (2026-07-12 continued)

### Desk / GPU
- Leave Apollo alone; no Gemma / CUDA from this lane
- CPU-only calibrate/train; AMD desk if ML resumes later (~75% cap)

### ML calibrate + gate (re-ran 08:36Z)
| Head | N | MAE before | MAE after | r | Fitted |
|------|---|------------|-----------|---|--------|
| Charge | 0 | — | — | — | no |
| Effort | 1 | 2.88 | 2.88 | — | no |
| Sleep | 0 | — | — | — | no |
| Stress | 0 | — | — | — | no |

- **accuracy_valid=false** · **deploy_gate=FAIL**
- Could not pull Fold prefs (no ADB). Labels still: one day Strain 14.7 only (`2026-07-10`), no Recovery/Sleep/Stress

### User must sync (exact)
1. **USB once:** enable USB debugging → `adb -s <ADB_SERIAL_USB> tcpip 5555` → `adb connect <FOLD_TAILSCALE_IP>:5555`
2. **WHOOP app** each morning for ≥3 completed days: Recovery %, Sleep %, Day Strain 0–21, Stress if shown
3. **NOOP:** Log WHOOP app scores (or Accessibility auto-capture) for those days
4. **Health Connect:** grant Sleep + HR; WHOOP→HC sync
5. **Wear overnight** so NOOP Charge/Rest/Effort score (do not treat `effort_proxy` as Effort)
6. Re-run `Tools\pull_fold_calibration.ps1` then confirm `accuracy_valid=true`

### UI this session (~34 shipped)
- Provenance/pill collapse; SpO2 hide; Stress /3 honesty; Charge sheet flat
- Sleep WHOOP % strip; Trends unit/export/48dp chevrons; Sport/Effort untint
- Alarm wash off; Settings overline drop; Cycle Import untint; pull script


### PR / handoff (this pass)
- Expanded beyond docs-only PR #329: full scrubbed android + docs + MG Tools onto fork branch for ryanbr/noop
- UI polish +3 (Sleep tools one-row, stage rhythm) -> ~34 shipped; still not on Fold
- Deploy still **NO** (gate FAIL; Fold ping OK, ADB :5555 refused; no USB)

### Deploy
- **NO** — gate fail + no ADB. Ship code only.

## Do not regress
- Period dates ≥ today; WHOOP compare 4 heads; Cycle tab toggle; equal vessels; Sleep night-first

## Paste
```text
Read ANY_MODEL_CONTINUE.md + docs/ASKING_FOR_BUT_NEVER_GET.md + docs/CALIBRATION.md + docs/UI_POLISH_PASS_2026-07-12.md. Deploy NO (cal gate FAIL N sparse; Fold ADB 5555 refused). CPU ML only; no Gemma/Apollo. USB tcpip then pull_fold_calibration.ps1 when ready.
```
