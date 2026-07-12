param(
    [string]$Serial = "<FOLD_TAILSCALE_IP>:5555",
    [string]$Into = "debug",
    # Release builds are NOT debuggable — run-as fails.
    # Export from release (Data sources → Backup) then pass -ReleaseBak.
    [string]$ReleaseBak = ""
)

<#
.SYNOPSIS
  Combine com.noop.whoop + com.noop.whoop.debug Room data into debug.

.USAGE
  1. On RELEASE app: Data sources → Export backup → .noopbak
  2. Copy file to PC
  3. Tools\merge_noop_flavors.ps1 -ReleaseBak C:\path\to\noop-backup-....noopbak
#>

$ErrorActionPreference = "Stop"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Get-Command python -ErrorAction SilentlyContinue)) { throw "python required" }

$debugPkg = "com.noop.whoop.debug"
$releasePkg = "com.noop.whoop"
$dbName = "noop_whoop.db"
$tmp = Join-Path $env:TEMP "noop_merge_$((Get-Date).ToString('yyyyMMdd_HHmmss'))"
New-Item -ItemType Directory -Path $tmp | Out-Null
$script = Join-Path $PSScriptRoot "merge_noop_sqlite.py"

function Pull-DebugDb([string]$outFile) {
    & $adb -s $Serial exec-out run-as $debugPkg cat "databases/$dbName" > $outFile
    if (-not (Test-Path $outFile) -or (Get-Item $outFile).Length -lt 100) {
        throw "Failed to pull $debugPkg db"
    }
    Write-Host "Pulled debug → $outFile ($((Get-Item $outFile).Length) bytes)"
}

function Expand-Bak([string]$bakPath, [string]$outSqlite) {
    $bytes = [System.IO.File]::ReadAllBytes($bakPath)
    if ($bytes.Length -ge 4 -and $bytes[0] -eq 0x50 -and $bytes[1] -eq 0x4B) {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $zip = [System.IO.Compression.ZipFile]::OpenRead($bakPath)
        try {
            $entry = $zip.Entries | Where-Object {
                $_.Name -like "*.sqlite" -or $_.Name -eq "noop-backup.sqlite"
            } | Select-Object -First 1
            if (-not $entry) { throw "No sqlite entry in $bakPath" }
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $outSqlite, $true)
        } finally { $zip.Dispose() }
    } else {
        Copy-Item $bakPath $outSqlite -Force
    }
    Write-Host "Release snapshot → $outSqlite"
}

$dbg = Join-Path $tmp "debug.db"
$rel = Join-Path $tmp "release.db"
$merged = Join-Path $tmp "merged.db"

Pull-DebugDb $dbg

if ($ReleaseBak -and (Test-Path -LiteralPath $ReleaseBak)) {
    Expand-Bak $ReleaseBak $rel
} else {
    & $adb -s $Serial exec-out run-as $releasePkg cat "databases/$dbName" > $rel 2>$null
    if (-not (Test-Path $rel) -or (Get-Item $rel).Length -lt 100) {
        throw @"
Release app is not pullable via run-as (normal for release builds).

Do this once:
  1. Open com.noop.whoop → Data sources → Export backup (.noopbak)
  2. Copy the file to this PC
  3. Re-run: Tools\merge_noop_flavors.ps1 -ReleaseBak <path-to.noopbak>
"@
    }
}

if ($Into -ne "debug") {
    throw "Only -Into debug is supported (release is not run-as writable)."
}

& python $script $rel $dbg $merged

Write-Host "Stopping $debugPkg ..."
& $adb -s $Serial shell am force-stop $debugPkg
$stage = "/sdcard/Download/noop_merged.db"
& $adb -s $Serial push $merged $stage
& $adb -s $Serial shell "run-as $debugPkg sh -c 'cp $stage databases/$dbName; rm -f databases/$dbName-wal databases/$dbName-shm'"
& $adb -s $Serial shell "rm -f $stage"
Write-Host "Merged into $debugPkg. Relaunch the app."
& $adb -s $Serial shell monkey -p $debugPkg -c android.intent.category.LAUNCHER 1
Write-Host "Done. Temp: $tmp"
