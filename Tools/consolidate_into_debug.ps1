param(
    [string]$Serial = ""
)

# Stage Downloads into debug app storage. Auto-picks USB Fold > wifi adb > emulator.
$ErrorActionPreference = "Continue"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$pkg = "com.noop.whoop.debug"
$releasePkg = "com.noop.whoop"
$appDl = "/sdcard/Android/data/$pkg/files/Download"

function Resolve-NoopSerial([string]$prefer) {
    if ($prefer) { return $prefer }
    $lines = & $adb devices | Select-String "\tdevice$"
    $ids = @($lines | ForEach-Object { ($_ -split "\s+")[0] })
    $usb = $ids | Where-Object { $_ -notmatch "emulator" -and $_ -notmatch ":" } | Select-Object -First 1
    if ($usb) { return $usb }
    $wifi = $ids | Where-Object { $_ -match ":" } | Select-Object -First 1
    if ($wifi) { return $wifi }
    $emu = $ids | Where-Object { $_ -match "emulator" } | Select-Object -First 1
    if ($emu) { return $emu }
    throw "No adb device online. Plug Fold USB, or adb connect <tailscale-ip>:5555"
}

$Serial = Resolve-NoopSerial $Serial
Write-Host "Using serial $Serial"

$hasDebug = (& $adb -s $Serial shell pm path $pkg 2>$null) -match "package:"
$hasRelease = (& $adb -s $Serial shell pm path $releasePkg 2>$null) -match "package:"
Write-Host "Packages: debug=$hasDebug release=$hasRelease"

if (-not $hasDebug) {
    Write-Host "DEBUG app missing - deploy first: Tools\deploy_live_edit.ps1 -Serial $Serial"
    exit 1
}

& $adb -s $Serial shell "mkdir -p $appDl"
# Stage known imports; ignore missing files
& $adb -s $Serial shell "cp '/sdcard/Download/My Calendar2026-07-10_iphone.pc' $appDl/" 2>$null
& $adb -s $Serial shell "cp '/sdcard/Download/noop-export-2026-07-08.zip' $appDl/" 2>$null
& $adb -s $Serial shell "sh -c 'cp /sdcard/Download/*.noopbak $appDl/ 2>/dev/null; cp /sdcard/Download/*.pc $appDl/ 2>/dev/null; true'"

Write-Host "Staged into $appDl"
& $adb -s $Serial shell "ls -la $appDl"

if ($hasRelease) {
    Write-Host "Release NOOP is installed. Export .noopbak from release Data sources, then DEBUG Consolidate / Backup Import."
} else {
    Write-Host "Release package not on this device. Drop a .noopbak in Downloads then Consolidate now."
}

$ask = Join-Path $PSScriptRoot "adb_ask_user.ps1"
if (Test-Path $ask) {
    $body = "1) Export release .noopbak to Downloads if you have release history. 2) DEBUG Data sources: Consolidate now. 3) Allow Health Connect. 4) Open Cycle and check August shading."
    powershell -NoProfile -ExecutionPolicy Bypass -File $ask -Serial $Serial -Title "NOOP merge" -Body $body
}

Write-Host "Then open DEBUG Data sources and tap Consolidate now."
Write-Host "Tailscale Fold: <FOLD_TAILSCALE_HOST> = <FOLD_TAILSCALE_IP> (adb tcpip 5555 if wireless refused)."
