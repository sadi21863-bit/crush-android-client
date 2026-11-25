# V2-16 architecture and implementation plan

Date: 2026-11-25 (plan), 2026-11-26 (approval note). **Revision 3.1** —
Revision 3 plus a five-point addendum (section 16); Revision 3 and its review
history are preserved. Status: **approved as the V2-16 planning baseline on
2026-11-26**, with the three approval clarifications in section 17. It is
derived from the approved `V2-16-RISK-REGISTER.md` Revision 3.

**Revision history**

| Revision | Change |
|---|---|
| rev 1 | initial plan |
| rev 2 | first review: prompt provenance, eight-case background experiment, A5/A1 dependency, six clocks, six-cell matrix, three state dimensions |
| rev 3 | second review: recovery matrix no longer assumes engine survival; FGS feasibility becomes P0 evidence; liveness separated from progress; X3/X4 traceability; honest LMK definition; foundation vs long-horizon validation |
| rev 3.1 | addendum: answer idempotency/read-back (C6), crash-consistency ordering, stale-generation lease protection, tool-class progress policy, LH-1 harness requirements |
| approval | 2026-11-26: approved as planning baseline; three clarifications recorded in section 17 |
No code, test, workflow, commit, push, PR or merge was made to produce it.

Baseline: `v2-15-lifecycle-hardening` at `37ab5db` (12 ahead / 0 behind `main`
`adc26c0`). Engine of record `v0.100.0`. Last full gate: 117 suites / 1087
tests / 0 failures.

## 1. Purpose

V2-16 exists to make the foundation able to carry the product: **long-horizon
autonomous coding on a phone**. The register found that the foundation is sound
for foreground, single-project work but cannot yet claim that capability: there
is no background execution, a silent tool phase is capped at ~4 minutes, a
parked prompt can be orphaned by process death, execution state is owned by the
chat ViewModel, and there is no run-level observability.

This plan sequences the work to close those gaps, and separates the two things
that are routinely confused:

- **correctness and architecture** (can an execution run, survive, be observed,
  cancelled and recovered?), and
- **release readiness** (does it work on the devices, API levels, locales and
  distribution model we intend to ship?).

### 1.1 Goals

1. Execution is an object with a lifecycle, owned outside the chat screen.
2. A run can survive interruption according to an explicit, tested contract.
3. A parked prompt cannot be orphaned without a visible, bounded resolution.
4. A run is observable and recoverable from inside the app.
5. The device, accessibility, storage and CI evidence needed for a release
   decision exists, or is explicitly `not-performed`.

### 1.2 Non-goals

- Gate 7 (signing, listing, submission, rollout).
- New product surfaces unrelated to execution, observability or release.
- Reopening accepted V2-15 decisions (L1-L5, A7/A7b, A12/E1/E2/E5, the lock
  model, the engine of record).
- Rewriting the engine or changing the provider wire.

### 1.3 Definition of done

V2-16 is complete when **both** track gates are accepted. The gates are approved
separately; Track B acceptance does not imply Track A and vice versa. If Track A
changes runtime behaviour after Track B device evidence was captured, the
affected Track B rows must be re-run on the final build before release
readiness is claimed.

### 1.4 What V2-16 does not prove

V2-16 establishes the **execution foundation**: ownership, recovery, prompt
safety, observability, the clock contract and the release evidence. It does not
prove that the product can carry a long autonomous coding task end to end. That
is **LH-1** (section 15), a future milestone with task-level completion
measures. No Track A gate may be read as a long-horizon capability claim.

## 2. Principles

1. **The chat screen observes and controls executions; it does not own them.**
   Clearing the chat entry may stop observation; it must not decide the fate of
   an execution.
2. **Evidence before decision.** D1-D4 and D7 are decided from measurements, not
   from plausibility.
3. **A foreground service is not a process-death guarantee.** Sustained
   background execution (A5) and process-death recovery (A2/A3) are separate
   milestones with separate evidence. A notification keeps the OS from freezing
   a live process; it does not survive a force-stop, a kill, or an OEM cleaner.
4. **No clock may end a run silently.** Every terminal path is named, visible
   and, where applicable, server-acknowledged (register A-G4).
5. **One change, one reason.** No unrelated refactors, dependency upgrades or
   UI redesigns ride along.
6. **The accepted baseline is immutable.** V2-15 records and behaviour are
   preserved; deviations need an explicit decision.
7. **Three states, never one boolean.** Execution state, engine state and
   observation state are separate dimensions (see 5.2.1). Collapsing them
   recreates the current coupling under a new class name.
8. **The smallest sufficient architecture.** No speculative layer, no
   abstraction without a gate that depends on it. The execution layer earns its
   place only by the guarantees in this plan.
9. **Liveness is not progress.** A heartbeat, a live socket or a live process
   proves the transport, not that the agent advanced. Only execution or tool
   progress may reset an execution-progress budget.

Track B evidence has two classes. **Exploratory** evidence (device
reconnaissance, baseline probes) may be captured on any build and cannot be used
for acceptance. **Acceptance** evidence is captured on the A8 build, or
revalidated on it, and only that counts at the B gate.

## 3. Target architecture

```
 Compose UI ──observes state / dispatches intents──▶ ChatViewModel (adapter)
                                                          │
                                                          ▼
                                     ExecutionController  (process-scoped, AppContainer)
                                       owns: execution records, phases, prompts,
                                             cancellation, recovery, progress
                                       │            │              │
                                       ▼            ▼              ▼
                              WorkspaceManager  EngineSupervisor  ExecutionStore (Room)
                              (binding, lease,  (process start,    (durable record,
                               generation)       health, stop)      migration-tested)
                                       │
                                       ▼
                                  CrushApi / SSE
```

**Boundaries**

| Layer | Owns | Must not own |
|---|---|---|
| `ExecutionController` | execution identity, phase, prompt ownership, cancel/recover policy, progress timestamps | engine spawning, HTTP details, Compose state |
| `ChatViewModel` | projection of executions for the current screen, intent dispatch | execution lifetime, cancellation on clear |
| `EngineSupervisor` | process start/stop, health, restart budget | execution semantics |
| `WorkspaceManager` | workspace binding, lease, generation | run state |
| `ExecutionStore` | durable execution records, recovery facts | secrets, transcripts |

**Non-negotiable invariant:** `ChatViewModel.onCleared()` must not cancel a run
or discard a prompt. It may only unsubscribe. Cancellation is an explicit
intent from the controller's API.

## 4. Work structure

| Phase | Purpose | Production code? |
|---|---|---|
| **P0 — Evidence and decisions** | answer X1, R17, R18/R31, D2 memory, D7 design, FGS feasibility | no production code (device experiments, design notes, debug-only feasibility prototype) |
| **Track A — Execution core** | A1-A8 | yes, after each prerequisite decision |
| **Track B — Release readiness** | B0-B6 | only where the track requires it (storage, CI, a11y fixes) |

Track B can start during P0 (device acquisition, storage assessments, CI lane)
and does not wait for Track A, except for the revalidation rule in 1.3.

## 5. Milestones

### 5.1 Phase 0 — evidence and decisions

#### P0-E1 — Engine contract experiment (X1)

**Goal:** establish, against `v0.100.0`, whether a parked prompt is republished
after process death, whether cancel is idempotent, whether an answer submission
is idempotent or uniquely identifiable by read-back (addendum 16.1), and whether
an active run or open SSE suppresses the engine idle timeout.

**Questions, each answered yes/no/unknown with evidence:**

1. Is a parked permission or question republished after `current-session`?
2. Does the republished prompt carry the same batch/question ids?
3. Is `POST /questions/answer` idempotent for the same batch and answer?
4. Can a submitted answer be uniquely identified from history or agent state
   after a lost acknowledgement (which field, which endpoint)?
5. Is `cancel` idempotent, and does it succeed when the run no longer exists?
6. Does an active run or an open SSE suppress the engine idle timeout?

**Method:** the eight-step protocol in the register (section 7), run twice —
permission prompt and question prompt — plus a repeat where the original run no
longer exists. For question 4, submit an answer, drop the acknowledgement (kill
the client immediately after the POST), and attempt to identify it from
`messages`/agent state. Capture: `app.log`, engine log, process list, SSE frames.

**Deliverable:** `docs/v2/V2-16-ENGINE-CONTRACT.md` with raw evidence and a
yes/no/unknown answer per question.

**Resolves:** D3, and the engine half of D4 and R32; settles C6's options.
**Feeds:** A3, A4, A2.

**Exit:** every question has an evidence-backed answer. If a question remains
unknown after two attempts, stop and return to the owner.

#### P0-E2 — Background-behaviour measurement (R17, D1)

**Goal:** replace R17's "not guaranteed" with measured behaviour, using precise
kill mechanisms and a fixed record per case.

**Cases and mechanisms** (all on `bbf8ca69`, API 29; this establishes baseline
behaviour only and does not stand in for API 30+):

| Case | What is done | What it isolates |
|---|---|---|
| B1 screen off | screen off while the app stays foreground | display/CPU state with no process kill |
| B2 backgrounded | HOME while the run continues | cached-process state |
| B3 Doze | `dumpsys deviceidle force-idle` (un-force afterwards) | idle-mode restrictions |
| B4 app process killed | `am kill com.crush.mobile` while backgrounded (or the in-app SIGKILL if adb cannot signal it) | app death with the engine child possibly alive |
| B5 engine process killed only | in-app fault injection (`kill_engine`) | engine death with the app alive |
| B6 package force-stopped | `am force-stop com.crush.mobile` | app + children killed, restart blocked |
| B7 OEM cleaner | MIUI `OneKeyClean` (the mechanism observed in Phase F) | OEM policy kill |
| B8 LMK reclaim | only valid if an actual LMK kill is reproduced; `am send-trim-memory` alone is not one (see below) | low-memory kill |

**Recorded per case** — all five, no exceptions:

1. app process survival;
2. engine process survival;
3. whether the execution continues;
4. whether it is recoverable after relaunch;
5. whether a pending prompt remains actionable.

**Constraints:** one scripted run per case; AUTO_APPROVE only for the experiment,
restored to ASK afterwards; no case may be inferred from another; if a mechanism
cannot be applied on this ROM, say so and use the nearest honest substitute.

**B8 is held to a stricter standard.** `am send-trim-memory` delivers a
memory-pressure callback; it does not prove the low-memory killer reclaimed the
process. B8 records: trim callback delivered (yes/no); actual memory pressure
observed (yes/no); app killed (yes/no); kill attributable to LMK
(yes/no/unknown); mechanism reproduced (yes/no). If an actual LMK kill cannot be
induced, the case is `not-performed`; a trim experiment must never be labelled a
successful LMK test.

**Deliverable:** `docs/v2/V2-16-BACKGROUND-MEASUREMENT.md` with the case table,
raw logs and exact commands.

**Resolves:** D1. **Feeds:** A5 (which must repeat the relevant cases on API
30+), A-G2.

**Exit:** every case has the five recorded fields; `not-performed` is allowed
only with the attempted mechanism stated.

#### P0-E3 — Clock-behaviour measurement (R18, R31)

**Goal:** measure the silent-phase and large-repo behaviour before changing any
clock, and capture a **tool-class matrix** so D4 does not create one arbitrary
global timeout (addendum 16.4).

**Tool classes to measure** (at least one representative each): build, test
suite, package install/download, long-running shell, network request, codegen.
For each: expected frame/output cadence, longest observed silence while healthy,
whether the engine reports progress, and whether the app's 240 s budget fires.

**Method:** (a) each tool class above, recording frames, busy probes and whether
the app cancels; (b) time `captureBefore()` on a synthetic large tree and test
the 30 s send budget.

**Deliverable:** `docs/v2/V2-16-CLOCK-MEASUREMENT.md` with a per-class table.

**Resolves:** the numbers behind D4 and R31. **Feeds:** A4.

**Exit:** every measured tool class has a cadence/silence profile and a verdict
on whether the current budget falsely classifies it as stalled; snapshot cost
measured; no production change.

#### P0-E4 — Concurrency memory sample (D2, R11)

**Goal:** establish the real memory cost of live engines before widening the
budget.

**Method:** legacy + two project engines, idle and with a bootstrapped chat;
PSS per process; thread count; third-start refusal observed.

**Deliverable:** `docs/v2/V2-16-CONCURRENCY-MEASUREMENT.md`.

**Resolves:** D2. **Feeds:** A7.

**Exit:** numbers against the 3.7 GB device and the current 2-engine budget.

#### P0-E5 — Execution-layer design spike (D7)

**Goal:** turn D7 into a concrete, reviewable design before code.

**Deliverable:** `docs/v2/V2-16-EXECUTION-LAYER-DESIGN.md` covering: execution
identity, the three state dimensions below, controller API, ChatViewModel
projection, persistence schema and migration, **send-path crash-point table**,
**stale-generation validation**, cancellation semantics, recovery
reconciliation, and the test strategy for "survives chat clear".

**Required design content — three dimensions, never one boolean:**

| Dimension | Answers | Values (examples) |
|---|---|---|
| **Execution state** | what the agent is doing | idle, running, parked-permission, parked-question, cancelling, completed, failed, lost |
| **Engine state** | whether the process/connection is alive | process alive/dead, socket up/down, generation |
| **Observation state** | whether a UI is subscribed and current | subscribed/unsubscribed, last delivered event, lag |

The design must hold these invariants explicitly:

- an execution can be **running** with no observer;
- the engine can be **alive** while the execution is **parked**;
- an execution can be **complete** while the UI has not yet received its final
  event;
- the UI can be **disconnected** while the execution remains recoverable.

**Crash-consistency (addendum 16.2).** The design must fix the send-path
ordering and define recovery for every crash point:

1. persist intent → 2. send request → 3. record engine acknowledgement →
4. advance to running.

| Crash between | On recovery |
|---|---|
| before 1 | nothing persisted; no action |
| 1 and 2 | intent recorded, never sent → mark unsent; **no automatic send** |
| 2 and 3 | request may have been accepted; ack not recorded → ambiguous send; identify via history if possible, otherwise a visible ambiguous state; **no automatic duplicate send** |
| 3 and 4 | run id recorded, state stale → resume as running |
| after 4 | normal recovery |

**Stale-generation protection (addendum 16.3).** A recovered execution must
validate engine instance identity, workspace path, lease and generation as one
atomic check before attaching. A generation mismatch must not attach to the new
engine as if it were the old one: it reconciles history or marks the execution
lost, and old prompts are never inherited.

**Resolves:** D7. **Feeds:** A1-A7.

**Exit:** owner approves the design (the A1 checkpoint); no production code.

#### P0-E6 — Foreground-service feasibility (D1 input)

**Goal:** establish whether a foreground service is a legally and technically
valid execution mechanism for this workload **before** D1 is decided and before
A5 is designed. A service with a persistent notification is not automatically a
viable execution host.

**Questions, per API level (29, 33/34, 35+):**

1. Which `foregroundServiceType` is legitimate for agent execution, if any?
   (`dataSync` is not assumed; convenience is not eligibility.)
2. Which permissions does each type require on each level
   (`FOREGROUND_SERVICE`, type-specific, `POST_NOTIFICATIONS` on 33+)?
3. What background-start restrictions apply when the start follows a user action
   versus a background event?
4. Which newer execution limits apply (Android 14/15 service timeouts,
   while-in-use restrictions, OEM variants)?
5. What does Play policy require for the chosen type and for this use?
6. What happens when the user denies the notification permission on 33+?

**Method:** platform documentation and behaviour checks, plus a debug-only
prototype service (not shipped, not wired to production) if needed to observe
start/stop behaviour. No production service is written in P0.

**Deliverable:** `docs/v2/V2-16-FGS-FEASIBILITY.md` with a per-type, per-API
verdict table: viable / not viable / unverified, with the reason.

**Feeds:** D1 and A5. **Exit:** if no type is legitimately viable, D1 records
foreground-only (or another mechanism); an invalid foundation is not built.

### 5.2 Track A — execution core

#### 5.2.1 The six-cell recovery matrix (A-G1)

This matrix is the executable definition of A-G1. It does **not** assume the
engine survives the death of its parent: that is a measurement (P0-E2/X1), not
an architectural expectation. The survivor column is a hypothesis; the required
behaviour is what the app must do in every case the measurement allows.

**Situation → required application behaviour**

| Situation | Required behaviour |
|---|---|
| Engine survives app death | Reattach, reconcile, then continue or finish observation |
| Engine died, session history remains | Recover what can be reconstructed; never invent events |
| Engine died, execution cannot resume | Mark the execution **lost** visibly; preserve the transcript that exists |
| Engine survived but prompt identity is ambiguous | Never submit an answer automatically; surface the ambiguous state |

**The six cells.** "Killed" means the app process (`am kill com.crush.mobile`)
unless stated; force-stop and OEM kills are the separate axis measured in
P0-E2. Every cell records the five P0-E2 facts plus whether the engine survived.

| Cell | What is killed | Survivor hypothesis (from P0-E2) | Required app behaviour | Permitted degraded outcome | Pass criterion |
|---|---|---|---|---|---|
| C1 idle | app, no run in flight | engine may be alive or already gone | no execution record to resume | none | clean start: no phantom run, no empty session minted |
| C2 mid-turn silent | app while the run streams but frames are quiet | engine alive **or** dead — not assumed | alive → reattach + reconcile; dead with history → reconstruct; neither → mark lost | execution lost but transcript visible as far as it exists | outcome is one of the three, never a silent disappearance |
| C3 mid-tool | app while a tool executes | engine alive with the tool, or dead — not assumed | same as C2 plus reconcile the tool row from history | tool status may be unknown but is shown as such | no duplicated tool execution; transcript matches the engine or is visibly incomplete |
| C4 parked permission | app while a permission prompt is open | engine parked if alive; may be dead | alive + correlated → D3 policy; dead → lost visibly; ambiguous → no auto-answer | prompt remains unresolved but visible and bounded | no automatic answer to an ambiguous prompt; no silent orphan |
| C5 parked question | app while a question batch is open | same as C4 | same as C4, preserving batch identity | same as C4 | same as C4 |
| C6 after Submit, before ack | app between the answer POST and its acknowledgement | engine may or may not have accepted | history shows the answer → finalize; unclear → ambiguous submission requiring visible user action, never a silent retry | duplicate-safe retry or visible ambiguity | no duplicate answer; any retry is justified by idempotency or unique read-back (addendum 16.1); otherwise the outcome is an explicit ambiguous submission |

If P0-E2 shows engine survival is unstable, each cell is run twice: once with
the engine alive, once with a forced engine kill. Every cell records the five
P0-E2 facts plus engine survival, and passes only when its pass criterion is
observed — not when no crash occurred.

#### A1 — Execution core and ownership

**Depends on:** D7 approved (P0-E5), plus the **A1 design checkpoint**: the
execution-layer design is reviewed and approved before code starts.
**Resolves:** R34, R19, R21 (ownership half).
**Deliverables:** a process-scoped `ExecutionController` in the container; the
three state dimensions of P0-E5 kept separate (execution, engine, observation);
a `StateFlow` the ViewModel projects; an intent API (start, cancel, answer,
retry); `ChatViewModel.onCleared()` reduced to unsubscribe; no engine cancel
from the UI layer. `EngineSupervisor` remains the only process supervisor — the
controller observes it, it does not duplicate or replace it.
**Verification:** unit tests for every transition; tests for the four E5
invariants (running without observer, alive while parked, complete before
delivery, disconnected yet recoverable); a test proving state survives
`onCleared()`; an instrumented test that clears the chat entry mid-run and
observes the execution continue; cancellation only through the intent API.
**Exit:** no execution lifetime decision remains in the ViewModel, and the
three dimensions cannot be collapsed into one `isRunning` flag.

#### A2 — Durable execution records and process-death recovery

**Depends on:** A1; D3 policy (P0-E1) for the prompt half.
**Resolves:** R23, R3, R24, R10 (recovery path), A-G1.
**Deliverables:** an `ExecutionStore` record (executionId, engine key, workspace
path, session id, engine generation, phase, last progress, prompt reference,
timestamps); additive Room migration with a migration test; startup
reconciliation (reattach → query agent/history → finish, resume, resolve prompt
or mark lost); a visible "recovered/lost" outcome.
**Verification:** the six cells of 5.2.1, one run per cell; crash-point fault
injection at each of the four send-path boundaries (addendum 16.2); a
stale-generation test where the engine restarts and reuses the path (addendum
16.3); no secrets in the record; migration test from the current schema.
**Exit:** every cell has a defined, observed outcome.

#### A3 — Prompt identity and orphan policy

**Depends on:** A1, P0-E1 results, D3.
**Resolves:** R1, R2, R20, R4 (confirm/no endpoint), E3/E4 closure or restated.

**Identity provenance — never claim more than the protocol carries.** A3 starts
from the X1 wire capture and classifies every field:

| Field | Source | Status |
|---|---|---|
| question batch id, question ids | engine | observed on the wire |
| session id on the prompt frame | engine | verified in X1; if absent, recorded |
| tool call id | engine | observed on the wire |
| execution id (run id) | app, minted at send | correlated by the app, never confirmed by the engine |
| engine instance key, workspace path | app | durable |
| engine generation / lease | app-derived from engine observations | validated on reattach |

**Correlation rule.** A prompt is attached to an execution only when the engine
supplies a session id that matches exactly one execution the app believes is
active on that session, and the engine instance and generation still match. If
the frame has no session id, if two executions could own it, or if the
generation changed, the prompt is **ambiguous**: the app must not answer it or
treat it as belonging to the current session. It exposes an ambiguous-prompt
state and takes only an action that does not require ownership — a workspace-
level cancel that releases the run, or leaving it to the engine ceiling while
showing the state.

**Deliverables:** the provenance table filled from X1; the D3 policy
implemented; explicit ambiguous handling; a terminal state for cancel and
answer; no identity claim the engine does not support.
**Verification:** the X1 scenarios repeated post-implementation; a prompt with
no session id and one with a stale generation both land in ambiguous handling,
not in the current session; an answer whose acknowledgement was lost lands in
the addendum-16.1 outcome (idempotent retry, read-back confirmation, or an
explicit ambiguous submission); duplicate answer/cancel rejected; a prompt whose
run no longer exists resolves visibly.
**Exit:** zero orphaned prompts in the matrix; E3/E4 either closed or recorded
as limitations with the exact missing field named.

#### A4 — Execution contract and clocks

**Depends on:** A1, P0-E1, P0-E3, D4.
**Resolves:** R18, R21 (gap recovery), R31, R32, A-G4.

**All six clocks, accounted for.** One contract, but no clock disappears:

| # | Clock | Owner | Starts | Resets | Terminal behaviour | Acknowledgement | Disposition |
|---|---|---|---|---|---|---|---|
| 1 | Silent interval | app | after the last frame while streaming | on any frame | app requests cancel | ack where received; failure recorded | retained, bounded by D4 |
| 2 | Tool execution interval | engine policy (unknown), app observes | when a tool starts | on tool progress | engine-defined; app shows a named wait | none today | retained; no app cancel unless D4 chooses |
| 3 | Total run duration | **unestablished** (app has none; engine unknown) | when the run is accepted | never | unknown until D4 names the enforcement point | n/a | retained as a requirement; D4 names the enforcement |
| 4 | Engine idle timeout | engine, value chosen by app | when the engine considers itself idle | per X1 | process exits | n/a | retained, set so it cannot kill a live run |
| 5 | Parked-prompt wait | app | when a prompt is shown | on answer/cancel or a stray frame | D3 policy: cancel, persist or republish | workspace cancel ack where used | retained, redefined by D3 |
| 6 | Send/startup | app | at the send intent | n/a | named `send timed out` | none | **split**: snapshot gets its own bound; the POST keeps a dedicated budget |

**Liveness is not progress — five signals.** The contract must keep them apart:

| Signal | What it proves | Source | Resets the execution-progress budget? |
|---|---|---|---|
| Connection liveness | the SSE/socket is up | transport | **no** |
| Engine liveness | the process is alive and `/v1/version` answers | supervisor/health probe | **no** |
| Execution progress | the agent advanced (new message, tool, plan or phase) | engine frames | **yes** |
| Tool progress | the active tool advanced (output or status) | tool frames | **yes** |
| Observation freshness | a UI received current state | controller/observer | **no** |

Rules:

- a heartbeat or keepalive resets **connection liveness only**;
- only execution or tool progress may reset the execution-progress budget;
- a bounded heartbeat may extend liveness, never indefinitely extend execution
  progress: a live engine with no progress for the budget is a **named stall**;
- stale observation never changes execution state.

**Deliverables:** the clock contract above implemented as one state machine;
per-clock terminal behaviour; a **per-tool-class wait policy** (D4, addendum
16.4) where each class has a named bounded wait and a no-progress class is shown
as awaiting, not stalled; progress detection using engine progress events if X1
found them, otherwise a bounded liveness heartbeat **plus an independent
execution-progress budget that the heartbeat cannot reset**; the workspace
snapshot outside the send budget; SSE gap handling with a defined outcome.
**Verification:** fake-clock tests for every terminal path; a stall test where
the connection and engine stay alive but no progress occurs; a silent-phase test
per tool class against its own policy; a forced stream drop mid-turn; a
large-repo send test that proves the snapshot bound.
**Exit:** every clock has the disposition in the table, no heartbeat can mask a
stalled execution, none ends a run silently, and a 20-minute task completes or
fails with a named reason.

#### A5 — Sustained background execution

**Depends on:** P0-E2 results, P0-E6 and D1; **A1 for production integration** —
the service must host the controller's active execution, never own a second one.
Research, manifest feasibility checks and service prototyping may start earlier;
no production wiring before A1 exists.
**Resolves:** R17, A-G2; supports R24.
**Deliverables (depending on D1):** (a/c) foreground service with notification,
`foregroundServiceType`, `POST_NOTIFICATIONS` on API 33+, start/stop bound to an
active execution, user-visible stop action; (b) a foreground-only product
constraint implemented as a visible interruption, not a silent death.
**Verification:** the relevant P0-E2 cases repeated post-implementation on API
29 **and at least one API 30+ device from D6** (background rules differ by API);
a defined duration with screen off; notification and stop behaviour; battery
sample.
**Exit:** A-G2 satisfied for the chosen option, with API-level-specific
evidence. **One owner only:** the service hosts the controller's execution; if
it starts or stops runs on its own, A5 fails review. **Explicitly not claimed:**
survival of a process kill — that is A2.

#### A6 — Run observability

**Depends on:** A1.
**Resolves:** R25, R26.
**Deliverables:** an execution surface showing state, last-progress age, current
tool, parked prompt and cancel/retry; a diagnostics run timeline (execution id,
engine generation, last frames, terminal reason); memory profile for a long
session with a cap/paging decision.
**Verification:** a deliberately stalled run is identified and recovered from
the app without adb; a 10-hour-session profile shows bounded memory or an
explicit cap.
**Exit:** A-G6 satisfied; "stuck" is distinguishable from "thinking".

#### A7 — Concurrency semantics

**Depends on:** P0-E4, D2.
**Resolves:** R22, R11.
**Deliverables:** the documented envelope enforced; a refused third start has an
actionable recovery path (stop a background engine); run-in-flight switching
tested; budget widened only if D2 says so and memory supports it.
**Verification:** tests for reservation, refusal, recovery and switch-with-run;
device check of the refusal message.
**Exit:** A-G5 satisfied.

#### A8 — Regression, mutation and Track A evidence

**Depends on:** A1-A7.
**Deliverables:** mutation runs for every new guard (execution identity, prompt
policy, clock terminal paths, recovery); full JVM gate; the V2-15 contract
suites unchanged and green; a consolidated Track A evidence document.
**Verification:** full gate ≥ the V2-15 baseline; each mutation fails the guard's
test when removed; no V2-15 test weakened.
**Exit:** A-G1…A-G7 evidenced; Track A gate review.

### 5.3 Track B — release readiness

#### B0 — Release matrix and device acquisition

**Depends on:** none (starts immediately).
**Resolves:** D6.
**Deliverables:** the matrix (API 29 + the API levels D6 names, phone + tablet/
foldable, portrait/landscape, one RTL locale) and the hardware or farm booking.
**Exit:** matrix approved; devices available or rows marked `not-performed`.

#### B1 — Matrix execution

**Depends on:** B0; A8 for acceptance evidence.
**Resolves:** R7, R28, B-G1.
**Evidence classes:** exploratory runs on the current baseline are allowed now
(device reconnaissance, API behaviour, capture tooling) and are labelled
exploratory — they cannot be cited at the gate. Acceptance evidence is captured
on the A8 build, or the affected rows are re-run on it.
**Deliverables:** row-by-row captures + manifest, each row labelled exploratory
or acceptance; explicit `not-performed` rows.
**Exit:** every row has acceptance evidence or a reason.

#### B2 — Accessibility

**Depends on:** stable UI (A6 changes the execution surface).
**Resolves:** R5, R7 (a11y half), B-G2.
**Deliverables:** TalkBack spoken labels (not just focus), focus order, 1.3× and
2.0× font, RTL, contrast, touch targets; fixes where they fail.
**Exit:** B-G2 checklist signed with captures.

#### B3 — Storage and distribution (D5)

**Depends on:** the four assessments; X4 for A1 only (see 7.1).
**Resolves:** R27, B-G3.
**Deliverables:** A1 policy answer; A2 app-managed-workspace assessment; A3
SAF/FUSE feasibility; A4 UX assessment; then the chosen model implemented and
the Play declaration prepared. If X4 is inconclusive, broad access is not
selected and A2-A4 continue.
**Exit:** B-G3 satisfied.

#### B4 — Engine-inclusive CI

**Depends on:** X3 (see 7.1).
**Resolves:** R14, R29, B-G4.
**Deliverables:** a lane that runs at least one engine-inclusive suite, or an
accepted equivalent with a stated coverage claim; the branch runs CI before any
merge request. The owner, evidence and inconclusive path are in 7.1.
**Exit:** B-G4 satisfied.

#### B5 — Security verification

**Depends on:** A8 (final build).
**Deliverables:** Keystore behaviour, no plaintext credentials, redaction of
logs/reports, backup rules, network config, dependency and secret scan.
**Exit:** B-G5 satisfied.

#### B6 — Device-automation robustness

**Depends on:** none.
**Resolves:** R6, R16, R30.
**Deliverables:** scripted foreground unblock, kill-tolerant runs, the blocked
recreation test either made deterministic or explicitly excluded.
**Exit:** device runs are repeatable enough to support B1/B2/B5.

## 6. Sequencing

```
P0-E1 ─┬─▶ D3 ─────────────────▶ A3 ─┐
       ├─▶ D4 ◀─ P0-E3 ─────────▶ A4 ─┤
       └─▶ R32 ───────────────────────┤
P0-E4 ───▶ D2 ─────────────────▶ A7 ─┤
P0-E5 ───▶ D7 ─▶ A1 ─┬─▶ A2 ─────────┤
                      ├─▶ A6 ─────────┤
                      └─▶ A5 ◀─ D1 ◀─ P0-E2 + P0-E6
                                       └─▶ A8 ─▶ Track A gate
B0 ─▶ B1/B2/B3/B4/B5/B6 ─▶ Track B gate
```

Parallelism: P0-E1…E5 are independent; A5 and A6 can run beside A2/A3/A4 once
their dependencies are met; Track B runs in parallel with Track A and is
revalidated on the A8 build.

## 7. Decision log (D1-D7)

| ID | Status | Resolved by | Must be decided before | Owner input |
|---|---|---|---|---|
| D1 background strategy | open | P0-E2 + P0-E6 | A5 | choose (a) FGS, (b) foreground-only, (c) hybrid, from a viable service type |
| D2 concurrency envelope | open | P0-E4 | A7 | accept/raise the 2-engine budget |
| D3 prompt durability | deferred | P0-E1 | A3 | choose from evidence |
| D4 long-run contract | open | P0-E1 + P0-E3 | A4 | accept the contract, its numbers and a per-tool-class wait policy (16.4) |
| D5 storage model | deferred | B3 A1-A4 (+X4 for A1) | B3 implementation | choose the model |
| D6 release matrix | open | B0 | B1 | define devices/API/locales |
| D7 execution ownership | open | P0-E5 | A1 | approve the execution-layer design |

No milestone may encode a guess for an open decision.

### 7.1 Dependency activities and traceability

X1-X4 are scheduled activities, not footnotes. Each has an owner, an evidence
artifact, a needed-by point and a defined outcome if inconclusive.

| ID | Register definition | Scheduled as | Performed by | Evidence | Needed by | If inconclusive |
|---|---|---|---|---|---|---|
| X1 | Engine contracts: prompt republish, cancel idempotency, idle semantics | P0-E1 | implementation owner | `V2-16-ENGINE-CONTRACT.md` + raw logs | before A3 and A4 | D3 stays deferred; conservative policy (no auto-answer, cancel/release path); escalate to owner |
| X2 | Devices: API 30+, tablet/foldable, RTL | B0 | release owner | device list or booking | before B1 | affected rows `not-performed`; B-G1 blocked |
| X3 | CI runtime lane | B4 | infra owner | workflow plus one green engine-inclusive run | before B4 exit | accepted equivalent with a stated coverage claim, or B-G4 not met |
| X4 | Play policy: All files access | B3-A1 | release owner | policy answer with citation | before the D5 choice | D5 cannot choose broad access; B3 A2-A4 proceed in parallel |

## 8. Risk-to-milestone traceability

| Risk | Milestone | Risk | Milestone |
|---|---|---|---|
| R1 E3 | A3 | R18 silent phase | P0-E3, A4 |
| R2 E4 | A3 | R19 best-effort cancel | A1, A4 |
| R3 prompt persistence | A2, A3 | R20 orphan prompt | A3 |
| R4 read-back | A3 (confirm; likely stays accepted) | R21 SSE gap | A1, A4 |
| R5 free-text test | A8 | R22 concurrency | P0-E4, A7 |
| R6 blocked recreation test | B6 | R23 run persistence | A2 |
| R7 device matrix | B1, B2 | R24 orphan engine | P0-E1, A2 |
| R8 A2 modal | not scheduled — monitoring | R25 observability | A6 |
| R9 A6 dual meaning | monitoring; revisit if it blocks A1 | R26 transcript memory | A6, A8 |
| R10 A13 disposal | A2, A5 | R27 storage | B3 |
| R11 PSS/threads | P0-E4, A7 | R28 API 30+ | B1 |
| R12 version | resolved (V2-A20) | R29 engine CI | B4 |
| R13 issue #2 | deferred, not scheduled | R30 MIUI harness | B6 |
| R14 CI | B4 | R31 send budget | P0-E3, A4 |
| R15 Gate 7 | out of scope | R32 idle semantics | P0-E1, A4 |
| R16 MIUI | B6 | R33 upstream contracts | P0-E1 |
| R17 background | P0-E2, A5 | R34 UI-scoped tracking | A1 |

Every unresolved row has a milestone or an explicit non-schedule reason. No row
is silently dropped.

## 9. Verification and evidence standard

- **Unit/JVM:** every guard has a deterministic test; mutation runs for the
  critical ones (execution identity, prompt policy, clock paths, recovery).
- **Instrumented/device:** every device claim names build, device, API, steps,
  log and capture; stored under `docs/parity/v2-16-*` and `v2-16-*.log`.
- **Contract tests:** X1 behaviours are pinned by tests so a future engine bump
  fails loudly.
- **No overwriting:** V2-15 evidence and records are append-only; new records
  supersede, they do not rewrite.
- **Labels:** every claim carries CODE / TEST / DEVICE / RECORD / UNVERIFIED
  (V2-A6). `not-performed` is a valid result.

## 10. Acceptance gates

### Track A gate (A-G1…A-G7)

| Gate | Evidence required |
|---|---|
| A-G1 | The six cells of 5.2.1, each with its pass criterion observed (not merely "no crash") |
| A-G2 | D1 option demonstrated: sustained background **or** controlled interruption |
| A-G3 | Prompt policy implemented; zero orphans in the X1 matrix |
| A-G4 | Per-clock terminal behaviour; no silent end; named failures |
| A-G5 | Concurrency envelope tested incl. refusal and recovery |
| A-G6 | Stuck run identified/recovered in-app without adb |
| A-G7 | Full gate green; mutation evidence; V2-15 suites intact |

### Track B gate (B-G1…B-G5)

| Gate | Evidence required |
|---|---|
| B-G1 | Matrix executed row by row; `not-performed` listed |
| B-G2 | Accessibility checklist with captures |
| B-G3 | Storage model implemented and policy-cleared |
| B-G4 | Engine-inclusive CI or accepted equivalent |
| B-G5 | Security verification with logs/scans |

Independent approval. B rows run on the A8 build or are revalidated on it.

## 11. Stop conditions

1. A decision in section 7 is still open when its milestone would start.
2. X1 is inconclusive after two attempts.
3. The chosen D1 option cannot be implemented without a product-visible change
   the owner has not accepted (e.g., a persistent notification).
4. A device/API row is unavailable — mark `not-performed`; never substitute a
   JVM or emulator result.
5. A change would alter the accepted V2-15 lock/Keystore model, provider wire or
   L1/L2/L3 semantics without a decision.
6. A milestone's evidence contradicts the register (for example, parked prompts
   are never republished and no app-side persistence is feasible) — return to
   planning, do not improvise.

## 12. Protection of the accepted V2-15 baseline

- Work happens on a new branch created from `37ab5db` when implementation is
  authorized; `main` stays at `adc26c0` until a separately approved merge.
- No V2-15 document, capture or log is edited in place; new records supersede.
- The following contracts must remain green and unchanged in meaning: L1
  shutdown coverage, L2 teardown isolation, L3 connection supersession, A7/A7b
  permission fail-closed, A12/E1/E2/E5 question lifecycle, the lock flow and
  the Keystore model, and the engine of record `v0.100.0`.
- The 1087-test JVM gate is the floor, not a target; a lower count fails A-G7.
- Dependency additions are decisions: the execution layer must not pull in a new
  library (for example a process-lifecycle artifact) without an explicit note in
  the decision log.
- Room changes are additive migrations with a migration test; no destructive
  fallback.

## 13. What this plan does not authorize

No implementation, no Phase 0 experiment, no code, no branch, no commit, no
push, no PR, no merge and no change to `main`. LH-1 is neither authorized nor
scheduled here. The next step is review of this revised plan; Phase 0 and
implementation start only after that review is approved explicitly.

## 14. Review record and approval status

The first review answered the four questions and required six corrections; this
revision also incorporates the second review's six corrections.

| Review item | Where addressed |
|---|---|
| Prompt identity may be impossible to guarantee | A3 provenance table + ambiguity rule |
| Process-death experiment underspecified | P0-E2 eight cases, five recorded fields |
| A5 missing an A1 dependency | A5 dependency + one-owner rule |
| Clock contract incomplete | A4 six-clock table with dispositions |
| Six-cell matrix not reproduced | 5.2.1, referenced by A2 and A-G1 |
| Execution / engine / observation states collapsed | P0-E5 design table + A1 invariants |

Second review (Revision 3):

| Review item | Where addressed |
|---|---|
| Recovery matrix assumed engine survival | 5.2.1 rewritten: survivor is a hypothesis, required behaviour and degraded outcomes defined |
| Foreground service assumed viable | P0-E6 feasibility; D1 and A5 gated on a legitimate service type |
| Heartbeat conflated with progress | A4 five-signal table; heartbeat cannot reset the progress budget |
| X3/X4 not traceable | 7.1 activities with owner, evidence, needed-by, inconclusive path |
| LMK test overstated | P0-E2 B8 stricter standard; a trim callback is not an LMK kill |
| Foundation mistaken for capability | 1.4 and LH-1 (section 15) |

Revision 3.1 addendum (section 16):

| Point | Where |
|---|---|
| C6 needs an idempotency or read-back guarantee | 16.1, P0-E1 question 4, C6 criterion |
| Room crash-consistency ordering | 16.2, P0-E5 crash-point table, A2 fault injection |
| Stale-generation lease protection | 16.3, P0-E5, A2 generation test |
| Tool-specific progress policy | 16.4, P0-E3 classes, D4, A4 |
| LH-1 measurable harness | 16.5, section 15 |

Recorded answers:

1. P0 evidence first — approved.
2. A1 with a design approval checkpoint — approved.
3. Track B device testing may start now, but exploratory and acceptance
   evidence are distinct (B1).
4. Independent gates with revalidation on the A8 build — approved.

**Approval status: pending.** Neither implementation nor the Phase 0 experiments
are authorized until this revision is approved as a whole.

## 15. Future milestone — LH-1 long-horizon execution validation

Not part of V2-16. Not scheduled, estimated or authorized by this plan.

V2-16 proves the foundation; LH-1 is the only milestone allowed to claim the
product vision: long-horizon autonomous coding on a phone.

**Scenario** — a realistic task with measurable completion criteria, not a
duration:

1. Explore a repository and identify the relevant files.
2. Make multiple related file modifications.
3. Run the build and test suite.
4. Encounter at least one tool failure and retry.
5. Encounter at least one permission or question interruption.
6. Lose observation (background, screen off, process death) and recover.
7. Continue to completion.
8. Verify the final repository state.

**Measures**, defined before the run and recorded after:

| Measure | Question |
|---|---|
| Objective completion | did the task meet its stated criteria? |
| Work loss | was correct work lost or reverted? |
| Duplication | were edits, tool calls or sends duplicated? |
| Human intervention | how many interventions, and why? |
| Recovery correctness | after each interruption, was state correct and did the task continue? |
| Final repository state | does the diff plus tests match the objective? |

**Harness requirements** (addendum 16.5):

- a baseline commit plus a workspace snapshot taken before the run;
- a task specification naming expected files and behaviour;
- build/test acceptance criteria for the final state;
- diff inspection against the baseline — unrelated changes fail the run;
- reproducible execution logs (execution id, timestamps, tool calls);
- a scoring sheet filled from the six measures.

**Entry criteria:** Track A accepted; at least one API 29 and one API 30+
device; a task harness that can verify the final state; observability sufficient
to score the measures.

**Exit:** a report with every measure, pass thresholds agreed before the run,
and an explicit list of what the foundation still cannot do.

## 16. Revision 3.1 addendum — five binding clarifications

These five points bind the milestones they name. They do not change the
architecture, the tracks or the gates; Revision 3 remains intact above.

### 16.1 Answer-submission idempotency and read-back

**Binds:** P0-E1, A3, C6.

An answer POST whose acknowledgement is lost has exactly three possible
outcomes:

1. the endpoint is **idempotent** for the same batch and answer → a retry is
   safe;
2. the submission is **uniquely identifiable** by read-back (history or agent
   state exposes the exact answer) → confirm and finalize;
3. neither → the submission is **ambiguous** and requires a visible user
   decision; it is never retried automatically.

P0-E1 question 4 settles which case holds; C6's pass criterion requires the
matching outcome; A3 implements and tests it. "Retry-safe" is not assumed.

### 16.2 Crash-consistency ordering

**Binds:** P0-E5, A2.

The send path is ordered: persist intent → send request → record engine
acknowledgement → advance to running. The crash-point table is in P0-E5. The
binding rules:

- never send before the intent record is durable;
- never automatically re-send an ambiguous send;
- never advance the state before the acknowledgement is recorded.

A2 injects a crash at each of the four boundaries and proves the recovery row.

### 16.3 Stale-generation lease protection

**Binds:** P0-E5, A2.

A recovered execution validates engine instance identity, workspace path, lease
and generation as **one atomic check** before attaching. A generation mismatch
must not attach to the new engine as if it were the old execution: it reconciles
history or marks the execution lost, and it never inherits the old prompts. A2
tests an engine restart that reuses the same path.

### 16.4 Tool-class progress policy

**Binds:** P0-E3, D4, A4.

A single global silence budget is not acceptable. P0-E3 captures build, test,
install/download, long shell, network and codegen classes; D4 defines a bounded,
named wait per class; A4 tests each class against its own policy. A class with
no observable progress is shown as **awaiting under its named policy**, not as
stalled, and never becomes an unbounded wait.

### 16.5 LH-1 harness

**Binds:** section 15 only; not a V2-16 blocker.

LH-1 requires a baseline commit, a task specification, expected files and
behaviour, build/test acceptance criteria, diff inspection against the baseline,
reproducible execution logs and a scoring sheet. Without them, a run cannot be
scored and the capability is not proven.

## 17. Approval review note (2026-11-26) — three clarifications

Revision 3.1 is approved as the V2-16 planning baseline with these three
clarifications recorded. The Revision 3.1 content above is not rewritten; these
clauses bind where they say they bind.

### 17.1 "Atomic" generation validation — implementation definition

**Binds:** P0-E5, A2 (extends 16.3).

"One atomic check" means validation and attachment happen in the **same
critical section** that guards engine replacement. A plain read-then-attach is
explicitly insufficient:

```
read generation → (engine replaced here) → attach   // FORBIDDEN
```

The design must name the mechanism, one of:

- hold the existing per-path `WorkspaceManager` mutex across validate + attach,
  with every engine replacement (stop/start/re-attach) taking the same mutex;
- reserve the runtime/generation in the registry (or a new execution lease) so
  the instance cannot be replaced between check and attach, and consume the
  reservation at attach.

The design must also state the lock order, the failure path (generation changed
→ abort attach, reconcile or mark lost) and the test that interleaves a restart
between validation and attachment. **Test requirement:** a deterministic hook
(latch/barrier) forces the restart in that window; the attach must fail or
restart the check, never attach to the new engine.

### 17.2 Send-path guarantee — what is and is not promised

**Binds:** P0-E5, A2, A3, C6.

The crash-consistency ordering does not create exactly-once execution. The
three levels stay distinct in implementation, tests and documentation:

| Level | Achievable | Mechanism | Not claimed |
|---|---|---|---|
| Durable local intent | yes | Room commit before the POST | that the engine received it |
| Confirmed engine acceptance | only with evidence | acknowledgement, or unique read-back (16.1) | acceptance when evidence is absent |
| Exactly-once submission | **no, without protocol support** | requires engine idempotency or reliable read-back | never inferred from local state |

The ambiguous window between "sent" and "acknowledgement recorded" therefore
remains a first-class state. Tests must prove the state machine exposes it; no
test may assert exactly-once.

### 17.3 FGS investigation — exact API scope

**Binds:** P0-E6, D1, A5, D6.

A "35+" bucket is not acceptable for a 2026 release decision. P0-E6 records, at
minimum:

- the exact `minSdk` (29), `targetSdk` (36) and `compileSdk` (37) of the build
  under test;
- each API level named by D6, at minimum **29, 33, 34, 35 and 36**, with its own
  row — no bucket;
- per level: permitted foreground-service types, required permissions,
  background-start eligibility, timeouts/execution limits, notification
  requirements;
- the Play policy requirement per candidate type, with the source and date;
- a verdict per level: viable / not viable / unverified.

If a D6 level is not investigated, D1 cannot be decided for that level and the
gap is recorded as unverified.

## 18. Approval record

| Item | State |
|---|---|
| `V2-16-RISK-REGISTER.md` Revision 3 | approved 2026-11-25 as the planning baseline |
| `V2-16-PLAN.md` Revision 3.1 | approved 2026-11-26 as the planning baseline, subject to section 17 |
| Docs-only commit | authorized and performed on `v2-16-planning-baseline` from `37ab5db` |
| Phase 0 experiments | **not authorized** |
| Implementation | **not authorized** |
| D1-D7 | unchanged: open or deferred until their evidence and owner decision |
| `main` | untouched at `adc26c0` |

Approving the plan does not approve any technical decision inside it (D1-D7),
and it does not authorize Phase 0. Experiments begin only under a separate,
explicit authorization.
