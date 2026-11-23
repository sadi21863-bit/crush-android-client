# Perf baseline capture (V2-0 sign-off carry-over: "V2-4 performance proof").
#
# WHAT THIS IS
#   A repeatable, honest capture of four numbers that can be compared across
#   builds on the same phone:
#     1. cold-start TotalTime / WaitTime  (`am start -W`)
#     2. frame stats since process start (`dumpsys gfxinfo`)
#     3. total PSS                        (`dumpsys meminfo`)
#     4. engine boot time                 (AppLog: "<key> engine Running after Nms")
#
# WHAT THIS IS NOT
#   - No frame BUDGET is asserted. A budget needs a target device class and a
#     number the owner accepts; this script only measures.
#   - No UI automation: the lock gate and the prewarm setting are whatever the
#     device currently has, and the script records that rather than pretending
#     it drove the app.
#   - `gfxinfo` frame stats accumulate from process start, so they describe the
#     launch + idle window, not a scripted scroll.
#
# Usage:
#   powershell -File tools/perf-baseline.ps1
#   powershell -File tools/perf-baseline.ps1 -Serial bbf8ca69 -Runs 3
#   powershell -File tools/perf-baseline.ps1 -Label after-v2-9
#
# Output: docs/parity/<date>-v2-baseline-perf/ (raw files + RESULTS.md).
# No credential value is read or written. `dumpsys` output can contain paths
# and pids only.

param(
    [string]$Serial = "bbf8ca69",
    [int]$Runs = 3,
    [string]$Label = "",
    [string]$OutDir = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }

$pkg = "com.crush.mobile"
$activity = "$pkg/.MainActivity"

if (-not $OutDir) {
    $stamp = Get-Date -Format "yyyy-MM-dd"
    $OutDir = "docs/parity/$stamp-v2-baseline-perf"
    # A second capture on the same day must not overwrite the first: the
    # numbers are only useful as a series, and a silent overwrite destroys the
    # baseline it is compared against. Explicit -OutDir keeps its old meaning.
    if (Test-Path (Join-Path $OutDir "starts.csv")) {
        $OutDir = "docs/parity/$stamp-" + (Get-Date -Format "HHmmss") + "-v2-baseline-perf"
    }
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Invoke-Adb {
    param([string[]]$AdbArgs)
    $out = & $adb -s $Serial @AdbArgs 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "adb $($AdbArgs -join ' ') failed: $out" -ForegroundColor Yellow
    }
    return ($out -join "`n")
}

function Write-Capture {
    param([string]$Name, [string]$Text)
    $path = Join-Path $OutDir $Name
    # LF-only, UTF-8 without BOM: diffable and safe for the repo.
    [System.IO.File]::WriteAllText((Resolve-Path -LiteralPath $OutDir).Path + "\" + $Name, $Text + "`n")
    return $path
}

Write-Host "== device ==" -ForegroundColor Cyan
$device = @(
    "serial        : $Serial"
    "captured      : $((Get-Date).ToString('o'))"
    "label         : $Label"
    "android       : $((Invoke-Adb -AdbArgs @('shell','getprop','ro.build.version.release')).Trim())"
    "sdk           : $((Invoke-Adb -AdbArgs @('shell','getprop','ro.build.version.sdk')).Trim())"
    "model         : $((Invoke-Adb -AdbArgs @('shell','getprop','ro.product.model')).Trim())"
    "abi           : $((Invoke-Adb -AdbArgs @('shell','getprop','ro.product.cpu.abi')).Trim())"
    "build         : $((Invoke-Adb -AdbArgs @('shell','getprop','ro.build.display.id')).Trim())"
) -join "`n"
Write-Capture -Name "device.txt" -Text $device | Out-Null

$versionLine = Invoke-Adb -AdbArgs @('shell','dumpsys','package',$pkg)
$versionLine = ($versionLine -split "`n" | Where-Object { $_ -match 'versionName|versionCode' }) -join "`n"
Write-Capture -Name "package.txt" -Text $versionLine | Out-Null

Write-Host "== cold start x$Runs ==" -ForegroundColor Cyan
$rows = New-Object System.Collections.Generic.List[object]

for ($i = 1; $i -le $Runs; $i++) {
    # A locked device can leave the system credential screen on top; `am start`
    # then delivers the intent to that screen and reports TotalTime 0 instead
    # of launching the app. Back dismisses it (or the app's lock overlay).
    Invoke-Adb -AdbArgs @('shell','input','keyevent','4') | Out-Null
    Invoke-Adb -AdbArgs @('shell','am','force-stop',$pkg) | Out-Null
    Start-Sleep -Seconds 3
    Invoke-Adb -AdbArgs @('logcat','-c') | Out-Null

    $raw = Invoke-Adb -AdbArgs @('shell','am','start','-W','-n',$activity)
    Write-Capture -Name ("start-$i.txt") -Text $raw | Out-Null

    $total = [regex]::Match($raw, 'TotalTime:\s*(\d+)').Groups[1].Value
    $wait = [regex]::Match($raw, 'WaitTime:\s*(\d+)').Groups[1].Value
    $status = [regex]::Match($raw, 'Status:\s*(\S+)').Groups[1].Value
    $rows.Add([pscustomobject]@{ Run = $i; Status = $status; TotalTimeMs = $total; WaitTimeMs = $wait })

    # Let the first frame settle and the engine attempt its boot.
    Start-Sleep -Seconds 6

    $engine = Invoke-Adb -AdbArgs @('logcat','-d','-s','EngineSupervisor')
    $engineBoot = @($engine -split "`n" | Where-Object { $_ -match 'engine Running after \d+ms' })
    if ($engineBoot.Count -gt 0) {
        # Record EVERY boot line: a launch can boot the legacy and a project
        # instance, and taking only the first made run 2's series inconsistent
        # (legacy 2391 ms vs project 2477 ms in the 2026-11-23 capture).
        $bootMs = ($engineBoot | ForEach-Object {
            [regex]::Match($_, 'engine Running after (\d+)ms').Groups[1].Value
        }) -join ';'
        $rows[$rows.Count - 1] | Add-Member -NotePropertyName EngineBootMs -NotePropertyValue $bootMs
    } else {
        $rows[$rows.Count - 1] | Add-Member -NotePropertyName EngineBootMs -NotePropertyValue "not observed"
    }
    Write-Capture -Name ("engine-boot-$i.txt") -Text ($engineBoot -join "`n") | Out-Null
}

Write-Host "== steady state ==" -ForegroundColor Cyan
Start-Sleep -Seconds 2
$gfx = Invoke-Adb -AdbArgs @('shell','dumpsys','gfxinfo',$pkg)
Write-Capture -Name "gfxinfo.txt" -Text $gfx | Out-Null

$mem = Invoke-Adb -AdbArgs @('shell','dumpsys','meminfo',$pkg)
Write-Capture -Name "meminfo.txt" -Text $mem | Out-Null

# API 29 prints the app-summary total as "TOTAL: <n>" while newer builds use
# "TOTAL PSS: <n>"; accept both rather than reporting an empty cell.
$pss = [regex]::Match($mem, 'TOTAL(?:\s+PSS)?:?\s+(\d+)').Groups[1].Value

$frames = [regex]::Match($gfx, 'Total frames rendered:\s*(\d+)').Groups[1].Value
$janky = [regex]::Match($gfx, 'Janky frames:\s*(\d+)').Groups[1].Value
$p50 = [regex]::Match($gfx, '50th percentile:\s*(\d+)ms').Groups[1].Value
$p90 = [regex]::Match($gfx, '90th percentile:\s*(\d+)ms').Groups[1].Value
$p95 = [regex]::Match($gfx, '95th percentile:\s*(\d+)ms').Groups[1].Value
$p99 = [regex]::Match($gfx, '99th percentile:\s*(\d+)ms').Groups[1].Value

$summary = $rows | ForEach-Object {
    "$($_.Run),$($_.Status),$($_.TotalTimeMs),$($_.WaitTimeMs),$($_.EngineBootMs)"
}
$summaryText = "run,status,totalTimeMs,waitTimeMs,engineBootMs`n" + ($summary -join "`n")
Write-Capture -Name "starts.csv" -Text $summaryText | Out-Null

$result = @(
    "# Perf baseline capture ($(Get-Date -Format 'yyyy-MM-dd HH:mm'))"
    ""
    "Device: $($device -replace "`n", '; ')"
    ""
    "## Cold start (force-stop before every run)"
    ""
    "| run | status | TotalTime ms | WaitTime ms | engine boot ms |"
    "|---|---|---|---|---|"
) + ($rows | ForEach-Object { "| $($_.Run) | $($_.Status) | $($_.TotalTimeMs) | $($_.WaitTimeMs) | $($_.EngineBootMs) |" }) + @(
    ""
    "## Frames since process start (dumpsys gfxinfo)"
    ""
    "- total frames rendered: $frames"
    "- janky frames: $janky"
    "- 50th / 90th / 95th / 99th percentile: $p50 / $p90 / $p95 / $p99 ms"
    ""
    "## Memory"
    ""
    "- TOTAL PSS: $pss kB"
    ""
    "## What this does NOT claim"
    ""
    "- No frame budget or regression threshold: this is a baseline capture, not a"
    "  performance proof. Compare against another capture from the SAME device,"
    "  same settings, same app version class."
    "- The lock gate and prewarm setting were not driven by this script; they are"
    "  whatever the device had at capture time."
    "- gfxinfo accumulates from process start, so it covers launch + idle, not a"
    "  scripted scroll."
    ""
    "Raw evidence: ``device.txt``, ``package.txt``, ``start-1..$Runs.txt``,"
    "``engine-boot-1..$Runs.txt``, ``gfxinfo.txt``, ``meminfo.txt``, ``starts.csv``."
)
Write-Capture -Name "RESULTS.md" -Text ($result -join "`n") | Out-Null

Write-Host ""
Write-Host "Wrote $OutDir" -ForegroundColor Green
Get-Content (Join-Path $OutDir "RESULTS.md")
