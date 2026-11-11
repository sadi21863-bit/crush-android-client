# Engine device rails - the read-only half of acceptance-kit tier T3.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools/engine-rails.ps1 -ExpectedVersion 0.99.0
#
# Every PASS/FAIL below is decided from the app's OWN evidence: files/app.log
# (per-process, recreated at launch) + the on-disk instance config. Anything
# this runner cannot truly decide is printed as OBSERVE, never guessed to a
# verdict. The interactive rails (owner finger taps: F04 Allow, sheet
# Confirm/Dispatch/Discard, lock credential) and the DB pulls stay in the
# runbook (docs/engine-acceptance-kit.md section 4); this script covers what
# is decidable without a human.
param(
    [Parameter(Mandatory = $true)][string]$ExpectedVersion,
    [string]$Device = "bbf8ca69",
    [string]$Package = "com.crush.mobile",
    # The deny-list minimum for the version under test (v0.99.0 = 31,
    # v0.100.0 = 33). The exact id set is owned by the JVM ratchet; this is a
    # belt so a truncated config on disk cannot pass unseen.
    [int]$MinDenied = 31
)

$ErrorActionPreference = "Continue"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

$script:passCount = 0
$script:failCount = 0

function Section([string]$title) {
    Write-Output ""
    Write-Output "== $title"
}

function Rail([string]$name, [bool]$ok, [string]$evidence) {
    if ($ok) { $script:passCount++ } else { $script:failCount++ }
    $verdict = if ($ok) { "PASS" } else { "FAIL" }
    Write-Output ("{0}  {1}" -f $verdict, $name)
    Write-Output ("      {0}" -f $evidence)
}

function Observe([string]$name, [string]$evidence) {
    Write-Output ("OBSERVE  {0}" -f $name)
    Write-Output ("         {0}" -f $evidence)
}

function Sh([string]$cmd) {
    (& $adb -s $Device shell $cmd 2>&1) -join "`n"
}

function AppCat([string]$path) {
    (& $adb -s $Device exec-out run-as $Package cat $path 2>&1) -join "`n"
}

# --- 0. device + log presence -------------------------------------------------
$devices = (& $adb devices 2>&1) -join "`n"
if ($devices -notmatch [regex]::Escape($Device)) {
    Write-Output "DEVICE $Device NOT ATTACHED"; exit 2
}
$log = AppCat "files/app.log"
if ([string]::IsNullOrWhiteSpace($log)) {
    Write-Output "files/app.log MISSING OR EMPTY - launch the app first"; exit 2
}
$appStarted = ([regex]::Matches($log, "I/App: app started")).Count

Section "0. app log"
Rail "files/app.log readable" $true ("app started x{0} (a new launch recreates this file)" -f $appStarted)

# --- 1. engine version (the artifact's own claim) ------------------------------
Section "1. engine version"
$versions = [regex]::Matches($log, 'version=\{"version":"v([0-9][^"]*)"') |
    ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique
$hasExpected = $versions -contains $ExpectedVersion
Rail "bound engine reports v$ExpectedVersion" $hasExpected $(
    if ($versions.Count -eq 0) { "no bindEngine version line seen" }
    else { "versions seen: " + ($versions -join ", ") }
)

# --- 2. bootstrap health ------------------------------------------------------
Section "2. bootstrap"
$bootAttempts = ([regex]::Matches($log, "bootstrap instance=")).Count
$bootFailures = ([regex]::Matches($log, "bootstrap failed")).Count
$lockRefusals = ([regex]::Matches($log, "lock engaged; refusing to bootstrap")).Count
Rail "no failed bootstrap in this process" ($bootFailures -eq 0) (
    "attempts={0} failures={1} locked-refusals={2} (locked refusals are the DESIGNED fail-closed boot)" -f $bootAttempts, $bootFailures, $lockRefusals
)

# --- 3. deny-list readback on disk (chat pool config) -------------------------
# The engine's config search chain includes BOTH files/crush-chat/cfg/crush.json
# and files/crush-chat/data/crush.json; on a healthy instance the INJECTED
# config lives at data/crush.json (measured 2026-11-12: cfg/ is empty, data/
# carries options.disabled_tools). Try data/ first, fall back to cfg/.
Section "3. chat-pool deny readback"
$cfgPath = "files/crush-chat/data/crush.json"
$cfg = AppCat $cfgPath
if ([string]::IsNullOrWhiteSpace($cfg) -or -not $cfg.TrimStart().StartsWith("{")) {
    $cfgPath = "files/crush-chat/cfg/crush.json"
    $cfg = AppCat $cfgPath
}
$cfgOk = $cfg -and $cfg.TrimStart().StartsWith("{")
$denied = @()
if ($cfgOk) {
    try {
        $obj = $cfg | ConvertFrom-Json
        $list = $obj.options.disabled_tools
        if ($null -ne $list) { $denied = @($list) }
    } catch {
        $cfgOk = $false
    }
}
Rail "chat-pool config parses" $cfgOk $(
    if (-not $cfgOk) { "neither data/crush.json nor cfg/crush.json is readable JSON" }
    else { "$cfgPath -> options.disabled_tools count=" + $denied.Count }
)
Rail "deny list meets the version floor ($MinDenied ids)" ($denied.Count -ge $MinDenied) $(
    "on-disk ids: " + $(if ($denied.Count -eq 0) { "(none)" } else { ($denied -join ", ") })
)

# --- 4. tool wall observation (chat vs code attribution is human) -------------
Section "4. tool wall (observation)"
$toolCalls = [regex]::Matches($log, "observed tool_call shape")
$names = [regex]::Matches($log, "observed tool_call shape.*?name=([a-z_]+)") |
    ForEach-Object { $_.Groups[1].Value } | Group-Object | ForEach-Object { "$($_.Name)x$($_.Count)" }
Observe "tool_call events in this process" (
    "count={0}{1}" -f $toolCalls.Count, $(if ($names) { " -> " + ($names -join ", ") } else { "" })
)
Observe "zero-tool wall (rail 3)" (
    "assert ZERO events for a CHAT-pool probe turn; count above includes any CODE-session turns"
)

# --- 5. crash + fatal scan ----------------------------------------------------
Section "5. stability"
$fatals = ([regex]::Matches($log, "FATAL EXCEPTION")).Count
$recreations = ([regex]::Matches($log, "app started")).Count
Rail "no FATAL EXCEPTION in the log" ($fatals -eq 0) (
    "fatal={0}; the log holds {1} launch(es) - >1 means the process restarted mid-run" -f $fatals, $recreations
)

# --- 6. the interactive rails reminder ---------------------------------------
Section "6. interactive rails (owner + DB, see the runbook)"
Observe "F04 consent rail" "root/sheet dialog -> Allow; consent stamp + fp == SHA-256 of the folder's crushrc"
Observe "handoff rails" "same-destination dispatch (no insert) + cold-boot resume (Batch G target session)"
Observe "DB assertions" "pull projects.db + -wal and diff statuses/stamps (p5/pE/PG pull scripts)"

Write-Output ""
Write-Output ("SUMMARY: {0} PASS, {1} FAIL" -f $script:passCount, $script:failCount)
if ($script:failCount -gt 0) { exit 1 } else { exit 0 }
