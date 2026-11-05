# S6 recon — ask-for-shell in Code pools (read 2026-11-05)

Read-only recon produced while drafting S6. No code changed. Nothing here
purports to have the engine's schema for the ask knob — see the open
question at the end, and the S5 drive's bio-strings evidence.

## 0. Context (from the S5 drive)

Measured live (device `bbf8ca69`, v0.99.0): with `permissionMode=ASK`
logged, `createWorkspace(yolo=false)` + `skipPermissions(id,false)` sent
cleanly, and NO persisted permission state anywhere (engine keeps
permission decisions in memory; crush.db has no workspaces table and no
yolo/permission columns), bash executed with NO permission_request — for a
never-before-run command too. The ask pipeline itself works: the engine DB
carries the historical todo.md WRITE denial. Working conclusion: the pinned
engine's DEFAULT tool policy asks for file-writes and auto-allows shell; to
make ASK mean ask for bash, the app must ship an explicit per-instance
policy config.

## 1. Per-instance engine config injection today

**Injector** — `app/src/main/java/com/crush/mobile/data/crush/EngineConfigInjector.kt`
(object, no state):

- `:128` `fun syncProviderModels(globalDataDir: File, providerId: String,
  overrides: List<ModelSpec>, engineRunning: Boolean = false)` — writes
  `<globalDataDir>/crush.json`; `engineRunning=true` REFUSES the write
  (`:144` "sync refused: engine is running; the write window is 'engine
  down' only").
- `:214` `fun injectChatToolPolicy(globalDataDir: File): SyncResult` —
  merges `options.disabled_tools` (union+dedup `:223–240`), idempotent
  (`:237` byte-stable early return), malformed file never rewritten
  (`:242`), atomic tmp+rename (`:245–252`), nothing logged (key-protection
  rule, KDoc `:35–40`). NO engineRunning guard — safe only by call-site
  placement.
- `:268` `CHAT_DISABLED_TOOLS` — 22 ids (`todos/question/task/diagnostics`
  appended in 22fbe28, `:273–276`).

**Call sites** — `app/src/main/java/com/crush/mobile/engine/CrushEngine.kt`,
inside `start()` under `startLock`, all pre-exec:

- `:206–220` `syncProviderModels(..., engineRunning = false)` — "call site
  IS the pre-spawn window".
- `:227–233` CHAT gate — `if (instanceKey == EnginePaths.CHAT_INSTANCE_KEY)
  { ... injectChatToolPolicy(paths.globalData) }` with "engine parses its
  config ONCE per process, so anything later cannot reach it" (`:225–226`).
- Spawn after: `:246–277` `ProcessBuilder(...).start()`.
- Re-entrant early return `:172–180` (process already alive → returns
  WITHOUT re-injecting — a restart is the only re-injection path).

**Paths / per-instance resolution** — `engine/EnginePaths.kt`:

- `:44–45` `crushBase = File(filesDir, "crush")` for legacy, else
  `File(filesDir, "crush-<key>")`.
- `:54` `globalData = File(crushBase, "data")` — the injector target; `:70`
  env `CRUSH_GLOBAL_DATA = globalData`.
- `:114` `CHAT_INSTANCE_KEY = "chat"`; project keys are `"p" +
  uuid.take(8)` (`AppContainer.kt:201`).

## 2. PermissionMode flow

- `domain/model/PermissionMode.kt:15–20` enum, default ASK; `:33–35`
  `parse()` FAILS SAFE to ASK.
- `AppSettings.kt:37`; `SettingsDataStore.kt:104` parse,
  `:187–189` save; `SettingsRepository.kt:53–54`; Settings toggle
  `SettingsScreen.kt:42–78` (`:77` switch write).
- `ChatViewModel.kt` doBootstrap: binding adoption FIRST (`:805–807`),
  then `:857–861` reads persisted settings (`.first()`), `:866–869`
  `effectivePermissionMode = if (instanceKey == CHAT_INSTANCE_KEY) ASK else
  mode` ("chat-yolo lock"), `:899–904`
  `session.openWorkspace(..., effectivePermissionMode, providerId)`.
- `CrushSession.kt:58–74` → `workspaces.open(..., yolo = mode ==
  AUTO_APPROVE)`; `WorkspaceManager.kt:169–228` — create
  (`createWorkspace(yolo=...)`) plus ALWAYS re-apply
  `api.skipPermissions(id, yolo)` (`:208`); reattach reuses cached
  `yoloMode ?: false` (`:259`).
- `CrushApi.kt:303–305` `POST /v1/workspaces/{id}/permissions/skip`.

**Who knows the mode at spawn time.** `CrushEngine.start()` is called only
by `EngineSupervisor.supervise()` (`EngineSupervisor.kt:227`); supervisors
start via `ChatViewModel.startEngine()` (`:391–393`) from composition
prewarm or first keystroke. doBootstrap's mode read is TOO LATE for spawn
config (spawn precedes the bootstrap attached to Running). The clean
spawn-time channel is the settings fan-out that already exists:
`AppContainer.kt:118–122`
`containerScope.launch { settingsRepository.settings.collect {
...settings apply... } }` — the same iteration that pushes
`applyTuning` (`:468–474`, `engine.setIdleTimeoutSec` +
`supervisor.tuning`) under the documented "applies to the NEXT engine
start" pre-exec contract. Seed at `runtimeFor()` (`:426–433`) for fresh
runtimes.

## 3. Receiving a permission_request

- Decode: `CrushEventStream.kt:123–135` (data-only SSE); envelope two
  levels `{"type","payload":{"type","payload"}}`; wire name
  `"permission_request"` (`CrushEvent.kt:19–40`, `:22`).
- State: `ChatViewModel.kt:282–283` `pendingPermission: PermissionRequest?`
- Branch: `ChatViewModel.kt:2744–2786` — parse via gson; `req.id.isNotBlank()`
  guard `:2746`; CHAT pool branch auto-denies (`:2747–2775`: log
  `"chat deny-all: ${req.toolName} auto-denied on the pool"`, transcript
  note, deny POST `:2775`); non-chat branch parks the card (`:2776–2781`).
- DTO: `CrushDtos.kt:159–168` `PermissionRequest(id, session_id,
  tool_call_id, tool_name, description, action, path, params)`.
- Card: `ChatScreen.kt:333–394` — "Allow this action?"; Allow once /
  Always (allowForSession) / Deny (`:378–392`).
- Resolve: `ChatViewModel.kt:1933–1974` — `PermissionGrant(permission = req
  echoed whole, action = DENY|ALLOW_SESSION|ALLOW)`; `crushApi(p).grant_`
  → `CrushApi.kt:310–311` `POST .../permissions/grant`; `pendingPermission`
  cleared only on 2xx (`:1966–1972`, P1-7 keeps the dialog on failure).

## 4. The S4 auto-deny layer

Event-side, INSIDE the same permission_request branch, keyed by instance
key only (Code pools fall through to the normal ASK card — S6's
ask-for-shell events are unaffected) — `ChatViewModel.kt:2747–2775` above.
`waitingOnPrompt` idle-wait path `:2689–2694` already cooperates with an
open card. Commit 22fbe28 = the deny-list extension; S4 auto-deny is the
backstop for surfaces the list cannot preempt (MCP names).

## 5. Tests to mirror (pure JVM, TemporaryFolder — no Robolectric for this layer)

- `EngineConfigInjectorTest.kt` — names: "creates the file with the full
  ordered list...", "preserves the provider key...", "second identical call
  changes nothing", "a write during the engine's live window is refused...",
  "a malformed file is left alone...".
- `EngineConfigInjectorChatPolicyTest.kt` — "creates the deny list when no
  config file exists yet", "second injection is byte-stable and
  idempotent", "provider keys are preserved...", "a malformed file is never
  rewritten", "existing entries are unioned, not clobbered".

## 6. Proposed minimal S6 patch shape

1. `EngineConfigInjector.injectAskShellPolicy(globalDataDir: File,
   engineRunning: Boolean = false): SyncResult` — same semantics as the
   chat one (union/dedup into `options.*`, idempotent, malformed never
   rewritten, tmp+rename, nothing logged); ADD the `engineRunning` guard
   `syncProviderModels` has.
2. `CrushEngine`: `@Volatile shellAskOnSpawn: Boolean` (default TRUE —
   fail-safe to ask, matching `PermissionMode.parse`), setter mirror of
   `setIdleTimeoutSec` ("applies to the NEXT engine start"). Call site:
   extend the `:227–233` block — chat keeps the deny list, non-chat pools
   call `injectAskShellPolicy` iff the flag is set.
3. Container: extend the settings collector (`AppContainer.kt:118–122`) to
   fan out the resolved boolean to the legacy engine + live projectRuntime
   (same iteration as `applyTuning`), and seed `runtimeFor()`. `PermissionMode`
   never leaves DI — the engine layer gets a Boolean.
4. Gate decision: project/keyed pools AND the legacy `""` pool are Code
   surfaces with shell alive — recommend `!= CHAT_INSTANCE_KEY` so legacy
   is not silently auto-shell.
5. Tests: new `EngineConfigInjectorAskShellPolicyTest.kt` — creates-when-
   absent; byte-stable idempotence across repeated spawn attempts;
   malformed refused; provider keys preserved; the pure gate (chat never
   injects / AUTO_APPROVE no-op / project+ASK on).

## 7. Open questions / risks

- **SCHEMA NOT PINNED.** What exactly to write into `options.*` for
  ask-for-shell is unresolved by the engine's schema; the S5 drive only
  proved the machinery exists (binary strings: "error parsing config for
  policy %q", "no supported policies found in config", "Permissions denied
  in shell config", `--enabled-tools`). Needs either the upstream config
  docs for this generation, or an S1-style device probe (write candidate
  shapes, respawn, watch for permission_request + `date`/`ls` behavior).
  Do not land the injector with a guessed key.
- **Write-back race (real, measured family):** S1b measured the engine
  writing back `data/crush.json` at runtime (config persistence survives
  even source deletion) — any app-side write while the engine is up can
  lose or clobber. Keep injection strictly pre-exec + guarded.
- **AUTO_APPROVE asymmetry:** "inject nothing" does NOT safely deactivate
  an ask block the engine persists. If S6 ships only "ASK = inject,
  AUTO_APPROVE = skip", a user flipping ASK→AUTO_APPROVE stays ask-gated
  until the config is stripped in an app-stopped window (the S1 repair
  path). Options: (a) upsert an explicit auto-allow block on AUTO_APPROVE
  (symmetric; recommended), or (b) document the restart requirement
  honestly.
- **Early-return blind spot:** a mode flip while an engine is warm writes
  nothing until a real restart (crash, pool switch, or requested
  `supervisor.restart`). Same rule as idle timeout; note it in the
  docs/UI copy or request a restart on flips (S6 decision).
- **Probe path:** `CrushStreamProbe.kt:115–129` auto-ALLOWS permission
  requests so model discovery cannot hang — with ask-for-shell armed,
  probe turns will log grants they never used to; verify probe latency
  post-arming.
- **Device drive after arming:** the S5 probe pair (`ls -la` replay + the
  fresh `date`) is the ready-made acceptance regression: both must now
  produce an ASK card, deny → honest refusal (MODE-06 completed as
  originally premised).

## 8. RESOLVED the same day (2026-11-05, upstream docs + pinned source) — supersedes the §7 schema question

The "SCHEMA NOT PINNED" blocker above is closed; §7 above stays as the
point-in-time record. Findings, pinned in upstream's own docs
(`docs/config/README.md`, `docs/config/FUTURE.md`) and our exact tag
`v0.99.0` source (`internal/agent/tools/bash.go` + `safe.go`,
`internal/permission/permission.go`):

1. **The config layer is allow/deny only** — legacy JSON
   `permissions.allowed_tools` (what `permissions allow` writes; schema
   verified at `schema.json#/Permissions`) and `options.disabled_tools`
   (what `permissions deny` writes, the S1-proven hide mechanism). There
   is NO ask-per-shell switch in either format; "prompted" is the default
   for a tool absent from both lists (`docs/config/FUTURE.md` three-state
   table).
2. **Bash sits ABOVE the generic permission service** in v0.99.0:
   a safe-read-only PREFIX bypass (`lowercased command starts with any
   `safeCommands` entry; bypass disabled when the command contains
   chaining metacharacters `;|&&$( \`) means `date`/`ls` never consult
   `permissions.Request` at all — the S5 no-card runs were INSIDE the
   designed bypass. Non-safe commands consult the service and prompt
   under our `yolo=false`; the separate banned-command blocklist
   (curl/wget/nc/scp/ssh/sudo/package managers/systemctl/mount/...) is
   refused outright, never prompted.
3. **S6 scope dissolved.** App-side ask-for-shell config injection is
   unnecessary and has no upstream knob to target. The native posture —
   read-only introspection auto-runs, writes and non-read-only commands
   prompt through our existing ASK card, dangerous network/admin commands
   hard-refuse — satisfies MODE-06's spirit (tools alive + ASK intact for
   consequential actions). The renamed acceptance fixtures: `mkdir` = the
   card-must-appear probe (non-safe), `date`/`ls` stay the no-card
   control (safe-list correct), banned refusal check = e.g. blocked
   `wget` attempt honest refusal.
4. **Upstream-notable:** `kill`/`killall` are inside `safeCommands` — an
   agent can silently stop processes without asking; in an embed the
   engine child shares the host app's UID. Flag candidate for the
   upstream batch (the 2026-11-05 `nightly` hardens matching/parsing
   around this — d3b8997, f7179d1, 15c37e3, fd40067, 95d807a; the rolling
   nightly prerelease is upstream's watch channel, pin-stays discipline
   unchanged).
