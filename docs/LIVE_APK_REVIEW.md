# Live UI on the emulator (no phone)

## Rules

- Work surface = **emulator window** (`emulator-5554`).
- **Do not stream the Fold**.
- Browser mark page optional; Live off by default.

## Start (UI only)

```powershell
powershell -ExecutionPolicy Bypass -File Tools\start_noop_live_review.ps1 -StartEmulator
```

Redeploy:

```powershell
powershell -ExecutionPolicy Bypass -File Tools\deploy_live_edit.ps1 -Serial emulator-5554
```

## Real MG on emulator — Google Bumble HCI bridge

Official method: [Bumble Android](https://google.github.io/bumble/platforms/android.html) + [Windows USB HCI](https://google.github.io/bumble/platforms/windows.html).

```text
WHOOP MG ←BLE→ USB dongle (WinUSB) ←HCI→ bumble-hci-bridge ←netsim→ emulator ← NOOP
```

### One-time dongle setup (required on Windows)

1. Plug in a **dedicated** USB Bluetooth dongle (not the laptop Intel radio).
2. Install [Zadig](https://zadig.akeo.ie/). Select the dongle → driver **WinUSB** → Install.
3. In Device Manager it must sit under **Universal Serial Bus devices** with `winusb.sys` (not under Bluetooth).
4. Probe:

```powershell
python -m bumble.apps.usb_probe
```

Use that dongle’s `usb:VID:PID`.

### Launch

```powershell
powershell -ExecutionPolicy Bypass -File Tools\start_emu_ble_bridge.ps1 -Usb usb:VID:PID
```

Then: MG pairing mode → NOOP on emulator → More → Devices → Add a device.

### Verified on this PC

- Bumble `0.0.232` + `grpcio` installed.
- Emulator 36.4.9 supports `-packet-streamer-endpoint`.
- Launcher: `Tools/start_emu_ble_bridge.ps1`.
- Built-in Intel (`usb:0` / `usb:8087:0033`) fails with `LIBUSB_ERROR_NOT_SUPPORTED` while Windows owns it — exactly what Google’s Windows docs warn about.

## Impeccable

```powershell
node .agents/skills/impeccable/scripts/context.mjs --target .
```
