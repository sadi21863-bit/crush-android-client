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
| Key model rejection (deepseek-v4.1-flash) | **PENDING exact status** | probed three times, "did not answer a probe" — a diagnostic build now logs verdict + per-base detail; hypothesis from the Go doc: workspace privacy defaults region-block DeepSeek ("allowed regions are Europe & United States") for a user outside them |

Findings that are NOT app bugs:
- the unknown engine event `client_session` is a NEW 0.99.0 event type the
  router skips silently (observed once during attach; it is additive, not
  fatal — worth an additive handler later);
- Go's own model endpooints demand three things the CASUAL integrations miss
  (plan-family list base, full /openai/v1 chat path, non-SDK User-Agent);
  each failure mode was verified independently: wrong-plan list (payg
  catalogue), short chat path (404 plan-miss), and the UA filter (python
  urllib 403 vs browser 200 on the same URL).

Verdict so far: **v0.99.0 + OpenCode Go WORKS end to end for entitled
models** — with the entire app-side model layer now split correctly
(list/chat/plan), and the last open question (status codes on rejected
models) is one diagnostic tap away.
