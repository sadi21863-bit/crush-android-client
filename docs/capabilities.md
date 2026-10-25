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
| Engine lifecycle | Crash/restart, health probing, dynamic thermal tuning |

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
| **File diffs** | Built and JVM-tested (snapshot → tracked files → diff → render), and the collector endpoint is verified running on device — but the only real turn measured so far tracked **zero files**, so the render has never been seen fed by an actual edit |
| **Tool-call timeline** | Tool activity is a per-message summary, not a live view of what the agent is doing turn by turn |
| **Per-turn cost display** | Cost is parsed and shown in the drawer only |
| **Multi-provider** | Plumbing in, 13 ids offered, two verified end to end (`opencode-zen`, `openrouter`) plus `groq` probe-verified but parked as a test target. See [providers.md](providers.md) |
| **Multi-project / workspaces** | One app-private workspace, path-derived id |
| **Attachments / image input** | Text only |
| **Instrumentation tests** | No `androidTest` suite at all |
| **Remote crash reporting** | In-app export only |

## Known defects

Open issues that are implemented-but-wrong, distinct from the gaps above.

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

**Related finding (live-captured):** in ASK mode the engine v0.98.1 never
broadcasts `permission_request` on the events stream - a tool turn blocked in
the ask step published nothing (session events route 404s too). Until the
engine publishes asks upstream, ASK-mode mutating turns hang and the app
times out to "no reply received"; see D12 in project-analysis.md. AUTO-approve
turns run tools end to end and stream a complete turn (proven 2026-10-20).

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
`CONNECTED`. `WorkspaceLease` is used at `WorkspaceManager.kt:132`; the
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

- **401 JVM unit tests**, all passing (as of 2026-10-20).
- Two `MockWebServer`-backed suites assert on real HTTP order and on requests
  actually reaching the server — the latter exists because a method that throws
  before the call produces a *passing-looking* no-op, which is exactly how
  `deleteSession` failed silently for so long.
- No instrumentation tests. The highest-risk areas — Keystore, device-credential
  lifecycle, coroutine flow hot/cold ordering, child-process behaviour — remain
  device-only.
