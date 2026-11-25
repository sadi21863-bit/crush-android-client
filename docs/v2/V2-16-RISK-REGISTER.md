# V2-16 risk and decision register

**Revision 3** — 2026-11-25. Review issues A-F and the five rev-3 corrections
addressed; D7 added. Status: planning artifact from a read-only audit. Baseline:
`v2-15-lifecycle-hardening` at `37ab5db` (12 ahead / 0 behind `main` `adc26c0`).
No code, test, workflow or committed document was changed to produce this
register; it is an untracked working-tree file awaiting the final approval
decision.

**Revision history**

| Revision | Change |
|---|---|
| rev 1 | initial audit: R1-R16 carried, R17-R33, D1-D6, gates |
| rev 2 | review issues A-F: D3 and D5 deferred, A-G2 conditional on D1, R17 reworded, schema/status normalized, five clocks separated, R34 + D7 added |
| rev 3 | five corrections: 4.1 ownership wording; R24 qualified against R32; A-G4 requires a per-clock terminal behaviour; D5 separates the four assessments from X4; R34 narrowed to app-side tracking and 4.2 consequence reworded |

## 1. How to read this document

**Class**

| Class | Means |
|---|---|
| **Defect** | behaviour that contradicts a stated contract; needs a fix or a re-stated contract |
| **Risk** | known limitation or unverified behaviour that can hurt the product; may be accepted |
| **Decision** | no correct answer without an owner choice; implementation must wait |
| **Dependency** | outside the app's control (upstream engine, device, CI, store policy) |

**Status** (the only values used)

| Status | Means |
|---|---|
| `open` | known, no blocker, work not started |
| `blocked` | cannot progress until a named decision or dependency resolves |
| `accepted` | owner accepted it as a limitation (V2-15 decision or this register) |
| `resolved` | closed with evidence |
| `deferred` | deliberately parked, not part of the next milestone |
| `unverified` | behaviour is asserted from source but has no measurement |
| `not-performed` | a planned verification has not been executed |
| `monitoring` | works as built; watch, no work planned |

**Severity** is product/engineering impact. **Track**: A = correctness and
architecture, B = release readiness. Evidence labels follow V2-A6: CODE, TEST,
DEVICE, RECORD, UNVERIFIED.

## 2. Baseline and audit surface

| Item | Value |
|---|---|
| Branch / head | `v2-15-lifecycle-hardening` / `37ab5db` |
| `main` | `adc26c0`, untouched |
| Last full JVM gate | **117 suites / 1087 tests / 0 failures** (XML re-read; `v2-61`) |
| Instrumented | 14 passed, 1 blocked by test design (Phase F) |
| Engine of record | `v0.100.0` (V2-A20); `0.98.1` is historical |
| Test device | Redmi Note 7 Pro, **API 29**, arm64-v8a, 3.7 GB RAM |
| CI | no Actions run or status check for this branch |

Audited by source read: engine supervision and spawn, workspace binding and
re-attachment, SSE/connection transport, question and permission lifecycles,
Room/DataStore persistence, diagnostics/logging, both manifests, and the
storage/SAF import path.

**Not audited:** Go engine internals, the protocol beyond the Kotlin client,
Gradle/dependency upgrades, the Play listing, and any runtime behaviour not
already recorded by V2-15. No new tests were run and no device experiment was
performed for this register.

## 3. Capability assessment — long-horizon autonomous coding on mobile

Static-analysis judgements, not measurements.

| Capability | Verdict | Why (evidence) | Gaps |
|---|---|---|---|
| 1. Long-running coding tasks | **Partially supported** | No total turn cap; idle extends while the engine reports busy. But a silent phase is capped at ~240 s (section 4), and nothing keeps the app alive in the background. | R17, R18, R31, R32, R34 |
| 2. Reliable execution across interruptions | **Not established** | SSE cannot be resumed; no explicit mid-turn resync. Process death loses the run handle and clears pending prompts. App-side execution tracking is owned by the chat ViewModel. | R20, R21, R23, R34 |
| 3. Persistent agent state and recovery | **Partial** | Engine persists sessions/messages; app persists binding, settings, projects, trust, handoffs, and resumes the most recently updated session with content. No run/prompt/"last session" state. | R3, R4, R20, R23 |
| 4. Multiple projects and isolated sessions | **Supported within a hard limit** | One engine + socket + data dir per project; legacy hosts Chat. Max **2** live non-legacy children; stop-on-switch unless a run is in flight; third start refused by name. | R22, R28 |
| 5. Tool execution, approval and cancellation | **Supported, with caveats** | ASK default, fail-closed policy apply, grant/deny, question answer/cancel. Cancel is best-effort; a prompt orphaned by process death has no defined resolution. | R1, R4, R19, R20 |
| 6. Observability and partial-failure recovery | **Weak — and this is the architectural gap** | Logs, crash archive and Diagnostics exist. There is no run-level surface: run id, last-event age, tool phase, per-run trace. "Stuck" is indistinguishable from "thinking". | R25, R26, R34 |

**On capability 6.** Observability is not a diagnostics-screen feature to add
later. For an autonomous coder, the execution state is the product: a user must
be able to tell whether the agent is working, waiting for approval, stalled, or
recovering, and must be able to act on that state. V2-16 should design the
execution state machine and its observable surface together with the lifecycle,
not after it.

## 4. Execution lifecycle model

### 4.1 The five clocks

Five distinct mechanisms, with ownership split between the app and the engine.
Their configuration and failure semantics are not reconciled in one contract.

| # | Clock | Current value | Owner | Effect when exceeded | Row |
|---|---|---|---|---|---|
| 1 | **Max silent interval** (per-frame stream idle) | 60 s, +3 extensions of 60 s while `agentInfo.isBusy` → **240 s** | app (`ChatViewModel`) | awaited server cancel, turn ends | R18 |
| 2 | **Max tool execution interval** | none in the app; engine-side policy unknown | engine (upstream) | unknown | R33 |
| 3 | **Max total run duration** | none in the app; engine-side policy unknown | engine (upstream) | unknown | R33 |
| 4 | **Engine idle timeout** | 60-86 400 s (device: 15 min), set via `CRUSH_SERVER_IDLE_TIMEOUT` | engine, value chosen by the app | engine exits | R32 |
| 5 | **App-side parked-prompt wait** | 600 s | app (`ChatViewModel`) | awaited cancel, turn ends | R20 |

A sixth, startup-only clock exists: a 30 s `SEND_TIMEOUT_MS` that also covers
the pre-send workspace snapshot (R31).

**The contract question.** A 20-minute coding task must not fail because a tool
emits no SSE frame for four minutes; equally, an unbounded run with no failure
detection is not acceptable. The five clocks must be replaced by one defined
execution contract with: observable progress (heartbeat or equivalent), bounded
failure detection, and explicit cancellation semantics. This is R18 + R32 + D4,
and it depends on X1 (engine progress events, if any).

### 4.2 Where execution state lives today

| Responsibility | Current owner | Survives chat screen cleared? |
|---|---|---|
| Engine process supervision | `AppContainer` / `EngineSupervisor` | yes |
| Active run handle, stream collector | `ChatViewModel.activeRun` (`:839`) | **no** — `onCleared()` (`:1939`) closes the router/connection and cancels `viewModelScope`; no engine cancel is sent |
| Pending permission / question | `ChatViewModel` state | **no** — cleared on bootstrap (`:1758-1773`) |
| Session/run history | engine data dir | yes |
| Binding, trust, settings | DataStore / Room | yes |

Consequence: an engine run may outlive the app-side tracking. The engine may
continue running or remain parked after app-side tracking is lost; the current
lifecycle does not establish a guaranteed cancellation or recovery path, and the
app has no execution object to re-attach to. This is R34 and decision D7.

## 5. Register

### 5.1 Carried from V2-15 (IDs and meaning preserved)

| ID | Class | Sev | Finding | Track | Status | Owner decision / acceptance / next action |
|---|---|---|---|---|---|---|
| R1 | Risk | Med | **E3** — pending question carries no `session_id`/`run_id`; the answer goes to the engine's current session. Benign with one project chat; concurrent asks could cross sessions. | A | accepted | V2-15 acceptance 2026-11-25; future identity work in D3/D7 |
| R2 | Risk | Med | **E4** — pending question has no engine-generation identity; a stale dialog survives an engine restart until answer/cancel fails. | A | accepted | V2-15 acceptance; folded into D3 |
| R3 | Risk | Low | No process-death persistence for a pending question. | A | accepted | V2-15 acceptance; folded into D3 |
| R4 | Risk | Low | No answer read-back; a 2xx on `/questions/answer` is the only proof. | A | accepted | V2-15 acceptance |
| R5 | Risk | Low | Free-text question UI has no Compose test; only the wire mapping is pinned. | B | open | add the test when Track B starts |
| R6 | Risk | Low | Recreation lock test cannot run behind the system credential prompt; JVM `LockStateTest` (9) is the coverage. | B | blocked | test-design fix, no production change |
| R7 | Risk | Med | Device matrix gaps: API 30+, tablet/foldable, RTL, TalkBack speech, three question kinds, real extended rail. | B | not-performed | accepted for V2-15 only; D6 defines the release matrix |
| R8 | Decision | Low | **A2** — project home is still a modal sheet. | A | open | owner design decision, not a release blocker |
| R9 | Risk | Low | **A6** — `chat` route + `codeActive` carry two meanings. | A | monitoring | works as built; revisit only if it blocks D7 |
| R10 | Risk | Med | **A13** — `onTerminate()` is not guaranteed; process-death cleanup relies on the engine idle timeout. | A | accepted | V2-15 acceptance; consequence tracked as R24 |
| R11 | Risk | Low | 3-child PSS and thread census never measured. | B | not-performed | measure with D2 |
| R12 | Decision | — | Engine version of record: `v0.100.0`; `0.98.1` historical. | — | resolved | V2-A20 |
| R13 | Dependency | Low | Issue #2: x86_64 ARM-bridge crash; README qualification deferred. | B | deferred | untouched by V2-16 unless chosen |
| R14 | Dependency | Med | CI is engine-less; no CI run exists for this branch. | B | open | X3 / D6 |
| R15 | Decision | — | Gate 7 (signing, listing, rollout) untouched. | — | deferred | separate milestone |
| R16 | Dependency | Low | MIUI defers instrumentation starts and can kill runs; wall times are not test times. | B | accepted | environmental constraint |

### 5.2 New findings from this audit

**No defect-class row was confirmed.** The closest candidates — R18 (busy engine
cancelled after 4 min of silence), R20 (parked prompt discarded without a
cancel) and R31 (send budget covers the snapshot) — are risks because the
contradicting behaviour is deliberate or unmeasured. A measurement that shows a
user-visible failure with no defined resolution reclassifies the row as a
defect; the ID stays.

| ID | Class | Sev | Finding | Evidence | Owner decision | Acceptance criterion | Track | Status |
|---|---|---|---|---|---|---|---|---|
| R17 | Risk | **High** | **Background execution is not explicitly supported.** No foreground service, no wake lock, no `KEEP_SCREEN_ON`; the engine is a plain child process. Execution is therefore **not guaranteed** when the app is not in front; whether it continues, freezes or dies depends on process importance, Doze, memory pressure and OEM policy, and is **unmeasured**. | No `<service>`/`android:process` in any manifest; no `startForeground`/`WakeLock`/`KEEP_SCREEN_ON` in `app/src/main`; `CrushEngine.kt:421` `ProcessBuilder`. | **D1** | Measure the five cases: screen off (app foreground), app backgrounded, Doze, process killed, LMK reclaim; record the observed state in each | A | unverified |
| R18 | Risk | **High** | **Silent-phase ceiling ~4 min.** 60 s per-frame idle; 3 extensions only while `agentInfo.isBusy`; 4th expiry cancels. A long silent tool phase is cancelled while the engine is busy. | `ChatViewModel.kt:5057,5066`; `awaitStream` `:4496-4538`. | **D4** | One silent-phase test per tool class (build, test, download) proves the chosen clock; a 20-min task completes or fails with a named reason | A | unverified |
| R19 | Risk | Med | **Cancel is best-effort.** `awaitServerCancel` logs and returns; the turn is dropped. A failed POST leaves an engine run nothing tracks. | `ChatViewModel.kt:4786-4794`. | **D7** | Cancel outcome is observable and retryable; a failed cancel is a named state, not a log line | A | open |
| R20 | Risk | **High** | **Parked prompt can be orphaned by process death.** Bootstrap clears both pending prompts without a cancel or grant; engine republish after `current-session` is unverified; the app-side ceiling dies with the process. | `ChatViewModel.kt:1758-1773`; no cancel on that path. | **D3 (deferred)** | X1 proves republish or not; the chosen policy leaves no prompt unresolved without a visible outcome | A | blocked |
| R21 | Risk | Med | **SSE gaps are not explicitly repaired.** No `id:`/heartbeat; no mid-turn `messages` resync; lost frames depend on later whole-message republish or `run_complete`. | `CrushEventStream.kt:12-21,71-89`; `messages()` only at session open / late-report. | **D4** | A forced stream drop mid-turn is recovered or reported with a defined outcome | A | unverified |
| R22 | Decision | **High** | **Concurrency envelope.** Max 2 live non-legacy engines, stop-on-switch unless a run is in flight, third start refused. A hard cap, not a tuning knob, if concurrent multi-project work is required. | `EngineInstanceRegistry.kt:51-56,160`; `AppContainer.kt:790-799`. | **D2** | The documented envelope is enforced and tested; a refused start has a recovery path | A | open |
| R23 | Risk | Med | **No app-side run/session persistence.** `activeSessionId` and `activeRun` are memory-only; recovery uses "most recently updated session with content". | `SettingsDataStore.kt` keys; `ChatViewModel.kt:1736-1752,1968-2001`. | **D7** | Relaunch after death returns to a defined run/session state with no silent loss | A | open |
| R24 | Risk | Med | **Orphan engine after app death.** `shutdown()` runs only from `onTerminate()`. The engine **may** remain alive until its configured idle timeout (device value 15 min) **if it considers itself idle**; whether an active run or an open SSE suppresses that timeout is unverified (R32), so **how long orphaned work continues is not established**. It may hold RAM/battery during that window. | `CrushMobileApp.kt:109-116`; `EnginePaths.kt:89`. | V2-15 accepted (R10); duration to be measured under R32 | Orphan lifetime measured with and without an active run (ties R32); the bound is documented or the lifecycle is changed | A | unverified |
| R25 | Risk | Med | **No run-level observability.** UI exposes phase/engine state and transcript; Diagnostics exposes app/device/engine and a log tail. No run id, last-event age, tool phase or run trace. | `ChatViewState.kt`; `DiagnosticsReport.kt`. | **D7** | From inside the app a run exposes: state, last progress age, current tool, and a cancel/retry action | A | open |
| R26 | Risk | Low | **Transcript memory is unbounded.** Full message list in `ChatUiState`; tool rows truncated, but no paging or cap for a very long session. | `ChatViewState.kt:166`; `ToolActivity.kt:168`. | **D4** | A 10-hour-session profile shows bounded memory or an explicit cap/paging policy | A | unverified |
| R27 | Decision | **High** | **Storage/distribution model.** Raw filesystem paths under `/storage/emulated/0`; SAF import limited to `primary:`; API 30+ needs **All files access**, a Play-restricted permission. | `AndroidManifest.xml`; `FolderUriDecoder.kt`; `ProjectsScreen.kt:150,214`. | **D5 (deferred)** | Policy eligibility, app-managed workspace feasibility, engine SAF support and UX are assessed before a choice | B | blocked |
| R28 | Dependency | Med | **API 30+ unexercised.** Scoped storage, All-files access, FGS types/notification permission, per-app language are unmeasured. | V2-15 matrix; `minSdk 29 / targetSdk 36`. | **D6** | Every D6 row executed or explicitly `not-performed` with a reason | B | not-performed |
| R29 | Dependency | Med | **No engine-inclusive CI.** `libcrush.so` is gitignored; CI builds/tests an engine-less APK; no automated runtime lane. | workflow; V2-15 R14. | **X3** | At least one engine-inclusive suite runs in CI or an accepted equivalent with a stated claim | B | open |
| R30 | Dependency | Low | Unattended instrumentation needs an explicit foreground start and can be killed externally. | Phase F section 5.1. | none | Device runs are scripted with the foreground unblock and tolerate kills | B | accepted |
| R31 | Risk | Med | **30 s send budget includes the pre-send workspace snapshot.** `captureBefore()` runs inside the same timeout as the POST; a large project can exhaust it, or produce a false "send timed out" after the POST landed. | `ChatViewModel.kt:4000-4023`; `WorkspaceSnapshotter.kt`. | **D4** | Snapshot is excluded from the send budget or bounded separately; a large-repo send test passes | A | unverified |
| R32 | Risk | Med | **Engine idle timeout vs active run is undocumented.** Whether an open SSE or an active run suppresses the idle timer is not stated or tested; a low value could kill a healthy turn server-side. | `EnginePaths.kt:89`; `TuningPolicy.kt:80`. | **X1 / D4** | X1 establishes engine idle semantics; the app never sets a value that can kill a live run | A | blocked |
| R33 | Dependency | Med | **Recovery behaviours are upstream contracts:** parked-question republish, cancel idempotency, idle semantics. | `spec/crush-openapi.json` (no question re-read); E3/E4. | **X1** | One contract test per behaviour against `v0.100.0` | A | blocked |
| R34 | Risk | **High** | **App-side active-run tracking and pending-prompt state are chat-UI-scoped.** Engine process supervision survives (`AppContainer`/`EngineSupervisor`), but `activeRun`, the stream collector and pending prompts live in `ChatViewModel`; `onCleared()` closes the router/connection and cancels `viewModelScope` without an engine cancel. The engine may continue running or remain parked after app-side tracking is lost; no guaranteed cancellation or recovery path is established. | `ChatViewModel.kt:839,1939-1953`; section 4.2. | **D7** | An execution can be started, observed, cancelled and recovered without the chat screen's ViewModel being alive | A | open |

## 6. Decisions

Decisions block implementation; none is to be guessed in code.

### D1 — Background execution strategy

| Option | Consequence |
|---|---|
| (a) Foreground service + persistent notification | real background execution; needs `FOREGROUND_SERVICE` (+ type on API 34+), `POST_NOTIFICATIONS` on 33+, battery/UX cost |
| (b) Foreground-only product constraint | no OS work; must be stated in the product and the UI; A-G2 becomes an interruption test |
| (c) Hybrid: FGS only while a run is active | best UX/battery trade-off; most implementation surface |

**Status:** open. **Blocks:** R17, gate A-G2, capability 1.
**No recommendation is recorded until R17's five-case measurement exists.**

### D2 — Concurrency envelope

Options: (a) keep 2 live engines, one active run; (b) one run per project up to
the budget; (c) raise the budget. **Status:** open. **Blocks:** R22, R11, gate
A-G5.

### D3 — Parked-prompt durability — **DEFERRED, blocked by X1**

The rev-1 recommendation (rely on engine republish) is withdrawn: it rests on
an unverified engine behaviour and cannot identify what to cancel if republish
does not happen. D3 stays undecided until **X1** returns the results of the
experiment in section 7. Only then choose between: app-side persistence with run
identity; republish-based recovery with cancel-on-attach; cancel parked runs on
process death.

**Status:** deferred (blocked by X1). **Blocks:** R2, R3, R20, gate A-G3.

### D4 — Long-run execution contract

Options: (a) keep 240 s silence; (b) raise it with a heartbeat; (c) adopt
engine progress events if they exist. **Status:** open, depends on X1.
**Blocks:** R18, R21, R31, R32, gates A-G4 and A-G6.

### D5 — Storage and distribution — **DEFERRED; A1 blocked by X4, A2-A4 unblocked**

The rev-1 recommendation (keep All-files access) is withdrawn. A restricted
permission is a distribution decision, not a convenience.

Four assessments are required before a storage model can be selected. They are
independent, and **only the first is blocked by X4**:

| # | Assessment | Blocked by |
|---|---|---|
| A1 | Play-policy eligibility for `MANAGE_EXTERNAL_STORAGE` | **X4** (external) |
| A2 | Viability of an app-managed workspace for the agent's file workflow | nothing — internal design spike |
| A3 | Can the engine operate through SAF/FUSE, and at what cost? | nothing — upstream feasibility check, adjacent to X1 but not X4 |
| A4 | Is the UX acceptable without broad filesystem access? | nothing — product design |

X4 answers A1 only. Treating A2-A4 as "blocked by policy" would idle work that
can proceed in parallel.

**Status:** deferred; **A1 blocked by X4**, A2-A4 unblocked. **Blocks:** R27,
gate B-G3.

### D6 — Release device matrix

Define API levels, form factors and locales for "broad release". **Status:**
open. **Blocks:** R7, R28, gate B-G1.

### D7 — Execution lifecycle independent of the chat UI

**Question:** can an execution continue, be observed, be interrupted and be
recovered without the chat screen remaining alive?

| Option | Shape |
|---|---|
| (a) keep ViewModel ownership | smallest change; execution remains screen-scoped; contradicts the product direction |
| (b) process-scoped execution controller in the container | run/session layer owns state; ViewModel becomes a view of it; no OS background guarantee by itself |
| (c) service-based execution with FGS | strongest guarantee; ties to D1 |

**Status:** open. **Blocks:** R19, R23, R25, R34; gate A-G6. Capability 2 and
the observability requirement cannot be accepted without an answer.

## 7. Dependencies

### X1 — Engine contract verification (`v0.100.0`)

Required experiment, run twice (permission prompt, question prompt):

1. Start a run that parks on the prompt.
2. Confirm the prompt is visible and the run is parked.
3. Kill the application process (not the engine).
4. Reopen the application.
5. Reattach to the same workspace.
6. Record whether the prompt is republished, and with what identity.
7. Cancel or answer; record whether the original run still exists.
8. Repeat when the original run no longer exists.

Also establish: cancel idempotency, and whether an active run or an open SSE
suppresses the engine idle timeout (R32).

### X2 — Devices

API 30+, one tablet/foldable, one RTL locale. Required for R28 and gate B-G1;
not substitutable by an emulator claim.

### X3 — CI runtime lane

An arm64 runner or device farm that can run at least one engine-inclusive
suite (R29, gate B-G4).

### X4 — Play policy

The `MANAGE_EXTERNAL_STORAGE` eligibility answer for D5 (R27, gate B-G3).

## 8. Track A — correctness and architecture gate

| Gate | Criterion |
|---|---|
| **A-G1** | Process-death matrix: app killed (a) idle, (b) mid-turn silent, (c) mid-tool, (d) parked permission, (e) parked question, (f) after Submit before ack. Each cell states what recovers automatically and what is lost. |
| **A-G2** | **Conditional on D1.** If D1 = FGS/hybrid: a run continues for a defined duration with screen off and the app backgrounded, measured on API 29 **and** one API 30+ device, with a visible notification. If D1 = foreground-only: backgrounding produces a controlled, visible interruption — named state, no silent cancellation, safe resume/recovery — and the limitation is stated in the UI and docs. |
| **A-G3** | A pending prompt has a defined identity and a defined process-death policy (persist, republish, or cancel), selected from X1 evidence. No prompt can be orphaned without a bounded, visible resolution. |
| **A-G4** | One execution contract replaces the five clocks of section 4.1. For **each** clock it defines an explicit, observable terminal behaviour, and the action is not assumed to be the same: silent interval → app-initiated cancel (server-acknowledged where possible); tool interval → whatever the engine supports (progress event, cancel, or bounded wait); total run → the enforcement point (app or engine) is named; engine idle → process-exit semantics are understood; prompt wait → a defined resolution policy (answer, cancel, or persist). No clock may end a run silently or without a user-visible state. |
| **A-G5** | Multi-project semantics documented and tested: budget, stop-on-switch, run-in-flight deferral, third-start refusal, recovery from refusal. |
| **A-G6** | A run is observable and recoverable from inside the app without the chat screen being the owner of its state: state, last progress age, current tool, cancel/retry. D7 answered. |
| **A-G7** | Full JVM gate green at the new baseline; V2-15 suites unchanged or consciously revised; every new guard has a mutation run that fails when removed. |

## 9. Track B — release-readiness gate

Accepted only on the matrix D6 defines; unavailable rows stay `not-performed`
and block the gate.

| Gate | Criterion |
|---|---|
| **B-G1** | Device/API matrix executed row by row with captures and a manifest. |
| **B-G2** | Accessibility: TalkBack spoken labels (not just focus), focus order, 1.3×/2.0× font, RTL, contrast, touch targets. |
| **B-G3** | Storage/compatibility: the D5 choice is implemented and policy-cleared; SAF limits documented. |
| **B-G4** | CI: engine-inclusive lane or accepted equivalent with a stated coverage claim; the branch runs CI before merge. |
| **B-G5** | Security: Keystore behaviour, no plaintext credentials, redaction, backup rules, network config, dependency/secret scan. |

## 10. Stop conditions

Stop and return to the owner when:

1. A decision in section 6 is needed — do not implement a guess.
2. X1 shows the engine cannot support the chosen recovery policy.
3. A Track B device or API level is unavailable — record `not-performed`,
   never substitute an emulator or JVM result for a device claim.
4. An accepted V2-15 limitation would change meaning — that reopens
   acceptance rather than being fixed silently.
5. A fix would change the app-lock/Keystore model or the provider wire without
   a decision.

## 11. Limits of this register

- Static audit only: no tests, no device experiments, no upstream source read.
- R17, R18, R20, R21, R26, R31, R32 and R34 are the rows most likely to change
  after one measurement each; they are written so a measurement confirms or
  refutes them without renumbering.
- The register does not rank work beyond the track and the decision it blocks;
  milestone order belongs to the next step.
- No implementation, merge, PR, commit or push is authorized by this document.
