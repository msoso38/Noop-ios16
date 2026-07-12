param(
    [string]$VersionName = "8.5.3-mg",
    [int]$VersionCode = 0,
    [switch]$RefreshDependencies,
    [switch]$ForceUpdate,
    [switch]$SkipServerStart,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$workspace = "<WORKSPACE>"
$store = "<AI_APPSTORE>"
$logPath = Join-Path $workspace "noop-v8.4.0-src\android\publish-main-release.log"
$gradle = "<USER_GRADLE>\wrapper\dists\gradle-8.7-bin\bhs2wmbdwecv87pi65oeuq5iu\gradle-8.7\bin\gradle.bat"
$javaHome = "<USER_JDKS>\jbr-17.0.14"
$sdkMapped = "N:\android-sdk-local"
$androidMapped = "N:\noop-v8.4.0-src\android"
$androidLong = Join-Path $workspace "noop-v8.4.0-src\android"

function Write-Log([string]$Message) {
    $line = "[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $Message
    Write-Host $line
    Add-Content -LiteralPath $logPath -Value $line -Encoding UTF8
}

function Ensure-NDrive {
    if (Test-Path -LiteralPath $sdkMapped) {
        Write-Log "N: drive already mapped to workspace SDK."
        return
    }

    # If N: exists but is wrong/broken, drop it and remap.
    if (Test-Path -LiteralPath "N:\") {
        Write-Log "Removing stale N: mapping..."
        subst N: /D | Out-Null
        Start-Sleep -Milliseconds 300
    }

    Write-Log "Mapping N: -> $workspace"
    $null = subst N: $workspace
    if (-not (Test-Path -LiteralPath $sdkMapped)) {
        throw "Failed to map N: to workspace. Build paths with spaces will fail. Tried: subst N: `"$workspace`""
    }
}

function Test-StoreUrl([string]$Url) {
    try {
        $r = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 5
        return ($r.StatusCode -ge 200 -and $r.StatusCode -lt 300)
    } catch {
        return $false
    }
}

function Start-StoreServer {
    if (Test-StoreUrl "http://127.0.0.1:8090/apps.json") {
        Write-Log "AI Store server already running."
        return
    }

    $pythonw = "C:\Python314\pythonw.exe"
    $python = if (Test-Path -LiteralPath $pythonw) { $pythonw } else { "python" }
    Write-Log "Starting AI Store server with $python ..."
    Start-Process -FilePath $python -ArgumentList "server.py" -WorkingDirectory $store -WindowStyle Hidden
    Start-Sleep -Seconds 2

    if (-not (Test-StoreUrl "http://127.0.0.1:8090/apps.json")) {
        throw "AI Store server did not start on http://127.0.0.1:8090/apps.json"
    }
}

function Invoke-NoopGradle([switch]$WithRefresh) {
    $gradleArgs = @(":app:assembleFullRelease", "--no-daemon", "--no-build-cache", "--stacktrace")
    if ($WithRefresh) { $gradleArgs += "--refresh-dependencies" }
    Write-Log "Running Gradle $($gradleArgs -join ' ')"
    & $gradle @gradleArgs 2>&1 | ForEach-Object {
        $text = "$_"
        Write-Host $text
        Add-Content -LiteralPath $logPath -Value $text -Encoding UTF8
    }
    $script:NoopGradleExitCode = $LASTEXITCODE
}

# Fresh log for this run
"" | Set-Content -LiteralPath $logPath -Encoding UTF8
Write-Log "=== publish-main-release start ==="
Write-Log "VersionName=$VersionName VersionCode=$VersionCode ForceUpdate=$ForceUpdate RefreshDependencies=$RefreshDependencies SkipBuild=$SkipBuild"

if (-not (Test-Path -LiteralPath $javaHome)) {
    throw "JAVA_HOME not found: $javaHome"
}
if (-not (Test-Path -LiteralPath $gradle)) {
    throw "Gradle not found: $gradle"
}
if (-not (Test-Path -LiteralPath $store)) {
    throw "AI Store folder not found: $store"
}

Ensure-NDrive

$env:JAVA_HOME = $javaHome
$env:ANDROID_SDK_ROOT = $sdkMapped
$env:ANDROID_HOME = $sdkMapped
$env:PATH = "$javaHome\bin;" + $env:PATH

$android = if (Test-Path -LiteralPath $androidMapped) { $androidMapped } else { $androidLong }
$manifestPath = Join-Path $store "apps.json"
if (-not (Test-Path -LiteralPath $manifestPath)) {
    throw "Store manifest missing: $manifestPath"
}

$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$noop = $manifest.apps | Where-Object { $_.id -eq "com.noop.whoop" } | Select-Object -First 1
if ($null -eq $noop) { throw "Could not find com.noop.whoop in $manifestPath" }

$storeCode = [int]$noop.versionCode
Write-Log "Current store listing: $($noop.versionName) ($storeCode) apk=$($noop.apk)"

# Always publish a higher versionCode than the store so the phone sees an update.
if ($ForceUpdate -or $VersionCode -le 0) {
    $nextCode = $storeCode + 1
    if ($VersionCode -gt $nextCode) { $nextCode = $VersionCode }
    $VersionCode = $nextCode
}
if ($VersionCode -le $storeCode) {
    throw "versionCode $VersionCode must be greater than current store versionCode $storeCode. Pass -ForceUpdate or a higher -VersionCode."
}

$apkName = "NOOP-v$VersionName-main.apk"
$apkNameVersioned = "NOOP-v$VersionName-$VersionCode-main.apk"
$buildFile = Join-Path $android "app\build.gradle.kts"
if (-not (Test-Path -LiteralPath $buildFile)) {
    throw "build.gradle.kts not found at $buildFile"
}

$buildText = Get-Content -LiteralPath $buildFile -Raw
$buildText = [regex]::Replace($buildText, 'versionCode\s*=\s*\d+', "versionCode = $VersionCode", 1)
$buildText = [regex]::Replace($buildText, 'versionName\s*=\s*"[^"]+"', "versionName = `"$VersionName`"", 1)
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($buildFile, $buildText, $utf8NoBom)
Write-Log "Build version set to $VersionName ($VersionCode)."

$src = Join-Path $android "app\build\outputs\apk\full\release\app-full-release.apk"
$meta = Join-Path $android "app\build\outputs\apk\full\release\output-metadata.json"

if (-not $SkipBuild) {
    Set-Location -LiteralPath $android
    $script:NoopGradleExitCode = 0
    Invoke-NoopGradle -WithRefresh:$RefreshDependencies
    $exit = $script:NoopGradleExitCode
    if ($exit -ne 0 -and -not $RefreshDependencies) {
        Write-Log "Gradle failed (exit $exit). Retrying once with --refresh-dependencies..."
        Invoke-NoopGradle -WithRefresh
        $exit = $script:NoopGradleExitCode
    }
    if ($exit -ne 0) {
        throw "Gradle build failed with exit code $exit. See log: $logPath"
    }
}

if (-not (Test-Path -LiteralPath $src)) {
    throw "Built APK not found at $src"
}

# Verify the APK metadata matches the intended version before publishing.
if (Test-Path -LiteralPath $meta) {
    $out = Get-Content -LiteralPath $meta -Raw | ConvertFrom-Json
    $builtCode = [int]$out.elements[0].versionCode
    $builtName = [string]$out.elements[0].versionName
    Write-Log "Built APK metadata: $builtName ($builtCode)"
    if ($builtCode -ne $VersionCode -or $builtName -ne $VersionName) {
        throw "Built APK version mismatch. Expected $VersionName ($VersionCode), got $builtName ($builtCode). Re-run without -SkipBuild."
    }
}

$apksDir = Join-Path $store "apks"
if (-not (Test-Path -LiteralPath $apksDir)) {
    New-Item -ItemType Directory -Path $apksDir | Out-Null
}

$dst = Join-Path $apksDir $apkName
$dstVersioned = Join-Path $apksDir $apkNameVersioned
Copy-Item -LiteralPath $src -Destination $dst -Force
Copy-Item -LiteralPath $src -Destination $dstVersioned -Force
Write-Log "Copied APK -> $dst"
Write-Log "Copied APK -> $dstVersioned"

$noop.versionName = $VersionName
$noop.versionCode = $VersionCode
$noop.apk = "apks/$apkName"
$noop.name = "NOOP Health Hub"
$noop.icon = "N"
$noop.tagline = "WHOOP 3/4/5/MG health hub with fixed alarms and MG buzz"
$noop.description = "Your fork of NOOP: pairs with WHOOP straps over Bluetooth, keeps WHOOP MG live HR stable, supports WHOOP 3/4/MG strap buzz, and receives Apple Watch pushes over Tailscale without seeded demo data."
$noop.changelog = "Update ${VersionName} (${VersionCode}): one alarm path, phone wake backup, connected WHOOP 3/4/MG strap buzz, post-wake HR follow-up, WHOOP 3 add path, primary/secondary strap visibility, and Strength Trainer muscle map."

# Keep the main NOOP entry first, drop demo listings, leave other apps alone.
$others = @($manifest.apps | Where-Object {
    $_.id -ne "com.noop.whoop" -and $_.id -ne "com.noop.whoop.demo" -and $_.id -ne "com.noop.whoop.demo.debug"
})
$manifest.apps = @($noop) + $others

$text = $manifest | ConvertTo-Json -Depth 10
[System.IO.File]::WriteAllText($manifestPath, $text, $utf8NoBom)
Write-Log "Updated manifest $manifestPath"

if (-not $SkipServerStart) { Start-StoreServer }

$localOk = Test-StoreUrl "http://127.0.0.1:8090/apps.json"
$tailOk = Test-StoreUrl "http://100.96.149.116:8090/apps.json"
$apkOk = Test-StoreUrl "http://127.0.0.1:8090/apks/$apkName"
Write-Log "Published $VersionName ($VersionCode)"
Write-Log "APK: $dst"
Write-Log "Manifest local: $localOk"
Write-Log "Manifest Tailscale: $tailOk"
Write-Log "APK local: $apkOk"
Write-Log "=== publish-main-release done ==="

if (-not $localOk) {
    throw "Publish finished but local store URL is not reachable."
}
