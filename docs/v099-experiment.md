# v0.99.0 isolated experiment — evidence log

Branch: `v099-experiment` worktree build (suffixed `applicationId = .v099`,
so it coexists with the reference install and never disturbs the z-lab
0.98.2 engine baseline). Log opened 2026-10-31.

## Stage 1 — binary + build + boot (agent, complete)

- **Source provenance**: upstream release `v0.99.0` (published 2026-10-29),
  tarball `crush_0.99.0_Android_arm64.tar.gz`, sha256
  `d18cd1f961a4c587f26a50aad8ef1fed38ad4362bb6237807e57cda1c170f78c` verified
  against the signed release `checksums.txt` before use; the pin lives in
  this branch's `tools/fetch-crush.ps1` (default advanced to 0.99.0 for this
  branch only).
- **Static API-surface diff vs the in-tree 0.98.2** (printable-string
  extraction of both engines): net delta ≈ one config variant — `/v1/config`
  gains `host_theme`; new `/v1/docs/` OpenAPI hosting; permission machinery
  gains `PermissionRequest`/`Outcome`/observer types and lifecycle hooks
  (additive); **no `/v1/workspaces`, SSE frame, or attach-surface changes
  visible**. Prediction: near-drop-in for this client.
- **Build**: `assembleDebug` green in the worktree (10m57s cold, 36 tasks).
- **Install**: `adb install -r -t` → Success, package `com.crush.mobile.v099`
  alongside the reference app.
- **First boot**: MainActivity Displayed +4.2 s with ProfileInstaller; no
  FATAL/ANR; app sits on onboarding (fresh app data — expected).

## Stage 2 — engine-level verdict (plan, from opening)

The engine spawns only after onboarding, so the boot verdict on-device
needed the user to complete onboarding in the EXPERIMENT app (pink `.v099`
icon): pick a provider, paste a key, and open the chat once. The agent then
reads `logcat`/`run-as` app logs for the UDS bind line, the 60-second
readiness gate, a workspace/session bootstrap + SSE connect, and the
engine's own version banner (buildinfo is authoritative).

## Stage 2 — engine-level verdict (live results, 2026-11-01)

The user picked `opencode-go` and pasted a real key from a **Go
subscription** account into this build.

| Test | Result | Evidence |
| --- | --- | --- |
| Engine boot on 0.99.0 + UDS bind | **PASS** | `exec: ... server -H unix://<datadir>/.../crush.sock`, banner `version=v0.99.0` go1.27.1 android/arm64 |
| Workspace bootstrap → session creation | **PASS** | workspace id issued (first: `e5434f10`, later `ac9bff4c`), session `cdf2c32a` (47 msgs) |
| agent/init with model slots | **PASS after the list-split fix** | `ensureAgentReady: key set for provider 'opencode-go'; model[large]=beam-free; agent/init ok; is_ready=true` — the same call 500'd with `coder agent configuration is missing` before the split fix |
| Model picker catalogue | **PASS with the split fix** | `catalogue prices loaded for 37 models`, sheet listed 28 Go models; BEFORE the fix the app drew the PAYG console list (Zen-era ids uncallable by a Go key — the user's "only zen models visible" report) |
| Automatic model selection | **PASS** | scoped to `beam-free` in ONE probe (pre-fix: 24 probes, no pick) |
| Turn: streaming + tool rounds | **PASS** on beam-free | SSE attached `ac9bff4c`; permission cards (`fetch`, `agentic_fetch`, `bash`, `write`) delivered + answered on device; `ToolActivity observed tool_call` for bash/fetch/write; **file write of 72 lines with a fully rendered diff** |
| Key model rejection (deepseek-v4.1-flash) | **CAUSE NAMED 2026-11-01 (account-side, not app-side)** | with the full measurement chain live (plan-walk + classification), the probe returned `PAYMENT_REQUIRED: Upstream request failed: Insufficient account funds` on the Go base — the Console API doc explains the routing: *"Each key works in one workspace, chosen when it is created"* and billing goes to THAT workspace's budget (Harnesses doc: "Use a key from a workspace subscribed to Go for Go"). A Go model 402ing "insufficient funds" on the Go base means the KEY was minted in a workspace the Go subscription is not attached to. THE FIX is account-side: mint a fresh personal key INSIDE the subscribed workspace (Console > Account > Personal API keys > choose Workspace) and paste it in the app; the app now SPELLS this out on the rejected-model card instead of burying it in a log line |

Findings that are NOT app bugs:
- the unknown engine event `client_session` is a NEW 0.99.0 event type the
  router skips silently (observed once during attach; it is additive, not
  fatal — worth an additive handler later);
- Go's own model endpooints demand three things the CASUAL integrations miss
  (plan-family list base, full /openai/v1 chat path, non-SDK User-Agent);
  each failure mode was verified independently: wrong-plan list (payg
  catalogue), short chat path (404 plan-miss), and the UA filter (python
  urllib 403 vs browser 200 on the same URL).

### Soak verdict — 2026-11-01 morning (the standing item, now measured)

The stopBlocking/backgrounding soak ran as a REAL mid-run backgrounding,
on the promoted build, with the prompt lifecycle instrumented first
(`pendingPermission SET/CLOSED` + `prompt-wait entering/resumes`):

1. **A mid-run background with a PENDING permission card held**: the card
   rose (fresh session, first tool ask), the loop parked in
   `receiveWhilePromptOpen` (1 ms after SET), the app was HOME-backgrounded
   for 40 s with the card pending — **no idle-cancel fired** — and on
   resume the card was still live and answering it (Allow once) resumed
   the run cleanly.
2. **The full multi-card chain measured**: fetch → bash → agentic_fetch,
   three separate cards in one turn, each parking + resuming the loop in
   milliseconds; between cards the run streamed tool rows (view/grep/
   web_fetch) and the turn continued.
3. **A real model behavior note**: asked for the releases list, the agent
   chose to paginate web_fetch through EVERY releases page (page=12…15+,
   all finished) — long-running, correct, and the SSE kept flowing under
   it.
4. **The 07:51 idle-cancel anomaly** (the original soak trigger) did NOT
   reproduce under the instrumented build across several cycles; the
   instrumented path is now the evidence record. One candidate cause for
   the original was identified but not proven (a mid-card pause/unlock
   interleave before the diagnostics landed) — if it recurs, the new
   timeline lines answer it in one run.
5. **Draft-loss finding** (recorded, not fixed): the composer draft is
   cleared across a stop/saveState backgrounding cycle — small UX gap, no
   correctness impact.

**The stopBlocking/backgrounding backlog item is CLOSED as measured**:
guard holds, park/resume works, multi-card turns survive backgrounding on
the promoted build.

### Promotion VERIFIED on device — 2026-11-01

Two-turn cycle executed on the promoted MAIN app (agent-driven via taps +
screenshots; the user approved permission cards):

- **Turn 1 (tool + ask card):** "list the files in your project directory"
  fired a bash tool row, the permission card was raised and approved, and
  the answer streamed: "There are 5 files in the project directory
  (/data/user/0/com.crush.mobile/files/workspace)" — the list includes the
  z-session artifacts (z3/z5/z6/z7ask.txt): the migration carried the
  D-era evidence workspace into the main app intact.
- **Turn 2 (write):** "create a file called go-promotion.txt …" → the
  write card rendered (Always/Deny/Allow once), approved Allow-once, and
  the chain closed: app log `file changes: +1 -0 across 1 file(s)` and
  files/workspace/go-promotion.txt exists with `hello`, written by the
  agent.
- Multi-provider keys verified live the same hour: settings re-bootstrapped
  on openrouter (290 models, dots-3-note-preview seated) then on
  opencode-go (28 models, beam-free) — both keys stored and switchable.
- Findings from the cycle, recorded not fixed: the migrated session cwd
  still carries v099-absolute paths (which remain valid only while the
  experiment app is installed); a one-shot `HTTP 409 "client not attached"`
  during reconnect self-healed on the next read; two consecutive bash
  permission asks fired 10 s apart during a declined-then-retried call.

**Promotion therefore reads DONE-DONE.** Remaining follow-ups live in the
standing backlog (stopBlocking soak, DeepSeek/GLM with the re-keyed
subscription key, v099-app deletion decision).

Verdict so far: **v0.99.0 + OpenCode Go WORKS end to end for entitled
models** — with the entire app-side model layer now split correctly(list/chat/plan), and the last open question (status codes on rejected
models) now ANSWERED: rejected taps name precise verdicts, the
PAYMENT_REQUIRED class carries the workspace-key guidance inline (2026-11-01;
the official mechanics: one key = one workspace, billing per that
workspace's budget), and `deepseek-v4.1-flash`'s 402 on the Go base is the
key's workspace lacking the subscription, not an app defect.

**PROMOTED 2026-11-01.** The user called it: `main` now bundles the upstream
**v0.99.0** clean engine (fetch pin advanced, checksum
`d18cd1f9…f78c` from the signed checksums.txt), and the model-layer work
from this branch is merged: `Provider.modelsListBase`/`chatBases`, the
plan-walking probe, the app User-Agent, dynamic Go listing (no hardcoded
ids, per the user's directive), and the 402 workspace-key guidance. The
0.98.2+dirty instrumented binary remains the evidence base for the 2026-10
z-sessions; new evidence accretes against 0.99.0. The standard two-turn
on-device verification cycle on the promoted build (ask-card approval +
file-edit diff, plus the standing stopBlocking/backgrounding re-measure)
is the remaining gate to call the promotion finished.
