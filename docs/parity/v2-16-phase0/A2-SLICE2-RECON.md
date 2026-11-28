# A2 slice 1 — second remediation reconnaissance (2026-11-27)

Repository state verified before any edit: branch `v2-16-planning-baseline`,
HEAD and `origin/v2-16-planning-baseline` both
`f99668c9292b4f3930ccb45ac7505b1ce5a9f60e`; worktree clean apart from the
pre-existing untracked `v2-66-phaseF-stuck.png`; `main` = `origin/main` =
`adc26c0…`. Three read-only audits covered the six required areas and the
supporting recovery/migration/evidence workstreams. Nothing was assumed fixed
because a commit message claimed it. A1 remains accepted; A2 slice 1 remains
unaccepted.

## Finding table

| Finding | Current source evidence | Existing test evidence | Status |
|---|---|---|---|
| Terminal events persisted durably | `AppContainer.kt:354-370`: callback calls controller then only `markRunningForRun`; no `markTerminal`. `ExecutionController.kt:169-172` sets in-memory `COMPLETED` only. `ExecutionDao.kt:82-87` never writes a terminal reason. `ExecutionStore.markTerminal` (`:76-77`) has no production caller. | `ExecutionControllerTest.kt:115-134` is in-memory only; `ExecutionReconcilerTest.kt:236-265` uses injected evidence, not events. No event-path terminal test. | **STILL BROKEN** |
| Event-before-ack race | `ChatViewModel.kt:4014` declare → `:4049` markSent → `:4076` POST → `:4115` markAcked. `ExecutionDao.kt:82-87` requires `ACKED`; `AppContainer.kt:362` treats a 0-row result as harmless. Adapter has `begin/commit/rollback` (`ExecutionEventAdapter.kt:87-148`) but no production call sites; a pre-ack confirmed event is dropped for durable phase. | None; `CrushEventRouterTest.kt:46-74` covers a different UI channel. | **STILL BROKEN** |
| Failed persistence blocks POST | `ChatViewModel.kt:4005`, `:4050`, `:4116` use `ExecutionPersistenceGuard.committed`. `:4122` fallback `markAmbiguous` discards its count. | `ExecutionPersistenceGuardTest.kt` 5/5 proves the pure rule, not the VM path. | **PARTIALLY FIXED** |
| Real send-path integration coverage | `ChatViewModel.kt:559-581`: no injectable API/store; `AppContainer.kt:93,148` has final lazy collaborators. No test constructs the ViewModel. | None; `ExecutionSendPolicyTest.kt:16-37` and `ExecutionPersistenceGuardTest.kt:22-43` are hand-written mirrors. | **NOT PROVEN** |
| Terminal transition idempotency | `ExecutionDao.kt:89-96` guards on live phases; `ExecutionController.kt:54-62` refuses terminal records. Event path never calls it. | Duplicate `markRunning` pinned; no duplicate terminal test; no progress-after-terminal DAO test. | **PARTIALLY FIXED** |
| Migration / evidence consistency | `ProjectDatabase.kt:102-134` `MIGRATION_3_4` byte-identical to `105843e`; `:143-148` v4→v5 additive. `A2-SLICE1-EVIDENCE.md:15` still says version 4; `A2-SLICE1-REMEDIATION-REPORT.md:6-11,77-78,119` says "not committed/pushed" though remote is `f99668c`. Archived XMLs substantiate 74 of the claimed 85 targeted tests; adapter XML missing from the A2 archive. | `ExecutionRoomRoundtripTest` 6/6 pins project+handoff+lease; fresh-install DDL not asserted; test writes legacy `leaseAtMs`. | **PARTIALLY FIXED** |

## Additional confirmed defects inside scope

| ID | Evidence | Status |
|---|---|---|
| FAILED confirmation mislabelled | `ExecutionReconciler.kt:101-104` writes DB phase FAILED but returns `Outcome.COMPLETED`; `Outcome.FAILED` is never constructed. `AppContainer.kt:862` adopts COMPLETED over a FAILED row. | **STILL BROKEN** |
| Confirmed running not expressible | `EngineEvidence.terminalLifecycle` is terminal-only (`ExecutionEvidence.kt:13`); CONFIRMED + null → LOST (`ExecutionReconciler.kt:108-114`), contrary to D7 §9. | **STILL BROKEN** (production port is UNAVAILABLE, so not reachable today) |
| Stale generation on live event path | `ExecutionEvent` has no generation field (`ExecutionModels.kt:52-64`); controller matches by key+runId only (`:159-163`). Generation is captured durably but never checked on events. | **PARTIALLY FIXED** (recovery-only check exists) |
| Compound controller/adapter operations | `ExecutionController.onIntent` (`:132-136`) and `onConfirmedEvent` (`:159-181`) use multiple independent locks; `ExecutionEventAdapter.feed` (`:266-300`) mutates controller before any durable write. No latch test coordinates event/POST/DB. | **NOT PROVEN** |
| Engine terminal taxonomy | `CrushEvent.kt:20-41` has no failure/stop/disconnect event; `RunComplete.cancelled` (`:51-58`) is ignored by the adapter. Spec `crush-openapi.json:677-679,755-793` has no idempotency or non-dispatch wording. | **NOT APPLICABLE** for invented event types; `cancelled` handling **STILL BROKEN** |

## Disposition rules adopted for this pass

- No new event types. Terminal persistence uses the actual wire taxonomy:
  `RUN_COMPLETE` (with its `cancelled` flag where the decoder exposes it);
  failure from a `finish(reason=error)` part only where an existing accessor
  proves it; disconnect/invalidation stays UNKNOWN; unattributed frames never
  reach the durable path.
- No DAO guard is weakened to make a race test pass.
- An honest UNKNOWN/INCOMPLETE is preferred to a fabricated COMPLETED or LOST.
- Dated corrections will be appended to stale docs; historical lines stay.
