# A2 slice 1 — second remediation pass report (2026-11-27)

Scope: terminal-event durability, event-before-ack ordering, guarded send-path
persistence, recovery honesty and evidence correction. No A3, C4–C6, D3/X1,
R37, full A2 expansion, `main`, PR or merge work. A1 remains accepted;
A2 slice 1 remains unaccepted. Pre-edit reconnaissance is in `A2-SLICE2-RECON.md`.

## A. Finding disposition

| Previous finding | Final status | Source evidence | Test evidence |
|---|---|---|---|
| Terminal events persisted durably | **FIXED (JVM)** | `ExecutionStore.applyConfirmedEvent` → guarded `ExecutionDao.markTerminalByRunId` (`run_id` + live phase + `ACKED`); `AppContainer` callback applies durably BEFORE the controller publishes; `RUN_COMPLETE` reason from the wire (`confirmed_completion` / `confirmed_cancelled`) | `ExecutionTerminalPersistenceTest` 6/6: COMPLETED+reason, restart read-back, duplicate terminal, unknown run, stale generation, progress-after-terminal, disconnect |
| Event-before-ack race | **FIXED (JVM)** | `ConfirmedEventBuffer` (bounded, deduped, explicit overflow); a 0-row durable apply now defers instead of being treated as harmless; `AppContainer.replayConfirmedAfterAck` replays after ACKED; no ACK is manufactured | `ConfirmedEventBufferTest` 5/5: pre-ack event buffered then applied, failed ACK preserved as AMBIGUOUS, stale generation, overflow INCOMPLETE, pre-ack terminal |
| Failed persistence blocks POST | **PARTIALLY FIXED** | `ExecutionPersistenceGuard` on intent/send/ack/ambiguity; the previously unguarded ack-fallback `markAmbiguous` now checks an affected-row count | `ExecutionPersistenceGuardTest` 5/5; **no VM-level POST-count test** |
| Real send-path integration coverage | **NOT PROVEN** | No injectable API/store seam exists in `ChatViewModel`; `api.send` is created per call and `AppContainer` collaborators are final lazy vals | None; `ExecutionSendPolicyTest` and guard tests remain decision pins only |
| Terminal transition idempotency | **FIXED** | `markTerminalByRunId` guard excludes terminal phases; controller `mutate` refuses terminal records | Duplicate terminal preserves first reason/timestamp; progress-after-terminal returns 0 |
| Migration / evidence consistency | **PARTIALLY FIXED** | `MIGRATION_3_4` byte-identical to `105843e`; `MIGRATION_4_5` additive; canonical lease column used in production; dated doc corrections added | `ExecutionRoomRoundtripTest` 6/6 incl. fresh-install DDL and v4→v5 copy; full-gate totals not fully re-derivable from the 8 archived XMLs |
| FAILED confirmation mislabelled | **FIXED** | `ExecutionReconciler` now returns `Outcome.FAILED` for `terminal == FAILED`; confirmed-live stays UNKNOWN | 2 new reconciler pins (13/13) |
| Stale generation on live event path | **FIXED at durable layer** | `ExecutionEvent.generation` from declare-time; `applyConfirmedEvent` refuses a generation mismatch; teardown clears declared runs | Terminal + buffer stale-generation tests |
| Stale doc commit claims | **FIXED** | Dated post-commit addenda appended, historical lines preserved | `A2-SLICE1-REMEDIATION-REPORT.md`, `A2-SLICE1-EVIDENCE.md` |

## B. Implementation changes

- `ExecutionModels.kt` — `ExecutionEvent.generation`, so a stale frame cannot
  silently match a newer execution.
- `ExecutionEventAdapter.kt` — declare-time generation, concurrent declared
  sets, `forgetRun`, teardown cleanup, and `run_complete.cancelled` mapped to
  a terminal reason without inventing new event types.
- `ExecutionDao.kt` — `byRunId` and guarded `markTerminalByRunId` (run id,
  live phase, `ACKED`). No guard was weakened.
- `ExecutionStore.kt` — `applyConfirmedEvent` resolves the row, checks
  generation, and performs a guarded progress or terminal write; `byRunId`,
  `markUnknown` exposed for the deferral path.
- `ConfirmedEventBuffer.kt` (new) — bounded 16-event/32 KiB deferral with
  dedupe, explicit overflow and drain.
- `AppContainer.kt` — durable-first confirmed-event callback, bounded
  deferral on a 0-row result, explicit INCOMPLETE on overflow, and
  `replayConfirmedAfterAck` after a committed ACK.
- `ChatViewModel.kt` — generation passed at declare, replay after ACK, and
  the ack-failure fallback write now checked with `ExecutionPersistenceGuard`.
- `ExecutionReconciler.kt` — FAILED no longer collapses to COMPLETED;
  confirmed-live no longer becomes LOST.
- Tests: two new classes (`ExecutionTerminalPersistenceTest`,
  `ConfirmedEventBufferTest`), two extended classes, plus dated documentation
  corrections and eight archived XML files.

## C. Persistence guarantees and ordering

1. `persistIntent` must commit before POST; a failure breaks the turn.
2. `markSent` must commit before POST; a 0-row/failure breaks the turn.
3. POST is a single `runCatching { api.send(…) }`; no automatic second POST on
   timeout, reset, 404 or other unproven outcome — the row becomes ambiguous.
4. `markAcked` must commit before the controller is advanced; a failure leaves
   an ambiguous durable row and never claims acknowledgement.
5. A CONFIRMED event is applied durably first; only an affected-row count of 1
   allows the controller to publish RUNNING/COMPLETED. A 0-row result is
   deferred, not interpreted as completion.
6. Terminal writes require the exact run id, an ACKED live row and a matching
   generation when one was declared; repeats return 0 and preserve the first
   reason and timestamp. Progress never overwrites a terminal row.
7. A disconnect/invalidation is UNKNOWN, never COMPLETED. Unattributed frames
   never reach the durable callback. No failure/stop event type was invented.

## D. Race coverage

| Test | Interleaving proved |
|---|---|
| `an event before ack is buffered then applied after ack` | event applied (0 rows) before ACKED → buffered → ACKED commits → replay applies → COMPLETED |
| `a failed ack never manufactures an acknowledged durable state` | no ACK write → deferred event stays unapplied → honest AMBIGUOUS |
| `a stale generation event is never applied on replay` | generation mismatch survives buffering and is refused after ACK |
| `overflow is explicit and duplicates do not multiply evidence` | 16/32 KiB bound, dedupe, INCOMPLETE, empty drained set |
| `a terminal event before ack is not lost and not claimed complete` | terminal defers, then persists only after ACK |
| `a duplicate terminal event cannot change reason or timestamp` | second terminal write returns 0 |
| `progress after terminal cannot regress the record` | late MESSAGE returns 0; phase stays COMPLETED |
| `terminal event for an unknown run changes nothing` | foreign run id returns 0 |
| `terminal event from a stale generation changes nothing` | generation guard returns 0 |

All are deterministic JVM/Robolectric tests with real guarded DAO writes; none
uses sleeps or wall-clock races.

## E. Test results

- Command: `gradlew.bat :app:testDebugUnitTest --tests 'com.crush.mobile.execution.*' --tests 'com.crush.mobile.di.AppContainerExecutionInvalidationTest'` —
  `BUILD SUCCESSFUL in 8m 32s`; **67 tests, 0 failures**.
- Command: `gradlew.bat :app:testDebugUnitTest` with `JAVA_HOME` = Android
  Studio JBR 25, branch `v2-16-planning-baseline`, HEAD `f99668c` plus the
  uncommitted changes in this pass — `BUILD SUCCESSFUL in 20m 14s`;
  **127 suites / 1169 tests / 0 failures / 0 errors / 0 skipped**
  (previous baseline 125/1156; +13 tests, none removed or disabled).
- Archived: `a2p2-fullgate-run.txt` (NUL-stripped raw output) and eight XMLs
  `a2p2-*-junit.xml` in `docs/parity/v2-16-phase0/`. The eight XMLs sum to 63
  tests; the 1169 full-gate total comes from Gradle output and is not fully
  re-derivable from the archive.

## F. Remaining limitations

- **No real send-path integration test.** There is no injection seam for
  `CrushApi`/`ExecutionStore` in `ChatViewModel`; adding one is a production
  refactor beyond the authorized narrow change. Zero/throw guarantees remain
  proven only at the pure-guard seam. This is the principal blocker.
- **No device evidence** and no R37 fault injection; **blocked by R37**.
- **No atomic validate-and-attach** (§7/§17.1); no latch test for
  restart-between-validation-and-attach. Recovery still stays honestly UNKNOWN
  where evidence is unavailable.
- **Production evidence is always UNAVAILABLE**; CONFIRMED recovery branches
  are harness/JVM proof only. No real engine-history querying exists.
- **No GitHub Actions** run exists for this branch; no CI claim is made.
- Full-gate totals are not independently auditable from the archived subset.
- Failure-from-`finish(reason=error)` is not mapped to a durable FAILED write:
  the wire taxonomy has no dedicated failure event and existing adapters do
  not expose it as an `ExecutionEvent`. No event type was invented.

## G. Acceptance recommendation

**PARTIALLY READY — OWNER DECISION REQUIRED.**

Both correctness defects named in this pass — a durable row ending at RUNNING
after a confirmed completion, and a pre-ACK event being silently dropped — are
fixed and JVM-proven with production transition paths. The remaining blocker is
the absence of a VM-level send-path integration test, which needs a production
injection seam the owner has not authorized; device/R37 and atomic-attach gaps
remain as previously disclosed. I do not self-approve A2 slice 1. No commit or
push was made.
