# Ingest a WHOOP/NOOP screenshot export from Downloads into pairing-logs/exports/
# and emit a manifest.csv that pairs same-minute WHOOP<->NOOP captures.
#
# Usage:
#   powershell -File Tools\ingest_export.ps1
#   powershell -File Tools\ingest_export.ps1 -ExportDir "C:\Users\Gilbert\Downloads\Noop mg-...\Noop mg"
#   powershell -File Tools\ingest_export.ps1 -ExportDir "...\Noop stresd" -PackKind stress
#
# Filenames are the source of truth: Screenshot_YYYYMMDD_HHMMSS_<APP>.jpg
# (Samsung stamps capture time + foreground app). The manifest adds empty
# `screen` / `values` columns that the decode pass fills in, so the CSV —
# not prose — becomes the durable record for that export.
#
# Screen taxonomy + three-lane compare:
#   docs/WHOOP_NOOP_SCREENSHOT_COMPARE.md  (AI app store root)

param(
    [string]$ExportDir = "",
    [int]$PairWindowMinutes = 10,
    [ValidateSet("auto", "stress", "sleep", "mixed")]
    [string]$PackKind = "auto"
)

$ErrorActionPreference = "Stop"
$downloads = Join-Path $env:USERPROFILE "Downloads"

# Prefer repo pairing-logs/exports (noop checkout). Fall back to AI-store workspace.
$exportsRoot = Join-Path $PSScriptRoot "..\pairing-logs\exports"
$wsExports = "C:\Users\Gilbert\Documents\Ai app store\pairing-logs\exports"
if (-not (Test-Path (Split-Path $exportsRoot)) -and (Test-Path (Split-Path $wsExports))) {
    $exportsRoot = $wsExports
}

$pattern = '^Screenshot_(\d{8})_(\d{6})_(.+)\.(jpg|jpeg|png)$'

function Infer-PackKind([object[]]$rows) {
    $names = ($rows | ForEach-Object { $_.file }) -join " "
    $stressHints = 0; $sleepHints = 0
    # Filename alone rarely encodes screen — default mixed; caller should pass -PackKind
    if ($PackKind -ne "auto") { return $PackKind }
    return "mixed"
}

$requiredByKind = @{
    stress = @(
        @{ Id = "S1"; App = "WHOOP"; Screen = "whoop_home"; Why = "Home Stress Monitor card (tip+band)" },
        @{ Id = "S2"; App = "WHOOP"; Screen = "whoop_stress_monitor"; Why = "Full Stress Monitor chart + high-zone copy" },
        @{ Id = "S3"; App = "NOOP"; Screen = "noop_today_health"; Why = "Today Health/Key Metrics stress row" },
        @{ Id = "S4"; App = "NOOP"; Screen = "noop_stress_hero"; Why = "Stress hero Now tip" },
        @{ Id = "S5"; App = "NOOP"; Screen = "noop_stress_timeline"; Why = "Intraday timeline + time-in-band (do not skip)" }
    )
    sleep = @(
        @{ Id = "L1"; App = "WHOOP"; Screen = "whoop_home"; Why = "Home rings + sleep activity" },
        @{ Id = "L2"; App = "WHOOP"; Screen = "whoop_sleep_detail"; Why = "Hours of sleep + stage bars" },
        @{ Id = "L3"; App = "NOOP"; Screen = "noop_sleep_hero"; Why = "Rest gauge + What shaped Rest" },
        @{ Id = "L4"; App = "NOOP"; Screen = "noop_sleep_stages"; Why = "Stage minutes / honesty strip" },
        @{ Id = "L5"; App = "NOOP"; Screen = "noop_today_health"; Why = "Today Rest vessel same wake-day" }
    )
    mixed = @(
        @{ Id = "M1"; App = "WHOOP"; Screen = "whoop_home"; Why = "Home rings + Stress card" },
        @{ Id = "M2"; App = "WHOOP"; Screen = "whoop_stress_monitor"; Why = "Stress Monitor if comparing stress" },
        @{ Id = "M3"; App = "WHOOP"; Screen = "whoop_sleep_detail"; Why = "Sleep detail if comparing Rest" },
        @{ Id = "M4"; App = "NOOP"; Screen = "noop_today_health"; Why = "Today Health row" },
        @{ Id = "M5"; App = "NOOP"; Screen = "noop_stress_timeline"; Why = "NOOP stress chart (often missing)" },
        @{ Id = "M6"; App = "NOOP"; Screen = "noop_sleep_hero"; Why = "Sleep Rest hero" }
    )
}

if (-not $ExportDir) {
    $candidates = Get-ChildItem $downloads -Directory | ForEach-Object {
        $leafDirs = @($_) + (Get-ChildItem $_.FullName -Directory -ErrorAction SilentlyContinue)
        foreach ($d in $leafDirs) {
            $shots = Get-ChildItem $d.FullName -File -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match $pattern }
            if ($shots) {
                [pscustomobject]@{ Dir = $d.FullName; Newest = ($shots | Sort-Object Name | Select-Object -Last 1).Name }
            }
        }
    }
    if (-not $candidates) { throw "No export folder with Screenshot_*_APP.jpg files found under $downloads" }
    $ExportDir = ($candidates | Sort-Object Newest | Select-Object -Last 1).Dir
}

Write-Host "Ingesting: $ExportDir"

$shots = @(Get-ChildItem $ExportDir -File | Where-Object { $_.Name -match $pattern })
if (-not $shots) { throw "No Screenshot_*_APP files in $ExportDir" }

$rows = foreach ($f in $shots) {
    $null = $f.Name -match $pattern
    $d = $Matches[1]; $t = $Matches[2]; $app = $Matches[3].ToUpper()
    # Normalize package-style suffixes (com.whoop... etc.) to WHOOP/NOOP when obvious
    if ($app -match 'WHOOP') { $app = 'WHOOP' }
    elseif ($app -match 'NOOP|STRAND') { $app = 'NOOP' }
    [pscustomobject]@{
        file     = $f.Name
        app      = $app
        captured = [datetime]::ParseExact("$d$t", "yyyyMMddHHmmss", $null)
        screen   = ""
        values   = ""
        pairFile = ""
        pairGapS = ""
    }
}
$rows = @($rows | Sort-Object captured)
$kind = Infer-PackKind $rows

# pair each capture with the nearest capture from the OTHER app within the window
foreach ($r in $rows) {
    $others = $rows | Where-Object { $_.app -ne $r.app }
    if ($others) {
        $best = $others | Sort-Object { [math]::Abs(($_.captured - $r.captured).TotalSeconds) } | Select-Object -First 1
        $gap = [math]::Abs(($best.captured - $r.captured).TotalSeconds)
        if ($gap -le $PairWindowMinutes * 60) {
            $r.pairFile = $best.file
            $r.pairGapS = [int]$gap
        }
    }
}

$stamp = ($rows[0].captured).ToString("yyyyMMdd") + "-" + (Split-Path $ExportDir -Leaf).Trim() -replace '[^\w\-]', '_'
$dest = Join-Path $exportsRoot $stamp
New-Item -ItemType Directory -Force $dest | Out-Null
foreach ($f in $shots) { Copy-Item $f.FullName (Join-Path $dest $f.Name) -Force }

$manifest = Join-Path $dest "manifest.csv"
$rows | Select-Object file, app, @{n='captured';e={$_.captured.ToString("yyyy-MM-dd HH:mm:ss")}}, screen, values, pairFile, pairGapS |
    Export-Csv $manifest -NoTypeInformation -Encoding UTF8

# REQUIRED_SHOTS.md - checklist vs inventory (screen still empty until decode)
$req = $requiredByKind[$kind]
$reqPath = Join-Path $dest "REQUIRED_SHOTS.md"
$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine("# Required shots - pack kind $kind")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("Playbook: docs/WHOOP_NOOP_SCREENSHOT_COMPARE.md")
[void]$sb.AppendLine("Export: $ExportDir")
[void]$sb.AppendLine("Copied to: $dest")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("| Id | Need | App | Target screen | Status |")
[void]$sb.AppendLine("|----|------|-----|---------------|--------|")
foreach ($item in $req) {
    $haveApp = @($rows | Where-Object { $_.app -eq $item.App }).Count
    if ($haveApp -gt 0) {
        $status = "HAVE $($item.App) files ($haveApp) - assign screen=$($item.Screen) in manifest during decode"
    } else {
        $status = "MISSING any $($item.App) shot"
    }
    [void]$sb.AppendLine("| $($item.Id) | $($item.Why) | $($item.App) | $($item.Screen) | $status |")
}
[void]$sb.AppendLine("")
[void]$sb.AppendLine("After decode: mark each Id DONE only when a row has that screen value filled.")
[void]$sb.AppendLine("Stress packs: S5 (noop_stress_timeline) was missing in Noop-mg 2026-07-14 - always capture it.")
[System.IO.File]::WriteAllText($reqPath, $sb.ToString())

# decode_worksheet.md - one block per file for Read-tool decode passes
$wsPath = Join-Path $dest "decode_worksheet.md"
$wsb = New-Object System.Text.StringBuilder
[void]$wsb.AppendLine("# Decode worksheet")
[void]$wsb.AppendLine("")
[void]$wsb.AppendLine("Fill screen + values here, then copy into manifest.csv.")
[void]$wsb.AppendLine("Taxonomy keys: docs/WHOOP_NOOP_SCREENSHOT_COMPARE.md sections 3-4.")
[void]$wsb.AppendLine("")
foreach ($r in $rows) {
    $pair = if ($r.pairFile) { "$($r.pairFile) (gap $($r.pairGapS)s)" } else { "(unpaired)" }
    [void]$wsb.AppendLine("## $($r.file)")
    [void]$wsb.AppendLine("")
    [void]$wsb.AppendLine("- app: $($r.app) | captured: $($r.captured.ToString('yyyy-MM-dd HH:mm:ss')) | pair: $pair")
    [void]$wsb.AppendLine("- screen:  (whoop_stress_monitor / noop_stress_timeline / ...)")
    [void]$wsb.AppendLine("- values:  (tip=; band=; tipClock=; highZone=; statusBarClock=; ...)")
    [void]$wsb.AppendLine("- chart notes: (night floor / peak hour / activity glyph / missing series)")
    [void]$wsb.AppendLine("")
}
[System.IO.File]::WriteAllText($wsPath, $wsb.ToString())

# labels stub template (append manually after decode - do not auto-write guessing)
$stubPath = Join-Path $dest "labels_stub.jsonl.example"
$stubLines = foreach ($r in @($rows | Where-Object { $_.app -eq 'WHOOP' -and $_.pairFile })) {
    $day = $r.captured.ToString("yyyy-MM-dd")
    $clock = $r.captured.ToString("HH:mm")
    '{"day":"' + $day + '","source":"screenshot","clock":"' + $clock + '","whoop_file":"' + $r.file + '","noop_file":"' + $r.pairFile + '","stress_tip":null,"stress_band":null,"noop_tip":null,"serial":"export:' + $stamp + '"}'
}
if ($stubLines) {
    [System.IO.File]::WriteAllLines($stubPath, @($stubLines))
}

$byApp = $rows | Group-Object app | ForEach-Object { "$($_.Name)=$($_.Count)" }
$span = "{0:HH:mm:ss} - {1:HH:mm:ss}" -f $rows[0].captured, $rows[-1].captured
$paired = @($rows | Where-Object { $_.pairGapS -ne "" }).Count
$tight = @($rows | Where-Object { $_.pairGapS -ne "" -and [int]$_.pairGapS -le 120 }).Count

Write-Host ""
Write-Host "Copied $($rows.Count) shots -> $dest"
Write-Host "PackKind: $kind   Apps: $($byApp -join ', ')   Span: $span"
Write-Host "Paired (<= ${PairWindowMinutes}m): $paired/$($rows.Count)   Tight (<=2m tip@clock): $tight"
Write-Host "Manifest:  $manifest"
Write-Host "Checklist: $reqPath"
Write-Host "Worksheet: $wsPath"
if (Test-Path $stubPath) { Write-Host "Labels:    $stubPath  (fill tips, append to pairing-logs\whoop-app-labels.jsonl)" }
Write-Host ""
Write-Host "Next:"
Write-Host "  1. Read each JPG; fill screen+values in manifest.csv (worksheet helps)."
Write-Host "  2. Tip@clock table + three-lane compare per docs\WHOOP_NOOP_SCREENSHOT_COMPARE.md"
Write-Host "  3. Update factor docs; ANY_MODEL_CONTINUE.md last."
