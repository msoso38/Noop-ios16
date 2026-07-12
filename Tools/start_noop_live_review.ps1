param(
    [string]$Serial,
    [string]$Avd = "WhoopRoot_API34",
    [switch]$StartEmulator,
    [switch]$MarkElements,
    [switch]$InstallPublished,
    [int]$Port = 8787
)

<#
.SYNOPSIS
  Emulator-only live UI surface. Does NOT stream the phone.

  By default does NOT install the published store APK (that wipes local test features
  like UI demo lab). Use -InstallPublished only when you intentionally want the store build.
  After Compose edits: Tools\deploy_live_edit.ps1
#>

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$emulator = Join-Path $env:LOCALAPPDATA "Android\Sdk\emulator\emulator.exe"
$apk = "<AI_APPSTORE>\apks\NOOP-v8.6.22-sleep-plan-sport-ml-debug-full-debug.apk"
$pkg = "com.noop.whoop.debug"
$activity = "com.noop.IconDefault"

if (-not (Test-Path -LiteralPath $adb)) { throw "adb was not found at $adb" }

function Get-DeviceSerials {
    @(& $adb devices | Select-String "\tdevice$" | ForEach-Object { ($_ -split "\s+")[0] })
}

Get-Process scrcpy -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue

$existingEmu = @(Get-DeviceSerials | Where-Object { $_ -like "emulator-*" })
if ($StartEmulator -or $existingEmu.Count -eq 0) {
    if ($existingEmu.Count -eq 0) {
        if (-not (Test-Path -LiteralPath $emulator)) { throw "Android emulator not found at $emulator" }
        Write-Host "Starting AVD $Avd ..."
        Start-Process -FilePath $emulator -ArgumentList "-avd",$Avd,"-no-boot-anim","-gpu","swiftshader_indirect","-memory","2048"
        & $adb wait-for-device
        $deadline = (Get-Date).AddMinutes(4)
        do {
            Start-Sleep -Seconds 2
            $serials = @(Get-DeviceSerials | Where-Object { $_ -like "emulator-*" })
            $booted = ""
            if ($serials.Count -ge 1) {
                $booted = (& $adb -s $serials[0] shell getprop sys.boot_completed 2>$null)
                if ($booted) { $booted = $booted.Trim() }
            }
        } while ($booted -ne "1" -and (Get-Date) -lt $deadline)
        if ($booted -ne "1") { throw "Emulator boot timed out" }
        $existingEmu = @(Get-DeviceSerials | Where-Object { $_ -like "emulator-*" })
    }
}

if ([string]::IsNullOrWhiteSpace($Serial)) {
    if ($existingEmu.Count -lt 1) { throw "No emulator. Pass -StartEmulator." }
    $Serial = $existingEmu[0]
}
if ($Serial -notlike "emulator-*") {
    throw "This launcher is emulator-only (got $Serial). No phone streaming."
}

& $adb -s $Serial shell settings put global animator_duration_scale 1 2>$null
& $adb -s $Serial shell settings put global transition_animation_scale 1 2>$null
& $adb -s $Serial shell settings put global window_animation_scale 1 2>$null

$installed = (& $adb -s $Serial shell pm path $pkg 2>$null)
if ($InstallPublished) {
    if (-not (Test-Path -LiteralPath $apk)) { throw "Published APK was not found at $apk" }
    Write-Host "WARNING: installing published APK (wipes local UI demo lab / source-only features)."
    & $adb -s $Serial install -r $apk
    if ($LASTEXITCODE -ne 0) { throw "APK install failed" }
} elseif (-not $installed) {
    Write-Host "No $pkg on emulator — deploying source fullDebug (keeps test features)..."
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "deploy_live_edit.ps1") -Serial $Serial
    if ($LASTEXITCODE -ne 0) { throw "deploy_live_edit failed" }
} else {
    Write-Host "Keeping installed $pkg (source debug). Not reinstalling store APK."
    Write-Host "After Compose edits: Tools\deploy_live_edit.ps1 -Serial $Serial"
}

& $adb -s $Serial shell am start -n "$pkg/$activity" | Out-Null

Write-Host ""
Write-Host "WORK SURFACE: the emulator window ($Serial)"
Write-Host "Charging preview: Tools\preview_charging.ps1 -Serial $Serial"
Write-Host "UI demo lab: More -> App -> Test Centre (first card)"
Write-Host "Note: physical MG cannot pair to the emulator over BLE."

if ($MarkElements) {
    Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -match 'noop_live_apk_bridge' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    $py = Join-Path $root "Tools\noop_live_apk_bridge.py"
    Start-Process -FilePath "python" -ArgumentList $py,"--port",$Port,"--serial",$Serial -WindowStyle Minimized
    Write-Host "Mark page: http://127.0.0.1:$Port/ (Live video OFF)"
}
