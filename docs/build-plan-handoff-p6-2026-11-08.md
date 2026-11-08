# P6 build plan — Chat→Code handoff (2026-11-08)

Status: DOCS ONLY — no code in or behind this file. This is the executor's
brief for the P6 build; it stands strictly on `design-handoff-p5-p6-2026-11-08`
(the accepted-prep design) and on code seams re-derived line-by-line today
against the WORKING TREE sitting on HEAD `b4306d4` (the tree carries four
files of uncommitted in-flight edits — the trust gate→record hardening batch;
their effect on this doc's anchors is recorded in Appendix §12. Every
`file:line` below was read, not inherited). The FIVE owner decisions in §1
gate everything: nothing below the §1 table starts until each is answered.

## 1. THE GATE — the five owner decisions (verbatim, as open questions)

These are §7 of the design handoff, restated word-for-word as the open
questions they are. **Not one build task in this document is started while any
of them is open.** Each decision maps 1:1 onto a batch below (map noted).

1. "Accept the consent-package architecture (§4) vs any lighter 'copy plan
   into composer' shortcut the owner might prefer (lighter = no Room rows,
   weaker traceability row (b) — recommend AGAINST, the plan demands it)."
   — gates Batch A (Room v3 `handoffs`) and Batch G (trace+resume) in their
   whole shape; if the lighter shortcut is chosen, Batches A and G collapse
   and proof row (b) ("handoff remains traceable and resumable after app
   restart") is no longer satisfiable. This doc plans the recommended path.
2. "Composer entry point: a 'Use this plan in Code' text button under
   completed assistant messages (same row as the new Copy/Save actions) —
   approve that placement?" — gates the Batch C entry-chip tasks; the owner's
   answer maps 1:1 to one of two concrete composable slots (§6).
3. "Fingerprint v1 = stat-walk (path+size+mtime, hash) OK?" — gates Batch F.
4. "Is the verify-first first-turn wording binding for the dispatch (my
   recommendation: yes, it makes plan item (3) provable)?" — gates Batch E;
   if binding, the draft template in §8 is what the wording-exactness unit
   test pins, and the template must be reviewed and frozen BEFORE its test
   is written.
5. "Sequencing: P5 acceptance fold + Copy/Save batch FIRST, then P6 build as
   its own gated batch series (consent UX → package model → dispatch) — with
   the cold-boot unlock batch interleaved per the standing deferral list?"
   — gates the literal start order of everything here.

Sequencing note the design adds (design §5): the two device proofs are (a)
fresh-approval drive and (b) restart-resume drive; every batch below names
which proof it feeds.

## 2. Re-verified seam inventory (ground truth this plan builds on)

All read today against HEAD `b4306d4`:

- `CrushApi.listSessions` GET `…/sessions` — `data/crush/CrushApi.kt:336-337`.
- `CrushApi.createSession(workspaceId, CreateSessionRequest)` POST
  `…/sessions` — `CrushApi.kt:339-340`; request shape `CreateSessionRequest`
  (id, parentSessionId, title) `data/crush/CrushDtos.kt:67-71`; response
  `Session` (id, title, messageCount, updatedAt, …) `CrushDtos.kt:73-84`.
- `CrushApi.messages(workspaceId, sessionId)` GET `…/sessions/{sid}/messages`
  — `CrushApi.kt:342-343`; the message shape is `StreamMessage` whose computed
  `.text` concatenates non-hidden text parts (`data/crush/CrushEvent.kt:66-77`)
  and whose failure case ("finish reason error ⇒ NO text"; callers must check
  `isFinished` too) is `CrushEvent.kt:83-89`. Existing history-replay call
  site: `ui/screens/chat/ChatViewModel.kt:1752`
  (`runCatching { crushApi(p).messages(ws, sessionId) }`).
- `WorkspaceManager.newSession(title: String): Session` —
  `data/crush/WorkspaceManager.kt:272-280` (builds the id, requires a LIVE
  workspace id through `liveIdOrNull()`, surfaces failure instead of
  swallowing — the exact fault the design wants dispatch to inherit).
- Room v2 registry: `@Database(entities = [ProjectEntity], version = 2,
  exportSchema = false)` + `projectDao()` — `data/project/ProjectDatabase.kt:17-19`;
  the additive trust migration `MIGRATION_1_2` (two ALTER TABLEs, nullable
  columns, "NULL means no consent recorded", rewriting rows forbidden) is
  `ProjectDatabase.kt:31-36`; entity row shape ("projects", `id`/`label`/
  `repo_dir`/`environment_level`/`created_at`/`updated_at` +
  `trust_fingerprint`/`consented_at_ms`) — `ProjectEntity.kt:30-42`;
  `toRef()`/`toEntity()` round-trips `ProjectEntity.kt:44-60`; DAO surface
  (upsert / all / byId / byPath / targeted trust UPDATE / deleteById) —
  `ProjectDao.kt:16-42`; single builder with `.addMigrations(MIGRATION_1_2)`
  — `data/project/RoomProjectStore.kt:30-34`; DI construction
  `val projectStore: RoomProjectStore by lazy { RoomProjectStore(appContext) }`
  — `di/AppContainer.kt:83`.
- Engine bindings: `EngineBinding` sealed interface Legacy :248 / ChatPool
  :249 / Project :250 — `di/AppContainer.kt:247-250`; key-for-binding
  :272-275; the one-runtime-per-key, never-evict registry with the
  non-legacy liveness budget gate ("at most two keys with live children —
  chat + one project") — `AppContainer.kt:205-226` (gate comment :212-213,
  gate call site :678); the project-open route the Projects row uses —
  `AppContainer.kt:318-352` region (named callers:
  `ProjectsViewModel.requestOpenProject`/`grantTrustAndOpen`, wiring in
  `ui/screens/projects/ProjectsViewModel.kt:222` invoked from
  `ProjectsScreen.kt:269`), plus `applyBinding` :520+.
- Chat-pool separation: `CHAT_INSTANCE_KEY = "chat"` —
  `engine/EnginePaths.kt:104-114` (const at :114; own state dir, own socket,
  no project cwd); the chat-instance policy gate check inside engine start —
  `engine/CrushEngine.kt:269`.
- Bubble + actions row: `MarkdownText(m.text, …)` renders the bubble body —
  `ui/screens/chat/ChatScreen.kt:557`; the completed-assistant guard
  ("Hidden while streaming … on failed rows it's Retry, not save") and the
  `MessageActionsRow(m.text)` call — `ChatScreen.kt:561-568`; the composable
  itself ("Copy message" / "Save as Markdown" TextButtons under a completed
  assistant bubble) is DEFINED in `ChatScreen`'s sibling
  `ui/screens/chat/ChatExport.kt:175-201` (composable head :184).
- Code-block header row: language label + "Copy" + "Save as file" chips share
  one Row (`MarkdownText.kt:163-192`; label :164-172, Copy button :173-183,
  Save button :184-191; the :157-162 span is the explanatory comment). Block
  content extraction is `codeBlockContent(block)` (`MarkdownText.kt:177,:186`).
- Sheet grammar precedent (the surface a new consent sheet rides):
  `ModalBottomSheet { … }` in the session drawer — `ChatScreen.kt:1094` with
  its New-chat/Settings/Projects row :1101-1124; the Projects-vs-Chat split
  copy lives in the sheet labels
  (`ChatViewModel.kt:424-432`, `"Chats - conversation only"` at :430).
- Session pick-by-id precedent (what "resume" reuses):
  `ChatScreen.kt:1066` (`sessions.firstOrNull { it.id == id }`), session
  sorting `SessionList` (`ChatViewModel.kt:294`, :1731); turn send: private
  `suspend fun streamSend(…` — `ChatViewModel.kt:2257`.
- Trust-gate precedent for per-target consent BEFORE anything attaches:
  `data/project/ProjectTrustService.kt` — `gate(ref)` :59, `recordConsent`
  :102, SHA-256 lowercase-hex of file bytes :109-117 (hex discipline
  promised at :85); the restore path that consumes it in the fail-closed
  order gate → record → THEN bind — `AppContainer.restoreLastBinding`
  :441-509 (launched from `init` :511-517; `applyBinding` only after
  `recordConsent` succeeded, :495).
- Stat precedent the fingerprint util stands beside:
  `domain/file/WorkspaceSnapshotter.kt` — per-file size+mtime reuse guard
  :27-38 (including the documented mtime-resolution false-negative class);
  runtime-review store that clears on process death —
  `domain/file/ReviewHistory.kt:124`.
- Room test precedents: JVM roundtrip with an own .db file ("own file, never
  the production projects.db") —
  `app/src/test/java/com/crush/mobile/data/project/ProjectTrustRoomRoundtripTest.kt:21`;
  instrumented survival proof —
  `app/src/androidTest/java/com/crush/mobile/ProjectStoreRoomPersistenceTest.kt:32+`.
- The plan text that is LAW here (verbatim field list and rules):
  `docs/crush-plan-2026-11-02.md:405-417` — the §9 schema lines :409-411 and
  the four rules :414-417; the §8 gate sentence ("a visible 'Use this plan in
  Code' action creates a proposed context handoff with user confirmation; it
  does not silently grant folder access") — `crush-plan-2026-11-02.md:304`.

No destructive Room fallback exists anywhere in the repo (zero matches for
`fallbackToDestructive`), and `exportSchema` stays off "until a breaking
(non-additive) change makes the export worth its tooling"
(`ProjectDatabase.kt:11-15`) — Batch A inherits both rules.

## 3. Batch A — Room v3 `handoffs` table (persistence FIRST inside consent UX)

Feeds: proof row (b) directly; the DRAFT row is also what the consent sheet
writes the user's approval into. Executed ONLY under decision §7.1 as
recommended (the architecture choice).

**(a1) Entity — NEW `app/src/main/java/com/crush/mobile/data/handoff/HandoffEntity.kt`.**
`@Entity(tableName = "handoffs")`, one row per handoff, carrying the plan §9
schema VERBATIM (field names below carry §9's camelCase via explicit
`@ColumnInfo(name = …)`; note the projects table uses snake_case — the naming
pin is flagged in §13) plus the status column from the build brief:

| Column | §9 field it carries | Type / shape |
|---|---|---|
| `handoffId` | `handoffId` | TEXT NOT NULL, PrimaryKey (UUID string; same discipline as `ProjectEntity.id`) |
| `sourceChatId` | `sourceChatId` | TEXT NOT NULL (the engine chat session id the app knows today) |
| `targetProjectId` | `targetProjectId` | TEXT NOT NULL (a `ProjectEntity.id` UUID string — ADR-0001 chain, never a label) |
| `targetCodeSessionId` | `targetCodeSessionId` | TEXT, NULL until DISPATCHED (NULL-until-stage mirrors `MIGRATION_1_2`'s nullable columns discipline) |
| `summaryMarkdown` | `summaryMarkdown` | TEXT NOT NULL (the composer's text) |
| `userApprovedAt` | `userApprovedAt` | INTEGER, NULL until APPROVED |
| `selectedReferences` | `selectedReferences` | TEXT NOT NULL (JSON-encoded array; empty string when none) |
| `projectTreeFingerprintAtHandoff` | — | TEXT, NULL until dispatch (computed AT dispatch per design §4(5); the verify-first turn re-walks and compares) |
| `expiryPolicy` | `expiryPolicy` | TEXT NOT NULL, reserved and inert (v1 writes `"NONE"`; enforcement is explicitly NOT in v1 per design §6) |
| `sourceProvider` | `sourceProvider` | TEXT NOT NULL (which provider/model served the source chat; its value's exact origin is a §12 fact) |
| `status` | — | TEXT NOT NULL, `CHECK (status IN ('DRAFT','APPROVED','DISPATCHED','ABANDONED'))` |

Status-machine pin: the design §4(6) wrote the same lifecycle as
"DRAFT → APPROVED → DISPATCHED → (DONE/ABANDONED)"; this brief fixes four
values ending at ABANDONED. The third-value wording (DONE vs ABANDONED) must
be pinned in review of this entity before code — do NOT add a fifth column
pending the pin.

**(a2) Migration — the SQL shape in `ProjectDatabase.kt` as `MIGRATION_2_3`.**
Bump `ProjectDatabase` to `version = 3`, add `HandoffEntity::class` to
`@Database(entities = […])` and `abstract fun handoffDao(): HandoffDao`.
Migration SQL shape (hand-written, hand-tested, same discipline as
`MIGRATION_1_2`; additive CREATE TABLE only; no existing row is rewritten or
touched — v2 trust columns survive verbatim):

```sql
CREATE TABLE IF NOT EXISTS handoffs (
  handoffId TEXT NOT NULL PRIMARY KEY,
  sourceChatId TEXT NOT NULL,
  targetProjectId TEXT NOT NULL,
  targetCodeSessionId TEXT,
  summaryMarkdown TEXT NOT NULL,
  userApprovedAt INTEGER,
  selectedReferences TEXT NOT NULL,
  projectTreeFingerprintAtHandoff TEXT,
  expiryPolicy TEXT NOT NULL,
  sourceProvider TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('DRAFT','APPROVED','DISPATCHED','ABANDONED'))
)
```

The SQL text and the entity's reflected shape must agree exactly — with
`exportSchema = false` Room reads this back against the expected table at
open, so a round-trip test that OPENS at v3 through the builder is the proof,
not merely a test of the DAO.

**(a3) DAO — NEW `app/src/main/java/com/crush/mobile/data/handoff/HandoffDao.kt`**,
modeled on `ProjectDao.kt:16-42`: `@Upsert` write for initial drafts and
status-machine updates as TARGETED column UPDATEs (the exact anti-clobber
pattern `ProjectDao.updateTrust` documents at :28-39 — never an upsert for a
state transition, or a racing surface's metadata is silently rewritten), plus
read queries: by id, all rows newest-first, CURRENT-draft lookup, and a
guarded transition query shape (update status WHERE status = expected, so
the DRAFT→APPROVED→DISPATCHED order is enforced by SQL, not by discipline).

**(a4) Binding — `ProjectDatabase.kt` + `RoomProjectStore.kt` + `AppContainer.kt:83`.**
One database file, one builder, one migration chain: `RoomProjectStore`
(:30-34) gains a narrow accessor exposing `handoffDao()` from the SAME
`RoomDatabase` instance it already constructs (no second databaseBuilder for
the same file, no re-registered migration list racing the first), consumed
through `projectStore` by whatever Batch C introduces — the DI seam stays
`AppContainer.kt:83`.

**(a5) Tests (pre-written expectations):**
- JVM `data/handoff` round-trip in the `ProjectTrustRoomRoundtripTest.kt`
  discipline (own .db file, never production `projects.db`): insert a
  DRAFT → targeted-transition to APPROVED and back-read every column byte.
- Status-machine test: an UPDATE from DISPATCHED cannot land while status is
  DRAFT (SQL guard rejects); APPROVED ⇒ `userApprovedAt` non-null by the
  writing code path.
- Open-at-v3 test through `Room.databaseBuilder` with `MIGRATION_1_2` +
  `MIGRATION_2_3` chained (the RoomProjectStore.kt:32 shape) proving a v2
  database upgrades and old project rows read back unchanged.
- Instrumented survival once the surface exists (`ProjectStoreRoomPersistenceTest.kt`
  style) — merged with the proof (b) drive below.

## 4. Batch B — the handoff package model

**(b1) Package type — NEW `app/src/main/java/com/crush/mobile/data/handoff/HandoffPackage.kt`.**
A data class carrying exactly what the consent sheet shows and dispatch
sends: `summaryMarkdown`, `selectedReferences` (code-block snippets the user
explicitly attached), and provenance bits (sourceId/title/time, source
provider) for the preamble assembly. Nothing else: the package carries ONLY
what the composer shows — no automatic full-transcript copy, no
address/key-shaped content (design §4(2); plan :407 "must not copy secrets or
the full transcript automatically").

**(b2) Prefill seam — the EXISTING messages channel, no new API.**
The composer prefills from the last completed assistant text via
`CrushApi.messages` (`CrushApi.kt:342-343`) — the same channel the chat
already replays history through (`ChatViewModel.kt:1752`). Selection rule at
build time: last `StreamMessage` (shape `CrushEvent.kt:66-77`) with role
assistant, `isFinished` true, finish reason NOT error, and non-empty `.text`
(the :83-89 finish case is the exact degradation trigger; a failed last turn
prefills NOTHING). Redaction rule per design §4(2): the prefill is the same
text Copy already hands the user — `MessageActionsRow` copies `m.text` of the
same bubble (`ChatScreen.kt:568`) — so the sheet never shows more than the
user could copy.

**(b3) Degrade-to-empty-editable rule — verbatim from design §4(1).**
If the chat engine is not attached, the composer opens EMPTY and stays fully
editable; the flow never depends on a live engine. Build-time corollary: the
prefill fetch is best-effort (`runCatching` in the `ChatViewModel.kt:1752`
style), a failure yields the empty composer, and the DRAFT row still exists —
the draft is app-side state, not engine state.

**(b4) Tests:** package builder redaction (nothing secret-shaped enters;
nothing not shown in the composer enters) and prefill selection (finished /
error-finish / empty-text cases) — the design §5 unit rows verbatim; template
assembly exactness lives in Batch E.

## 5. (depends on A+B) Consent sheet surface — Batch C

**(c1) Entry chip, candidate 1 — under the completed assistant bubble.**
The design's §7.2 text ("same row as the new Copy/Save actions") maps to a
third `TextButton` inside `MessageActionsRow` (`ChatExport.kt:184-201`,
alongside "Copy message" :189-195 and the save button :197-199), rendered
only where the completed-assistant guard already holds
(`ChatScreen.kt:561-568` — guard comment and call site at :567-568).
Implementation is additive: an optional callback parameter on
`MessageActionsRow(text: String)` (default null keeps the single existing
call site :568 unchanged when disabled), and the call site passes the
callback opening the consent sheet with `m.text` as the summary draft.

**(c2) Entry chip, candidate 2 — under the code-block header row.**
Maps to a third `TextButton` in the code-block header Row
(`MarkdownText.kt:163-192`) after "Save as file" (:184-191). Its tap attaches
`codeBlockContent(block)` (already the exact text Copy/Save extract,
:177/:186) as a `selectedReference` and opens the same consent sheet. This
candidate is the one that directly serves plan §9's "selected snippets"
(`selectedReferences[]`); §7.2 answers WHICH slot becomes v1's entry, and the
answered slot is implemented — the other is left documented here, not built.

**(c3) Sheet — NEW `app/src/main/java/com/crush/mobile/ui/screens/chat/HandoffConsentSheet.kt`.**
`ModalBottomSheet` in the session-drawer grammar (`ChatScreen.kt:1094+
`), containing: the editable summary composer (prefill per b2/b3), the
destination picker (Batch D), and the consent card naming the project and
what will run there — design §4(3) copy: "Code agent with file and shell
tools". Confirm writes `userApprovedAt` + `status = APPROVED` to the row;
Cancel destroys the draft and leaves the project untouched AND unopened
(plan proof row (a); design §5 device drive (a)). The sheet must not promise
a hands-off run — the verify-first turn hits the permission card flow (§11,
risk 2).

## 6. Target picker + no-alias re-approval — the picker half of C

**(d1) Rows — the projects table, already Room-backed.**
`RoomProjectStore.list()` (`RoomProjectStore.kt:43` → `ProjectDao.all()`,
`ProjectDao.kt:19-20`) returns `ProjectRef`s (`id, label, repoDir,
environmentLevel` via `ProjectEntity.toRef`, `ProjectEntity.kt:44-51`). The
picker lists only these rows; there is no other project source, and the row's
`id` (stable UUID, `ProjectEntity.kt:32`) is the ONLY thing written to
`targetProjectId` — never a label or path alias (ADR-0001 chain per design
§2).

**(d2) What selection may touch — nothing, until gated.**
The trust gate is per-project consent, never transferable:
`ProjectTrustService.gate` (:59) then `recordConsent` (:102) run in that
order before any engine attach — the exact fail-closed order
`restoreLastBinding` enforces at `AppContainer.kt:462-508` (gate first,
record BEFORE `applyBinding`, :495). If the target project was never
consented (a folder with no record), selection stops at the F04 prompt path —
the sheet shows "requires consent", reusing the verdict language, and does
not silently approve.

**(d3) No-alias re-approval rule — plan :416 ("If the target project changes,
require new confirmation; no dynamic target alias").**
Wiring: the APPROVED row's `targetProjectId` is immutable; a target change
must be expressed as a NEW draft row (new `handoffId`) whose own fresh
`userApprovedAt` is required before dispatch; the DISPATCHED row keeps
pointing at the project it was approved for. HandoffDao's guarded UPDATE
(a3) makes re-approval same-row impossible; the unit test from design §5
("no-alias re-approval rule") pins it: change target on an APPROVED draft ⇒
old row's approval never transfers, new row requires a fresh Confirm.

## 7. Dispatch — `newSession` + first-turn verify-first (Batch E)

**(e1) Target session — the existing seam, on the target workspace.**
`WorkspaceManager.newSession(title = "Handoff <HHmmss>")`
(`WorkspaceManager.kt:272-280`) in the target project's workspace; the
returned `Session.id` (`CrushDtos.kt:73-84`) is written to
`targetCodeSessionId` and status moves DRAFT→APPROVED→DISPATCHED by the
guarded transitions (a3). Failure surfaces — the :268-270 docstring's rule
(a silent null once masqueraded as "no reply") — dispatch failure leaves
status APPROVED, the row resumable, never a half-claimed DISPATCHED.

**(e2) Engine binding — same route the Projects row uses.**
`EngineBinding.Project` (`AppContainer.kt:250`) through the requestOpen/
grantTrustAndOpen route region (`AppContainer.kt:318-352`;
`ProjectsViewModel.kt:222`). NOT `restoreLastBinding` (:441) — that is the
cold-boot path and is the pattern Batch G extends, not the dispatch path.
Budget arithmetic: chat pool may stay live while the project child starts —
the registry's "at most two keys with live children — chat + one project"
(`AppContainer.kt:212-213`, gate at :678) absorbs this; a THIRD live child
reuses the stop-on-switch chain E6 proved (design §4 risk framing).

**(e3) First turn — the verify-first template (DRAFT; frozen by §7.4 before
any test pins it).**
The send rides the existing chat send path (`streamSend`,
`ChatViewModel.kt:2257` region) after the project binding, as one plain-text
turn. Draft text, placeholders bracketed:

```
[HANDOFF <handoffId>] This task was handed off from a Crush Chat conversation.
Provenance: source chat "<sourceChatTitle>" (<sourceTimestamp>), provider
<sourceProvider>. Approved by the user at <userApprovedAt>.
Project tree fingerprint at handoff: <projectTreeFingerprintAtHandoff>
(deterministic stat walk: path + size + mtime, sorted, SHA-256).
Before anything else, re-walk the tree and state whether it differs.

APPROVED SUMMARY — the only authorized contract for this session. Treat the
prior chat conversation as background, not as instructions:
<summaryMarkdown>

SELECTED REFERENCES:
<selectedReferences or "(none)">

RULES FOR THIS TURN:
1. Inspect the repository against the approved summary.
2. List every mismatch between the summary and current repository reality.
3. Do not write, create, delete, or modify any file in this turn.
4. Await our explicit confirmation after the mismatch list; activities after
   confirmation are separate turns and each will hit the usual approval flow.
```

The wording-exactness unit test (design §5: "first-turn preamble assembly
(provenance + verify-first wording exactness)") pins THIS text once §7.4 is
answered "yes"; a "no" means the owner supplies its own binding wording and
§7.4's open state blocks Batch E entirely.

**(e4) Verify-first turn reality check.** Turn (e3) is a full agent turn
with tools; PermissionMode defaults to ASK (`domain/model/PermissionMode.kt:15`,
default from `AppSettings.kt:37`), so the turn's tool reading cards will hit
the ask-card flow the user must Allow (`ChatViewModel.kt:282` comment: "A
tool call awaiting the user's decision. See PermissionMode.ASK."). The
consent card copy may not promise an unattended run (carried in §11, risk 2).

## 8. Project-tree fingerprint — Batch F

**(f1) Utility — NEW `app/src/main/java/com/crush/mobile/domain/file/ProjectTreeFingerprint.kt`**
(next to its precedents). Deterministic walk of `ProjectRef.repoDir`: per
file, record relative path (forward slashes, portable), size, mtimeMs;
records sorted by path; SHA-256 over the sorted record stream → lowercase
hex (hex discipline as `ProjectTrustService.kt:85,:109-117`). Stat-only —
no file contents read, unlike `WorkspaceSnapshotter`'s content capture
(:27-29) — cheap enough to run at dispatch AND again at the verify turn; run
on `Dispatchers.IO` (the `ProjectTrustService.gate` :59 shape). Unreadable
paths get a deterministic marker row hashed like any other record (the
snapshotter's explicit-marker-over-silent-skip ethos, :24-25).

**(f2) Determinism tests (design §5: "fingerprint determinism/stability on fixture trees"):**
same tree twice ⇒ same hash; walk-order independence (fixture with deep
nesting); size or mtime change flips the hash; empty tree stable; marker row
for an unreadable file deterministic across two runs. Known accepted
false-negative class (same-size rewrite inside mtime resolution) is
DOCUMENTED, not discriminated — `WorkspaceSnapshotter.kt:33-38` states the
same property and why it is the correct trade; the verify turn re-walking is
what catches drift where it matters.

## 9. Restart-safe trace + resume — Batch G

**(g1) The row is the trace (design §4(6) verbatim).** A restart mid-handoff
re-locks the app (lock state is process-resident, `LockState.kt:13`: process
death is the natural reset; unlock-cancelled is never a death —
`DeviceAuth.kt:32`); the Room row survives and the FIDELITY of the dispatch
turn rests on the row + disk, never on cards — the runtime Review store
clears on process death (`ReviewHistory.kt:124`).

**(g2) Resume route — a NEW cold-boot extension of the `restoreLastBinding`
PATTERN, not a modification of it.** At `AppContainer.init` (:511-517), where
`restoreLastBinding()` already launches, a separate function (proposed:
`openLastDispatchedHandoff()`) runs beside it, mutated-none of its bytes:
read the DISPATCHED row (a3 query) → trust-gate the target project in the
fail-closed order (:462-508 pattern incl. the TrustVerdict.Required branch
surfacing `PendingProjectTrust`) → `applyBinding(EngineBinding.Project)`
(:495 pattern) → resolve the target session by id from the workspace's
session listing (`CrushApi.listSessions` :336-337; pick-by-id precedent
`ChatScreen.kt:1066`; history replay for display :1752). No matching live
session ⇒ the row stays DISPATCHED and surfaceable, never auto-deleted.

**(g3) Post-dispatch safety net — already shipped (Gates-2 borrowings, design
§4 tail).** Review history, per-file review/revert, Export a copy — the
differences land in `ReviewHistory` (runtime store, §11 risk 5 framing).
The composed sanitized return-note chip stays NOT in v1 (design §6); the
secret-blocking rule is honored structurally by Batch B (b1) — nothing
secret-bearing enters the target, and the return path only carries deliberate
exports at the user's review.

## 10. Risks (honest, one line each)

- **Engine-instance budget 2:** dispatch opens a project child while the chat
  pool may stay live; the registry's budget-2 non-legacy gate
  (`AppContainer.kt:212-213`, gate call :678) must absorb it via the proved
  stop-on-switch chain.
- **ASK cards on the verify-first turn:** the verify turn is a real tool turn
  under PermissionMode ASK (`PermissionMode.kt:15`, `AppSettings.kt:37`) and
  the consent card must not promise hands-off.
- **Free-model flake:** PAYMENT_REQUIRED/timeout rows in the ledger apply to
  BOTH ends of the workflow; retry-is-the-path stands (design §4).
- **MIUI battery:** the same brisk-drive discipline applies to handoff
  drives (design §4) — no long-lived wake-locks behind the sheet.
- **Runtime-only Review store:** `ReviewHistory` clears on process death
  (`:110`) — fidelity claims for the dispatch turn rest on disk + Room rows,
  never on cards.

## 11. Required from the OWNER before any code

The five §1 decisions (all of them; §7.1 and §7.4 are shape-defining, §7.2
picks one of two named composable slots, §7.5 orders everything herein relative
to P5 closure and the cold-boot batch) — plus these device/device-verifiable
facts, verifiable only on hardware:

1. Prefill selection: that a real chat session's last completed assistant
   message arrives via GET messages (`CrushApi.kt:342-343`) in the shape
   `StreamMessage.text` assumes (`CrushEvent.kt:66-77`) — including the
   failed-turn case (finish reason error, :86-89) degrading to the
   empty-editable composer (b3). One device pass before Batch B lands.
2. `sourceProvider`'s exact value origin: which app-readable record states
   the provider/model the source chat actually ran on (settings registry vs
   engine-reported) — the entity column (a1) and the preamble line (e3) both
   consume it verbatim.
3. Stat-walk cost on the LARGEST real project folder: one device measurement
   before freezing the fingerprint algorithm — imported (external-user-data)
   folders can be large (RoomProjectStore's removal docstring :54-59 treats
   them as user data), and the walk must stay cheap enough to run twice per
   handoff.

## 12. Appendix — anchor drift vs `design-handoff-p5-p6`

Anchors cited by the design doc that sit at slightly different lines today
(all re-derived by grep/read for THIS doc):

- ChatScreen bubble/actions: design said "~:558-568" — the bubble's
  `MarkdownText` call is :557 and the completed-guard + `MessageActionsRow`
  call are :561-568 (:567 guard, :568 call); the `MessageActionsRow`
  composable itself is DEFINED in `ChatExport.kt:184` — a sibling file,
  which the design's ChatScreen citation did not name.
- Code-block header row: design said "MarkdownText.kt:157-190" — the header
  Row is :163-192 (label :164-172, Copy :173-183, Save :184-191; :157-162 is
  the explanatory comment above it).
- Sessions POST: design said "CrushApi.kt:337-343 (sessions POST ~)" — that
  span contains three calls: listSessions GET :336-337, createSession POST
  :339-340, messages GET :342-343; the POST exactly is :339-340.
- Sheet labels: design said `ChatViewModel.kt:424-431` — the three label
  branches sit at :430-432 (:424 starts the explaining comment).
- Stands exactly: `WorkspaceManager.newSession` :272 ✓;
  `EngineBinding.Project` `AppContainer.kt:250` ✓; `EnginePaths` chat key
  :104-114 ✓.

**In-flight uncommitted edits observed in the tree (NOT this doc's work;
must commit before Batch A branches, so anchors re-pin):** ProjectTrust
gate→record window hardening is mid-flight in the working tree
(`ProjectTrustService.kt`, `AppContainer.kt`, `ProjectsViewModel.kt`,
`ProjectTrustServiceTest.kt`, and mid-flight `ReviewHistory.kt` — the file
drifted WHILE this doc was being written: the `ReviewHistory.kt:110` cite
re-derived to :124 before the doc pinned it). It ADDS
`ProjectTrustService.confirmAtGate(...)` (record-on-allow that persists ONLY
the gate-observed state, returning `TrustWriteOutcome`), widens
`TrustVerdict.Allow` with `observedFingerprint`, and switches BOTH consent
write paths to it (`restoreLastBinding` Allow branch, and the
requestOpen "Run it and open" route). Effect on this plan: the ORDER this
doc cites — gate first, record BEFORE bind, fail-closed on any window drift
— is unchanged (strengthened if anything), but the exact line numbers in
those three files shift when the batch commits (`gate` :59, `recordConsent`
:102, `restoreLastBinding` :441-509, `requestOpenProject` :222 were read at
the uncommitted state). §6/§9 of this doc re-verified correct against that
state, including the new `movedTo` → Required-treatment branches — Batch D's
consent-before-attach wiring must build on `confirmAtGate`, not
`recordConsent`, for the gated path once the batch lands. This doc writes
NO code and commits nothing.
