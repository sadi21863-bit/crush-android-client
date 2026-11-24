# V2-15 Phase C — device evidence (raw capture)

Captured 2026-11-24 on the connected phone, on the debug build from this
working tree (uncommitted L1/L2/L3 + Phase C tests).

| Fact | Value |
|---|---|
| Serial | `bbf8ca69` |
| Model | Redmi Note 7 Pro (`violet`) |
| Android / API | 10 / 29 |
| ABI | arm64-v8a |
| App | `com.crush.mobile`, versionName `1.0.0` (debug), pid 14763 |
| Engine | bundled Crush `v0.100.0` (build `2my2izixtou8`, go1.27.1, android/arm64) |
| MemTotal | 3,702,708 kB |
| MemAvailable at start of run | ~1.18 GB |

All PSS values below are the `TOTAL:` line of `adb shell dumpsys meminfo <pid>`
in kB, three samples per process unless stated. These are per-process PSS
values; summing them double-counts shared pages and is an upper bound only.

## Sequence

The app was cold-started, unlocked by the user, and bound to project
`Test project folder 2` (instance key `pd871eee8`). The legacy supervisor was
already running (it is started on app boot and survives project switches).

### State A — legacy + project `pd871eee8` (2 children)

UI: project session list. 11:48:17–11:48:24.

| PID | Process | PSS samples (kB) |
|---|---|---|
| 14763 | app | 99,012 / 99,108 / 99,012 |
| 14968 | `libcrush.so` legacy (`files/crush.sock`) | 23,944 / 23,944 / 23,944 |
| 14972 | `libcrush.so` project `pd871eee8` | 40,811 / 40,811 / 40,811 |

`dumpsys meminfo com.crush.mobile` TOTAL = 99,135 kB — i.e. the package query
reports the **main process only**; the native children are separate PIDs and
must be sampled individually.

### State B — legacy + project `p5ab508c3` (2 children)

The user switched to `TestProject3` (row tap → *Open Code chat*). Log:
`11:49:47.742 Lifecycle: stopping project engine for instance 'pd871eee8' (no run in flight)`
`11:49:47.957 bindEngine ... p5ab508c3` → `11:49:52.127 engine Running after 4122ms`.
UI: `TestProject3` chat with a resumed 8-message session. 11:51:05–11:51:10.

| PID | Process | PSS samples (kB) |
|---|---|---|
| 14763 | app | 143,297 / 125,764 / 125,658 |
| 14968 | `libcrush.so` legacy | 23,594 / 23,594 / 23,594 |
| 18718 | `libcrush.so` project `p5ab508c3` | 60,460 / 60,460 / 60,460 |

First app sample is a transient (chat bootstrap); the last two are the stable
range. Legacy stayed idle at 23.6 MB.

### Stop

11:51:32 the user returned to the **Crush Chat** tab. The binding moved to the
legacy key and `applyBinding` stopped the outgoing project engine:

```
11:51:32.809 I/Lifecycle: stopping project engine for instance 'p5ab508c3' (no run in flight)
11:51:32.811 I/EngineSupervisor: [p5ab508c3] stop() requested
11:51:32.918 I/CrushConnection: connection stopped
```

### State C — legacy only (1 child)

UI: legacy chat with a resumed 2-message session. 11:51:51–11:51:56.

| PID | Process | PSS samples (kB) |
|---|---|---|
| 14763 | app | 138,323 / 130,411 / 127,877 |
| 14968 | `libcrush.so` legacy | 51,906 / 51,906 / 51,494 |

The project child is gone. The legacy child grew from 23.9 MB (idle) to 51.9 MB
because it is now hosting a bootstrapped chat session — child PSS is
workload-dependent, not a fixed per-engine cost.

### State D — legacy only, projects list

11:52, after tapping **Crush Code**.

| PID | Process | PSS samples (kB) |
|---|---|---|
| 14763 | app | 128,574 / 126,378 |
| 14968 | `libcrush.so` legacy | 51,158 / 51,158 |

### Post-stop census (same moment as State C)

`adb shell ps -A -o PID,NAME | grep libcrush` returned exactly one engine:

```
14968 libcrush.so
```

Engine command lines (from `/proc/<pid>/cmdline` via `run-as`):

```
14968 ... libcrush.so server -H unix:///data/user/0/com.crush.mobile/files/crush.sock -D .../files/crush/state
```

No child for `pd871eee8` or `p5ab508c3`.

`/proc/net/unix` filtered to `crush` entries contains **no** socket for either
project — only the legacy `crush.sock` (one `01` LISTEN entry plus its `03`
connected peers) and the engine's pinentry/sshaskpass helper sockets.

`run-as com.crush.mobile ls -l files/crush-p5ab508c3.sock files/crush-pd871eee8.sock`
returned `No such file or directory` for both: the engine removed its socket
file during shutdown.

## What this does and does not prove

- Proves on a real device: a project engine child dies on the stop path, its
  socket file is removed, no listener remains, and the registry/app stay alive.
  This is the runtime half of `A13` that JVM tests could not reach.
- Shows engine children are not free: 23.6–60.5 MB PSS each depending on
  workload, on top of a 99–143 MB app process. This supports keeping the
  budget at 2 non-legacy instances and not adding automatic eviction.
- **3-child sample NOT PERFORMED.** Normal binding switches stop the outgoing
  non-legacy engine (`AppContainer.kt:775-800` when no run is in flight), so the
  reachable maximum via ordinary navigation is legacy + one project. Two live
  project children need the run-in-flight deferral/handoff path or a budget
  override; that is a separate experiment and was not run.
- App-process PSS moved from 99 MB (idle projects list) to ~128 MB after chat
  interactions. That is UI/content caching, not engine retention, and it is why
  the process/socket census — not PSS deltas — is the stop proof.

## Screenshots

- `v215-02-unlocked.png` — project session list, 2 children (state A).
- `v215-04-tab.png` — projects list, `Test project folder 2` current.
- `v215-05-two-projects.png` — `TestProject3` sheet before opening its chat.
- `v215-06-after-stop.png` — projects list after the stop; no `Current` row,
  legacy only.
