# V2-16 P0-E2 — Background-behaviour measurement

Date: **2026-11-26**. Device: Redmi Note 7 Pro, **API 29** (Android 10),
arm64-v8a, serial `bbf8ca69`. App: debug `com.crush.mobile` from the V2-16
planning tree. Engine: `v0.100.0` (`build_id 2my2izixtou8`). Branch
`v2-16-planning-baseline`, head `9192bc5` (no commit).

Scope: **API 29 baseline only.** This does not stand in for API 30+.

Raw evidence:

- `docs/parity/v2-16-phase0/v2-16-p0-app-1039-1052.log` (app log, 10:39–10:52)
- `docs/parity/v2-16-phase0/v2-16-p0-app-checkpoint2.log` (app log, later)
- `docs/parity/v2-16-phase0/v2-16-p0-logcat-kills.txt` (logcat kill reasons)
- `docs/parity/v2-16-phase0/v2-16-p0-session-822d13c1.json` (session history, 62 msgs)
- screenshots `v2-16-p0-b2-done.png`, `v2-16-p0-b4-interrupted-card.png`,
  `v2-16-p0-b7-recents.png`, `v2-16-p0-b7-cleaner.png`, `v2-16-p0-b4-gate.png`

## 1. Method

Every case used one fresh turn on the same bound project engine
(`p<uuid8>` Test project folder 2, socket `files/crush-pd871eee8.sock`) and a
bash tool with a fixed sleep. The recorded fields are the five the plan
requires; `n/a` is used only when the mechanism cannot produce the condition
(for example, no prompt was outstanding in these runs).

Permission mode: **AUTO_APPROVE for the experiment only**, enabled in Settings
at 10:36 (the plan allows it) and **restored to ASK at 11:43**. The setting
takes effect on the next session attach; the app log confirms
`permissionMode=AUTO_APPROVE` + `permissionWire=yolo` during the runs and the
final UI capture shows `ASK EACH TIME`.

Prompt used per case (only the reply token changes):

> `Use the bash tool to run: sleep <N>. Then reply <TOKEN>. Run no other tool.`

Observations came from four independent sources, not from the UI alone:

1. `ps -A` for the app and every `libcrush.so` child;
2. the engine's own `/v1/agent` (`is_busy`) over the private Unix socket;
3. the engine's session history (`GET …/sessions/{id}/messages`);
4. `dumpsys activity activities`, `dumpsys power`, `dumpsys deviceidle`,
   `logcat` kill reasons, and screenshots.

## 2. Results

| Case | Mechanism | 1 App survives | 2 Engine survives | 3 Execution continues | 4 Recoverable after relaunch | 5 Pending prompt | Verdict |
|---|---|---|---|---|---|---|---|
| **B1** screen off | `KEYCODE_POWER`, app foreground | **yes** | **yes** | **yes** (90 s bash completed) | n/a (no relaunch) | n/a | **passed with caveat** |
| **B2** backgrounded | `KEYCODE_HOME` | **yes** | **yes** | **yes** (`B2DONE` while backgrounded) | **yes** (visible after relaunch) | n/a | **passed** |
| **B3** Doze | `dumpsys deviceidle force-idle` | **yes** | **yes** | **yes** (`B3DONE` after unforce) | n/a (no relaunch; history intact) | n/a | **passed** |
| **B4** app killed | `am kill com.crush.mobile` | **no** | **no** | **no** | **yes, with an explicit “Interrupted” marker** | n/a | **passed** |
| **B5** engine only | in-app `kill_engine` | — | — | — | — | — | **not-performed** |
| **B6** force-stopped | `am force-stop` | **no** | **no** | **no** | **yes, same Interrupted marker** | n/a | **passed** |
| **B7** OEM cleaner | recents → OneKeyClean / Cleaner | **no** (`GarbageClean`) | **no** | run completed **before** the kill | **yes** (`B7DONE` intact) | n/a | **passed** |
| **B8** LMK | trim only (not LMK) | yes (trim did not kill) | yes | n/a | n/a | n/a | **not-performed** |

## 3. Per-case evidence

### B1 — screen off while foreground

- Prompt sent 11:00:26; `tool_call` observed **11:00:27.702** (run `5de163d6`).
- `KEYCODE_POWER` 11:00:35 → `pause` 11:00:34.385, `stop` 11:00:34.697,
  `mWakefulness=Asleep`, `mScreenOn=false`.
- The device **woke itself at 11:01:24.302** (`resume … away=49s`) — a 49 s
  screen-off window, not the intended longer one. Cause not established;
  `svc power stayon true` was set for the session and is the leading
  explanation. Recorded as a caveat, not hidden.
- At 11:01:27.973 the app logged `stream idle > 60s … engine reports busy -
  extending (1/3)`.
- `tool_result` at **11:01:58.016**, `failed=false`, **duration 90,033 ms**;
  history ends `B1DONE`.

Interpretation: the app process and engine survived, and the 90 s bash ran
through the 49 s screen-off window. The reply was produced after the device
resumed. This case does not isolate “screen off for the whole tool”; the
spontaneous wake cut the window short.

### B2 — backgrounded with HOME

- Prompt sent 10:53:22; `tool_call` **10:53:23.839** (run `b734b1e2`).
- HOME 10:53:47. At +40 s: `com.miui.home` resumed; app pid **2751** and
  engines **2838 / 9933 / 11068** all alive; `/v1/agent` `is_busy=true`.
- The turn finished while backgrounded: `tool_result err=false`, reply
  `B2DONE` (session history; 7 msgs at 10:55:59).
- Relaunch 10:56:21 (`gate=OPEN … away=154s`); the UI showed the completed
  bash row (2 m 00 s) and `B2DONE`.

### B3 — Doze

- Prompt sent 10:57:25; `tool_call` **10:57:26.173** (run `82b92e72`).
- `force-idle` 10:57:33: `Now forced in to deep idle mode`,
  `mState=IDLE`, `mScreenOn=true`, **`mCharging=true`** (the phone was
  plugged in; record this as part of the condition).
- At +40 s (10:58:34): still IDLE; app + all three engines alive;
  `is_busy=true`.
- Unforce 10:58:52 (`Light state: ACTIVE, deep state: ACTIVE`); the turn
  completed with `tool_result err=false` and `B3DONE`.

Interpretation: forced Doze did not kill the app or the engine, and the local
bash continued. The provider call that produces the reply needs the network;
it succeeded after the unforce. A longer Doze window with the final LLM call
inside it was not measured.

### B4 — app process killed while backgrounded

- Prompt sent 11:05:23 (run `b4…`, sleep 120); HOME 11:05:32.
- `am kill com.crush.mobile` **11:05:37**: app pid 2751 and **all three**
  `libcrush.so` children (2838, 11068, 13544) disappeared. No process
  survived.
- Relaunch 11:06:01 → `ConfirmDeviceCredentialActivity` (cold start,
  `gate=PROMPT coldStart=true`); owner unlocked.
- Session 822d13c1 history after relaunch:
  - msg 20 user: the B4 prompt;
  - msg 21 assistant: `[tool_call bash]`;
  - msg 22 tool: `is_error=true`, content
    **“tool call was interrupted and did not produce a result, you may retry
    this call if the result is still needed”**.
- UI: the turn renders **“This reply failed / Interrupted / Retry”**
  (`v2-16-p0-b4-interrupted-card.png`). No silent loss, no false completion.

### B5 — engine-only kill (not performed)

Attempted at 11:03:10, exactly the mechanism documented in `CLAUDE.md` and
used by `tools/chaos.ps1`:

```
adb shell am start -n com.crush.mobile/.ui.screens.spike.SpikeActivity \
  -a com.crush.mobile.FAULT --es fault kill_engine
```

Result: **`SecurityException: Permission Denial: … not exported from uid
10316`**. `SpikeActivity` is declared `android:exported="false"` in
`app/src/debug/AndroidManifest.xml`. `run-as com.crush.mobile am start …`
then fails with `package=com.android.shell does not belong to uid=10316`.
The direct-signal alternatives were already refused in P0-E1 (F5:
`run-as kill`, `toybox kill`, `adb shell kill`).

Therefore **no engine-only kill is available on this build**, and the case is
`not-performed`. The run started for B5 completed normally (`B5DONE`) and is
**not** evidence about engine death.

> **Harness consequence:** `tools/chaos.ps1` `Crash-Engine` uses this exact
> command and discards stderr (`2>$null | Out-Null`). On this build S01
> cannot inject its fault, so any green S01 that claims an engine-pid change
> is suspect. This is a test-integrity finding, not a product finding.

### B6 — package force-stopped

- Prompt sent 11:11:57; HOME 11:12:06; `am force-stop` **11:12:10**: app
  17356 and engine 17416 gone.
- An explicit launch (monkey) restarted the package at 11:12:13
  (`gate=PROMPT coldStart=true`). Force-stop blocks implicit starts, not an
  explicit user launch. Owner unlocked; by 11:14:24 `gate=OPEN`.
- History: same reconciliation as B4 — `tool_result is_error=true`,
  **“tool call was interrupted and did not produce a result …”**.

### B7 — OEM cleaner

- Prompt sent 11:16:31 (sleep 120); recents opened 11:16:41.
- **11:17:19 — `OneKeyClean`**: logcat shows MIUI `ProcessManager` killing
  many packages (`com.miui.gallery`, `com.miui.notification`,
  `com.android.smspush`, …) with reason `OneKeyClean`. **`com.crush.mobile`
  is not in that kill list**; the app and its engine survived the
  OneKeyClean tap.
- The MIUI Cleaner app was opened at 11:17:26 (cache/obsolete-file scanner;
  not a process killer). The app went to `stop` at 11:17:43.
- The B7 turn **completed at 11:18:33** (`tool_result err=false`,
  duration 120,100 ms; `B7DONE` in history) — the cleaner interaction did not
  interrupt the run.
- **11:18:52 — `GarbageClean`**: logcat:
  `ProcessManager: GarbageClean: kill com.crush.mobile Adj=805 State=20` →
  `ActivityManager: Killing 18029:com.crush.mobile/u0a316 (adj 805):
  GarbageClean`. The engine child died with it.
- Relaunch 11:20:25 → gate; owner unlocked; history intact, `B7DONE` shown.

Interpretation: the OEM **policy** kill that actually hit the app is
`GarbageClean` (MIUI process manager, cached adj 805), not `OneKeyClean`.
Both labels are OEM policy, neither is LMK. The kill happened **after** the
turn completed; this case does not show an OEM cleaner interrupting an
in-flight turn.

### B8 — LMK (not performed)

`am send-trim-memory com.crush.mobile COMPLETE` was issued at 11:18:49,
three seconds before the `GarbageClean` kill at 11:18:52. Trim-callback
delivery was therefore **not verifiable**, and the kill is attributable to
`GarbageClean`, not LMK. No memory pressure was induced and no LMK kill was
reproduced.

Recorded against the stricter standard:

| Field | Value |
|---|---|
| trim callback delivered | **not verified** (kill 3 s later) |
| actual memory pressure observed | **no** |
| app killed | **yes** — by `GarbageClean` |
| kill attributable to LMK | **no** |
| mechanism reproduced | **no** |

Verdict: **not-performed**. `am send-trim-memory` was never treated as an LMK
test.

## 4. Cross-cutting findings

### 4.1 A project switch cancels an in-flight turn (material)

At 10:42:35 a `sleep 300` turn started on project *Test project folder 2*
(run `90a0468f`). At 10:43:54 the Projects surface was used to open a third
project. The app's liveness guard kept the folder-2 engine alive (the third
start was refused `2/2` and engine 11068 was still listed), **but the turn
was cancelled**: the session history already contained
`tool_result is_error=true`, **“Error: user cancelled assistant tool
calling”** when it was read at 10:44:58. No automatic resume followed.

So the “run-in-flight” guard preserves the **engine process**, not the
**execution**. A binding switch is observed by the app as an explicit cancel
(`awaitServerCancel` on the outgoing ViewModel), not as a detach. This
contradicts the assumption that a live engine equals a live execution and is
fed to **P0-E5 / A1 / A7**.

### 4.2 Recovery is explicit, not silent

After both B4 (`am kill`) and B6 (`force-stop`), the engine persisted the
session, and on the next attach the orphaned tool call was reconciled to
`tool call was interrupted and did not produce a result, you may retry this
call if the result is still needed`. The UI shows **Interrupted / Retry**.
That is the honest “lost” outcome the plan asks for; it is **not** an
automatic resume and must not be described as recovery of the execution.

### 4.3 Process death kills the engine on this ROM

B4, B6 and B7 all show the engine child dying with the app. There is no case
on this device where the engine outlived the app, so “engine survives app
death” remains **not producible** here (same as P0-E1 F5).

### 4.4 The budget refusal is silent in the UI

The third-project start was refused with a precise reason in the log
(`engine instance budget: 2/2 non-legacy instances hold live children`), but
the screen only said **“Sessions aren't available. Start one to connect this
project's engine.”** The refusal reason does not reach the user. A7 must give
the budget refusal a named surface.

### 4.5 Post-session reaping

After ASK was restored and the app was left in the background (Settings
screen), a final check found the app and both engines gone and the launcher
resumed. Logcat in this evidence set covers the earlier `GarbageClean` kill;
the exact reason for this final reaping was not re-captured. It is recorded as
an observation, not attributed.

## 5. Verdict

- B1 **passed with caveat** (49 s screen-off window).
- B2 **passed**, B3 **passed**, B4 **passed**, B6 **passed**, B7 **passed**
  (OEM kill observed, after completion).
- B5 **not-performed** (fault harness unreachable on this build).
- B8 **not-performed** (no LMK; trim is not LMK).

Every case has all five fields recorded. The gaps are stated, not inferred.
`D1` remains open: this is API 29 evidence, and FGS/API 30+ behaviour is a
separate question (P0-E6).

## 6. Reproduction

```powershell
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
# B1
& $adb -s bbf8ca69 shell input keyevent KEYCODE_POWER
# B2
& $adb -s bbf8ca69 shell input keyevent KEYCODE_HOME
# B3
& $adb -s bbf8ca69 shell dumpsys deviceidle force-idle
& $adb -s bbf8ca69 shell dumpsys deviceidle unforce
# B4
& $adb -s bbf8ca69 shell am kill com.crush.mobile
# B5 (fails on this build: not exported)
& $adb -s bbf8ca69 shell am start -n com.crush.mobile/.ui.screens.spike.SpikeActivity -a com.crush.mobile.FAULT --es fault kill_engine
# B6
& $adb -s bbf8ca69 shell am force-stop com.crush.mobile
# B7
& $adb -s bbf8ca69 shell input keyevent KEYCODE_APP_SWITCH   # then the recents X / Cleaner
# evidence
& $adb -s bbf8ca69 shell run-as com.crush.mobile cat files/app.log
& $adb -s bbf8ca69 logcat -d -v time | Select-String 'OneKeyClean|GarbageClean|Killing .*com.crush.mobile'
```

## 7. Risk mapping (proposed, register not edited)

| Row | Effect of this measurement |
|---|---|
| **R17 background** | API 29: HOME, screen-off and forced Doze do **not** kill the app or engine; the turn completes. The failure mode is process/package death (B4/B6) or OEM policy (`GarbageClean`), not backgrounding itself. API 30+ still open. |
| **D1** | Foreground-only remains the safe default; FGS is not needed for API 29 backgrounding, but API 30+ restrictions are unmeasured. D1 **open**. |
| **R14 / C6** | A killed turn is not lost silently; the engine writes an interrupted tool result and the UI offers Retry. That is the recovery primitive A1 can build on. |
| **New (A7)** | A project switch cancels an in-flight execution even when the engine survives. Needs an explicit product rule and a UI state, not an implicit side effect. |
| **New (B4 harness)** | `chaos.ps1` S01 cannot inject `kill_engine` on the current build (exported=false). The harness must use a reachable fault path or the scenario must be marked skipped. |
| **New (A7)** | The 2/2 budget refusal is logged but not named in the UI. |
