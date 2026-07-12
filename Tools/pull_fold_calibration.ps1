# Pull WHOOP/NOOP calibration inputs from Fold (USB or wireless ADB), then re-run calibrate.
# Usage:
#   .\Tools\pull_fold_calibration.ps1
#   .\Tools\pull_fold_calibration.ps1 -Serial <ADB_SERIAL_USB>
#   .\Tools\pull_fold_calibration.ps1 -Serial <FOLD_TAILSCALE_IP>:5555
#
# Wireless ADB must already be listening (USB once: adb -s <ADB_SERIAL_USB> tcpip 5555).

param(
    [string]$Serial = "<ADB_SERIAL_USB>",
    [string]$TailscaleHost = "<FOLD_TAILSCALE_IP>"
)

$ErrorActionPreference = "Stop"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }

$noopRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$pairing = [IO.Path]::GetFullPath((Join-Path $noopRoot "..\pairing-logs"))
if (-not (Test-Path $pairing)) {
    New-Item -ItemType Directory -Path $pairing | Out-Null
}

function Get-AdbDevices {
    & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" }
}

$list = Get-AdbDevices
if (-not $list) {
    Write-Host "No USB device; trying Tailscale ${TailscaleHost}:5555 ..."
    & $adb connect "${TailscaleHost}:5555" | Out-Host
    Start-Sleep -Seconds 2
    $list = Get-AdbDevices
}

if (-not $list) {
    Write-Host @"

PULL BLOCKED — Fold not on ADB.
Tailscale ping can succeed while port 5555 refuses (wireless debugging off).

USB once on this PC:
  1. Enable USB debugging on Fold
  2. & `$adb -s <ADB_SERIAL_USB> tcpip 5555
  3. & `$adb connect <FOLD_TAILSCALE_IP>:5555
  4. Re-run this script

WHOOP / Health Connect (need ≥3 completed paired days):
  - Each morning open WHOOP: Recovery %, Sleep %, Day Strain (0–21), Stress if shown
  - NOOP: Log WHOOP app scores OR Accessibility auto-capture for ≥3 finished days
  - Health Connect: grant Sleep + HR to NOOP; let WHOOP sync into HC
  - Wear overnight so NOOP scores Charge / Rest / Effort (effort_proxy alone is not Effort)
  - Export or share noop-daily-metrics.jsonl into pairing-logs when available

"@
    exit 2
}

$use = $Serial
if (-not ($list | Where-Object { $_ -match [regex]::Escape($Serial) })) {
    $use = ($list[0] -split "\s+")[0]
}
Write-Host "Using device $use"

$pkg = "com.noop.app"
$outPrefs = Join-Path $pairing "noop_whoop_app_scores.xml"
& $adb -s $use shell "run-as $pkg cat /data/data/$pkg/shared_prefs/noop_whoop_app_scores.xml" 2>$null |
    Set-Content -Path $outPrefs -Encoding utf8
if ((Get-Item $outPrefs -ErrorAction SilentlyContinue).Length -lt 20) {
    Write-Host "Could not read whoop app prefs via run-as (release build / no debug)."
}

& $adb -s $use shell "ls /sdcard/Download/noop* 2>/dev/null; ls /sdcard/Android/data/$pkg/files/ 2>/dev/null" | Out-Host

Push-Location $noopRoot
python Tools/calibrate_whoop_noop.py
Pop-Location
Write-Host "Calibration refreshed. See $pairing\calibration-report.json"
