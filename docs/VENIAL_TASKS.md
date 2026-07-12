# Venial UI tasks (token-saving method)

Use this for **small, obvious polish** (one overlay, one animation, one color, one copy tweak). Do **not** run the full Impeccable setup tree, explore the monorepo, or open unrelated skills.

## When a task is venial

- Touches **1–2 files** already named by the user (or obvious from the last handoff)
- Outcome is visible on the **emulator** without BLE / Fold / new architecture
- No new screens, no navigation redesign, no protocol/BLE changes

If it needs PRODUCT/DESIGN debate, multi-surface IA, or BLE: it is **not** venial — use full Impeccable.

## Agent checklist (cheap path)

1. Read **only** the named file(s) + this doc if needed. Skip `context.mjs` / full skill load.
2. Edit. Keep NOOP tokens; charging ring green must be hard `Color(0xFF2DD4A0)` (never classic `statusPositive` / `chargeColor` — those read yellow).
3. Deploy **only** source debug:
   ```powershell
   Tools\deploy_live_edit.ps1 -Serial emulator-5554
   Tools\preview_charging.ps1 -Serial emulator-5554 -Pct 67
   ```
4. Update `ANY_MODEL_CONTINUE.md` with one line.
5. Do **not** stream the Fold. Do **not** run `start_noop_live_review.ps1 -InstallPublished`.

## NEVER wipe test features

`start_noop_live_review.ps1` used to `adb install` a **published store APK**, which deletes UI demo lab and other source-only debug UI.

- Default start script: **keeps** the installed source `fullDebug` APK.
- After Compose edits: **only** `deploy_live_edit.ps1`.
- Published APK: explicit `-InstallPublished` only.

UI demo lab lives at **More → App → Test Centre** (first card) and on **More** (debug UI demo card).

## Copy-paste prompt

```text
VENIAL: [one sentence]. File(s): [paths]. Emulator only.
Follow noop-v8.4.0-src/docs/VENIAL_TASKS.md — no full Impeccable explore.
Never install store APK. deploy_live_edit.ps1 + preview_charging.ps1. Update ANY_MODEL_CONTINUE.md last.
```

## Charging overlay

| Goal | Command / entry |
|------|-----------------|
| Preview | `Tools\preview_charging.ps1 -Serial emulator-5554 -Pct N` |
| In-app | More → App → Test Centre → UI demo lab |
| Code | `ChargingFullScreen.kt` |
| Colors | Clear glass track, then mint green fill `0xFF2DD4A0` |

## Anti-patterns (burn tokens)

- Re-reading entire `SKILL.md` for a 20-line animation tweak
- `start_noop_live_review` with store APK after source edits
- Full clean rebuild when `deploy_live_edit` incremental is enough
- Phone stream / scrcpy
