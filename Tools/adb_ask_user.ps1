param(
    [string]$Serial = "<FOLD_TAILSCALE_IP>:5555",
    [string]$Title = "NOOP",
    [Parameter(Mandatory = $true)][string]$Body
)

# Short device notification asking for input (pin/reply). Don't spam — use every ~90-120m.
$ErrorActionPreference = "Continue"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { throw "adb not found" }
$devices = @(& $adb devices | Select-String "`tdevice$" | ForEach-Object { ($_ -split "\s+")[0] })
if ($Serial -notin $devices) { throw "Device $Serial not connected" }

# cmd notification post works on API 28+; tag keeps updates replacing prior ask.
& $adb -s $Serial shell cmd notification post -t "$Title" noop_ask "$Body" 2>$null
if ($LASTEXITCODE -ne 0) {
    # Fallback: toast via service call (best-effort)
    & $adb -s $Serial shell "am broadcast -a android.intent.action.BOOT_COMPLETED" 2>$null | Out-Null
    Write-Host "notification post failed; body was: $Body"
} else {
    Write-Host "ADB ask -> $Serial : $Body"
}
