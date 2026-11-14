# Chat / Code modes — initial research and planning (2026-11-04)

> **Superseded in part (2026-11-05, verified to `1d229de`, HEAD):**
> the status paragraph below was true on 2026-11-04 only and is kept for
> chronology. S0–S5 have since shipped with on-device evidence as of
> `1d229de`: S0 socket+diff fixes built `349bad3` and verified live
> (`1c4c28c`), S1/S1b probe
> docs, S3 built (`93c4dc8`), S4 mode UI + drives (`4058588`, `22fbe28`,
> `1bf7101`), S5 with **A05/A11/A12 measured** (`8eb0477`), **A06
> measured** (per `9dcfa0b`), and the Gate-7 wedge phase 1 landed in
> `1d229de` — consolidated in [`capabilities.md`](capabilities.md) and
> [`CRUSH-MOBILE-STATUS-AND-DECISIONS.md`](CRUSH-MOBILE-STATUS-AND-DECISIONS.md).
> Per the owner's accepted 2026-11-05 pack, the **Gate-0 (trust + chat +
> REST: S-01 fail-open, S-02 restore-trust, S-04 REST fallback) and
> Gate-1 (E2/E4/E5/E6) priorities supersede this file's historical
> S0→S5 slice ordering (§4) and the §5 "implementation NOT started"
> framing**, which is retained only for record.
>
> **Addendum 2026-11-08 (state refresh only; no decision text touched):**
> the §1 sentence "Remaining Code-mode gaps are the changes rail on
> external files and a true Review/revert surface" has since closed —
> the Gate-2 review/revert+export surface SHIPPED and was device-proven
> 2026-11-07 (`5f14dfc`/`c8dd154`, export-no-op fix `bf5019a`):
> per-turn change collector → ReviewHistory runtime store (MAX_CARDS = 8,
> process-memory only — the screen's header line says "Per-turn file
> changes recorded while the app runs. History clears when the app
> process ends", `domain/file/ReviewHistory.kt:47` +
> `ui/screens/review/ReviewScreen.kt:121–128`), per-file
> Revert with the confirm dialog and honest refusal surfaces, and the SAF
> "Export a
> copy" with byte-exact parity proven on device
> (`ui/screens/review/ReviewScreen.kt`). §7's "G6 ... backlog post-v1"
> reads accordingly: G6 is no longer backlog. P5's wrap also landed the
> owner-directed chat output save batch `fe9e7e8`
> (`ui/screens/chat/ChatExport.kt`: copy/whole-message save +
> per-code-block Copy/Save-as-file chips) — app-side polish only, no new
> engine capability, S-01 deny-all untouched. And the A06 `mkdir` card
> probe that §4/§7 still describe as "drive PENDING" was MEASURED that
> same 2026-11-05 evening (card fired for the non-safe chained command,
> deny honored +0 -0, nothing executed — capabilities.md "A06 acceptance
> drive"), so the renamed MODE-06 completion probe is done; the honest
> residual (denied turn slides to the no-reply failure banner instead of
> a narrated refusal) is recorded in that drive entry. P6 handoff prep
> is authored in
> [design-handoff-p5-p6-2026-11-08.md](design-handoff-p5-p6-2026-11-08.md),
> with five owner decisions listed there and nothing proceeding without
> them.

Status (2026-11-04; **superseded — see the header note above**): PLAN with user
decisions RECORDED (§5) and the independent review FOLDED IN (§7, user-approved
"fold fully"). No code existed on this date; S0–S5 shipped 2026-11-05 and the
Gate 0–4 work that superseded the slice ordering is recorded in the status hub.
This distills the roadmap's
Chat/Code phases ([`crush-plan-2026-11-02.md`](crush-plan-2026-11-02.md) §6, §7,
P4/P5/P6, §21) against what the app actually has on device today (2026-11-04,
import-only decision landed in `5ef70f5` + `4a59b68`), and lays out slices,
open product questions, and the verification matrix.

Sources: the plan sections above, [`adr-0001-project-workspace-identity.md`](adr-0001-project-workspace-identity.md)
(P4/P5 consumes the identity chain — its ADR hard stop is now RESOLVED, proven
on device), the shipped capability state in
[`capabilities.md`](capabilities.md), and the current code
(`PermissionMode`, `CrushSession`, navigation).

---

## 1. What the roadmap demands

### Chat mode (plan §6, phase P5)

- Usable with **no project and no Termux**; tool policy enforced **at the
  agent or service boundary, not by hiding buttons in Compose**.
- Default policy: **no shell, no file write, no package install, no repository
  modification**; "choose allowed safe read-only tools deliberately".
- Chat remains **project-agnostic** — never implicitly bound to the
  most-recently-opened Code project.
- Explicit **"Use this plan in Code"** promotion (that is P6's handoff —
  listed here for seam-keeping only; OUT of scope for the first slice).
- Acceptance (§21 **MODE-01**): prompt injection asking for `rm`/git write is
  **denied at engine policy**.

### Code mode (plan §7, phase P4)

- Starts at the **Projects screen** (we have this); per-project home;
  three workflows: **Explore (read) / Build (write+run after approval) /
  Review (diff/verify)**.
- Model/provider keys SHARED between modes; tool grants and project
  selection SEPARATE (P5 item 3).
- Acceptance rows already partially proven by the import-folder drive:
  multi-file edit (MODE-07 pre-context: `notes.md`+`todo.md` wrote two real
  files), ASK approval (MODE-06 measured 2026-10-29), SAF import (MODE-10).
  MODE-06 folded forward 2026-11-05: half A (Code tools alive) measured in
  the S5 drive; the S6 ask-per-shell premise dissolved — the no-card runs
  are the engine's v0.99.0 SAFE-READ-ONLY prefix bypass above the permission
  service (resolved in upstream source, see
  [`s6-ask-shell-recon-2026-11-05.md`](s6-ask-shell-recon-2026-11-05.md) §8),
  so half B's card probe was renamed to the non-safe command `mkdir`
  (drive PENDING for the next device session).
  Remaining Code-mode gaps are the changes rail on external files and a true
  Review/revert surface.

### Sequencing

- P5 depends on P3 (done) and "can develop alongside P4"; the ADR hard stop
  is satisfied, so Chat/Code navigation may now be built.
- **P7 (Termux L1) depends on P3 + P5** — the user's queue order
  (modes before Termux L1) matches the plan's dependency graph.

#### S3 implementation note (2026-11-05, built and gated, device verification pending)

S3 was built as the **mode model = the engine-instance binding**, not a
per-session stored field: a session's mode is DERIVED from the instance its
pool lives on (a chat-pool session IS a chat session), which keeps the
engine's own pools as the storage of record and avoids a Room migration for
v1. The review's §5 table wanted a session-mode Room table; the derivation
shape satisfies the same requirement (mode is durable per pool, the engine
owns transcripts) at smaller surface. Recorded honestly:
`EngineBinding { Legacy, ChatPool, Project(ref) }` in AppContainer is the
single binding authority (`setActiveProject`/`setActiveChatPool` → shared
stop-on-switch contract), the ViewModel routes via
`container.activeBinding`, the chat pool is a real keyed engine instance
(`EnginePaths.CHAT_INSTANCE_KEY = "chat"` → `filesDir/crush-chat`,
`crush-chat.sock`), the S1-verified `injectChatToolPolicy` runs at
CrushEngine spawn for that instance only, and ` effectivePermissionMode`
pins ASK there forever (yolo belt-and-braces, S1's engine-side hiding being
the actual boundary).

Implementation consequence for verification: the chat pool is reachable
ONLY through S4's mode picker, so the S3 mechanism verifies ON the S4
drive — one install, one device session covering pool denial on a real
engine turn (the tool-free label's final proof) plus the Code-mode ASK
regression. S4 next, unblocked by this slice's mechanism.

Deferred here, moved explicitly: the S5 A11/A12 row (process-death restore
to the last mode) needs a persisted last-engine-binding in DataStore —
recorded for the S4/S5 batch, not silently dropped.

---

## 2. What exists today (measured state)

- **No mode concept in code.** There is exactly `PermissionMode`
  (ASK / AUTO_APPROVE) persisted in DataStore, applied per workspace attach as
  `yolo = mode == AUTO_APPROVE` in `CrushSession.openWorkspace`, and consumed
  by the ASK-card flow (`ChatViewModel`, 2026-10-27 fix; PROMPT_WAIT_MS).
- **One chat screen serves everything.** The session sheet lists the LEGACY
  pool (`filesDir/crush`, shared `filesDir/workspace` cwd). Project sessions
  live on per-project keyed instances (`crush-<key>`) since slice 2. A "Chat
  session" today == any legacy-pool session; there is no enforcement boundary
  beyond ASK/yolo.
- **Projects are now import-only** (2026-11-04 decision): rows are external
  user folders or the permanent legacy row; creation of minted internal repos
  is removed. So "Code starts at a Projects screen" is already true; "project
  import" is already the desktop-app model the plan wants.
- **Engine agent plumbing exists**: `CrushSession.ensureAgentReady(mainAgent)`
  seats an engine agent — default is the engine's own **build agent** ("coder
  agent" in engine error strings). This is the likely enforcement hook for a
  read-only chat agent, to be PROBED (see S1).
- **Known defect on keyed instances**: the per-instance events socket fails
  ENOENT on the imported-folder instance (top of fix queue; false no-reply
  banner). Today's legacy chats work because the legacy instance's socket
  works.

---

## 3. Design shape proposed (draft — decisions pending)

### 3.1 Modes as a session attribute

`ChatMode` vs `CodeMode` is a property of a SESSION, chosen at creation and
stored with the session row (Room binding, or a DataStore-free per-session
row), never a global toggle alone. Each mode binds to an engine instance:

- **Code sessions** → existing per-project keyed instances (unchanged).
- **Chat sessions** → a dedicated keyed instance (e.g. key `chat` →
  `filesDir/crush-chat`, own pooled sessions, cwd `filesDir/workspace` or a
  scratch dir) — this is exactly the per-instance architecture built for
  projects, reused, and it satisfies "separate Chat sessions and
  project-linked Code sessions **in navigation and storage**" (P5 item 2)
  plus "project-agnostic" (§303).
- The legacy instance keeps hosting the EXISTING history (no migration; those
  sessions remain attached to the legacy row as they are today).

**Hard dependency:** a chat-keyed instance inherits today's events-socket
defect until it is fixed. Therefore S0 (below) is a true prerequisite of the
session-separation slice — it is already the top of the fix queue for
imported-folder projects anyway.

**Alternative rejected for v1:** running chat sessions on the legacy instance
with app-only enforcement avoids the socket bug but pollutes the legacy pool
(the app's home history) and leaves mode separation fake on the storage
axis — the plan explicitly wants separation, and we would pay a migration
later.

### 3.2 Enforcement ladder (per plan §6: agent-or-service boundary)

Layered, each layer independently provable:

1. **Engine agent seating** (if the engine offers a restricted/read-only
   agent — PROBE REQUIRED): chat sessions seat that agent instead of the
   build agent. Evidence, not assumption; S1 measures what v0.98.2/v0.99.0
   actually supports.
2. **Service boundary in-app** (works today with zero engine changes):
   chat sessions never open yolo; the ASK machinery (proven at 2026-10-29)
   is extended with a **chat tool policy**: any `write/bash/edit/patch`,
   package or repo-modifying tool request inside a Chat session is
   **auto-DENIED immediately** (not parked against PROMPT_WAIT_MS) and
   surfaces as a chat-mode message ("Chat can't write files — use a Code
   project"). Allow-listed safe read-only tools are DECIDED-OUT of v1
   (§5, Q2 = deny-all in v1); they remain a deliberate later option only
   with S1 probe evidence.
3. **Prompt reinforcement**: chat sessions ride the chat-appropriate system
   prompt/model slots (still provider-driven, no hardcoded ids).
4. **UI conservatism**: chat screen hides code affordances (model chip
   stays; diffs rail not shown for chat sessions) — cosmetic ONLY, never the
   boundary (plan's own wording).

The MODE-01 acceptance test targets layer 1-2 honestly: injection must be
DENIED, and the evidence is the deny-trace + no file/checkout change.

### 3.3 Navigation

Draft: the session sheet gains a **Chats** section (chat-pool sessions,
tracked separately from the legacy row's code-ish history) and the New-chat
flow gains a mode choice at creation (chip on the composer or a cast sheet);
the header badge shows the mode. Projects screen stays the Code entry today.
Full tab restructure (bottom Chat/Code tabs as the plan's screen spec
implies) and any in-session toggle are BACKLOGGED selectively (§5,
Q1 = choose-at-creation for v1); the toggle's honest place is the
Chat→Code promotion/handoff seam (roadmap §9, P6).

Model/provider keys stay SHARED across modes (P5 item 3): the model
catalogue, key storage and pricing drawer obviously do not fork per mode.

---

## 4. Proposed slices

| Slice | Content | Gate/proof |
|---|---|---|
| S0 | Events-socket per-instance fix + changes-rail external-file support (top of queue; unblocks keyed instances generally). Review fold-in: fix targets confirmed in source — replace the hard-coded legacy `workspaceRoot()` with the frozen project binding (F02) and make event-connection identity (instanceKey, socketPath, bootEpoch, workspaceId) instead of the port-only guard (F03); ENOENT root device-pinned by the same-evening trace; the repaired shape is recorded in capabilities.md | **VERIFIED 2026-11-05 on `349bad3`**: turn with no false banner, rail exact +2 -0 with hunks and 0 unreadable, A→B→A clean with honest capped retries during the engine-down window |
| S1 | **Engine capability probe** — DONE 2026-11-05: mechanism PROVEN on the pinned engine (`options.disabled_tools` hides tools from the agent; persisted by the engine's own config store; per-instance scope) — see capabilities.md S1/S1b. **Blocks the tool-free Chat label** (F01) — label now UNBLOCKED pending S3 wiring | probe log + `capabilities.md` S1/S1b |
| S1b | **Imported-folder config trust probe + policy** — PROBED 2026-11-05: the pinned engine EXECUTES project-root `crushrc` as bash on every workspace attach (twice per cycle, app's full UID, no consent gate); `.crush.json`/`.crush/crush.json` did not execute in the paths probed. S3/S4 must add the import-time trust/quarantine gate (F04/A05) | sentinel drive: imported folder with sentinel hook performs NO execution before a visible trust decision |
| S2 | **Product decisions** — RESOLVED 2026-11-04 (Q1-Q4 answered, see §5) | decisions-open.md item 10 + plan §5 |
| S3 | Mode model — BUILT 2026-11-05: `EngineBinding { Legacy, ChatPool, Project }` (mode = binding semantic, derived from the instance pool; see the S3 implementation note in Sequencing), dedicated chat-pool engine (`filesDir/crush-chat`), spawn-time `injectChatToolPolicy` (S1-mechanism, injector idempotency + malformed-file safety tested), chat-yolo lock. Verification rides the S4 drive (pool reachable only through S4's picker) | 6 new injector tests + gate |
| S4 | UI: New-chat mode choice, sheet sections, header badge; enforcement wiring (auto-deny policy layer with the proven ASK plumbing) | drive: MODE-01 injection attempt denied (deny-all holds, binary-verified config); self-test on import + legacy switch — MEASURED 2026-11-05. MODE-06 split honest: Code tools alive MEASURED; the ASK gate is engine-policy-scoped (asks for writes, auto-allows shell) — RESOLVED 2026-11-05 in upstream source (not an app defect): no-card runs live inside the engine's designed SAFE-READ-ONLY prefix bypass above the permission service; S6 ask-per-shell config injection DISSOLVED (upstream knob is allow/deny only — see capabilities.md S5 drive + [`s6-ask-shell-recon-2026-11-05.md`](s6-ask-shell-recon-2026-11-05.md) §8); the card probe renamed to the non-safe command `mkdir` — drive PENDING for the next device session |
| S5 | Acceptance run vs §21 matrix rows 01/06/07 + docs: capabilities.md, BASELINE, this plan updated to MEASURED | **MEASURED 2026-11-05** on `8eb0477`: A05 (consent gate — marker appears exactly on "Run it and open"), A11/A12 (DataStore token `proj:<full-uuid>` → `chat`; cold boot logged `restoring last binding: chat pool` then `[chat] supervise loop start`; badge + chat-pool session + chat model on restore), F04 dialog live with two-pass crushrc-as-config refinement; honest residuals recorded (shell not ask-gated by engine default; warm-rebind transcript staleness). Doc commit + pushed CI green stands from part 1 |

Slices stay parallel-safe with the current backlog order: S0 is the existing
top-of-queue defect; Termux L1 lands after P5 per the dependency graph.
Sequencing aligned 2026-11-05: there is no S6 arming step to schedule
(ask-per-shell injection dissolved, resolved in upstream source — recon §8);
the wedge-fix vehicle is the Gate-7 registry batch
([`design-runtime-registry-2026-11-05.md`](design-runtime-registry-2026-11-05.md)),
IMPLEMENTED in phases — phase 1 engine-layer interlock + starting-watchdog
(in flight this evening), phase 2 container registry + §3 reclaim (queued).

---

## 5. Decisions recorded (2026-11-04, user-answered) — implementation NOT started

Answers arrived while the user runs their own research; recorded here
SEPARATELY from the research above and in [`decisions-open.md`](decisions-open.md)
as item 10. Neither S0 nor S1/S3/S4/S5 start until the user's explicit go.

- **Q1 navigation → (a) choose-at-creation.** Mode is picked in the
  new-session flow; Chats group separately from project sessions. The user
  wants (b) two-tab restructure and (c) in-session toggle used "in some
  useful places" — BACKLOGGED selectively, not v1. Honest mapping: (c)'s
  useful place is the Chat→Code promotion/handoff seam (roadmap §9, P6) —
  an explicit, disclosed in-session "move this to Code" upgrade, deferred
  to that phase; (b) is a navigation rebuild for a later polish pass.
- **Q2 Chat tool policy → (a) deny ALL tools in v1** (pure conversation).
  Deferred, in expected order: (i) the "smart transfer" flow the user asked
  for — when a write/shell request is auto-denied in Chat, the denial itself
  already rides S4's chat-mode message ("Chat can't write files — take this
  to a Code project"), and the interactive transfer-to-Code ACTION lands
  later (naturally the Q1(c)/P6 seam); (ii) read-only allowlist — only after
  S1's engine probe proves candidate tools strictly read-only.
- **Q3 default → (a) fixed Chat default** for new sessions. A Settings
  choice (option c) is backlogged, not v1.
- **Q4 legacy history → (a) leave untouched** — recorded on the user's
  delegation ("wipe-and-restart OR your choice"), standing until their final
  confirmation at go-time. Rationale: deleting real history buys only a
  prettier list; a deliberate, fully-disclosed "clear legacy history" action
  can be built later if a fresh start is truly wanted — never a silent wipe
  (precedent: decisions-open item 9 already reserved "delete chats" as a
  SEPARATE future feature, with the legacy row itself permanent).

### 5.1 What the decisions change in the slices

- **S1 BLOCKS the tool-free Chat label** — REVISED 2026-11-04 on the review
  fold-in (F01): app-side ASK auto-deny reacts only to approval events a
  tool request already emitted; it cannot prove no other tool ran. Set
  engine-side/token-boundary denial must be PROVEN (tool enumeration + real
  traces on the pinned engine) before Chat ships labeled "tool-free";
  app-side auto-deny STAYS as defense-in-depth. Code-mode slices (S3/S4)
  do not depend on S1.
- **v1 execution order** (on the user's go): S0 transport identity +
  imported-root diff fix → S1 probe + S1b imported-config trust (the two
  security proofs) → S3 mode model → S4 UI → S5 acceptance vs
  MODE-01/06/07 plus the review's A-matrix additions (§7).
- **Explicitly NOT v1** (backlog): tabs restructure, in-session toggle/upgrade,
  read-only allowlist, interactive transfer-to-Code, Settings default toggle,
  legacy-history clear/retire (only if the user later opts in).

---

## 6. Risks / known interactions

- The events-socket defect (ENOENT on keyed instance) blocks new keyed
  instances, including the chat pool — S0 first.
- The changes-rail defect (external files "unreadable") is a Code-mode UX
  gap in the Review workflow — ride S0's fix wave.
- Section 5 storage spec's `conversations/` metadata dir is NOT built;
  chat-specific metadata (mode, provenance-ready fields for P6) can ride the
  Room store instead — no filesystem format commitment in v1.
- Lock/credential rule (§6 last bullet) is already honored: nothing about
  mode decryption or engine start changes the app-lock contract (MODE-21
  regression on the drive).

---

## 7. Independent review fold-in (2026-11-04, user-approved "fold fully")

Input: `Crush_Review.pdf` (2026-11-04) — an independent connected-GitHub
source review pinned at `b8aa505`. Its five verifiable source claims were
re-verified in our tree the same day and ALL held: F02 hard-coded
`workspaceRoot()` (`ChatViewModel.kt:2388`; used :2187/:2418); F03
`UDS_PORT_SENTINEL = 1` + port-only reuse guard (`ChatViewModel.kt:1175`);
F01 `resolvePermission` reaction-only approval model; F06 single
`projectRuntime` slot (`AppContainer.kt:157`); F11 `okClient() ?: plain`
fallback (`CrushApi.kt:101`). The review honestly marks its own limits: no
compile/device tests, ENOENT root cause unproven, F04 = threat hypothesis
pending device proof.

**Confirmed unchanged from the review:** the Q1–Q4 product choices (only
Q2's enforcement MECHANISM corrected — §5.1 revised); the user's
smart-transfer idea as scenario A17; S0-first ordering and the deferral of
tabs/toggles/automation; session-mode as durable Room metadata.

**Adopted as plan changes:** S1 blocking for the tool-free Chat label;
new slice S1b (F04 imported-config trust probe/policy); execution order
S0 → S1+S1b → S3 → S4 → S5; the review's Gate 0–9 sequence maps onto our
slices (G1≈S0, G2≈S1b, G3≈S1, G4≈S3, G5≈S4; G6 Code-Review surface and G7
runtime-resource work are backlog post-v1 (G7 adapted 2026-11-05: its
registry batch is now the in-implementation wedge-fix vehicle — see the
Sequencing note); G8 ≈ P6 handoff; G9 ≈ Termux,
unchanged P7).

**Backlog additions (recorded, not v1):** F06 managed runtime map with
run ref-counts and a max-active-engines budget; F07 canonical `repo_dir`
identity + unique index; F08 access states rechecked at project-open;
F09 crash-atomic deletion journal; F10 snapshot-coverage honesty
("not measured" ≠ "no changes"); F11 fail-closed UDS transport; F12
gradual RunCoordinator extraction out of the 2.8k-line ChatViewModel
(shapes S3 without a rewrite). F05 storage strategy → decisions-open
item 11. New 2026-11-05: wedge shape 2 delivery-loss (container-level) —
phase 2 of the registry design; the supervisor starting-watchdog covers
only shape 1 ([`design-runtime-registry-2026-11-05.md`](design-runtime-registry-2026-11-05.md) §3).

**New acceptance rows adopted** (beyond the MODE matrix): A05 — no execution
from imported config before an explicit trust decision — **MEASURED 2026-11-05,
see capabilities.md S5 drive** (sentinel appears exactly on the consent tap;
crushrc refined to engine-config channel, twice-per-attach load confirmed in
the engine log). Still open: A06 — adopted 2026-11-05 as the renamed MODE-06
completion probe (its ask-per-shell premise dissolved, resolved in upstream
source, see [`s6-ask-shell-recon-2026-11-05.md`](s6-ask-shell-recon-2026-11-05.md)
§8): the card-must-appear fixture is a NON-safe command — `mkdir` → card →
deny → honest refusal — with `date`/`ls` the no-card safe-list control and
banned `wget` the outright-refusal control; drive PENDING for the next
device session. A07 duplicate/aliased import = one canonical row;
A10 crash-during-removal reconciliation never touches user files; A13 snapshot
coverage limits stated, never an unqualified "clean"; A14 missing socket fails
closed with no TCP fallback or credential spill; A17 Chat→Code transfer shows
the target project and inherits no Build policy; A20 a run keeps its frozen
provider through a mid-run change.
