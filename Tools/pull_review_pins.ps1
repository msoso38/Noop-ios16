param(
    [string]$Serial = "emulator-5554",
    [switch]$ClearOnDevice
)

<#
.SYNOPSIS
  Pull DEBUG review pins from the emulator into REVIEW_PINS.md for batch agent fixes.

.USAGE
  1. On emu: tap the pin FAB (bottom-right) → tap a spot → type a note → Pin it.
  2. Repeat on any screen.
  3. Run: Tools\pull_review_pins.ps1
  4. Tell the agent: "Read REVIEW_PINS.md and fix all of them."
#>

$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
$workspace = Split-Path -Parent $root
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$pkg = "com.noop.whoop.debug"
$remoteDir = "/sdcard/Android/data/$pkg/files"
$outMd = Join-Path $root "REVIEW_PINS.md"
$outJson = Join-Path $root "REVIEW_PINS.json"
$wsMd = Join-Path $workspace "REVIEW_PINS.md"

if (-not (Test-Path -LiteralPath $adb)) { throw "adb not found at $adb" }

$devices = @(& $adb devices | Select-String "\tdevice$" | ForEach-Object { ($_ -split "\s+")[0] })
if ($Serial -notin $devices) {
    $emu = @($devices | Where-Object { $_ -like "emulator-*" })
    if ($emu.Count -ge 1) { $Serial = $emu[0] }
    else { throw "No device. Start the emulator or pass -Serial." }
}

$remoteMd = "$remoteDir/review_pins.md"
$remoteJson = "$remoteDir/review_pins.json"

& $adb -s $Serial shell "run-as $pkg cat files/review_pins.md" 2>$null | Out-Null
# Prefer external files path (world-readable for adb pull).
$pulled = $false
foreach ($pair in @(
    @{ Remote = $remoteMd; Local = $outMd },
    @{ Remote = $remoteJson; Local = $outJson }
)) {
    & $adb -s $Serial pull $pair.Remote $pair.Local 2>$null
    if ($LASTEXITCODE -eq 0 -and (Test-Path -LiteralPath $pair.Local)) {
        $pulled = $true
    }
}

if (-not (Test-Path -LiteralPath $outMd)) {
    # Fallback: app-private files via run-as
    $tmp = Join-Path $env:TEMP "noop_review_pins.md"
    $raw = & $adb -s $Serial shell "run-as $pkg cat files/review_pins.md" 2>$null
    if ($LASTEXITCODE -eq 0 -and $raw) {
        Set-Content -LiteralPath $outMd -Value ($raw -join "`n") -Encoding utf8
        $pulled = $true
    }
}

if (-not $pulled -or -not (Test-Path -LiteralPath $outMd)) {
    @"
# Review pins

_No pins found on device._

On the debug app: tap the **pin** button (bottom-right) → tap a spot → leave a note → **Pin it**.
Then re-run ``Tools\pull_review_pins.ps1``.
"@ | Set-Content -LiteralPath $outMd -Encoding utf8
    Write-Host "No pins on device yet. Wrote empty stub to $outMd"
} else {
    Copy-Item -LiteralPath $outMd -Destination $wsMd -Force
    Write-Host "Pulled pins → $outMd"
    Write-Host "Also copied → $wsMd"
    Get-Content -LiteralPath $outMd | Select-Object -First 40
}

if ($ClearOnDevice) {
    & $adb -s $Serial shell "rm -f $remoteMd $remoteJson" 2>$null
    Write-Host "Cleared on-device pin files (in-app pins still until Clear all)."
}

Write-Host ""
Write-Host "Tell the agent: Read REVIEW_PINS.md and fix all pins."
