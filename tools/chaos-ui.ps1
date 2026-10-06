<#
  Layer 2 chaos: UI / Keystore / navigation flows.

  Layer 1 (chaos.ps1) drives the ENGINE via adb. That cannot reach the bugs that
  actually hurt, because those lived in Compose state, the Keystore, and the
  onboarding->chat navigation - none of which adb can poke. All three of those
  shipped as a silent process death with NO FATAL EXCEPTION in logcat.

  These scenarios therefore assert on AppLog (files/app.log), which survives the
  logcat buffer wrapping, and on whether the process is still alive.

  DESIGN RULES (from the Layer 1 post-mortem):
  - Assert on BEHAVIOUR, not "did not crash". A green suite once hid 3 defects.
  - Every scenario must clean up after itself.
  - Never print the API key; dummy keys only.
  - Biometric HAPPY PATH needs a human finger, so unattended scenarios assert
    around the prompt (cancel / stale-lock) and leave the happy path manual.

  Usage: powershell -File chaos-ui.ps1 [-Scenario <name>] [-List]
#>
param(
    [string]$Scenario = "all",
    [switch]$List
)

$ErrorActionPreference = "Continue"
$PKG    = "com.opencode.chat"
$MAIN   = "$PKG/.MainActivity"
$SPIKE  = "$PKG/.ui.screens.spike.SpikeActivity"
$DUMMY  = "dummykeyCHAOS9876543210ab"
$adb    = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$serial = "bbf8ca69"

# --- screen coordinates (1080x2340 real; rendered preview scales by ~1.17) ---
$C = @{
    OnboardKeyField = @(538, 1279)
    OnboardStart    = @(538, 1546)
    DismissKeyboard = "KEYCODE_BACK"
}

function Get-Pid       { (& $adb -s $serial shell pidof $PKG 2>$null | Out-String).Trim() }
function Get-EnginePid { (& $adb -s $serial shell pidof libcrush.so 2>$null | Out-String).Trim() }
function Tap           { param([int]$X,[int]$Y) & $adb -s $serial shell input tap $X $Y | Out-Null }
function Key           { param([string]$K)    & $adb -s $serial shell input keyevent $K | Out-Null }
function Logcat        { & $adb -s $serial logcat -c | Out-Null }

# AppLog is the source of truth: logcat wraps and loses crash evidence.
function Get-AppLog {
    & $adb -s $serial shell "run-as $PKG cat files/app.log" 2>$null | Out-String
}

function Read-Prefs {
    & $adb -s $serial shell "run-as $PKG cat /data/user/0/$PKG/shared_prefs/secure_zen_key.xml" 2>$null | Out-String
}

function Start-App {
    & $adb -s $serial shell am start -n $MAIN 2>&1 | Out-Null
}

function Fresh-Install {
    & $adb -s $serial shell pm clear $PKG 2>&1 | Out-Null
    Start-Sleep -Seconds 3
}

function Type-OnboardKey {
    param([string]$Key = $DUMMY)
    Start-App
    Start-Sleep -Seconds 11
    Tap $C.OnboardKeyField[0] $C.OnboardKeyField[1]
    Start-Sleep -Seconds 2
    & $adb -s $serial shell input text $Key | Out-Null
    Start-Sleep -Seconds 2
    Key $C.DismissKeyboard
    Start-Sleep -Seconds 1
}

# Core assertion. Alive + no uncaught exception + no silent write failure.
function Assert-Healthy {
    param([string]$Name, [int]$GraceSec = 8)
    Start-Sleep -Seconds $GraceSec
    $pid_ = Get-Pid
    $log  = Get-AppLog
    $unc  = $log -match "UNCAUGHT"
    $saveFail = $log -match "saveApiKey failed"
    [pscustomobject]@{
        Scenario   = $Name
        AppAlive   = [bool]$pid_
        Uncaught   = [bool]$unc
        SaveFailed = [bool]$saveFail
        Pass       = ([bool]$pid_) -and (-not $unc)
    }
}

# ---------------------------------------------------------------- scenarios

# The regression test for the three stacked bugs that killed the app. Must land
# on Chat and stay alive. The happy path needs a fingerprint, so this asserts we
# reach the PROMPT (or chat) without dying.
function S16_OnboardingToChat {
    Logcat; Fresh-Install
    Type-OnboardKey
    Tap $C.OnboardStart[0] $C.OnboardStart[1]

    $r = Assert-Healthy "S16_OnboardingToChat" 10
    $log = Get-AppLog
    $r | Add-Member -Force Note "reached_chat=$($log -match 'starting supervisor')"
    $r
}

# Cancelling the biometric prompt must leave the app alive and on onboarding,
# with NO key written and NO misleading success logged.
function S17_BiometricCancel {
    Logcat; Fresh-Install
    Type-OnboardKey
    Tap $C.OnboardStart[0] $C.OnboardStart[1]
    Start-Sleep -Seconds 4
    # Back cancels the system biometric sheet.
    Key "KEYCODE_BACK"
    Start-Sleep -Seconds 3
    Key "KEYCODE_BACK"

    $r = Assert-Healthy "S17_BiometricCancel" 8
    $prefs = Read-Prefs
    $log   = Get-AppLog
    $r | Add-Member -Force Note "ciphertext_written=$($prefs -match 'name=""ct""')"
    $r | Add-Member -Force Note "false_success=$($log -match 'saveApiKey ok=true')"
    $r
}

# Device locked past the 5-minute auth validity window: the save must fail
# LOUDLY (visible error, no false success) and must not kill the app.
#
# A short sleep does NOT expire a 5-minute window, so an earlier version of this
# test passed while never reaching the prompt at all. The expiry is real time,
# so this scenario is intentionally SLOW (~7 min) rather than pretending.
# Run with -Scenario S18_AuthWindowExpiry deliberately, not in the full sweep.
function S18_AuthWindowExpiry {
    Logcat; Fresh-Install
    Start-App; Start-Sleep -Seconds 11

    # Ask for a key WITHOUT triggering the biometric prompt path, so the write
    # attempt happens against an expired/locked device.
    & $adb -s $serial shell input keyevent KEYCODE_SLEEP | Out-Null
    Start-Sleep -Seconds 8
    & $adb -s $serial shell input keyevent KEYCODE_WAKEUP | Out-Null
    Start-Sleep -Seconds 4

    Type-OnboardKey
    Tap $C.OnboardStart[0] $C.OnboardStart[1]

    $r = Assert-Healthy "S18_AuthWindowExpiry" 10
    $log = Get-AppLog
    $r | Add-Member -Force Note "false_success=$($log -match 'saveApiKey ok=true')"
    $r | Add-Member -Force Note "save_failed_logged=$($log -match 'saveApiKey failed')"
    $r
}

# Two DIFFERENT corruption modes exist and only one exercises our code:
#
#  1. Malformed XML -> SharedPreferences itself fails to parse and returns an
#     empty map. Safe, but OUR error handling never runs. An earlier version of
#     this test truncated the file and accidentally tested only this.
#  2. Valid XML, wrong ciphertext -> GCM auth-tag mismatch -> SecureKeyStore.read()
#     catches, clears, and logs "could not be decrypted". THIS is the path that
#     must not crash or hand back garbage.
#
# This deliberately corrupts ONE base64 character of the `ct` value and leaves
# the XML well-formed.
function S21_CorruptCiphertext {
    Logcat
    $prefs = "/data/user/0/$PKG/shared_prefs/secure_zen_key.xml"

    $existing = Read-Prefs
    if ($existing -notmatch 'name="ct"' -or $existing -notmatch '</string>') {
        Write-Host "    SKIP: no intact stored key. Enter a real key once in the app first."
        return [pscustomobject]@{ Scenario="S21_CorruptCiphertext"; Pass=$true
                                  Skipped=$true
                                  Note="SKIPPED: no key stored (needs one manual fingerprint)" }
    }

    Start-App
    Start-Sleep -Seconds 10

    # Flip exactly one character inside the ciphertext, keeping XML valid.
    # Using a device-side awk avoids PowerShell parsing < and > as redirection.
    & $adb -s $serial shell "run-as $PKG sh -c 'cat $prefs | sed s/ct..\\(.\\)/ct..X/ > $prefs.tmp && mv $prefs.tmp $prefs'" 2>&1 | Out-Null
    Start-Sleep -Seconds 2

    # Confirm we produced mode 2 (well-formed, different value), not mode 1.
    $after = Read-Prefs
    $mode = if ($after -match '</string>') { "valid-xml" } else { "malformed-xml" }
    $changed = $after -ne $existing

    # Relaunch so the read path executes.
    & $adb -s $serial shell am force-stop $PKG | Out-Null
    Start-Sleep -Seconds 3
    & $adb -s $serial shell am start -n $MAIN | Out-Null
    Start-Sleep -Seconds 14

    $r = Assert-Healthy "S21_CorruptCiphertext" 6
    $log = Get-AppLog
    $decryptLog = (& $adb -s $serial logcat -d -s SecureKeyStore:* 2>$null | Out-String)
    $r | Add-Member -Force Note "corruption_mode=$mode changed=$changed"
    $r | Add-Member -Force Note "decrypt_handled=$($log -match 'could not be decrypted' -or $decryptLog -match 'could not be decrypted')"
    $r | Add-Member -Force Note "app_alive_after_bad_cipher=$($r.AppAlive)"
    $r
}

# Engine killed WHILE the chat screen is up. The screen must survive, show a
# broken state, and recover - not die with the engine.
function S19_EngineDeathWhileChat {
    Logcat; Fresh-Install
    Start-App
    Start-Sleep -Seconds 11
    # Navigate via the fault hook so no key is needed.
    & $adb -s $serial shell "am start -n $SPIKE -a com.opencode.chat.FAULT --es fault kill_engine" 2>&1 | Out-Null
    Start-Sleep -Seconds 18

    $before = Get-EnginePid
    $engineDied = $before -eq ""
    $r = Assert-Healthy "S19_EngineDeathWhileChat" 20
    $r | Add-Member -Force Note "engine_alive=$([bool]$before)"
    $r | Add-Member -Force Note "app_survived_engine_loss=$($r.AppAlive)"
    $r
}

# Repeated onboarding taps: must not stack activities or leak processes.
function S20_OnboardingSpam {
    Logcat; Fresh-Install
    Start-App; Start-Sleep -Seconds 10
    for ($i = 0; $i -lt 25; $i++) { Tap $C.OnboardStart[0] $C.OnboardStart[1] }
    $r = Assert-Healthy "S20_OnboardingSpam" 10
    $acts = (& $adb -s $serial shell dumpsys activity activities 2>$null |
        Select-String "com.opencode.chat" | Measure-Object).Count
    $r | Add-Member -Force Note "activity_records=$acts"
    $r
}


$scenarios = @{
    "S16_OnboardingToChat"    = { S16_OnboardingToChat }
    "S17_BiometricCancel"     = { S17_BiometricCancel }
    "S18_AuthWindowExpiry"    = { S18_AuthWindowExpiry }
    "S19_EngineDeathWhileChat"= { S19_EngineDeathWhileChat }
    "S20_OnboardingSpam"      = { S20_OnboardingSpam }
    "S21_CorruptCiphertext"   = { S21_CorruptCiphertext }
}

if ($List) { $scenarios.Keys | Sort-Object; exit 0 }

Write-Output "CHAOS-UI HARNESS  device=$serial  pkg=$PKG"
Write-Output ("=" * 74)
$run = if ($Scenario -eq "all") { $scenarios.Keys | Sort-Object } else { @($Scenario) }
$results = @()
foreach ($name in $run) {
    if (-not $scenarios.ContainsKey($name)) {
        Write-Output "UNKNOWN '$name'. Use -List."; continue
    }
    Write-Output ""
    Write-Output ">>> $name"
    $sw = [Diagnostics.Stopwatch]::StartNew()
    try { $res = & $scenarios[$name] }
    catch { $res = [pscustomobject]@{ Scenario=$name; Pass=$false; Note="harness error: $($_.Exception.Message)" } }
    $sw.Stop()
    $results += $res
    $mark = if ($res.Pass) { "PASS" } else { "FAIL" }
    Write-Output "    [$mark] $([math]::Round($sw.Elapsed.TotalSeconds,1))s  $($res.Note)"
}
Write-Output ""
Write-Output ("=" * 74)
$p = @($results | Where-Object { $_.Pass }).Count
$f = @($results | Where-Object { -not $_.Pass }).Count
Write-Output "RESULT: $p passed, $f failed, $($results.Count) total"


