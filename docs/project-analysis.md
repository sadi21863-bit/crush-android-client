# Project Analysis — Bugs, Gaps & Capability Plan

Written 2026-10-03. Companion to `docs/ui-spec.md` (that covers *how it looks*;
this covers *what it does* and *what is broken*).

## 1. Honest scorecard

| Area | State |
|---|---|
| Engine lifecycle | Proven (boot, supervise, recover, hang-detect) |
| Crush protocol client | Working, 40 unit tests |
| Streaming | Proven end-to-end (`HELLO FROM CRUSH`) |
| Key security | Encrypted + **persistence proven** |
| Auto-tuning | Working, user overrides visible |
| Unit tests | 54 green |
| Chaos L1/L2 | 10 + 6 scenarios |
| **Chat send (UI)** | **Fixed but NEVER verified end-to-end** |
| **Markdown rendering** | **Absent** |
| Session history UI | Absent |
| Unlock flow in chat | Broken (wrong message, no prompt) |

**One functional path is unverified. Everything else is foundational.**

## 2. Bugs

### B1 — `send()` crash (fixed, UNVERIFIED)
`launch { api.send(...) }` created an independent coroutine, so `HTTP 404:
workspace not found` escaped the surrounding `try/catch` and killed the process
with no visible error. Now sequential + `runCatching`, and a 404 clears the
cached workspace id so the next turn re-bootstraps.

**Lesson:** a `launch{}` inside a `try` is a lie — the catch cannot see it.

### B2 — Chat reports "no API key" when the key exists
Routing correctly sends the user to Chat, but `ChatViewModel` reads the key, gets
null because an unlock is needed, and reports it as *missing*. Copy is wrong and
there is no way to unlock from the chat screen. **The app is effectively unusable
without visiting Settings.**

### B3 — Dead code that can mislead
`ZenApi.kt` / `ZenRepository.kt` are the abandoned direct-API architecture.
`ZenModel` collided with the live type (renamed `LiveZenModel`). Nothing calls
them; they invite future confusion. **Delete.**

### B4 — `SpikeActivity` is still a launcher entry
Two `LAUNCHER` activities ship (`MainActivity` + `SpikeActivity`). A user can
land on the diagnostic screen. Remove the spike from the manifest before release.

### B5 — Engine log path is wrong
`EnginePaths.logFile()` builds `cache/server-tcp--127.0.0.1/crush.log`, but
Crush actually writes to `cache/server-tcp___127.0.0.1_<port>/crush.log` (we
found the real logs there). The helper points nowhere.

## 3. Gaps

### G1 — No Markdown renderer (biggest quality gap)
Assistant replies are plain text. Code fences, bold, lists, tables all render
raw. See `ui-spec.md` §P0.1.

### G2 — No session history UI
`listSessions` exists in the client; there is no drawer, no resume, no rename.
A chat app without history feels broken after one conversation.

### G3 — No tool-call rendering
Crush emits `tool_call` parts and we parse the type, but the UI shows nothing.
The user cannot see what the agent did — fatal for an *agent* app that will
edit their files.

### G4 — No permission prompt UI
We auto-allow everything (`yolo`). For an app that touches a user's files that
is the **wrong default**. The API has grant/deny; the UI should ask.

### G5 — No stop-generation verification
The Stop button exists but has never been pressed in a test.

### G6 — `AppLog` writes are unsynchronized and unbounded-ish
Fine for diagnostics; must not ship enabled at INFO in release.

## 4. Capability plan — grouped by cost

The engine exposes **68 endpoints**. Grouped: `mcp` 16, `agent` 12, `config` 8,
`sessions` 6, `lsps` 4, `project` 3, `questions` 2, `permissions` 2,
`filetracker` 2, `skills` 2, plus singles.

### Tier 1 — Cheap (client exists, UI missing)
| Feature | Endpoint(s) | Notes |
|---|---|---|
| Session list / resume / rename | `GET/POST /sessions`, `PATCH` | Client exists |
| Stop generation | `POST /agent/sessions/{sid}/cancel` | Built, untested |
| Model picker | `config/model`, `providers` | Built, untested |
| Regenerate reply | resend prompt | No endpoint needed |
| Edit & resend | `DELETE` session + resend | No endpoint needed |

### Tier 2 — Moderate (needs new UI + error handling)
| Feature | Endpoint(s) | Notes |
|---|---|---|
| **Tool-call timeline** | part `type:"tool_call"` | **Highest value** — see agent activity |
| **Permission prompts** | `permissions/grant`, `/questions/answer` | Correctness + trust |
| Markdown + code blocks | — | UI-spec P0.1 |
| Diff viewer | part types | Shows what changed on device |
| Attachments / images | part types | Model-dependent |

### Tier 3 — Expensive (scope decisions)
| Feature | Endpoint(s) | Reality check |
|---|---|---|
| File browser | `filetracker`, `project` | Needs scoped storage / SAF on modern Android |
| MCP client UI | `mcp/*` (16) | Full MCP config UI is a project in itself |
| LSP status | `lsps/*` | On-device language servers are impractical |
| Shell access | `agent/sessions/{sid}/shell` | **Dangerous** — do not ship casually |
| Session summarize/compact | `agent/.../summarize`, `config/compact` | Nice for long chats |

### Explicitly out of scope
- **LSP** — running language servers on a phone is not realistic.
- **Shell** — arbitrary command execution from a chat app is a security
  decision, not a feature. Requires explicit thought, not a checkbox.

## 5. Security posture (current)

| Item | State |
|---|---|
| Key at rest | AES-256/GCM, Keystore master key ✅ |
| Key persistence | Proven across auth-window expiry ✅ |
| Backups | Disabled + extraction rules ✅ |
| `/sdcard` plaintext key | Deleted, code removed ✅ |
| `crush.json` (Crush's own) | **Plaintext by Crush's design** ⚠️ |
| `GET /config` returns raw key | Must never be logged ⚠️ |
| Real key still in repo-local file | **Rotate before release** ⚠️ |
| Permissions default | yolo (auto-allow) ⚠️ |

Two items need attention before any release: **rotate the key**, and **decide the
permission default**. Auto-allowing an agent that can edit files is not
defensible in a shipped app.

## 6. Recommended order

1. Verify `send()` (needs the phone)
2. Fix B2 — unlock prompt in chat
3. Markdown renderer (G1)
4. Tool-call timeline (G2/Tier 2) — *this is what makes it feel like an agent*
5. Permission prompts (G4)
6. Session history drawer (G2)
7. Delete dead code (B3), fix B4/B5
8. Decide permission default + rotate key

**Do not** start Tier 3. The 16 MCP endpoints and shell access are traps that
will consume the project without making the core chat better.