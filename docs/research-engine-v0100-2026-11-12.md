# Crush engine research — v0.99.0 → v0.100.0

**Research date:** 2026-11-12
**Repository verified:** [github.com/charmbracelet/crush](https://github.com/charmbracelet/crush) — public Charm repo, "Glamourous agentic coding for all 💘".
**Scope:** v0.99.0 (2026-10-29) as baseline, v0.100.0 (2026-11-05), and any v0.100.x patch. Primary sources only: GitHub repo, tags/releases, release notes, raw source at tag, official release assets.

## 1. Latest v0.100.x

**Latest v0.100.x is v0.100.0 — and it is also the latest stable release overall.**

| Item | Value |
|---|---|
| Tag | `v0.100.0` |
| Published | `2026-11-05T17:47:22Z` (release page shows 05 Nov 17:47) |
| Tag commit | `c7854d54a8fc2eef37f5c31ee4e829318b1ea13c` |
| GitHub "Latest" badge | Yes |
| v0.100.x patch releases | **None.** No `v0.100.1`, `v0.100.2`, etc. as of 2026-11-12 |

The release list is `nightly` (pre-release, 2026-11-11) → `v0.100.0` → `v0.99.0`; the tags page likewise goes `nightly` → `v0.100.0` → `v0.99.0`, with no v0.100.x patch in between.

**Android arm64 artifact: YES.**

- `crush_0.100.0_Android_arm64.tar.gz`
- Size: 27,120,315 bytes (~25.9 MiB)
- Uploaded: `2026-11-05T17:47:17Z`
- SHA-256: `cdb15f23393c7e334b059a37d2c41a12fcb5f4d02d6e940795b7707e4b0afeaa`
- Direct URL: https://github.com/charmbracelet/crush/releases/download/v0.100.0/crush_0.100.0_Android_arm64.tar.gz

Android APKs are also published: `crush_0.100.0_aarch64.apk` (26,329,427 bytes, sha256 `ccff4776…d613c`), plus `armv7`, `x86`, and `x86_64` APKs. Use the `Android_arm64.tar.gz` if the app expects the tar.gz layout.

Sources: [v0.100.0 release](https://github.com/charmbracelet/crush/releases/tag/v0.100.0), [tags page](https://github.com/charmbracelet/crush/tags), [expanded assets](https://github.com/charmbracelet/crush/releases/expanded_assets/v0.100.0), [releases API](https://api.github.com/repos/charmbracelet/crush/releases?per_page=8).

## 2. Changelog between v0.99.0 and v0.100.0

There are no v0.100.x patches, so v0.100.0 is the complete delta. Full commit-level notes: https://github.com/charmbracelet/crush/releases/tag/v0.100.0

### Headline features

- **Subagents.** Crush can dispatch detached subagents that run in the background; the parent can check status, send mid-run follow-ups, stop them, or move one to a different model. Each subagent gets a name, its own session, and its report is delivered back as a message. New commits: `965d1a7`, `938fef2`, `4efcd2c`, `d4430bc`, `d11509b`.
- **Sysadmin permission mode.** A third mode beyond normal/yolo that auto-approves every request and also drops the exec-time dangerous-command block list (`44cb8e3`, `9910443`).
- **Configurable dangerous commands.** New `permissions safe`, `permissions block`, and `permissions unblock` crushrc commands (`526da24`, `46c7bc4`). Yolo mode no longer prompts for dangerous commands but still applies the exec-time block list; sysadmin drops that list too (`c42442d`).
- **Integrated GPG pinentry and SSH prompts.** GPG passphrase/PIN prompts render as a native Crush dialog, and SSH password/passphrase/confirmation prompts can be answered inside Crush (`a5a7820`, `16b7ee1`).
- **Subagent UX.** Progress in transcript/header/quit dialog, compact queued reports, named actions, sidebar rows (`49e7723`, `36bf101`, `573e1fb`, `ae4f92c`, `c55f231`).

### Notable fixes

- Subagent lifecycle and races: end the turn while subagents work, queue reports behind user messages, keep reports on cancel, stop follow-ups racing completion, forget finished subagents past a cap, read parent model without racing agent build.
- Bash/shell hardening: parse-based auto-approval instead of prefix matching; reject backslash escapes; judge command chains by their parts; detect chaining by parsing; apply block list to scripts/builtins; cap in-memory output; case-insensitive dangerous commands; clearer danger descriptions.
- Permissions: harden dangerous-command checks, scope session approvals to what was approved, report the permission mode actually enforced.
- Server/pubsub: keep only newest pending update per message for slow subscribers; don't show an error for diagnostics.
- Agent/provider: surface truncated/provider-stopped responses; use routed model pricing for Prism session costs; share one AWS SSO login; stop title generation outliving its run; fix Anthropic replay; fix parallel-tool-call crash; OAuth re-sign-in fixes.
- UI: header branch color, subagent sidebar labels, markdown tool-output tail, keybind hints under sidebar, bash tool display.

### Breaking / compatibility changes (source-derived)

The vendor does **not** label these as breaking in the release notes, but the v0.99.0→v0.100.0 source diff shows them:

1. **Workspace payload changed `yolo` → `permission_mode`.** In v0.99.0 `proto.Workspace` had `YOLO bool json:"yolo"`. In v0.100.0 it has `PermissionMode WorkspacePermissionMode json:"permission_mode"` with values `normal`, `yolo`, `sysadmin`. The handler decodes with plain `json.Decoder` (unknown fields ignored), so an old client that still sends `{"yolo":true}` gets `permission_mode == ""`, which maps to **normal** — i.e. yolo is silently lost. Clients must send `"permission_mode":"yolo"`.
2. **Permission endpoints replaced.** `GET /v1/workspaces/{id}/permissions/skip` and `POST /v1/workspaces/{id}/permissions/skip` are gone. v0.100.0 adds `POST /v1/workspaces/{id}/permissions/mode` accepting `{"mode":"normal"|"yolo"|"sysadmin"}`.
3. **New SSE payload types.** `permission_mode_changed`, `pinentry_prompt_request`, `pinentry_prompt_notification`, `ssh_prompt_request`, `ssh_prompt_notification`. The `/v1/workspaces/{id}/events` SSE envelope itself is unchanged (`{"type": "...", "payload": {...}}`).
4. **New message content part.** `sub_agent_report` with fields `label`, `output`, `failed`, `model`. `proto.SubAgentReport` is new; old message parts are unchanged.
5. **New endpoints for prompts.** `POST /v1/workspaces/{id}/pinentry/answer`, `.../pinentry/cancel`, `.../ssh/answer`, `.../ssh/cancel`.

### Config file semantic changes (crushrc / crush.json)

New config fields, no removals:

- `permissions.blocked_commands []string` — extra commands treated as dangerous.
- `permissions.allowed_commands []string` — remove commands from the dangerous list, including built-ins.
- `permissions.safe_commands []string` — exact command lines that skip the prompt.
- `options.pinentry_integrated *bool` (default true).
- `options.pinentry_cache_timeout *int` (seconds; default 0).

New crushrc builtins: `permissions safe`, `permissions block`, `permissions unblock` (`internal/shellconfig/permissions.go`). `permissions allow` and `permissions deny` are unchanged.

Docs gap: `docs/config/README.md` at v0.100.0 documents the pinentry options but **not** `safe`/`block`/`unblock`; those are only in the release notes and source. JSON `crush.json` remains supported but deprecated; the docs say new options are added only to Bash config.

Sources: [release notes](https://github.com/charmbracelet/crush/releases/tag/v0.100.0), [v0.100.0 config.go](https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/config/config.go), [v0.99.0 config.go](https://raw.githubusercontent.com/charmbracelet/crush/v0.99.0/internal/config/config.go), [v0.100.0 endpoints.go](https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/server/endpoints.go), [v0.99.0 endpoints.go](https://raw.githubusercontent.com/charmbracelet/crush/v0.99.0/internal/server/endpoints.go), [v0.100.0 proto.go](https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/proto/proto.go), [v0.100.0 message.go](https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/proto/message.go), [v0.100.0 pubsub events](https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/pubsub/events.go), [v0.100.0 config docs](https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/docs/config/README.md).

### Tool-surface changes

- Four new built-in tool IDs: `agent_dispatch`, `agent_send`, `agent_status`, `agent_stop`.
- No built-in IDs removed.
- `agent` remains, but the coordinator's `effectiveToolNames` keeps `agent` for interactive top-level runs and the four detached tools for non-interactive runs (subagents get neither). `question` remains interactive-only.
- The task subagent's read-only set now also includes `bash`; the plan agent's list now includes both `agent` and the four detached delegation IDs, with `effectiveToolNames` keeping the style suited to the run mode. Subagents get neither delegation style.

### Session / workspace storage changes

- **No new DB migrations.** v0.99.0 and v0.100.0 both have the same 12 files under `internal/db/migrations/`, ending at `20260928000001_add_mcp_enabled_servers.sql`.
- **Session schema unchanged.** `proto.Session` and the `session` table shape are identical between tags; `parent_session_id`, `channel`, todos, and cost fields already existed.
- Subagent work is stored as **child sessions** using the existing `parent_session_id`; subagent reports are message parts, not a new table.
- Behavioral storage fix: subagent prompts are kept out of the prompt history (`1662cbd`).
- Workspace permission mode is a **runtime override** (`config.RuntimeOverrides`), never persisted; no new workspace state file or table.

Sources: [v0.99.0 tree](https://api.github.com/repos/charmbracelet/crush/git/trees/v0.99.0?recursive=1), [v0.100.0 tree](https://api.github.com/repos/charmbracelet/crush/git/trees/v0.100.0?recursive=1), [v0.100.0 store.go](https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/config/store.go), [v0.100.0 proto/session.go](https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/proto/session.go).

## 3. Built-in tool IDs in v0.100.0

Authoritative list: `allToolNames()` in `internal/config/config.go` at v0.100.0. It contains 33 IDs:

```
agent
agent_dispatch
agent_send
agent_status
agent_stop
bash
crush_info
crush_logs
job_output
job_kill
download
edit
multiedit
lsp_diagnostics
lsp_references
lsp_restart
lsp_symbols
lsp_definition
lsp_call_hierarchy
lsp_rename
lsp_replace_symbol
fetch
agentic_fetch
glob
grep
ls
question
sourcegraph
todos
view
write
list_mcp_resources
read_mcp_resource
```

**Diff vs v0.99.0:**

- **NEW (+4):** `agent_dispatch`, `agent_send`, `agent_status`, `agent_stop`
- **REMOVED:** none
- Unchanged count: 29 → 33.

Caveats: LSP tools are only attached when an LSP is configured or `auto_lsp` is enabled; `list_mcp_resources`/`read_mcp_resource` only when MCP servers are configured; `question` and `agent` are interactive-only; the four new delegation tools are non-interactive-only at the top level.

Sources: [v0.100.0 config.go](https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/config/config.go), [v0.99.0 config.go](https://raw.githubusercontent.com/charmbracelet/crush/v0.99.0/internal/config/config.go), [v0.100.0 coordinator.go](https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/agent/coordinator.go).

## 4. Workspace-root `crushrc` loading

**Verdict: DENIED — no change to the documented crushrc discovery/execution behavior in v0.100.0.** The specific "executed twice per workspace attach" count is **NOT FOUND IN PRIMARY SOURCES**.

Evidence:

- The v0.100.0 release notes contain no mention of `crushrc`, workspace-root config loading, or config sourcing. The only config-related commit is `46c7bc4` ("let a project configure the commands it runs all day"), which adds the permission command lists.
- `docs/config/README.md` at v0.100.0 still documents project-root config with this priority: (1) `./.crushrc`, (2) `./crushrc`, (3) `$XDG_CONFIG_HOME/crush/crushrc`. It says the file "runs when Crush starts", is Bash, and merges with JSON with crushrc winning in the same directory.
- A diff of `internal/config/load.go` between v0.99.0 and v0.100.0 shows **no changes** to `lookupConfigs`, `loadFromConfigPaths`, `shellConfigSibling`, or `isShellConfig`.
- A diff of `internal/backend/backend.go` shows **no changes** to `CreateWorkspace`'s `config.Init` call; the only changes are the permission-mode field/hunks.
- `config.Init` calls `config.Load` once. `Load` → `loadFromConfigPaths` executes each discovered shell config via `shellconfig.LoadShellConfig` once per path (30 s timeout, cwd = directory of the crushrc, embedded shell interpreter).
- A duplicate `POST /v1/workspaces` for an already-hosted path returns the existing workspace and does **not** reload config. The other path that re-executes crushrc is explicit `ReloadConfig` (SIGHUP/explicit reload), which re-reads the server and every hosted workspace config.

Practical conclusion for the Android app: v0.100.0 does not intentionally change project-root crushrc sourcing. If the app currently observes exactly two executions per attach, that is an implementation detail not documented by the vendor; the only source-level ways to get two executions are (a) workspace creation plus an explicit config reload, or (b) two separate `Load` calls. Add an integration test against the actual `crush_0.100.0_Android_arm64.tar.gz` rather than relying on the undocumented count.

Sources: [v0.100.0 config docs](https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/docs/config/README.md), [v0.99.0 config docs](https://raw.githubusercontent.com/charmbracelet/crush/v0.99.0/docs/config/README.md), [v0.100.0 load.go](https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/config/load.go), [v0.99.0 load.go](https://raw.githubusercontent.com/charmbracelet/crush/v0.99.0/internal/config/load.go), [v0.100.0 init.go](https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/config/init.go), [v0.100.0 shellconfig load.go](https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/shellconfig/load.go), [v0.100.0 backend.go](https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/backend/backend.go).

## 5. Documented migration steps

**v0.100.0-specific vendor migration guide: NOT FOUND IN PRIMARY SOURCES.** The v0.100.0 release notes have no "Migration", "Upgrade", or "Breaking changes" section.

What the vendor does document:

- **v0.99.0 release notes:** Catwalk v3 changed provider `type` semantics (`completions`, `responses`, `messages`), and "your config is migrated automatically when it loads"; legacy values `openai`, `openai-compat`, `anthropic` keep working. Since the upgrade ladder starts at v0.99.0, this migration is already behind you.
- **`docs/config/README.md`:** JSON `crush.json` is still supported but deprecated; new options are added only to Bash config. "If you're migrating from the old JSON format, you can also ask Crush to convert the config for you." `crushrc` projects/global precedence is documented there.
- No `crush migrate` command is documented in the v0.100.0 config docs.

Derived client-side migration steps (not vendor-documented, from the source diff):

1. On `POST /v1/workspaces`, send `"permission_mode": "normal"|"yolo"|"sysadmin"` instead of `"yolo": true`. Old `yolo` is ignored and becomes normal.
2. Replace `GET`/`POST /v1/workspaces/{id}/permissions/skip` with `POST /v1/workspaces/{id}/permissions/mode`.
3. Handle new SSE payloads: `permission_mode_changed`, `pinentry_prompt_request`, `pinentry_prompt_notification`, `ssh_prompt_request`, `ssh_prompt_notification`.
4. Handle the new `sub_agent_report` message part; render it or ignore it safely.
5. If tool allow/deny lists are pinned, add `agent_dispatch`, `agent_send`, `agent_status`, `agent_stop`.
6. Keep project-root `crushrc` as-is; verify the execution count on the real artifact (see §4).

Sources: [v0.99.0 release notes](https://github.com/charmbracelet/crush/releases/tag/v0.99.0), [v0.100.0 config docs](https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/docs/config/README.md), [v0.100.0 endpoints.go](https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/server/endpoints.go), [v0.100.0 proto.go](https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/proto/proto.go).

## Sources

### Releases, tags, metadata

- https://github.com/charmbracelet/crush
- https://github.com/charmbracelet/crush/releases
- https://github.com/charmbracelet/crush/releases/tag/v0.100.0
- https://github.com/charmbracelet/crush/releases/tag/v0.99.0
- https://github.com/charmbracelet/crush/tags
- https://github.com/charmbracelet/crush/releases.atom
- https://github.com/charmbracelet/crush/releases/expanded_assets/v0.100.0
- https://api.github.com/repos/charmbracelet/crush/releases?per_page=8
- https://api.github.com/repos/charmbracelet/crush/releases/tags/v0.100.0
- https://api.github.com/repos/charmbracelet/crush/git/trees/v0.100.0?recursive=1
- https://api.github.com/repos/charmbracelet/crush/git/trees/v0.99.0?recursive=1

### v0.100.0 source and docs

- https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/config/config.go
- https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/config/load.go
- https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/config/init.go
- https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/config/store.go
- https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/config/scope.go
- https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/backend/backend.go
- https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/backend/config.go
- https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/server/endpoints.go
- https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/server/server.go
- https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/server/proto.go
- https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/server/events.go
- https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/proto/proto.go
- https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/proto/permission.go
- https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/proto/message.go
- https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/proto/session.go
- https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/pubsub/events.go
- https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/shellconfig/load.go
- https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/shellconfig/permissions.go
- https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/agent/coordinator.go
- https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/agent/agent_tool.go
- https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/agent/agent_dispatch.go
- https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/internal/agent/tools/tools.go
- https://raw.githubusercontent.com/charmbracelet/crush/v0.100.0/docs/config/README.md

### v0.99.0 source and docs (for diffing)

- https://raw.githubusercontent.com/charmbracelet/crush/v0.99.0/internal/config/config.go
- https://raw.githubusercontent.com/charmbracelet/crush/v0.99.0/internal/config/load.go
- https://raw.githubusercontent.com/charmbracelet/crush/v0.99.0/internal/backend/backend.go
- https://raw.githubusercontent.com/charmbracelet/crush/v0.99.0/internal/server/endpoints.go
- https://raw.githubusercontent.com/charmbracelet/crush/v0.99.0/internal/proto/proto.go
- https://raw.githubusercontent.com/charmbracelet/crush/v0.99.0/internal/proto/message.go
- https://raw.githubusercontent.com/charmbracelet/crush/v0.99.0/internal/proto/session.go
- https://raw.githubusercontent.com/charmbracelet/crush/v0.99.0/internal/pubsub/events.go
- https://raw.githubusercontent.com/charmbracelet/crush/v0.99.0/docs/config/README.md

### Consulted but not used

- `https://api.github.com/repos/charmbracelet/crush/contents/internal/tools?ref=v0.100.0` — 404; tools live under `internal/agent/tools`.
- `https://github.com/charmbracelet/crush/tree/v0.100.0/internal` — timed out; used the Git tree API instead.
