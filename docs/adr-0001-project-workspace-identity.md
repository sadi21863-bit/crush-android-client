# ADR-0001 — Project / workspace / session / run identity

**Status: PROPOSED (not accepted)**
**Date: 2026-11-02**
**Author: Crush Mobile roadmap (P2/P3)**
**Source: [`crush-plan-2026-11-02.md`](crush-plan-2026-11-02.md) §5 (canonical identity
and persistence), §22 (minimum engineering contracts), §23 (first implementation
sprint, hard stop). Grounded in the current tree as of 2026-11-02; every code
citation below was read against that tree.**

> **Non-claiming rule.** This is a decision record for a design, not a report of
> working features. Nothing below asserts that the engine already hosts multiple
> concurrent workspaces with isolation — that is exactly what the two integration
> proofs in §8 must establish before anything is built on this model. Until they
> pass, the accurate capability statement is the existing gaps row in
> [`capabilities.md`](capabilities.md): *"Multi-project / workspaces — one
> app-private workspace, path-derived id."*

## 1. Context — the seams as they exist today

The engine embedding is proven and should not be rebuilt: `crush server` as a
child process (`CrushEngine.start()`), bound to `-H unix://<filesDir>/crush.sock`
([`EngineSockets.kt:47`](../app/src/main/java/com/crush/mobile/engine/EngineSockets.kt),
socket path from `EngineSockets.socketPath(Context)`, never a TCP port), with
engine state dirs wired by `EnginePaths` [EnginePaths.kt:38–44]:
`filesDir/workspace` (cwd), `filesDir/crush/state` (the `-D` data dir — engine
session/message rows, per the spawn at [`CrushEngine.kt:219–224`]), `crush/cfg`,
`crush/data` (`crush.json` — provider config, governed by the single-writer
contract W1–W4 in
[`key-path-single-writer-design.md`](key-path-single-writer-design.md)), `crush/cache`,
`crush/skills`, `crush/tmp`. The 2026-11-02 egress fix added the local CONNECT
bridge env (`HTTPS_PROXY` → loopback, `NO_PROXY` for loopback) so the engine
**never resolves DNS itself** — see
[`engine-egress-dns-2026-11-02.md`](engine-egress-dns-2026-11-02.md).

But the identity model in front of the engine is a single implicit world:

- **One workspace per engine instance.** `doBootstrap()` calls
  `session.openWorkspace("$filesDir/workspace", "$filesDir/crush/state",
  permissionMode, providerId)` at
  [ChatViewModel.kt:715](../app/src/main/java/com/crush/mobile/ui/screens/chat/ChatViewModel.kt)
  — the path argument is a constant, not a choice. `CrushSession.openWorkspace`
  ([CrushSession.kt:58](../app/src/main/java/com/crush/mobile/data/crush/CrushSession.kt))
  is the attach seam; `WorkspaceManager.open(path, dataDir, yolo)`
  ([WorkspaceManager.kt:169](../app/src/main/java/com/crush/mobile/data/crush/WorkspaceManager.kt))
  creates the workspace and `reattachIfStale()` (:238) resurrects it.
- **Sessions are engine-managed rows.** They persist engine-side independently
  of the workspace handle (`capabilities.md` engine facts: "Session lifetime —
  persistent engine state"; "Workspace identity — keyed by resolved cwd; the id
  is an attachment handle that changes on re-attach") inside the `-D` data dir
  (`files/crush/state`). The app never reads that store directly; it goes through
  the socket API (`crushApi(port)`), the same rule `deleteSession` learned the
  hard way ("sessions are persistent engine state" — a timeout does not prove a
  delete).
- **ChatViewModel holds all identity transiently.** `wsId: String?` and
  `sessionId: String?` [ChatViewModel.kt:355–356] are in-memory only; a process
  death forgets them. The turn turnstile is `activeRun: RunHandle?` (:353,
  audit P1-05) and `RunHandle(turnId)` freezes `port`/`ws` at preflight
  ([RunHandle.kt:33](../app/src/main/java/com/crush/mobile/ui/screens/chat/RunHandle.kt))
  — immutable for one turn, but not durable across restarts and not project-
  qualified. `pendingProviderId` (:390, P1-07) defers provider switches to a run
  exit; `sessionViewEpoch` (:373, added 2026-11-02) guards the view layer against
  stale-switch writes (see §8).
- **"Project" does not exist.** There is no app-side row, table, directory, or UI
  concept for a project. The provider key, permission mode, and model slots are
  global; the cwd is global; the Chat screen is the only surface.

## 2. Problem

Roadmap P2–P6 all consume a durable project identity that is not there yet:

- **P2** needs `Project`/`WorkspaceBinding` rows and one canonical app-private
  project root per project, **without losing existing conversations or provider
  keys**.
- **P3** needs a project→cwd map whose workspace attachment is *verified to
  actually bind that path*, and run identity qualified by project, session, and
  workspace generation.
- **P4/P5** need a Projects screen and Chat/Code mode separation bound to a real
  project (or, for Chat, no project at all).
- **P6** needs `targetProjectId` in the handoff package — a name hangs on
  nothing today.

And the roadmap's **hard stop** (§23) makes the sequencing explicit: *if the
embedded engine cannot consistently attach workspaces to two distinct project
directories without session bleed, do not build Chat/Code navigation, Termux
levels, or local inference on top — resolve identity and lifetime first.*

So the question this ADR answers is not "code the Projects screen" (that is P4)
but "what are Project, WorkspaceBinding, Session, Run, and EnvironmentProfile —
and how do they exist in *this* codebase, additively, before any of it ships."

## 3. Decision

Adopt the identity chain **Project → WorkspaceBinding → Session → Run** with
**EnvironmentProfile and FileSnapshot as orthogonal axes**, adapted to today's
seams (this adaptation, not the plan's verbatim tables, is the decision):

1. A **Project** is the durable app-owned identity: stable UUID, label,
   environment level, canonical POSIX directory under app-private storage, VCS
   metadata, createdAt. It survives engine restart, process death, and app
   upgrade. **No ID is ever derived from a transient workspace id** (which
   changes on every re-attach).
2. A **WorkspaceBinding** is the runtime attach of a project to the engine: it
   records which engine instance, which crush workspace id, which cwd, and which
   **generation**. It is the checked, stored version of what `openWorkspace`
   already returns (`CrushSession.Ready(workspaceId, …)`). Stale bindings are
   **discarded, never merged** — the `sessionViewEpoch` discipline generalized
   from view staleness to binding staleness.
3. A **Session** is one continuous conversation, optionally bound to a project.
   Chat sessions may have `projectId = null`; Code sessions always carry one and
   additionally a mode (`Chat`/`Code`). Sessions remain engine-managed state;
   the app stores the *binding*, not the transcript.
4. A **Run** is one turn, with immutable identity from send to terminal state —
   what `RunHandle` already does, extended with session and workspace
   generation, so identity is qualified end to end.
5. An **EnvironmentProfile** describes where execution happens (L0 today), as a
   set of **capability flags probed at runtime — never privileges inferred from
   the level number**.
6. A **FileSnapshot** pins provenance for any file an agent touched, so no diff
   is ever rendered against the wrong revision.

The four-event identity chain in one line:

```
projectId ──binding──▶ cwd (canonical, on disk) ──attach──▶ workspaceId ──(re)creates/opens──▶ sessionId ──turn──▶ runId
```

## 4. Entities, invariants, and what each becomes in code

| Entity | Plan intent, adapted | **Exists today as** | **Becomes** |
|---|---|---|---|
| `Project` | durable identity per repo | nothing | Room row + dir under `filesDir/projects/<uuid>/repo` with a `.crush-mobile/` manifest (`project.json`, snapshots, sync-journal); created via new ProjectStore `create/list/rename/archive/delete` |
| `WorkspaceBinding` | attach record, discard-when-stale | `wsId` [ChatViewModel.kt:355] + `existingSessionWorkspace` + `reattachIfStale()` result, plus `WorkspaceLease` | Room row `(projectId, engineInstanceId, workspaceId, cwd, generation, attachedAt)`; attach seam stays `CrushSession.openWorkspace(canonicalRepoPath, dataDir, permissionMode, providerId)` — only the `path` argument changes from the constant `"$filesDir/workspace"` to the project's repo dir |
| `Session` | conversation row, project optional | `sessionId` transient; engine rows in the `-D` data dir (`files/crush/state`); view state `ChatUiState.activeSessionId` | Room `SessionBinding(sessionId, projectId?, mode, providerId, modelId, createdAt)`; creation/open seams unchanged (`openSession(sessionId)` [ChatViewModel.kt:1371], `newThread()` [:1467], the send-path session creation) |
| `Run` | one identity per turn | `RunHandle(turnId)` frozen at preflight [RunHandle.kt:33], `activeRun` [:353] | identity extended to `(turnId, sessionId, workspaceGeneration, capability grant)`; journaled per §18 of the plan; Stop contract (P1-05) unchanged — identity **not reusable until server cancel is confirmed** (P3 proof) |
| `EnvironmentProfile` | where execution happens | the L0 spawn contract, implicit: `EnginePaths.env()` (CRUSH_* vars, HOME, TMPDIR, egress-proxy vars, `CRUSH_SERVER_IDLE_TIMEOUT`) + UDS bind + key/permission-mode config | Room row per backend; L0 today; L1–L3 via a later `TermuxConnector.probe()`-shaped seam; **capability flags, never privileges inferred from the level** |
| `FileSnapshot` | revision-pinned provenance | `workspaceSnapshot: WorkspaceSnapshotter.Snapshot` [ChatViewModel.kt:≈414] + `FileChangeCollector`/diff drawer (snapshot → tracked files → diff, JVM-tested; zero files measured on a real turn — the capabilities gap) | per-revision manifest under `projects/<uuid>/.crush-mobile/snapshots/`; provenance recorded before diff display |

**Direct code consequences, named:**

- `CrushSession.openWorkspace(...)` keeps its exact signature; the *caller* passes
  `project.repoPath` instead of the `filesDir/workspace` constant. The `-D` data
  dir argument stays `EnginePaths.dataDir`; the `-H unix://` socket stays
  `EngineSockets.socketPath`. One engine process, multiple attached workspaces —
  **if** the proofs establish the engine supports them (§8; if not, fallback in §7).
- `workspaceSnapshot` and the drawer diff become per-project; a switch of project
  clears them exactly like `openSession` clears them today [ChatViewModel.kt:≈1419].
- Provider keys remain **per-provider, never per-project**. `ensureAgentReady`
  must run per binding (each workspace's agent init), and the single-writer
  contract W1–W4 in [`key-path-single-writer-design.md`](key-path-single-writer-design.md)
  is untouched by this ADR: the injector still refuses while alive, the injector
  write window stays pre-spawn only, `crush.json` stays engine-owned cras-safe.

**Invariants (I1–I7):**

- **I1 (identity chain).** A run resolves to `(projectId, binding.generation, sessionId)`
  and keeps that identity to terminal state; cancellation both requested *and
  confirmed by the engine* before the identity is reusable.
- **I2 (canonical path is the anchor).** The engine keys workspace identity by
  resolved cwd. Therefore a binding must bottom out in an existing POSIX
  directory; no binding without it. (This is also why the SAF-URI alternative in
  §6(c) fails.)
- **I3 (stale bindings discarded).** Any writer carrying identity it
  snapshotted before a newer switch/attach lands — its effect is dropped. This
  is the `sessionViewEpoch` contract raised from the view layer to the identity
  model. Never re-use a `workspaceId` across a generation change; re-attach is a
  new binding, not a stale one.
- **I4 (one engine, project-scoped sessions).** Sessions are bound to their
  project's workspace generation. Passing a session id against a different
  workspace generation is an error, not a fallback (the same error the
  `existingSessionWorkspace` pairing comment already documents).
- **I5 (engine state stays engine-owned).** No direct reads/writes of
  `files/crush/state` by app code, ever; identity rows mirror only what the
  socket API reports. The engine DB remains the transcript source of truth
  (the `deleteSession` lesson).
- **I6 (no silent overwrite).** Every diff is rendered against a snapshot of the
  revision it was computed from; `FileSnapshot` provenance precedes display.
- **I7 (keys never enter project space).** Keystore storage (`secure_api_keys`
  per provider), injector contract, and the plaintext-`crush.json` trade
  (decisions-open items 4/6) are out of scope and unchanged. No key material is
  copied into `filesDir/projects/**` or Room rows.

The plan's `filesDir/` sketch is adapted, not pasted: this ADR **does not**
introduce a `sockets/` indirection (the socket stays `filesDir/crush.sock`) and
does not introduce a separate `conversations/` tree (Chat metadata lives in
Room rows; no separate directory layer until a proven need).

## 5. Migration plan (P2)

Principle: **additive and reversible.** Nothing the engine or the running app
depends on is renamed, moved, or re-keyed.

1. **Engine layout unchanged, byte for byte.** `-H` socket path, `-D` data dir
   (`files/crush/state/crush.db`), `crush/cfg`, `crush/data/crush.json`,
   `crush/cache`, `crush/skills`, `crush/tmp`, and the egress-bridge env all stay
   exactly as `EnginePaths`/`CrushEngine` build them. No rename of `files/crush/*`.
2. **Room schema v1 + migration journal.** New store, versioned from day one:
   `ProjectEntity`, `WorkspaceBindingEntity`, `SessionBindingEntity`,
   `EnvironmentProfileEntity`, plus a journal row recording migration completion.
3. **One generated legacy project.** Upgrade creates `Project(uuid generated,
   label "Chats", environmentLevel L0, canonicalPath = filesDir/workspace as-is)`.
   Because the cwd is the workspace identity's anchor, **keeping that path means
   every existing session id, transcript, and workspace handle keeps resolving**
   — existing chats are claimed by the legacy project without touching engine
   rows. New projects get `filesDir/projects/<uuid>/repo`. Any later relocation
   of the legacy dir is an explicit, separately-tested operation (full re-bind +
   backup + rollback), never part of this migration.
4. **Session backfill, idempotent.** On first launch after upgrade, enumerate the
   legacy workspace's sessions through the socket API and insert SessionBinding
   rows (projectId = legacy project, mode = Chat). If the engine is not up yet at
   migration time, adoption is lazy — first `openSession()` writes the row. The
   journal marker makes re-runs no-ops; a source session is never modified.
5. **Keys untouched.** Keystore ciphertext is keyed by provider id, orthogonal to
   projects; nothing migrates. DataStore settings likewise.
6. **Rollback property:** the pre-upgrade APK reads no Room. Since the engine
   state and `crush.json` are untouched, reinstalling the prior build yields the
   un-projected app, working, with all chats present. This *is* the rollback test:
   seeded old build → upgrade → verify chats/keys/files; downgrade on the same
   seed → verify still functional.
7. **Failure shapes pinned as planned test fixtures** (written in the style of the
   single-writer doc's §5 — named now, claimed only when they run):
   - `LegacyProjectMigrationTest` — seeded old state produces exactly one legacy
     project; re-run is a no-op (journal).
   - `UpgradeKeepsConversationsTest` — chats/playable transcripts and provider
     keys survive install-over.
   - `DowngradeNoRoomTest` — Room-free APK functions against the same engine
     state.
   - `OutOfSpaceMigrationTest` — Room open/write failing must not strand chat:
     the un-projected single-workspace path remains the fallback (P2's out-of-space
     case).
   - `DuplicateUuidRejectedTest` — unique `projectId`; duplicate create errors,
     no partial dir.
8. **Ordering discipline.** The plan's §20 rule stands: no migration of current
   Chat into the new model until the P2/P3 proofs pass; P2 merges as a small
   vertical slice, not a tree-wide PR.

## 6. Alternatives considered

**(a) Keep a single workspace forever.** Rejected. It leaves the capabilities.md
gap row unaddressed by definition, and every P2–P6 deliverable hardens the wrong
thing: handoff would name a target that is "everything on the phone," a Projects
screen would be a list of folders inside one cwd, Multi-file edits offer no
project isolation, and the hard stop cannot even be *attempted*, because two
distinct directories are its subject. It also forfeits the one cheap moment to
add durable identity: today, with one legacy entity to migrate.

**(b) Per-provider workspace directories.** Rejected on axis confusion. The
plan's §4 separation is exact: environment/where-tools-run and provider/
where-inference-runs are orthogonal axes. Provider is the inference axis; the
cwd-to-project is the file axis. Conflating them would (i) multiply workspaces by
provider count per project for zero file-isolation gain, (ii) invalidate session
identity on every provider switch — breaking the already-shipped P1-07 semantics
where a switch must not tear down a running turn (and W3 of the single-writer
design), and (iii) couple key-slot state to directory layout, which is precisely
the coupling the 2026-11-02 "seven call sites passed `opencode-zen`" fix removed
(providers resolved once, from persisted settings; nothing else hardcodes a
provider).

**(c) Engine-external mapping only (an app-side table mapping projectId →
whatever cwd the engine happened to get, no canonical project dirs).** Rejected
because it makes I2 unverifiable: with no canonical directory to compare against,
"must match the intended path; discard stale bindings" has nothing to check, and
stale bindings are exactly the failure this model exists to prevent. It would
also have to accept SAF URIs as "cwds," which Android does not permit — a
document-tree URI is not a POSIX path and never can be the engine's resolve
target (Android rule in the plan §8). The canonical app-managed directory is the
only base that turns "project" back into a verifiable filesystem fact, which the
attach step can confirm.

**Fallback, not alternative:** if the proofs below fail inside one engine process
(upstream cannot host N concurrent attached workspaces), the model survives with
**one engine process per active project** — same rows, same bindings, heavier
lifecycle (spawn/stop per switch, `EngineProxy` bridge per instance, fully in
line with existing `EngineSupervisor` restart semantics). That is the plan's own
"resolve identity and lifetime first" branch — it changes *how many* engines,
never the identity model.

## 7. Consequences

**Gains:**

- Turns the gap row into a proven-or-refuted evidence item *before* UI ships on
  it — the VERIFIED/IMPLEMENTED/EXPERIMENTAL discipline applied to the roadmap
  itself.
- Generalizes the hardest-fought recent fix: the 2026-11-02
  `sessionViewEpoch` work ([`session-transcript-bleed-2026-11-02.md`](session-transcript-bleed-2026-11-02.md),
  pinned by `ChatSessionTranscriptGuardTest`) proved that ungoverned identity
  fields bleed state across boundaries even *within* one workspace — two
  writers, one `messages` list, whichever landed last won, and the other chat's
  transcript appeared under yours. Cross-workspace bleed would compound that
  class of defect with real files and permissions in the blast radius. The
  snapshot-and-drop contract that killed the view bleeds is the same discipline
  I3 demands of bindings.
- Keeps every solved foundation intact (plan §2): UDS transport, SSE
  long-livedness, provider handshakes, approval flow, P1-05 Stop identity,
  P1-07 switch semantics, key-path single-writer contract — all untouched
  consumers of this decision, not casualties of it.
- Process death stops being an identity amnesiac: Room rows let ChatViewModel's
  `wsId`/`sessionId` be reconstructed instead of re-derived from constants.

**Costs and risks, stated honestly:**

- Room schema + migration fixtures + an identity layer the ViewModels must then
  consult — real surface, in exchange for real durability.
- Per-binding agent config: `ensureAgentReady` runs per workspace, and engine
  v0.98.2-class writeMu behavior (one workspace-config Load measured ~5s) means
  N projects may pay N config loads unless the engine amortizes. Measured on
  device before promising otherwise.
- Event routing complexity: today's single long-lived `eventConnection` /
  `eventRouter` pair [ChatViewModel.kt:≈432] becomes per-binding, with
  `reattachIfStale()` semantics per project — additional late-SSE/lost-reader/
  duplicated-callback races (exactly the P3 race list) must be tested, not assumed.
- The largest open unknown is upstream: whether one engine process hosts multiple
  concurrent attached workspaces **with isolation**. That is why the proofs are
  first and the hard stop is absolute — a bleed discovered after Chat/Code
  navigation ships is a recall, not a fix; discovered now, it is a scoped
  constraint (fallback in §6).

## 8. Integration proofs, ordered FIRST (plan §23 hard stop)

Sequence, per §23's own sprint: **(0)** one in-memory `ProjectRef` prototype
pointing at a fresh app-private directory, started on that cwd **without touching
existing Chat**; then the proofs; then — and only then — Room persistence and any
Code-mode UI, each shipping with VERIFIED/IMPLEMENTED/EXPERIMENTAL labels updated
in [`capabilities.md`](capabilities.md).

- **Proof A (the hard-stop probe).** Two projects attach to two distinct cwds,
  each seeded with its own marker file and its own session. Assert zero cross-
  bleed in every measurable channel: files (each cwd sees only its marker),
  events (routing and run ids stay within their own project), permission prompts
  (an ask raised in A can be answered only from A), and session transcripts
  (A's history replay cannot land under B's view). Pass requires engine restart
  and process-kill repetition: two projects independent after both (P2's proof),
  beyond the one-shot demo.
- **Proof B (contamination integration test).** A multi-project harness runs a
  turn that edits two files in Project A, verifies reasonable diffs, and asserts
  Project B is unchanged — files, events, permissions, and diffs alike (P3's
  proof). Stop cancellation confirmed server-side before any identity reuse
  (I1) is covered in the same harness.

If Proof A fails, the hard stop executes exactly as the plan §23 states: no
Chat/Code navigation, no Termux levels, no local inference built on this. The
negative result is recorded against the capabilities table (a *verified* not-
possible is evidence, not an embarrassment), and identity work continues under
the §6 fallback branch. The ADR's Status then advances to REVISITED with the
device traces attached, in the same record-keeping style as the 2026-10-25
pseudo-item this repo has already retired once with real numbers.

## 9. Status and what this ADR does not decide

**PROPOSED** for the P2/P3 slices; not accepted, not implemented. Accepted would
mean: Proof A and Proof B passed on the reference device (arm64, API 29), their
traces/recordings archived next to this file, and the capabilities table updated.

**Deliberately left undecided here:**

- Room schema details beyond the four entities and a journal (column-level
  decisions belong to the P2 implementation PR committing schema v1).
- The Projects screen's shape (P4 belongs to the Code-experience design, not to
  identity).
- Whether the legacy "Chats" project is ever renamed/relocated, and what "delete
  project" does to associated engine sessions (P2 must ship the separate
  remove-from-list vs delete-local-copy distinction the plan §8 already requires).
- Termux L1–L3 specifics (their own gated ADRs, per the plan's phase order —
  none of which build until the proofs in §8 pass).
