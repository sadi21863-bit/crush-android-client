# A2 slice 1 — remediation reconnaissance (2026-11-27)

Repository state checked BEFORE any edit: branch `v2-16-planning-baseline`,
HEAD `105843e9886565852e94aa097cbbf5065625ac3e`, remote head identical,
worktree clean apart from pre-existing untracked `v2-66-phaseF-stuck.png`.
`main` is `adc26c0` and was not touched. A1 remains accepted and is not reopened.

Documents read: V2-16 plan (§5.2.1, §14, §16), execution-layer design (§5, §6,
§7, §9, §17.1), A2 scope, A2 evidence, A1 acceptance, D7 decision and the
frozen risk register. Source inspected: `ExecutionEntity`, `ExecutionDao`,
`ExecutionStore`, `ExecutionReconciler`, `ExecutionController`, `AppContainer`,
`ChatViewModel`, `ProjectDatabase`, `RoomProjectStore` and all related tests
and migration fixtures. Each finding below was verified against CURRENT source;
none was assumed still present.

## Finding-by-finding table

| ID | Current source evidence | Status | Proposed correction (in-scope) | Tests required |
|---|---|---|---|---|
| **A1** | `ChatViewModel.kt:4034-4041` checks `marked.isFailure` only; `ExecutionDao.kt:37-42` returns `Int`, `ExecutionStore.kt:52` passes it through; POST at `:4059` | **Confirmed present** — exceptions checked, `0` ignored | Require affected-row count `== 1`; a `0` refuses the POST with a named controller rejection | Guard test: fake `markSent` returns 0 → no POST; throws → no POST |
| **A2** | `ChatViewModel.kt:4087` controller advances, `:4090` durable ack written afterwards with `runCatching{}` result discarded; DAO guard `ExecutionDao.kt:45-51` | **Confirmed present** — wrong order, both `0` and exception swallowed | Write durable ack FIRST; advance controller only on a committed write; otherwise record ambiguity and do not claim acknowledged | Guard test: `markAcked` 0 / throws → controller stays `SEND_ATTEMPTED` |
| **A3** | `ChatViewModel.kt:3979-3996, 4034-4041, 4059` | **Correct for exceptions only; `0` gap is A1** | Covered by A1 | POST-count assertions |
| **A4** | `ChatViewModel.kt:4077, 4108-4110, 4113` discard all results | **Confirmed present** | Check counts and log named refusal; never report silent success | Guard tests for reject/ambiguous 0 and throw |
| **A5** | `ExecutionDao.kt:63-67` `markRejected` lacks ack guard; `:98-104` `markUnsent` can hit AMBIGUOUS; `:107-113` `markLost` includes terminal `UNKNOWN`. Terminal set `ExecutionController.kt:286-290` | **Confirmed, latent (not reachable via current call sites)** | Add `AND ack_state='SENT_UNCONFIRMED'` to `markRejected`; exclude AMBIGUOUS from `markUnsent`; drop `UNKNOWN` from `markLost` | DAO no-op tests for each |
| **A6** | Existing tests are DAO/controller/reconciler only (`ExecutionRoomRoundtripTest.kt:170-191`, `ExecutionReconcilerTest.kt:77-87`, `ExecutionControllerTest.kt:99-111,136-149`) | **Confirmed absence at send-path level** | Add a pure guarded-write seam used by production and deterministic tests driving it | 4 tests (A1/A2 × 0/throw) |
| **B1–B4** | `ChatViewModel.kt:3950,3964,4104-4105,4112-4113,4117-4176`; retry mints new `runId` (`:3965`) + new `executionId` (`ExecutionController.kt:67`) + new row; `spec/crush-openapi.json:678-679,755-756,776-793` and design `:93-98,263-285` give NO proof that a 404 proves non-dispatch | **Confirmed present** — automatic post-POST retry on an unverified assumption, while the first row is `AMBIGUOUS` | Remove post-POST retry; classify every POST outcome without a durable ack as ambiguous; keep pre-POST reattach (`:3762`) | Decision tests: 404 → ambiguous, no second POST, one row, stable id, no `retrySourceExecutionId` divergence |
| **B5** | `SessionContinuityTest.kt:37-40,88-115` is a pure mirror of string matching; no send-path POST count test | **Confirmed absence** | — | Add decision tests above |
| **C1–C3** | `ExecutionReconciler.kt:7-12,44-73` marks `LOST` solely on `recovery:no_atomic_attach`; `ReconciliationResult` (`ExecutionModels.kt:33-41`) is unused in production; no unknown/evidence outcome exists | **Confirmed present** | Add an evidence port; use the approved vocabulary; map UNAVAILABLE/INCOMPLETE/CONFLICTING/NO_EVIDENCE → visible `UNKNOWN` with named reason, not `LOST` | Evidence variants incl. query failure, incomplete, conflicting, confirmed terminal, confirmed absence, idempotency |
| **C4** | `AppContainer.kt:148-153,336-371` has no `CrushApi`/`WorkspaceManager`; `CrushApi.kt:287-288,342-343` exist but are not wired at boot | **Confirmed** — default evidence is genuinely UNAVAILABLE | Default production port returns UNAVAILABLE truthfully | Test that UNAVAILABLE → UNKNOWN, not LOST |
| **E1–E4** | `ExecutionReconciler.kt:54-56` defers run-side AMBIGUOUS with a prompt-specific label; `AppContainer.kt:836-841` drops `DEFERRED`; `ExecutionDao.kt:29-34` keeps `SEND_ATTEMPTED` discoverable | **Confirmed present** — ambiguous send never visible and mislabelled C6 | Make run-side ambiguity a stable visible state (`SEND_AMBIGUOUS`), adopt every startup, remove D3/A3 label | 3-restart test; three consecutive reconciliations stay visible |
| **E5** | `ExecutionReconcilerTest.kt:105-119` does 2 passes, no container/controller/3rd restart | **Partial** | — | Extend to 3 cycles through real container |
| **F1–F2** | `ChatViewModel.kt:3983-3988` stores binding token + filesystem path; `AppContainer.kt:845-849` feeds them into `ExecutionTarget(instanceKey, workspaceId, sessionId)` (`ExecutionModels.kt:43-47`); real keys are `""`/`"chat"`/`"p<uuid8>"` (`AppContainer.kt:425-432`) | **Confirmed present** — semantically incompatible reconstruction | Map token → real instance key explicitly; do not put a path in `workspaceId`; keep path only as row metadata | Token→key mapping test; no path-as-id assertion |
| **F3–F4** | `ChatViewModel.kt:3986` stores `generation = null`; no generation comparison in `ExecutionReconciler.kt:17-20`; no stale-generation test | **Confirmed present** | Capture generation at send where `mgr` is in scope; compare via evidence port; mismatch → CONFLICTING/UNKNOWN, never attach | Match / mismatch / missing / invalidation-between-validation-and-adoption tests |
| **F5** | No `withPathLock` in main; design `:289-334`, plan `:935-959` require same-critical-section attach | **Confirmed** — no atomic attach claimed (explicitly disclaimed in reconciler) | Do not claim atomicity; keep unknown outcome | No production atomicity claim in evidence |
| **F6** | Design §5 `:225` `lease_epoch_ms`; implementation `lease_at_ms` at `ExecutionEntity.kt:38`, `ProjectDatabase.kt:112`, `ExecutionStore.kt:29,39`, `ChatViewModel.kt:3987`, `ExecutionRoomRoundtripTest.kt:85` | **Confirmed present** — canonical design contract is `lease_epoch_ms` | Align implementation to design through an additive column migration; do not rewrite shipped MIGRATION_3_4 | Migration + roundtrip assertions on canonical column |
| **D1–D3** | `AppContainer.kt:831-863` writes only controller + `AppLog`; `ChatViewModel.kt:735-745` combine excludes `executionController.executions`; no UI reads it (repo-wide grep); `ChatViewState`/`ChatListContent` carry no execution outcome | **Confirmed present** — recovered records are controller-map-only | Add controller flow to hostState, one defaulted host input, one defaulted notice field, one display-only line in the list. No new prompt action | Pure projection test + Compose render test |
| **D4** | UI suites contain no execution-outcome assertion; `ExecutionReconcilerTest.kt:122-155` stops at controller | **Confirmed absence** | — | Two tests above |
| **G1–G2** | `ExecutionRoomRoundtripTest.kt:43-75` creates empty `handoffs` and inserts only a project (`:68-72`); assertions `:107-109` are partial; evidence doc `:25-28` overclaims handoff preservation | **Confirmed present** | Insert fully populated + NULL-optionals handoff rows before v3 and assert every field after migration; correct evidence wording | Extend migration test |
| **G3** | Entity `ExecutionEntity.kt:29-48` vs DDL `ProjectDatabase.kt:105-132` and DAO `:21-119` | **Not reproducible as an internal mismatch** — columns/types/nullability/PK/indexes agree | None for code; note ack-state list difference in report | Existing table/index test retained |
| **G4** | `RoomProjectStore.kt:32-39,56` registers MIGRATION_3_4; no `fallbackToDestructiveMigration*` anywhere | **Already correct** | None | None |

No C4/C5/C6, D3/X1, A3, R37, D1/D2/D4/D7, `main`, PR, merge or new library is
touched by any proposed correction above.
