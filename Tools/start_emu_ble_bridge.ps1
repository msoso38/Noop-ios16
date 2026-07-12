param(
    [string]$Avd = "WhoopRoot_API34",
    [int]$BridgePort = 8877,
    [string]$Usb = "usb:0",
    [switch]$KeepExistingEmu,
    [int]$MemoryMb = 2048
)

<#
.SYNOPSIS
  Google Bumble HCI bridge: PC Bluetooth radio -> Android emulator.

  Docs: https://google.github.io/bumble/platforms/android.html

  Flow:
    1) bumble-hci-bridge android-netsim:_:PORT,mode=controller USB
    2) emulator -avd ... -packet-streamer-endpoint localhost:PORT

  Prefer a dedicated USB BLE dongle. Built-in Intel (usb:0 / usb:8087:0033) is often
  locked by Windows; if the bridge cannot open HCI, plug a USB dongle and pass
  -Usb usb:VID:PID from:  python -m bumble.apps.usb_probe
#>

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$emulator = Join-Path $env:LOCALAPPDATA "Android\Sdk\emulator\emulator.exe"
$apk = "<AI_APPSTORE>\apks\NOOP-v8.6.22-sleep-plan-sport-ml-debug-full-debug.apk"
$pkg = "com.noop.whoop.debug"
$activity = "com.noop.IconDefault"
$scripts = Join-Path $env:APPDATA "Python\Python314\Scripts"
$bridgeExe = Join-Path $scripts "bumble-hci-bridge.exe"
$logDir = Join-Path $root ".impeccable\ble-bridge"
$logFile = Join-Path $logDir "bumble-hci-bridge.log"

if (-not (Test-Path $emulator)) { throw "emulator not found: $emulator" }
if (-not (Test-Path $bridgeExe)) {
    Write-Host "Installing bumble..."
    python -m pip install --upgrade bumble grpcio | Out-Null
    if (-not (Test-Path $bridgeExe)) { throw "bumble-hci-bridge missing at $bridgeExe" }
}
try { python -c "import grpc" 2>$null } catch {
    Write-Host "Installing grpcio (required for android-netsim)..."
    python -m pip install grpcio | Out-Null
}

New-Item -ItemType Directory -Force -Path $logDir | Out-Null

Write-Host "=== USB Bluetooth candidates (Bumble) ==="
python -m bumble.apps.usb_probe 2>&1 | Out-Host

# Stop phone mirrors if any; never use Fold for this path.
Get-Process scrcpy -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match 'noop_live_apk_bridge|bumble-hci-bridge' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

if (-not $KeepExistingEmu) {
    $emuSerials = @(& $adb devices | Select-String "emulator-\d+\tdevice" | ForEach-Object { ($_ -split "\s+")[0] })
    foreach ($s in $emuSerials) {
        Write-Host "Stopping $s ..."
        & $adb -s $s emu kill 2>$null
    }
    Get-Process qemu-system*,emulator -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
}

$hostEnd = "android-netsim:_:${BridgePort},mode=controller"
Write-Host ""
Write-Host "Starting Bumble HCI bridge:"
Write-Host "  $bridgeExe $hostEnd $Usb"
Write-Host "  log: $logFile"

$env:BUMBLE_LOGLEVEL = "DEBUG"
$bridgeArgs = @($hostEnd, $Usb)
$errFile = Join-Path $logDir "bumble-hci-bridge.err.log"
Start-Process -FilePath $bridgeExe -ArgumentList $bridgeArgs -RedirectStandardOutput $logFile -RedirectStandardError $errFile -WindowStyle Hidden
Start-Sleep -Seconds 3

if (-not (Get-Process bumble-hci-bridge -ErrorAction SilentlyContinue)) {
    Write-Host "Bridge process exited. Last log lines:"
    if (Test-Path $logFile) { Get-Content $logFile -Tail 40 }
    if (Test-Path $errFile) { Get-Content $errFile -Tail 40 }
    throw "bumble-hci-bridge failed to stay up. Often needs a USB dongle WinUSB-owned (not Windows Bluetooth). Probe: python -m bumble.apps.usb_probe"
}

Write-Host "Bridge PID(s): $((Get-Process bumble-hci-bridge).Id -join ', ')"
Write-Host "Launching emulator $Avd -> localhost:$BridgePort ..."

$emuArgs = @(
    "-avd", $Avd,
    "-packet-streamer-endpoint", "localhost:$BridgePort",
    "-no-boot-anim",
    "-gpu", "swiftshader_indirect",
    "-memory", "$MemoryMb"
)
Start-Process -FilePath $emulator -ArgumentList $emuArgs
& $adb wait-for-device

$deadline = (Get-Date).AddMinutes(4)
$serial = $null
do {
    Start-Sleep -Seconds 2
    $serials = @(& $adb devices | Select-String "emulator-\d+\tdevice" | ForEach-Object { ($_ -split "\s+")[0] })
    if ($serials.Count -ge 1) {
        $serial = $serials[0]
        $booted = (& $adb -s $serial shell getprop sys.boot_completed 2>$null)
        if ($booted) { $booted = $booted.Trim() }
    } else { $booted = "" }
    Write-Host "  boot=$booted serial=$serial"
} while ($booted -ne "1" -and (Get-Date) -lt $deadline)

if ($booted -ne "1") { throw "Emulator boot timed out" }

# Toggle BT off/on after attach (Bumble tip)
& $adb -s $serial shell svc bluetooth disable | Out-Null
Start-Sleep -Seconds 2
& $adb -s $serial shell svc bluetooth enable | Out-Null
Start-Sleep -Seconds 2

if (Test-Path $apk) {
    & $adb -s $serial install -r $apk | Out-Null
    & $adb -s $serial shell am start -n "$pkg/$activity" | Out-Null
}

Write-Host ""
Write-Host "READY"
Write-Host "  Emulator: $serial"
Write-Host "  Bridge:   localhost:$BridgePort  USB=$Usb"
Write-Host "  Log:      $logFile"
Write-Host "  Put MG in pairing mode, then in NOOP: More -> Devices -> Add a device"
Write-Host "  If BT won't turn on: LE-only dongle, or Windows still owns the radio. Use a dedicated USB dongle + Zadig WinUSB."
Write-Host "  Bridge log tip: look for HCI traffic after enabling BT in the emulator."
