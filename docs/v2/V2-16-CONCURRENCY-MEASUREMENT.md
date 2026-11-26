# V2-16 P0-E4 — Concurrency and memory sample

Date: **2026-11-26**. Device `bbf8ca69`, API 29, 3.7 GB total RAM
(`MemTotal 3,702,708 kB`), available 0.94–1.15 GB during the samples.
Engine `v0.100.0` (`2my2izixtou8`). Branch `v2-16-planning-baseline`,
head `9192bc5`, no commit. Permission AUTO_APPROVE for the experiment,
restored to ASK at 11:43.

Raw evidence: `docs/parity/v2-16-phase0/v2-16-p0-app-1039-1052.log`,
`v2-16-p0-app-checkpoint2.log`, `v2-16-p0-logcat-kills.txt`,
`v2-16-p0-session-822d13c1.json`.

## 1. Method and engine identities

Identity came from `/proc/<pid>/cmdline` (socket path) rather than from a
guess. The app's liveness budget is **two non-legacy instances with live
children** (`EngineInstanceRegistry`: `maxLiveNonLegacy = 2`, legacy key `""`
exempt).

| Instance | Socket | Engine pids observed | Role |
|---|---|---|---|
| legacy | `files/crush.sock` | 2838, 17416, 24132 | app's own chat workspace, outside the budget |
| Test project folder 2 | `files/crush-pd871eee8.sock` | 2853 → 11068 → 17416/18075/24136 | project engine, budget slot 1 |
| TestProject3 | `files/crush-p5ab508c3.sock` | 9933 → 13544 | project engine, budget slot 2 (restarted once, reason not established) |
| Test project folder | `pe26670d9` (no socket) | **none** | third start, **refused** |

PSS came from `/proc/<pid>/smaps_rollup`, RSS/threads from
`/proc/<pid>/status`, all under `run-as com.crush.mobile`.

## 2. Samples

### Baseline — legacy + one project, no turn in flight (10:31, app pid 2751)

| Process | PSS | Threads |
|---|---|---|
| app | **50,086 kB** | 39 |
| legacy engine 2838 | 15,009 kB | 11 |
| project engine 2853 | 23,645 kB | 13 |

`MemAvailable 1,151,744 kB`; 2 engine children.

### Bootstrapped project engine (10:32, pid 9933, session resumed)

PSS **80,000 kB** / 21 threads on the fresh reading; settled to **51,913 kB**
/ 21 threads in the next sample. Bootstrap (workspace attach + agent init)
roughly doubles an idle engine's footprint; the fresh reading includes pages
that are later reclaimed.

### Sample A — legacy + two project engines, one run in flight (10:40, app 2751)

| Process | PSS | Threads | State |
|---|---|---|---|
| app | 79,063 kB | 52 | two bound runtimes |
| legacy 2838 | 14,801 kB | 11 | idle |
| TestProject3 9933 | 51,913 kB | 21 | `sleep 300` run in flight |
| folder-2 11068 | 66,587 kB | 13 | bootstrapped, idle |

`MemAvailable 940,548 kB`. **3 engine children** (legacy + 2 non-legacy).

### Sample B — both project runs in flight (10:42, app 2751)

| Process | PSS | Threads | State |
|---|---|---|---|
| app | 78,438 kB | 50 | — |
| legacy 2838 | 14,828 kB | 11 | idle |
| TestProject3 9933 | 49,616 kB | 21 | run in flight |
| folder-2 11068 | 65,940 kB | 13 | run in flight |

`MemAvailable 959,844 kB`, `SwapFree 1,403,820 kB`.

Engine PSS total ≈ **130 MB**; app + engines ≈ **209 MB** of 3.7 GB. Available
memory did not collapse between samples A and B.

### Final idle sample (11:21, app 24069, after the OEM kill)

| Process | PSS |
|---|---|
| app | 71,715 kB |
| legacy 24132 | 26,642 kB |
| folder-2 24136 | 49,662 kB |

## 3. Third-start refusal (the budget gate)

At 10:43:54, with both non-legacy engines live and both runs in flight, the
third project (`pe26670d9`) was opened. Exact app log:

```
10:43:54.143 I/Chat: starting supervisor for instance 'pe26670d9' (prewarm on)
10:43:54.144 W/EngineRegistry: engine instance budget: 2/2 non-legacy instances
  hold live children; start of 'pe26670d9' refused until one stops
10:43:54.145 W/ChatViewModel: engine start refused for instance 'pe26670d9':
  engine instance budget: 2/2 non-legacy instances hold live children; ...
```

`ps -A` after the attempt still showed exactly three `libcrush.so` children
(2838, 9933, 11068) — **no fourth child was spawned**. The refusal is
fail-closed and deterministic, and it names both the budget and the key.

UI consequence: the screen showed **“Sessions aren't available. Start one to
connect this project's engine.”** only; the budget reason was log-only.

## 4. What actually creates the two-engine state

The binding switch (`AppContainer.applyBinding`) stops the outgoing
non-legacy engine **unless a run is in flight** on it. Therefore:

- ordinary project-to-project taps keep only one non-legacy engine alive;
- two live non-legacy engines were observed only while both outgoing
  projects had turns in flight (the setup used here), or via the handoff
  dispatch path (`bindCodeForHandoff`) which was not separately measured;
- the budget gate therefore binds during *concurrent runs / handoff*, not
  during ordinary navigation.

**Material cross-reference:** in that same setup the folder-2 run was
cancelled at the project switch while the engine stayed alive
(`tool_result: Error: user cancelled assistant tool calling`). The engine
process surviving a switch does **not** mean the execution survives it. See
`V2-16-BACKGROUND-MEASUREMENT.md` §4.1. The two-engine state is consequently
“two live engines”, not “two independently continuing executions”.

## 5. Verdict

- **Measured:** PSS, RSS and thread counts for legacy, one project engine and
  legacy + two project engines; third-start refusal observed with a named
  reason and no child spawned.
- **Memory conclusion:** a third engine is *not* needed for memory reasons on
  this device. The second project engine added ~50–66 MB PSS; the whole
  process tree stayed around 209 MB with ~0.94 GB still available.
- **Reliability conclusion:** the 2/2 refusal is reliable at the point
  measured (one attempt, both slots live). It was not repeated enough times
  to claim statistical reliability; it is a deterministic gate in code, and
  the single observation agrees with it.
- **D2 remains open:** the owner must decide whether to accept the 2-engine
  budget. A7 must surface the refusal by name, and must decide the semantics
  of a project switch with an in-flight run (currently: engine kept, turn
  cancelled).

## 6. Reproduction

```powershell
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s bbf8ca69 shell ps -A | Select-String 'libcrush.so'
foreach ($p in <app pid>,<engine pid>) {
  & $adb -s bbf8ca69 shell run-as com.crush.mobile cat /proc/$p/smaps_rollup | Select-String '^Pss:'
  & $adb -s bbf8ca69 shell run-as com.crush.mobile cat /proc/$p/status | Select-String 'VmRSS|Threads'
}
# start a long turn on project A, switch to project B and start a long turn,
# then open project C; read "engine start refused" from files/app.log
```

## 7. Risk mapping (proposed, register not edited)

| Row | Effect |
|---|---|
| **D2 / A7** | Memory does not justify a third engine; the existing 2/2 gate is observed and safe on this device. D2 is still an owner decision. |
| **R11 PSS/threads** | Numbers now recorded for idle, bootstrapped, busy and two-project states. |
| **R22 concurrency** | The gate binds only when both non-legacy slots have live children (concurrent runs / handoff), not during ordinary taps. |
| **New (A7)** | Budget refusal is silent in the UI; a named refusal surface is required. |
| **New (A1/A7)** | Engine survival ≠ execution survival on a project switch; the cancellation must be a designed, visible outcome. |
