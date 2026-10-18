# P1 Item 1 — Attack-Surface Map: Embedded Crush Engine HTTP API

**Generated:** 2026-10-17 · **Engine:** libcrush.so v0.98.1 (static aarch64 PIE, bundled in `app/src/main/jniLibs/arm64-v8a/`) · **Status:** see footer matrix.

> **Evidence note.** This document is the deliverable for **audit item P1.1** (loopback
> exposure of the embedded Crush engine). Every endpoint fact below was taken from the
> pinned wire spec `spec/crush-openapi.json` (68 paths / 76 operations — count verified
> programmatically), cross-checked against how the app actually calls the engine
> (`CrushApi.kt`, `CrushConnection.kt`). No endpoint name appears here that is not in the
> pinned spec. This is a static-analysis map; nothing here demonstrates a live exploit.

## 1. Scope and attacker model

**Scope.** The engine's loopback HTTP/SSE listener only. The app's separate client for
external provider APIs (`ZenModelsApi.kt` → `GET <provider>/models`,
`POST <provider>/chat/completions` with a Bearer key) talks to internet hosts, not to the
engine listener, and is **out of scope** here.

**Attacker model.** A sibling Android app running on the same device:

- Runs as a **different app UID**; **no root**, no `run-as`, no adb.
- Holds (or does not need) the **`INTERNET` permission** — any installed app can request it.
- Android does **not** partition loopback: a connection to `127.0.0.1:<port>` from any
  UID is accepted by the kernel; there is no per-UID firewall on loopback by default.
- The engine binds an **ephemeral TCP port on 127.0.0.1** and **v0.98.1 ships NO
  authentication layer** — recorded as external audit finding **F01**
  ("Loopback is unauthenticated across app UIDs", `BASELINE.md` §7). `CrushApi.kt`'s own
  header concedes this: *"The engine is unauthenticated, so this must only ever be
  pointed at 127.0.0.1. Binding Crush to a routable interface would expose an agent
  with shell execution to the network."*
- Consequently **every** operation below is invocable by the attacker with zero
  credentials, once the port is known. The spec paths are relative to the `/v1` mount
  (the app addresses `http://127.0.0.1:PORT/v1/...`); the table shows the `/v1` prefix.

## 2. RW severity classes

| Class | Meaning |
|---|---|
| **READ-SENSITIVE** | Returns keys, config, or user code/prompts (chat history incl. inlined code, queued prompt text, skill file bytes, diagnostics quoting source). |
| **READ-INVENTORY** | Metadata only (session lists, provider catalogue, model info, version, flags). |
| **WRITE-CRITICAL** | Changes security state (provider key set, config set/remove, refresh-oauth, permissions/skip, project/init). |
| **WRITE-AGENT** | Drives the agent on the user's real workspace (send prompt, agent lifecycle, current-session, session create/edit/delete, question answering). |
| **TOOL-EXEC** | Endpoints with execution semantics (shell, summarize, queued-prompt edits, LSP/MCP process and tool controls). |
| **SUBSCRIBE** | SSE event stream — whole conversation, tool arguments, file changes. `client_id` acts as a capability token that anyone can forge. |
| **INDEX** | Health/version fingerprinting, client retirement, engine control (shutdown). |

`*` after a verb = an operation the app actively uses (20 distinct paths; see §3 notes).

## 3. Endpoint map — all 68 paths / 76 operations

Multi-verb paths are grouped into one row; per-verb classes are shown as `VERB: CLASS`.

| # | Path (`/v1` mount) | Verb(s) | RW class | One-line exfiltration / damage effect |
|---|---|---|---|---|
| 1 | `/clients/{client_id}` | DELETE | INDEX | Retires a client and releases every claim it holds — detaches the real app's stream (DoS). |
| 2 | `/config` | GET | READ-SENSITIVE | Server-level config tree — same config family that carries provider credentials and env. |
| 3 | `/control` | POST | INDEX | Accepts `shutdown` / `shutdown_if_idle` — remote kill of the user's engine (DoS; also muddies forensics). |
| 4 | `/health` | GET* | INDEX | Liveness beacon; confirms a live engine to attack. |
| 5 | `/version` | GET* | INDEX | Discloses version/commit/go-version/platform — target fingerprinting. |
| 6 | `/workspaces` | GET*+POST* | GET: READ-SENSITIVE / POST: WRITE-CRITICAL | GET lists workspaces whose schema embeds `config.Config` + `env` per workspace; POST creates a workspace with attacker-chosen `yolo`, `env`, `config`, and `path` — aims the agent at an arbitrary directory. |
| 7 | `/workspaces/{id}` | GET / DELETE | GET: READ-SENSITIVE / DELETE: WRITE-CRITICAL | GET returns the workspace object incl. config/env and the user's real project path; DELETE destroys the workspace the app depends on. |
| 8 | `/workspaces/{id}/agent` | GET*+POST* | GET: READ-INVENTORY / POST: WRITE-AGENT | GET discloses readiness/model; **POST injects an attacker prompt that the agent executes on the user's real workspace** (202 Accepted; run survives client disconnect). |
| 9 | `/workspaces/{id}/agent/default-small-model` | GET | READ-INVENTORY | Discloses the configured small model. |
| 10 | `/workspaces/{id}/agent/init` | POST* | WRITE-AGENT | (Re)initializes the agent coordinator — arms the agent so attacker prompts are accepted. |
| 11 | `/workspaces/{id}/agent/main` | POST* | WRITE-AGENT | Switches the active agent (e.g. coder ↔ plan), hijacking agent behaviour mid-life. |
| 12 | `/workspaces/{id}/agent/sessions/{sid}` | GET | READ-INVENTORY | Session metadata incl. title, todos, cost — todo text can leak user intent. |
| 13 | `/workspaces/{id}/agent/sessions/{sid}/cancel` | POST* | WRITE-AGENT | Kills the live run mid-turn (sabotage; also the only sanctioned stop). |
| 14 | `/workspaces/{id}/agent/sessions/{sid}/prompts/clear` | POST | TOOL-EXEC | Purges the user's queued prompts (queued-edit semantics). |
| 15 | `/workspaces/{id}/agent/sessions/{sid}/prompts/list` | GET | READ-SENSITIVE | Returns queued prompt **text** — the user's typed-but-not-yet-run instructions. |
| 16 | `/workspaces/{id}/agent/sessions/{sid}/prompts/queued` | GET | READ-INVENTORY | Count of queued prompts. |
| 17 | `/workspaces/{id}/agent/sessions/{sid}/shell` | POST | TOOL-EXEC | **Runs an attacker-supplied shell command inside the user's workspace on behalf of the session; stdout + exit code come back in the response.** RCE under the app UID — the single worst row in this table. |
| 18 | `/workspaces/{id}/agent/sessions/{sid}/summarize` | POST | TOOL-EXEC | Forces a summarization run over session content (invokes the model on user material). |
| 19 | `/workspaces/{id}/agent/update` | POST | WRITE-AGENT | Updates agent state for the workspace. |
| 20 | `/workspaces/{id}/config` | GET | READ-SENSITIVE | **Returns the resolved workspace config including the provider `api_key` in plaintext (observed on device 2026-10-17).** One unauthenticated GET = full key theft. See §4. |
| 21 | `/workspaces/{id}/config/compact` | POST | WRITE-CRITICAL | Mutates compact-mode config (config-write family; low impact but unauthenticated). |
| 22 | `/workspaces/{id}/config/import-copilot` | POST | WRITE-CRITICAL | Imports Copilot credentials into config **and returns the token** on success. |
| 23 | `/workspaces/{id}/config/model` | POST* | WRITE-CRITICAL | Repoints the `large`/`small` model slots — can steer all traffic to an attacker-chosen model/provider. |
| 24 | `/workspaces/{id}/config/provider-key` | POST* | WRITE-CRITICAL | **Overwrites the provider API key** (key swap, billing hijack, or lockout). Spec declares `api_key` as `array[integer]`; the real wire format is a plain JSON string (Go `json.RawMessage`) — see §4. |
| 25 | `/workspaces/{id}/config/refresh-oauth` | POST | WRITE-CRITICAL | Forces an OAuth token refresh for a provider — credential-state change. |
| 26 | `/workspaces/{id}/config/remove` | POST | WRITE-CRITICAL | Removes an arbitrary config field (can delete the stored key or permissions config). |
| 27 | `/workspaces/{id}/config/set` | POST | WRITE-CRITICAL | Sets an arbitrary config key/value. |
| 28 | `/workspaces/{id}/current-session` | POST* | WRITE-AGENT | Repoints the client's current session — steers where subsequent prompts land (requires `client_id` query param; spec-vs-app drift recorded in BASELINE D1). |
| 29 | `/workspaces/{id}/events` | GET* | SUBSCRIBE | SSE firehose for the whole workspace: full turns, tool calls with arguments, file changes. The only handshake is `?client_id=<UUID>` — a forged capability token. Attacker rides a live turn in real time. |
| 30 | `/workspaces/{id}/filetracker/lastread` | GET | READ-INVENTORY | Last-read timestamps per file path. |
| 31 | `/workspaces/{id}/filetracker/read` | POST | WRITE-AGENT | Forges file-read bookkeeping — can mask agent edits from the app's change detection. |
| 32 | `/workspaces/{id}/lsps` | GET | READ-INVENTORY | Lists running LSP clients. |
| 33 | `/workspaces/{id}/lsps/start` | POST | TOOL-EXEC | Starts an LSP server process (process spawn on device). |
| 34 | `/workspaces/{id}/lsps/stop` | POST | TOOL-EXEC | Stops LSP servers. |
| 35 | `/workspaces/{id}/lsps/{lsp}/diagnostics` | GET | READ-SENSITIVE | LSP diagnostics can quote user source lines. |
| 36 | `/workspaces/{id}/mcp/auth` | POST | TOOL-EXEC | Runs the OAuth flow for a named MCP server; blocks until complete (auth-flow hijack / URL surfacing). |
| 37 | `/workspaces/{id}/mcp/auth-url` | GET | READ-INVENTORY | Leaks an in-flight MCP OAuth authorization URL. |
| 38 | `/workspaces/{id}/mcp/config-disabled` | POST | TOOL-EXEC | Toggles an MCP server in config — reshapes the tool surface the agent can call. |
| 39 | `/workspaces/{id}/mcp/disabled` | GET / POST | GET: READ-INVENTORY / POST: TOOL-EXEC | Lists disabled MCP servers; POST toggles per-repo enablement. |
| 40 | `/workspaces/{id}/mcp/docker/disable` | POST | TOOL-EXEC | Disables Docker MCP. |
| 41 | `/workspaces/{id}/mcp/docker/enable` | POST | TOOL-EXEC | Enables Docker MCP (adds tool surface). |
| 42 | `/workspaces/{id}/mcp/enabled` | GET | READ-INVENTORY | Lists enabled-override MCP servers. |
| 43 | `/workspaces/{id}/mcp/get-prompt` | POST | TOOL-EXEC | Fetches an MCP prompt (external tool invocation with attacker args). |
| 44 | `/workspaces/{id}/mcp/pending-auth` | GET | READ-INVENTORY | Lists MCP servers pending OAuth. |
| 45 | `/workspaces/{id}/mcp/prompts` | GET | READ-INVENTORY | Lists MCP prompts. |
| 46 | `/workspaces/{id}/mcp/read-resource` | POST | TOOL-EXEC | Reads an attacker-named URI through a configured MCP server. |
| 47 | `/workspaces/{id}/mcp/refresh-prompts` | POST | TOOL-EXEC | Refreshes MCP prompt cache. |
| 48 | `/workspaces/{id}/mcp/refresh-resources` | POST | TOOL-EXEC | Refreshes MCP resource cache. |
| 49 | `/workspaces/{id}/mcp/refresh-tools` | POST | TOOL-EXEC | Refreshes MCP tool cache (can change the agent's callable tools). |
| 50 | `/workspaces/{id}/mcp/start` | POST | TOOL-EXEC | Starts an MCP server process (process spawn). |
| 51 | `/workspaces/{id}/mcp/states` | GET | READ-INVENTORY | MCP client states. |
| 52 | `/workspaces/{id}/messages/user` | GET | READ-SENSITIVE | **All user messages across the workspace** — every prompt the user ever typed, one request. |
| 53 | `/workspaces/{id}/permissions/grant` | POST* | WRITE-CRITICAL | Answers a permission request `allow`/`deny` — authorizes (or forges) a tool-execution decision; with `yolo` already true the gate is moot anyway. |
| 54 | `/workspaces/{id}/permissions/skip` | GET / POST* | GET: READ-INVENTORY / POST: WRITE-CRITICAL | GET discloses skip state; **POST flips skip — agent-wide auto-execution without prompts.** |
| 55 | `/workspaces/{id}/project/init` | POST | WRITE-CRITICAL | Marks the project initialized (writes project state). |
| 56 | `/workspaces/{id}/project/init-prompt` | GET | READ-INVENTORY | Returns the init prompt template. |
| 57 | `/workspaces/{id}/project/needs-init` | GET | READ-INVENTORY | Init status flag. |
| 58 | `/workspaces/{id}/providers` | GET* | READ-INVENTORY | Provider catalogue incl. models and pricing; reveals which providers are configured. |
| 59 | `/workspaces/{id}/questions/answer` | POST* | WRITE-AGENT | Answers the agent's open questions with attacker-chosen responses — steers an in-flight run without the user seeing it. |
| 60 | `/workspaces/{id}/questions/cancel` | POST* | WRITE-AGENT | Cancels a question batch (forces the agent down a default path). |
| 61 | `/workspaces/{id}/sessions` | GET*+POST* | GET: READ-INVENTORY / POST: WRITE-AGENT | Lists sessions (titles, token counts, cost); POST mints sessions for attacker runs. |
| 62 | `/workspaces/{id}/sessions/{sid}` | GET / PUT / DELETE* | GET: READ-INVENTORY / PUT: WRITE-AGENT / DELETE: WRITE-AGENT | GET session metadata; PUT edits it (title spoofing); **DELETE destroys the user's chat history.** |
| 63 | `/workspaces/{id}/sessions/{sid}/filetracker/files` | GET* | READ-INVENTORY | Reveals every project file path the agent touched this session (project-structure disclosure). |
| 64 | `/workspaces/{id}/sessions/{sid}/history` | GET | READ-SENSITIVE | Session history — conversation content. |
| 65 | `/workspaces/{id}/sessions/{sid}/messages` | GET* | READ-SENSITIVE | **Full message list: chat history plus any code the user pasted or the agent read/edited.** The app's own history loader. |
| 66 | `/workspaces/{id}/sessions/{sid}/messages/user` | GET | READ-SENSITIVE | All user messages of the session. |
| 67 | `/workspaces/{id}/skills` | GET | READ-INVENTORY | Lists visible skills. |
| 68 | `/workspaces/{id}/skills/read` | POST | READ-SENSITIVE | Returns skill **file content as bytes** from the user's project (POST verb, read semantics). |

App-used annotations: the starred set is exactly 20 paths — `/health`, `/version`,
`/workspaces` (GET+POST), `/workspaces/{id}/agent` (GET+POST), `/agent/init`,
`/agent/main`, `/agent/sessions/{sid}/cancel`, `/config/model`, `/config/provider-key`,
`/current-session`, `/events`, `/permissions/grant`, `/permissions/skip` (POST only),
`/providers`, `/questions/answer`, `/questions/cancel`, `/sessions` (GET+POST),
`/sessions/{sid}` (DELETE only), `/sessions/{sid}/filetracker/files`,
`/sessions/{sid}/messages`. (The pinned spec contains no `/clients` *register* verb —
only `DELETE /clients/{client_id}`; registration is implicit via `client_id` on
workspace create.)

## 4. 🔑 Keys in config — the load-bearing finding

- **The `api_key` is present in `GET /v1/workspaces/{id}/config` responses (observed on
  device 2026-10-17).** One unauthenticated GET against a sibling app's loopback port
  returns the resolved workspace config with the provider credential in plaintext. The
  app's Keystore-wrapped storage (`SecureKeyStore`, AES-256/GCM) protects the key at
  rest inside *this* app, but the engine hands it back over an unauthenticated socket
  the moment it is configured — the two protections do not compose.
- The same class of leak is plausible on `GET /v1/config` (server-level config tree) and
  on `GET /v1/workspaces` / `GET /v1/workspaces/{id}`, whose schemas embed
  `config.Config` and an `env` string array (spec-declared, not device-verified for those
  three).
- **`POST /v1/workspaces/{id}/config/provider-key`** completes the round trip: an
  attacker can *overwrite* the key. Note the spec bug: it declares
  `"api_key": {"type":"array","items":{"type":"integer"}}`, but the engine decodes a
  plain JSON string (Go `json.RawMessage`); a byte array fails with
  `HTTP 400 {"message":"decode api key string:"}` (`CrushDtos.kt` documents this quirk).
  The spec error does not reduce risk — it only means a naive generator would send the
  wrong shape.
- `POST .../config/import-copilot` responds with the imported `token` in the body — a
  second key-shaped read hiding behind a write.

## 5. Why endpoint-level redaction is not sufficient

Endpoint redaction (masking `api_key` in config responses) addresses exactly one row of
§3. It does **not** address:

1. **User code and prompt theft.** `GET .../sessions/{sid}/messages`,
   `.../messages/user`, `GET /v1/workspaces/{id}/messages/user`, `.../history`,
   `.../prompts/list`, and `.../skills/read` return chat history, inlined code, queued
   prompts, and skill file bytes regardless of key masking.
2. **Key theft via the round trip that exists today.** Even with `/config` masked, the
   key remains readable from the other config-bearing reads until *upstream* redacts
   them — and masking cannot undo that the plaintext variant was observed in v0.98.1.
3. **Security-state flips.** `POST .../permissions/skip` (auto-approve everything),
   `POST .../permissions/grant`, `POST .../config/set|remove`, `.../refresh-oauth`,
   `POST /v1/workspaces` (create a `yolo:true` workspace with attacker-chosen `env`
   aimed at any directory) all remain open.
4. **Agent hijack on the user's real project.** `POST .../agent` sends an arbitrary
   prompt that the agent executes against the user's workspace — with permissions
   skipped, a prompt-injected instruction ("delete the repo", exfiltrate via network
   tools) runs unattended. `POST .../agent/sessions/{sid}/shell` is worse: it is direct
   shell execution with stdout returned.
5. **Live-turn eavesdropping.** `GET .../events?client_id=<forged-UUID>` is an SSE
   subscription to the whole workspace: every streamed token, every tool call with its
   arguments, every file change — including material that never appears in any config
   response.
6. **Destruction and denial of service.** `DELETE .../sessions/{sid}`,
   `DELETE /v1/workspaces/{id}`, `POST /v1/control` (shutdown), `POST .../cancel`,
   `DELETE /clients/{client_id}` — history loss and engine kill are one request each.

Redaction alone is therefore explicitly **not** being claimed as a sufficient control.

## 6. Attack preconditions and realism

- **(a) Connectivity.** The attacker app needs the `INTERNET` permission (normal,
  user-granted at install for most apps) — or any other path to open a TCP socket to
  `127.0.0.1`. Loopback is not UID-partitioned on stock Android.
- **(b) Port discovery.** The port is ephemeral, but a TCP connect scan of localhost is
  feasible in seconds (loopback connects are sub-millisecond; probing
  `GET /v1/version` or `/v1/health` disambiguates the engine from any other local
  listener). A malicious app may also poll repeatedly at low cost.
- **(c) No auth layer.** Engine v0.98.1 implements no authentication, no origin check,
  no token requirement on any of the 76 operations — per upstream source and audit F01.
  The only "credentials" anywhere in the protocol are UUID-shaped identifiers
  (`client_id`, workspace id, session id), which are capability tokens at best:
  discoverable via `GET /v1/workspaces` and forgeable for `client_id`.
- **(d) What is NOT demonstrated.** No device-side exploit chain was executed for this
  document: no sibling-app PoC connected to a live engine, no key was actually exfiltrated
  by an attacker process, no workspace was deleted by anything other than the app itself.
  This map is static analysis of the pinned spec plus app source, with the one on-device
  observation recorded in §4. An honest threat statement is: **exposure is confirmed by
  construction; the end-to-end attack chain remains undemonstrated.**

## 7. Mitigation directions (aligned with the current P1 plan)

- **(i) Private Unix-domain socket** if the engine supports a UDS listen address —
  timeboxed experiment; a filesystem UDS inside the app-private dir is sandbox-protected
  by UID, which would close the sibling-app surface at the transport layer. Feasibility
  must be checked against upstream; do not assume.
- **(ii) In-app EngineGate bearer-token proxy** — upstream-compatible, app-side: the app
  fronts the engine and all traffic (including SSE) flows through a token-checking gate.
  Honest caveat: a proxy alone cannot stop a sibling app from connecting *directly* to
  the engine's port; it is a client-side hardening layer and a stepping stone, not a
  boundary.
- **(iii) Upstream-native auth patch (P1.6)** — a shared-secret/token check implemented
  inside the engine itself. This is the only direction that closes rows 1–68 at the
  listener; track upstream as the durable fix.
- **(iv) Config redaction** (mask `api_key` in `/config`-family responses) — limited by
  what upstream will accept; reduces §4 to a residual, but as §5 shows it leaves every
  WRITE-*/TOOL-EXEC/SUBSCRIBE row fully open. Directional hygiene only; **no claim that
  redaction alone is sufficient.**

---

*Generated 2026-10-17.*

**Sources actually consulted (read-only):** `spec/crush-openapi.json` (pinned, 68 paths /
76 operations, counts verified programmatically);
`app/src/main/java/com/crush/mobile/data/crush/CrushApi.kt`;
`app/src/main/java/com/crush/mobile/data/crush/CrushConnection.kt`;
`app/src/main/java/com/crush/mobile/data/crush/CrushDtos.kt`;
`app/src/main/java/com/crush/mobile/data/api/ZenModelsApi.kt`;
`BASELINE.md` §7 (F01 wording).

**Status matrix (as mapped, 2026-10-17):** `CONFIRMED MAPPED (static analysis); exfiltration chain NOT demonstrated on device`.

---

## 8. P1 outcome (2026-10-18): mitigation (i) landed and device-verified

Direction (i) was implemented — the engine pivot is no longer an experiment.
The HTTP/TCP surface described in §3 is **closed at the transport layer** for
every UID except the app's own.

### What shipped (commits `a5c53b6`, `f235639`)

- Engine spawned with `-H unix:///data/user/0/com.crush.mobile/files/crush.sock`
  (v0.98.1 `ParseHostURL` accepts `scheme://`; net listener is `net.Listen("unix", …)`).
  No TCP port is opened at any stage; app URLs keep the
  `http://127.0.0.1:1/…` shape with a sentinel port (`UDS_PORT_SENTINEL`) and
  Path remapping happens inside the OkHttp socket factory.
- Clients attach via `junixsocket-common` `AFUNIXSocketFactory.FactoryArg`
  (`EngineSockets.kt`), with the JNI shim **vendored**
  (`app/src/main/jniLibs/arm64-v8a/libjunixsocket-native.so`, 60 KB, extracted
  from the upstream AAR, sha1 `eed2f4d90ebdc0de4964fd9ffe752cb9b57aa57d`).
  Upstream trap worth remembering: `junixsocket-native-android` on Central is
  POM-packaging — Gradle downloads no natives, and every call dies with
  `UnsatisfiedLinkError: NativeUnixSocket.createSocket`, which `runCatching`
  converts into silent probe failures.
- Readiness/heartbeat probes (`EngineSockets.probe`, supervisor, diagnostics)
  all traverse the socket; `/proc/net/tcp*` is monitored as a regression canary.

### On-device verification (Redmi Note 7 Pro, `bbf8ca69`, 2026-10-18)

| Check | Result |
|---|---|
| Socket inode | `srwx------ u0_a316 u0_a316 … files/crush.sock` (owner-only rwx) |
| TCP listeners owned by the app | **0** rows in `/proc/net/tcp` and `/proc/net/tcp6` whose UID is the app's (only DNS resolver, uid 1052) |
| adb shell (uid 2000) `ls` on the socket path | **Permission denied** (traversal: dir is 0700) |
| adb shell `cat` on the socket | **Permission denied** (open would also require MAY_WRITE for AF_UNIX connect) |
| `run-as com.crush.mobile` from shell | sees the socket — proving only the owner UID passes |
| End-to-end turn | user prompt → engine executed reasoning + tool rows → SSE text streamed back over the socket and rendered (`probe 'Reply_with_exactly:_UDS-OK' → 'UDS-OK'`) |
| Stale-socket recovery | engine log `WARN Removed stale socket before binding` on rebind after app death |

### Why this closes the sibling-app threat (kernel-enforced)

For filesystem AF_UNIX sockets, `connect()` performs an
`inode_permission(MAY_WRITE)` on the socket inode, and reaching the inode at all
requires traversal permission through every parent directory. The app-private
dir chain is 0700 and the socket inode is 0600, both owned by the app UID — so
any **different Android UID** (the sibling-app threat from §1, plus the
shell/2000-uid case actually exercised above) is denied by the kernel before
our code or the engine's runs. No app-side allowlist to bypass; nothing is sent
over the network.

Note the honest limits of the live proof: the negative case was exercised with
the shell UID (2000), not a literal sibling app UID (installing an attacker
app on this device was not done). The kernel rule invoked is the same one for
any non-owner UID, and the 0600/0700 bits are the observable protection. The
`run-as` positive control confirms the ownership boundary is where access ends.

### Residual (unchanged by the pivot)

- Root/adbd or any process with `CAP_DAC_OVERRIDE` can still access the socket —
  out of model (§1 excludes rooted devices).
- Same-UID code (plugins inside our process) is inside the boundary by
  definition.
- §4 (raw `api_key` in `/config` responses) and §5 endpoint-class risks are now
  reachable **only by the app itself**; they remain real for USB debugging
  (`adb backup` is blocked, but `run-as` is available on debug builds).
- Upstream `charmbracelet/crush` does not accept a UDS listen address via
  documented config — our support is via the `-H` flag; track upstream
  acceptance (P1.6) as the durable path.
