# Deep analysis — 2026-10-27 (six-agent full-project review)

Six read-only analysis agents covered the whole tree in parallel: SSE event
pipeline, engine lifecycle + data layer, the ChatViewModel state machine, the
UI layer, files/diffs + security, and build/tests/docs. Every finding keeps its
file:line citation; cross-checked findings that six agents agree on are the
highest-confidence items. Findings already closed today are marked accordingly
so the backlog stays honest.

## 1. What the project is (one paragraph)

A Kotlin/Compose Android app that embeds the Charm Crush CLI engine
(v0.98.2) as a bundled native binary (`libcrush.so`, exec'd as a child
process) talking over a kernel-gated app-private unix-domain socket with a
localhost HTTP+JSON+SSE surface. The app supplies model provider keys
(Keystore-encrypted, injected over the socket), a persistent workspace +
session model (workspace ids change per engine restart; identities are
path-derived), a biometric app lock, a chat UI with streaming bubbles, tool
activity rows, file diffs, diagnostics/crash reporting, and an ASK/AUTO
permission model. 37 JVM unit test files; no instrumentation suite.

## 2. Verdict at a glance

**The architecture is consistently honest** — nearly every failure mode has
been made *observable* rather than silently swallowed, and the pure-policy
pattern (lease freshness, provider switch, tool state, lock decisions, diff
metadata) is real and consistently tested. The weaknesses cluster in exactly
two places: **(a)** event consumption — the demux/router layer dropped
whole classes of events (D12, closed today) and carries several race/zombie
paths; **(b)** **the ChatViewModel monolith** — 1900 lines owning bootstrap,
session identity, streaming, diffs, and the session list, structurally
untestable and the source of most P0/P1 behavioral bugs.

## 3. Closed today (this session, uncommitted)

| Fix | Evidence |
|---|---|
| **D12 full root cause + fix**: fanned run-id-less events (`permission_request`, `question_batch_request`, every `message`) in `CrushEventRouter.dispatch()` to all registered runs; previously only to catch-alls, which nothing subscribes to — silent loss | Instrumented-engine breadcrumb chain (publish → fan-in → session filter pass → SSE write) + code path (`CrushEventRouter.kt` dispatch; `ChatViewModel.kt:1459/1479/1753`); 3 new `CrushEventRouterTest` cases |
| **Streaming text painted only at turn end** — same router bug, message frames never reached the collector | Engine `proto.Message` has no run id (`internal/proto/agent.go`); UI symptom ("thinking…" until `run_complete`) |
| Doc drift: five D12 sites rewritten to the two-defect model (`project-analysis.md`, `capabilities.md`, `ecosystem-research-2026-10-25.md`, `README.md` known-issues); README 12→13 Catwalk-id drift + two-provider verification status | see those files |

## 4. Consolidated backlog (prioritized)

### P0 — break core behavior, do next

1. **Recomposition storm during streaming** (new): every frame rebuilds the
   whole `messages` list with unstable `List` fields, no `@Immutable`
   (`ChatViewModel.kt:1788-1795`, `ChatViewModel.kt:94-114`,
   `ToolActivity.kt:50`), and `remember(src)` re-parses the entire markdown
   string per token (`MarkdownText.kt:56-57`) — O(n²) per turn; this is why
   uiautomator cannot snapshot the app. Fix: `@Immutable` models, mutate only
   the streaming bubble, incremental markdown parse (append-only last block).
2. **Stream timeout semantics** (new): `withTimeoutOrNull(STREAM_TIMEOUT_MS)`
   wraps the *whole* collect — no per-frame reset, no server-side cancel on
   expiry (`ChatViewModel.kt:1728/1729-1776`). After the D12 fix restores
   streaming, legit >3 min agentic turns die client-side; the engine keeps
   running, `workActive` drops and hang-restart can fire under the live run.
   Fix: per-event idle timeout + `cancel()` before finalizing.
3. **`question_batch_request` has no handler** (`else -> Unit`,
   `ChatViewModel.kt:1774`): once fan-out delivers it, an unanswered question
   hangs the turn to the same 180 s timeout — D12 relived through the sibling
   event. Needs a `pendingQuestion` branch + answer POST before the fan-out
   fix ships for question flows.

### P1 — degrades or misleads

4. **404-zombie connection** (new): the workspace-gone branch exits the reader
   loop without clearing `running`/`thread` (`CrushConnection.kt:178-183`), so
   same-identity `ensureAttached` is a permanent no-op and every turn silently
   falls back to the per-turn stream — which re-introduces the ~30 s workspace
   reap. Fix: set `running=false` on 404 and observe connection state to
   re-attach.
5. **`runIdOrNull` over-matches** (new): recursive descent through tool
   params/arrays (`CrushEventRouter.kt:170-189`) can bind events to a
   *foreign* run; constrain to the two documented depths.
6. **Mid-send retry never re-points the shared connection** (new): after
   `workspace not found`, `targetWs` changes but `ensureAttached` is not
   called (`ChatViewModel.kt:1543-1568`) — the turn can hang on a socket
   attached to the dead workspace.
7. **`pendingPermission` lifecycle** (new): never cleared on stop/timeout/new
   thread except by `resolvePermission` (`ChatViewModel.kt:1247/1757`); grant
   failure is silent (`1247-1258` — dialog gone, agent still blocked).
8. **Stale before-image snapshots** (new): snapshot reuse is keyed only on
   path presence (`WorkspaceSnapshotter.kt:90-94`) against claims of
   mtime/size guarding; turn N's before image is turn N−1's state — second
   edits render inflated/wrong diffs, user edits get attributed to the agent.
9. **Secret masking at the log sink** (new): redaction runs only on
   diagnostics copy-out (`DiagnosticsReport.kt:246-248`), not in
   `AppLog.write` (`AppLog.kt:113-149`); `gsk_` (Groq) keys and generic
   high-entropy tokens pass through.
10. **Engine teardown + startup retry arm** (new): no production call to
    `EngineSupervisor.stop()` (`SpikeActivity.kt:144` only); one slow cold
    start (>60 s readiness) → `Failed(willRetry=false)` forever
    (`CrushEngine.kt:250-256`, `EngineSupervisor.kt:109-114`).
11. **Lease not bound to engine generation** (new): `WorkspaceManager` trusts
    a ≤15 s-old workspace id that may predate an engine restart
    (`WorkspaceManager.kt:35-46`); `forget()` has zero callers.
12. **`runCatching` swallows CancellationException** (new) across the
    handshake (`CrushSession.kt:125/137/145/153/159`,
    `WorkspaceManager.kt:107/146`) — retry logic can run after abort.
13. **Recents preview leaks unlocked chats** (new): no `FLAG_SECURE` anywhere;
    the task-switcher screenshot previews plaintext chat
    (`MainActivity.kt:272-275`).

### P2 — quality/hygiene (condensed)

- Lost-update races on `MutableStateFlow` (read-modify-write everywhere; typed
  input can be erased by a stream frame) — `ChatViewModel.kt:1264/1738-1746`.
- DROP_OLDEST silent frame loss (no drop logging/counter) —
  `CrushEventRouter.kt:58-64/124`; unknown-type frames dropped with zero
  logging (`CrushConnection.kt:259`).
- Close-vs-send race loses an event silently; `completeRun` double-closes
  channels — `CrushEventRouter.kt:104/138-141`.
- LCS in `FileDiff` allocates a full DP table — OOM-class at caps
  (`FileDiff.kt:239-247`); two-row table + reconstruction is the fix.
- Fallback/backoff ladder: cap is 4 s (dead `MAX_BACKOFF_MS`), attempt resets
  on any success — `CrushConnection.kt:222-226/188`; repeated connect-fails
  spam the persistent log during engine boot.
- `run_complete` accepted with empty run id — cross-turn race
  (`ChatViewModel.kt:1763`; `CrushStreamProbe.kt:104`).
- Live tool rows never reach COMPLETED within a turn (history replay only) —
  `ToolActivity.kt:37-40/190-198`.
- Bootstrap cost per resume: ≤3 one-token probes + duplicate catalogue fetch
  (`ChatViewModel.kt:634/654/745`); verified-model persistence would kill the
  resume cost.
- Engine groq catalogue frozen (2026-10-17) and hardcodes cost=0 → engine
  reports "free" (`EngineConfigInjector.kt:171-188`,
  `ZenModelsApi.kt:224-234`).
- Sentinel port produces real `:1` URLs on fallback paths —
  `CrushApi.kt:92-102/110`.
- Mutating `yoloMode` before workspace creation succeeds —
  `WorkspaceManager.kt:82-96`.
- INJECTOR lost-update vs concurrent engine config write —
  `EngineConfigInjector.kt:134-167`.
- Equality-based base re-detection in SUP-01 —
  `EngineSupervisor.kt:207-209`; plus unbounded wedge-restart loop, dead
  tuning constants, probe-without-appContext reports healthy
  (`EngineSupervisor.kt:86-119/357-364/317-321`).
- `stopBlocking` may stall main ~3 s (`EngineSupervisor.kt:80-83`).
- UI: markdown quadratic inline-scan hardening + code-block caps
  (`MarkdownParser.kt:189-250`, `MarkdownText.kt:140-147`); Settings mojibake
  (`SettingsScreen.kt:141-143`); provider-mislabeled key field copy
  (`:365/374`); hardcoded diff colors vs unused theme tokens
  (`ChatScreen.kt:544-547`); a11y gaps (raw enum `contentDescription`,
  no `liveRegion`, focusable no-op chips); heartbeat floods the 200-line
  diagnostics tail (`CrushMobileApp.kt:56-66`); enter-send setting has no UI.
- Security: key plaintext at rest in `crush.json` (documented trade, deserves
  an agent-prompt guard note) — `EngineConfigInjector.kt:36-38/129`;
  no-lockscreen fail-open (`DeviceAuth.kt:54-62`); `KeySession` default-arg DI
  trap (`SettingsDataStore.kt:18`); Keystore-invalidation comment wrong
  (`SecureKeyStore.kt:171-172`).
- Build/repo: R8 off + lint fully disabled (`app/build.gradle.kts:92-94/155-169`);
  spike harness (`SpikeActivity/FullE2ETest/CrushSmokeTest`) ships in every
  APK; no signed release has ever been produced; plaintext test key file at
  repo root ("opencode api key for testing.txt") — rotate/remove;
  `AGP 10` opt-outs must go before AGP 10 (`gradle.properties:42/47`).

### Verification/coverage gaps (from the tests agent)

Biggest untested surfaces: `ChatViewModel` itself (incl. the 84-line
uncommitted send-path edit), `CrushStreamProbe`, `WorkspaceManager`
reattach/continuation (only lease object tested), permission-ask UI flow,
`LockOverlay`, all four diagnostics files, `ZenModelsApi` beyond base-url.
No `androidTest` at all (documented).

## 5. Strengths worth keeping (evidence-backed)

- Bounded drop-oldest channels + identity-based socket replacement +
  cancel-correct blocking I/O (`CrushEventRouter.kt:58-64`,
  `CrushConnection.kt:78-124/115/144`).
- LAZY + record-then-start preflight, handle-owned bubbles,
  server-side-first cancel with `workActive` hold
  (`ChatViewModel.kt:1295-1302`, `RunHandle.kt:41-118`,
  `ChatViewModel.kt:1620-1626/1838-1848`).
- Restart hysteresis + hang detection with opposite handling for HTTP failure
  vs process death (`EngineSupervisor.kt:135-155/221-224/254-261`).
- Diff honesty: phantom "+N -0" defended at both ends; `tooLarge` labeled not
  faked; null/secrets-free logging hygiene at the boundaries that matter.
  (`WorkspaceSnapshotter.kt:83-89`, `FileChangeCollector.kt:82-85/34-46`,
  `FileDiff.kt:163-173`).
- Lock/key storage: provider-scoped slots, require() non-caching path,
  backup/transfer fully excluded, Keystore loss fails closed
  (`SecureKeyStore.kt`, `KeySession.kt:107-111`, manifest +
  `data_extraction_rules.xml`).
- Test discipline where it exists: 37 JVM suites pin contracts at the pure
  layers; read the "verdict = well" table in §4 (tests agent).
- Documentation: baseline/audit snapshots frozen by design; README honest
  ✅/⚠️ status language; the ledger pattern (D1–D13) makes findings durable.

## 6. What "full understanding" changes operationally

1. The D12 saga is the top case study: two defects compounding (engine not
   publishing + app dropping run-id-less frames) — defeating "we fixed it"
   claims twice. Rule from this: **claim a fix only with an end-to-end turn
   proof** (matching the project's verify-this habit).
2. Event-consumption is the riskiest layer: every "silent drop" path there
   renders downstream as exactly the same symptom (silent hang), so add
   breadcrumbs *at the drop sites*, not only at the publish sites.
3. Most P0/P1 behavioral bugs trace to the ChatViewModel monolith; splitting
   it (at minimum extracting the send/turn loop into a testable class) would
   have made D12 unit-findable instead of device-debugged.

## 7. Execution notes 2026-10-27

This backlog was handed to parallel build agents the same day it was written.
Distribution record, so re-assembly of who-owns-what stays possible:

| Territory | Scope distributed |
|---|---|
| Chat/streaming core | §4 P0/P1 ChatViewModel items (recomposition storm, stream timeout, question-batch handler, pendingPermission) |
| SSE pipeline | Event-consumption class: 404-zombie connection, `runIdOrNull` over-match, mid-send retry re-point, DROP_OLDEST logging |
| Engine lifecycle | Supervisor/engine teardown, generation-bound lease, CancellationException swallowing, groq catalogue |
| Files/security | Snapshot staleness, sink-side secret masking, `FLAG_SECURE`, diff/allocation hardening |
| Build/docs | Spike-harness source-set move, decisions doc, ledger/doc bookkeeping (this agent) |

These notes record only what **this** agent completed; each other territory
is owned by its own agent's report.

Done by the build/docs agent (2026-10-27, uncommitted):

- Moved `SpikeActivity.kt` / `FullE2ETest.kt` / `CrushSmokeTest.kt` from
  `app/src/main/java/…/screens/spike/` to the debug-only source set
  `app/src/debug/java/…/screens/spike/` (byte-identical copies, SHA-256
  verified, package lines untouched, originals deleted). Pre-flight grep
  proved nothing outside the three files and the manifest referenced them
  anywhere in `app/src` (unit tests included), so the move could not break a
  build. `app/build.gradle.kts` needed no change — AGP picks up `src/debug`
  implicitly.
- Removed the `SpikeActivity` `<activity>` entry from
  `app/src/main/AndroidManifest.xml`; created
  `app/src/debug/AndroidManifest.xml` with the same entry (same attributes,
  `exported=false`) so debug builds keep `adb shell am start …SpikeActivity`
  working while release APKs stop shipping the harness.
- Created [`docs/decisions-open.md`](decisions-open.md); ledger row D14
  appended in `project-analysis.md` §5; README Known issues gained a pointer
  bullet. No gradle run (forbidden this session), so the release-APK claim is
  by-config-not-by-execution until someone builds `assembleRelease` and
  inspects it.

## 8. Deferred / user decisions

Human-gated decisions extracted from §4 (R8/lint re-enable, keystore +
signed release, plaintext test-key rotation, injector-vs-engine write
design, no-lockscreen fail-open stance, key-at-rest guard note, plus the
deferred verified-model persistence item) live in
[`docs/decisions-open.md`](decisions-open.md). They are intentionally not
tracked as engineering backlog items here.

## 9. Addendum — 2026-10-29 D12 live-device verification (ask card E2E)

D12's fix was verified end-to-end on device `bbf8ca69` with real turns
(z3…z6, evidence `build/d12-evidence/`, logs `app-current*.log`). Two new
defects surfaced during that verification; both root-caused and fixed in
tree the same day. Anatomy of each, condensed for reuse:

### Defect A — an open ask card killed its own turn (fixed, proven live)

`awaitStream`'s per-frame idle deadline (`STREAM_IDLE_MS`, 20 s) could not
distinguish "engine silent" from "engine parked waiting for the user's
grant" — in ASK mode a pending permission IS silence to the frame loop.
App log proof: `permission requested: write` 00:34:19 → *same run*
`stream idle > 20s …; requesting engine cancel` 00:34:39, while z4's ask
card stood open. Fix: while `pendingPermission`/`pendingQuestion` is
non-null the frame receive runs without the idle race; the outer total
ceiling remains the only cap. Live proof (z6): ask opened 01:42:55, the
dialog still stood ~50 s later, grant → write → `file changes` at
01:44:10, zero idle-cancel lines. The §4 P0-2 row ("no per-frame reset")
is partially superseded: the idle race + awaited engine cancel exist; what
remains of P0-2 is the *outer* 3-min ceiling question for long agentic
turns.

### Defect B — every snapshot read failed on API < 33 ("1 unreadable" phantom)

Symptom chain (z4/z5/z6): after any turn, the UI's file-changes card added
`N file(s) not shown (unreadable…)` for files the engine tracked but that
pre-dated the turn — including a 5-byte text file that had diffed cleanly
a turn earlier. `FileChangeCollector`'s route instrumentation (added
2026-10-29) decided it: `routes: … ROUTE=truncated-before …;
snapKeys=[] snapTruncated=[all files]` on a 3-file, 20-byte workspace.

Root cause: `WorkspaceSnapshotter.looksBinary()` used
`InputStream.readNBytes(int)` — a Java 11 API android.jar exposes only at
API 33+. On this API 29 device every call throws `NoSuchMethodError`,
which `runCatching { … }.getOrDefault(true)` converts into "binary" for
EVERY non-empty file → the capture stored no content and marked all files
truncated → the collector's `truncated-before` branch honestly reported
them unreadable. JVM unit tests never saw it because desktop JDKs have the
method. z3 looked clean only because its tracked file did not exist yet at
its own capture. Fix: manual 512-byte `InputStream.read` fill (API-1
portable), semantics unchanged. Verification turn planned: `routes:`
should print `snapKeys=[files]` + diff-edit routes, `0 unreadable`.

Also in this pass (defensive, keep): `FileChangeCollector.normalise()` now
accepts `/data/data` ↔ `/data/user/0` alias spellings of the workspace
root, because the engine records whatever absolute spelling the model put
in its tool call. Verification turn z7 (02:08–02:09, fixed build) closed
the loop: ask card → automated Allow-once → resume →
`routes: z7ask.txt ROUTE=diff-created(+1/-0) | z6/z5/z3ask.txt
ROUTE=diff-edit(+0/-0); snapKeys=[4 files] snapTruncated=[]` and
`file changes: +1 -0 across 1 file(s), 0 unreadable` — the phantom is
gone. The TEMP diagnostics (`tracked(N)…`, `routes: …`) served their
purpose and were removed after z7; their output is preserved in
`build/d12-evidence/app-current4.log` (z6) and `app-current5.log` (z7).

Process notes worth keeping: `uiautomator dump` cannot settle on this app
(biometric prompt + caret blink), so all UI automation ran on
screencap→System.Drawing pixel measurement; FLAG_SECURE renders the lock
gate as a 0-byte screencap, which doubles as the unlock detector; the
permission mode only re-binds at `doBootstrap` (the settings screen even
labels it "next time the agent starts a session"), so a mode flip needs
force-stop+relaunch+one fingerprint.
