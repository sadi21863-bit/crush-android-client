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
permission model. 38 JVM unit test files (455 `@Test` methods, counted
2026-10-31; `ChatStateMergeTest` joined 2026-10-31); no instrumentation suite.

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

1. **Recomposition storm during streaming** — **PARTIALLY RESOLVED in
   `3235f7e` (verified 2026-10-31)**: the message and tool-row models now
   carry `@Immutable` (`ChatViewModel.kt:129/162`, `ToolActivity.kt:58`,
   each annotated with an "analysis §4 P0-1" citation), and stream updates
   replace the affected row (`ChatViewModel.kt:2398` map transform) instead
   of touching all rows — correctness claim partially addressed, the
   O(n²) absolutes below no longer hold as written. **Still open**: the
   full-per-frame markdown re-parse — `remember(src) { parse(src) }` at
   `MarkdownText.kt:67` re-parses the entire string every streaming frame
   (incremental/append-only block parse is the remaining fix). (Original
   finding: every frame rebuilt the whole `messages` list with unstable
   `List` fields, no `@Immutable`, `remember(src)` full re-parse per frame;
   this is why uiautomator cannot snapshot the app.)
2. **Stream timeout semantics** — **RESOLVED 2026-10-28→29** (`3235f7e`
   built the per-event idle deadline + awaited server cancel; `a870939`-era
   device work then proved the ask-parked case wrong both ways: an open ask
   was cancelled by the 20s idle race (Finding 1, fixed by suspending the
   idle race while a prompt is pending), and the REMAINING 180s total cap
   could only ever kill the healthy cases (long steady-stream turns,
   think-past-it ask reading). Final shape: idle race for real silence,
   `PROMPT_WAIT_MS` (10 min, decisions-open item 8) for parked prompts, no
   total cap. Residual gap: the total cap's absence is reasoned from
   evidence, not device-tested against a wedged-but-chattering run.
3. **`question_batch_request` has no handler** — **RESOLVED** (handler
   shipped in `3235f7e`: raw-batch `PendingQuestion` + `resolveQuestion`
   release path; questions also suspend the idle race since 2026-10-29).

### P1 — degrades or misleads

**CLASS CLOSED 2026-10-29**: every row below was re-checked against the
current tree and all ten are already fixed by the 2026-10-27/28 engine and
SSE hardening commits — this section was never updated because those
sessions shipped code, not rows. Commit citations below are verified by
`git log -S` and by the code markers (`P1-4`…`P1-11`) each fix carries.

4. **404-zombie connection** — **RESOLVED** (`d50f26e`): the workspace-gone
   branch now unwinds exactly like `stop()` (cancel in-flight call,
   `running=false`, `thread=null`, state→IDLE) while KEEPING the workspace
   id, so same-identity `ensureAttached` starts a fresh reader instead of
   being a permanent no-op (`CrushConnection.kt`, the 404 branch's
   "THE 404-ZOMBIE BUG (P1-4)" comment).
5. **`runIdOrNull` over-matches** — **RESOLVED** (`d50f26e`): the recursive
   descent became a bounded run-id search ("bounded run-id search (P1-5)",
   `CrushEventRouter.kt`); deep tool-param ids no longer bind events to a
   foreign run.
6. **Mid-send retry never re-points the shared connection** — **RESOLVED**
   (`3235f7e`): the fresh-workspace retry calls
   `eventConnection?.ensureAttached(ws2, clientId)` ("analysis §4 P1-6"),
   so the turn cannot hang on a socket still attached to the dead
   workspace.
7. **`pendingPermission` lifecycle** — **RESOLVED** (`3235f7e`): prompts
   retire when their session dies in replay, on stop in preflight and on
   the server ("retire it (P1-7)"), inside the answer-dialog lifecycle,
   and when the engine generation boundary orphans them; the legacy
   cross-workspace prompt keeps its dialog answerable instead of painting
   an unanswerable one.
8. **Stale before-image snapshots** — **RESOLVED** (`c22b394`, "honest
   snapshot reuse"): reuse is no longer keyed on path presence alone;
   the snapshotter re-validates the claimed state so turn N's before
   image is genuinely turn N−1's committed state (the API-portable binary
   probe followed in `584bf3d`).
9. **Secret masking at the log sink** — **RESOLVED** (`c22b394`): the one
   regex set (`AppLog.Masking`: `oc_sk_`, `gsk_`, `sk-`, then the generic
   25+ digit-bearing token rule with a UUID-shape exception) now runs in
   `AppLog.write` on every persisted line — message and throwable trace,
   both sinks — and `SecretMaskingTest` pins it; the diagnostics copy-out
   delegates to the same matcher instead of owning a divergent copy.
10. **Engine teardown + startup retry arm** — **RESOLVED** (`4cd9c19`,
    "Engine: teardown hook, retry budgets"): `EngineSupervisor.stop()` is a
    production teardown path, and cold-start readiness failures run a
    bounded START-failure budget (`startRetryGivesUp`, P1-10) instead of
    latching `Failed(willRetry=false)` on one slow boot.
11. **Lease not bound to engine generation** — **RESOLVED** (`4cd9c19`,
    "generation-bound workspace lease"): every manager records its
    allocation generation and fails closed if it would hand out a
    workspace id minted by an older generation than the running engine
    (P1-11); the lease state carries the generation that minted it.
12. **`runCatching` swallows CancellationException** — **RESOLVED**
    (`4cd9c19`): `runCatchingNonCancellation` (CrushSession.kt) rethrows
    CancellationException, and every handshake `runCatching` site switched
    to it — an abort no longer becomes "step failed, retry once".
13. **Recents preview leaks unlocked chats** — **RESOLVED** (`c22b394`):
    `FLAG_SECURE` is set BEFORE the platform takes the task-switcher
    picture and cleared only after the gate decision (`MainActivity.kt`,
    "FLAG_SECURE BEFORE…" comment), so a locked chat cannot appear as a
    plaintext thumbnail.

### P2 — quality/hygiene (condensed)

**RE-TRIAGED 2026-10-29.** Several rows were already fixed by the hardening
commits named above the P1 class; those are now separated from the backlog.

**Resolved since this analysis was written** (verified by code inspection and
`git log -S`, not separately re-tested):

- LCS diff allocates a full DP table → linear-space diff (`c22b394`).
- `run_complete` accepted with empty run id → strict-match with counted
  foreign-run completes (`3235f7e`); the same change makes live tool rows
  reach COMPLETED inside the turn at `run_complete` (was replay-only).
- DROP_OLDEST silent frame loss + close-vs-send race/`completeRun`
  double-close → overflow-safe queue design with a `closed` guard for late
  subscribers (`d50f26e`, `CrushEventRouter.kt:42-95`).
- Backoff ladder: dead `MAX_BACKOFF_MS` (4 s cap because the shift bound was
  250ms shl 4) → shift bound 6 so the 10 s clamp engages, and the fresh-
  connect ladder reset is now deliberate (`P2-16`, `CrushConnection.kt:260`).
- Engine groq catalogue frozen with cost=0 → the injector no longer writes
  the old cost fields (`P2-21`, `EngineConfigInjector.kt:185`).
- Mutating `yoloMode` before workspace creation succeeds → identity + yolo
  mode commit only on success (`P2-22`, `WorkspaceManager.kt:170/186`).
- SUP-01 equality-based re-detection + unbounded wedge-restart loop →
  monotonic revision of user-supplied settings + bounded wedge budget
  (`4cd9c19`, `EngineSupervisor.kt:71/168-241`).
- Sentinel port producing live `:1` fallback URLs → UDS transport; the
  sentinel survives only as a compile-compat URL shape
  (`CrushApi.kt:93-110`), absent-socket fails fast instead of connecting.
- Spike harness shipping in every APK → moved to the debug source set
  (`a870939`).

**Still open** (rows below are NOT re-verified today unless they carry a
note; label sizes unchanged from the original analysis):

- StateFlow logical races: read-modify-write facets where a stream frame can
  erase typed input — `ChatViewModel`. **RESOLVED 2026-10-31** as a design
  pass with two code fixes and two documented no-actions: **R1 attach-
  clobber** — `openSession`'s final write replaced `messages = replayed` and
  forced `isStreaming = false`, so on a slow network a turn the user sent
  during the history fetch (send() claims `isStreaming` synchronously,
  making the window real) was ERASED by the late transcript, along with its
  Stop affordance; fixed in `ce43c7a` by merging
  inside the CAS loop — replayed rows own the transcript order, any live
  rows they do not contain survive after them (`mergeReplay`, pinned by
  `ChatStateMergeTest`) — and `isStreaming` now only drops when
  `activeRun == null`, so a live run keeps its Stop and sends stay blocked
  while the engine is owned (intentional vs the old switch behavior: the
  opposite risk was two concurrent streams, the exact interleave the
  send-claim fix killed). **R3 selection lie** — `setModel` optimistically
  wrote `selectedModel = id` while the network verify could reject it:
  the TopBar then showed a model the engine slots had refused. Fixed with
  a generation token (`modelSelection`): the latest tap is the only writer
  that may settle, a superseded tap neither errors nor writes the slots,
  and a rejection reverts to the pre-tap selection. **No-action
  (documented)**: R2 — a stale `loadSessions` write is equal-shape
  idempotent data, last-write-wins is harmless; R5 — a late permission
  from a cancelled turn is closed by design in D12/P1-7 (prompts belong to
  the watched session and are retired on switch). Bootstrap double-fire
  was already guarded by the `bootstrapping` flag in-tree.
- App-wide ui-ux-pro-max audit (2026-10-31, whole surface) — **shipped
  (code commit)**: Onboarding — the save button now shows a spinner and
  "Saving key..." while `saveApiKeyAwait` runs (skill Submit-Feedback,
  high), a superseded re-tap is disabled; the provider picker got the
  chevron affordance and a spoken "Provider: X" phrase; the save error is
  a polite live region. LockOverlay — the biometric prompt still said
  "Unlock OpenCode" (pre-rebrand copy); now "Unlock Crush Mobile", error
  line announced. Diagnostics — "  Copy"/"  Share" whitespace-as-layout
  replaced by Spacers; StatusRow label+value merge into one spoken unit.
  Chat — sessions trigger reads "Open chats list" (action, not widget
  name); ModelSheet renders the active check + `stateDescription`
  "selected" for the current model (the `selected` param existed and was
  never rendered). Verified-clean during the sweep: session-sheet empty
  states, row tap-target sizes, diff/tool a11y (earlier batches), bubble
  contrast (brand purple on white-on-purple = 4.9:1, passes AA), and the
  DiagnosticsScreen honesty copy. Code in `a67aee5` + `ce43c7a`.
- ~~UI rows~~ — **CLEARED 2026-10-31**, two waves: (1) verified already
  in-tree, same hardening generation as the P1 class: the markdown inline
  scan carries `INLINE_SCAN_BUDGET_CHARS = 64 * 1024` + `ScanBudget`
  exhaustion fallback to lossless plain text, and code blocks are capped
  (`CODE_MAX_LINES` + `maxLines` + ellipsis); the tool-row enum
  `contentDescription` is human phrases + `Role.Button` and the model
  "FREE" AssistChip was already replaced by a labelled non-interactive
  Text. (2) Fixed this session: diff +/- colors were hardcoded light-mode
  hex breaking dark mode → themed `DiffColors` tokens via `LocalDiffColors`
  (`9a01b0f`); Settings mojibake (double-encoded `·` separators) restored;
  provider-mislabeled key copy now names the SELECTED provider
  (`9a01b0f`); both blocking dialogs got `LiveRegionMode.Polite` atoms
  (`9a01b0f`); enter-send existed in model/DataStore/ViewModel with no
  consumer → IME-action consumer + "Keyboard" toggle card shipped
  (`a02d882`); the 2s heartbeat flooding the 200-line diagnostics tail
  slowed to 30s with the kill-vs-crash attribution trade documented
  (`a02d882`).
- Security: key plaintext at rest in `crush.json` — decision item 6 (open,
  user trade); no-lockscreen fail-open — decision item 5 (open, user
  store stance). **CLEARED 2026-10-31, the other two halves of this row
  were already fixed in-tree**: `KeySession`'s default-arg DI trap is
  defused by construction — `SettingsDataStore(context, keySession)`
  takes no default and its kdoc records why (a self-built instance wired
  a second unmanaged cache; AppContainer is the single constructor
  site), and the Keystore-invalidation comment's wrong claim ("device
  credentials changed") was corrected in `c22b394` to the real remaining
  failure mode — backup restore onto other hardware, where ciphertext
  travels without the hardware-backed key material.
- Build/repo: R8 off + lint fully disabled — decision item 1 (open; blocked
  on triaging the AGP/lint crash); no signed release has ever been
  produced; **plaintext test key file still at repo root** ("opencode api
  key for testing.txt" — confirmed present 2026-10-29, rotate/remove is a
  user action); `AGP 10` opt-outs must go before AGP 10
  (`gradle.properties:42/47`).
- INJECTOR lost-update vs concurrent engine config write — decision item 2
  (open, single-writer design call).
- `stopBlocking` main-stall latency: call sites now live on the supervisor's
  own scope, but the ~3 s worst case is not re-measured.
- Bootstrap cost per resume: ≤3 one-token probes + duplicate catalogue
  fetch — parked as decision item 7 (deferred, cross-agent API design).

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
- Test discipline where it exists: 38 JVM suites (455 `@Test` methods,
  counted 2026-10-31) pin contracts at the pure layers; read the "verdict =
  well" table in §4 (tests agent).
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

**Completion record (2026-10-29)** — every distributed territory landed and was
re-verified in-tree during the D14 closure pass: Chat/streaming core in
`3235f7e` + `d779c17` + `a268aca`; SSE pipeline in `d50f26e`; Engine lifecycle
in `4cd9c19`; Files/security in `c22b394` + `584bf3d`; Build/docs in
`a870939`. Per-item status now lives in §4 P0/P1/P2 above, which was the
section the distribution table pointed at while it was still unwritten.

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

## Tree-verification pass 2026-10-31

- Every commit cited in §4 rows verified in `git log`: `d50f26e`, `3235f7e`,
  `4cd9c19`, `c22b394`, `584bf3d`, `a870939`, `d779c17`, `a268aca`, plus the
  10-31 additions `9a01b0f` (UI wave 2), `a02d882` (enter-send + 30 s
  heartbeat), `0dca731` (D13 card), `ce43c7a` (R1/R3), `a67aee5` (UI sweep).
- Code-marker grep confirmed `P1-4`…`P1-11`, `P2-16`, `P2-21`, `P2-22`,
  `SUP-01`, `runCatchingNonCancellation`, "FLAG_SECURE BEFORE…" — all present
  with the claims' semantics; `INLINE_SCAN_BUDGET_CHARS = 64*1024`
  (`MarkdownParser.kt:185`), `CODE_MAX_LINES` (`MarkdownText.kt:39`),
  `PROMPT_WAIT_MS = 600_000L` (`ChatViewModel.kt:2438`).
- a67aee5 diff verified: "Open chats list" trigger, ModelSheet
  `stateDescription = "selected"` + active check, Diagnostics Copy/Share
  Spacers + merged StatusRow, onboarding spinner, "Unlock Crush Mobile".
- The bounded run-id search now sits at `CrushEventRouter.kt:239` and the
  404 unwind at `CrushConnection.kt:195`; the older §5 line cites drifted a
  few lines but still resolve to the described constructs.
- §2's "1900 lines" for the ChatViewModel monolith was the 10-27 measure;
  the file is ~2327 lines today. Historical count left as written.
- Engine of record: code comments and the D12 evidence name v0.98.2; the
  jniLibs binary does not hash to the 0.98.1 BASELINE checksum and
  `tools/fetch-crush.ps1` still pins 0.98.1 (divergence recorded in README).
