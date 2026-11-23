# V2-13 / A7-A7b - Permission policy fails closed

**Status: COMPLETE (JVM scope), 2026-11-23.** Owner-authorized security
follow-up to `V2-GAP-AUDIT.md` A7/A7b (`V2-AUDIT-0`): permission-policy
application is now a hard prerequisite of `WorkspaceManager.open()`. This is an
implementation record, not a release statement - no device, instrumented,
TalkBack or API 30+ run exists, and Gate 7 is untouched. Base is `6cdd2fc` plus
this change's working-tree diff (two production files, one new test class);
paths, not hashes, are the durable reference.

## 1. Why this was P0

The UI promise is "ASK means the agent asks before it acts." Before this change
the engine could be running a different policy than the one the UI showed:

- `open()` applied `permissions/mode` (v0.100.0+ family) or
  `permissions/skip` (v0.99 family) as a best-effort: a non-2xx was logged, the
  code continued into `agent/init`, and `open()` returned a workspace. No
  read-back exists, so a rejected apply left the engine at its previous policy.
- A7b made it durable: `reattachIfStale()` re-applies the mode recorded at open
  time, and that record is the *request*, never a confirmed result. Every later
  reattach repeated the request and reported the workspace attached.
- The lease was written before the apply, so a later non-forced reattach could
  treat the workspace as fresh and skip the retry.

The unsafe direction is concrete: the app shows ASK while the engine keeps an
earlier auto-approve policy. A dialog that never appears cannot be noticed, and
no test failed because `open()` still succeeded.

## 2. Behavior before and after

| Step | Before | After |
|---|---|---|
| policy apply fails | log and continue | log the mode and failure, flush `workspaceId`/`workspaceGeneration`, throw `PermissionPolicyException` |
| `agent/init` | always runs | skipped; `open()` throws first |
| lease (`lastAttachedAtMs`) | set before the apply | set only after the policy applies |
| `open()` result | workspace returned | `PermissionPolicyException` (`IOException`) with `requestedMode` |
| failed reattach | replayed the request, reported attached | returns `false`; the stale id is already flushed |
| chat bootstrap | proceeded to key read / send | named phase, `engineReady=false`, returns before reading the key |

The workspace is still created before the policy is applied; the client never
uses that id. Path and data dir stay recorded, so a later retry re-opens the
same logical workspace and re-applies the same request. The failing open does
not initialise an agent.

## 3. Code paths

`app/src/main/java/com/crush/mobile/data/crush/WorkspaceManager.kt`

- `PermissionPolicyException` (`:12-18`) - extends `java.io.IOException`, carries
  `requestedMode` (`ask`, another wire mode, or `skip=<bool>`), keeps the cause.
- `open()` (`:193-272`) - creates the workspace, commits identity and the
  requested mode, calls `applyPermissionPolicy` (`:248`), then sets the lease
  (`:253`), then runs `agent/init` (`:264`). A rejection escapes before both.
- `applyPermissionPolicy()` (`:286-301`) - picks the wire family, runs it
  through `runCatchingNonCancellation`, logs
  `permission policy apply failed (<mode>)`, flushes the id and generation, and
  throws. `runCatchingNonCancellation` (`CrushSession.kt:21-27`) rethrows
  `CancellationException`, so a cancelled caller aborts rather than becoming a
  policy failure.
- `reattachIfStale()` (`:311-334`) - returns `false` when the forced `open()`
  fails; the flush means a subsequent non-forced call cannot short-circuit on a
  cached id.

`app/src/main/java/com/crush/mobile/ui/screens/chat/ChatViewModel.kt`

- `bootstrap` catch (`:1324-1341`) - logs `engine rejected permission policy
  '<mode>'`, sets the named phase ("engine rejected the permission policy
  (<mode>). Nothing was sent; check the engine version and try again.") with
  `engineReady = false`, then returns. The key read (`:1344`) and every later
  step are not reached.
- `CrushSession.openWorkspace` (`CrushSession.kt:58-88`) selects the wire family
  from the bound engine version and does not catch the exception.

## 4. Tests

New `app/src/test/java/com/crush/mobile/data/crush/WorkspacePermissionPolicyTest.kt`
(4 tests), path-keyed `MockWebServer` dispatcher so request order/count cannot
route a response to the wrong endpoint:

1. `rejected mode policy fails the open and never initialises the agent` - 500
   on `permissions/mode` throws `PermissionPolicyException` naming `ask`, no
   `/agent/init` is recorded, `currentWorkspaceId` is null.
2. `rejected legacy skip policy fails the open and never initialises the agent` -
   same for `permissions/skip`; the label starts `skip=`.
3. `accepted policy still runs agent init and keeps the workspace` - a 2xx
   apply still reaches `/agent/init` and keeps the id, so the gate is not
   over-broad.
4. `a failed reattach reports not attached and flushes the id` - a rejected
   forced reattach returns `false` and leaves no id.

`BootstrapOrderTest` (9 tests) is unchanged and still green; it needed no edit.

## 5. Gate

`v2-14-a7-gate-2026-11-23.log` (repo root, local-only; `*.log` is gitignored):

| Item | Result |
|---|---|
| Tasks | `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, `:app:lintRelease` |
| Build | **BUILD SUCCESSFUL in 33m 38s** |
| Tests | **1001 tests / 105 suites / 0 failures / 0 errors / 0 skipped** |
| Lint | **0 errors / 1 pre-existing warning** |

The tally was re-read from `app/build/test-results/testDebugUnitTest/*.xml` (105 suite files).

## 6. Limits

- **No effective-mode read-back.** A 2xx on `permissions/mode` or
  `permissions/skip` is the proof; an engine that accepts and ignores the
  request cannot be detected.
- **One caller is covered.** Only `ChatViewModel.bootstrap` maps the exception
  to UI state; other `WorkspaceManager` callers (tests, handoff) see the throw
  or `false` and must handle it.
- **No device or instrumented run.** `assembleDebugAndroidTest` compiles only;
  no phone has seen a real engine reject a real policy.
- **Engine-side residue.** The workspace was created before the rejection; the
  client discards the id and leaves it to be reaped.
- Still open: A12, A2, A6, A13, device/TalkBack/API 30+ rows, Gate 7.

## 7. Evidence index

| Path | Role |
|---|---|
| `docs/v2/V2-GAP-AUDIT.md` | A7/A7b verdicts, severity, V2-AUDIT-0 decision |
| `docs/v2/V2-AUDIT-FIXES.md` | batch record; A7 row, test pins, gate and limits |
| `app/src/main/java/com/crush/mobile/data/crush/WorkspaceManager.kt` | exception, `open`, `applyPermissionPolicy`, `reattachIfStale` |
| `app/src/main/java/com/crush/mobile/ui/screens/chat/ChatViewModel.kt` | bootstrap catch and named phase |
| `app/src/main/java/com/crush/mobile/data/crush/CrushSession.kt` | `runCatchingNonCancellation`, `openWorkspace` |
| `app/src/test/java/com/crush/mobile/data/crush/` | `WorkspacePermissionPolicyTest` (4 new), `BootstrapOrderTest` (9) |
| `v2-14-a7-gate-2026-11-23.log` | full-gate log (local, untracked) |

## 8. How to verify

Focused run (4 + 9 tests):

```powershell
.\gradlew.bat :app:testDebugUnitTest `
  --tests "com.crush.mobile.data.crush.WorkspacePermissionPolicyTest" `
  --tests "com.crush.mobile.data.crush.BootstrapOrderTest"
```

Full gate:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintRelease
```

Re-read `app/build/test-results/testDebugUnitTest/*.xml` for the tally rather
than trusting a summary line.
