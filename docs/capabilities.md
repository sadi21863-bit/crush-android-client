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
| Tool-call visibility | Collapsed rows with name, state, arguments, output. **Wire format unconfirmed** — see below |
| Markdown rendering | Hand-rolled, streaming-safe. Fenced code, inline code, headings, lists, quotes, rules, emphasis, links |
| In-app diagnostics | Device/ABI/engine status, archived crash, log tail, copy/share report |
| Settings | Key replace/delete, permission mode, diagnostics entry point |
| Prewarm | Eager engine start when enabled; lazy on first keystroke otherwise |

## Not implemented

These are the real gaps, ordered by how much they matter for a coding agent.

| Gap | Impact |
|---|---|
| **File diffs** | The agent writes files; the user sees prose describing the change, not the change. Largest gap against Claude Code / Codex |
| **Tool-call timeline** | Tool activity is a per-message summary, not a live view of what the agent is doing turn by turn |
| **Per-turn cost display** | Cost is parsed and shown in the drawer only |
| **Multi-provider** | Only `opencode-zen`. The engine advertises 42 providers |
| **Multi-project / workspaces** | One app-private workspace, path-derived id |
| **Attachments / image input** | Text only |
| **Instrumentation tests** | No `androidTest` suite at all |
| **Remote crash reporting** | In-app export only |

## Known defects

Open issues that are implemented-but-wrong, distinct from the gaps above.

### Tool-call wire format is unconfirmed

The engine emits `tool_call` parts and the client parses them, but the exact
field names were never read off a real tool-using turn. The only fixture
available was `{"name":"read"}`.

`ToolActivityParser` therefore reads each field through a list of candidate wire
names (`WIRE_NAME = ["name", "tool", "tool_name", "toolName"]`, and similarly
for arguments, state, output, error) and returns `null` when none match. A
`runCatching`-guarded log records the **key names** the engine actually sends,
once per distinct shape, so the list can be corrected without guessing again.

An unrecognised state maps to `UNKNOWN`, never `COMPLETED`. Claiming a tool
finished when it may not have is the same class of lie as reporting a Stop that
never happened.

### Delete treats transport failure as success

`CrushApi.deleteSession` converts a dropped connection, `InterruptedIOException`,
`EOFException`, and anything containing "workspace not found" into success, on
the reasoning that "a session cannot outlive its workspace".

That reasoning is wrong. Sessions are persistent engine state keyed by the
workspace's cwd; the workspace is only an attachment handle. So a timeout or a
reaped workspace does **not** prove the session was deleted, and the conversation
can reappear in the drawer later.

Correct behaviour: re-acquire the workspace, retry, and only mutate local state
once the server confirms. The workspace re-acquisition is now in place; the
"swallow as success" part is not yet fixed.

### The engine API is unauthenticated and cross-app reachable

Confirmed: `GET /v1/version` and `GET /v1/workspaces` return `HTTP 200` to a
different app UID (`adb shell`, uid 2000) with no credentials. Android's
loopback is shared by all apps on the device, so binding to `127.0.0.1` excludes
the network but not local apps.

Not confirmed: command execution or key exfiltration. Workspace creation
requires a registered `client_id` and an unregistered one is refused, so no
shell command could be attached during testing. Treat as a known architectural
limitation, not a proven RCE.

### First-launch lock bypass

On a fresh install the first session opens without a lock prompt, because
`AppLockPolicy` returns `OPEN` when there is no stored ciphertext. Later cold
starts do prompt. Whether onboarding itself warrants a gate is a product
decision, not settled.

### Redundant workspace-lease scaffolding

`WorkspaceLease`, `reattachIfStale`, and the force-retry paths in the send loop
all predate the long-lived SSE connection, which removes the condition they
existed to handle. They are now redundant and remain as dead scaffolding. Left
in place deliberately until the connection has proven stable, to avoid removing
a fallback that is still the active path when the connection is not `CONNECTED`.

## Engine facts this project depends on

All verified on-device against Crush v0.97.1, not read from docs.

| Fact | Detail |
|---|---|
| Workspace lifetime | Reaped after ~30s with **no attached client**. Not a fixed interval — it is attachment-based |
| Workspace identity | Keyed by resolved cwd. The id is an attachment handle that changes on re-attach |
| Session lifetime | Persistent engine state, independent of the workspace handle |
| SSE | Only `data:` lines. No `event:`, `id:`, or heartbeat |
| SSE resume | Impossible (no `id:`). Reconnect and resync from `/messages` |
| Message updates | Whole message republished, not deltas |
| Event envelope | `{"type":…,"payload":{"type":…,"payload":{…}}}` |
| Auth | None |
| Provider id | `opencode-zen` |
| `api_key` | Must be sent **bare**; a `NAME=` prefix yields 401 |
| `scope` | An integer (0 = global), not a string |
| `/v1/health` | 200 with an **empty body**; use `/v1/version` |

## Testing posture

- **213 JVM unit tests**, all passing.
- Two `MockWebServer`-backed suites assert on real HTTP order and on requests
  actually reaching the server — the latter exists because a method that throws
  before the call produces a *passing-looking* no-op, which is exactly how
  `deleteSession` failed silently for so long.
- No instrumentation tests. The highest-risk areas — Keystore, device-credential
  lifecycle, coroutine flow hot/cold ordering, child-process behaviour — remain
  device-only.
