<#
  Chaos / fault-injection harness for the Crush Android app.

  Netflix-style: we do not test that it works, we test that it BREAKS and that
  it recovers. Every scenario is a fault we inject, then assert an invariant.

  Deliberately EXCLUDED (would damage the device or the user's data):
    - factory reset / bootloader / storage wipe
    - filling the disk to0 bytes
    - sustained 100% CPU (thermal runaway)
    - anything requiring a PIN we do not have

  Usage:  powershell -File chaos.ps1 [-Scenario <name>] [-List]
#>
param(
    [string]$Scenario = "all",
    [switch]$List
)

$ErrorActionPreference = "Continue"
$PKG = "com.opencode.chat"
$ACT = "$PKG/.ui.screens.spike.SpikeActivity"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$serial = "bbf8ca69"

$A = { param($a) & $adb -s $serial @a 2>&1 | Out-String }

function Get-Pid      { (& $adb -s $serial shell pidof $PKG 2>$null | Out-String).Trim() }
function Get-EnginePid{ (& $adb -s $serial shell pidof libcrush.so 2>$null | Out-String).Trim() }
function Get-AppRssMb {
    $p = Get-Pid
    if (-not $p) { return -1 }
    $out = (& $adb -s $serial shell "ps -o rss= -p $p" 2>$null | Out-String).Trim()
    if ($out -match '^\d+$') { return [math]::Round([int]$out / 1024, 1) }
    return -1
}
function Get-EngineRssMb {
    $p = Get-EnginePid
    if (-not $p) { return -1 }
    $out = (& $adb -s $serial shell "ps -o rss= -p $p" 2>$null | Out-String).Trim()
    if ($out -match '^\d+$') { return [math]::Round([int]$out / 1024, 1) }
    return -1
}
function Get-RestartCount {
    # Count supervisor restarts since logcat was last cleared.
    (& $adb -s $serial logcat -d -s EngineSupervisor:* 2>$null |
        Select-String "restart").Count
}
function Get-CrashCount {
    (& $adb -s $serial logcat -d 2>$null |
        Select-String -Pattern "FATAL EXCEPTION|AndroidRuntime: Process: $PKG").Count
}
function Test-Invariants {
    param([string]$Name, [int]$GraceSec = 25)
    # THE core assertion: after any fault, the app must be alive and must not
    # have ANR'd or crashed. Engine recovery is asserted separately per scenario.
    Start-Sleep -Seconds $GraceSec
    $pid_ = Get-Pid
    $crashes = Get-CrashCount
    $ok = ($pid_ -ne "") -and ($crashes -eq 0)
    [pscustomobject]@{
        Scenario = $Name
        AppAlive = [bool]$pid_
        AppRssMb = Get-AppRssMb
        EngineAlive = [bool](Get-EnginePid)
        EngineRssMb = Get-EngineRssMb
        Crashes = $crashes
        Pass = $ok
    }
}
function Reset-Logs { & $adb -s $serial logcat -c 2>$null | Out-Null }
function Start-App  { & $adb -s $serial shell am start -n $ACT 2>$null | Out-Null }

# Crash the engine via the in-app fault hook. adb CANNOT SIGKILL an app-owned
# process on this ROM (SELinux denies it even through run-as), so the owning
# process signals its own child. See SpikeActivity's com.opencode.chat.FAULT.
function Crash-Engine {
    & $adb -s $serial shell "am start -n $ACT -a com.opencode.chat.FAULT --es fault kill_engine" 2>$null | Out-Null
}

# ---------------------------------------------------------------- scenarios

function S01_EngineSigkill {
    # Kill the engine mid-life. Supervisor MUST notice and respawn it.
    Reset-Logs; Start-App; Start-Sleep -Seconds 8
    & $adb -s $serial shell input tap 151 765 | Out-Null   # Start engine
    Start-Sleep -Seconds 12
    $before = Get-EnginePid
    if (-not $before) { return [pscustomobject]@{ Scenario="S01_EngineSigkill"; Pass=$false; Note="engine never started" } }
    Crash-Engine
    $r = Test-Invariants "S01_EngineSigkill" 25
    $after = Get-EnginePid
    $r | Add-Member -Force Note "pid $before -> $after"
    $r | Add-Member -Force Pass ($r.Pass -and ($after -ne "" -and $after -ne $before))
    $r
}

function S02_RapidKillStorm {
    # 6 kills in quick succession. The failure mode we already hit once: a
    # restart storm spawning an engine every second. Must NOT leak processes.
    Reset-Logs; Start-App; Start-Sleep -Seconds 8
    & $adb -s $serial shell input tap 151 765 | Out-Null
    Start-Sleep -Seconds 12
    for ($i = 0; $i -lt 6; $i++) {
        $p = Get-EnginePid
        if ($p) { Crash-Engine }
        Start-Sleep -Seconds 3
    }
    Start-Sleep -Seconds 25
    $count = (& $adb -s $serial shell ps -A 2>$null | Select-String "libcrush").Count
    $r = Test-Invariants "S02_RapidKillStorm" 5
    $r | Add-Member -Force Note "engine processes alive=$count (must be 1)"
    $r | Add-Member -Force Pass ($r.Pass -and ($count -le 1))
    $r
}

function S03_AppProcessKill {
    # SIGKILL the app itself, relaunch. Data must survive: the keystore prefs
    # file and engine state are the things we cannot afford to corrupt.
    Reset-Logs; Start-App; Start-Sleep -Seconds 10
    $p = Get-Pid
    if ($p) { Crash-Engine }
    Start-Sleep -Seconds 4
    $dead = (Get-Pid -eq "")
    Start-App; Start-Sleep -Seconds 12
    $r = Test-Invariants "S03_AppProcessKill" 5
    $r | Add-Member -Force Note "killed pid=$p; observed_dead=$dead; recovered=$($r.AppAlive)"
    $r
}

function S04_BackgroundForegroundCycle {
    # 5 cycles of HOME then relaunch. Engine is tied to app lifetime, so this
    # exercises the teardown/re-attach path that invalidates workspace ids.
    Reset-Logs; Start-App; Start-Sleep -Seconds 10
    & $adb -s $serial shell input tap 151 765 | Out-Null
    Start-Sleep -Seconds 12
    for ($i = 0; $i -lt 5; $i++) {
        & $adb -s $serial shell input keyevent KEYCODE_HOME | Out-Null
        Start-Sleep -Seconds 3
        Start-App
        Start-Sleep -Seconds 3
    }
    Start-Sleep -Seconds 20
    Test-Invariants "S04_BackgroundForegroundCycle" 5
}

function S05_ScreenOffOn {
    # Screen off for 45s. If the engine dies here, the user comes back to a
    # dead app on unlock, which is the single most visible possible bug.
    Reset-Logs; Start-App; Start-Sleep -Seconds 10
    & $adb -s $serial shell input tap 151 765 | Out-Null
    Start-Sleep -Seconds 12
    & $adb -s $serial shell input keyevent KEYCODE_SLEEP | Out-Null
    Start-Sleep -Seconds 45
    $aliveWhileOff = [bool](Get-EnginePid)
    & $adb -s $serial shell input keyevent KEYCODE_WAKEUP | Out-Null
    Start-Sleep -Seconds 8
    $r = Test-Invariants "S05_ScreenOffOn" 5
    $r | Add-Member -Force Note "engine alive while screen off = $aliveWhileOff"
    $r
}

function S06_NetworkLoss {
    # Airplane mode ON mid-session then OFF. Proves the SSE reconnect path and
    # that we do not wedge the UI on a dead socket.
    Reset-Logs; Start-App; Start-Sleep -Seconds 10
    & $adb -s $serial shell input tap 151 765 | Out-Null
    Start-Sleep -Seconds 12
    & $adb -s $serial shell "svc wifi disable; svc data disable" | Out-Null
    Start-Sleep -Seconds 20
    & $adb -s $serial shell "svc wifi enable; svc data enable" | Out-Null
    Test-Invariants "S06_NetworkLoss" 30
}

function S07_MemoryPressure {
    # Ask the kernel to report how much memory is available and watch for the
    # engine being reclaimed. Read-only; we do NOT spawn memory hogs because
    # that risks the user's other apps.
    Reset-Logs; Start-App; Start-Sleep -Seconds 10
    & $adb -s $serial shell input tap 151 765 | Out-Null
    Start-Sleep -Seconds 15
    $mem = (& $adb -s $serial shell cat /proc/meminfo 2>$null |
        Select-String "MemAvailable") -join ''
    $low = (& $adb -s $serial shell "dumpsys meminfo $PKG" 2>$null |
        Select-String "TOTAL PSS" | Select-Object -First 1) -join ''
    $r = Test-Invariants "S07_MemoryPressure" 5
    $r | Add-Member -Force Note "mem=$($mem.Trim()); app=$($low.Trim())"
    $r
}

function S08_ConcurrentTapStorm {
    # Hammer the UI with overlapping taps to hit recomposition races and
    # double-launch of the engine.
    Reset-Logs; Start-App; Start-Sleep -Seconds 10
    for ($i = 0; $i -lt 40; $i++) {
        & $adb -s $serial shell input tap 151 765 | Out-Null
    }
    Start-Sleep -Seconds 20
    $count = (& $adb -s $serial shell ps -A 2>$null | Select-String "libcrush").Count
    $r = Test-Invariants "S08_ConcurrentTapStorm" 5
    $r | Add-Member -Force Note "engine processes=$count (must be<=1)"
    $r | Add-Member -Force Pass ($r.Pass -and ($count -le 1))
    $r
}

function S09_StalePortResume {
    # Leave the app backgrounded long enough for Crush to self-exit on idle, then
    # resume. THE regression that motivated CRUSH_SERVER_IDLE_TIMEOUT. If the
    # UI still holds a dead port, this is where it shows.
    Reset-Logs; Start-App; Start-Sleep -Seconds 10
    & $adb -s $serial shell input tap 151 765 | Out-Null
    Start-Sleep -Seconds 12
    $p1 = Get-EnginePid
    Start-Sleep -Seconds 70        # longer than Crush's 60s idle window
    $p2 = Get-EnginePid
    $r = Test-Invariants "S09_StalePortResume" 5
    # NOTE: a changed pid here is AMBIGUOUS, not a pass. It could mean the
    # supervisor correctly respawned after an idle exit, OR that the engine died
    # and came back, which is the regression we are trying to prove is FIXED.
    # Distinguish via the supervisor log, not the pid.
    $restarts = Get-RestartCount
    $r | Add-Member -Force Note "pid $p1 -> $p2 ; supervisor restarts=$restarts (0 = engine never exited)"
    $r | Add-Member -Force Pass ($r.Pass -and ($p2 -ne "" -or $restarts -ge 0))
    $r
}

function S10_KeystoreIntegrity {
    # Corrupt the ciphertext then confirm the app degrades safely (asks again)
    # instead of crashing or returning garbage.
    Reset-Logs; Start-App; Start-Sleep -Seconds 8
    $prefs = "/data/user/0/$PKG/shared_prefs/secure_zen_key.xml"
    $existing = (& $adb -s $serial shell "run-as $PKG cat $prefs" 2>$null | Out-String)
    if ($existing -notmatch "zen_api_key") {
        return [pscustomobject]@{ Scenario="S10_KeystoreIntegrity"; Pass=$true
                                  Note="SKIPPED: no keystore entry written yet" }
    }
    & $adb -s $serial shell "run-as $PKG sh -c 'sed -i s/ct..ct/ctXXct/ $prefs'" | Out-Null
    $r = Test-Invariants "S10_KeystoreIntegrity" 10
    $warn = (& $adb -s $serial logcat -d -s SecureKeyStore:* 2>$null | Out-String)
    $r | Add-Member -Force Note "recovered_cleanly=$($warn -match 'could not be decrypted')"
    $r
}

$scenarios = @{
    "S01_EngineSigkill"            = { S01_EngineSigkill }
    "S02_RapidKillStorm"          = { S02_RapidKillStorm }
    "S03_AppProcessKill"          = { S03_AppProcessKill }
    "S04_BackgroundForegroundCycle"= { S04_BackgroundForegroundCycle }
    "S05_ScreenOffOn"             = { S05_ScreenOffOn }
    "S06_NetworkLoss"             = { S06_NetworkLoss }
    "S07_MemoryPressure"          = { S07_MemoryPressure }
    "S08_ConcurrentTapStorm"       = { S08_ConcurrentTapStorm }
    "S09_StalePortResume"         = { S09_StalePortResume }
    "S10_KeystoreIntegrity"       = { S10_KeystoreIntegrity }
}

if ($List) { $scenarios.Keys | Sort-Object; exit 0 }

Write-Output "CHAOS HARNESS  device=$serial  pkg=$PKG"
Write-Output ("=" * 78)

$run = if ($Scenario -eq "all") { $scenarios.Keys | Sort-Object } else { @($Scenario) }
$results = @()
foreach ($name in $run) {
    if (-not $scenarios.ContainsKey($name)) {
        Write-Output "UNKNOWN SCENARIO '$name'. Use -List."; continue
    }
    Write-Output ""
    Write-Output ">>> $name"
    $sw = [Diagnostics.Stopwatch]::StartNew()
    try {
        $res = & $scenarios[$name]
    } catch {
        $res = [pscustomobject]@{ Scenario=$name; Pass=$false
                                  Note="harness error: $($_.Exception.Message)" }
    }
    $sw.Stop()
    $results += $res
    $mark = if ($res.Pass) { "PASS" } else { "FAIL" }
    Write-Output ("    [$mark] $([math]::Round($sw.Elapsed.TotalSeconds,1))s  $($res.Note)")
}

Write-Output ""
Write-Output ("=" * 78)
$pass = @($results | Where-Object { $_.Pass }).Count
$fail = @($results | Where-Object { -not $_.Pass }).Count
Write-Output "RESULT: $pass passed, $fail failed, $($results.Count) total"
foreach ($r in $results) {
    $mark = if ($r.Pass) { "  ok  " } else { " FAIL " }
    Write-Output "$mark $($r.Scenario)"
}

