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

## Implemented, unverified on device

| Capability | Notes |
|---|---|
| Tool-call visibility | Collapsed rows with name, state, arguments, output. **Wire format CONFIRMED 2026-10-25 (D3 closed)** — see below |
| Markdown rendering | Hand-rolled, streaming-safe. Fenced code, inline code, headings, lists, quotes, rules, emphasis, links |
| In-app diagnostics | Device/ABI/engine status, archived crash, log tail, copy/share report |
| Settings | Key replace/delete, permission mode, diagnostics entry point |
| Prewarm | Eager engine start when enabled; lazy on first keystroke otherwise |

## Not implemented

These are the real gaps, ordered by how much they matter for a coding agent.

| Gap | Impact |
|---|---|
| **File diffs** | Built and JVM-tested (snapshot → tracked files → diff → render), and the collector endpoint is verified running on device. 2026-11-04: the FIRST real edit turn was measured (imported-folder project: `notes.md` + `todo.md` written into `/storage/emulated/0/Test project folder`) — but the changes rail failed to display it: it counted `0 files changed +0 -0` and listed both files `(unreadable, binary, or too large)`. That external-file event routing is the known defect below; the diff render is still unexercised by end-to-end data |
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
  the 2026-10-20 statement — the suite has grown since, not run through this
  count here).
- **Six** `MockWebServer`-backed test files (2026-10-31 count; two as of
  2026-10-20) assert on real HTTP order and on requests
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
