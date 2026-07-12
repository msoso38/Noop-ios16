param(
    [string]$Serial = "emulator-5554",
    [switch]$Clean
)

# Rebuild + install NOOP fullDebug onto emulator/device. ASCII-only (Windows PS safe).

$ErrorActionPreference = "Stop"

$projectDir = Join-Path (Split-Path -Parent $PSScriptRoot) "android"
$workspaceDir = (Resolve-Path (Join-Path $projectDir "..\..")).Path
$gradle = "<USER_GRADLE>\wrapper\dists\gradle-8.7-bin\bhs2wmbdwecv87pi65oeuq5iu\gradle-8.7\bin\gradle.bat"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$pkg = "com.noop.whoop.debug"
$activity = "com.noop.IconDefault"

if (-not (Test-Path $gradle)) { throw "Gradle not found at $gradle" }
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }

$devices = @(& $adb devices | Select-String "\tdevice$" | ForEach-Object { ($_ -split "\s+")[0] })
if ($devices -notcontains $Serial) {
    throw "Device $Serial not connected. Connected: $($devices -join ', '). Start Tools\start_noop_live_review.ps1 first."
}

if (-not (Test-Path "N:\android-sdk-local")) {
    subst N: $workspaceDir | Out-Null
}

$env:JAVA_HOME = "<USER_JDKS>\jbr-17.0.14"
$env:ANDROID_HOME = "N:\android-sdk-local"
$env:ANDROID_SDK_ROOT = "N:\android-sdk-local"

Push-Location "N:\noop-v8.4.0-src\android"
try {
    $gradleArgs = @(":app:installFullDebug", "--no-daemon", "-Dorg.gradle.jvmargs=-Xmx1536m", "--max-workers=1")
    if ($Clean) { $gradleArgs = @(":app:clean") + $gradleArgs }
    Write-Host "Deploying to $Serial via $($gradleArgs -join ' ') ..."
    & $gradle @gradleArgs
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
finally {
    Pop-Location
}

& $adb -s $Serial shell am force-stop $pkg | Out-Null
& $adb -s $Serial shell am start -n "$pkg/$activity" | Out-Null
$pidOnDevice = (& $adb -s $Serial shell pidof $pkg 2>$null)
Write-Host "Live APK updated on $Serial (pid=$pidOnDevice). Emulator window is the app."
Write-Host "Preview charging: Tools\preview_charging.ps1 -Serial $Serial"
