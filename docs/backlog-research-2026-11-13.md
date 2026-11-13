# Backlog research — 2026-11-13 (v0.100.0 era)

Research pass over the standing TODO list, requested before implementation.
Each section states findings (with code/upstream evidence), options, a
recommendation, and the decision it needs. Nothing here is implemented unless
the row says so.

Device context: `bbf8ca69`, app 1.0.0 (1), engine v0.100.0, Android 10 /
API 29. Gate baseline: 689 tests / 74 suites, 0 failures.

---

## A. Store-release blockers (todos 1–3)

### A1. Live plaintext test key at repo root

- `opencode api key for testing.txt` (136 B) exists at the repo root; it is
  gitignored (`*key*.txt`) and `git log --all` shows it was **never
  committed**, so no history purge is needed. No code or build script reads
  it.
- Per `decisions-open.md` item 3 the key is **live**. Deletion alone is not
  remediation; the key must be revoked at the provider.
- OpenCode Zen keys are Console keys (`oc_sk_…`): Console → Account →
  Personal API keys. A revoked key returns HTTP 401 immediately; account-side
  state is visible without transmitting the key.
- **Runbook (owner-only):** create a replacement with expiry → paste it into
  Settings on the device → run one turn → revoke the old key → confirm 401
  (optional curl) → delete the file → hunt copies in backups/other machines.
- **Recommendation:** do it, XS effort, no code. Record closure with date +
  key prefix only, never the key.

### A2. Import storage strategy (All Files Access vs narrow SAF)

- Current flow: `MANAGE_EXTERNAL_STORAGE` is requested before the picker
  (`AndroidManifest.xml:26-28`, `ProjectsScreen.kt:135-160`). The SAF result is
  used only as a path selector: `FolderUriDecoder.kt:37-46` maps `primary:` to
  `/storage/emulated/0/…`; no `takePersistableUriPermission`/`DocumentFile`
  anywhere. The engine needs a real POSIX cwd (`ProjectRef.kt:30-34`).
- Play policy: AFA is restricted to file managers, backup, anti-virus,
  document management, search, encryption, migration + case-by-case
  exception. **“Developer tool” is not a listed category**; a declaration +
  demo video is required and applies to internal/closed testing too. The
  ledger's “may qualify” is not supported by the current text.
- Options: **A** keep AFA (S effort, approval/rejection risk, broad access) vs
  **B** narrow SAF + managed mirror into `filesDir/projects/<uuid>/repo`
  (M for import/copy + explicit sync; L for live bidirectional), with the F08
  access states surfaced at project open.
- **Recommendation:** Play flavor without AFA + **B-MVP** (persistable SAF
  grant, mirror, explicit “Sync to folder”, honest “imported copy” wording).
  Keep AFA in a sideload/dev flavor. B-full is a product change, not a
  permission tweak. Needs the owner's product call.

### A3. No-lockscreen fail-open (store build)

- `DeviceAuth.kt:52-62`: when no device credential exists,
  `createConfirmDeviceCredentialIntent()` returns null and the code calls
  `onResult(true)` — “allow without prompt”. `hasSecureLockScreen()` exists but
  has **no UI call site**.
- `AppLockPolicy.kt:64-65` opens with no ciphertext (fresh install);
  onboarding and Settings save keys without authentication. The Keystore
  master key is not auth-gated — fail-closed would be an **in-app UI gate**,
  not cryptographic protection.
- Play does **not** require an app lock; this is an internal store gate
  (decisions-open item 5).
- Smallest safe batch: distinct `NoCredential` outcome + terminal LockOverlay
  with a recovery path + one shared key-write guard used by onboarding and
  Settings + tests. A literal two-line flip is unsafe (bricks no-lock
  devices / credential removal).
- **Recommendation:** S (~1 day + device pass) when the store build is real;
  not now. Owner call.

---

## B. Engine rails 3/4/6 (todos 5–6)

### B1. Zero-tool wall (rail 3) and fresh advertisement (rail 4)

- Rail 3 cannot be a PASS/FAIL today: `ToolActivity.recordShape()` logs once
  per distinct shape (`ToolActivity.kt:236-253`), and the kit's
  “policy installed/verified” line does not exist (`CrushEngine.kt:293-316`
  logs only failures). The denial note fires only on a permission request
  (`ChatViewModel.kt:3933-3967`) — with all built-ins hidden there is none, so
  a note would indicate an MCP/unknown surface, not the wall.
- Rail 4 cannot be stronger than a model-prose probe: `GET …/agent` exposes no
  tool list; `CreateSession` does not rebuild the agent (`InitCoderAgent` does).
  A hard proof needs a fake OpenAI-compatible provider via
  `provider.base_url`, capturing the outbound `tools[]`.
- Plan (rail 3): force-stop for a fresh agent → `+ New chat` → prompt for
  `bash` and `view` → assert zero new `tool_call observed instance=chat`,
  zero `permission requested`, 35-id readback; negative control in a Code
  pool must show a tool call. Rail 4: fresh engine → “list callable tool
  names, answer NONE” → zero tool calls; stale names recorded as upstream
  fact.
- **Recommendation:** add a per-call log line + a content-free policy-success
  line, then extend `tools/engine-rails.ps1` with a `-ChatWallProbe` mode
  (guided, not unattended). Rail 4 stays OBSERVE unless a fake-provider
  harness is built.

### B2. `sub_agent_report` render (rail 6)

- v0.100.0 shape: a **user** message whose only part is
  `{"type":"sub_agent_report","data":{"label","output","failed","model"}}`
  (`proto/message.go:191-200,515-548`; `server/events.go:404-476`).
- The app drops it: live parsing only accepts assistant frames
  (`ChatViewModel.kt:3911-3927`); replay keeps user rows only with non-blank
  text (`:96-112`); there is no unknown-part-to-text path.
- **Recommendation:** retain non-text user parts + add a `SubAgentReport`
  accessor/renderer + synthetic fixture tests (CI-able); one optional real
  `agent_dispatch` run in a Code pool.

---

## C. Product research (todos 7–9, 11)

### C1. Sysadmin Mode (recommended: do not surface in v1)

- v0.100.0 has `normal|yolo|sysadmin` (`internal/proto/proto.go:17-25`);
  sysadmin skips prompts **and disarms the bash dangerous-command block list**
  (`internal/agent/tools/bash.go:326-331`). The API is
  `POST …/permissions/mode`.
- The app cannot emit it (`EngineProtocol.modeFor` maps two values;
  `PermissionMode` has two). Surfacing needs a third mode, a fail-safe parser,
  an engine-version gate, a three-state UI with a persistent warning, and
  mode readback; a persisted sysadmin must fail closed on rollback to 0.99.
- **DECIDED 2026-11-13 (owner): A — keep sysadmin unreachable in v1.**
  B (Code-only, typed confirmation, red state) only on concrete demand. High
  risk either way: no mechanical guard on bash and no undo for shell effects.

### C2. `CRUSH_PERMISSIONS_PIPED` (recommended: do not adopt)

- Its only upstream consumer suppresses the **TUI** permission dialog
  (`internal/ui/model/ui.go:5957-5963`). It does not change the permission
  service: the request still publishes over SSE and blocks on `respCh`.
- The app already is the answering client (ask cards + POST + 10-minute
  ceiling), and it launches `crush server` headless — there is no TUI dialog to
  suppress. Setting it would be a **no-op today** and a semantic trap if
  mistaken for auto-approval.
- **DECIDED 2026-11-13 (owner): A — do not set it.** Revisit only if an
  embedded TUI becomes a second prompt surface; then pair it with a watchdog
  proving asks still arrive.

### C3. Tool-call timeline (smallest honest version: S)

- `ToolActivity` has no timestamp/sequence; the engine republishes whole
  messages and the app replaces the list each frame, so tools from earlier
  steps disappear when a new assistant message starts.
- Smallest honest version: merge rows by `tool_call.id` **across assistant
  message ids** for the whole turn, keep first-seen order, stamp app-observed
  times, show elapsed + spinner. Do not interleave text (part order is creation
  order, not chronology) and do not claim engine timings.
- Full step-grouped timeline with text anchors = M, needs step identity.

### C4. Attachments / image input (staged, M)

- v0.100.0 supports `AgentMessage.Attachments` (`file_path`, `file_name`,
  `mime_type`, base64 `content`); the engine forwards images only when the
  selected model's `capabilities.vision` is true.
- App is text-only: no attachments in the DTO/send, no model in `ChatMessage`,
  replay drops user rows without text. An image-only prompt would vanish on
  reopen.
- Landmine: `EngineConfigInjector` writes top-level `supports_images: false`;
  v0.100.0 expects `capabilities.vision` — currently harmless, but a future
  injected vision model would be silently downgraded.
- Plan: 1) read/gate on `capabilities.vision` + fix injected specs; 2) Photo
  Picker → downscale/encode → send; 3) transcript fidelity (render/persist a
  preview, warn when the model cannot see it); 4) multi-image/retention.
- Do not ship attachment UI before stage 3.

---

## D. Explain-first items (no work started)

- **Remote crash reporting (12):** JVM-uncaught archive exists
  (`DiagnosticsRecorder.kt:46-125`, 3 entries/128 KB), exports via clipboard
  and `ACTION_SEND` only. No backend/endpoint/consent/outbox/symbolication.
  Adding remote upload is a product/privacy decision, not a wiring task.
- **Chat/Code backlog (16):** in-session toggle, Settings default-mode,
  smart-transfer/read-only allowlist are explicitly backlogged
  (`chat-code-modes-plan-2026-11-04.md:288-303`). Mode is derived from the
  engine binding, not a session attribute; the deny-all boundary is
  device-proven, and an allowlist would weaken it and need new ratchets.
- **Cross-provider verified-model persistence (17):** one global
  `selected_model` slot; every cold boot re-probes up to 24 candidates;
  switching provider clears it. A per-provider record (id, verdict, time,
  failure class, invalidation) is deferred pending API design.
- **Legacy history clear (18):** history is engine-owned SQLite; only
  per-session DELETE exists. A bulk clear would touch sessions/messages, the
  runtime review store, handoff rows referencing deleted chats, and must stop
  the engine first. Q4 chose to keep history and forbade a silent wipe; a
  disclosed “delete chats” feature remains unbuilt.
- **ChatViewModel extraction (25):** the file is **4,284 lines** (not ~3k).
  Candidates: RunCoordinator (`send`/`streamSend`/`awaitStream`/stop, highest
  payoff/risk), HandoffController (~879 lines), ChatBootstrap/ModelDiscovery
  (~490 lines). Shared mutable state is the hazard; no integration-level
  coordinator test yet.
- **Standing risks (26):** A07 is partial (string-path dedupe only, no
  canonicalisation/unique index; aliases can twin rows); crash-atomic removal
  is ordered but unjournaled (startup reconciliation missing); snapshot
  honesty is implemented with one silent residual (`fileChanges == null` shows
  nothing).
- **Stale tool advertisement (27):** engine-side and benign — the execution
  wall filters by exact name, zero `tool_call` events from stale ids; only
  wasted prose. The v0.100.0 fresh probe is rail 4.

---

## E. Closed/verified in this pass

- **#30 device cleanup:** removed orphan engine dirs `files/crush-pa`,
  `files/crush-pb`, `files/probediag` (no project row references them; only
  `crush`, `crush-chat`, `crush-p077dc19e`, `crush-pe26670d9` remain).
- **#29 fixture cleanup:** removed the 98 B `crushrc` sentinel and
  `.crushrc-ran.marker` from `/storage/emulated/0/Test project folder`. The
  project's stored consent is now stale by design → next open re-gates as
  removed-since-consent.
- **#19 diagnostics (device):** screen opens from Settings → “Report a
  problem”; status shows app 1.0.0, API 29, engine installed/running/responding
  (`GET /v1/version` 200); Copy verified by pasting the report; Share opens
  the system chooser. Cosmetic: UDS mode renders `pid -1, port 1`.
- **#20 permission modes (device, v0.100.0):** ASK is the baseline
  (`permissionWire=normal`). AUTO-APPROVE produced
  `permissionMode=AUTO_APPROVE` + `open: permissionWire=yolo … yolo=true`; a
  read-only `ls` turn ran one completed bash call with **zero permission
  requests** and returned `go-promotion.txt, notes.md, todo.md`; setting
  restored to ASK and re-verified (`permissionWire=normal`, `yolo=false`).
- **#28 upstream filings:** not a problem — no blocking impact; issue #4222 is
  filed and the drafts stay available. Closed as “no action”.
- **#21 first-launch app lock bypass:** by design, not a defect. The gate
  opens only when no ciphertext exists (fresh install) and Onboarding prompts
  when it creates the key; later cold starts prompt. The store-level
  fail-closed batch is item 3 / §A3. Recorded in `decisions-open.md` §5.
- **S-01 break/recover (device, 2026-11-13):** malformed 1-byte
  `files/crush-chat/data/crush.json` made the chat pool fail closed — four
  supervisor refusals (first after 33 ms), permanent `Failed`, honest Failed
  badge, zero chat children, file never rewritten. Restoring the 2137 B
  backup cold-booted `chat tool policy installed and readback-verified (35
  ids denied)` and `[chat] engine Running after 25009ms` with one child.
- **Second imported project row (device, 2026-11-13):** `Test project folder
  2` (`pd871eee8`) imported via SAF beside `Test project folder`
  (`pe26670d9`). Three-way switch folder2 -> folder1 -> folder2 -> legacy
  logged stop-on-switch on every hop, distinct cwds/state DBs/sockets,
  fresh-subscribe restarts of the same key (2880 ms), preserved per-project
  history with no bleed, a real turn on the new row (`OK`) and the older
  row's history intact, and never more than one
  live non-legacy child (legacy + one project = 2 total, inside the
  non-legacy budget; the refusal path remains JVM-covered).
- **#6 real `sub_agent_report` (device, 2026-11-14):** an `agent_dispatch`
  in folder 2 spawned `pepper-subagent-ok` (child answered `SUBAGENT-OK`);
  the engine stored the user-role `sub_agent_report` part and the parent
  replied `DONE`. Reopening the session rendered the dedicated card
  correctly (label, model, monospace output) — replay retention proven.
  **Live append gap found:** the report arrived ~6 s after dispatch but
  the open transcript stayed on "waiting for its report" until the
  session was reopened; the turn stream closes at `end_turn`, and only
  session (re)open re-reads messages. Follow-up: consume engine-initiated
  wake runs or bounded post-dispatch re-fetch.
