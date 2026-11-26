# V2-16 P0-E5 — Execution-layer design spike (D7)

Date: **2026-11-26**. Branch `v2-16-planning-baseline`, head `9192bc5`.
**Design proposal only.** No production code was written, no Room migration was
applied, no foreground service was added, and **D7 is not finalized** — it is
presented for owner approval at the A1 checkpoint, as the plan requires.

The owner switched the app's provider to OpenRouter during this spike. The
design is provider-agnostic; no provider id or credential appears here or in
any evidence file.

## 0. Evidence this design must satisfy

| Source | Finding that constrains the design |
|---|---|
| P0-E1 (`V2-16-ENGINE-CONTRACT.md`) | Parked prompts are republished to a **new client on a live engine**, with the same batch/question/tool-call ids. Cancel/answer are same-request replay-safe. Read-back is textual and proven only for `yes_no`. Lost-ack recovery is strongly supported, **not** fault-injected. Exactly-once is **not** established. Engine/workspace ids die with the engine; only path is durable. |
| P0-E2 (`V2-16-BACKGROUND-MEASUREMENT.md`) | HOME / screen-off / forced Doze did not kill app or engine. App/package/OEM death kills **both**, so recovery cannot assume a surviving engine. A killed turn gets an explicit **Interrupted/Retry** state. **A project switch cancels the run while the engine stays alive** — the central problem. |
| P0-E3 (`V2-16-CLOCK-MEASUREMENT.md`) | A healthy 260 s bash is cancelled at exactly 240 s. Busy ≠ progress; silence ≠ failure. Bash output is not streamed frame-by-frame; `is_busy` is the only observable progress proxy. Snapshot cap 400 files/4 MB and 0.10 s device I/O proxy. |
| P0-E4 (`V2-16-CONCURRENCY-MEASUREMENT.md`) | 2 non-legacy engines ≈ 209 MB tree; third start refused `2/2`, no child spawned; refusal reason is log-only. Ordinary project taps stop the outgoing engine unless a run is in flight. |
| P0-E6 (`V2-16-FGS-FEASIBILITY.md`) | No FGS type guaranteed; FGS is not process-death recovery. D1 stays open. |

Design rules that follow: **execution lifetime is independent of navigation and
of any single ViewModel**; **liveness is not progress**; **no implicit cancel**;
**no claim of exactly-once**; **no secret or transcript in durable records**.

---

## 1. Execution identity and ownership contract

### 1.1 What currently cancels the run on a project switch

Traced from the device evidence into code (precise chain):

1. Projects row tap → `ProjectsScreen.kt:235-249` → `ProjectsViewModel.requestOpenProject`
   (`ProjectsViewModel.kt:217-274`) → `AppContainer.setActiveProject`
   (`AppContainer.kt:407-411`) → `applyBinding` (`:750-813`).
2. `NavGraph.kt:97-102` navigates with `popUpTo(Chat, inclusive = true)` → the
   **old chat back-stack entry and its `ChatViewModel` are destroyed**.
3. `bindProject()` (`ChatViewModel.kt:1148-1191`) retargets `instanceKey`
   (`:1162`) and nulls `port/wsId/sessionId/workspaces` (`:1170-1173`) while the
   old `activeRun: RunHandle` still holds its frozen target.
4. `onCleared()` (`ChatViewModel.kt:1939-1954`) only revokes its own connection
   registration, stops the SSE reader and closes the router. It does **not**
   POST a cancel. But `viewModelScope` cancellation unwinds `streamSend`
   (`Catch CancellationException` at `:4138-4143`) and its `finally` (`:4148-4168`)
   calls `abandonRun(handle)`.
5. `abandonRun` (`ChatViewModel.kt:873-878`) clears the container flag with the
   **current, already-retargeted** `instanceKey`, not the handle's key. The
   single volatile slot `engineRunInFlightFor` (`AppContainer.kt:375-376`) can
   therefore stay set for the old key, or be overwritten by another run.
6. `applyBinding`'s guard (`:790-800`) keeps the outgoing engine alive when
   that slot matches; `ConnectionRegistry.revoke` is deferred while in flight
   (`:808-812`, `ConnectionRegistry.kt:127-137,161-170`). So the **engine
   survives** — but no component now owns the execution.
7. The engine's history then contains `tool_result is_error=true, “Error: user
   cancelled assistant tool calling”` for the folder-2 run (P0-E2 §4.1). There
   is **no cancel POST in the switch path**. Only two places POST engine cancel:
   `stopStreaming()` (`ChatViewModel.kt:4936-4984`, POST at `:4973-4975`) and
   `awaitServerCancel()` (`:4786-4793`, from the idle/prompt ceilings at `:4537`
   and `:4469`). Neither is the switch. The exact engine-side cause of that
   cancellation (disconnect interpreted as cancel, an expiry race, or another
   path) is **not established on device** and is not claimed here.

**Answer to the owner's six questions**

| Question | Answer |
|---|---|
| 1. Who currently cancels? | No explicit cancel call. The run loses its owner when the nav entry destroys the ViewModel; the controller-equivalent responsibility stays in `ChatViewModel.activeRun`. The engine reports a cancellation afterwards; source unproven. |
| 2. Triggered by ViewModel, navigation, workspace replacement or other? | **Navigation destroying the ViewModel** (`popUpTo inclusive`) plus `bindProject()` retargeting the key. `applyBinding` intentionally does not stop the engine when a run is flagged. |
| 3. Which component should own execution lifetime? | A **process-scoped `ExecutionController` in `AppContainer`**, with its own coroutine scope, independent of every nav entry and of Compose lifecycle. It owns identity, phase, prompts, cancel/recover policy and progress timestamps. |
| 4. How can UI detach/attach without cancelling? | UI subscribes to a controller `StateFlow` **by executionId**. Detach = unsubscribe only. Attach = new observer epoch for the same executionId. No connection, run or prompt is stopped by an observer leaving. Engine cancel exists **only** as an explicit `Intent.Cancel` from controller API. |
| 5. How are leases/generations preserved? | The record stores **workspace path + project-id token + observed engine generation**, never the ephemeral workspace id. Recovered attachment validates all four in one critical section under the per-path mutex (§7). |
| 6. What if the workspace is replaced/unavailable? | Generation mismatch or missing lease → **abort attach**, reconcile from engine history if possible, otherwise mark the execution **lost** visibly. Old prompts are never inherited by the new engine (§7, §9). |

**Non-negotiable (plan §3):** `ChatViewModel.onCleared()` must not cancel a run
or discard a prompt. Moving the cancel POST into a controller alone does **not**
solve this: the ownership, detach and cancel contracts below are what solve it.

### 1.2 Identity vocabulary — explicit, not conflated

| Name | Minted by | Wire? | Durable? | Use |
|---|---|---|---|---|
| `executionId` | app, at send intent | no | **yes** (Room) | primary key of an execution; survives process death; **the** recovery identity |
| `runId` | app, per send attempt | yes (`AgentMessageRequest.run_id`) | yes, **reused for retries of this execution** | engine/router demux; **not** an idempotency key today |
| `sessionId` | app (`ses_<hex>`), engine persists | yes | yes (engine + record) | most reliable join key for history/prompt correlation |
| `toolCallId` | engine | yes | in engine history; keep raw in record | tool rows, prompt correlation, read-back of `yes_no` answers |
| `batchId`/`requestId` | engine | yes | raw JSON in record | answer/cancel of question batches |
| `workspacePath` | user/import | n/a | **yes** | durable workspace identity (`repo_dir` bytes) |
| `instanceKey` | app | no | as token `proj:<full UUID>`, `""`, `chat` | engine instance selection; **never** persist only `p<uuid8>` (lossy) |
| `engineGeneration` | app (`AtomicLong` in `WorkspaceManager`) | no | observed value in record | stale-generation detection |
| `workspaceId`, `clientId`, observer epochs | engine/app | n/a | **never** | process-local only; not recovery keys |

`run_id` is currently minted **per retry attempt** (`ChatViewModel.kt:3967-3968`)
and is absent from `message`, permission and question frames. A lost 202 can
therefore create two engine runs with different ids. The design changes this:
`executionId` is the stable app id; `runId` is minted **once per execution** and
reused on any same-execution retry, so a duplicate attempt is at least
recognizable. This is an app-side correlation aid, **not** an engine guarantee.

### 1.3 Ownership boundaries

| Layer | Owns | Must not own |
|---|---|---|
| `ExecutionController` | execution identity/phase, prompt ownership, cancel/recover policy, progress timestamps, observer registry, acceptance/reconciliation | engine spawning, HTTP details, Compose state |
| `ChatViewModel` | projection for the current screen, intent dispatch, transient input state | execution lifetime, cancellation on clear, connection ownership beyond its epoch |
| `EngineSupervisor` | process start/stop, health, restart budget | execution semantics; **remains the only process supervisor** |
| `WorkspaceManager` | binding, lease, per-path mutex, generation | run state |
| `ExecutionStore` | durable execution facts | secrets, transcripts, api keys |
| `Navigation` | which screen is shown | any execution lifetime decision |

---

## 2. Three independent state dimensions

Never collapse these into one `isRunning` boolean. Each has its own source of
truth and its own transition rules.

| Dimension | Values | Source of truth |
|---|---|---|
| **Execution state** | `draft` → `intent_recorded` → `send_ambiguous` / `awaiting_ack` → `running` → `parked_permission` / `parked_question` → `cancelling` → `completed` / `failed` / `lost` | controller + `ExecutionStore` |
| **Engine state** | `process_alive` / `dead`, `socket_up` / `down`, `generation`, `is_busy`, `is_ready` | supervisor + `/v1/version` + `/v1/agent` |
| **Observation state** | `unsubscribed`, `subscribed`, `lastDeliveredEvent`, `lagMs`, `ownerEpoch` | controller observer registry |

Invariants the design must hold (and tests must pin):

- an execution can be **running** with **no observer**;
- the engine can be **alive** while an execution is **parked**;
- an execution can be **complete** before the UI has received its final event;
- the UI can be **disconnected** while the execution remains recoverable;
- a change in observation state never changes execution state;
- a change in engine state never silently changes execution state — it produces
  an explicit reconcile/lost transition.

A UI row is a projection of all three: e.g. `running + engine_dead +
unsubscribed` renders “running, engine unavailable, reconnecting” until
reconciliation decides `completed` or `lost`. It never renders a bare spinner.

---

## 3. `ExecutionController` — API and responsibility boundaries

Process-scoped, constructed once in `AppContainer`, with a container-owned
coroutine scope (never `viewModelScope`). It is the **only** caller of engine
cancel for an execution.

```kotlin
// Shape only — no production code written.
class ExecutionController(
    private val store: ExecutionStore,
    private val container: AppContainer,   // for runtimeFor / registry / bindings
    private val apiFactory: (port: Int) -> CrushApi,
    private val engineStates: Flow<Map<String, EngineStateSample>>,
    private val scope: CoroutineScope,      // container scope
) {
    val executions: StateFlow<Map<String, ExecutionView>>      // projection input

    // intents — the ONLY mutation surface
    suspend fun start(cmd: StartCommand): StartResult          // durable intent first
    suspend fun cancel(executionId: String, reason: String): CancelResult
    suspend fun answer(executionId: String, answer: Answer): AnswerResult
    suspend fun cancelPrompt(executionId: String): PromptResult
    suspend fun retry(executionId: String): RetryResult         // same executionId/runId
    fun subscribe(executionId: String, ownerEpoch: Long): Flow<ExecutionView>
    fun unsubscribe(executionId: String, ownerEpoch: Long)      // detach ONLY

    suspend fun reconcileOnStartup(): ReconciliationSummary
}
```

Rules:

1. `start` writes `draft` → `intent_recorded` **durably before** any POST.
2. `cancel` targets the execution's frozen engine/session identity and records
   confirmation or a named unconfirmed cancel state (R19).
3. `answer`/`cancelPrompt` include batch/question/tool-call identity; never
   auto-answer an ambiguous prompt.
4. `subscribe`/`unsubscribe` change only observation state; they never touch an
   engine, a run, or a prompt.
5. The controller **observes** `EngineSupervisor` state; it never starts/stops a
   process itself. `EngineInstanceRegistry` keeps the capacity gate.
6. A single `ExecutionView` is published per execution; UI state is derived,
   not written by the UI.

---

## 4. `ChatViewModel` projection and intent contract

- Holds **no** `activeRun`, stream job, connection owner or pending prompt as
  its own lifetime. It maps the controller's `StateFlow` into `ChatViewState`
  for the currently bound engine/session.
- `onCleared()` becomes exactly: `controller.unsubscribe(executionId, myEpoch)`
  plus its own UI-scoped channel cleanup. **No engine cancel, no prompt clear,
  no `reportRunInFlight` from a destroyed ViewModel.**
- Navigation/binding changes do not call the controller's cancel. `bindProject`
  retargets what the UI *shows*; it does not retarget an execution's stored
  engine identity.
- Intents from UI: `Start`, `Stop`, `Answer`, `CancelPrompt`, `Retry`,
  `OpenExecution(id)` (attach observer), `LeaveExecution(id)` (detach). Stop is
  an explicit user intent — the only UI path that can cancel.
- The existing keyed guards (`sessionViewEpoch`, `connectionOwnerEpoch`,
  `ConnectionRegistry.revokeIfOwn/isSuperseded`) remain the pattern for
  deciding which observer may update which view; they become observer-epoch
  guards, not ownership of the run.
- Project switch during a run: the old entry is destroyed, but the execution
  keeps running under `executionId`; reopening that project (or a global runs
  surface) re-subscribes by id and reconciles from history if frames were
  missed. The current session list/session history is the fallback view.

---

## 5. Durable `ExecutionStore` schema and additive migration

Ride the **existing** `projects.db` v3 builder; do not create a second database
or a second migration chain (`RoomProjectStore.kt:22-45`). Additive **v3 → v4**:

```sql
ALTER TABLE executions ... -- new table, no existing row rewritten
CREATE TABLE IF NOT EXISTS executions (
  execution_id      TEXT PRIMARY KEY NOT NULL,
  run_id            TEXT NOT NULL,
  session_id        TEXT NOT NULL,
  engine_key_token  TEXT NOT NULL,          -- "" | "chat" | "proj:<full UUID>"
  workspace_path    TEXT NOT NULL,          -- byte-exact repo_dir
  engine_generation INTEGER,                -- observed; NULL = unknown
  lease_epoch_ms    INTEGER,                -- observed lease timestamp; NULL = unknown
  phase             TEXT NOT NULL,          -- execution-state value
  progress_at_ms    INTEGER,
  created_at_ms     INTEGER NOT NULL,
  updated_at_ms     INTEGER NOT NULL,
  prompt_ref        TEXT,                   -- raw batch/tool-call JSON only
  terminal_reason   TEXT,                   -- named reason or NULL
  ack_state         TEXT NOT NULL           -- SENT_UNCONFIRMED | ACKED | AMBIGUOUS
)
CREATE INDEX IF NOT EXISTS idx_exec_session ON executions(session_id, updated_at_ms)
CREATE INDEX IF NOT EXISTS idx_exec_engine  ON executions(engine_key_token, phase)
CREATE INDEX IF NOT EXISTS idx_exec_path    ON executions(workspace_path)
```

Contract:

- **no secrets, no transcripts, no provider key, no prompt text** — only ids,
  raw prompt reference, timestamps and named reasons (plan §4 boundary, §12).
- No `ForeignKey`/cascade to `projects`: traces must survive project removal,
  matching `HandoffEntity.kt:33-40`.
- Status/phase writes are **targeted** `UPDATE … WHERE phase = <expected>` and
  return an affected-row count — never `@Upsert` for transitions
  (`HandoffDao.kt` pattern). CHECK constraints, if used, must match the
  `@Entity` (fresh installs skip hand-written DDL).
- Register `Migration(3,4)` in `RoomProjectStore.kt:33`; **no destructive
  fallback**.
- Migration test follows the existing pattern: hand-build v3 DDL in a test db,
  insert a project row, set `db.version = 3`, open through `RoomProjectStore`,
  assert the row and its trust/handoff columns are byte-preserved, then assert
  a guarded phase transition. Use a test-only db name and wipe `-wal/-shm`.
- Retention: terminal executions are kept for a bounded time for recovery and
  diagnostics, then pruned by `updated_at_ms`; pruning is never in the
  active-window path. Exact bound is D4/A6, not decided here.

---

## 6. Send-path crash points and ambiguous acknowledgement

Ordering (plan addendum 16.2): **persist intent → send request → record ack →
advance to running**. Never send before the durable intent; never auto-resend
an ambiguous send; never advance before the ack is recorded.

| Crash between | Recorded state | On recovery | Automatic re-send? |
|---|---|---|---|
| before step 1 | nothing | no action; no phantom session created | **no** |
| 1 and 2 | `intent_recorded`, `runId` reserved | mark **unsent**, visible as “not sent”; user may retry | **no** |
| 2 and 3 | `SENT_UNCONFIRMED`, `ack_state=AMBIGUOUS` | query `GET .../sessions/{sid}/messages` and `run_complete`; if a matching message/answer exists, finalize; otherwise expose **ambiguous submission** | **no** |
| 3 and 4 | ack recorded, phase stale | resume as `running`; revalidate generation first (§7) | n/a |
| after 4 | `running` | normal recovery/reconcile | n/a |

**Answer submission (C6) uses the same three-level rule** (addendum 16.1):

| Level | Evidence | Action |
|---|---|---|
| same-request replay safe | P0-E1: `{"resolved":true}` then `{"resolved":false}`, both 200 | a retry of the **same** batch/answer is permitted |
| unique read-back | `tool_result` keyed by `tool_call_id` contains `User answered: …` | confirm and finalize — **proven only for `yes_no`** |
| neither | multi-choice/free-text formats unmeasured | **visible ambiguous submission**, user decides; never auto-retry |

Exactly-once execution is **not claimed** at any level; the design only provides
durable intent, deterministic correlation and honest ambiguity. `runId`
reuse makes duplicate attempts recognizable, not impossible.

---

## 7. Atomic workspace-generation validation and attachment

“Atomic” per §17.1 means validation and attachment in the **same critical
section that guards engine replacement**. A read-then-attach is forbidden:

```
read generation → (engine replaced here) → attach   // FORBIDDEN
```

Mechanism chosen: **hold the existing per-path `WorkspaceManager` mutex**
(`openLocks`/`openLock(path)`, `WorkspaceManager.kt:87-90`) across validate +
attach, and make **every** engine replacement take the same mutex:

- `WorkspaceManager.openLocked` (`:229-324`) already commits id+generation
  under this lock;
- `reattachIfStale` (`:370-402`) already uses it;
- `EngineSupervisor.stop/restart` and `EngineInstanceRegistry` replacement take
  the same path lock via a controller-owned `withPathLock(path) { … }` wrapper;
  until that wrapper exists, a recovered attach must **refuse** rather than
  attach outside the lock.

Lock order (fixed, no cycles):

```
execution lease / store row (short) → per-path WorkspaceManager mutex
   → runtime/generation read → lease freshness check → attach
   → release path mutex → publish observation
```

Validation is one **all-or-nothing** predicate:

```
engine_key_token == current binding token
AND workspace_path == manager.workspacePath
AND generation == WorkspaceManager.currentEngineGeneration()
AND lease.isFresh(now)            // 15 s, fails closed on null/backwards clock
```

Failure path: **abort attach**; do not inherit old prompts; reconcile from
engine history if the session exists on the new engine, otherwise mark
`lost` with a named reason. A generation change never silently rebinds a
recovered execution to the new engine.

Deterministic test: a latch/barrier hook inside `withPathLock` forces an engine
restart **between** validation and attach; the attach must fail or restart the
check, never complete against the new engine.

---

## 8. Project-switching independence contract

1. A binding change (`setActiveProject`, nav `popUpTo`, tab switch, in-chat
   rebind, handoff bind) **must not** call `ExecutionController.cancel`,
   `EngineInstanceRegistry.stop` for a run-in-flight key, or clear prompts.
2. The controller keeps executions keyed by `executionId` and their **frozen**
   engine identity; the ViewModel/view only holds an observer epoch.
3. Detaching a view (back, clear chat, switch project, app background) is
   `unsubscribe` only. The run continues and may complete with no observer.
4. Reattaching uses `subscribe(executionId)`; missed frames are repaired from
   `GET .../sessions/{id}/messages` (Crush republishes whole messages, so text
   is **replaced**, never appended).
5. If the execution's workspace is replaced/unavailable: §7 validation fails →
   reconcile or `lost`; **old prompts are never inherited** by the new engine.
6. A third engine start while 2/2 slots are live returns a **named capacity
   refusal** to the controller/UI (§11), never a silent no-op.
7. The only user action that cancels an execution is explicit `Stop`, which
   dispatches `Intent.Cancel` to the controller. Leaving a screen is not Stop.

This contract is what the current implementation violates: ownership is in the
destroyed ViewModel, so detach becomes de-facto cancel. The design removes the
lifetime from that component rather than adding a cancel call elsewhere.

---

## 9. Cancellation and recovery state transitions

| From | Trigger | To | Notes |
|---|---|---|---|
| `draft` | `start` persists intent | `intent_recorded` | before any POST |
| `intent_recorded` | POST accepted, ack recorded | `awaiting_ack` → `running` | never advance before ack |
| `intent_recorded` | POST outcome unknown | `send_ambiguous` | no auto-resend; read-back then finalize or ambiguous |
| `running` | permission/question frame with joinable session+generation | `parked_permission` / `parked_question` | engine may be alive; app may be unsubscribed |
| `parked_*` | answer/cancel POST confirmed or read-back | `running` | same answer/cancel replay safety only |
| `parked_*` | prompt ambiguous (no session id, two owners, stale generation) | `parked_ambiguous` | **no auto-answer**; workspace-level cancel or bounded wait with visible state |
| any active | `Intent.Cancel` | `cancelling` → `completed(cancelled)` or `failed(unconfirmed)` | engine confirm recorded; one terminal write wins |
| any active | engine `finish(reason=canceled/error)` | `completed(cancelled)` / `failed` | `isFinished` alone is not success (P0-E1) |
| any active | engine process dead, history reconstructable | `reconciling` → `completed` / `lost` | no invented events |
| any active | app death, engine dead, no history | `lost` | visible, transcript preserved as far as it exists |
| `send_ambiguous` | user chooses retry | `intent_recorded` with **same** executionId/runId | no duplicate claim of success |

**Cancel racing with completion** is resolved in the controller's single
critical section: the first terminal transition (cancel-confirmed or
completion) writes `terminal_reason`; later arrivals are recorded as late
observations and cannot flip the terminal state. A cancel whose POST failed is
`failed(cancel_unconfirmed)`, not a silent disappearance.

**Prompt policy (D3, not finalized):** republish + read-back is viable **only
when the engine survives**; on this ROM process death kills the engine, so the
prompt is `lost` and must be shown as such. Multi-choice/free-text formats are
unmeasured — their read-back stays `ambiguous` until measured.

---

## 10. Six clocks and per-tool-class waiting policy

All six clocks are retained; none disappears, and no heartbeat can reset an
execution-progress budget.

| # | Clock | Owner | Starts / resets | Terminal behaviour | Disposition |
|---|---|---|---|---|---|
| 1 | Silent frame interval | controller | last frame; resets on any **frame** | named wait per tool class; no automatic cancel on silence alone | retained, per §16.4 |
| 2 | Tool execution interval | engine (unknown), observed by app | tool start | engine-defined; app shows the tool's named wait | retained; no cancel from silence |
| 3 | Total run duration | **currently unestablished** | run accepted; never | to be named by D4/A4 (execution deadline) | retained as a requirement, not invented |
| 4 | Engine idle timeout | engine, value chosen by app | engine idle | process exits | retained; set so it cannot kill a live run (R32 open) |
| 5 | Parked-prompt wait | controller | prompt shown; on answer/cancel | D3 policy: persist/republish/cancel with visible outcome | retained |
| 6 | Send/startup | controller | send intent | snapshot bound separate from POST budget | **split**: snapshot gets its own bound; POST keeps a dedicated budget |

Five signals, kept separate (from plan §16 / P0-E3):

| Signal | Proves | Resets execution progress? |
|---|---|---|
| connection liveness (SSE/socket up) | transport | **no** |
| engine liveness (`/version`, pid) | process | **no** |
| execution progress (new message/plan/phase) | agent advanced | **yes** |
| tool progress (`tool_result`, status) | tool advanced | **yes** |
| observation freshness | UI received state | **no** |

Per-tool-class named waits, seeded from P0-E3 measurements (values are
**policy proposals for D4**, not new constants):

| Class | Observed healthy duration | Named wait policy | On no progress |
|---|---|---|---|
| network (`fetch`) | < 1 s | short bounded | awaiting → named stall at ceiling |
| codegen (bash) | 0.13 s for 200 files | short bounded | same |
| build (bash CPU loop) | 1.9 s | medium bounded | same |
| test suite (bash) | 15.2 s | medium bounded | same |
| package/download (native `download`, 10 MB) | ~3 s | medium bounded | same |
| long-running shell | 90 / 120 / 260 s | **long bounded wait with engine-progress observable**; heartbeat cannot reset it | **awaiting under named policy**, never cancelled merely for silence |

The 240 s global silence cap is **not** carried forward as the execution policy.
A healthy silent tool must not be cancelled for lack of intermediate output.
D4 stays open: this spike supplies the policy shape and measured numbers, not
a new timeout. The snapshot bound (0.10 s device proxy, 400 files/4 MB caps)
is outside the send POST budget; no large-tree send timeout is expected in the
measured envelope.

---

## 11. Concurrency capacity and refusal-state contract

- Capacity is a **product policy**, not a physical limit: today 2 non-legacy
  engines, legacy exempt. P0-E4 shows ≈209 MB tree and no memory pressure;
  **D2 remains open**, so no widening is decided here.
- `EngineInstanceRegistry.requestStart` keeps the atomic check-and-reserve and
  the named refusal reason (`2/2 … refused until one stops`).
- Refusal is a **first-class execution/UI state**, not a log line:
  `CapacityRefused(reason, activeKeys, suggestions)`. The UI must show:
  which executions hold the slots, that this one was **not** started, and an
  actionable path — e.g. stop a named background execution via `Intent.Cancel`,
  or retry after one stops. No phantom “running” row.
- A refused start records `failed(capacity_refused)` with no engine child and
  no prompt ownership; retry re-enters the capacity gate.
- Observation of capacity is separate from execution state: a refused start is
  a terminal execution outcome, not an engine-state fact.

---

## 12. Deterministic tests for the critical interleavings

Fault-injection rule first (**R37**): a harness command that is denied must
**fail loudly**. `tools/chaos.ps1` must assert that `am start` succeeded and
that the engine pid actually changed; it must never swallow stderr and report
green. Until a reachable fault path exists, engine-only-kill tests are
`not-performed`, not passes.

| # | Scenario | Deterministic seam | Expected | Device evidence |
|---|---|---|---|---|
| 1 | Switch projects during active run | nav `popUpTo` while controller has `running` execution; fake observer epoch | execution stays `running` under its id; old engine child not stopped; no cancel POST; re-subscribe by id reconciles | **available** (P0-E2 §4.1 shows current violation; post-A1 re-run required) |
| 2 | Clear chat screen during active run | destroy ViewModel with `onCleared` while run active | `onCleared` only unsubscribes; execution continues; completion recorded before any observer | **available** |
| 3 | Replace engine while recovery attaches | latch/barrier inside `withPathLock` forcing restart between validate and attach | attach aborts or re-validates; never attaches to new generation; prompts not inherited | **available** via debug hook (deterministic) |
| 4 | App death during answer submission | crash hook between `answerQuestions` POST and recording ack; restart from store | `SENT_UNCONFIRMED` → read-back; yes_no finalizes, others `ambiguous`; no auto-retry | **available** (in-app crash hook; P0-E1 read-back applies) |
| 5 | Ambiguous answer acknowledgement | suppress ack response in fake `CrushApi` | visible ambiguous state; user decides; no duplicate answer | **available** as unit/deterministic fake |
| 6 | Long-running silent tool | fake clock + 260 s bash class profile | no cancel for silence; awaits under named policy; heartbeat does not reset progress | **available** (P0-E3 number; fake-clock unit test) |
| 7 | Engine death while app alive | supervisor `Failed`/`Stopped` state injected via test seam; assert controller transition | execution → `reconciling` → `lost` or reconstructed; no silent drop | **unavailable on this build** — `SpikeActivity` not exported (R37); deterministic seam required |
| 8 | App death while engine alive | fake engine peer / engine-side unit test | reattach + reconcile + continue or finish observation | **unavailable on this ROM** (P0-E1 F5/P0-E2: all kills take children); deterministic seam required |
| 9 | Third engine start | registry unit test with 2 live children + device observation from P0-E4 | named `CapacityRefused`, no child, UI state, retry path | **available** (device observation recorded) |
| 10 | Cancel racing completion | barrier on cancel-confirm vs `run_complete` | one terminal write wins; late arrival logged as observation; no flip | **available** as deterministic unit test |

Also required: a test proving execution state survives `onCleared()`, an
instrumented “clear chat entry mid-run, continue” case (A1), the v3→v4 migration
test (A2), and mutation tests removing each guard (A8).

---

## 13. Impact on decisions and preserved contracts

- **D7 (this spike):** proposed answer — execution lifetime is owned by a
  process-scoped controller, independent of navigation; three dimensions stay
  separate; cancel is an explicit intent. **Owner approval still required.**
- **D1, D2, D4** remain **open**; this document supplies inputs only. No FGS
  decision, no capacity widening, no new timeout.
- V2-15 contracts are preserved: UDS transport, engine policy install, ASK
  default/fail-safe, whole-message replace on SSE, no TCP fallback, no plaintext
  key, no secrets in records, `EngineSupervisor` as sole process supervisor.
- Not claimed: exactly-once execution, guaranteed background survival, engine
  survival after app death, structured multi-choice/free-text read-back, or
  validated lost-ack recovery.
- R35 stays open until the owner confirms old-key invalidation in the provider
  console.

## 14. Incremental implementation order (for review only, no code now)

1. **A1** — controller + three dimensions + intent API; ViewModel reduced to
   projection/unsubscribe; tests 1, 2, 10.
2. **A2** — v3→v4 `ExecutionStore` + startup reconciliation + six-cell matrix;
   tests 3, 4, migration.
3. **A3** — prompt provenance/ambiguous policy + answer retry rules; test 5.
4. **A4** — six clocks + tool-class waits + snapshot bound; test 6.
5. **A7** — capacity/refusal surface + run-in-flight switching; test 9.
6. **A5/A6/A8** — background decision (D1), observability, regression gate.

## 15. Questions for the owner at the A1 checkpoint

1. Is a single process-scoped controller acceptable, or should execution
   ownership live in a service-bound component (A5) from the start?
2. Should `Stop` remain a user-only intent, or may a capacity refusal stop a
   named background execution automatically (with confirmation)?
3. For an **ambiguous** answer submission, confirm the UX: visible user
   decision, never automatic retry.
4. What total execution deadline, if any, should D4 name for a 20-minute task?
5. Accept the 2-engine capacity policy for now (D2) while A7 adds a named
   refusal surface?
6. Approve the v3→v4 additive `executions` table on `projects.db`, with no
   secrets/transcripts and a guarded migration test?
