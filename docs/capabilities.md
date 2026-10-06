# Chat system capabilities

Status as of 2026-10-04. **120 unit tests, 0 failures.** Single reference device:
Redmi Note 7 Pro, API 29, arm64-v8a.

"Engine" = the bundled Crush `libcrush.so`. "App" = what the Android UI actually
exposes. The gap between those two columns is the roadmap.

---

## Verified working on device

| Capability | Evidence |
|---|---|
| Streaming replies | SSE, `run_complete` + `run_id` match confirmed |
| Free models | `space-bunny-free` live |
| Model picker | Live Zen catalogue, free-first, with cost / context / reasoning levels |
| Multi-turn threads | Sessions reused instead of one-per-send |
| Chat list | Drawer with auto-titles, message count, cost, relative time |
| Reopen chat + history | Replayed from `GET /sessions/{sid}/messages` |
| Delete chat | Confirmation dialog, clears local binding |
| New chat | History preserved |
| Stop mid-stream | Cancels without tearing down the engine |
| App lock | Fingerprint **or** PIN / pattern / password |
| Encrypted API key | AES-256-GCM, Keystore-gated, `auth-legacy` tier on API 29 |
| Crash + event log | `files/app.log` |
| Auto-tuning | Static (RAM/cores) + dynamic (thermal / low-memory / power-saver) |
| Self-healing | Reaped workspace → auto re-attach + one retry |

## Engine supports it, app does not surface it

| Capability | Endpoint | State |
|---|---|---|
| Edit / resend | `prompts/clear`, `messages` | not wired |
| Regenerate | re-POST `messages/user` | not wired |
| Tool-call timeline | `tool_call` parts | parsed, not rendered |
| Thinking / reasoning | `reasoning` parts | parsed, not rendered |
| Per-turn token + cost | session DTO | list only |
| Server-side run cancel | `agent/sessions/{sid}/cancel` | client-side only |
| Context compaction | `config/compact` | not wired |
| Session summarization | `sessions/{sid}/summarize` | not wired |
| Prompt queue | `prompts/queued` | not wired |
| File tracker | `sessions/{sid}/filetracker/files` | not wired |
| Permission prompts | `permissions/grant` | **bypassed by yolo** |
| Ask-user questions | `questions/answer` | not wired |
| MCP servers | 16 endpoints | not wired |
| Skills | `skills/read` | not wired |
| LSP | `lsps/start` | not wired |
| Shell | `sessions/{sid}/shell` | not wired |
| Multi-provider + OAuth | `config/provider-key`, `refresh-oauth` | Zen only. 42 providers live |

## Cannot do at all

- Markdown rendering — replies are plain text
- Syntax highlighting
- Image / file attachments (engine supports; no UI)
- Voice input beyond the IME microphone
- Switching between projects (sessions are workspace-scoped, one workspace)
- Offline use — requires Zen reachable
- Sending while backgrounded
- Session forking — `parent_session_id` exists, no UI
- Search across chats — no endpoint

## Verified only on API 29

The Keystore tier ladder and the authentication path both **change at API 30**:

- API < 30 → `KeyguardManager.createConfirmDeviceCredentialIntent` (tested)
- API >= 30 → `BiometricPrompt` with combined authenticators (**never executed**)

`minSdk` is 26. API 26–28 is likewise unexercised.

---

# Prototype readiness

## Verdict: not ready to hand to friends yet

Three blockers, in order of severity.

### 1. arm64-v8a only — this is the disqualifier

The APK ships a single ABI. On a 32-bit device (`armeabi-v7a`) the app installs
and then **cannot run the engine at all** — no chat, no error worth reading.
Plenty of budget Android phones are still 32-bit.

Either ship `armeabi-v7a` or gate the Play listing and tell testers up front.

### 2. Debug build

`assembleDebug` means `android:debuggable=true`. Anyone with adb can `run-as` the
app and read `files/crush.json`, which stores the provider key **in plaintext by
Crush's design**. Handing a debuggable build to a friend hands them the key.

A release build needs a signing config that does not exist yet.

### 3. `SpikeActivity` is still a launcher entry

The test harness (`CrushSmokeTest`, `FullE2ETest`, fault injection) appears in the
app drawer as a second icon. Confusing at best; it exposes a debug surface at
worst.

## Also outstanding, lower severity

- **Permission mode is `yolo`** — the agent auto-approves its own tool calls.
  Blast radius is limited to the app-private workspace, so it cannot reach a
  friend's photos or messages, but it is the wrong default to ship. Settings
  should expose *ask* vs *auto-approve*.
- **The API key has been exposed twice in tool transcripts** via
  `GET /v1/workspaces`, which embeds `config.providers.*.api_key` in plaintext.
  Redaction tooling exists in `tools/probe-send.ps1` and `probe-sse-dump.ps1`.
  Rotation is still pending.
- **Unverified by a human**: two consecutive sends, PIN unlock, the chat list,
  delete, and history reopen are all unconfirmed end-to-end.
- **No crash reporting.** If a friend's phone fails, we learn nothing unless they
  send `app.log`.

## Minimum bar before handing it over

1. Release-signed build with `debuggable=false`
2. Remove `SpikeActivity` from the launcher
3. Permission mode default changed from yolo to ask
4. One human pass: unlock → send → send again → open list → reopen → delete
5. Decide the ABI question, and say so to testers

## Notes for testers

- Needs Android 8.0+ (API 26); only API 29 is known to work
- arm64 only
- Requires an OpenCode Zen API key, entered on first run
- The key is stored encrypted, but Crush keeps its own plaintext copy in
  app-private storage
