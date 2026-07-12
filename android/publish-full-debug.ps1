param(
    [switch]$RefreshDependencies
)

$ErrorActionPreference = "Stop"

$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$workspaceDir = (Resolve-Path (Join-Path $projectDir "..\..")).Path
$storeDir = "<AI_APPSTORE>"
$gradle = "<USER_GRADLE>\wrapper\dists\gradle-8.7-bin\bhs2wmbdwecv87pi65oeuq5iu\gradle-8.7\bin\gradle.bat"

if (-not (Test-Path "N:\android-sdk-local")) {
    subst N: $workspaceDir | Out-Null
}

$env:JAVA_HOME = "<USER_JDKS>\jbr-17.0.14"
$env:ANDROID_HOME = "N:\android-sdk-local"
$env:ANDROID_SDK_ROOT = "N:\android-sdk-local"

Push-Location "N:\noop-v8.4.0-src\android"
try {
    # The mapped N: build root can retain Kotlin/KSP incremental state from an older checkout.
    # Clean first so a debug publish is reproducible instead of failing on stale source paths.
    $gradleArgs = @(":app:clean", ":app:assembleFullDebug", "--no-daemon")
    if ($RefreshDependencies) { $gradleArgs += "--refresh-dependencies" }
    & $gradle @gradleArgs
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
finally {
    Pop-Location
}

$metadataPath = Join-Path $projectDir "app\build\outputs\apk\full\debug\output-metadata.json"
$apkPath = Join-Path $projectDir "app\build\outputs\apk\full\debug\app-full-debug.apk"
$metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
$element = $metadata.elements[0]
$versionName = [string]$element.versionName
$versionCode = [int]$element.versionCode
$storeApkName = "NOOP-v$versionName-full-debug.apk"
$storeApkRel = "apks/$storeApkName"
$storeApkPath = Join-Path $storeDir $storeApkRel

Copy-Item -LiteralPath $apkPath -Destination $storeApkPath -Force

$appsPath = Join-Path $storeDir "apps.json"
$json = Get-Content -LiteralPath $appsPath -Raw | ConvertFrom-Json
$json.apps = @($json.apps | Where-Object { $_.id -ne "com.noop.whoop.demo.debug" })
$app = $json.apps | Where-Object id -eq "com.noop.whoop.debug"
if (-not $app) {
    $app = [pscustomobject]@{
        id = "com.noop.whoop.debug"
        name = ""
        icon = "N"
        tagline = ""
        description = ""
        versionName = ""
        versionCode = 0
        apk = ""
        changelog = ""
    }
    $json.apps += $app
}

$app.name = "NOOP $versionName Full Debug"
$app.icon = "N"
$app.tagline = "WHOOP MG pairing test build"
$app.description = "Full NOOP debug build for real WHOOP MG pairing and buzz testing. Non-demo build; no seeded Apple Watch or WHOOP sample data."
$app.versionName = $versionName
$app.versionCode = $versionCode
$app.apk = $storeApkRel
$app.changelog = "314: Rebuilt sleep planning and alarms with an honest HR-based early cue plus guaranteed phone fallback; compact scrolling scores, local profile/device names, lower redraw cost, and debug-only user-confirmed sport-label collection. WHOOP 5/MG retrieval remains opt-in and evidence-led."

$text = $json | ConvertTo-Json -Depth 12
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($appsPath, $text, $utf8NoBom)

Write-Host "Published $versionName ($versionCode)"
Write-Host "APK: $storeApkPath"
Write-Host "Store: http://100.96.149.116:8090/apps.json"

