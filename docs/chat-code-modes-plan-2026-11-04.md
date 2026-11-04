# Chat / Code modes — initial research and planning (2026-11-04)

Status: PLAN with user decisions RECORDED (2026-11-04, §5) — no code written,
implementation awaits the user's explicit go. This distills the roadmap's
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
  Remaining Code-mode gaps are the changes rail on external files and a true
  Review/revert surface.

### Sequencing

- P5 depends on P3 (done) and "can develop alongside P4"; the ADR hard stop
  is satisfied, so Chat/Code navigation may now be built.
- **P7 (Termux L1) depends on P3 + P5** — the user's queue order
  (modes before Termux L1) matches the plan's dependency graph.

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
| S0 | Events-socket per-instance fix + changes-rail external-file support (already top of queue; unblocks keyed instances generally) | drive: imported-folder turn completes with no false banner; rail shows +N -N for notes.md/todo.md |
| S1 | **Engine capability probe** (no product code): enumerate engine agents; test per-agent tool config; test a session opened with restricted agent + no-yolo; test tool-denial behavior. All evidence through the app, ids safe | probe log + `capabilities.md` update |
| S2 | **Product decisions** — RESOLVED 2026-11-04 (Q1-Q4 answered, see §5) | decisions-open.md item 10 + plan §5 |
| S3 | Mode model: `AgentMode` enum, per-session storage, engine-instance routing (legacy / project key / chat key), run-in-flight guard reuse | JVM unit tests + gate |
| S4 | UI: New-chat mode choice, sheet sections, header badge; enforcement wiring (auto-deny policy layer with the proven ASK plumbing) | drive: MODE-01 injection attempt denied; MODE-06 regression (Code ASK intact) |
| S5 | Acceptance run vs §21 matrix rows 01/06/07 + docs: capabilities.md, BASELINE, this plan updated to MEASURED | gates green + docs commit |

Slices stay parallel-safe with the current backlog order: S0 is the existing
top-of-queue defect; Termux L1 lands after P5 per the dependency graph.

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

- **S1 stays but is re-scoped**: v1 enforces deny-all app-side on the proven
  ASK plumbing, so the engine probe no longer BLOCKS v1 — it informs the
  deferred items (engine agent seating hardening, read-only allowlist,
  transfer-to-Code action).
- **v1 execution order** (on the user's go): S0 socket defect → S3 mode
  model (deny-all chat policy) → S4 UI (creation choice + groupings + badge
  + deny message) → S5 acceptance vs MODE-01/06/07.
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
