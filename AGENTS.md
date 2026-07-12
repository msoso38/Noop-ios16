# NOOP agent rules

## Venial polish (save tokens)

For small UI tweaks: **`docs/VENIAL_TASKS.md`**. Skip full Impeccable explore.

## Protect test features

- After source edits use **`Tools\deploy_live_edit.ps1`** only.
- Do **not** install the published store APK over debug (wipes UI demo lab). `start_noop_live_review.ps1` keeps the source APK unless `-InstallPublished`.
- Charging preview: More → App → Test Centre (first card), or `Tools\preview_charging.ps1`.

## Impeccable (REQUIRED for non-venial UI)

1. `PRODUCT.md` + `DESIGN.md`
2. `node .agents/skills/impeccable/scripts/context.mjs --target .`
3. `reference/product.md` + `reference/android.md`
4. Material 3 + NOOP tokens; no glows / nested cards / invented vitals

## Live edit (emulator only)

- Surface = **emulator window**. Never stream the phone.
- Start emu: `Tools\start_noop_live_review.ps1 -StartEmulator`
- Deploy: `Tools\deploy_live_edit.ps1 -Serial emulator-5554`
- Charging: `Tools\preview_charging.ps1 -Serial emulator-5554 -Pct 67`

## Real MG BLE on emulator (Google Bumble)

```powershell
Tools\start_emu_ble_bridge.ps1 -Usb usb:VID:PID
```

Needs a **dedicated USB Bluetooth dongle** with **WinUSB via Zadig**. See `docs/LIVE_APK_REVIEW.md`.
