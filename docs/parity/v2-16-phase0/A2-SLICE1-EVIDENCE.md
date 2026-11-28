# A2 slice 1 — implementation and evidence (2026-11-27)

Authorization boundary: `A2-SLICE1-SCOPE.md`. The owner authorized the run
half only. C4/C5/C6, D1, D2, D4, D7, prompt policy, device fault work and
`main` remain untouched. A1 stays accepted.

> **2026-11-27 — dated schema-state correction.** Line 15 below describes the
> initial slice (`version = 4`, `MIGRATION_3_4` only). Shipped HEAD `f99668c`
> has `version = 5` with the additive `MIGRATION_4_5` aligning the canonical
> `lease_epoch_ms` column; `MIGRATION_3_4` itself is unchanged. The historical
> line stands as written.

## What was implemented

| File | Change |
|---|---|
| `execution/ExecutionEntity.kt` | New additive `executions` row: ids, engine key token, byte-exact workspace path, nullable generation/lease, phase, timestamps, nullable `prompt_ref` (never written in slice 1) and named terminal reason. Three declared indexes. No prompt text, transcript, key or credential. |
| `execution/ExecutionDao.kt` | Guarded targeted UPDATEs returning affected-row counts, in the `HandoffDao` style. Never `@Upsert` for a transition. Includes `markRunningByRunId`, guarded on `ACKED`. |
| `execution/ExecutionStore.kt` | Narrow durable port for the send path. `persistIntent` is the only row creator; it writes `INTENT_RECORDED` / `INTENT_ONLY` and does not claim the send happened. |
| `execution/ExecutionReconciler.kt` | Startup decisions for C1–C3 and the send-path boundaries using the approved evidence vocabulary. Evidence gaps are UNKNOWN, not LOST; never auto-resends and never resolves prompt ambiguity. See `A2-SLICE1-REMEDIATION-REPORT.md`. |
| `data/project/ProjectDatabase.kt` | `version = 4`, `ExecutionEntity` registered, `MIGRATION_3_4` = plain CREATE TABLE + indexes beside `MIGRATION_2_3`; no destructive fallback. |
| `data/project/RoomProjectStore.kt` | Registers `MIGRATION_3_4` in the same builder; exposes `executionDao()`. No second database. |
| `di/AppContainer.kt` | Lazily owns store and reconciler; one boot launch writes named outcomes into the in-memory controller; the confirmed-event callback advances the stored phase only for an `ACKED` row. No engine is started by any of this. |
| `ui/screens/chat/ChatViewModel.kt` | Send path writes four boundaries: durable intent before POST, send marker before POST, ack / rejection / ambiguous outcomes after, and ambiguity on stop-during-POST. A failed durable write is a named failure and the POST is not made. |
| `execution/ExecutionController.kt` | Additive `onRecovered` adopts a DECIDED reconciliation outcome; no I/O, no engine lifecycle change. |

## Verification

### Behavioural pins (`ExecutionRoomRoundtripTest`, `ExecutionReconcilerTest`)

- **Migration:** hand-built v3 database with real project and handoff rows →
  open through the real chain → rows preserved field-by-field, `executions`
  table and all three indexes present in `sqlite_master`, restart roundtrip
  byte-faithful.
- **Guarded writes:** an un-sent row cannot be acked (0, unchanged); a repeated
  ack is a 0 no-op; a terminal row cannot advance; a confirmed frame advances
  only an `ACKED` row.
- **C1:** empty table → empty reconciliation summary, no record minted.
- **Boundary 1:** `INTENT_RECORDED`/`INTENT_ONLY` → `UNSENT` with
  `recovery:send_not_attempted`, no send.
- **Boundary 2→3:** `SEND_ATTEMPTED`/`SENT_UNCONFIRMED` → `AMBIGUOUS`, never
  auto-resend.
- **C2/C3:** `SUBMISSION_ACKNOWLEDGED`/`RUNNING` with no engine evidence →
  `UNKNOWN` with `recovery:evidence_unavailable`; LOST only on confirmed
  absence from a successfully queried evidence source.
- **C6:** pre-existing ambiguity is left untouched (`DEFERRED`), and a second
  reconciliation pass is a no-op.
- **Adoption:** a `LOST` outcome reaches `ExecutionController.executions` as a
  terminal detached record with its named reason — no live-engine claim.

### Full gate

`gradlew.bat :app:testDebugUnitTest` — `BUILD SUCCESSFUL in 8m`.
Initial slice gate: **123 suites / 1139 tests / 0 failures**.

After the post-implementation remediation recorded in
`A2-SLICE1-REMEDIATION-REPORT.md`, the final gate is
**125 suites / 1156 tests / 0 failures / 0 errors / 0 skipped**
(`BUILD SUCCESSFUL in 6m 54s`; raw output `a2-rem-fullgate-run.txt`).
The gate floor is 1087; a lower count fails A-G7.

## Scoped limitations (not defects hidden by a green suite)

- **No device process-kill evidence.** These are JVM/Robolectric crash-point
  *simulations* (seed the row at a boundary, then reconcile), not real
  `am kill` runs. This ROM blocks adb from SIGKILLing app-owned processes;
  device cells need the debug bridge (R37), which is not authorized here.
- **No atomic validate + attach.** Design §7 / §17.1 require validation and
  attachment in the same critical section guarding engine replacement. That
  wrapper does not exist, so C2/C3 recovered rows stay honestly UNKNOWN
  (evidence unavailable), not LOST and not rebound to a new engine.
- **Generation/lease are stored NULL at send.** The generation counter is
  process-local and resets to 0, so a persisted value cannot be compared across
  process death. Recovery fails closed instead of guessing.
- **C4/C5/C6 remain D3/X1/A3 work.** No prompt policy was invented.
- No new library, no CI run for this branch, no `main` change, no PR/merge.

## Evidence class

JVM/Robolectric only. Passing tests are not a device claim, and a green gate
does not prove the six cells were observed on hardware.
