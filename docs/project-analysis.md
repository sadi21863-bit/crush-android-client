# Project Analysis

Architecture, the defects that shaped it, and what is left.

> This document previously listed bugs B1–B5 and gaps G1–G6 as open. Most are
> now closed. It has been rewritten as a **current-state** document; the
> historical record lives in the git log and in
> [`capabilities.md`](capabilities.md), which carries the verified/unverified
> split per feature.

## 1. Scorecard

| Area | State |
|---|---|
| Engine lifecycle (boot, health, restart, shutdown) | Solid; supervised, with thermal tuning |
| Crush protocol client | Solid; HTTP order pinned by `MockWebServer` tests |
| Model selection | Built, honest, live-probed; verified against OpenRouter `:free` (Zen free tier withdrawn — §1a) |
| Conversation continuity | Fixed and verified — was splitting every 2–3 messages |
| Session management | Delete fixed; resume fixed; reuse now correct |
| App lock | Fixed and verified; bypass removed and now covered |
| Markdown | Implemented, streaming-safe, hand-rolled |
| Tool-call visibility | Built; wire shape confirmed on the wire 2026-10-25 (D3 closed) - parser pinned by tests |
| File diffs | Built and JVM-tested; wire path verified, no edited-file turn yet |
| Security | Key encrypted; engine transport app-private UDS (cross-app reach closed); HTTP layer still unauthenticated |
| Test coverage | 401 JVM tests; **no instrumentation suite** |

## 1a. The Zen free tier is gone (measured 2026-10-11)

This is an upstream change, not a bug in the app, and it blocks any end-to-end
device test. Measured against a live key:

| Request | Result |
|---|---|
| `GET /v1/models` | **HTTP 200**, 43 models listed |
| `POST /v1/chat/completions` × all 43 | **0 usable** — 33 × HTTP 402, 10 × HTTP 403 |

The split is unambiguous and is the whole story:

- **33 models return 402** — payment required.
- **10 models return 403** — and those 10 are *exactly* the models whose ids end
  in `-free` (`ling-3.1-flash-free`, `cat-jam-free`, `mimo-v2.6-flash-free`,
  `muse-spark-*.contributor-free`, `nemotron-3.5-lightning-free`, …).

So the free tier is still **advertised** in the catalogue and no longer
**granted**. A key that authenticates perfectly — which is why the provider's own
dashboard reports it as active — can list models and call none of them.

**Why the app's message was confusing.** "Model access is disabled" is accurate
but hides the useful distinction: the key is not revoked, the *models* are. The
next diagnostic step should say which of the two it found, and the model picker
should never offer a model that has already been observed to fail.

`FALLBACK_PROBE_MODEL = "space-bunny-free"` was singly and doubly wrong: the id
is no longer in any catalogue, and a hardcoded guess is exactly what Crush
rejects a handshake over (the "large model not found in provider" a friend hit
on a fresh install). **Removed** on 2026-10-16: the early agent init now runs
only when a model is actually chosen, and with none selected the workspace is
claimed by `openWorkspace` alone and the model comes from the live probe.

The pattern worth naming: **almost every serious defect lived in the seam between
the Android lifecycle and the engine process lifecycle**, not in pure logic. That
is exactly the area with the least automated coverage.

## 2. The defect class that dominated

Crush reaps a workspace with **no attached client** after ~30 seconds, and the
app opened an SSE stream per turn. So the workspace was guaranteed to die between
turns, which produced a cascade:

- sends failing with `workspace not found` after any pause
- `deleteSession` addressing a reaped workspace
- a new session minted on every re-attach — so conversations split every third
  message
- `WorkspaceLease` and force-retry paths written solely to paper over it

Every one of these was chased individually for a long time before the shared
cause was identified. The fix was structural: **one long-lived workspace-scoped
SSE connection**, which removes the condition entirely.

Lesson worth carrying: when several symptoms share a shape, the shared cause is
more productive than the next symptom.

## 3. Two bugs that only a device could have found

Both passed the entire unit suite.

**`deleteSession` did nothing, silently.** It was `suspend` but called OkHttp's
synchronous `execute()` without `withContext(Dispatchers.IO)`, so it threw
`NetworkOnMainThreadException` on every call. It was invisible because that
exception has a **null message** and the log printed only the message — every
attempt read `deleteSession failed: null`. The request never left the device.

Fixed by logging the exception **type**, which exposed it immediately.

**Conversations split every 2–3 messages.** The send path chose between reusing
and replacing the session with:

```kotlin
if (existing != null && existingSessionWorkspace == ws) existing else newSession()
```

The `== ws` half read like a safety check. It was the bug: every re-attach
produces a new workspace id, so after any typing pause the check failed and a new
session was minted. The stated premise — that sessions die with their workspace —
was simply wrong.

Both now have regression tests that assert on **behaviour that would have failed**
rather than on absence-of-crash.

## 4. Architecture

```
Compose UI
  └─ ChatViewModel  (agent state machine, one structured job per turn)
       ├─ CrushApi          request/response
       ├─ CrushEventStream  per-turn fallback stream
       ├─ CrushConnection   long-lived workspace SSE
       └─ CrushEventRouter  demultiplexes events to the run that asked
            │
  AppContainer
    ├─ EngineSupervisor → CrushEngine (child process)
    ├─ KeySession → SecureKeyStore (AES/GCM + Android Keystore)
    └─ SettingsRepository (DataStore)
```

The app owns a **second lifecycle** it did not choose: engine process, workspace
attachment, session, SSE stream, Activity/ViewModel, and credential. Most serious
defects are mismatches between those lifetimes.

## 5. Open defects

Reassessed 2026-10-20 against live code; every row below was re-verified, not
carried forward.

| ID | Defect | State |
|---|---|---|
| D1 | `deleteSession` treats transport failure as success, so a failed delete reappears | **Fixed** — `DeleteOutcome` (Failed → banner, local state untouched; Deleted/AlreadyGone → confirmed) + force-retry; pinned by `DeleteOutcomeTest` |
| D2 | Engine API unauthenticated and reachable from another app's UID | **Closed by architecture** — UDS-only transport (2026-10-18): the engine binds `files/crush.sock` `srwx------`, so `connect()` requires the app UID. Evidence and limits in [`p1-attack-surface.md`](p1-attack-surface.md) §8 |
| D3 | Tool-call field names unconfirmed against a real engine turn | **Closed 2026-10-25 - captured on the wire (SSE + persisted messages, engine v0.98.1): part = {"type":"tool_call","data":{"id","name","input":<args-JSON-string>,"finished":<bool>}}; ToolActivityParser reads data first with the part as fallback, resolves state from the finished boolean (COMPLETED only when true), mines file_path from input; pinning tests added. Spin-off finding: in ASK mode engine v0.98.1 never broadcasts permission_request on any route - mutating tool turns hang until cancelled; see D12 |
| D4 | `DynamicPolicy` hysteresis off by one — relaxes after 2 calm samples, not 3 | **Fixed** — caller passes `priorCalm` so `calmRun + 1` is not applied twice; pinned by `SupervisorHysteresisTest` |
| D5 | `AppLog` rewrites the whole file on a 2s heartbeat and clears rather than rotating | **Fixed** — `rotateKeepingTail` keeps the newest half, archive gets the evicted head. **Upgraded 2026-10-25:** the standalone-usage audit showed only 2 files ever survived (init roll deleted `app-prev.log`; mid-run rotation deleted it again to store an evicted head), erasing the Oct-23 standalone evidence. Evicted heads + each run's roll now append to dated `app-archive-<day>.log` files retained **30 days** (pruned past retention + at a 20MB total cap, oldest first); pinned by `RetentionPruneTest` |
| D6 | `AppLog` writes are unsynchronised across threads | **Fixed** — `writeLock`; size cached instead of stat'd per line |
| D7 | `CrushEngine.readPid` casts `Process.pid()` (a `Long`) to `Int`; the cast fails and diagnostics see `-1` | **Fixed** — `pidFromReflective` (`as? Number`, range-guarded), top-level for direct testing |
| D8 | `EnginePaths.logFile()` points at a path Crush may no longer write | **Fixed** — `newestEngineLog` picks the newest `crush.log` by mtime (5 JVM tests) |
| D9 | First-launch lock bypass (no ciphertext yet, so no gate) | By design and documented — nothing at rest to protect |
| D10 | `WorkspaceLease` and force-retry paths are now redundant dead scaffolding | **Closed as incorrect** — both live: `WorkspaceLease.isFresh` is the reattach heuristic (`WorkspaceManager`), force-retry runs on failed deletes (`ChatViewModel`) |
| D11 | Bootstrap resolved the provider from the **in-memory mirror** (`currentPersistedProvider`) instead of the persisted settings it already awaited; a freshly recreated ViewModel could lose that race and configure the default `opencode-zen`, latching "no API key - add one in Settings" **silently** against an intact key | **Fixed** (2026-10-20, device-proven) — commit `9498a67`: provider resolved from the awaited DataStore emission; the three resolution helpers are suspend and await `first()`; the blank-key branch logs `provider=<id> storedCiphertext=<bool> providersWithKeys=…` before giving up. The silent branch was the debugging cost — **any "giving up" branch must log what it decided on** |
| D12 | ASK-mode ask cards never rendered: an ask turn blocked in the permission step and the UI got nothing (D3 spin-off, 2026-10-25). v0.98.1 never published the ask on SSE at all; v0.98.2 fixed engine delivery, but on 2026-10-27 an instrumented v0.98.2 engine **proved the ask still never reached the app**: breadcrumbs `PW permissionsvc publish` → `PW fan-in permissions` → `PW deliver check` (session filter passing) → `PW sse recv` all fired, the app's `current-session` client_id contract was honored, and the app still showed no card and no `permission requested:` log | **Fixed 2026-10-27 (app-side)** — second defect confirmed and closed: `CrushEventRouter.dispatch()` sent events with **no run id** (the wire shapes of `permission_request`, `question_batch_request`, and every `message` frame — only `run_complete`/`agent_event` echo the app-minted run id) to catch-all channels alone, and **nothing in the app subscribes to the catch-all**. The frame was `trySend`-ed to zero targets. Fix: anonymous events are fanned out to every registered run (a registered run is an in-flight turn; the engine already session-scopes frames to the just-reported current session); pinned by three new `CrushEventRouterTest` cases. Same cause explained "thinking" bubbles with no streaming text (message frames dropped) and paint-on-final-text-only UX. **Still open**: live device re-test of a full ask-then-grant turn (gated on provider daily quota); `question_batch_request` has no app handler yet — once fan-out delivers it, an unanswered question hangs the turn to the 180 s timeout the same way (D14) → **closed 2026-10-29**: the ask-then-grant turn was re-tested live on device (z6/z7, ask card rendered, automated Allow-once granted, turn resumed; evidence `build/d12-evidence/`), and the question handler shipped in `3235f7e`. During re-test two sibling defects surfaced and closed same-day (idle race + total cap vs parkedness prompts — see deep-analysis-2026-10-27.md §9) |
| D13 | Failed turns rendered as NOTHING in history: the replay mapper dropped assistant rows without text or tools, but provider-failure turns persist as messages whose only part is a `finish` with reason "error" (standalone-usage wire evidence 2026-10-25: `dial tcp: lookup openrouter.ai ... connection refused` after a DNS outage). Every failed standalone turn vanished on reopen - the user-visible face of "the app sometimes doesn't work" | **Fixed** 2026-10-25 - replay keeps rows carrying a finish-error and the existing bubble renders the error text; degenerate `parts:[]` rows stay dropped deliberately (no invented history). Extracted as `replayRow`; pinned by `ChatHistoryReplayTest`. Follow-up from research CLOSED 2026-10-31 (`0dca731`): failed assistant turns now render as a dedicated error card (M3 `errorContainer` surface, warning icon, "This reply failed" headline with a polite live region scoped to it, raw engine error in monospace beneath; any partial output the turn produced before dying is kept above the error line) with a Retry button that re-sends the nearest preceding user prompt as a NEW turn - retry text is derived at render time from the transcript itself (no model changes), the button stays visible but disabled while a run is live or the engine port is down, and a Retry-less card is the graceful fallback when no non-blank user prompt exists in history. The failed pair is never rewritten: history keeps it and the retry lands as a fresh exchange |
| D14 | **Event-consumption defects class**: anonymous-frame drop (D12, fixed 2026-10-27), 404-zombie connection (`CrushConnection.kt:178-183`), `runIdOrNull` over-match binding events to a foreign run (`CrushEventRouter.kt:170-189`), mid-send retry never re-pointing the shared connection (`ChatViewModel.kt:1543-1568`), silent DROP_OLDEST frame loss (`CrushEventRouter.kt:58-64/124`) — the silent-hang family all points downstream to the same symptom, so drop sites need breadcrumbs, not only publish sites | **Closed 2026-10-29** — the whole P1 class was re-verified fixed in-tree (per-item commits/proofs now recorded in [deep-analysis-2026-10-27.md](deep-analysis-2026-10-27.md) §4): anonymous fan-out + 404 unwind + bounded run-id search (`d50f26e`), mid-send re-pointing + prompt lifecycle (`3235f7e`), teardown hook + retry budgets + generation-bound lease + cancellation-safe handshake (`4cd9c19`), honest snapshots + sink masking + FLAG_SECURE recents (`c22b394`), and the `question_batch_request`-handler child this row borrows from D12 shipped in `3235f7e`; the DROP_OLDEST-breadcrumb hygiene share of this row sits in the P2 list and gets its own triage |

## 6. Capability gaps

Ordered by how much each matters for a coding agent. Detail in
[`capabilities.md`](capabilities.md).

1. **File diffs** — the agent writes files invisibly.
2. **Tool-call timeline** — summary per message, not a live view.
3. **Per-turn cost display**.
4. **Verify a third provider** — `opencode-zen` and `openrouter` are verified end to end (OpenRouter since 2026-10-17, including the model-probe fallback order); `groq` is probe-verified but parked (8K TPM). Next candidates: `hyper` (device-code flow, no OAuth plumbing needed) or `gemini`. See [providers.md](providers.md).
5. **Multi-project** — one app-private workspace.
6. **Instrumentation tests**.
7. **Remote crash reporting** — in-app export only.

## 7. Security posture

| Item | State |
|---|---|
| Key at rest | AES-256/GCM, master key in Android Keystore |
| Keystore auth gating | **None** — required to fix an API 30+ onboarding crash |
| App lock | Platform credential screen, mandatory once a key exists |
| Backups | `allowBackup=false` + `data_extraction_rules.xml` |
| `crush.json` | **Plaintext key**, app-private, backup-excluded (Crush's design) |
| Engine API | Bound to a **Unix domain socket** (`srwx------`), reachable only by the app UID — see [`p1-attack-surface.md`](p1-attack-surface.md) §8 |
| Cleartext | Permitted to loopback only, via `network_security_config.xml` |
| Release builds | Signed; debug builds are explicitly not shareable |

The compound risk worth naming: because the engine is unauthenticated *and*
stores the key in plaintext *as the app's UID*, the plaintext key's usual
protection boundary (root-only) is weaker than it appears. Reachability is
confirmed; command execution is not.

## 8. What went well

- The engine protocol was verified **on-device** before client code was written,
  which is why the HTTP details are accurate.
- `WorkspaceLease` and `ModelPrice` were written as pure functions specifically so
  the risky decisions could be tested without a device.
- Comments record *why* each safeguard exists, including the incident. Several
  odd-looking constraints exist for reasons that would otherwise be "cleaned up"
  and reintroduce the bug.
- Where a value could not be verified, it fails safe and says so — an
  unrecognised tool state is `UNKNOWN`, not `COMPLETED`; an unconfirmed Stop says
  "stop requested", not "stopped".

## 9. Recommended order

Re-baselined 2026-10-20 after the D-table audit: D1 was already fixed, D10 the
"remove the scaffolding" instruction was closed as incorrect (the lease is the
live fallback path), and D2 was resolved by the UDS pivot — so the original
items 3, 6, 7 drop out. The exposed **Groq** key was rotated by the user
(2026-10-25); groq stays parked as a test target.

1. **Verify a live ask-then-grant turn on device (D12)** - the engine half is
   done (v0.98.2 delivers session-scoped asks, verified with instrumented
   breadcrumbs 2026-10-27) and the app half is done (CrushEventRouter now fans
   run-id-less events to registered runs; unit tests pin it). What remains is
   the end-to-end proof: ASK-mode turn → ask card → Allow → file written.
   Gated on provider daily quota at the time of the 2026-10-27 verification
   attempt. Evidence and re-test matrix:
   [`ecosystem-research-2026-10-25.md`](ecosystem-research-2026-10-25.md) §1.
2. **Verify a real file-diff turn on device** - the diff feature is built and
   JVM-tested but was only ever exercised on a turn that tracked zero files;
   the engine already ships `metadata{diff,additions,removals}` per
   `tool_result` (research §3/§8). Also chase the "1 unreadable" miss seen on
   the auto-approve turn.
3. **File diffs** - the largest remaining user-visible gap.
4. **Instrumentation tests** for the lock gate, so the auth bypass cannot
   return.
5. **Release gate**: signed release only, debug non-shareable; then a real
   edited-file diff turn and a permission-mode walkthrough before
   distribution. Review the FSL-1.1-MIT license posture for any public
   release (research §1 - crush is NOT plain MIT).


