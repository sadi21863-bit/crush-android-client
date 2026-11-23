# V2 final integration review

Status: **verification record, 2026-11-23.** Read-only review of the V2 redesign
against the Rev 3.1 contract. No source, test or build file was changed by this
pass. It does not replace `V2-COMPLETION-LEDGER.md` (what shipped and how it was
measured) or `V2-GAP-AUDIT.md` (external audit verified against source); it
cross-checks the claims those records make at the seams between chat, shell,
security and engine.

## 1. Scope and method

Reviewed tree: `225ce94` plus the uncommitted V2-11 batch (three new JVM test
classes, a one-line parser fix in `DiagnosticsReport.kt` (`matches` →
`containsMatchIn`) that the new crash-terminator test exposed,
`V2-GAP-AUDIT.md`, `V2-COMPLETION-LEDGER.md`,
`docs/parity/2026-11-23-v2-baseline-perf/`, `tools/perf-baseline.ps1`).

**The brief itself is not in this tree.** A repo-wide search for
`crush-mobile-v2-design-brief-state-contract-test-plan-2026-11-14.md` returns
nothing, so the contract clauses below are sourced from the records that quote
it, not from the brief:

| Authority used | Why |
|---|---|
| `V2-0-reconciliation.md` §3 (state contract) and §4 (test registry) | line-by-line reconciliation of Rev 3.1 §5/§6, accepted in `V2-0-signoff-2026-11-22.md` |
| `V2-4-chat-redesign.md` §1 and §4 | the frozen §5.5.1 composer formula and the tests that must stay green |
| `V2-7-8-codebase-inventory-2026-11-22.md` §2.1 (line 181) | repeats the frozen composer table verbatim |
| `V2-GAP-AUDIT.md` | A1-A14 with exact locations; this review links rather than duplicates it |

Method and limits:

- Source + JVM/Robolectric evidence only. **No Gradle, adb, device or instrumented
  run was performed for this review.** Statements about executed results are
  quoted from the focused-run log and package records, not observed here.
- The three V2-11 test classes are **green in both runs: a focused run (19
  tests / 0 failures, `v2-ledger-tests-1123g.log`: `DiagnosticsReportTest` 11,
  `ChatAccessibilityTest` 4, `AppContainerBindingTest` 4) and the full four-task
  gate (981 tests / 104 suites / 0 failures; `v2-11-gate-2026-11-23.log`)**.
- Line numbers were opened at this tree, not recalled. Where a test exists but
  covers only part of a clause, the row says so instead of rounding up.
- A "test exists" claim was checked with a file lookup; a "test pins X" claim was
  checked by reading the assertion.

**Gate status:** Full four-task gate: green — 981 tests / 104 suites / 0
failures / 0 errors / 0 skipped; lint 0 errors / 1 pre-existing warning
(`v2-11-gate-2026-11-23.log`).

## 2. Verdict key

| Verdict | Rule |
|---|---|
| **VERIFIED** | Enforcement exists in code and an existing test asserts the behaviour (not merely that nothing crashed), and the two agree. |
| **PARTIAL** | Code enforces the clause, but the test covers only part of it, or the uncovered half is a different layer (Activity wiring, real process, device). The missing half is named. |
| **GAP** | No enforcement, or no test at all. Audit-confirmed gaps carry their `A` id from `V2-GAP-AUDIT.md`. |

Two rules apply to every row:

1. A test must assert **behaviour**, not absence of crash.
2. A test class that exists is not a test that ran. The V2-11 focused run now
   satisfies this rule for `DiagnosticsReportTest`, `ChatAccessibilityTest` and
   `AppContainerBindingTest` (19 tests / 0 failures, `v2-ledger-tests-1123g.log`);
   the full four-task gate is still pending.

## 3. State contract matrix (Rev 3.1 §5)

Clause text is taken from `V2-0-reconciliation.md` §3, which is the accepted
reconciliation of §5.

| # | Contract clause | Code | Test that pins it | Verdict | Note |
|---|---|---|---|---|---|
| S1 | Single source of chat state; the renderable view is a pure projection, not a second state | `ui/screens/chat/ChatViewModel.kt:636` (one `_state`), `:667-702` (`hostState` combine); `ChatViewState.kt:270-375`; `ChatScreen.kt:38`, `:108` | `ChatViewStateTest` 18 (determinism, totality, Android-free source scan at `:346`); `ChatHostInputsTest` 7 | **VERIFIED** | `ChatUiState` is the only mutable authority. `ChatScreen` does read four more flows for effects (S12); none of them builds the rendered view. |
| S2 | Phase/readiness comes from the same snapshot as the binding; a stale free-text phase cannot claim readiness | `ChatViewState.kt:98-112` (`statusLabel`), `:291` (`readiness`); `ChatHostInputs.kt:99-106` (mismatched key wins, then seed, then ready) | `ChatViewStateTest` `the header status is derived from readiness, not the stale phase` (`:230`), `a failed engine keeps its reason` (`:244`); `ChatHostInputsTest` `a mismatched key is switching for every engine state` (`:130`) | **VERIFIED** | `phase` is still free text and is shown only where readiness is `Starting`/`Failed` and it is the sole detail. The F-E 18-minute-stale header symptom is closed at the render seam. |
| S3 | Binding identity: a binding and its instance key are published together (Option A) | `di/AppContainer.kt:305-308`, `:715-725` (snapshot written before the same-key early return) | `AppContainerBindingTest` 4 (`the initial snapshot is the legacy pair`, `binding the chat pool moves the binding and key together`, `a same-key project label update still moves the snapshot`, `every published snapshot pairs its binding with its own key`); `EngineBindingTokenTest` 5 | **VERIFIED** | The container-level carry-over test exists in V2-11 and is green in the focused run and the full gate; its assertions are explicit. |
| S4 | Run-in-flight: a binding switch never kills a running turn; the signal outlives the ViewModel | `AppContainer.kt:340-359` (`engineRunInFlightFor`, key-guarded clear, deferred connection flush); `ChatViewModel.kt:3567-3570`, `:818-822` | No direct test of `reportRunInFlight`; `ConnectionRegistryTest` 15 pins the deferral/flush rule (`:206`) | **PARTIAL** | The registry half is pinned; the container flag and its key guard are inspection-only. A JVM container test should assert a foreign-key clear does nothing. |
| S5 | Pending permission is presented with all three decisions; dismiss is a deny, never an approval | `ChatViewModel.kt:434`, `:715-729` (stale-id guard); `ChatContent.kt:355-420`; `ChatDispatch.kt:58-69` | `ChatContentGateTest` 8 (`:72` three decisions, `:119` back = deny); `ChatDispatchTest` `:73`; `ChatGateTest` `:142` (does not gate composer) | **VERIFIED** | The stale-request drop (`ChatViewModel.kt:723-727`) is code without a test, but the decision mapping and the dialog are pinned. |
| S5b | Prompt lifecycle: a prompt from an ended/bootstrap-replaced run cannot survive | `ChatViewModel.kt:1675-1687` (bootstrap), `:3980-3985` (turn `finally`) | **no test** | **GAP (coverage)** | Clearing exists in both places and is correct by inspection, but neither clear is asserted. The dialog tests start from a fabricated pending prompt, so they cannot see lifecycle. |
| S6 | Pending question is shown without fabricating options; the projection drops the raw payload | `ChatViewState.kt:357-364` (`QuestionView`), `:242-251`; `ChatContent.kt:430-462` | `ChatViewStateTest` `:194` (title kept, raw dropped); `ChatContentGateTest` `:138` and `:156` (no option list; deny and dismiss release) | **VERIFIED** | The dialog is honest about not answering. Answering is absent: see A12 in §8. |
| S7 | Draft preservation and freshness: the captured callback decides with the current snapshot | `ChatScreen.kt:152-157`; `ChatDispatch.kt:22-33`; `ChatViewModel.kt:3536-3538` | `ChatHostCallbackTest` 4 (`:32`, `:61`, `:87`, `:113`); `ChatDispatchTest` `:19-70` | **PARTIAL** | Freshness is well pinned. **Draft survival is not tested**: `bindProject` (`ChatViewModel.kt:1081-1124`) does not clear `input`, so a draft crosses an instance switch, and no test asserts that. The field is only cleared on an accepted send (`:3741`). |
| S8 | Error/finish handling: a `finish` with `reason=error` is a failed turn, not an empty reply | `data/crush/CrushEvent.kt:86` (`StreamMessage`); `ChatViewModel.kt:4032-4033`, `:3963-3965` | `CrushProtocolTest` 5 finish rows (`:204`, `:218`, `:237`, `:263`); `ChatHistoryReplayTest` `:43` keeps a failed turn across reopen | **PARTIAL** | Parsing and replay are pinned. The ViewModel's finalisation marker ("no reply received", reserved for non-stopped turns) has no test; no test constructs `ChatViewModel`. |
| S9 | Transcript replacement: SSE republishes whole messages, so text is replaced, never appended | `ChatViewModel.kt:4371-4414` (`text = m.text.ifBlank { it.text }`), `:4612-4621` (`updateStreaming` maps only the owning bubble) | `RunHandleTest` `:99` pins tool accumulation by call id; `ChatStateMergeTest` 3 pins replay-vs-live merge; **no test asserts text replacement** | **PARTIAL** | The risk is real: an "append" regression would still render. Tool frames are the opposite rule (accumulate across LLM steps) and are pinned; the text rule is inspection-only. |
| S10 | Model picker state: selected model may be absent without becoming an error; loading, error, "not in current list" | `ChatViewState.kt:137-163`, `:271-282`, `:328-339`; `ChatContent.kt:1693-1700` | `ChatViewStateTest` `:121` (absent is not an error), `:334` (loading); `ChatContentSheetsTest` model rows (error, loading, not-in-list + Reload); `ChatModelMetadataTest` 6 | **VERIFIED** | The badge is still the legacy `-free` suffix rule (`ChatViewState.kt:277-280`), not catalogue `isFree`; T6-01 remains open and is not this clause. |
| S11 | Composer gates are exactly the frozen §5.5.1 table | `ChatViewState.kt:292-293`, `:352`; `ChatContent.kt:228-239` | `ChatGateTest` (7 methods; 8 rows at `:49-58`); `ChatContentGateTest` `:172`; `ChatAccessibilityTest` `:77`, `:86` | **VERIFIED** | See §4. The projected `canSubmitViaIme` has no production consumer (F6). |
| S12 | 5.5 #11: one collection per screen | `ChatScreen.kt:38-45` collects `hostState`, `engineStateForActiveInstance`, `activeInstanceKey`, `keyAvailable`; `:108` renders only `host` | **no test for the collection count**; the projection is pinned by `ChatGateTest`/`ChatHostInputsTest` | **PARTIAL** | 5 collections remain. They are effect inputs (start/stop/bind/retry), not render inputs, so the original "two sources of render state" defect is closed; the literal clause is not. |

### 3.1 Test registry (§6) cross-check

Only the IDs whose tests were opened for this review. `T2B3-01` (Review history) and
`T2B5-01` (Onboarding) are outside this pass's chat/composer/security focus; their
package records stand unchanged. Counts are read from source. Where a class body
was not re-read line by line, the row relies on its test names plus the package
record; no row claims a green run.

| Registry ID | Existing class (current count) | Verdict | Note |
|---|---|---|---|
| T2A-01 | `ChatViewStateTest` 18 | **VERIFIED** | Determinism, totality, Android-free source scan. Count grew from the reconciliation's 10. |
| T2A-02 | `ChatGateTest` 7 (eight rows in one method) | **VERIFIED** | The frozen §5.5.1 table. |
| T2A-03 | `ChatHostInputsTest` 7 | **VERIFIED** | Readiness/seed rules over every `EngineState`. |
| T2A-04 / T2A-04a | `ChatModelMetadataTest` 6 | **VERIFIED** | Pins the legacy `-free` suffix rule; the catalogue `isFree` replacement (T6-01) is still open. |
| T2A-05 | `ChatDispatchTest` 8 | **VERIFIED** | Draft gate, permission mapping, host effects, interaction ledger. The current file asserts **35** rows; `V2-0-reconciliation.md` said 37. Count drift recorded, not a behaviour defect. |
| T2B1-01 | `ChatContentTranscriptTest` 16 | **VERIFIED** | Transcript/roles/empty copy. |
| T2B1-02 | `ChatContentGateTest` 8 | **VERIFIED** | Permission, question, composer enablement. |
| T2B1-03 | `ChatContentSheetsTest` 16 | **VERIFIED** | Session/model/handoff sheets. |
| T2B1-04 | `ChatHostCallbackTest` 4 | **VERIFIED** | Draft-callback freshness. |
| T3-01 | `ShellNavigationTest` 10 | **VERIFIED** | Exists under a different name, as the sign-off records. Count grew from 8; the veto case is still bottom-bar-only (A1). |
| T6-01 | — | **GAP** | Catalogue `isFree` badge not implemented; `ChatViewState.kt:277-280` still uses the suffix. |
| T6-02 | — | **GAP** | No interaction-parity test of the brief's shape; `ChatDispatchTest`'s ledger is the closest. |

## 4. Composer gate matrix (Rev 3.1 §5.5.1)

The table in `ChatGateTest` (`app/src/test/java/com/crush/mobile/ui/screens/chat/ChatGateTest.kt:49-58`)
is **frozen**. Seven test methods cover it, and the eight rows live inside one
of them (`the eight composer rows are exact`). Its eight rows are the contract.
Renaming, reordering, adding a ninth row or "simplifying" the tuples is a
contract change that needs owner sign-off; the V2-7-8 inventory repeats the
freeze at line 181. The named states requested by the review are a mapping onto
that table, not a replacement for it.

Gate functions: `ChatViewState.kt:292` (`canType`), `:293` (`canSend`), `:352`
(`canSubmitViaIme`).

| Frozen row | Tuple (`ready/recoverable/prewarm/streaming/draft`) | `canType` | `canSend` | Nearest requested state | Verdict |
|---:|---|---|---|---|---|
| 1 | `T/T/T/false/""` | true | false | **empty** / **ready** | **VERIFIED** — empty draft never sends (`ChatGateTest:50`) |
| 2 | `T/T/T/false/"hi"` | true | true | **ready** / **running** | **VERIFIED** (`:51`) |
| 3 | `T/F/T/false/"hi"` | true | true | **ready**, recoverable irrelevant | **VERIFIED** (`:52`) |
| 4 | `F/T/T/false/"hi"` | true | true | **not-ready** but recoverable | **VERIFIED** (`:53`) — the 2026-11-03 recovery path; pinned again at `:74` |
| 5 | `F/F/T/false/"hi"` | false | false | **blocked** / **not-ready** | **VERIFIED** (`:54`) — prewarm on, engine unusable: field disabled, no silent no-op |
| 6 | `F/F/F/false/"hi"` | true | false | **no-key** shape / cold start | **PARTIAL** — the enablement shape is exact and pinned, but the gate is not key-aware: a missing key is phase text from bootstrap (`ChatViewModel.kt:1342-1350`), not a composer input. |
| 7 | `F/F/F/true/"hi"` | false | false | **streaming** | **VERIFIED** (`:56`); Stop must stay enabled and replace Send (`ChatAccessibilityTest:86`) |
| 8 | `T/T/T/false/"   "` | true | false | **empty** (whitespace) | **VERIFIED** (`:57`) — `isNotBlank`, not `isNotEmpty` |

Requested labels with **no composer row**:

| Label | Where it is actually enforced | Verdict |
|---|---|---|
| **locked** | App-level overlay (`MainActivity.kt:297-328`) plus a bootstrap refusal (`ChatViewModel.kt:1148-1166`). The composer is not a lock gate. | **GAP (coverage)** — `MainActivityLockFlowTest` exists under `app/src/androidTest` but has no executed result in this batch; no JVM test drives the overlay. |
| **no-key** | Bootstrap banner/phase (`ChatViewModel.kt:1342-1350`); the composer only sees `engineReady=false`. | **PARTIAL** — row 6 is the same enablement shape, but no test proves the key state produces it. |

Two honest notes on this matrix:

- `canSubmitViaIme` is computed and pinned (`ChatGateTest:132`) but **never
  consumed**. `ChatContent.kt:1377` recomputes
  `enabled && !streaming && sendEnabled && value.isNotBlank()`. The values are
  equivalent today; the field is a contract witness, not the enforcement point.
- The frozen table is keyed on four booleans, not on the brief's eight named
  states. Do not add a "locked" boolean to `ComposerView` to make the names fit;
  that would move an app-level gate into the content seam.

## 5. Security boundaries

| # | Boundary | Code | Test | Verdict | Note |
|---|---|---|---|---|---|
| SEC1 | API key at rest: AES-256/GCM ciphertext, master key in Android Keystore, one slot per provider | `data/local/SecureKeyStore.kt:87-137` (slots), `:198-217` (cipher), `:299-342` (key creation, StrongBox attempted first) | `SecureKeyStoreProviderScopeTest` 11 (slot layout, migration, scoped clear); `SecureKeyStoreInstrumentedTest` 3 (real cipher round trip, no plaintext at rest, tamper clear) — V2-9 record: `OK (3 tests)` on `bbf8ca69` | **VERIFIED** | JVM tests substitute `encrypt`/`decrypt` because Robolectric has no AndroidKeyStore; the real cipher has a separate device run. |
| SEC2 | Auth validity: is the key bound to user authentication? | `SecureKeyStore.kt:25-53` — **no** `setUserAuthenticationRequired`; `AppLockPolicy.kt:15-26` says so explicitly | `SecureKeyStoreInstrumentedTest` reads without a prompt; `AppLockPolicyTest` `:33-45` | **VERIFIED** | "Verified as designed", not as a user-presence control: the at-rest key is decryptable while the app is locked, and the lock gate protects the UI and drops the in-memory key, not the ciphertext. `docs/project-analysis.md` §security records the same ("Keystore auth gating: None"). This is a deliberate trade recorded after an API 30+ onboarding crash. |
| SEC3 | Lock gate: cold start prompts; grace window; overlay rather than a route; cancel stays locked | `ui/AppLockPolicy.kt:57-88`; `data/local/LockState.kt`; `MainActivity.kt:405-440` (process state, fresh unlock, 300 s at `:476`); `ui/screens/lock/LockOverlay.kt` | `AppLockPolicyTest` 16 (`:42` cold start, `:66` never open without a key, `:140` fresh unlock once); `LockStateTest` 9 (`:41`, `:60`, `:91` recreate) | **PARTIAL** | The decision function and temporal state are fully pinned. The Activity wiring (ownerVerified, monotonic age, FLAG_SECURE ordering) and the overlay itself are inspection-only in this review; the historical device verification is recorded in `docs/CRUSH-MOBILE-STATUS-AND-DECISIONS.md`, not re-run here. |
| SEC4 | Provider-key scoping: one credential per provider; a second save does not destroy the first; bare key normalization | `SecureKeyStore.kt:87-137`, `:219-256`; `data/crush/CrushApi.kt:487-494`, `:597-611` (`normalizeApiKey`) | `SecureKeyStoreProviderScopeTest` 11; `CrushProtocolTest` normalize rows (`:24-119`); `BootstrapOrderTest` `:107-151` (provider-key before model) | **VERIFIED** | `api_key` is sent bare; the `OPENCODE_API_KEY=` prefix is stripped, and a `getKey()`/`byte[]` path is not used. |
| SEC5 | Project trust re-gates when `crushrc`/`.crushrc` changes, appears or vanishes; consent is bound to bytes | `data/project/ProjectTrustService.kt:83-115` (gate), `:147-167` (record only gate-observed state), `:185-204` (name-bound composite digest); restore paths `AppContainer.kt:517-581`, `:654-696` | `ProjectTrustServiceTest` 25 (changed/removed/appeared/rename/dotted/two-file, plus gate-window moves); `ProjectTrustRoomRoundtripTest` | **VERIFIED** | Gate verdicts are pinned. Every production call site gating before bind is inspection-only; the restore and handoff paths are cited, not each independently tested. The contract is check-at-bind, not TOCTOU-free. |
| SEC6 | Diagnostics export masking: the copied block, including log tail, crash text and notes, passes the key net | `ui/screens/diagnostics/DiagnosticsReport.kt:192-237` (`buildReport` → `redactSecrets` at `:237`), `:251`; 200-line tail at `:328` | `DiagnosticsReportTest` 11 (`:79` tail key, `:90` crash key, `:107` truncation marker, `:138` notes) | **VERIFIED** | Report-builder level only; focused run 11/11 green. The screen, clipboard and share intent are still **NOT PERFORMED** on device (audit A9 nuance). |
| SEC7 | Log masking at the sink: persisted lines and logcat both mask key-shaped tokens | `util/AppLog.kt:129-154` (mask before both sinks), `:213-252` (patterns, UUID exemption) | `AppLogTest` 23 (brand keys, generic token, brand-less `sk-`, UUID pass-through, exception names) | **VERIFIED** | Over-masking is an accepted trade recorded in the class KDoc. |
| SEC8 | Backup exclusion: `allowBackup=false`, full-backup off, cloud/transfer rules exclude every domain | `AndroidManifest.xml:32-34`; `res/xml/data_extraction_rules.xml` | **no test** | **GAP** | Configuration is correct by inspection but no test (instrumented backup check or manifest assertion) proves it. Not a code defect; an unverified claim. |
| SEC9 | Permission application fails closed: a rejected `permissions/mode`/`skip` must fail the open, not continue | `data/crush/WorkspaceManager.kt:233-244` (log-only `onFailure`), `:255` (`agent/init` still runs), `:282-295` (reattach replays the recorded request) | **no test** | **GAP — A7/A7b** | The engine's effective policy is whatever it was; the app records and replays the request as if applied. No read-back exists. Needs the owner-sign-off batch `V2-AUDIT-0`; see the A7 section of `V2-GAP-AUDIT.md`. |

Enforcement is deliberately ordered: SEC1/2 are storage, SEC3 is the visible
gate, SEC9 is the engine-policy gate. A failure in SEC9 is not a storage failure
and must not be filed as one.

## 6. Engine isolation

| # | Isolation claim | Code | Test | Verdict | Note |
|---|---|---|---|---|---|
| E1 | Per-instance UDS path: legacy `crush.sock`, other keys `crush-<key>.sock`, app-private | `engine/EngineSockets.kt:54-56`; spawn `CrushEngine.kt:221`, `:354-359` (`-H unix://…`) | `EngineSocketsHostnameTest` 3 (hostname pin, missing file → null); `TwoEngineInstancesPerProjectTest` (androidTest, uses `socketPath(context,"pa"/"pb")`) — not executed in this review | **PARTIAL** | No JVM test asserts the `crush-<key>.sock` naming or that the path is under `filesDir`. The instrumented test would prove it but has no run in this batch. |
| E2 | Per-project data dir: engine state, config, cache, tmp under `crush-<key>` | `EnginePaths.kt:43-57`, `:68-89` (`CRUSH_GLOBAL_CONFIG`/`DATA`/`CACHE`, `HOME`) | **no test** | **GAP** | Code is explicit; nothing pins that two keys cannot share a state tree. This is the isolation claim the session-pool finding depended on. |
| E3 | Legacy vs project vs chat-pool instances: legacy is the permanent singleton, outside the registry and budget; chat and projects are registry keys | `di/AppContainer.kt:251-257`, `:852-861`; `EngineInstanceRegistry.kt:21-28`, `:128-129`, `:163-164` | `EngineInstanceRegistryTest` `:144` (legacy never stops/counts), `:239` (legacy start uncapped); `AppContainerBindingTest` `binding the chat pool moves the binding and key together` (chat key), `a same-key project label update still moves the snapshot` (project key) | **VERIFIED** | The legacy key `""` hosts Crush Chat; the compatibility `chat` key still exists for pre-split sessions. |
| E4 | Spawn veto: `maySpawn` consulted at the last moment inside the same monitor as `stopBlocking`; a throw vetoes | `CrushEngine.kt:125` (default true), `:381-398` (critical section), `:639-643` (`spawnVetoed`); `EngineSupervisor.kt:213` installs `{ isWanted() }` | `CrushEngineSpawnVetoTest` 4 (`:40`, `:46`, `:57`, `:68`); `ChatPolicySpawnGateTest` 5 (policy gate) | **PARTIAL** | The pure decision and lazy single evaluation are pinned. The actual "no `exec`, no `process` assignment" path needs a real `Process`/Context and is explicitly left to the device drive in the test KDoc. |
| E5 | Registry budget: at most two non-legacy live children, fail-closed with a named reason | `EngineInstanceRegistry.kt:128-144`, `:199`, `:206-208` | `EngineInstanceRegistryTest` `:156` boundary, `:185` third refused, `:205` own slot reclaimed, `:216` grains — **none exercises concurrency** | **GAP — A8** | Boundary logic is pinned; check-and-reserve is not atomic. `STARTING` is written after the check and is not counted (`:142`), and the only caller is `ChatViewModel.startEngine` (`:607`) on the main thread today. Structural, not observed. Batch `V2-AUDIT-3`. |
| E6 | Engine death/restart supervision: unhealthy child restarts with bounded budget/backoff; idle stop is distinguished from crash | `EngineSupervisor.kt:203-465` (`supervise`), `:504-633` (`monitorUntilUnhealthy`), `:649-655` (`setWorkActive`/`isWanted`) | `SupervisorHysteresisTest` 17 (start budget, hard failures, wedge budget, watchdog thresholds, pressure loop) | **PARTIAL** | Decision functions and the transcribed pressure loop are pinned; the live loop (process table, socket probe, restart) is not JVM-tested. Chaos `S01` history exists but was not run in this review. |
| E7 | No TCP: engine REST/SSE rides only the UDS; the one loopback listener is the egress relay, not engine ingress | `CrushEngine.kt:27-29`, `:190-194`, `:354-359`; `EngineSockets.kt:58-70`; `AndroidManifest.xml:42` (`usesCleartextTraffic=false`); `EngineProxy` | `EngineSocketsHostnameTest` 3; `CrushApiRestTransportTest` (socket client / absent file / scrubbed path) | **PARTIAL** | Transport selection is pinned at the client factory. No test asserts the engine's spawn args contain `unix://` and no port; that is inspection + instrumentation. |

## 7. Integration findings

These span two packages and are not owned by any single package record. They are
recorded, not fixed.

**F1 — Chat/Code share one route, and `codeActive` decides the selected tab.**
`MainActivity.kt:261-265` derives `codeActive` from
`activeBinding is EngineBinding.Project`; `CrushShell.kt:160-170` maps
`Projects` selected when `codeActive` and route is `chat`. `ShellNavigationTest`
`:76` pins the selection. This is audit A6 (monitor, no rewrite) and remains the
one place where "route" and "mode" are not the same thing.

**F2 — The run veto is bottom-bar-only.** The bottom bar consumes the Boolean
(`CrushShell.kt:188-195`); the rail (`:229-232`) and extended rail (`:293-296`)
call `onSelectChat` for its side effect and navigate regardless. The only veto
test runs at `w411dp` (`ShellNavigationTest:98`); the rail tests (`:210`, `:222`)
never exercise a false callback. This is **GAP — A1**, batch `V2-AUDIT-1`. The
audit's nuance stands: it is a consistency defect, not a run-safety defect,
because the veto only suppresses the binding reset.

**F3 — Diagnostics exposes no pinned engine version or contract level.** The
report data class has no version field (`DiagnosticsReport.kt:22-51`) and
`describeEngineState` (`:282-293`) drops `EngineState.Running.version` even
though the state carries it (`EngineState.kt:11-14`). About hard-codes
`"Version 1.0.0"` (`SettingsContent.kt:432`). V2-6 D7 records this as a decision
("no host-exposed value exists; inventing one is out of bounds"), and the records
disagree about the bundled version (0.100.0 vs 0.98.1; ledger §6 row 3 keeps both).
This is a **deliberate GAP**, not a regression.

**F4 — Relative-time labels freeze at composition.** `ChatListContent.kt:62` and
`ChatContent.kt:1428` both capture `remember { System.currentTimeMillis() }`.
`SessionList.relativeTime` is pure and tested (`SessionListTest` 16), but a list
left open shows "now" indefinitely. This is audit A2's design backlog row; there
is no refresh ticker and no test for staleness.

**F5 — Review export says "as it sits on disk now" but is labelled "Export a
copy".** `ReviewContent.kt:107-113` states the semantic; `:339-342` repeats it at
the export seam; the button is `"Export a copy"` (`:351`). `ReviewContentTest`
`:251` pins the frozen root + relative path and `:268` the outcome message, not
the current-vs-historical distinction. This is **A3**, copy-only batch
`V2-AUDIT-2`. The per-turn, latest-turn-only, process-scoped history is a separate
V2-5 contract and is documented there.

**F6 — `canSubmitViaIme` is a projected contract value with no consumer.** It is
computed at `ChatViewState.kt:352` and pinned at `ChatGateTest:132`, but a
production grep finds only those two lines. `ChatContent.kt:1377` recomputes the
same conjunction. Because the composer table is frozen, this is recorded, not
changed: a future edit must either consume the field or delete it with owner
sign-off, not "tidy" one half.

**F7 — The chat screen still collects five flows.** `ChatScreen.kt:38-45` reads
the host snapshot plus engine state, active key and key availability. Only the
host snapshot builds `view` (`:108`), so the original two-render-source defect is
gone; the literal "one collection per screen" clause is not. Any refactor must
keep the effect inputs (start/stop/bind/retry), so deleting collections is not
free.

## 8. Release statement

**What this review certifies.** At `225ce94` plus the uncommitted V2-11 batch, the
state-contract clauses that are marked VERIFIED have both an enforcement point
and a test whose assertions match the code, and the composer table in
`ChatGateTest` still matches the frozen §5.5.1 formula. Security storage,
provider scoping, trust re-gating, log masking and report masking have real
tests. Engine isolation has real code, but several of its claims are partial or
untested.

**What it does not certify.** A green CI gate would not mean release-ready, for
four reasons:

1. **The gate compiles more than it executes.** CI builds the androidTest APK;
   instrumented classes are not run there, and only `SecureKeyStoreInstrumentedTest`
   has a stored device result.
2. **The V2-11 classes are green in both runs.**
   `AppContainerBindingTest`, `DiagnosticsReportTest` and
   `ChatAccessibilityTest` passed the focused run (19 tests / 0 failures,
   `v2-ledger-tests-1123g.log`) and the full four-task gate (981 tests / 104
   suites / 0 failures, `v2-11-gate-2026-11-23.log`).
3. **No device run happened here.** The composer, lock overlay, rails, relative
   time and diagnostics share path were not exercised in this review.
4. **One security promise is not enforced.** A rejected permission policy is
   logged and then treated as applied. That is a security-surface gap, not a
   polish item.

**Open GAPs (release-gating set):**

| ID | Gap | Where | What closes it |
|---|---|---|---|
| **A7 / A7b** | Permission application is fail-open; a failed apply is recorded and replayed by reattach. No read-back. | `WorkspaceManager.kt:233-244`, `:255`, `:282-295` | `V2-AUDIT-0`: treat a non-2xx policy apply as an open failure with a named result; read back the effective mode if the engine exposes it; path-keyed `MockWebServer` tests (create 200 + mode 500 must skip `agent/init`); a failed reattach must not report attached. **Needs owner sign-off (behaviour change).** |
| **A1** | Rail/extended-rail discard the run veto; the same tap behaves differently by width. | `CrushShell.kt:188-195` vs `:229-232`, `:293-296` | `V2-AUDIT-1`: one `selectPrimary(destination): Boolean`, used by all three layouts, with a 6-case test matrix keyed to each layout's test tag. |
| **A8** | Budget check and reservation are not atomic; `STARTING` is set after the check and is not counted. | `EngineInstanceRegistry.kt:128-144`, `:199` | `V2-AUDIT-3`: atomic reservation, or an explicit single-thread contract plus an assertion. Today's only caller is main-thread `startEngine`. |
| **A12** | The app cannot answer agent questions; it can only deny/dismiss, which cancels the batch. | `ChatContent.kt:430-462`; transport already exists at `CrushApi.kt:477`, `:481` | Question milestone (large): choice/free-text UI plus resolution state over `answerQuestions`/`cancelQuestions`; today the dialog is honest and the raw payload is preserved. |

Non-release coverage gaps also recorded: the locked composer/overlay path has no
executed test (SEC3, §4); backup exclusion has no test (SEC8); per-project data
dirs have no test (E2); per-instance socket naming has no JVM test (E1); the
permission/UI copy items A3, A4, A5, A10 and A11 remain in `V2-AUDIT-2` and were
not re-verified here.

**Closing these is a separate authorized batch.** `V2-AUDIT-0` needs explicit
owner sign-off; `V2-AUDIT-1` and `V2-AUDIT-3` are small; A12 is a milestone.
Nothing in this review authorizes a fix, a build, or a release.

## 9. Evidence index

| Path | Role |
|---|---|
| `docs/v2/V2-0-reconciliation.md` | accepted Rev 3.1 §5/§6 reconciliation; clause source for this review |
| `docs/v2/V2-0-signoff-2026-11-22.md` | V2-0 acceptance and the carry-over rows |
| `docs/v2/V2-4-chat-redesign.md` | frozen composer formula, chat test plan and device findings |
| `docs/v2/V2-7-8-codebase-inventory-2026-11-22.md` | line-level inventory; repeats the composer freeze |
| `docs/v2/V2-GAP-AUDIT.md` | A1-A14 verification, severity, batch IDs; not duplicated here |
| `docs/v2/V2-COMPLETION-LEDGER.md` | shipped/tested/device labels; V2-11 focused-run and full-gate status |
| `docs/v2/V2-9-release-hardening.md` | Keystore instrumented run, ASK default, secret scan |
| `docs/parity/2026-11-19-v2-0-baseline/MANIFEST.md` | the F-E stale-phase capture and its limits |
| `docs/parity/2026-11-20-v2-4-chat/MANIFEST.md` | chat device set (composer, sheets, Retry, 2.0x) |
| `docs/parity/2026-11-20-v2-5-code/MANIFEST.md` | project binding, Review, export, trust re-gate |
| `docs/parity/2026-11-21-v2-6-settings/MANIFEST.md` | Settings/permissions; Diagnostics version decision |
| `docs/parity/2026-11-22-v2-9-hardening/01-securekeystore-instrumented.txt` | real-Keystore 3-test run |
| `app/src/test/java/com/crush/mobile/...` | JVM/Robolectric classes cited per row |
| `app/src/androidTest/java/com/crush/mobile/...` | instrumented classes; only `SecureKeyStoreInstrumentedTest` has a stored run |
| `app/src/main/java/com/crush/mobile/...` | production enforcement points cited per row |

No credential is read, written, printed or copied by this review.
