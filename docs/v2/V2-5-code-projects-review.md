# V2-5 - Code / Projects / Review redesign (in progress, started 2026-11-20)

Status: **IN PROGRESS.** This record is written before the code change. Code
will land in batches on the existing host/content seams; unfinished rows stay
NOT PERFORMED until a build log, CI run or device capture exists.

Authority:

- Revision 4.0 (2026-11-19) V2-5 goal, tasks and exit criteria.
- Rev 3.1 brief §4.7.2 (Projects), §4.7.3 (Review), §4.8 (accessibility),
  §4.9 (performance), §8.4.1 (project opening/back-stack) and §8.4.2
  (Review lifecycle).
- ADR-0001 identity/run-freeze invariants.
- V2-A2 (three primary destinations; Review is pushed), V2-A3 (Code is
  project-bound), V2-A4 (no reopened deferrals), and the owner trim "Code is
  project-bound; phone-first; tablet panes only after phone acceptance".
- **V2-A9 (owner, 2026-11-20):** V2-5 ships **Build + Review**; Explore is
  deferred until a real read-only file browser exists. No fake tab, no fourth
  primary destination.

## 1. Scope

| # | Task | What "done" means here |
|---|---|---|
| V2-5.1 | Projects redesign and identity | Current marker bound to `activeBinding` (F-A), trust state, imported-vs-app-managed identity, distinct empty/loading/error; import-only and named Remove preserved |
| V2-5.2 | Code chat with persistent project identity | The bound project is visible in the top bar on populated chats, not only in the sessions sheet/empty state |
| V2-5.3 | Build/Review workflow | Project chat is Build; Review is the pushed trusted surface. Explore deferred (V2-A9); navigation never grants permission |
| V2-5.4 | Changed-files rail and Review cards | Newest-first cards, header counts, per-file diff/revert/export, project provenance, designed partial states |
| V2-5.5 | Provenance, partial states, revert, test status | Project label, turn/capture time, unreadable/binary/oversize/partial/not-measured states, confirmed and named revert; no invented test results |
| V2-5.6 | Byte-exact export | The existing SAF byte copy is preserved; copy states that it exports the file as it is on disk now |
| V2-5.7 | Tablet panes | **NOT PERFORMED**; O3 stays parked until phone acceptance (V2-A4/trim) |
| V2-5.8 | Import-only removal | Removing a project reference never deletes the user's imported folder; internal app workspaces keep their existing destructive copy |
| V2-5.9 | Trust-before-bind | Every open/restore/handoff path keeps gate → consent → bind; no new bypass |
| Carry-in | Review project scoping | Review history is currently process-global; a turn in project B supersedes project A's cards. V2-5 scopes it by engine instance so the Review surface cannot present another project's cards |
| Carry-in | F-A Current marker | The V2-0 ledger assigned this to V2-4; V2-4 shipped the session-sheet marker only. Projects still has none, so it lands here |

Out of scope:

- A file explorer / Explore mode (V2-A9).
- Tablet/wide pane integration (O3).
- Review history persistence across process death (pre-existing P4 item).
- A test runner. The app does not execute tests; Review says so rather than
  turning model claims into test results.
- Revert-all, branching, per-file staging, project rename/archive/sort.
- Chat deny-all, composer gates, provider policy, lock policy, trust record
  semantics.
- Any silent deletion of imported folders.

## 2. Decisions

### V2-5-D1 - Review is scoped by engine instance

`ReviewHistory` currently holds one process-global list. `beginTurn()` marks
**all** cards superseded, a run in any project disables revert everywhere, and
the 8-card cap is shared. That contradicts V2-A3. V2-5 adds a `scopeKey`
(the frozen instance key: `""` legacy, `"chat"` pool, `"p<uuid8>"` project):

- `beginTurn(scope)` supersedes only that scope's cards and raises that
  scope's run flag.
- `runSettled(scope)` clears it under `NonCancellable`.
- `recordTurn(scope, ...)` inserts into that scope, cap 8 per scope.
- `revert(scope, cardId, path)` verifies the card belongs to the scope.
- The existing `opGate` stays global, so writes still serialize.

`ReviewScreen` filters by the active instance key and shows only that scope.
The project label comes from the frozen turn metadata, not from whatever
binding happens to be active when the screen recomposes.

### V2-5-D2 - "Not measured" is a card state, not no card

A turn whose snapshot was unavailable currently records nothing
(`FileChangeSet.isEmpty` is true), so Review cannot distinguish "no changes"
from "not measured". `recordTurn` keeps a card when
`snapshotUnavailable` is true, and the card says "Changes were not measured
for this turn" with the path/unreadable detail available. Ordinary empty
turns are still dropped.

### V2-5-D3 - Provenance is host-collected and labelled

Each card shows: project label (or "Legacy workspace"), folder tail, turn
start time, snapshot capture time, session id first 8, file/line counts.
Model/provider attribution is **not** invented; it is added only if it can be
frozen at turn start without a second source. Test results are not shown as
verified because the app does not run tests.

### V2-5-D4 - Projects selection is never colour-alone

The current project row (and the legacy row when `activeBinding` is Legacy)
carries a filled check, the word `Current`, and
`stateDescription = "Current project"`. The marker reads
`container.activeBinding`; it is not a second stored choice.

### V2-5-D5 - Trust is visible, not implied

Project rows distinguish "Configuration approved" (a consent record exists)
from "No approved configuration". This is a status fact, not a claim that a
`crushrc` is absent. V2-5 adds no revoke control; removing the project remains
the only way to drop its consent.

### V2-5-D6 - Project identity persists in Code chat

For a Project binding, the top-bar sub-line becomes
`<project label> · <status>`; ChatPool keeps `Chat - no tools · <status>`;
Legacy stays `<status>`. This uses the same readiness snapshot as V2-4 F-E and
does not add a chip that could be mistaken for an action.

### V2-5-D7 - Build/Review, no Explore

Project chat is Build. Review stays the pushed, app-owned surface. A
Build/Review control may navigate to Review but must not change the run,
provider, project, engine, session or permission policy. Explore is deferred
by V2-A9; no tab is rendered.

### V2-5-D8 - Export honesty

The export path remains the existing byte-for-byte SAF copy. Its label and
helper copy say it exports the file **as it is on disk now**, not the
before-image in the card. This is the truthful description of the shipped
behaviour.

### V2-5-D9 - Phone-first

No wide/tablet claim in this package. The 600/840dp shell rules stay as
shipped; Review and Projects are phone-first single surfaces. Tablets remain
O3/parked.

## 3. Code map

| Area | File | Planned change |
|---|---|---|
| Projects state | `projects/ProjectsViewState.kt` | active project id / legacy-active fact; loading on refresh |
| Projects host | `projects/ProjectsScreen.kt` | collect `activeBinding`; pass active id + trusted ids; keep SAF/AFA/trust wiring |
| Projects content | `projects/ProjectsContent.kt` | CrushCard rows, Current marker + semantics, trust/identity labels, distinct states, humanised environment copy |
| Project identity | `project/ProjectRef.kt`, `data/project/RoomProjectStore.kt` | carry `trusted` from the Room trust columns (defaulted for source compatibility) |
| Chat identity | `chat/ChatViewState.kt`, `chat/ChatContent.kt` | project label in the header sub-line; tests for Project/ChatPool/Legacy |
| Review store | `domain/file/ReviewHistory.kt`, `TurnChanges.kt` | scope key, per-scope supersede/run/cap/revert, not-measured card |
| Turn call sites | `chat/ChatViewModel.kt` | freeze the instance key at turn start; pass scope/project label; make `runSettled` run even if recording fails |
| Review host | `review/ReviewScreen.kt` | active scope + project label; keep SAF export; saveable export target |
| Review content | `review/ReviewContent.kt`, `ReviewViewState.kt` | Scaffold/top bar, project provenance, not-measured/partial copy, confirmed revert, export-as-on-disk copy |
| Tests | `ProjectsContentTest`, `ReviewContentTest`, `ReviewHistoryTest`, `ChatViewStateTest`, `ChatContentGateTest`/dispatch ledger if actions change | new pins below |

Unchanged on purpose: engine binding/ADR-0001 invariants, trust service
semantics, import-only removal semantics, Chat deny-all, composer gates,
provider policy, shell destinations.

## 4. Test plan

| Pin | File |
|---|---|
| Current project marker: active project row has check + `Current` + `Current project` semantics; legacy row when Legacy; others none | `ProjectsContentTest` |
| Trust label: approved vs no approved configuration; imported vs app-managed identity; no raw `L0_EMBEDDED` copy | `ProjectsContentTest` |
| Projects states: loading, empty, error; busy disables import/remove; legacy has no Remove; remove names target | `ProjectsContentTest` |
| Chat header: Project shows `<label> · <status>`; ChatPool keeps `Chat - no tools`; Legacy stays status-only | `ChatViewStateTest` + `ChatContentTranscriptTest` |
| Review isolation: cards from scope A do not appear in B; `beginTurn(A)` does not supersede B; cap 8 per scope; run flag per scope | `ReviewHistoryTest` |
| Not-measured turn is retained and rendered as not measured; ordinary empty turn is dropped | `ReviewHistoryTest` + `ReviewContentTest` |
| Provenance: project label, folder tail, turn/capture time, session short id | `ReviewContentTest` |
| Revert: confirmed/named; disabled for running/superseded/reverted; concurrent reverts still serialize | `ReviewContentTest` + `ReviewHistoryTest` |
| Export: byte-exact path preserved and labelled as-on-disk | `ReviewContentTest` (host copy unchanged) |
| Trust before bind: existing `ProjectTrustServiceTest` + restore rows stay green | unchanged |

## 5. Evidence

| Claim | Evidence | Status |
|---|---|---|
| Four-task gate | `v2-5-gate-1120-1.log`: `BUILD SUCCESSFUL in 20m 45s` | PASS (attempt 1) |
| Unit-test count / failures | 910 tests, 95 suites, 0 failures, 0 errors, 0 skipped | PASS |
| Lint | `lint-results-release.txt`: 0 errors, 1 `NotShrinkingResources` warning (pre-existing) | PASS |
| CI | GitHub Actions run on the pushed commit | NOT PERFORMED |
| Projects current marker / trust / states | device capture set | NOT PERFORMED |
| Project identity in Code chat | device capture | NOT PERFORMED |
| Populated Review card + provenance/partial | device capture (needs a real file-changing turn) | NOT PERFORMED |
| Revert confirmed and serialized | device + existing JVM tests | NOT PERFORMED on device |
| Export byte-exact | device export + sha256 comparison | NOT PERFORMED in this pass |
| Two-project isolation / switching | device + instrumentation where runnable | NOT PERFORMED |
| Trust re-gating on changed `crushrc` | existing tests + device | NOT PERFORMED |

Device environment when the pass runs: Redmi Note 7 Pro `bbf8ca69`, API 29,
393dp, app `com.crush.mobile`. Captures will go in
`docs/parity/2026-11-20-v2-5-code/`.

## 6. Honest limits

- API 29 cannot prove API 30+ AFA/rationale behaviour.
- Tablet/wide panes are out of scope (O3); no wide claim.
- Review history is process-memory only; the card cap is 8 per scope. This is
  pre-existing and not changed by V2-5.
- The app runs no tests, so Review cannot show generated test results; it will
  say so.
- Export is the file as it is on disk now, not the card's after-image; the copy
  says so.
- No screenshot harness; device captures are manual. 2.0x font scale will be
  checked on the affected surfaces if the device pass allows.
- The extended-rail and wide layouts remain unobservable on this phone.
