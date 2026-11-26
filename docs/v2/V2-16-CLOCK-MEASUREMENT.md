# V2-16 P0-E3 — Clock and tool-class measurement

Date: **2026-11-26**. Device `bbf8ca69`, API 29, serial as above.
Engine `v0.100.0` (`2my2izixtou8`). Branch `v2-16-planning-baseline`,
head `9192bc5`, no commit. Permission mode AUTO_APPROVE for the experiment,
restored to ASK at 11:43.

Raw evidence:

- `docs/parity/v2-16-phase0/v2-16-p0-app-1039-1052.log` (10:39–10:52 run logs)
- `docs/parity/v2-16-phase0/v2-16-p0-app-checkpoint2.log` (the 240 s timeline)
- `docs/parity/v2-16-phase0/v2-16-p0-session-822d13c1.json` (tool results)
- `docs/parity/v2-16-phase0/v2-16-p0-ask-restored.png`

## 1. The clocks as implemented (source read, not inferred)

From `ChatViewModel.kt`:

| Constant | Value | Role |
|---|---|---|
| `STREAM_IDLE_MS` | **60,000 ms** | per-frame idle deadline; resets on every received frame |
| `IDLE_EXTENSIONS_MAX` | **3** | extra 60 s windows while `agentInfo().isBusy == true` |
| effective total | **240 s** (4 × 60 s) | then `awaitServerCancel` + `StopStream` |
| `PROMPT_WAIT_MS` | 600,000 ms | ceiling for **one parked** ask/question |
| `SEND_TIMEOUT_MS` | 30,000 ms | wraps **both** `captureBefore()` and the prompt POST |

On idle expiry the app probes the engine; `busy=true` earns an extension,
`busy=false` cancels immediately. `waitingOnPrompt` uses a separate loop, so a
parked dialog is not counted as silence.

## 2. Tool-class matrix

Measured on the same project session, one class per turn. “Silence” is the
gap between the tool-call frame and the tool-result frame as observed in
`app.log`. “Progress” means what the app can actually observe, not what the
tool printed internally.

| Class | Representative used | Tool duration (app log) | Longest healthy silence | Engine reports progress? | 60 s probe fired? | 240 s budget fired? |
|---|---|---|---|---|---|---|
| network request | engine `fetch` on `example.com` | 0 ms (call 11:29:12.075 → result 11:29:12.421) | < 1 s | one tool_call + one tool_result frame | no | no |
| codegen | bash, 200 text files | **127 ms** (11:30:45.063) | ~0.5 s | result frame only | no | no |
| build | bash, 400k-iteration CPU loop | **1,914 ms** (11:31:37.556) | ~2 s | result frame only | no | no |
| test suite | bash, 5 × `echo` + `sleep 3` | **15,200 ms** (11:32:46.065) | ~15.5 s | result frame only | no | no |
| package install/download | engine `download`, 10,000,000 bytes | result at 11:33:37.785; follow-up bash 33 ms | ~3 s | proxy CONNECT + result frame | no | no |
| long-running shell | bash `sleep 90` | **90,033 ms** (11:01:58.016) | **90 s** | `is_busy=true` at the 60 s probe; extension 1/3 | **yes** | no |
| long-running shell | bash `sleep 120` (B7) | **120,100 ms** (11:18:33.744) | **120 s** | `is_busy=true`; extensions 1/3 and 2/3 | **yes** (twice) | no |
| long-running shell | bash `sleep 260` | cancelled at 240 s | **240 s** | `is_busy=true`; extensions 1/3, 2/3, 3/3 | **yes** (3×) | **yes** |

Notes on the representatives:

- `curl` through bash is **denied by the engine's own tool policy**; the model
  explicitly said “`curl` via bash is disallowed by my rules; used `fetch`
  instead”. Therefore the network and download classes were exercised with
  the engine's native `fetch` / `download` tools, which is the realistic
  representative for this build.
- The test suite printed `running1…running5`, but the app received **no
  per-line frames**: only the tool_call and the final tool_result. Output
  cadence is therefore one frame at start, one at completion for every bash
  tool measured.

## 3. The 240-second budget (the R31/D4 number)

One `sleep 260` turn, foreground, no project switch, no backgrounding:

| Time | Event |
|---|---|
| 11:23:58.682 | `tool_call observed … name=bash run=9aa2b546…` |
| 11:24:59.021 | `stream idle > 60s … engine reports busy - extending (1/3)` |
| 11:25:59.081 | `… extending (2/3)` |
| 11:26:59.139 | `… extending (3/3)` |
| **11:27:59.200** | `… (extension budget spent); requesting engine cancel` |
| 11:28:18 (expected) | the `sleep 260` would have returned |

Engine history: `tool_result is_error=true`, content **`context canceled`**.
No assistant reply. The app cancelled a **healthy, busy** tool **19 seconds
before it would have finished**, purely because no SSE frame had arrived for
60 s at a time.

This is the behaviour the current 240 s budget produces. It is reported as a
number, not as a proposal to change it. **D4 remains open.**

## 4. Liveness versus progress

The three things the app can distinguish are:

1. **liveness** — `agentInfo().is_busy == true` (engine reports a run);
2. **frame freshness** — time since the last SSE frame;
3. **progress** — *not observable* for bash tools in this build.

During `sleep 260`, liveness was continuously true and frames were stale;
the app treated that as a stall and cancelled. There is no signal that
distinguishes “busy but silent” from “busy and stuck”. Any per-tool-class wait
policy in D4/A4 must work with the busy probe as the only progress proxy, or
must obtain a real progress signal from the engine.

The parked-prompt path is separate and was not affected by these clocks:
`PROMPT_WAIT_MS` (600 s) governs it; the P0-E1 prompt cases used that path.

## 5. Snapshot cost and the 30 s send budget

Source facts (`WorkspaceSnapshotter.kt`, `ChatViewModel.kt:4000-4018`):

- caps: `maxFiles=400`, `maxFileBytes=256 KB`, `maxTotalBytes=4 MB`,
  depth ≈ 4, with build/dependency directories skipped;
- `captureBefore()` executes **inside** `withTimeoutOrNull(SEND_TIMEOUT_MS)`,
  in the same 30 s window as the prompt POST;
- an unchanged file is reused by size + mtime, so only the first capture of
  a tree pays for content reads.

**Measured proxy (device, emulated storage):** created 400 × 8 KB files
(3.2 MB) in the project folder and timed the shell equivalent of the walk
and reads:

```
400
0m00.10s real  0m00.03s user  0m00.08s system
```

This is a shell `find | wc -l; cat … > /dev/null` proxy, **not** a call to the
Kotlin `captureBefore()`. Within the implemented 400-file / 4 MB caps, the
measured I/O cost is ~0.1 s — two orders of magnitude below the 30 s send
budget and the 60 s idle clock. The synthetic tree was removed afterwards.
A direct instrumented Kotlin measurement on the device was not performed;
the cap analysis and the proxy are reported as such.

## 6. Verdict

- **Passed:** every required tool class has a cadence/silence profile and a
  verdict; snapshot cost measured as a device I/O proxy with the cap analysis;
  no production change.
- **Material finding:** the existing 240 s budget falsely classifies a
  healthy 260 s bash tool as stalled and cancels it 19 s early. Do **not**
  raise the timeout merely because of this measurement — the correct fix
  needs a progress signal or a per-tool-class policy (D4/A4).
- **Open:** a genuine progress signal from the engine; API 30+ behaviour;
  direct Kotlin snapshot timing on device.

## 7. Reproduction

```powershell
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
# send: "Use the bash tool to run: sleep 260. Then reply E3LONG. Run no other tool."
& $adb -s bbf8ca69 shell run-as com.crush.mobile cat files/app.log > app.log
Select-String -Path app.log -Pattern 'stream idle|tool_call observed|requesting engine cancel'
# engine history: look for the tool_result content "context canceled"
```

## 8. Risk mapping (proposed, register not edited)

| Row | Effect |
|---|---|
| **R18 silent phase** | Measured: silence is the norm for bash tools; output is not streamed frame-by-frame. The busy probe is the only liveness signal. |
| **R31 send budget** | With caps at 400 files / 4 MB and a 0.10 s device I/O proxy, `captureBefore()` is not a send-timeout risk in the measured tree. Direct Kotlin timing still open. |
| **D4 / A4** | The 240 s total budget demonstrably cancels healthy silent-but-busy work. A single global silence budget is not sufficient; this is evidence for a tool-class policy, not a timeout increase. |
| **New** | Engine-side progress reporting (or per-class expectations) is required to distinguish “busy, silent, healthy” from “wedged”. |
