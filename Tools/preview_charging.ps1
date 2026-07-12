param(
    [string]$Serial = "emulator-5554",
    [double]$Pct = 67
)

# Show charging full-screen on debug NOOP (emulator OK, no strap).

$ErrorActionPreference = "Stop"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$pkg = "com.noop.whoop.debug"
$activity = "com.noop.ui.MainActivity"

if (-not (Test-Path $adb)) { throw "adb not found" }
$devices = @(& $adb devices | Select-String "\tdevice$" | ForEach-Object { ($_ -split "\s+")[0] })
if ($devices -notcontains $Serial) { throw "Device $Serial not connected. Found: $($devices -join ', ')" }

# Emulator often ships with animator_duration_scale=0 (Developer options), which makes
# Compose tween animations finish in one frame. Re-enable so the charge sequence is visible.
& $adb -s $Serial shell settings put global animator_duration_scale 1
& $adb -s $Serial shell settings put global transition_animation_scale 1
& $adb -s $Serial shell settings put global window_animation_scale 1

# Cold start with intent extras (most reliable on emulator).
& $adb -s $Serial shell am force-stop $pkg | Out-Null
Start-Sleep -Milliseconds 400
& $adb -s $Serial shell am start -n "$pkg/$activity" --ez noop_preview_charging true --ef pct $Pct
Write-Host "Charging preview launch -> $Serial at $Pct%."
Write-Host "Sequence: ding -> frost blur -> clear+green (clear faster, green bounce) -> snap close."
Write-Host "Or in-app: More -> Settings -> Test Centre -> UI demo lab -> Preview charging animation"
