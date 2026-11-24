# V2-15 Phase D — permission-policy verification

Status: **verification complete, 2026-11-24.** Scope is the existing V2-13
A7/A7b fail-closed implementation. One reproducible defect was found and
narrowly corrected (a cancelled re-open could leave a fresh old lease covering
an unconfirmed policy). No permission-policy redesign, no L1/L3 change, no Go
engine or dependency change, no device claim from JVM tests. Phase D is
committed on `v2-15-lifecycle-hardening` as a follow-up to `a1be361`; no PR or
issue was made.

## 1. Verdict

| Contract | Verdict | Evidence |
|---|---|---|
| Rejected `permissions/mode` cannot reach `agent/init` | **Holds** | `WorkspacePermissionPolicyTest`; scenario 2 |
| Rejected `permissions/skip` cannot reach `agent/init` | **Holds** | `WorkspacePermissionPolicyTest`; scenario 3 |
| Policy rejection cannot retrieve or transmit the API key | **Holds by ordering + wire evidence** | `ChatViewModel.kt:1332-1360`: the key read is after `openWorkspace` and after the rejection `return`; rejection tests assert no `/config/provider-key` |
| Transport failure during the apply is fail-closed | **Holds** | Scenario 4: `IOException` → `PermissionPolicyException`, no init/key, id flushed |
| Cancellation cannot leave an unconfirmed attachment | **Was broken; corrected** | Scenario 5/5b: a cancelled re-open left the old fresh lease and could skip the policy; fixed in `WorkspaceManager.open()` |
| A stale workspace id is not reused across engine generations | **Holds** | Scenario 8/9; `liveIdOrNull()` flushes on generation mismatch; `CrushSession.ensureAgentReady` returns “no workspace” before using the key |
| Failed reattachment is not reported as success | **Holds** | Scenario 6: `reattachIfStale` returns false, id flushed; a later retry can recover |
| Effective permission mode can be read back | **No trustworthy read-back exists** | §5; a 2xx apply remains the only proof |
| Concurrent opens/reattaches are serialized | **Not serialized (documented limit)** | Scenario 10: same-mode overlapping opens converge; different-mode races are outside the single-bootstrap path and remain last-writer at the engine |

## 2. Implementation trace

`WorkspaceManager.open()` (`WorkspaceManager.kt:193`) is the only path that
creates/reattaches and applies the policy:

1. `POST /v1/workspaces` (`createWorkspace`). Nothing is committed until it
   returns.
2. Commit `yoloMode`, `permissionModeWire`, `workspacePath`, `workspaceDataDir`,
   then the id and the engine generation that minted it.
3. **Clear `lastAttachedAtMs`** (correction) so the id is not treated as
   attached while the policy is still unconfirmed.
4. Apply the policy:
   - v0.99.0 family: `POST …/permissions/skip {skip:Boolean}`
   - v0.100.0+ family: `POST …/permissions/mode {mode:"normal"|"yolo"}`
5. On rejection/transport failure: flush id + generation, throw
   `PermissionPolicyException`; `agent/init` is skipped.
6. On cancellation: flush id + generation + lease, rethrow
   `CancellationException`.
7. On success: set the lease, then call `agent/init` (the existing redundant
   pre-key init) and return.

`ChatViewModel.doBootstrap` (`ChatViewModel.kt:1332-1360`) catches
`PermissionPolicyException`, publishes
`engine rejected the permission policy (…). Nothing was sent; check the engine
version and try again.`, and returns **before** `keySession.require(...)`.
`CrushSession.ensureAgentReady` (`CrushSession.kt:114-115`) also returns
`Ready("", …, false, ["no workspace"])` before `setProviderKey` when the
manager has no live id.

`reattachIfStale()` (`WorkspaceManager.kt:311`) short-circuits only when the
lease is fresh **and** `liveIdOrNull()` is non-null. `liveIdOrNull()` flushes an
id whose minting generation differs from the current engine generation.

## 3. Scenario matrix

All scenarios are JVM/MockWebServer tests; no device run is claimed. The fake is
path-keyed and records method/path/body in order. `agent/init` means
`/workspaces/{id}/agent/init`; “credentials” means `/config/provider-key`
(the only path that transmits the key).

| # | Scenario | Operation order | ID / generation | `agent/init` | Credentials | Final lease / attachment | User-visible result |
|---|---|---|---|---|---|---|---|
| 1 | Successful mode apply | create → mode → init | id live, current generation | yes | read later, after success; never before mode | lease set | ready |
| 2 | Rejected `permissions/mode` | create → mode 500 → throw | flushed (null/null) | **no** | no `/config/provider-key` request; bootstrap returns before key read (source) | no lease | named policy-rejection phase |
| 3 | Rejected `permissions/skip` | create → skip 500 → throw | flushed | **no** | no `/config/provider-key` request; bootstrap returns before key read (source) | no lease | named policy-rejection phase |
| 4 | Transport failure on apply | create → mode disconnect → throw | flushed | **no** | not sent | no lease | policy-rejection phase (cause preserved) |
| 5 | Cancellation during initial open | create → mode in flight → cancel | flushed (correction) | **no** | not sent | no lease | caller cancelled; next bootstrap recovers |
| 5b | Cancellation during re-open with a fresh old lease | create → mode in flight → cancel → non-forced reattach | old lease cleared; reattach reopens | no on cancel; yes on recovery | not sent | recovered lease only after re-apply | next operation recovers, never skips policy |
| 6 | Reattach after policy failure, then retry | create → mode 500 → false; create → mode → init → true | flushed on failure; live after retry | no on failure; yes on recovery | not sent | no lease on failure; lease after recovery | send/new-thread bootstraps again; retry can succeed |
| 7 | Repeated open after rejection | create → mode 500; create → mode → init | flushed, then live | only on the successful open | not sent | lease only on success | second attempt succeeds |
| 8 | Engine restart between policy and init | create → mode → generation bump → init (old id) | id flushed on next read | attempted pre-key on the old id | **not sent**; `ensureAgentReady` sees “no workspace” | no usable lease; reattach recovers | not ready; next operation recovers |
| 9 | Generation change during reattach | create → mode → init on new generation; then bump + reject | live after success; flushed after rejection | yes on success; no on rejection | not sent | lease after success; none after rejection | recovery or named failure |
| 10 | Concurrent opens (same mode) | two overlapping create → mode → init chains | final id live, current generation | twice | not sent | final lease set | both calls return the same workspace |

Credential **read** is source-inspected in `ChatViewModel`; the tests assert the
wire requests (no `/config/provider-key` on any fail-closed path). The
successful case asserts the first provider-key request comes after the policy
request.

Scenario 5b pins the security consequence of the defect: without the correction,
the cancelled re-open left the old fresh `lastAttachedAtMs` while the new id was
already claimed, so a subsequent non-forced `reattachIfStale()` returned `true`
without re-applying the new mode. The pre-fix run (`v2-26-phaseD-prefix.log`)
already failed the initial cancellation assertion
(`expected null, but was:<ws-scenario>`).

## 4. Narrow correction

`WorkspaceManager.open()` now:

```kotlin
workspaceId = id
workspaceGeneration = WorkspaceManager.currentEngineGeneration()
lastAttachedAtMs = null          // id is not attached until the policy succeeds

try {
    applyPermissionPolicy(id, yolo, permissionModeWire)
} catch (c: CancellationException) {
    workspaceId = null
    workspaceGeneration = null
    lastAttachedAtMs = null
    throw c
}
```

Why this is the right scope:

- It does not change the request bodies, the mode mapping, `applyPermissionPolicy`
  rejection handling, `agent/init`, the UI message, or the v0.99/v0.100 family
  selection.
- It makes the lease mean “policy confirmed for this id” again. A cancelled
  operation is treated like a failed one: no claim survives.
- Normal success still sets the lease after the 2xx apply, so bounded retry and
  reattach semantics are preserved.
- No L1/L2/L3 file is touched.

## 5. Effective-mode read-back

**No trustworthy read-back exists in the bindable protocol.**

Evidence from `spec/crush-openapi.json`:

- `GET /workspaces/{id}` returns `yolo` (boolean), not `permission_mode`.
- `GET /workspaces/{id}/permissions/skip` returns `skip` (boolean) — the v0.99
  family.
- There is no `GET /workspaces/{id}/permissions/mode`; `POST …/permissions/mode`
  is the v0.100.0 route and is not even in the 68-endpoint snapshot.
- `GET /workspaces/{id}/config` returns an untyped `config.Config`; the app has
  no DTO field or code path that treats any part of it as the effective mode.

A successful `POST /permissions/mode` is therefore the **only** proof the app
has that the engine applied the requested enum. This is retained as a
limitation, not “fixed” by inferring from `yolo` or from a 2xx. If a future
engine adds a mode read-back, it must be verified against a live engine before
the app relies on it.

## 6. Residual risks

- **Accept-then-ignore:** a 2xx on the policy POST does not prove the engine
  applied it; there is no read-back (§5).
- **Pre-key init window:** `open()` still sends the redundant `agent/init`
  before the key/model are set (existing V2-13/V2-12 behaviour). On an engine
  restart between policy and init, that call targets the old id; the generation
  flush prevents any subsequent key use or turn, but the redundant call remains.
- **Concurrent different-mode opens:** `WorkspaceManager` has no mutex. Two
  overlapping same-mode opens converge (scenario 10), but a concurrent
  ASK/AUTO pair would be last-writer at the engine and at the manager. The
  production path uses one bootstrap/manager per ViewModel; this is documented
  as a limit, not treated as a policy bypass (a rejected open still flushes and
  cannot leave a confirmed claim).
- **No device verification:** all scenario evidence is JVM/MockWebServer. The
  v0.100.0 `/permissions/mode` route and a real engine restart were not
  re-exercised on `bbf8ca69` in this phase.
- **`ChatViewModel` key-read ordering** is source-inspected; there is no JVM
  `ChatViewModel` harness. The wire tests prove no provider-key request on the
  fail-closed paths.

## 7. Test evidence

| Run | Result |
|---|---|
| Focused A7/A7b (`v2-29-phaseD-focused3.log`) | **BUILD SUCCESSFUL in 44s**; **27 tests / 3 classes / 0 failures / 0 errors / 0 skipped**: `WorkspacePermissionPolicyScenariosTest` 9, `WorkspacePermissionPolicyTest` 4, `WorkspaceLeaseTest` 14. |
| Pre-fix reproduction (`v2-26-phaseD-prefix.log`) | The cancellation test failed exactly as expected: `a cancelled open must not leave a usable workspace claim expected null, but was:<ws-scenario>`. The first transport fake used `DISCONNECT_AT_START` and did not surface an I/O failure, so that test was corrected to `DISCONNECT_AFTER_REQUEST`; that was a harness defect, not a product defect. |
| Full JVM + compile + lint gate (`v2-30-phaseD-gate.log`) | **BUILD SUCCESSFUL in 18m12s**; **1059 tests / 115 suites / 0 failures / 0 errors / 0 skipped**; lint **0 errors / 1 pre-existing warning**; `assembleDebug` and `assembleDebugAndroidTest` completed. |

The successful-policy case also pins ordering: `permissions/mode` is on the wire
before the first `/config/provider-key` request; the rejection/transport/
cancellation cases pin that no provider-key request occurs at all.

## 8. Restrictions and state

- No feature work, permission redesign, Go engine/dependency change, or L1/L2/L3
  change. The only production change is the `WorkspaceManager.open()`
  lease/cancellation correction above.
- No commit, push, PR or issue.
- Branch `v2-15-lifecycle-hardening` stands at `a1be361`; the Phase D changes
  are committed on the same branch as a follow-up. Historical evidence is
  untouched.
- No device claim is made from JVM tests.
