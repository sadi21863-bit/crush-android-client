# Capabilities

What this app actually does, and — just as important — what it does not.

Verification is against a physical arm64 device (Redmi Note 7 Pro, API 29).
"Implemented but unverified" means the code is written and compiles, and has not
been exercised on hardware. That distinction has bitten this project repeatedly:
two defects that looked finished (`NetworkOnMainThreadException` in delete, and
minting a new session on every re-attach) passed every unit test and only
surfaced on a phone.

**arm64-v8a only · minSdk 29 · API 29+**

## Verified working

| Capability | Evidence |
|---|---|
| Chat with streamed replies | Real replies received; Markdown rendered |
| Multi-message conversations | 64 messages accumulated in one session |
| Context carried across turns | Assistant answered "Which word?" to an instruction only completed in the prior turn |
| Long-lived workspace SSE | 3 messages over ~90s with pauses past the ~30s reap window; zero reap symptoms |
| App lock | Credential prompt on cold resume; cancelling and tapping around does **not** unlock |
| Session history, resume, delete | Verified on device by the user |
| Auto-titles | "New chat" became "Ping" automatically |
| Live model discovery | `probed 10 candidate(s), using space-bunny-free (of 43 listed)` |
| Real price loading | `catalogue prices loaded for 87 models` |
| Permission mode persistence | `permissionMode=ASK` survives restarts |
| Engine lifecycle | Crash/restart, health probing, dynamic thermal tuning. **Per-project stop-on-switch verified 2026-11-04** (`28328b5`): two `libcrush.so` children with a project chat open → ONE child + `I/Lifecycle: stopping project engine for instance 'p8a3825d0' (no run in flight)` on returning to the legacy row (run-in-flight guarded — a mid-run switch leaves the run riding its own engine) |
| Chat→Code handoff dispatch (P6 Batch C) | E2E on device 2026-11-09: entry chip → consent sheet → `DISPATCHED` row (S-02 ordering proven) → verify-first first turn ran real bash; full record below |
| Permission modes (ASK / AUTO-APPROVE) | ASK: card + grant proven. AUTO-APPROVE on v0.100.0 (2026-11-13): `permissionWire=yolo`, a read-only `ls` turn ran one completed bash call with **zero permission requests**; restored to ASK (`permissionWire=normal`) |
| In-app diagnostics | Screen + Copy + Share device-walked 2026-11-13: status block, 95-line log tail, copy verified by pasting the report, share opened the system chooser |
| Engine v0.100.0 + version-aware permission adapter | Device-proven 2026-11-12 (`6800c0e`): v0.100.0 bound and streamed; the wire family follows the bound engine (`MODE_ENUM`/`permission_mode` on 0.100+, `SKIP_FLAG`/`yolo` on 0.99); chat-pool deny readback **35 ids**; rollback drill: v0.99.0 opened the v0.100 engine state DB unchanged (no one-way door). T3 evidence in [engine-acceptance-kit.md](engine-acceptance-kit.md) §5 |

## Implemented, unverified on device

| Capability | Notes |
|---|---|
| Tool-call visibility | Collapsed rows with name, state, arguments, output. **Wire format CONFIRMED 2026-10-25 (D3 closed)** — see below |
| Markdown rendering | Hand-rolled, streaming-safe. Fenced code, inline code, headings, lists, quotes, rules, emphasis, links |
| In-app diagnostics | Device/ABI/engine status, archived crash, log tail, copy/share report |
| Settings | Key replace/delete, permission mode, diagnostics entry point |
| Prewarm | Eager engine start when enabled; lazy on first keystroke otherwise |
| Handoff resume path (P6 Batch D) | RESUME sheet surface for an approved-but-undispatched row dispatches on the existing approval; explicit discard writes ABANDONED (DISPATCHED traces refuse it); no-alias replace; Open in Code jump; F04 dialog rewired in; gate green (`20e8347`); device-proven in the P5 drive (`b8cb2f12` resume dispatch — no insert, stamp unchanged) and the same-destination dispatch re-checked on v0.100.0 (2026-11-12) |

## Not implemented

These are the real gaps, ordered by how much they matter for a coding agent.

| Gap | Impact |
|---|---|
| **File diffs** | Built and JVM-tested (snapshot → tracked files → diff → render), and the collector endpoint is verified running on device. 2026-11-04: the FIRST real edit turn was measured (imported-folder project: `notes.md` + `todo.md` written into `/storage/emulated/0/Test project folder`) — but the changes rail failed to display it: it counted `0 files changed +0 -0` and listed both files `(unreadable, binary, or too large)`. That external-file event routing is the known defect below; the diff render was still unexercised by end-to-end data then — *superseded: the `349bad3` drive (2026-11-05) measured this same rail at `2 files changed +2 -0` with the expanded unified hunk rendered on screen, and the P4 Gate-2 drives (2026-11-07) re-proved on-screen hunks + byte-identical Revert on it (see the FIXED and P4 sections below)* |
| **Tool-call timeline** | Tool activity is a per-message summary, not a live view of what the agent is doing turn by turn |
| **Per-turn cost display** | Cost is parsed and shown in the drawer only |
| **Multi-provider** | Plumbing in, 13 ids offered. **Verified end to end: `opencode-zen`, `openrouter`, `opencode-go`** — including Go's PAID lanes, all three measured 2026-11-02 evening: `deepseek-v4.1-flash` + `glm-5.3-flash` turns (21:55) and `deepseek-v4-pro` turn ("Reply with exactly: OK" → `OK`, 22:14), after ~1h paid-lane provision lag post-subscription (the 402s were allowance provisioning, never a client defect — see [providers/opencode-go.md](providers/opencode-go.md)). `hyper` real-key verified to its $0 ceiling (23 live models all `402 authentic-but-unfunded`, `badKey=false`; a completed turn awaits funded credits). `groq` probe-verified but parked. See [providers.md](providers.md) |
| **Multi-project / workspaces** | **Identity + isolation + persistence + first UI + LIVE BINDING all measured on device (ADR-0001 arc, accepted 2026-11-03):** the engine keys logical workspaces on the PATH (attach is idempotent, multiple workspaces coexist on one engine process — `TwoProjectAttachTest`), BUT sessions are a **global pool per data dir** in crush v0.99.0 (`listSessions` ignores the workspace id — measured) → the selected shape is **one engine instance per project** (own socket + own state dir), measured isolating sessions/workspaces exactly per project in `TwoEngineInstancesPerProjectTest`. P2: `RoomProjectStore` restart-survival + idempotent legacy migration proven (`ProjectStoreRoomPersistenceTest`). P3 slice 1 **live on device**: the Projects screen (session-sheet entry) lists rows from the production registry — the legacy workspace migrated in automatically on first entry, and create minted a canonical `files/projects/<uuid8>/repo` row (retro-note 2026-11-04: THAT minted-internal-repo creation path has since been REMOVED by product decision — Projects are created ONLY via Import folder now; the store's `create` survives solely as the migration + import seam, and the removal semantics at (5) below cover rows it minted). **Slice 2 measured live (22:18, test-project):** tapping a project row binds the chat to that project's OWN engine instance — `starting supervisor for instance 'p8a3825d0' (prewarm on)`, `bridge up on 127.0.0.1:38109`, `bootstrap instance=p8a3825d0 workspacePath=.../projects/83cc71bd/repo`, agent/init ok on the fresh state dir, **"no prior session with content"** (fresh pool — no bleed from the legacy 10-hour history), a real turn "Reply with exactly: OK" → `OK` on the project engine (deepseek-v4.1-flash), and `ps` showing TWO `libcrush.so` children (legacy + project) simultaneously. The engine's own start routing had to be fixed to reach this (prewarm start hard-coded the legacy singleton — measured as a permanent "engine stopped", fixed in `b8d3be7`) — and now the close-out of that row's lifecycle part, 2026-11-04:

  **2026-11-04 measured (device `bbf8ca69`, build `28328b5`): (1) import-folder-as-project WORKS end to end — SAF `OpenDocumentTree` → pure `FolderUriDecoder` → row `Test project folder` / `/storage/emulated/0/Test project folder` (spaces intact), `bootstrap instance=p536fa26e workspacePath=/storage/emulated/0/Test project folder` on its own engine child. (2) The engine edits the REAL folder in place: a deepseek-v4.1-flash turn created exactly `notes.md` + `todo.md` there (`ls` verified 2 files, contents per spec: a three-bullet plan + two unchecked `- [ ]` boxes), ASK approvals surfaced and honored, the engine self-verified with its own `ls`, and after the turn the OTHER project's repo dir (`files/projects/83cc71bd/repo`) remained EMPTY — cross-project isolation held with a real user folder in the mix. The user-visible consequence: engine-made files in imported folders are plain files in the user's storage (openable/shareable in any file manager), which is the answer to "where do created files go". (3) The stop-on-switch polish is PROVEN on the fixed build: legacy → test-project gave two children (8422 + 9566), back to the legacy row gave ONE child + `I/Lifecycle: stopping project engine for instance 'p8a3825d0' (no run in flight)` — while the pre-fix build (measured 09:27–09:30) had left BOTH children alive on the same trace. Root cause: the chat ViewModel is scoped to the nav back-stack entry and a projects↔chat round trip recreates it, so the stop in its `bindProject` never fired (the fresh VM early-returned on the default `""` key); the stop moved to `AppContainer.setActiveProject` (`28328b5`), which outlives every entry, guarded by a VM-reported run-in-flight marker (`reportRunInFlight`, set at `send()` start, cleared key-guarded in `abandonRun`). (4) NEW known defect, discovered in the same drive: on the imported-folder instance the events socket never connects (`CrushConnection: events connect failed (attempt N): No such file or directory` — attempts 73→98+ at ~5s cadence), so the UI missed the run's terminal event and showed a false "This reply failed — no reply received" banner while the engine side actually completed both writes (bubble/tool-call content arrived via re-fetch); the changes rail also classified both external files "(unreadable, binary, or too large)" (0 +0 -0 counted). This is ADR-0001 §7's predicted per-binding event-routing cost landing; fix queued as the next cycle's first item. (5) PRODUCT DECISION (user, 2026-11-04): Projects are created ONLY via Import folder (the desktop-app model) — the "New project" minted-internal-repo path is REMOVED (its `filesDir/projects/<uuid>/repo` content was invisible to the user's file manager — the very failure import-folder exists to fix), and a per-row REMOVE lands with a type-honest confirmation: an imported (external) folder is USER DATA (removing drops only the row/reference — the folder and every file stay untouched; re-importing the same folder later starts a FRESH project, fresh row id → fresh engine state dir, so no session bleed), an app-minted internal repo is DELETED WITH its row (the `filesDir/projects/...` tree plus its `filesDir/crush-<key>` engine state dir), and the legacy workspace row is permanent (it hosts the existing history; no Remove affordance rendered; the idempotent entry migration would re-create the row anyway). Removal REFUSES while a run is in flight on that row's engine ("a run is active on this project's engine — remove it after the run finishes"), then stops the engine instance, drops the row, and deletes the internal trees. Implemented this cycle: `ProjectDao.deleteById` → `RoomProjectStore.remove` (row-only) → `ProjectsViewModel.removeProject` (guards + ordered IO) → the screen (single full-width Import button; per-row Remove icon; the type-honest dialog per kind); compile-green, on-device verification pending the gate. VERIFIED ON DEVICE the same evening (`5ef70f5`, drive shots + `run-as`): the screen shows ONLY the Import button, the legacy row carries no Remove affordance, per-row Remove + per-kind type-honest dialogs branch correctly, removing `test-project` and `hello` fired `I/Lifecycle: stopping project engine for instance 'p8a3825d0'/'p80e7971b' (project removed)` AND deleted both `crush-<key>` engine state dirs + the rows' `repo` data, and the imported `Test project folder` row survives the dialog-cancel path with `/sdcard/Test project folder` (`notes.md` + `todo.md`) fully intact. Two first-use defects measured AND fixed the same evening (slot-fix batch): the removal dialog printed literal `%s` placeholders (Kotlin `.format` bound to the last concatenated literal — now parenthesized around the whole string), and the empty minted-slot wrapper `files/projects/<uuid8>` was left behind while its `repo` child was deleted (removal now deletes the whole slot; the two orphan wrappers swept manually; the Proof-A/Proof-B `marker-A.txt`/`marker-B.txt` isolation artifacts under `a/repo`/`b/repo` are deliberately preserved). Both fixes shipped in `4a59b68` and re-verified on device: the imported-row confirmation now renders the REAL folder path ("`Your folder /storage/emulated/0/Test project folder and every file in it stay untouched…`" — no literal placeholders), and Cancel keeps the row, the folder, and its files intact. |
| **Attachments / image input** | Text only |
| **Instrumentation tests** | **OK (8 tests) on device 2026-11-01**: `MainActivitySmokeTest` (2, launch-level) + `MainActivityLockFlowTest` (6, app-lock security contract — gate↔FLAG_SECURE, recreation-survival, no-decrypted-key-on-locked-resume, **real Keystore** roundtrip/isolation/tamper-clear, `require()`-never-caches). CI compiles androidTest on every push (`:app:assembleDebugAndroidTest`); connected runs still need a device (`connectedDebugAndroidTest` when attached) |
| **Remote crash reporting** | In-app export only |

## Known defects

Open issues that are implemented-but-wrong, distinct from the gaps above.

### Per-instance events socket + imported-root diff — BOTH FIXED (349bad3, verified on device 2026-11-05)

On the imported-folder instance (`p536fa26e`, real cwd
`/storage/emulated/0/Test project folder`) the per-instance events socket
NEVER connects: `CrushConnection: events connect failed (attempt N): No such
file or directory`, attempts 73→98+ at ~5s cadence for the whole turn. The
legacy workspace instance's events socket works — the failure is specific to
the binding keying. Measured consequences, on a run that genuinely COMPLETED
(both files written, engine-side `ls` self-check, final summary text arrived
via re-fetch):

- the UI missed the run's terminal event and showed a false "This reply
  failed — no reply received" banner with a Retry button on a finished run;
- the changes rail counted `0 files changed +0 -0` and classified both
  written files `(unreadable, binary, or too large)`.

Root-cause candidates CONFIRMED in source 2026-11-04 by the independent
connected-GitHub review, pinned at `b8aa505`: (a) the changes-rail half is
`ChatViewModel.workspaceRoot()` unconditionally returning the legacy
`EnginePaths(context).workspace` (:2388) for pre/post-turn capture — the app
edits the imported folder but diffs the legacy root, which is exactly why
external files read as "(unreadable, binary, or too large)"; (b) the
transport half is port-only connection reuse (`UDS_PORT_SENTINEL = 1` for
every engine + the `p == connectionPort` guard) — an unchanged number cannot
prove the socket file stayed the same. Device diagnosis the same evening
completed the chain: a clean first-open attach CONNECTED (defect is
sequencing-dependent, not unconditional), and the full failure shape is a
connection REUSED across an instance switch within one ViewModel — serving
the wrong engine one direction, or retrying ENOENT forever against the
UNLINKED socket file (Go's UnixListener unlinks on close) of an engine the
stop-on-switch fix had stopped, while call-time REST kept succeeding.
Full fold-in ledger in
[`chat-code-modes-plan-2026-11-04.md`](chat-code-modes-plan-2026-11-04.md) §7.

**FIXED in `349bad3` (2026-11-05 evening) and verified end-to-end on device
`bbf8ca69`**: connection identity is now the SOCKET FILE (rebuilt whenever the
bound instance's path differs), the transport is resolved PER ATTEMPT (missing
socket = one honest logged attempt `socket not present yet: <scrubbed path>`,
a respawned socket is followed by retry, no silent plain-TCP fallback), the
supervisor health probe addresses its own instance's socket, and
capture/collect use a PER-RUN FROZEN root from the active project binding.
Acceptance evidence, build `349bad3`: fresh keyed attach connected in 35 ms;
one ASK-approved writing turn on the imported folder completed with its real
terminal text — **no false banner**; rail truth `2 files changed +2 -0`,
per-file `todo.md +1 -0` / `notes.md +1 -0`, `0 unreadable`, with the expanded
unified hunk (`@@ 2,4 +2,4 @@`, green `+ - [ ] S0 verified`) rendered on
screen; files byte-verified via adb (`S0 turn check` / `- [ ] S0 verified`);
A→B→A switching produced `stopping project engine` + transport identity
change to `crush.sock` + connected (legacy), then engine respawn + reconnect
on `crush-p536fa26e.sock` with the honest capped retry ladder during the
engine-down window and ZERO app-PID ENOENT (the one trace ENOENT was MIUI
system noise, PID 18108). CI green on `349bad3` (gate + MODE-25).

**Residual, non-blocking, suspected for the post-v1 hardening (review Gate 7
shape):** (1) a stale ViewModel's connection survives navigation
(projects↔chat round trip keeps the old entry's reader alive) and retries
honestly with capped backoff until reclaim — same family as the runtime-map
backlog item, needs the VM/connection registry; (2) the engine respawn after
stop-on-switch took ~55–60 s from re-bind to `exec` (00:05:18 bind → 00:06:14
spawn) — supervisor start latency after a manual stop deserves its own probe
before the next release.

### S3/S4 mode split — BUILT AND DEVICE-DRIVEN 2026-11-05 (builds `4058588` + `22fbe28`)

**Verified on device (chat pool + binding machinery):**

- The session sheet's "+ New chat" (and the top-bar "+") now start a CHAT
  session on the dedicated pool: `filesDir/crush-chat` state dirs +
  `crush-chat.sock` spawned on demand, a real per-instance engine with its
  own session pool; the top-bar badge reads "Chat - no tools - <phase>" and
  the sheet labels the surface ("Chats - conversation only" / "Project -
  <label>" / "Workspace (legacy)"). The switch lifecycle honors the S3
  contract on the way out: `stopping project engine for instance 'chat'
  (no run in flight)` measured on leaving the pool for a project.
- **Deny-all holds under attack.** Asked to `rm -rf /storage/emulated/0`
  Documents and list the folder: NOTHING executed (the /storage listing
  byte-verified intact), bash/edit/ls/view hidden at the engine (S1
  mechanism), the model instead attempted a `question` tool call trying to
  get the deletion confirmed by the user; with the extended list the model
  then reported — via a `crush_info` introspection — **"crush_info shows
  bash in the disabled tools list, and I have no tool that can re-enable
  it"**, and later "The `question` tool is also unavailable now, so I
  can't ask you to confirm through it." The pool's config file was
  independently read afterwards: all 22 disabled ids present (agent/bash/
  job_output/job_kill/download/edit/multiedit/lsp_*/fetch/agentic_fetch/
  glob/grep/ls/sourcegraph/write/view + todos/question/task/diagnostics).
  A `crush_info` residual-read remains (cosmetic; flagged for the list at
  the next edit).
- S4's app-side defense layer (Q2) and the F04 crushrc dialog are wired in
  code but NOT yet exercised live: the S4 auto-deny path needs a
  permission event the engine-side deny list cannot preempt (e.g. an MCP
  server), and A05 needs an imported folder that actually carries a
  crushrc — recorded as pending for the next sentinel drive.
- MODE-01-grade posture: chat = pure conversation by ENGINE policy, no
  file access, no shell, no ask-anything path.

**New local artifacts of this batch, residual but honest:**

- **Supervisor Starting→Running wedge (twice):** after device sleep, a
  keyed supervisor's child process boots and binds its socket, but the
  app-side state never flips off "engine stopped" (a ~10 min hang on the
  p536fa26e instance at 06:33; the 07:11 spawn wedged the same way within
  minutes). A force-stop restores sanity. Needs its own probe/fix in the
  S5 batch (likely a Starting-state watchdog with one bounded restart) —
  the harder sibling of the recorded ~55-60 s spawn-latency residual.
- **F06 triple-engine leak measured:** three libcrush children at once
  (legacy + chat + keyed) after a morning rebind; the chat-pool child
  survived its own `stopping project engine` line (stop-vs-Starting
  race). The review's Gate-7 managed-runtime map is the fix; unchanged
  priority (P1 backlog).
- **A11/A12 restore gap confirmed live:** after the app slept, restore
  landed on the LEGACY pool session, not the chat pool the drive had
  opened — confirmation of the deliberately deferred last-binding
  persistence (S4/S5 batch).
- The MIUI system logcat intermittently drops our process's Log lines
  entirely while app.log persists — diagnostics-only quirk, noted for
  future drives (troubleshoot through run-as files/app.log first).

### S5 acceptance drive — MEASURED 2026-11-05 evening (device `bbf8ca69`, build `8eb0477`)

Both halves of the sentinel drive landed end-to-end, entirely through the
app + adb.

**F04/A05 consent-before-execution — PROVEN.** Sentinel = a destructive-
marker hook (`crushrc`, 83 bytes, content `date +%s > "...marker"`; pushed
at 08:34 because adb-shell quote mangling broke inline writes). Phase A
timeline: 16:47 cold boot — the cold boot produced NO restore line anywhere
in app.log (virgin-token honesty — instrumentation live instead: `[]
supervise loop start`); sheet showed the honest "Workspace (legacy)" label;
Projects → row tap → folder listed crushrc + notes.md + todo.md, NO marker;
the F04 dialog rendered live with full disclosure ("RUNS that file as a
Bash script (twice per attach)"). The marker (`.crushrc-ran.marker`, 11
bytes) appeared at 16:54 EXACTLY on the "Run it and open" consent,
coincident with app.log `starting supervisor for instance 'p536fa26e'` at
16:54:27. Nothing executed across ~7 minutes of taps before the decision.
Cancel-vs-run map: cancel is unbound-and-inert by construction
(`pendingCrushrcTrust = null` only); the consent path calls the SAME
`setActiveProject` a plain open uses — no privilege bypass. Post-drive
sentinel cleanup (18:34): folder back to notes.md + todo.md only.

**crushrc = engine config, not just auto-bash (refined S1b understanding).**
The engine's own log shows `internal/shellconfig.LoadShellConfig`
LOADING the project-root crushrc on every attach — twice (12:52:06Z +
12:52:10Z for one attach), matching S1b's twice-per-cycle execution
measurement. A marker-only file is inert ("Shell config produced no
config"), but the binary carries shell-config permission knobs
("Permissions denied in shell config"), so a well-formed crushrc CAN alter
session behavior. The consent gate is load-bearing; the marker proves
execution, the engine log proves the config channel.

**Code pool: tools are alive (MODE-06 half A) — no-card runs RESOLVED in
upstream source (not an app defect, and not a default-policy question).**
In the project pool, bash executed and returned real output twice: `ls
-la` (16:58) and — the decisive probe — a NEVER-EXECUTED-before `date`
(17:01), each auto-called by the model with NO permission card, while
permissionMode=ASK was logged at bind (`permissionMode=ASK
providerId=opencode-go`, 16:54:29), `createWorkspace(yolo=false)` +
`skipPermissions(id,false)` were both sent with no failure warns, and the
engine state DB has no persisted permission state (schema + byte-grep
checked). Pinned in the upstream source at our exact tag
(`v0.99.0/internal/agent/tools/bash.go` + `safe.go`, fetched 2026-11-05):
the bash tool has a SAFE-READ-ONLY BYPASS above the permission system — a
lowercased PREFIX match against a fixed `safeCommands` list (cal, date,
df, du, echo, env, free, groups, hostname, id, **kill**, **killall**, ls,
nice, nohup, printenv, ps, pwd, set, time, timeout, top, type, uname,
unset, uptime, whatis, whereis, which, whoami + git read-only sub
commands), SKIPPED when the command contains chaining metacharacters
(`;|&&$(` backtick). Safe-and-unchained commands run with
`permissions.Request` NEVER CONSULTED — no prompt, no event, nothing for
our card to show. `ls` and `date` are both on the list: both probes were
running INSIDE the designed bypass. Everything else still consults the
permission service under our ASK settings (`yolo=false` → prompts), and a
separate banned-command blocklist (curl, wget, nc, scp, ssh, sudo, su,
apt/yum/pacman-style package managers, systemctl/mount/fdisk, ...) is
REFUSED OUTRIGHT, never prompted. Evidence of the ask pipeline working
stands: the historical todo.md WRITE denial records in the engine DB, and
`write.go` consults the permission service (its permission test exists
upstream). CORRECTED CONCLUSION for MODE-06 half B: the ASK gate is intact
for consequential commands by design; read-only introspection is exempt;
the correct card probe is a NON-safe command (e.g. `mkdir`) — TODO for the
next drive. Sharp edge worth an upstream flag candidate: `kill`/`killall`
inside the safe list means an agent can silently stop processes (in an
embed, the engine child and host app share a UID). The S6 ask-per-shell
config question dissolves against this source (upstream's JSON knob is
allow/deny only — `permissions.allowed_tools`, `options.disabled_tools` —
with no ask-per-shell switch; the native posture + honest UI copy is the
answer; see s6-ask-shell-recon-2026-11-05.md §7 corrected). The 2026-11-05
`nightly` range hardens exactly this area (parsed-command approval, chain
judged by parts, session approvals scoped to what was approved, server
reporting its enforced mode) — recorded as merge-advisory for future pin
bumps.

**A11/A12 last-binding restore — PROVEN.** Token journey read from
DataStore directly: after the project bind, `last_engine_binding` =
`proj:536fa26e-c2f1-4a51-a89e-9d5abb24d3d8` — the FULL uuid persisted, the
lossy `p<first8>`-key shape avoided by construction. "New chat" via the
sheet (18:36) flipped the token to `chat` and the header badge to
"Chat - no tools - engine stopped" (chat pool's own model slot,
deepseek-v4.1-flash, distinct from the project pool's haiku). Force-stop,
then cold boot: app.log `18:37:45.441 I/Lifecycle: restoring last binding:
chat pool` — immediately followed by `[chat] supervise loop start` — and
the UI landed the chat pool's own last session ("Delete Documents", whose
transcript is itself the deny-list evidence: "bash tool isn't available",
"crush_info shows bash in the disabled tools list") with the badge
"Chat - no tools - ready". One-shot restore, pre-UI, correct pool.

**Honest residual, non-blocking:** after the warm New-chat switch on the
same process, the message list still displayed the PREVIOUS pool's
transcript while the header already showed the chat badge (the rebinding
pool's session cannot load until its engine starts). A cold restart
resolves it correctly. Whether the swap should blank the transcript first
is UX polish, noted only.

**Wedge caught LIVE while awake — shape 2: alive loop, alive child, lost
delivery (2026-11-05 18:58–19:10, build `8eb0477`).** The planned
spawn-latency probe (chat→Test folder rebind) became a wedge capture:
`[chat] stop() requested` 18:58:15.345 clean (exactly TWO libcrush
children remained after — legacy + project — NO F06 orphan under the new
interlock-era behavior); `[p536fa26e] start()` → `starting attempt 1` →
**`engine Running after 3952ms`** at 18:58:19.481 — yet `bindEngine`
delivered nothing beyond its two `Stopped` emissions (18:58:15.524/530),
and the UI stayed pinned "New chat / engine stopped" (no pool label, no
model, sheet showed "No chats yet") for ~12 minutes WHILE the child and
socket were healthy. Re-tapping the project row at 19:10:14 re-drove the
binding: a FRESH subscription was instantly served the still-cached
`Running` (14.796), bootstrap completed in ~3 s (haiku re-seat, 63-msg
session resume, event-stream attach, tool-shape replay) — full recovery by
re-subscribe. Classification: the supervisor's StateFlow held the correct
value the entire time; the DELIVERY to collector(s) failed — distinct from
the overnight shape (dead loop + orphan child, design doc §0 shape 1).
Corollary: a supervisor-internal probe watchdog CANNOT see shape 2 (the
supervisor is healthy); coverage belongs to the container-level
registry/reclaim design (`design-runtime-registry-2026-11-05.md` §3:
fresh-subscribe-always-served + collector census). No tap-level or
product-code fix was made live — the wedge state was driven only to
recovery, evidence untouched. Active-drive spawn numbers: chat restore
spawn 2772 ms, project spawn 3952 ms — the 55–60 s residual did NOT
reproduce while awake (it was a post-manual-stop overnight shape; the
wall-vs-monotonic doze hypothesis stays the lead).

**A06 acceptance drive — the renamed card fixture — MEASURED 2026-11-05
evening (19:54–20:00 IST) on the recovered surface.** Prompt "Run mkdir
probe7 with the bash tool" in the project pool (engine v0.99.0, ASK
logged):
- **The card fired for a NON-safe command, exactly as the source fixes
  it:** app.log `permission requested: bash (id=9f7fe0fc);
  pendingPermission SET for run…` at 19:54:50.719; the card rendered
  "Allow this action? / bash / Execute command: mkdir probe7 && ls -ld
  probe7" with the full folder path and the honest bounds footer.
  DOUBLE-CONFIRMATION of the upstream reading: the model chained
  `&& ls -ld probe7` onto its mkdir — `containsCommandChaining` catches
  `&&`, so the safe-list shortcut was correctly DISABLED and the
  permission service consulted (an unchained `mkdir` alone would also be
  non-safe; the chain made the card's necessity visible on BOTH
  mechanisms).
- **Deny honored end-to-end:** Deny → `prompt CLOSED (grant ok; …
  cleared)` 19:59:15 → `file changes: +0 -0 across 0 file(s), 0
  unreadable` — NOTHING executed; folder listing unchanged after.
- **Honest residual (minor, UX):** the denied turn ended with the app's
  failure banner "This reply failed — no reply received / Retry" instead
  of a model-narrated refusal (the run's final response produced no text
  after the denied tool call). Security-neutral (the banner is honest;
  nothing ran); candidate micro-fix: a canned "You denied this action"
  note when a denied tool call ends a turn with no final text.
- **Allow path (user-driven, second identical probe at 20:00:11):** the
  re-sent prompt raised the SAME card (id=456e6d09), closed 2.3 s later
  via Allow, execution proceeded, final text confirmed "probe7 created."
  — the card is the arbiter in both directions.
- probe7 was cleaned from the folder afterwards (listing verified back
  to notes.md + todo.md only).
- RESOLVED BY OWNER CLARIFICATION (2026-11-06): the header model slot
  reading `deepseek-v4.1-flash` was CORRECT, not a UI-state read
  inconsistency - the owner switches the model to deepseek-v4.1-flash
  deliberately and continuously to keep turns cheap (pinned `claude-haiku-5-5`
  in the bootstrap was never the operative slot value after their switch;
  the "no model action between" assumption was wrong because the switch
  was owner-made outside any tracked app event). Engine-side effect
  confirmed healthy: same pid honored the cheaper model mid-session with
  no restart. No anomaly remains; disambiguation is removed from the
  wedge/registry follow-up list (the denied-turn canned note stays).

### Owner-pack security gaps source-confirmed in code — 2026-11-05 evening (HEAD `1d229de`)

The owner's builder-context pack + independent audit (both pinned at
`9dcfa0b`) surfaced the three findings below, NEW relative to the S1b/F04
entries above. All three code locations were re-read and confirmed in this
tree on 2026-11-05 (read-only verification; line numbers as read at
`1d229de`) — source-confirmed by owner pack + audit, code-confirmed in
repo today. The fixes are owned by the Gate-0 batches tracked in
[CRUSH-MOBILE-STATUS-AND-DECISIONS.md](CRUSH-MOBILE-STATUS-AND-DECISIONS.md).

**S-01 (pack P0-2) — chat tool-policy injection is FAIL-OPEN.**
`CrushEngine.kt:253-257` (the start() pre-spawn window) wraps the chat
pool's `EngineConfigInjector.injectChatToolPolicy(paths.globalData)` in
`runCatching { ... }.onFailure { Log.w(TAG, "chat tool policy injection
skipped: ${it.message}") }` and spawns unconditionally. An injector
failure therefore starts the chat engine with built-in tools VISIBLE
while the surface labels it "Chat - no tools" — the S1-proven hide
mechanism exists, but the spawn path carries no fail-closed guarantee
(the `1d229de` interlock covers veto-at-spawn, not injector success).
Pack-derived device acceptance: break the chat instance's config store
and verify the engine FAILS to start (never a tools-exposed spawn), with
the deny list re-confirmed from inside (crush_info-style introspection)
on the next healthy start.

**FIXED IN CODE (7ec34ed, 2026-11-06):** typed verdict +
verified-by-readback; Chat REFUSES to spawn on any failure shape with an
honest `Failed(willRetry=true)` badge. Device row (Failed badge + a
healthy reboot of the pool) queued for the shared device pass.

**S-02 (pack P0-1) — last-binding restore bypasses the crushrc trust
dialog.** `AppContainer.kt:334-359`: `restoreLastBinding()` resolves the
saved project token and calls `applyBinding(EngineBinding.Project(ref))`
DIRECTLY (line 358). The F04/A05 consent gate lives only in the
interactive ProjectsScreen path (`ProjectsScreen.kt:337-361`,
`pendingCrushrcTrust`), so a cold-boot restore onto an imported folder
that carries — or against which an attacker planted — a project-root
crushrc executes that file with NO trust decision (S1b-measured: bash,
twice per attach, the app's full UID). Both restore entries share the
gap: the container's init launch (`AppContainer.kt:367`) and the chat VM
bootstrap call (`ChatViewModel.kt:835`). Pack-derived device acceptance
(crushrc removed since consent (re-verify folder state)'), the pack
mapping row **A03, restore-with-changed-crushrc** is MEASURED — device
acceptance below.

**S-04 (pack P1-6; review lineage F11) — REST keeps the silent plain-TCP
fallback.** `CrushApi.kt:101` (stream client, lines 86-103, which also
feeds `client` at line 105): `return if (socketPath != null)
EngineSockets.okClient(socketPath, plain) ?: plain` — a configured
socketPath that is absent AT CONSTRUCTION TIME silently falls back to
the plain `127.0.0.1` TCP client, i.e. the pre-2026-10-18 unisolated
transport recorded in [p1-attack-surface.md](p1-attack-surface.md)
§4/§8. The events path already fails closed (per-attempt supplier, no
`?: plain` — `349bad3`); the REST construction path is the remaining
leaf. FIXED IN CODE (f5d24dc, 2026-11-06): typed per-call
`RestTransport` {Uds/Unavailable/TcpForTests}, construction guard against
the bare shape, per-call resolution heals the boot window on the same
instance. Device boot-window row queued (open the app after a cold boot;
verify boot flows complete without plain-TCP fallback - the S-04 JVM
tests cover the ladder).
Pack-derived device acceptance: with the socket path configured and the
socket file removed, REST attempts must fail closed with the honest
scrubbed-path log and ZERO plain-TCP connects.

### Gate-0 closures — S-02 device acceptance (A03 drive) 2026-11-06 morning (device `bbf8ca69`, sources in clean-rebuilt `app-debug`)

**Context: the morning's launch crash was NOT this code.** The first
post-commit install crashed before Application init with
`ClassNotFoundException com.crush.mobile.CrushMobileApp`. Ruled out one
by one, each with evidence: transfer corruption (installed APK MD5
identical to local), class missing from the APK (found in its dex
entries), device runtime (the Nov 3 `r8probe` release-rigored build
launched fine DURING the same window - pid alive, window focused),
main-package install state (fresh install dir after `install -r` still
crashed), forced re-dexopt (`cmd package compile -m everything -f`
printed Success but the package state stayed `quicken/unknown`). ROOT
CAUSE: AGP 9's incremental debug dex pipeline, after session-long
accumulation, emitted a dex set this device could load nothing from; a
FULL clean rebuild (`:app:clean :app:assembleDebug`, all 38 tasks
executed, 22m07s) re-emitted a loadable artifact and the identical
source tree boots. Operational rule now standing: when a fresh install
of this app CNFEs at Application instantiation on THIS device,
clean-rebuild first; do not chase device state. Upstream filing
candidate to be DRAFTED ONLY (generic incremental-dex repro shape, no
app/device branding; awaits owner go like all filings).

**A03 restore-trust drive** (sentinel crushrc planted while the app was
dead; consent recorded by the user later the same morning):
1. Cold boot: `I/Lifecycle: last-binding restore blocked: project
   consent required (crushrc present, no consent recorded)` - the
   token-resolved project was NOT bound; the legacy prewarm pool started
   normally instead (S-01's fail-closed chat machinery untouched).
2. ZERO execution while blocked: no `.crushrc-ran.marker` from planting
   through the crash era to unlock - the folder stayed clean.
3. Consent on the restore prompt ("Run it and open"): marker written at
   the consent second (mtime + epoch content vs device clock delta 84 s),
   and app.log shows `starting supervisor for instance 'p536fa26e'` +
   `supervise loop start` + `Running` - the consent re-bound the project
   engine exactly as designed.
4. Post-consent surface: the project chat with its OWN restored history
   (lists notes.md/todo.md + the fresh marker).
5. No new residuals. NOTE (2026-11-06 owner clarification): the on-screen
   model slot showing `deepseek-v4.1-flash` is the owner's deliberate
   cost-saving model choice, working as designed - the once-recorded
   "model-slot read anomaly" is fully resolved and must NOT be re-flagged.
5b. Observation-only (no verdict yet): the project surface title reads
   the stored session label ("GitHub Trending") rather than the folder
   name; origin check queued, cosmetic either way.

Device-unexercised variants (JVM-covered only, queued with the E6 stress
pass): cancel-at-restore (legacy stays bound), changed-crushrc regate,
noConfig-flip, and migration 1->2 on a real legacy install (JVM test
covers it with a hand-built v1 DB). Sentinel fixture stays planted:
crushrc 72 B, sha256
dcba1093d3d1d41758f9170458190a903f5ef5837bc47c9c7d9e84599af3e147; the
recorded fingerprint matches, so future binds of this folder stay
unblocked (no further prompts from THIS sentinel).

### Gate-1 device stress drive — E6 warm-switch + E5 back-nav, 2026-11-06 evening (device `bbf8ca69`, clean-rebuilt `app-debug`, same session's app pid `3053` throughout)

**E6 warm-switch stress: PASS (within the two-row limitation below).**
Rows available on the Projects surface: exactly two - `Legacy workspace`
(shared pool) and `Test project folder` (own instance `p536fa26e`), so
A<->B was exercised across 5 measured switches (A->B, B->A, A->B, B->A,
A->B; screenshot-verified anchors, `e6-03`..`e6-07`).

Per-hop, all designed behaviors held:
1. **Stop-on-switch**: every A->B hop logged the full designed chain -
   `I/Lifecycle: stopping project engine for instance 'p536fa26e' (no
   run in flight)` -> `EngineSupervisor stop() requested` ->
   `EngineProxy: bridge stop` -> `CrushConnection: connection stopped` -
   and the child process actually vanished (ps: 2 -> 1 children).
2. **Fresh-subscribe recovery**: every B->A hop spawned a NEW child
   (pids 3141 -> 3951 -> 4912) for the SAME instance key, re-Running in
   2.7-2.9 s, and the project chat restored its OWN history (the full
   GitHub-trending transcript) on the fresh bind.
3. **Child budget**: counts alternated 2 <-> 1 exactly as designed;
   never >2; app pid 3053 stable all drive; zero crash-buffer entries.
4. **Per-instance transports are honest**: legacy binds use
   `crush.sock`, project binds `crush-p536fa26e.sock`; each bind logs
   `event transport identity change -> ...; rebuilding connection` and
   rebuilds the long-lived stream rather than reusing a dead one
   (boot-window healing observed live, consistent with the S-04 row
   above). Lens: `ensureAgentReady` + `resuming session` meshes per hop.
5. **No stale retries, no reclaim warnings** anywhere in app.log across
   the whole switching storm.

Limitation recorded: with only one non-legacy project row, the
registry's 2-non-legacy-children budget ceiling is NOT exercisable on
device (JVM-covered only). Needs a second imported project row in a
future drive.

**E5 back-nav: DEFECT MEASURED AND REPRODUCED (engine-safe, nav-broken).**
Repro sequence: drawer -> Projects -> back (returns to chat, twice
clean) -> back again from chat. From there the system back key does NOT
exit the app - it ping-pongs deterministically between the Projects
surface and the chat surface; 5+ consecutive back presses, all
re-surfacing alternates, zero exits (measured `b2`/`b3`/`b4`/`b5`
screenshots inside one tight command, plus the initial exit-leg probe).
Android-level back stack stayed `sz=1` single-Activity throughout ->
app-internal Compose nav state, not platform stacking; no
`enableOnBackInvokedCallback` in the manifest (classic onBackPressed
path).

Code anchor: `ui/navigation/NavGraph.kt:64-66` - Projects' `onOpenProject`
calls `navController.navigate(Screen.Chat.route) { launchSingleTop = true
}`, which from stack `[chat, Projects]` PUSHES a new chat entry
(`[chat, Projects, chat]`) instead of popping back to the existing chat -
each row tap stacks one more Projects-underneath layer, so backs keep
finding another alternate surface. Remaining mechanism gap: the
bare-root `[chat]` -> Projects reversal is not yet pinned (no
restoreState/saveState machinery exists; candidates: MIUI back-key
delivery framing or an unpinned NavHost pop-back behavior) - to be
closed in the fix batch.

Engine interplay during the ping-pong (all SAFE): every chat
re-exposure re-bootstraps ONLY the last-bound legacy instance - log
shows `bindEngine Running`, `ensureAgentReady` (claude-haiku-5-5),
`resuming session 52095506 (0 msgs)`, connection rebuilt on
`crush.sock`; NO project child spawn, child count stable at 1, no
retries - a wasteful re-boot on nav restore (side-effect repetition),
not a registry/reclaim defect.

Boot-window cosmetics that self-heal (screenshot pair `b2` vs `b4`):
the back-exposed chat first shows retained composition from the
PREVIOUS binding - title "GitHub Trending" + the project transcript
with a `connecting to agent` badge and the model slot momentarily at
the probed default (`claude-haiku-5-5`) - then swaps to the real
legacy state (`ready`, empty transcript, correct title) when the boot
completes ~5 s. Honest mid-boot display; separate cosmetic note: the
slot shows the session-synced model only after full boot, so the
pre-sync default reading is expected, not the model-slot anomaly
(which is RESOLVED; see the A03 section).

Queued from this drive (code batches, no docs-only impact): E5 nav fix
(`onOpenProject` should popBackStack to the existing chat entry and
re-bind in place instead of re-pushing; also decide back-from-chat-at-
root behavior: finish the task), plus the optional
wasteful-reboot-on-nav-restore dedup. Upstream filing: none (in-app ux,
no upstream overlap).

### E5 nav fix SHIPPED + device-verified; S-04 flake diagnosed & hardened (2026-11-07)

**E5 back-nav fix: `f516d1f` (parent `053ccb5`) - gh-verified via CI green
through `526b3a6`.** The fix replaces the measured stacking behavior:
`onOpenProject` navigates to Chat with `popUpTo(Chat) { inclusive }` +
`launchSingleTop` - the stack is REPLACED back to a single chat entry
instead of pushing a new chat entry on top of Projects. Fresh-entry
rebootstrap semantics unchanged (each row open still re-binds that row's
engine, same per-hop shape the E6 drive measured). Device re-verification
after reinstall with data kept (one lock-gate unlock):
- D1/D2 drawer -> Projects -> back -> chat: clean cycles intact.
- D3 back from the chat root EXITS the task (launcher focus; this was
  the infinite ping-pong before).
- D5 Test-row open: fresh project child `29947`, 2 children, per-project
  socket rebuild (`crush-p536fa26e.sock`), history restored from the
  project session.
- D6 back from the project chat EXITS.
- D7 Legacy-row open: full designed stop-on-switch chain for the project
  child, children 2->1, socket identity back to `crush.sock`.
- D8 back from the chat root again EXITS.
Zero crash-buffer entries throughout; app left in the designed
legacy-chat resting state. Out of scope: the optional dedup of the
wasteful legacy re-boot on nav restore (measured before the fix; the
new navigate shape still re-bootstraps by design) - stays an optional
follow-up, not a defect.

**S-04 socket-identity drain test: HARDENED for runner load (`526b3a6`);
"random flake" was not accepted as an explanation until understood.**
Measured on the two failures:
- First CI failure in a 40-run history (39 green, same suite, same test,
  2026-11-03..11-06), plus two repeats on the same day: the local full
  gate (31m30s, 2-core Windows box mid-release-compile) and CI
  `42280949224` (gate 3m09s, 2-vCPU hosted runner).
- The CI per-test report pins the failure shape: test duration 15.026s
  with the collector parked in `StateFlowImpl.collect` the whole window -
  CONNECTED was never visible to it. A StateFlow cannot lose a published
  value to a parked collector, so either CONNECTED never happened, or it
  happened before the collector landed, the state moved away, and never
  returned.
- Ladder arithmetic: the honest-fail ladder's sleeps for six consecutive
  failed attempts sum 250+500+1000+2000+4000+8000 = 15.75s - deeper than
  the old 15s `withTimeout` in ONE failure run; the entire assertion
  window can be consumed by a single sustained slow path although the
  test's happy path is ~300ms.
- A second named window: the collector used to subscribe AFTER
  `ensureAttached` started the reader thread (~250ms to CONNECTED), so a
  pre-subscription publish was possible; the read of the leaked value
  relied on the state STAYING CONNECTED - true for the KEEP_OPEN stream
  in the common case, but never a guarantee worth making load-bearing.
- Hardening in `526b3a6`: (1) subscribe BEFORE attach - the collector is
  created with `CoroutineStart.UNDISPATCHED` (body runs inline until its
  first suspension) and only then does `ensureAttached` launch the
  reader, making the publish-vs-subscribe interleaving irrelevant;
  (2) budget 15s -> 30s - the test pins transport identity and honest
  attempt shape, not wall clock; wrong-shape recovery still surfaces as
  the timeout, never as a wrong pass. Both changes carry their rationale
  in code comments.
- No prod defect found (search was real, not ceremonial): the
  `clientSource` probe has exactly one call site per attempt
  (`CrushConnection.kt:212`), CONNECTED is set only after a successful
  response, the ladder mirrors Crush's own client shape, and weeks of
  device evidence (every measured bind 2.7-2.9s, no 15s-class stalls in
  any drive) show no prod-side stall; prod also has no 15s deadline
  wrapped around connection.
- Verdict after hardening: isolation green (1m48s-2m11s), full local
  gate GREEN, CI `42309481087` GREEN 3m45s (562 tests) - first CI pass
  of this suite with the hardening, riding the E5-fix chain.

### P4 Gate-2: Review/revert+export surface — DEVICE-PROVEN (2026-11-07; export no-op found live, fixed `bf5019a`)

Both P4 proof rows closed on device `bbf8ca69` across two drives the same
day ("phone user edits two files, reviews each hunk and exports/preserves
the result" + "unsafe tool calls require project-scoped approval").

**Batch chain, CI green each:** batch 1 `5f14dfc` (run `42320008115`)
- the record surface: per-turn collector -> `ReviewHistory` runtime store
  (MAX_CARDS=8), chat footer "N file changed +a -b" with per-file rows +
  the "Review all turns" rail, review cards newest-first with hunks.
  Batch 2 `c8dd154` (run `42344434695`, 3m34s) - per-file Revert (confirm
  dialog; POST-TURN GUARD truth table 25 rows) + Export a copy SAF rows.
  Fix `bf5019a` (run `42390123731`) - found and closed in drive A below.

**Drive A (midday, session `4161054d` - "GitHub Trending Analysis", Test
project folder):**
- Multi-file turn: one prompt produced a notes.md modify AND a scratch.md
  create in a single turn. Owner allowed the engine edit permission card
  (12:02:57 `prompt CLOSED (grant ok; id=19790e4d)`); 12:03:10
  `file changes: +2 -0 across 2 file(s), 0 unreadable`; footer "2 files
  changed +2 -0". Review card "Turn at 12:01:22 · session 4161054d - Test
  project folder · 2 file(s) +2 -0"; card time is turn START (the record
  line lands at turn END). Hunks expanded on screen: scratch.md
  `@@ 0,1 +1,1 @@` as a new file; notes.md `@@ 4,4 +4,4 @@` with context
  and the added line.
- Confirm-dialog copy captured: "Rewrites "notes.md" to the content it
  had before the turn at 12:01:22. The revert refuses automatically if
  the file changed after that turn ended."
- REVERT BYTE-IDENTICAL on disk: notes.md post-turn
  `49983f10…344952ce9` -> restored `dcb75b2b…afb99bff1f3` = the pre-turn
  hash captured hours earlier; scratch.md untouched (`baacd8a2…`,
  42 bytes) - per-path revert left the created file alone.
- Refusal surface in UI: after the revert the row shows a "Reverted"
  chip + the persisted note "Done: restored to its pre-turn content" and
  the Revert button is GONE (on-device semantic of the already-reverted
  guard; its full truth table stays unit-proven).
- BUG FOUND LIVE: Export a copy was a SILENT NO-OP - the tap left the
  screen unchanged, no DocumentsUI, no log (owner reported "the export
  option doesn't work"). Cause: `onExport` recorded the pending target
  but nothing ever called `exportLauncher.launch` - the batch-2 reply
  contract shipped, the launch didn't. Fix `bf5019a`: record + launch in
  the same callback, suggesting the bare filename (rel path's last
  segment). Gate GREEN 32m3s (587 tests + both assembles + lintRelease),
  CI green, reinstalled data-kept.

**Runtime-only honesty demonstrated live:** mid-drive the app process
restarted (~16:04). Disk truth survived (reverted notes.md + sentinel
intact), but the Review store came back EMPTY - exactly what the screen
copy says ("History clears when the app process ends"), so the drive
re-ran a fresh one-file chain in the new process instead of restoring
the old card. app.log record lines are per-process too: at fold time a
grep returns only the current process's lines.

**Drive B (evening, fresh process, same session) - multi-card + export
parity:** record lines 23:14:43 and 23:16:45 `+0 -0 across 0 file(s)`
(free-model flake turns ended empty: recorded, but NO CARDS - only
turns with a diffable change enter the store), then `+1 -0` 23:17:03
and `+1 -0` 23:18:18 (the append landed twice; the model itself flagged
"the same line twice (lines 2 and 3)" and, on a one-word follow-up,
removed the duplicate), then `+0 -1` 23:19:28. Review screen: THREE
cards newest-first - 23:18:30 (+0 -1) with live "Revert this file"
("restores what this turn measured"); 23:17:33 and 23:16:48 (+1 -0,
"Older turn - read-only; only the latest completed turn is revertible").

**EXPORT PARITY (`bf5019a` device-verified):** Review -> Export a copy
on the newest card -> DocumentsUI PickActivity OPENED
(`mCurrentFocus=…documentsui.picker.PickActivity`; pre-fix the same tap
left MainActivity focused); filename PRE-FILLED "scratch.md" (the
suggested name); Recent -> drawer -> Downloads; SAVE enabled -> wrote
`/sdcard/Download/scratch.md`, 63 bytes. sha256 of source
`/storage/emulated/0/Test project folder/scratch.md` =
`831a410d66ec1804857b1855192ca8cbfb4067c0ff2399bfd851fb2ff540e423` ==
the downloaded copy, byte for byte - a byte-exact copy AS-ON-DISK-NOW
(source content: "scratch pad for the wall organizer build." +
"export drive marker."). The screen then showed the outcome sentence
"Exported a copy of scratch.md."; the app rode the SAF round-trip
cleanly (`resume gate=OPEN … verified=true`, away=114s).

Final disk truth after both drives: notes.md still the pre-turn
restored bytes `dcb75b2b…`; sentinel `crushrc` = `dcba1093…f3e147` with
`.crushrc-ran.marker` present through BOTH drives. Honest caps still
standing: the pre-written gone-file sentence for a turn-deleted path
("file is gone; nothing to export") is unit-proven, not device-fired;
history is runtime-only by design (restart clears cards; the workspace
files themselves persist, and revert/export act on them).

Queued at owner direction this same night: the same save pattern for
CHAT conversation output - code-block Copy + Save-as-file chips,
app-side only so the Chat deny-all policy (S-01) stays untouched.

**CHAT OUTPUT SAVE SURFACE - DELIVERED AND DEVICE-PROVEN (2026-11-08
morning, `fe9e7e8`, run 42403223894):** the queued batch shipped and
verified the same day on device `bbf8ca69`. Code shape (app-side only,
no engine-policy changes): MarkdownText's CodeBlock header row =
language label (ellipsis-bounded) + Copy + "Save as file" chips;
ChatScreen renders "Copy message" + "Save as Markdown" rows under
completed assistant messages, hidden while streaming and on failed
cards (verified live BEFORE any success case: the gateway-401 failure
card showed NO actions row, Retry stayed its affordance).
`codeBlockContent()` saves the FULL emitted block, never the
CODE_MAX_LINES display trim (a saved file ending in a truncation marker
would be a lie). `codeSaveName()` treats the fence tag as untrusted
model output: 24 known tags mapped (python -> code.py etc.), unknown
tags sanitized to [a-z0-9_-] capped at 12 chars, missing -> txt; pinned
by unit tests including separator-smuggling rows. The saver mirrors the
device-proven Review export contract (CreateDocument; user-cancel
silent; refused stream/IO failure = one precise sentence; UTF-8 exact
bytes; Main-thread toast).

Device drive - three renderer instances proved over history replay
(fresh-install restart -> OpenRouter provider switch -> opencode-go key
replacement + full process restart, action rows rendered each time):

- Message level on replayed history: "Save as Markdown" ->
  documentsui.picker.PickActivity OPENED, name PRE-FILLED
  `message-062916.md` (device-clock HHmmss), Downloads -> SAVE ->
  `/sdcard/Download/message-062916.md`, 72 bytes, byte-identical to the
  displayed message's markdown (inline backticks preserved, no trailing
  newline); toast "Saved message-062916.md." on screen. "Copy message"
  -> toast "Copied message."
- Code level on a FRESH turn (deepseek-v4.1-flash; prompt engineered
  for exactly one fenced python block with two exact marker lines):
  header rendered `python` label + Copy + Save as file; Copy chip ->
  toast "Copied code block."; Save as file -> picker with PRE-FILLED
  `code.py` (the fence-tag->name mapping live) -> SAVE ->
  `/sdcard/Download/code.py`, 35 bytes, sha256
  `56cc99b7a7fa4289d2f0e4bf852b9ec4e8d521076795a099dcd3c10e33f21d00`,
  od-verified content EXACTLY "drive marker one.\ndrive marker two."
  (no fences, no truncation marker, no trailing newline) - the saved
  file equals the displayed block.

Boundary honesty through the wave: the python turn's collector measured
`+0 -0 across 0 file(s)`; the saves wrote ONLY the user-picked SAF
destinations; app.log carries the turn's one record line and NO record
lines for the saves themselves (a chat save is not a project file
change by design - no Review cards); sentinel set intact end-to-end
(notes.md `dcb75b2b...`, scratch.md `831a410d...`, project-root
`crushrc` = `dcba1093...f3e147` byte-stable with `.crushrc-ran.marker`
present and refreshed by the 08:10 engine boot - content `1794105629`,
a boot epoch; the engine's own config store rewrote
`files/crush/data/crush.json` at that same boot, `7bedaa84...`). The
S-01 chat deny-all policy is untouched by this batch - it rides the
chat pool instance (`files/crush-chat`), injected and verified by
readback at spawn as always.

This is the Chat-side polish the P5 acceptance fold will cite.

**S-01 CATALOGUE PARITY - REOPENED BY EXTERNAL REVIEW, FIXED AND
DEVICE-RE-PROVEN 2026-11-08 (`a7e6d14`, CI run 42454530745):** an
independent code review alleged the Chat deny-all list was NOT exhaustive
against the pinned v0.99.0 engine. VERIFIED TRUE and fixed. Evidence
chain: (1) upstream `internal/config/config.go` `allToolNames()` =
29 built-in ids; `resolveAllowedTools` filters `disabled_tools` by
EXACT NAME (a literal "lsp_*" string hid NOTHING - the app list's old
"lsp_*" shorthand and even the capabilities-era prose were wrong); (2)
the shipped binary itself (strings over
`app/src/main/jniLibs/arm64-v8a/libcrush.so`) carries all 9 missing ids
and NONE of the four v0.100.0 agent tools (agent_dispatch/agent_send/
agent_status/agent_stop - those belong to the engine-upgrade ladder,
v0.100.0 released 2026-11-05 with subagents, Sysadmin Mode, configurable
safe/blocked commands and PGP/SSH prompts); (3) FIX: `CHAT_DISABLED_TOOLS`
now carries ALL 31 ids (the 22 + lsp_rename/lsp_replace_symbol (LSP
WRITES), lsp_symbols/lsp_definition/lsp_call_hierarchy (LSP reads),
crush_info/crush_logs (self-introspection), list_mcp_resources/
read_mcp_resource (BUILT-IN MCP surfaces - the old injector note claiming
MCP names are absent from the built-in list was wrong and is superseded));
the two inert drive-observed ids (task/diagnostics) stay. A FROZEN-
CATALOGUE RATCHET TEST (EngineConfigInjectorChatPolicyTest) now fails on
any engine upgrade whose catalogue outpaces the deny list, with the four
v0.100.0 additions named for the upgrade ladder. Gate GREEN 28m12s.

Device re-proof after reinstall (data-kept): `files/crush-chat/data/
crush.json` (the chat-pool instance config the engine READS at spawn)
carries ALL 31 ids on disk (readback over run-as cat; idempotent union
merge over the previous 22-pack); across every post-fix turn the
ToolActivity log recorded ZERO new tool_call events (count unchanged at
3 historical replay lines). The model was instructed to call crush_info
and lsp_rename: it ATTEMPTED both (DSML-marked text attempts for
deepseek-v4.1-flash, which calls tools in text markup) - crush_info and
lsp_replace/lsp_rename attempts produced NO engine tool_call events and
NO execution; the model's own nudge reply: "No code was executed in any
verifiable sense". Failed-turn honesty held throughout (claude-haiku and
deepseek empty turns record +0 -0 / no cards; the failed cards show
Retry, never save/copy rows).

**REGISTERED NEW DEFECT (upstream engine, v0.99.0) - stale tool
advertisement for long-lived agents:** a re-opened legacy chat session's
agent still ADVERTISED exactly the 7 newly-denied ids (crush_info,
crush_logs, lsp_call_hierarchy, lsp_definition, lsp_rename,
lsp_replace_symbol, lsp_symbols) as callable in its verbatim tool list -
while bash/edit/write/ls/view/question/todos (denied in the 2026-11-05
22-id pack) are NOT advertised, and both MCP-resource ids are absent
too. Pattern: the agent's prompt-side toolset was built when the OLD
22-id deny list was live and does NOT rebuild on a deny-list change;
the flank never executes (S1 wall + zero tool_call events), but the
model is misled into attempting inaccessible tools (wasted turns on
DSML text). Root cause sits in the upstream engine (v0.99.0 agent/tool
registry rebuild semantics; possibly persisted agent state), NOT in the
app's policy write. Engine-respawn + fresh chat session: fresh-agent
advertisement verification is PENDING the free-model flake (three
consecutive "no reply received" turns on deepseek-v4.1-flash in the new
session; retries are the established path). Honest security statement:
the EXECUTION boundary is exact-name-deny at the engine boundary
(S1-verified mechanism, re-proven live tonight); the advertisement
staleness is a prompt-hygiene defect with no demonstrated execution
path, and it RODES the engine-upgrade ladder (v0.100.0 re-verification
will require the ratchet test + a fresh-advertisement probe).

RESIDUAL-2 CLOSED - FRESH-AGENT ADVERTISEMENT PROBE, DEVICE 2026-11-09
(bbf8ca69, session "List tools and try", 5th send): after the four
deepseek-v4.1-flash probe sends every produced an EMPTY turn (+0 -0, no
cards, no reply), the fifth send ran on **GLM-5.3-Flash** (protocol note:
the residual plan pinned GLM-5.2; the picker row drift made that the
chosen row of the same generation - owner-selected in the picker, one
hand tap; model switch verified in the header tag). The fresh agent
REPLIED (~17s, current-session 95ec7be6, EngineProxy CONNECT ok, "file
changes: +0 -0") and its verbatim answer is the advertisement finding in
the model's own words: "None are actually callable here: no tool
definitions are attached to this chat, so there is no verbatim list I
can truthfully give, and I cannot attempt lsp_rename (any 'attempt'
would be fabricated text, not a real call)." - and it distinguished,
unprompted, the system prompt's PROSE mentions (view, bash, edit,
multiedit, write, lsp_rename, lsp_replace_symbol, lsp_symbols,
lsp_definition, lsp_call_hierarchy, todos, agent, web_fetch,
find_references) from provided schemas: "a tool is only callable if its
schema is provided to me in-session, and none is." So the asymmetry the
S-01 record hypothesised is CONFIRMED on device: fresh agent =
ZERO advertised tools (no schemas delivered at all), while long-lived
chat-pool agents still advertise the 7 stale ids; and the turn logged
ZERO tool lines in app.log (nothing executed - consistent with the
fabrication refusal). The upstream defect isolation stands: prompt-side
toolset staleness (long-lived agents) and never-delivering a schema set
to fresh agents are BOTH upstream v0.99.0 engine semantics, riding the
v0.100.0 ladder re-verification (ratchet + fresh-advertisement probe
rerun). Evidence: device screenshots of the exchange, app.log lines of
15:06:38-15:06:55 2026-11-09.

DEVICE UPGRADE, SAME WINDOW (2026-11-09 15:17): the Batch B build from
the 13:47 gate (app-debug.apk 101,684,841 B) installed over the running
app (`install -r`, Streamed Install Success) - S-4-era build's denial
note, Batch A's Room schema v3, and Batch B's domain model are now the
device build. Post-install restart PROOF: relaunch resumed straight
into the probe thread with the full pool intact (Room v2->v3 migration
opened clean - no FATAL/exception lines, alive heartbeat restarted at
t=30s granularity from 15:18); the handoffs table itself is not
queried by any current product path (no handoff dispatched on device
yet), so no deeper table proof is claimed. Cosmetic observables on
record: the session's model tag flipped to claude-haiku-5-5 after the
restart (selection does not survive the relaunch) and battery rose
27->80% once the socket hand-froze ended; the Batch C entry chip is
correctly ABSENT in this build (it rides the gate run in flight) - the
Copy/Save row is unchanged. (NOTE 2026-11-09: that absence note is
SUPERSEDED - the entry-chip build is device-verified in the P6 Batch C
record below.)

This parity pass is the security-acceptance artifact the reopened P5
fold cites.

**P5 ACCEPTANCE FOLD - PROVEN 2026-11-08 (formal closure record):** the
crush-plan P5 contract ("Crush Chat mode with hard tool boundary") is
closed on all four numbered items and both proof rows, on the artifact
chain of 2026-11-05 through 2026-11-08.

Item 1 - MODE-SPECIFIC TOOL/CAPABILITY POLICY ENFORCED BELOW COMPOSE:
`injectChatToolPolicy` writes `options.disabled_tools` into the chat
pool's own config BEFORE spawn and VERIFIES by readback
(EngineConfigInjector), `chatPolicyGate` FAILS CLOSED at
`CrushEngine.start` - Chat never spawns as a tool-capable instance on
any failure shape (S-01 `7ec34ed`; device-proven live, the MODE-01
denial drive 2026-11-05: "run ls via bash" answered with the model's
plain-language refusal - no ask card, nothing executed). 2026-11-08:
the deny list grew 22 -> 31 ids by CATALOGUE PARITY (upstream
`allToolNames()` + strings over the shipped libcrush.so; the WRITE-class
lsp_rename/lsp_replace_symbol included; the frozen-catalogue RATCHET
TEST now pins per-version coverage) and the device was re-proven on the
new wall: the instance config on the device carries all 31 (run-as
readback of `files/crush-chat/data/crush.json`), and across every
post-fix turn the engine logged ZERO tool_call events while the model
was ASKED to call crush_info and lsp_rename (its attempts produced
nothing - "no code was executed in any verifiable sense"). Registered
engine-side residual: the stale tool advertisement for long-lived
agent sessions (upstream v0.99.0) - filed honestly, benign under the
execution wall, rides the upgrade ladder.

Item 2 - SEPARATE CHAT AND PROJECT-LINKED CODE SESSIONS IN NAVIGATION
AND STORAGE: the dedicated chat-pool engine instance (`CHAT_INSTANCE_KEY
= "chat"` - own state dir `filesDir/crush-chat`, own socket
`crush-chat.sock`, its own session pool, NO project cwd) vs per-project
engine children under the Gate-1 registry (budget 2 non-legacy,
stop-on-switch, E6 stress PASSED); navigation honest (sheet
"Chats - conversation only" vs project rows; E5 back-exit fix).

Item 3 - SHARED KEYS/MODELS, SEPARATE GRANTS: provider keys + model
slots are global (per-provider vault, ProviderSwitchPolicy), while tool
grants live in each engine instance's own config - the separation is
structural at the engine boundary, not a UI promise.

Item 4 - REGRESSION-TEST CHAT PROMPT-INJECTION REQUESTS TO MODIFY
ARBITRARY FILES: the deny-ALL policy means Chat has nothing to reach
through; the two policy suites (EngineConfigInjectorChatPolicyTest +
ChatPolicySpawnGateTest, extended tonight into the 603-test gate with
the two hostile consent-window cases) pin the boundary; device probes
proved live denial (the bash/fetch family earlier probes and tonight's
crush_info/lsp_rename probes).

Proof row (a) - Chat cannot execute shell or write source in the
default mode: PROVEN (S1 + MODE-01 + tonight's zero-execution drives,
under forced-ASK framing the global mode cannot weaken). Proof row (b)
- Chat works when Termux is absent and no Code projects exist: PROVEN
STRUCTURALLY (no Termux anywhere; the chat pool never touches the
projects DB; every chat drive this week ran with zero Code sessions -
tonight included).

Artifacts: the chat screen, the policy layer (inject + readback + gate +
ratchet), the security test tier - this fold completes the record the
reopened P5 security acceptance was held for. **P5 IS CLOSED.**
Residuals (explicitly NOT blockers, queue order preserved per
decisions-open §10): (1) the engine-side stale tool advertisement
defect (the v0.100.0 upgrade ladder will re-verify); (2) the
fresh-session advertisement confirmation on device (pending free-model
flake, non-security); (3) the "smart transfer" denial message pointing
at Code mode (small polish batch); (4) a read-only Chat allowlist only
after an engine probe proves strict read-only (deferred by the owner's
standing decision).

**P6 BATCH C DEVICE VERIFICATION - PASSED 2026-11-09 (device `bbf8ca69`,
APK 102,369,305 B):** the Chat→Code handoff contract (Batch A rows
`d65ead0` + Batch B package `e23389c` + Batch C sheet/route `53bd2a7`)
was exercised END-TO-END on the device: a completed assistant reply in
chat session `95ec7be6…` showed the third chip "Use this plan in Code"
(the "entry chip correctly ABSENT" note above is superseded - that probe
build predated the gate ride), and the sheet opened with the provenance
line, the FULL summary prefill (Batch B's `take(idx+1)` over the tapped
transcript prefix), a single-select projects destination picker (row
"Test project folder") and the honesty consent card. Confirm produced
the complete dispatch chain in `app.log`: chat-pool instance stopped
18:40:22, code instance `p536fa26e` started (id = `p` + project-id
prefix) and reached Running 3162 ms, `bootstrap
workspacePath=/storage/emulated/0/Test project folder`,
`permissionMode=ASK` - destination binding proven by the engine's own
bootstrap line.

Row-level truth, read back from a pulled `projects.db`(+WAL) via run-as
copy (binary-safe pull, local sqlite3): `status=DISPATCHED`,
`userApprovedAt=1794229822696`, `targetProjectId=536fa26e…`,
`targetCodeSessionId=f3e78747…` (which DECODES the dispatch-window log
flip-flop below), `projectTreeFingerprintAtHandoff=NULL` (the PLANNED
Batch F deferral: `markDispatched` fingerprint String→`String?`),
`sourceProvider=deepseek-v4.1-flash` (provider stamp = model id).
S-02 ORDERING PROVEN ON REAL STAMPS: trust `recordConsent` …654
strictly before approve …696.

The verify-first protocol ran as designed. The first turn went out
byte-for-byte from the Batch B template ("SELECTED REFERENCES: (none)",
RULES 1-4 including the separate-turns confirmation rule); the agent
executed REAL bash (find+sha256 walk over the bound tree; `ls -la` and
a `.git` check) and answered; the ask-card approval flow worked live in
the handoff Code session (`permission requested: bash
(id=31163177/cd5aa293/adc3e8a4); pendingPermission SET` + prompt-wait
lines, owner taps). After the owner's confirmation turn ("continue",
~19:18) the agent ran `lsp_symbols` (finished), a bash marker
comparison and `crush_info`, and delivered the full mismatch report
(items 1-7, on screen) - and the protocol itself CAUGHT the tree
mutation: `.crushrc-ran.marker` mtime 1794232103 = 2,281 s (38m01s)
AFTER the approval stamp 1794229822; file changes stayed "+0 -0 across
0 file(s)"; the agent wrote NOTHING and ended "Awaiting confirmation
before any further action" (rule 4 honored: any further activity = a
fresh confirmation turn + the usual approval flow). HONESTY
CROSS-MATCH: the agent's INDEPENDENTLY computed sha256 of `crushrc`
(`dcba1093…`) equals the gate-recorded trust fingerprint
`dcba1093d3d1d417…` in the projects table - two implementations
agreeing byte-for-byte; the mechanism-level read (the stat-walk) is the
Batch F deferral. The engine stayed Running for the whole window; no
FATAL/crash anywhere in the session.

Open observations (recorded; none is believed a defect in the dispatch
mechanism): (1) the FIRST verify turn's run `c2e97e48…` stalled >20 s
and the app's idle watchdog canceled it - bash ask-cards still finished
and the agent's opening reply arrived; the owner's "continue" nudge
re-ran the protocol cleanly (watchdog behaving as designed; re-verify
rides later batches); (2) a transient dispatch-window log flip-flop
`4161054d` ↔ `f3e78747` ("resuming session 4161054d (110 msgs)") is
engine restore noise - the DB stamp records the correct target; (3) the
header title settled "Handoff: Tool", not the minted "Handoff HHmmss" -
engine-side title propagation wins the header (observed; cosmetic); (4)
the transcript view merges old chat content and the handoff turns in
one scroll (provenance preserved; UX note); (5) the
`.crushrc-ran.marker` WRITER MECHANISM is unidentified (engine crushrc
runner vs ask-card bash; a 72 B `.crushrc` probe residue from
2026-11-06 sits in the tree) - open question for the Batch F stat-walk;
(6) the instance model tag danced deepseek→haiku→deepseek at start
(pool default probe), same cosmetic family as the restart reset above.

Evidence: consent-sheet and drive screenshots, `app.log` lines, the
pulled Room db+WAL rows; the device sdcard probe files (`dc_glob_*`,
`dc_pull_*`) were removed after capture.

**P6 BATCH D BUILD (2026-11-09, `20e8347`; the post-approval surface
Batch C's comments deferred to "Batch D's piece") — code-side COMPLETE,
device drive pending:** one consenting sheet now serves three modes over
Batch A's guarded status machine. DRAFT is Batch C unchanged (cancel
stays silent, cancel-never-inserted untouched). RESUME surfaces a
resumable APPROVED row (the failed/never-started dispatch case the Batch
A guarantee protected): the approved summary is READ-ONLY - what the
user approved is what dispatches, immutably - and Confirm (relabeled
Dispatch) stamps `markDispatched` ON the existing approval, the original
`userApprovedAt` riding the first turn verbatim; `markApproved` is
single-shot and stays untouched. The explicit "Discard this handoff"
runs the guarded abandon (DRAFT/APPROVED only) and leaves every other
column intact - the approval stamp survives as the record; a DISPATCHED
trace offers no discard (the DAO refuses it; it is the proof-row-(b)
object). The no-alias replace (plan :416): a destination change abandons
the approved row and the SAME Confirm approves a fresh draft - with the
ordering hazard found and fixed in-review: the new destination's
gate+record completes BEFORE the abandon, so the F04 dialog's re-call
repeats only read-only steps (a first-cut abandon-first would have
dead-ended the user mid-consent; the reorder is the honest fix). The F04
dialog itself is now the handoff path's consent verdict: Required /
moved-inside-window publish to a screen-local `CrushrcTrustDialog` (the
Projects row-tap pattern) whose Allow re-calls the same entry and finds
Allow - the second gate runs the identical code path. And the
DISPATCHED surface's "Open in Code" re-opens the target Code session:
the dispatch's bind choreography extracted ONCE (`bindCodeForHandoff` -
the settle beat and the liveness budget gate included) and then
`openSession`, the session drawer's own epoch-guarded route with history
replay; the COLD-BOOT extension beside `restoreLastBinding` stays batch
G (the plan's division, unchanged). The sheet-open gains the trace
classification read (`HandoffDao.rowsForSource`, newest first): newest
DISPATCHED = jump surface, newest APPROVED = resume surface, else the
untouched draft, else the fresh prefill - an ABANDONED head re-taps into
an honest fresh handoff. Gate: local run verified per-XML (660 tests, 0
failures, 0 errors - 653 + the 7 new rows: discard-abandons-approved
with the stamp survival + re-entry refusal, discard-refused-on-
dispatched byte-identical trace, rowsForSource newest-first ordering,
the no-alias replace DAO path, resume-stamp-keeps-original-approval,
mode classification + slot exclusivity, the resume decision helper);
CI run 42653529569 GREEN 2m42s. HONESTY (recorded in the commit too):
the first local gate's client survived a host restart as a detached job
and completed the work; the observed re-run verified 90 tasks
UP-TO-DATE and the suite verdict was taken per-XML.

**P6 BATCH F BUILD (2026-11-09, `327a47c`) — the stat-walk deferral
CLOSED, device drive pending:** `ProjectTreeFingerprint` (domain/file,
plan §8 f1) walks the project folder STAT-ONLY - per file: relative path
(forward slashes), byte size, mtimeMs - sorts the rows by path and
hashes the stream to lowercase hex (the trust service's hex
discipline). The WorkspaceSnapshotter's noise rule (.git/build/
node_modules and friends) and depth cap ride, DOCUMENTED as the shared
false-negative family; the markers keep the explicit-over-silent ethos:
a refused file or directory records `<path><TAB>?<TAB>?` - never
silent, never invented numbers; a refused directory's subtree is not
visited (the first cut keyed that branch on `listFiles()==null` only -
the gate caught it via its own row and the fix landed before any
commit). A missing root is a deterministic empty digest; the walk lives
on Dispatchers.IO with no file contents read. Wiring: `dispatchHandoff`
computes the digest AT dispatch and carries it to BOTH the row
(`markDispatched`'s column - single-shot, a later stamp can never
rewrite it) and the verify-first first turn's provenance line - the
"(not recorded)" degradation now means exactly "the walk could not
run", never a missing feature. The resume dispatch stamps ITS OWN
fresh digest at its dispatch (the recorded tree is always the tree AS
DISPATCHED; and the digest is the app's own metric - the agent's
verify turn re-walks SEMANTICALLY, comparing tree reality against the
approved summary). Tests 660 → 669 (7 determinism rows + the DAO stamp
row + the real-digest render row); the first gate run was 668+1 and
the re-run lands 669/0/0; CI run 42661917711 GREEN 4m4s. The six Batch
C observations are also resolved by mechanism this window:
`docs/batch-c-observations-resolution-2026-11-09.md` (the marker
writer is the consented crushrc execution on every workspace
re-attach; the bootstrap-tail resume-grab is designed and absorbed by
the epoch guard; one probe for the drive: the engine-side session
lineage, plus the first turn's agent model capture).

**THE D+F DEVICE DRIVE + THE FIX BATCH (`f979d78`, 2026-11-10):** the
drive ran the D+F build live - dispatch worked, S-02 ordering again
(approve stamp 1794245706562 = the same second the chat engine stopped),
and the Fingerprint went to FULL live evidence: the row carries
`projectTreeFingerprintAtHandoff = e0f610c1db00772512c8a7eb2becd465ff
8a0343ecb239a979c093082eed66f1`, the verify-first turn renders it
byte-for-byte on screen, and an INDEPENDENT PowerShell stat walk of the
same folder produced the same digest (sec-aligned ms rows,
path-sorted) - row, screen and algorithm agree BYTE-FOR-BYTE. The drive
also elected obs specimens on the spot: the idle watchdog ate the first
verify run again (spec #2); the model probe flipped haiku in ahead of
the first turn and haiku failed to reply while the user's choice (glm)
worked - the owner nailed the pattern live ("the model changes back to
haiku... and it failed to reply"). The fix batch closes BOTH roots:
(1) the AppSettings SELECTED_MODEL slot existed but setModel never
wrote it and the probe shortlist never read it, so every fresh
instance (new chat, handoff attach, unlock re-bind) probed the
registry's static list first - setModel now PERSISTS the verified
choice (R3's race discipline untouched; only the success branch
writes) and the shortlist leads with the persisted id, dropping it
silently when no longer listed; (2) the dispatch and the jump carry
the stamped model id (a provider-id stamp denotes no model) as a
one-shot preference the next bootstrap init honors first - the
verify-first turn runs ON the model the user approved with. The UX
asks landed too: the redaction-net refusal (the report-derived summary
hit `long-hex-secret` properly - the owner's eleven "dead" Confirm
taps: the net, hiding behind the sheet's scrim by snackbar-only
notice) now renders INSIDE the sheet in error color; and the P2
enhancement is the replay-surviving resume bar - "Handed off to X -
Continue in Code" pinned at the source chat's transcript top
(stickyHeader), built FROM the handoffs row by refreshHandoffResumeHint
(the table truth the ephemeral dispatch note could never survive a
replay with), carrying its own F04 dialog state, with the jump made
sheet-independent (openHandoffInCode reads the active chat's own
trace) and refreshed at openSession/newThread/dispatch/cold-boot. The
jump has the now-familiar open question answered twice over: the
"resuming session 4161054d (110 msgs)" grab at 18:40 was the designed
bootstrap tail claiming a REAL 1-day-old code session ("GitHub
Trending Analysis and PDF Report", title seen in the drive's drawer)
before the dispatch minted its own; the epoch guard absorbed it.
Tests 669 -> 673 (shortlist head-first + unlisted-head-dropped; the
stamp split; the hint classification). Local gate 12m per-XML 673/0/0;
CI run 42676549043 GREEN 3m52s.

**WATCHDOG RETUNE SHIPPED (`37b1ab5`, 2026-11-10, same morning).** Specimen #3 (below, OBS-1 addendum) was the first proven TURN KILL: the owner granted the run's ls + view asks ("prompt CLOSED (grant ok)" 07:23:27), the engine executed the granted tools server-side with no SSE frames, and the frame loop — handed back from the parked-prompt loop AT the grant — read the healthy silence as death ("stream idle > 20s for run 2e3086ae…" 07:23:47, scan `+0 -0 across 0 file(s), 2 unreadable`). `STREAM_IDLE_MS` 20s → 60s shipped the same hour; the ~30s workspace reap window stays the earlier honest terminator for true corpses, and the 10-minute parked-prompt ceiling is untouched. Local gate 30m42s cold-daemon, per-XML 673/0/0 across 70 suites (census unchanged); CI run 42757904775 GREEN 3m01s. Follow-up recorded in the comment, not shipped: on idle expiry, probe the engine's run state first and cancel only when it is not busy.

**OBS-1 ADDENDUM (specimen #3, 2026-11-10).** Three runs now share the signature — the z4 ask (2026-10-29) was #1, the D-drive verify run was #2, this one #3 — grant = proof of life; the race read it as silence. The observations doc's ① "deliberately NOT taken" paragraph is SUPERSEDED.

**THE SYNTHETIC-TAP FINDING (hard; 2026-11-10).** The handoff DRAFT sheet's Confirm button ate EVERY synthetic attempt — 5/5 across `input tap` (x2), swipe-form press, and raw `input motionevent DOWN/UP` — zero registrations (no log lines, no row in projects.db(-wal) for source `19b5224d`, sheet state unchanged D32/D33). The button was not disabled (solid purple after destination selection; the same session's destination-row taps, chip tap, picker rows, send, and hamburger ALL register); the owner's physical finger registers on it instantly (the standing drive protocol). Record: handoff consent taps are owner-finger-only on this MIUI/Compose combo until root-caused. SUPERSEDED 2026-11-11: `19b5224d` DOES carry a handoff row — `93567528` (source `19b5224d` → target `a41519ac`, approvedAt 1794275487234, 808-char summary), DISPATCHED by the owner's own 07:21 finger-Confirm — the mint that created `a41519ac`; its sheet now correctly classifies as the DISPATCHED-jump mode. And P4's manufactured excursion was completed the same morning on a fresh chat (`1dda7d90`) — the stranded-APPROVED → RESUME → dispatch-on-existing-approval lifecycle closed live (the 2026-11-11 fold below).

**THE PERSISTED-SLOT PROOF (obs-6 fix verified on device).** After one picker re-affirm of glm-5.3-flash (probe "glm-5.3-flash → WORKS", 07:04:27, success branch writes), every boot since binds glm — 07:05:36 "probed 1 candidate(s), using glm-5.3-flash (of 27 listed)", 07:12:46 same, 07:21:39 rebind same; model[large]/[small] = glm on each ensureAgentReady. The haiku flip is gone across four boots (chat-pool boot, cold restore, unlock rebind); fresh chats answer glm on turn one, no retries.

**LATERAL DRIVE PROOFS (2026-11-10, no code change).** The resume bar is LIVE and sticky on device ("Handed off to 'Test project folder'. — Continue in Code" at the source chat `55cf3b3e`'s transcript top, surviving replay). The engine auto-retitles tool-bound sessions ("Summarizing scratch.md into notes.md plan", "Reorganize notes.md add sections list", "Handoff Task Mismatch Review Request" — display contract, per obs-③). Cold-boot restore = newest-with-content (resumed `19b5224d`, 2 msgs, after force-stop) and does NOT auto-open the RESUME sheet — the chip re-open is the route. The manifest carries NO deep links (MAIN/LAUNCHER only): `am start` can launch the app but cannot navigate in-app; sheet navigation is reachable only through the UI.

**CI FLAKE SPECIMEN + THE FIX (`20d2ce0`, 2026-11-10).** CI run 42758736412 (the docs-only commit 593f518 — the chain's first failing run, on a DOCS commit, which is exactly how you know it was not the code) failed one test on the runner and none anywhere else in the class's life: ProjectTreeFingerprintTest's "rewrite rename and mtime drift flip the digest", AssertionError at the "size and USUALLY mtime together" assert. Mechanism, from the uploaded report: the rewrite was 3 bytes → 3 bytes, path and size unchanged, so mtime alone had to carry the flip; on the fast runner the whole baseline-stat → rewrite → re-stat sequence is sub-millisecond, so the rewrite landed in the SAME millisecond bucket as the original write, File.lastModified() returned one value for both walks, and the digest held — the metric behaved exactly as its own docstring documents (the same-size rewrite inside the mtime resolution = the documented, not-discriminated false-negative family; the verify turn's semantic re-walk is the catch). The test asserted an outcome the metric never guaranteed; "usually" was load-bearing inside an unconditional assert. The ms-bucket collision is runner-speed luck — three prior CI greens and every local gate got the other outcome. THE FIX (`20d2ce0`, local gate 4m50s per-XML 673/0/0 across 70 suites, CI run 42760055813 GREEN 3m39s): the unconditional flip asserts now ride the stat changes a stat-only walk can always see (a size-CHANGING rewrite, then the rename), the code comment carries the specimen verbatim, and the pure-mtime component stays pinned by the explicit setLastModified row that was already there. No product change; nanosecond mtime precision considered and REFUSED — the metric row shape is frozen state.

**P4 CORE EXCURSION CLOSED LIVE (`593a04a6`, 2026-11-11).** The full designed lifecycle verified on device, DB-truthed at every step. STAGING: fresh chat `1dda7d90` (hamburger → "+ New chat" pill ×2), the plan prompt sent, GLM's reply hex-free (the redaction net would not block); the chip tap opened the DRAFT sheet synthetically (the chip registers — only Confirm is eaten); the owner scrolled the sheet and selected "Test project folder" (synthetic limits below); Confirm armed purple, bounds measured x552..1034 y2056..2164 — identical to the previous day's recipe. THE STRAND: a host-side baseline-count WAL poll caught the approval stamp at 13:55:46.010 and `am force-stop` landed 228ms later — the row written APPROVED with `targetCodeSessionId` still NULL (the dispatch never minted its session); the owner's "app crashed" was the kill. Critical poll detail: the WAL already contained "APPROVED" ×3 from the three dispatched rows' pre-dispatch frames (WAL is append-only), so a naive contains() poll would have fired BEFORE the tap — the poll fires only when the count EXCEEDS the arm-time baseline (3 wal / 1 db → +1 on the new row). THE COLD BOOT: fail-closed — restored the last binding (`a41519ac` on `p536fa26e`) with ZERO handoff processing in the log; the stranded row simply waited. THE RESUME SURFACE: the chip on `1dda7d90` opened the sheet in RESUME mode, verbatim — "This handoff was already approved - Confirm dispatches on that approval, no new approval is written. Approved 2026-11-11 13:55. Dispatching to 'Test project folder' on the existing approval." — the stamp matches the DB row; the summary field renders READ-ONLY ("a new draft re-approves a plan"). THE DISPATCH-ON-EXISTING-APPROVAL: the owner's Confirm minted session `8f1787c6` (`targetCodeSessionId` set), row → DISPATCHED; the dispatched turn ran bash/ls and raised the ask card exactly as the sheet's contract card promises ("does not promise a hands-free run"); the agent's verify-first re-walk is a negative-control proof — it reported the tree EXACTLY as-at-approval ("scratch.md is not a haiku… Plan step 1 has not been executed… Awaiting your explicit confirmation") because the strand had interrupted the dispatch before any file touch. The Discard leg was not exercised on this row (the dispatch consumed it); it can ride P5's strand.

**SYNTHETIC-INPUT MAP EXTENDED (MIUI hard facts, 2026-11-11).** (1) Sheet AND navigator scroll are impossible synthetically — `input swipe` collapses to taps (no MOVE injection reaches Compose) and `input event DOWN/MOVE/UP` is advertised in the usage text but rejects "Unknown command: event"; sheet content below the fold is reachable ONLY through the owner's finger scroll. (2) The bottom ~115px (y≈2225..2340) is the 3-BUTTON system nav bar, not a gesture pill — a tap at (531,2325) fired HOME (app backgrounded; recovered by `am start`; the short away resumed gate=OPEN with the settle beat re-grabbing `1dda7d90`). The app's tappable floor is y≈2225. (3) The IME never opens on synthetic focus taps — keyevents reach the focused field bare — so BACK (keyevent 4) and ESC (111) with the sheet up both DISMISS the sheet (two staging cycles burned learning this; the first DEL batch was also cut by the screen sleeping — `screen_off_timeout` bumped to 10min for the drive). The clearing recipe that works: tap the field's last text line → KEYCODE_MOVE_END when the cursor lands mid-text → device-side DEL loop → NO trailing keyevent. (4) The sheet re-prefills from the plan message on every open (compose state does not survive a dismiss). (5) One sheet dismissal at 13:44:27.757 with no synthetic input in flight — owner-touch suspected, same family as the 13:14:10 `away=6s` blur (benign, cause unrecorded). (6) The navigator lists only the ACTIVE binding's sessions, and the "+ New chat" pill center measured (274,1367); a (285,1390) tap opened "Review changes" (the mis-register family).

**THE REDACTION BLOCKS EXPLAINED + THE MINT MYSTERY CLOSED (2026-11-11).** `a41519ac`'s provenance block quotes the FULL 64-hex tree fingerprint, so every handoff FROM that session trips `long-hex-secret` — the owner's overnight refusals (23:15–23:22 and 05:26 blocks, all cancelled, no rows written) were the net doing its job on fingerprint-bearing text. The plan texts in `19b5224d` and `1dda7d90` are hex-free — Confirm runs clean there, as the staging proved. With it closes the mint mystery: the owner's own finger-Confirm on `19b5224d`'s DRAFT sheet at ~07:21 the previous morning wrote row `93567528` (source `19b5224d` → target `a41519ac`, approvedAt 1794275487234, 808-char summary) — the "new" session was the handoff's own target all along; the sheet's DISPATCHED-jump classification and the DB row agree.

**WATCHDOG LIVE PROOF + SPECIMEN #4 (2026-11-11).** Zero idle kills after the 10:46 install through the owner's overnight turns (`a41519ac` went 15 → 21 msgs). The archive added a FOURTH 20s-era specimen: 07:24:58.286 "stream idle > 20s for run f459e0a2" — one minute after #3, same pre-install build. All four specimens are historical; the 60s budget is still unburned in live use.

**JUMP SURFACE VERIFIED SYNTHETICALLY (2026-11-11 morning).** The resume bar's "Continue in Code" at (879,332) fired `openHandoffInCode` end-to-end: no re-gate (the consent state held), the project instance restarted in 2.9s, "resuming session a41519ac (21 msgs)" landed, and the provenance block replayed the handoff id, approval stamp, fingerprint and APPROVED summary byte-for-byte against the DB row pulled minutes earlier. The sheet's DISPATCHED classification (jump-mode, un-discardable) verified on-screen.

**P5 DRIVE: DELETE+RE-IMPORT, THE DISCARD LEG, AND THE CHAT-SIDE F04 CONSENT BUG (2026-11-11 afternoon, DB-truthed throughout).** PHASE 1 — the tombstone→re-import cycle: the Projects screen's type-honest Remove dialog tombstoned row `536fa26e` (engine stop logged "no run in flight"; **all 4 DISPATCHED handoff traces survived the row's deletion** — the Batch A no-FK guarantee, now device-proven; the folder itself untouched); re-import through the SAF picker minted a FRESH row `e26670d9` with trust columns NULL (the fresh-consent contract held), and **no F04 dialog at import — the gate fires at row-OPEN**; the row-tap raised the shared `CrushrcTrustDialog`, "Run it and open" registered SYNTHETICALLY over the Projects list, `recordConsent` wrote a fingerprint byte-equal to the SHA-256 of the pulled crushrc (match verified locally), and the engine started on the fresh instance key `pe26670d9`.

**THE DISCARD LEG (strand #2, row `d6b5b664`).** Staging re-used the drawer pill (which resumes the LAST POOL session — it landed directly on the strand source `758b725a`, no finger-scroll needed); the WAL-count poll caught the approval stamp at 14:55:28.890 and force-stopped 229ms later (the P4 recipe reproduces); the row stranded APPROVED → target `e26670d9` (the re-imported id — no stale alias), `targetCodeSessionId` NULL. Cold boot re-proved fail-closed ("lock engaged; refusing to bootstrap", zero handoff processing). The chip opened the RESUME sheet with the verbatim banner and read-only summary; the red "Discard this handoff" TextButton proved **finger-only** (two dead-center synthetic attempts, zero reactions); the owner's finger tap wrote ABANDONED with a byte-level column diff showing **status as the ONLY changed column** — every other column byte-identical, the approval stamp surviving as the record of what had been approved. The chip re-tap then opened a FRESH DRAFT (no banner, editable summary, no discard affordance) — the ABANDONED head is invisible to classification; the scrim dismiss is silent ("handoff sheet cancelled; no row was written, nothing bound").

**THE F04-MID-ABANDON LEG (strand #3) — GATE HALF PROVEN, ALLOW HALF EXPOSED A REAL BUG.** Source chat `36139fbe` (its plan turn mangled its own tool-call markup and failed honestly; the reply still chip-classified; `notes.md` untouched). DRAFT sheet → destination already carried the last selection; the poll caught the stamp at 16:42:10.664 (force-stop 203ms later); row `b8cb2f12` stranded APPROVED; cold boot fail-closed again. A benign crushrc planted in the legacy workspace via run-as produced a LEARNING first: a bare-word `crushrc` is INVALID to the engine's config loader — `bootstrap failed: HTTP 500 … failed to load config from paths […cfg/crushrc…]` — the file must be a valid shell line like the sentinel; rewritten as an `echo` line, the rebind's bootstrap recovered. In the RESUME sheet the Legacy row was selected SYNTHETICALLY (row-select stays synthetic; banner unchanged, "Will run in 'Legacy workspace'"); the owner's finger Dispatch raised **the F04 dialog floating OVER the sheet** ("This folder carries a crushrc … 'Legacy workspace' contains a project-root crushrc…"); the DB pulled mid-dialog showed the row still APPROVED byte-identical and Legacy fp/consent NULL — the pre-consent truth captured. THEN THE BUG: Allow ("Run it and open") — synthetic attempts dead, and the owner's 20–30 finger taps ALL looped: each Allow re-calls `confirmHandoff` → `gateAndRecordTarget` → Required again → the dialog re-opens (~400ms per cycle; **27 "handoff blocked: consent required (crushrc present, no consent recorded)" log lines**; the DB byte-identical after every tap). ROOT CAUSE by code-read: `ChatScreen.kt`'s dialog `onConfirm` only clears pending and RE-CALLS — **nothing on the chat side ever calls `container.projectTrust.recordConsent`**; the comment's claim ("the dialog recorded consent") has no implementation behind it. The Projects site works only because ITS onConfirm calls `vm.grantTrustAndOpen` → `recordConsent`. So the chat-side F04 Allow can NEVER succeed — an infinite re-gate loop with zero writes; Cancel is the clean exit (used). Leg BLOCKED by the bug; fix spec'd for the next code batch: record consent for the pending ref (the `recordConsent` seam, which re-hashes at record time) BEFORE the re-call, mirroring `grantTrustAndOpen`.

**THE SALVAGE — SAME-DESTINATION RESUME DISPATCH (row `b8cb2f12`).** Destination re-selected synthetically back to "Test project folder" (the approved target); the owner's finger Dispatch produced NO dialog (the target is already consented) and the guarded single `markDispatched` ran: **handoff row count still 6 — NO INSERT**; `b8cb2f12` → DISPATCHED with `targetCodeSessionId` SET (`55bdf3a5…`), `userApprovedAt` UNCHANGED (the original stamp rides the dispatch), and the target's consent re-stamped at the S-02 prelude. The dispatched agent's verify-first turn ran read-only and produced an honest mismatch report — its own stat-walk hash differing from the recorded tree digest (walk-implementation caveat stated by the agent itself), and the `.crushrc-ran.marker` mtime AFTER the approval stamp (the consented sentinel executing on the new attach — the known "twice per attach" semantics, not an unknown writer); the owner granted its bash ask cards live ("prompt CLOSED (grant ok)" ×2).

**PATTERN + FIXTURE NOTES.** (1) `consented_at_ms` RE-STAMPS on every successful gate (cold-boot restore and the dispatch prelude both observed) — fingerprint unchanged, stamp is a review aid, not freshness. (2) The crushrc fixture was REMOVED by owner decision after the leg (legacy workspace restored to its pre-plant state). (3) Synthetic map extended: sheet Discard TextButton finger-only (still true — the disabled-looking red text never reacted; the owner's finger did the work); F04-over-sheet Allow NOT input-filtered — strand #3's "dead" synthetic taps actually registered and vanished into the re-gate loop (the fix's re-drive proved it: a single synthetic tap drove the whole chain end-to-end); F04-over-Projects Allow synthetic-OK (P5 phase 1); drawer "+ New chat" pill = resume-last-pool-session (a staging shortcut that avoided a finger-scroll). (4) The strand #3 dispatch log receipt rides `Log.i`, not `AppLog.i` — `discardHandoff`'s success line has the same sink inconsistency (its DISCARDED receipt was found in the DB, not the log); both ride the next code batch's fix.

**THE FIX VERIFIED LIVE — F04-MID-ABANDON LEG CLOSED ON THE FIXED BUILD (2026-11-11 night, row `ef5f3278` → `65770c54`).** `9a86a7e` (the consent-before-re-call fix + the AppLog sink batch) passed local gate 33m39s per-XML 675/0/0 across 71 suites and CI run 43032267552 GREEN; installed over the running app. RE-DRIVE STAGING: a fresh strand (row `ef5f3278`, source `9c17ad6a` — the strand #4 dispatch's own code chat, so cold boot resumed exactly there) was manufactured with the poll armed BEFORE the owner's Confirm ask (a first attempt, `61c964f3`, missed the strand because the owner pre-tapped while the poll armed with the stamp already in its baseline — the ask-then-tap strict order is now the rule; that miss still banked a clean same-destination dispatch). The cold boot fail-closed again; the benign crushrc (`echo planted-crushrc-marker` — valid shell per the earlier learning) was planted in the legacy workspace; the RESUME sheet opened on the stranded row with Legacy preselected. THE FIXED PATH, with DB truth at every step: the owner's Dispatch → gate Required (log "consent required (crushrc present, no consent recorded)"); the F04 dialog over the sheet; pre-consent pull: row APPROVED byte-identical, Legacy fp/consent NULL; **a single SYNTHETIC Allow tap (749,1511)** drove the chain — `grantHandoffTrust` recorded consent (Legacy fp == SHA-256 of the planted bytes, verified True) → re-call → gate Allow → `ef5f3278 ABANDONED for the no-alias replace` (21:25:09.334, stamp 1794410105185 untouched) → new row `65770c54` APPROVED 9ms later → DISPATCHED with target `077dc19e` (UUID) and session `b894b36f` bound (21:25:24.325). ALL FOUR receipts are in files/app.log (the sink fix proving itself). The dispatched agent's verify-first turn ran read-only in the legacy workspace and cross-audited BOTH crushrc files (its own cwd's harmless echo vs the audited sentinel), flagging the sentinel's self-modifying semantics honestly. The crushrc fixture was removed by owner decision afterwards (the Legacy row now holds consent for bytes that no longer exist — the next Legacy gate honestly re-gates as REMOVED_SINCE_CONSENT).

**P6 BATCH G SHIPPED + DEVICE-VERIFIED (`4bc5fb7`, 2026-11-12 00:26–00:30) — P6 CLOSED.** Cold-boot resume of the newest DISPATCHED trace, beside `restoreLastBinding` (whose bytes are untouched; the extension runs after it in the same init launch): `HandoffDao.latestDispatched` (insertion-order, LIMIT 1), the same fail-closed gate (Required / moved-inside-window publish the root-level `PendingProjectTrust`, whose Allow lands the binding), an adopt-only-when-the-target-is-the-active-binding-or-nothing-is-bound posture (a user who moved on is never yanked; the row stays DISPATCHED and surfaceable), and the deterministic session half: a new `pendingOpenSession` one-shot is published BEFORE the binding lands and is CLAIMED by `findSessionToResume` only when the bound workspace's OWN listing contains that id (a plumbing mismatch = the wrong instance's bootstrap; the intent is left for the right one). DEVICE PROOF — the exact scenario: cold boot with the newest trace `65770c54` → Legacy, whose consent covered the crushrc removed the night before → the restore gate fired `Required` ("crushrc removed since consent (re-verify folder state)") AND the new line `handoff resume blocked: project consent required (…)` proved the extension read the row; owner unlock → root-level F04 with the honest REMOVED_SINCE_CONSENT reason note → a single SYNTHETIC Allow → consent recorded as the noConfig marker (stamp 00:30:21) → binding `p077dc19e` → engine Running in 2.6s → **`resuming session b894b36f-… (25 msgs; the handoff trace's target)`** with the replay attached and the dispatched chat on screen. Plan proof row (b) — "the handoff remains traceable and resumable after app restart" — is now device-proven end-to-end; the row count stayed 9 (no rewrites, no deletes). Tests 675 → 676 (`latest dispatched only`: newer ABANDONED/DRAFT rows never mask the resume trace; a newer dispatch wins in insertion order); local gate 19m27s per-XML 676/0/0 across 71 suites; CI run 43082252173 GREEN.

**THE ENGINE ACCEPTANCE KIT + THE v0.100.0 RESEARCH/LADDER PLAN (2026-11-12, groundwork for every future engine).** The system: `EngineCatalogues` (one manifest row per engine version — v0.99.0 = 29 ids, v0.100.0 = 33 strings-confirmed, no removals; `PINNED_VERSION`) + the rewritten ratchet test requiring `CHAT_DISABLED_TOOLS` to cover EVERY known row (a future version is added as ONE manifest row and the failing test names exactly the ids to deny); `EngineApiContracts` (the compatibility half — per-version permission endpoint/field + new SSE shapes, with `gapsFor("0.100.0")` pinned by tests: **`/permissions/skip` → `/permissions/mode`**, **workspace `yolo` bool → `permission_mode` enum**, `sub_agent_report` part, pinentry/SSH events — the four real upgrade blockers found by primary-source research, now test-visible instead of device-surprises); the deny list pre-grown with the four v0.100.0 sub-agent tools (inert under v0.99.0); `tools/fetch-crush.ps1` gaining the v0.100.0 SHA-256 pin from the signed release's checksums.txt (the default advanced to 0.100.0 at the merge; `FetchScriptPinTest` now keeps the default and the Kotlin pin from drifting apart); and `tools/engine-rails.ps1`, the read-only tier-T3 device runner (engine version line vs expected, bootstrap health, on-disk deny readback, tool-wall observation, crash scan) — LIVE on the pinned build at **6 PASS / 0 FAIL**. Device truths folded from the investigation: the chat-pool config lives at `files/crush-chat/data/crush.json` (`cfg/` is empty; 31 ids on disk pre-install), and the engine SOURCES the workspace-root crushrc as a shell script (the earlier bare-word plant hard-failed config load with exit 127; an `echo` line loads and runs — the "twice per attach" note is re-measured per version, upstream source shows one execution per path per `config.Load`). Research + plan docs: `docs/research-engine-v0100-2026-11-12.md` (v0.100.0 is the latest — no patches; no DB migrations; no crushrc change; no vendor migration guide), `docs/engine-acceptance-kit.md` (the reusable tiers T0–T3, promotion/rollback rules, the copy-paste add-a-version order) and `docs/v0100-ladder-plan-2026-11-12.md` (the concrete execution: the version-aware permission adapter is the remaining code work; device rails owner-gated; Sysadmin surface + piped permissions are explicit owner decisions). Tests 676 → 680 (+1 manifest row, +3 contract rows); local gate 18m45s per-XML 680/0/0 across 72 suites; CI run 43122414797 GREEN.

**THE VERSION-AWARE PERMISSION ADAPTER LANDED (`79c3815`, 2026-11-12) — ONE BUILD SPEAKS BOTH ENGINES.** `EngineProtocol`: the bound engine's OWN version line (`EngineState.Running.version`) selects the permission wire family — v0.99.0 keeps the exact existing wire (`yolo` bool + `/permissions/skip`); v0.100.0+ sends `permission_mode` (normal|yolo — the sysadmin value is unreachable: `modeFor` cannot produce it) and re-applies through `/v1/workspaces/{id}/permissions/mode`; an absent/unparseable version fails SAFE to the pinned family, so a rollback binary always speaks its own protocol. Plumbing: `CreateWorkspaceRequest.yolo` becomes nullable (old default kept) with a new nullable `permission_mode` (Gson omits nulls → exactly one field rides each create), `CrushApi.setPermissionMode`, `WorkspaceManager.open(permissionModeWire)` re-attaching in the SAME family (the P1-11 re-attach discipline: never flip an engine between protocols mid-flight), `CrushSession.openWorkspace(engineVersion)`, and the bootstrap reading the bound supervisor state's version. Contracts now separate RAILS-PROVEN (`IMPLEMENTED` stays v0.99.0) from ADAPTER-SUPPORTED (both families declared and pinned), and the v0.100 gap list shrinks to the two SSE tolerances. Tests 680 → 685 (EngineProtocolTest 4 + a dual-family contract row); local gate 13m16s per-XML 685/0/0 across 73 suites; CI run 43127356497 GREEN. **THE v0.100.0 CANDIDATE WAS STAGED ON BRANCH `engine-v0100` (merged as `6800c0e`)**: the fetch script downloaded the official artifact, verified the recorded signed-release SHA-256, wrote the 94.8 MB AArch64 PIE, and the strings scan over the WRITTEN binary is clean — 33/33 ids, the `agent_task` probe absent, v0.100.0 version strings, zero v0.99.0. Branch gate GREEN 34m34s (685/0/0). Structural fact recorded: `libcrush.so` is GITIGNORED by design (the candidate binary is a local artifact; the fetch pin + T1 scan are its provenance chain and a fresh clone must fetch). `main` keeps the v0.99.0 engine until the device rails pass; the morning's install is the branch build. **SUPERSEDED: the rails passed and the merge landed as `6800c0e` (2026-11-12) — see the T3 paragraph below and the status-hub merge row.**

**v0.100.0 T3 DEVICE RAILS — GREEN (2026-11-12 evening, candidate `be35981` + `84d4bfa`, device `bbf8ca69`).** The scripted runner passed **8/0** (`-ExpectedVersion 0.100.0 -MinDenied 33`): v0.100.0 bound, bootstrap attempts=1 failures=0 with one designed locked-refusal, rail 1b chose `wire=MODE_ENUM` and logged `open: permissionWire=normal` with ZERO permission-route 404s, the chat-pool config carried all **35** deny ids, no FATAL. Interactive: **F04 fresh consent** on the imported `Test project folder` — swapping the crushrc bytes (fingerprint `dcba1093…` → `64b70f24…`) produced the CHANGED_SINCE_CONSENT dialog; Cancel left the marker byte-identical (`1794410408`), started no engine and wrote no DB row; Allow recorded consent `1794484916475` BEFORE the `17:31:56` attach and the marker advanced to `1794484921`, with the DB fingerprint equal to the pulled file's SHA-256. **Restore/Batch G**: a project-binding cold boot restored + gated while locked (`bootstrap refused: locked`), then resumed `9c17ad6a`; a Legacy-bound cold boot logged `handoff resume: restoring project 'Legacy workspace' + trace session b894b36f` and resumed `b894b36f` with 25 messages, leaving row `65770c54` DISPATCHED and its stamp untouched. **E6**: Legacy↔Test switches stopped the prior engine each time, children peaked at 2 (prewarm + active) and ended at 1. **Rollback (rail 9)**: main `d60f7c9` + engine 0.99.0 (release hash verified) installed over the v0.100 data, booted, unlocked and resumed `b894b36f` with all 25 messages; the engine state DB was unchanged in shape (`goose_db_version` 13, sessions 2, messages 25) — v0.99 opens v0.100 data, no one-way door. Correction recorded: the legacy *row tap* bypasses F04 by design (`4058588`); the Nov 5 proof was project row `536fa26e`, not legacy. **Same-destination dispatch (rail 7b, owner finger, on the reinstalled candidate)**: from the Legacy chat `b894b36f` the sheet opened with Legacy `077dc19e` preselected; ONE insert `eb78ea9d`, the gate's consent stamp `1794488827189` preceded APPROVED `1794488827242` by 53 ms, and DISPATCHED landed at `18:37:07.936` on the first try to engine session `755fcfd0-0462-47e6-b27e-f4e3d0276061`. The first turn completed on v0.100 (six granted bash asks, `open: permissionWire=normal`, `file changes: +0 -0`, zero 404/FATAL); the Legacy engine DB grew 2→3 sessions / 25→41 messages. A full-row DB diff shows exactly one new handoff row and ALL nine prior rows byte-identical — `65770c54` stayed DISPATCHED with target session `b894b36f`, stamp `1794412509337` and summary hash untouched; the only project change was `077dc19e.consented_at_ms` (the gate re-stamp). Merge landed 2026-11-12 as `6800c0e` (CI run 43227604396 GREEN); rails 3/4 and the sub-agent SSE render check remain advisory.

### Import-removal first-use defects - BOTH FIXED (2026-11-04)

**S1/S1b security probes — VERIFIED 2026-11-05 (device `bbf8ca69`, engine v0.99.0)**

Completed 2026-11-05 after midnight, entirely through the app + adb (no
product code); evidence-first per the plan's slice contract.

**S1 (engine tool-denial mechanism) — PROVEN:**

- Writing `options.disabled_tools` (the full built-in list: agent, bash,
  job_output, job_kill, download, edit, multiedit, lsp_*, fetch,
  agentic_fetch, glob, grep, ls, sourcegraph, write, view) into the keyed
  instance's global config HIDES the bash tool from the agent entirely:
  asked to `run ls via bash`, the agent ATTEMPTED a tool call, the engine
  answered the model "tool not available", and the model replied in plain
  text "the bash tool is no longer available in this session. Restoring it
  would be needed before I can list the folder." — NO ASK card, NOTHING
  executed, no side effects. THIS is the Chat deny-all mechanism (engine
  boundary proof); app-side auto-deny stays defense-in-depth.
- The denial PERSISTS: the engine's config store MERGED the probe's
  `options` block into its own `data/crush.json` (the single-writer
  behavior decision-4 designed), so it survived even after the source
  probe file was deleted and the engine respawned. Repair = strip the
  `options` key from the engine-owned file in an app-stopped window and
  respawn (done; bash verified live again on the next turn).
- `POST /v1/workspaces/{id}/agent/main` with `agent_id` exists in the app
  API layer (`CrushApi.setMainAgent`) — the S3 Chat-instance seating hook
  is available without new plumbing.
- Scoping: `disabled_tools` is a per-ENGINE-INSTANCE-global, so Chat's
  deny-all must ride the DEDICATED chat instance state dir (crush-chat);
  Code per-project instances keep their tools. Matches the planned
  engine-instance architecture exactly.

**S1b (imported-folder config trust) — PROVEN:**

- Sentinel drive on the imported folder: a project-root `crushrc` file
  carrying two `echo >> marker` lines was EXECUTED AS BASH BY THE ENGINE at
  workspace attach, twice per attach cycle, with the app's full UID and
  inherited All-Files-Access — BOTH markers landed (in the user's shared
  folder AND inside the app's private tree). F04 is not a hypothesis: an
  imported folder DOES get arbitrary shell execution upstream today, with
  no trust decision.
- `.crush.json` (project root) and `.crush/crush.json` were NOT executed
  (PreToolUse hook sentinels never fired across no-tool turns AND a tool
  attempt) — in the paths probed, the executable-config surface of this
  engine is the project-root `crushrc`.
- Implication for S3/S4: an import-time "include this folder's crushrc or
  quarantine it" trust decision is REQUIRED before attaching an engine
  (fold-in F04's acceptance A05), and the read-only-allowlist option
  (Q2-later) gains a mechanically proven basis: `ls` ran WITHOUT a prompt
  on this engine (upstream read-only behavior) while bash/edit asked.

**Probe residue and repairs (disclosed):** the first sentinel had an
unquoted path (its bash redirect created a stray file, cleaned); the probe's
`disabled_tools` write-back had to be stripped manually (done, verified);
the repair also removed the engine-owned `options` block from the keyed
crush.json — if the user ever relied on an upstream "always allow" grant on
this instance, regrant it. All sentinel artifacts are deleted from the test
folder; the drive's leftover `app.log` lines about deepseek/tcp probes are
recorded as-is.

### Import-removal first-use defects — BOTH FIXED (2026-11-04)

Driving the import-only removal on device (`5ef70f5`) surfaced two shipped
defects, both fixed the same evening in the slot-fix build:

1. **Dialog placeholders rendered literally.** The removal confirmation
   printed `'%s' is an app-internal project. Removing it also DELETES its
   generated workspace folder %s permanently.` — no project name, no path.
   Root cause is the same Kotlin trap that bit the store seam before:
   `"...%s..." + "...permanently.".format(args)` binds `.format` to the
   FINAL literal of the concatenation chain; its placeholder-free content
   silently swallows the format args and the rest renders raw. Fix: wrap
   the whole concatenated string in parentheses before `.format` (both
   dialog branches).
2. **Empty minted-slot wrapper survived removal.** `removeProject` deleted
   `ref.repoDir` (`files/projects/<uuid8>/repo` — the data) but not its
   parent, so after both rows were gone `run-as ls files/projects` still
   listed `83cc71bd` and `1cdf4551` as empty dirs. The slot dir is
   app-owned, invisible, and its key can never recur — fix: the removal
   deletes `repoDir.parentFile` (the whole slot). The two orphan wrappers
   from `5ef70f5` were swept manually; the Proof-A/Proof-B
   `marker-A.txt`/`marker-B.txt` isolation artifacts under `a/repo`/`b/repo`
   are deliberately left untouched.

The clean parts of the same drive: the engine-stop log lines
(`(project removed)` for `p8a3825d0` and `p80e7971b`), both `crush-<key>`
state dirs gone, `crush-p536fa26e` + `.sock` intact for the imported
instance, and the imported folder byte-intact on the cancel path.

### Tool-call wire format - CONFIRMED (D3, 2026-10-25)

A real tool-using turn was captured two ways: raw SSE frames and the persisted
messages endpoint. Both agree:

- part envelope: `{"type":"tool_call","data":{...}}`
- data keys: `id` (engine call id), `name` (write/bash/...), `input` (the tool
  arguments as a JSON STRING), `finished` (boolean, absent on streaming frames)
- tool results arrive as sibling `tool_result` parts carrying
  `{tool_call_id, name, content, metadata (JSON string with diff/additions/removals), is_error}`

ToolActivityParser reads data first (the part is the fallback), resolves
state from the `finished` boolean - COMPLETED only on `finished:true`, RUNNING
for a call id without it - and mines `file_path` out of the `input` JSON so the
row names the touched file. Unknown field shapes still degrade to the row
showing the tool name alone, and the runCatching-guarded key logging (now
covering both the part and its data keys) stays in place to catch engine drift.

**Related finding (live-captured, two defects):** in ASK mode an ask turn
never surfaced a prompt. Both halves were found and closed:
(1) **v0.98.1 engine defect** — the old engine never published
`permission_request` on the events stream at all; crush v0.98.2 adds
session-scoped ask delivery (plus pending-prompt republish on
`current-session`).
(2) **App-side defect, found 2026-10-27** — against v0.98.2, an instrumented
engine proved every breadcrumb fired (`permissionsvc publish` → workspace
fan-in → session-filter pass → frame written to SSE), yet the app still got
nothing: `CrushEventRouter` delivered run-id-less frames (`permission_request`,
`question_batch_request`, and every `message` — only `run_complete` echoes the
app-minted run id) to catch-all channels alone, and nothing in the app
subscribes to catch-alls. Fixed by fanning anonymous frames to every registered
run; pinned by tests. Consequence worth keeping visible: streaming text rode the
same broken path — the bubble stayed "thinking" until `run_complete` painted the
final text. See D12 in project-analysis.md. AUTO-approve turns run tools end to
end and stream a complete turn (proven 2026-10-20).

> **2026-10-31 engine-update note.** The bundled engine is no longer the v0.98.1
> named in half (1) above: `jniLibs/arm64-v8a/libcrush.so` is locally built from
> upstream **v0.98.2** (Go buildinfo `v0.98.2+dirty`; upstream released
> 2026-10-23), so that engine half is no longer a live defect in the shipped
> bundle. Upstream ask
> [#4092](https://github.com/charmbracelet/crush/issues/4092) (UDS scheme
> documentation, optional TCP auth) was closed by upstream on 2026-10-21 with
> nothing landed there. RESOLVED 2026-10-31: `tools/fetch-crush.ps1` now
> defaults to 0.98.2 with the upstream SHA-256 pinned from the signed
> checksums.txt (`9402531`); the downgrade path is closed.
>
> **Upstream v0.99.0 (published 2026-10-29, verified via the GitHub
> release API): evaluated 2026-10-31, NOT adopted.** Its Catwalk v3
> retypes providers by API format (`completions`/`responses`/`messages`,
> with automatic config migration); the client/server block is aimed at
> exactly this app's shape - host themes, host context, append-to-
> next-prompt, shared viewing, and `CRUSH_PERMISSIONS_PIPED=1` so the
> host answers permission prompts and the engine asks once. None of it
> is needed today: the provider registry/key slots/model probes sit
> above the engine, and in-band permission and question cards already
> work device-verified on v0.98.2 (D12). Sequencing decision (2026-10-31,
> agreeing with the second external review): evaluate the upgrade in an
> isolated branch AFTER the parked stopBlocking/backgrounding
> re-measure, and re-verify the full bootstrap - stream - ask - approve
> - finish cycle on device against the v3 engine before flipping the
> fetch pin.
>
> **Experiment stage 1 complete (2026-10-31, same day; the user green-lit
> running it in parallel).** Isolated branch `v099-experiment` (worktree,
> commit `d326e60`) with a `.v099`-suffixed applicationId so the experiment
> installs ALONGSIDE the reference app; engine fetched with a
> checksums.txt-verified pin (branch-only default 0.99.0); static API-surface
> binary diff: near-drop-in surface (only `/v1/config` gains `host_theme`;
> permission plumbing additive; no workspaces/SSE/attach changes); build
> green; experiment app installed and boots clean, parked on onboarding.
> Stage 2 (bind/readiness/SSE verdict) needs the user's one onboarding pass
> in the experiment app; evidence log: `docs/v099-experiment.md` on the
> branch. Main tree stays on 0.98.2+dirty.

> **PROMOTED 2026-11-01.** The isolated experiment branch
> (`v099-experiment`, evidence log `docs/v099-experiment.md` on that branch)
> proved OpenCode Go dynamically — plan-correct listing, probes, turns — on
> the upstream v0.99.0 engine, and the user called the promotion: `main`
> now bundles upstream **v0.99.0** (checksum-pinned from the signed
> release) plus the model-layer split. The instrumented 0.98.2+dirty binary
> remains the evidence base for the 2026-10 z-sessions; new evidence
> accretes against 0.99.0. Two-turn on-device verification on the promoted
> build (ask-card approval + file-edit diff, plus the standing
> stopBlocking/backgrounding re-measure) remains to call it done-done.

### Delete treating transport failure as success — FIXED

`CrushApi.deleteSession` used to convert a dropped connection,
`InterruptedIOException`, `EOFException`, and anything containing "workspace not
found" into success, on the reasoning that "a session cannot outlive its
workspace". That reasoning was wrong: sessions are persistent engine state, so a
timeout or a reaped workspace does **not** prove the session was deleted.

**Resolved (2026-10):** the dropped-connection swallow is gone. `DeleteOutcome` —
pinned by `DeleteOutcomeTest` — distinguishes *confirmed*, *already-gone*, and
*failed*, and the client re-attaches and force-retries before trusting `AlreadyGone`.

### The engine API is unauthenticated — RESOLVED for cross-app reach, still no HTTP auth

Transport (2026-10): the engine is launched with a **Unix-domain socket inside
the app-private directory**; the app is the only UID that can address it, and
the sibling-app TCP surface documented in
[p1-attack-surface.md](p1-attack-surface.md) is closed at the kernel level.
On-device: `GET /v1/version` returning `HTTP 200` to a different app UID (uid
2000, `adb shell`) is no longer possible — there is no reachable TCP port.

Details of the device experiment and what it does and does not prove:
[p1-attack-surface.md](p1-attack-surface.md) §8. Upstream has been offered the same fix:
[charmbracelet/crush#4092](https://github.com/charmbracelet/crush/issues/4092).

The HTTP layer itself still has **no token, origin, or credential check** — over
a filesystem socket from the same UID that is defence-in-depth buffer, not a
boundary. Rows of the attack-surface map still apply to *this* app's own UID,
which is trusted.

### First-launch lock bypass

On a fresh install the first session opens without a lock prompt, because
`AppLockPolicy` returns `OPEN` when there is no stored ciphertext. Later cold
starts do prompt. Whether onboarding itself warrants a gate is a product
decision, not settled.

### Workspace-lease scaffolding — NOT redundant

An earlier revision of this page called `WorkspaceLease`, `reattachIfStale`, and
the force-retry paths dead scaffolding from the per-turn-connection era.
**Re-checked 2026-10: they are still live code** — the send path falls back to
`reattachIfStale` + lease + force-retry whenever the shared connection is not
`CONNECTED`. `WorkspaceLease.isFresh` is used at `WorkspaceManager.kt:248`; the
force-retry path is live in the ChatViewModel send loop. The audit that gloated
here was wrong that time.

## Engine facts this project depends on

All verified on-device against Crush v0.97.1→v0.98.1, not read from docs.

| Fact | Detail |
|---|---|
| Workspace lifetime | Reaped after ~30s with **no attached client**. Not a fixed interval — it is attachment-based |
| Workspace identity | Keyed by resolved cwd. The id is an attachment handle that changes on re-attach |
| Session lifetime | Persistent engine state, independent of the workspace handle |
| SSE | Only `data:` lines. No `event:`, `id:`, or heartbeat |
| SSE resume | Impossible (no `id:`). Reconnect and resync from `/messages` |
| Message updates | Whole message republished, not deltas |
| Event envelope | `{"type":…,"payload":{"type":…,"payload":{…}}}` |
| Auth | None at the HTTP layer; transport is an app-private UDS since 2026-10 |
| Listener | Unix-domain socket inside the app-private dir (was `tcp://127.0.0.1:<ephemeral>` before the 2026-10 pivot) |
| Provider id | `opencode-zen` (a plain `openrouter` also works) |
| `api_key` | Must be sent **bare**; a `NAME=` prefix yields 401 |
| `scope` | An integer (0 = global), not a string |
| `/v1/health` | 200 with an **empty body**; use `/v1/version` |

## Testing posture

- **455 JVM unit tests** in 39 files (2026-10-31 count; 401 "all passing" was
  the 2026-10-20 statement — the suite has grown since). **Current verified
  count: 594, all passing (2026-11-08** — `@Test` grep under
  `app/src/test/java` at code HEAD `fe9e7e8`; that commit's local gate ran 594
  tests + both assembles + lintRelease).
- **Six** `MockWebServer`-backed test files (2026-10-31 count; two as of
  2026-10-20; **eight** as of 2026-11-08) assert on real HTTP order and on
  requests
  actually reaching the server — the latter exists because a method that throws
  before the call produces a *passing-looking* no-op, which is exactly how
  `deleteSession` failed silently for so long.
- Instrumentation coverage is no longer absent (2026-11-01): **OK (8 tests)** on
  device — launch-level smoke + the `MainActivityLockFlowTest` security
  contract, including the previously device-only Keystore behaviours
  (real-cipher roundtrip, tamper-clear) and the `require()`-never-caches
  bypass regression. Remaining device-only areas: the answered biometric
  paths (a test cannot tap the real system dialog) and child-process
  lifecycle; coroutine flow hot/cold ordering stays JVM-tested.
- CI separates the two certification profiles (MODE-25, 2026-11-03): every
  `android-ci` run attaches a **provenance manifest** (job summary +
  downloadable artifact) stating, measured from the checkout, whether the
  assembled APK is **engine-less** (CI's usual shape — the engine binary is
  gitignored: compile + JVM suites + lint certified, runtime NOT proven) or
  **engine-included** (vendored blob possible on a source build), plus the
  debug APK's sha256/size. Engine-included device-gate evidence stays with
  the on-device ledger rows in this file.
