# V2-5 IA correction: Crush Chat / Crush Code

Written 2026-11-20 from the owner's notebook sketch and the source plan
(`crush-plan-2026-11-02.md`, `chat-code-modes-plan-2026-11-04.md`). This
supersedes the earlier V2-5 assumption that "Projects" was a chat launcher.

## The IA (from the sketch)

```
[ Crush Chat ]   [ Crush Code ]   [ Settings ]

Crush Chat (legacy workspace)
  └─ chat list → conversation (question/reply only)

Crush Code
  └─ project list → project → sessions/chats → Code chat
                  └─ Review changes

Settings
```

- Three primary destinations only: **Crush Chat, Crush Code, Settings**.
- "Projects" is not a tab. It is the project list inside **Crush Code**.
- Crush Chat uses the **legacy workspace** and is Q&A only.
- Every Code project has its own chats/sessions.
- The Chat → Code handoff remains the explicit bridge.

## Decisions

### D1 - Chat uses the legacy engine instance, with the chat boundary

The sketch says "Crush Chat will use the legacy workspace". Today Chat uses a
separate `ChatPool` instance and legacy is a third binding. To follow the
sketch without weakening the tool boundary:

- Chat binds `EngineBinding.Legacy` (`instanceKey = ""`).
- `CrushEngine.start()` applies the same readback-verified `injectChatToolPolicy`
  deny-all list to the legacy instance that it applies to `ChatPool`.
- `startChatSession()` (New chat in Chat) keeps the Legacy binding; it no
  longer switches to ChatPool.
- `ChatPool` remains code-reachable for compatibility with sessions created
  before this change, but it is no longer the Chat tab's engine. Existing
  ChatPool sessions are not deleted; they are archival unless a compatibility
  surface is added later. This is recorded as a limit, not silently migrated.
- The chat boundary stays an engine boundary, not a hidden-button policy.

Risk: old legacy sessions were created when tools were available. Their
existing transcripts remain viewable; new turns on the legacy instance are
tool-free. This is the intended reclassification of legacy as Chat history.

### D2 - List-first Chat and Code

- The Chat route renders a **chat list** first (Legacy sessions), not the last
  conversation. Selecting a chat opens it; Back returns to the list.
- The Code route is the **project list**. A project opens its home; "Open Code
  chat" binds the project and opens the **project session list** (the same list
  surface, scoped to that project). Selecting a session opens Code chat.
- "New chat" in Chat mints a Legacy session. "New session" in Code mints a
  session on the current project engine; it must not jump to Chat.
- The shell bottom bar stays on the list screens (primary routes). The
  conversation remains on the same route; Back is handled in-screen to return
  to the list.

Implementation note: this reuses the existing `ChatViewModel`, `ChatViewState`,
`SessionList`, and `ChatAction` seam. It adds a list mode to `ChatScreen`
(local `showList`) and does not add a fourth route or a second ViewModel.

### D3 - Code project home

The project home remains the bridge between the project list and its sessions:

- identity: label, path, imported/app-managed, trust;
- latest measured turn from ReviewHistory (or "not measured");
- actions: **Open Code chat** (bind → project session list) and **Review
  changes** (bind → pushed Review).

The home never binds by itself. The trust gate runs before either action.

### D4 - Labels and language

- `nav_chat` = **Crush Chat**; `nav_projects` = **Crush Code**; Settings stays.
- Legacy/ Chat header status = `Crush Chat · <status>`.
- ChatPool (compatibility) = `Crush Chat - no tools · <status>`.
- Project header = `<project label> · <status>`.
- Session sheet surface labels: "Crush Chat" for Legacy/ChatPool; "Project -
  <label>" for Code.
- The route names (`chat`, `projects`, `settings`) stay stable to avoid a
  navigation rewrite; the user-facing labels and screens change.

### D5 - What is not in this batch

- Explore/file browser (V2-A9 remains deferred).
- Branch/status and test results (no Git or test-runner producer).
- Migrating ChatPool history into Legacy.
- Tablet panes (O3).
- Review history persistence and a test runner.

## Implementation phases

1. **Boundary and labels** - legacy chat-policy injection, Chat binds Legacy,
   New chat/session behavior per binding, nav/string/header labels.
2. **List-first surfaces** - Chat list, project session list, Back-to-list,
   empty/loading/unavailable states using the existing session data.
3. **Project home integration** - Open Code chat lands on the project session
   list; Review remains pushed.
4. **Tests** - policy gate for `""`, Chat/Project new-session behavior, list
   rendering/selection/back, labels; full gate.
5. **Device pass** - Chat list, Code list, project → sessions, IME, Review,
   isolation; restore/process recreation.

## Evidence

Code and gate: PASS (`v2-5-gate-1120-8.log`, 917 tests / 96 suites / 0
failures; lint 0 errors / 1 pre-existing warning). Final device pass: **NOT
PERFORMED** — the owner skipped it on 2026-11-21. Pre-fix captures and their
limits are listed in `../parity/2026-11-20-v2-5-code/MANIFEST.md`.
