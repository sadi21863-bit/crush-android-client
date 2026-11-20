# V2-5 research: the Chat / Code split as originally planned

Written 2026-11-20 after re-reading the source plan, not just the V2 summaries.
This is the reference the V2-5 implementation must follow. It records where the
current implementation drifted and the one consequence that is still an owner
decision.

## Sources

- `docs/crush-plan-2026-11-02.md` (product thesis and screen spec).
- `docs/chat-code-modes-plan-2026-11-04.md` (mode model, navigation answers,
  legacy-history answer).
- `docs/adr-0001-project-workspace-identity.md` (identity/run-freeze).
- `docs/v2/ADR-REGISTER.md` V2-A2/A3/A9/A10.
- `docs/v2/V2-5-code-projects-review.md` and the Rev 3.1 brief §4.7.2/§4.7.3.

## What the plan actually says

### The product split

> "Crush Chat is conversation-first: asking, learning, brainstorming, planning,
> analysis and drafting. Crush Code is project-first: selecting an actual
> repository, inspecting relevant files, planning modifications, approving
> tools, editing multiple files, reviewing diffs and verifying tests. A
> user-approved handoff bridges the two without silently increasing
> privileges." - `crush-plan-2026-11-02.md:178`

The architecture is explicitly three layers:
`Chat experience` (no code-execution tools) + `Code experience`
(ProjectRegistry -> Explore/Build/Review) + `Handoff composer`
(`crush-plan-2026-11-02.md:220-224`).

### Chat

- Project-agnostic; usable with no project and no Termux
  (`crush-plan-2026-11-02.md:300-303`).
- Tool policy enforced at the agent/service boundary, not by hiding buttons.
  Default: no shell, file write, package install or repository modification.
- "Use this plan in Code" creates a proposed handoff with user confirmation; it
  does not silently grant folder access (`:304`).
- The implemented mode is `EngineBinding.ChatPool`: a dedicated engine instance
  with the injected deny-all/chat policy (`chat-code-modes-plan-2026-11-04.md:111-126`).

### Code

- **Starts at the Projects screen**, not at an empty chat
  (`crush-plan-2026-11-02.md:309`).
- Each project has a **project home**: "Current branch, capability status,
  active runs, change count", primary action "Explore / Build"
  (`crush-plan-2026-11-02.md:321-327`). The chat/list flow is separate:
  "Code chat" with agent conversation, tools, permissions and run status
  (`:329-333`).
- Workflow is **Explore (read) / Build (write+run after approval) / Review
  (diff/verify)** (`:309`; `chat-code-modes-plan-2026-11-04.md:84-88`).
- Projects screen content: "Imported/local projects, repo identity,
  environment badge, last activity" (`crush-plan-2026-11-02.md:317-321`).
- Review is a trusted app surface: host-collected changes, provenance,
  revert/export (`crush-plan-2026-11-02.md:335-351`; brief §4.7.3).

### Navigation

- The source plan describes Chat and Code experiences, but the owner's
  navigation answer was deliberately smaller: mode is chosen at session
  creation, chats are grouped separately from project sessions, and the full
  two-tab restructure was **backlogged**
  (`chat-code-modes-plan-2026-11-04.md:223-230`, `:266-272`).
- V2-A2 encodes the shipped shell: three primary destinations - **Chat,
  Projects, Settings**; Review is a pushed route. No fourth "Code" destination.
- Therefore **"Projects" is the Code entry screen**, and "Crush Code" is the
  experience/mode, not a fourth tab. The plan's own screen table calls the
  screen "Projects" and the row "Project home"
  (`crush-plan-2026-11-02.md:317-327`). Renaming the tab is not required by the
  plan; it would be a shell-label decision, not a product-model fix.

### Legacy history

- The legacy workspace hosts the pre-mode history and must not be wiped
  (`chat-code-modes-plan-2026-11-04.md:181-183`, `:282-288`).
- At that time the Projects list showed the legacy row because it was the only
  way to reach that history. The owner has now corrected the product model:
  legacy is **Chat history**, not a Code project, and must not sit beside
  imported project folders (V2-A10).

## Where the implementation drifted

| # | Drift | Evidence | Correction |
|---|---|---|---|
| 1 | Projects listed the legacy Chat workspace beside Code projects | `ProjectsContent.kt` row list; V2-0 F-A/brief §4.7.2; V2-A10 | Hide it (V2-A10); keep the Room row for history/identity |
| 2 | A project row jumped straight into chat; no project home | `ProjectsScreen.kt` `onOpen` -> bind -> Chat; plan `crush-plan-2026-11-02.md:321-327` | Row opens a home: identity, path, trust, last measured changes, Open Code chat, Review |
| 3 | Projects carried no Code activity | rows showed chat-registry fields; plan `:317-321` asks for last activity | Show the latest measured turn (or "not measured") in the home and row |
| 4 | Review was process-global, so one project's turn superseded another's cards | `ReviewHistory` pre-V2-5; brief §8.4.2 #8 | Fixed: per-root scope, run flags, cap, host filter |
| 5 | A not-measured turn vanished, equating "not measured" with "no changes" | `FileChangeSet.isEmpty` + `recordTurn`; plan F10 | Fixed: retain a not-measured card and label it |
| 6 | The IME bar sat between composer and keyboard | owner device report 2026-11-20; V2-3 capture | Fixed in `CrushShell`: hide the phone bar while the IME is visible |

## The consequence that needs an owner decision

Hiding the legacy row removes the **only current UI path** to the pre-mode
legacy transcripts. Chat today binds `ChatPool`; its session sheet lists only
the active pool. The plan wanted chats grouped separately from project
sessions, but the two-tab/chat-history restructure was explicitly backlogged
(`chat-code-modes-plan-2026-11-04.md:266-272`). The options are:

1. **Accept archival** - legacy history stays on disk, untouched, but V2 has no
   UI path to it. Hiding it is honest and the smallest change.
2. **Earlier chats view** - add a read-only Chat-side surface for legacy
   transcripts. This needs a safe way to read the legacy pool without turning
   Chat into a tool-enabled mode; it is real work, not a label change.
3. **Migrate** - move legacy sessions into the ChatPool. Risky, not planned,
   and not V2-5.

V2-5 batch 2 proceeds with option 1 unless the owner says otherwise; the V2-5
record marks option 2 as the follow-up if old history must remain reachable.

## Corrected V2-5 scope (batch 2)

- Projects lists **Code projects only**; the legacy row is filtered out.
- A project row opens a **project home**, not chat:
  - label, path, imported/app-managed identity, trust;
  - latest measured turn from `ReviewHistory` for that root (file count,
    additions/removals, time, or "not measured");
  - **Open Code chat** (trust-gated bind + Chat replace);
  - **Review changes** (same gate, then Review for that project).
- The home never binds by itself and never grants permission.
- No file browser / Explore (V2-A9); no branch/status or test results because
  the app has no Git or test-runner producer. Those are honest limits, not
  invented rows.
- Keep the three destinations and the "Projects" label; "Crush Code" is the
  mode/experience name.
