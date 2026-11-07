# P5 wrap + P6 handoff — preparation and research (2026-11-08)

Status: PREP/RESEARCH ONLY — no code here; every build claim cites a shipped
SHA or a file:line. This doc is the working brief for closing P5 and for the
P6 design decision, prepared while the chat Copy/Save batch was in its gate.
Owner decisions are requested at §7; nothing below files anything upstream.

## 1. P5 — what the slice requires vs what exists

Plan contract (crush-plan §P5): mode-specific tool/capability policy enforced
BELOW Compose; separate Chat vs project-linked Code sessions in navigation and
storage; model/provider keys shared while tool grants and project selection are
separate; regression-test Chat prompt-injection requests to modify files.
Proof rows: (a) Chat cannot execute shell or write source in the default mode;
(b) Chat works when Termux is absent and no Code projects exist. Artifacts:
chat screen, policy layer, security tests.

The implementation already satisfies the letter of every row:

| Requirement | Where it lives |
|---|---|
| Policy below Compose | `EngineConfigInjector.injectChatToolPolicy` writes `options.disabled_tools` (full built-in list) into the chat instance's global config BEFORE spawn and verifies by readback (`ChatPolicyResult.PolicyInstalled/AlreadyVerified` vs `PolicyFailure`); `CrushEngine.start()` runs `chatPolicyGate` FAIL-CLOSED — Chat never spawns as a tool-capable instance on any failure shape (CrushEngine.kt:244-296, 620-650; EngineConfigInjector.kt:232-420). Unit-pinned: `EngineConfigInjectorChatPolicyTest`, `ChatPolicySpawnGateTest`. |
| Engine-boundary proof on device | S1 drive measured (capabilities §security probes): with the policy installed, "run ls via bash" got the model's plain-language refusal — NO ask card, nothing executed; the denial persists in the engine's own config store until deliberately repaired. |
| Session/navigation separation | Chat rides the dedicated pool instance (`CHAT_INSTANCE_KEY = "chat"`, EnginePaths.kt:104-114): its own state dir `filesDir/crush-chat`, own `crush-chat.sock`, its own session pool, NO project cwd. Code sessions ride per-project children (`EngineBinding.Project`, AppContainer.kt:250) under the Gate-7 registry (one runtime per key, never evict, budget 2 non-legacy live children). The sheet labels the split in plain language: "Chats - conversation only" vs project rows (ChatViewModel.kt:424-431). |
| Shared keys/models, separate grants | Provider credentials + model slots are global; tool grants live in the engine-instance config, so separation is structural, not a UI promise. |
| Prompt-injection regressions | Covered by the two policy suites above plus the S1 device probe; the deny-ALL default means "modify a file" in Chat has nothing to reach through (v1 design decision Q2, decisions-open §10). |
| Proof (b): no Termux, no projects | Structural: there is no Termux dependency anywhere and the chat pool never touches the projects DB — every chat drive this week ran with Termux absent. Record as "self-evident by architecture + every chat drive". |

P5's closure therefore needs ONE more formal step: an acceptance fold that
cites the rows above (same style as the A03 Gate-0 fold) — after the chat
Copy/Save batch's device verify lands, so the fold can also record that
batch's SAF read of conversation text as P5-polish.

Backlogged beyond v1 (decisions-open §10 order, NOT blockers for closure):
1. "smart transfer" denial message — when a Chat model visibly tries to reach a
   file/shell, surface a chat-side message pointing at Code mode (the S1
   refusal is the carrier; the UI just names the seam if we see the pattern).
2. Interactive transfer-to-Code action — that IS P6's entry point (§4 below).
3. Read-only allowlist — only after an engine probe proves strict read-only;
   not before.

## 2. P6 — the plan's contract (verbatim anchors)

Outcome: "Make discuss → build the product-defining workflow."

Numbered items: (1) editable summary with source provenance and selected
attachments; (2) user chooses destination project and approves exactly what
context is copied; (3) Code agent verifies plan versus current repo before
first write; (4) sanitized Code→Chat completion note; block secret transcript
copying.

Proof rows: (a) no private project is touched until the user explicitly
selects and confirms it; (b) handoff remains traceable and resumable after
app restart. Artifacts: handoff models, consent UX, end-to-end flow.

The roadmap (plan §9) fixed the data model already:
`handoffId, sourceChatId, targetProjectId, targetCodeSessionId,
summaryMarkdown, userApprovedAt, selectedReferences[],
projectTreeFingerprintAtHandoff, expiryPolicy, sourceProvider` — with rules:
review/edit the summary before dispatch; show WHICH private project it will
access; the Code agent first verifies the summary against repository reality
and lists mismatches (no blind execution); if the target project changes,
require NEW confirmation (no dynamic target alias); "Back to Chat" carries a
sanitized implementation summary, never raw project secrets.

Two hard design inputs already in the docs:
- ADR-0001: P6 consumes the durable identity chain Project →
  WorkspaceBinding → Session → Run; the package keys on `targetProjectId`
  (stable UUID), never a label or a transient workspace id.
- decisions-open §10 (Q1): the in-session mode toggle (Q1(c)) has its natural
  home in this seam, and the "interactive transfer" backlog item is P6's entry
  affordance — build with that eventual shape in mind.
- upstream-outreach-plan §308-345: the CURRENT build's honest copy is "no
  Chat→Code handoff; that is an explicit roadmap item" — the feature gate for
  saying otherwise is this design being accepted.

## 3. Industry precedent — studied today (live fetches)

**Claude (Anthropic, docs fetched 2026-11-08).** Their cross-surface story is
SESSION CONTINUITY: terminal, VS Code/JetBrains, desktop, web and the mobile
app all connect to "the same underlying Claude Code engine", and the
surface-hops are explicit affordances over the SAME session object: Remote
Control (continue a local session from the phone), Dispatch (message a task
from the phone, open the Desktop session it creates), `claude --teleport`
(pull a web/cloud session into the local terminal), `/desktop` (continue a
terminal session in the desktop app). The load-bearing point: capability
level NEVER changes across those hops — every surface is the fully-loaded
agent; continuity is a session-object move, not a privilege move.

**OpenAI (Codex docs fetched 2026-11-08).** The ChatGPT desktop app presents
"choose where to work": start a chat, create a project, or open a folder —
Chat vs Work (Codex) is an explicit composer toggle, projects carry context
across chat and work, tasks can dispatch to Codex Cloud and land back; and
there is a documented "Import from another agent" flow (developers.openai.com
/codex/import) — i.e., structured context import BETWEEN agents, again with
the destination agent's full toolset already granted.

**Why we consciously diverge.** Both products can afford "seamless teleport"
because the capability ceiling is constant across surfaces. OURS IS NOT:
Chat's engine instance has every Code tool hidden by config (S-01, verified
by readback), while a Code instance can write the workspace. A Chat→Code
handoff here is therefore a CAPABILITY ELEVATION, and the plan's §9 re-encodes
continuity as CONSENT MEDIATION: not a session teleport but an authored,
versioned, user-approved context package + explicit target + verify-first.
That divergence is the safety thesis of the whole design and matches both
plan §8 ("a visible 'Use this plan in Code' action creates a proposed context
handoff with user confirmation; it does not silently grant folder access")
and the Q2-style engineering we already shipped. Practical note for v1: the
elevation is also WHY no session-import API is needed - the context moves as
TEXT we ourselves compose, and the destination engine does not need to read
the source transcript at all.

## 4. Proposed v1 architecture (for owner decision at §7)

**The engine-native seam (everything needed already ships):**

1. Source: chat transcript is engine-side (app keeps no Room mirror today —
   the only Room table is `projects`). The composer prefetches the last
   assistant message via the EXISTING `GET /v1/workspaces/{id}/sessions/
   {sid}/messages` (CrushApi.kt:337-343, the same channel that replays history).
   Degradation rule: if the chat engine is not attached, the composer opens
   EMPTY and stays fully editable — the flow never depends on a live engine.
2. Draft the package: summaryMarkdown prefilled from that message (user edits
   it), plus optional selected code-block snippets per the ChatExport surface
   just built (the same text the user can already copy/save is what can attach
   as a reference). NO automatic full-transcript copy, NO address/key-shaped
   content: the package carries only what the composer shows.
3. Consent: destination picker lists rows from the projects table; the consent
   card names the project + what will run there ("Code agent with file and
   shell tools"), Confirm is the handoff approval. Changing the target after
   approve = new draft + new approval (no aliases).
4. Dispatch: `WorkspaceManager.newSession(title = "Handoff <HHmmss>")` in the
   target project's workspace (existing POST /v1/workspaces/{id}/sessions,
   WorkspaceManager.kt:272), record `targetCodeSessionId`, bind the engine the
   same route the Projects row uses, and send a FIRST TURN of the shape:
   provenance preamble (handoffId, source chat title/time, fingerprint) +
   approved summary + selected references + an explicit
   VERIFY-FIRST instruction ("inspect the repo against this summary, list
   mismatches, do not write files until we confirm"). Item (3) of the plan
   without new engine capability.
5. Fingerprint: at dispatch, a deterministic walk of the target project root
   (path + size + mtime, sorted) → SHA-256 → `projectTreeFingerprintAtHandoff`.
   The verify-first turn can then state what changed since the handoff was
   approved (repeat the walk at turn time).
6. Traceability + resumability: a `handoffs` Room table (v3, additive — same
   discipline as the trust migration) carrying the §9 schema verbatim with a
   DRAFT → APPROVED → DISPATCHED → (DONE/ABANDONED) status. Restart-safe by
   construction: the row is the trace; resuming opens the target code session
   from the row. Satisfies proof row (b) directly.
7. Back to Chat (v1 minimal): the code session's outcome text reaches the
   chat through the surfaces we ALREADY shipped tonight — copy/save from a
   Code reply (ChatExport) or the Review screen's Export a copy / reverted+
   changed-file rows as the "implementation summary". A composed
   "sanitized note" chip is a v2 nicety; the plan's block on raw secrets is
   v1-honored by the package rule in (2) (nothing secret-bearing ever enters
   the target; the return path only ever carries what the code session AT
   THE USER's review hands over through deliberate exports).

**Gates-2 borrowings that make the loop bidirectional:** the dispatched turn's
changes land in ReviewHistory (runtime store), hunks get reviewed, per-file
reverts refuse stale targets, and Export a copy preserves results — the whole
device-proven Review/revert+export surface from the Gate-2 drive is P6's
post-dispatch safety net.

**Constraints and risks named now:**

- Engine-instance budget: dispatch opens a project child while the chat pool
  may stay live; the registry's budget-2 non-legacy ceiling and its
  supersession machinery must absorb chat + project (usually room for 2; a
  THIRD live child reuses the stop-on-switch chain E6 proved).
- The verify-first turn is a full agent turn with tools: it will hit the
  engine permission card flow (user Allow) like every Code drive — the consent
  card must not promise a hands-off run.
- Free-model flakiness (PAYMENT_REQUIRED/timeout rows in the ledger) applies
  to both ends of the workflow; the retry-is-the-path ops note stands.
- Lock gates: a restart mid-handoff re-locks the app; the Room row keeps
  traceability, the session resumes unlocked as measured.
- Runtime-only Review store clears on process death (by design) — fidelity
  claims for the dispatch turn must rest on disk + Room rows, never on cards.
- MIUI battery: the same brisk-drive discipline applies.

## 5. Test plan (pre-written expectations)

Unit (JVM): package builder redactions (nothing secret-shaped; nothing not
shown in the composer), fingerprint determinism/stability on fixture trees,
Room handoff round-trip + status machine, no-alias re-approval rule,
first-turn preamble assembly (provenance + verify-first wording exactness).
Device proofs map to plan's two rows: (a) fresh-approval drive (no touch
before Confirm; cancel leaves the project untouched AND unopened), (b)
restart-resume drive (dispatch → kill process → reopen → trace resolves and
the target session is reachable from the handoff row).

## 6. Explicitly NOT in v1

Expiry enforcement (field reserved), any session-import/teleport machinery,
automatic full-transcript mirroring into Code, composed return-note
generation, multi-project fan-out, in-session mode TOGGLE (Q1(c) stays a seam
decision for owner), read-only allowlist in Chat.

## 7. Decisions requested from the owner (nothing proceeds without these)

1. Accept the consent-package architecture (§4) vs any lighter "copy plan
   into composer" shortcut the owner might prefer (lighter = no Room rows,
   weaker traceability row (b) — recommend AGAINST, the plan demands it).
2. Composer entry point: a "Use this plan in Code" text button under
   completed assistant messages (same row as the new Copy/Save actions) —
   approve that placement?
3. Fingerprint v1 = stat-walk (path+size+mtime, hash) OK?
4. Is the verify-first first-turn wording binding for the dispatch (my
   recommendation: yes, it makes plan item (3) provable)?
5. Sequencing: P5 acceptance fold + Copy/Save batch FIRST, then P6 build as
   its own gated batch series (consent UX → package model → dispatch) — with
   the cold-boot unlock batch interleaved per the standing deferral list?
