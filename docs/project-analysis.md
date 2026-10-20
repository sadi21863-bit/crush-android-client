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
| Model selection | Built and honest; **no callable model exists on the current key** |
| Conversation continuity | Fixed and verified — was splitting every 2–3 messages |
| Session management | Delete fixed; resume fixed; reuse now correct |
| App lock | Fixed and verified; bypass removed and now covered |
| Markdown | Implemented, streaming-safe, hand-rolled |
| Tool-call visibility | Built; inner field names spec-confirmed, envelope not |
| File diffs | Built and JVM-tested; wire path verified, no edited-file turn yet |
| Security | Key encrypted; **engine API unauthenticated and cross-app reachable** |
| Test coverage | 331 JVM tests; **no instrumentation suite** |

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
| D3 | Tool-call field names unconfirmed against a real engine turn | **Open** — next on the §9 order |
| D4 | `DynamicPolicy` hysteresis off by one — relaxes after 2 calm samples, not 3 | **Fixed** — caller passes `priorCalm` so `calmRun + 1` is not applied twice; pinned by `SupervisorHysteresisTest` |
| D5 | `AppLog` rewrites the whole file on a 2s heartbeat and clears rather than rotating | **Fixed** — `rotateKeepingTail` keeps the newest half, archive gets the evicted head |
| D6 | `AppLog` writes are unsynchronised across threads | **Fixed** — `writeLock`; size cached instead of stat'd per line |
| D7 | `CrushEngine.readPid` casts `Process.pid()` (a `Long`) to `Int`; the cast fails and diagnostics see `-1` | **Fixed** — `pidFromReflective` (`as? Number`, range-guarded), top-level for direct testing |
| D8 | `EnginePaths.logFile()` points at a path Crush may no longer write | **Fixed** — `newestEngineLog` picks the newest `crush.log` by mtime (5 JVM tests) |
| D9 | First-launch lock bypass (no ciphertext yet, so no gate) | By design and documented — nothing at rest to protect |
| D10 | `WorkspaceLease` and force-retry paths are now redundant dead scaffolding | **Closed as incorrect** — both live: `WorkspaceLease.isFresh` is the reattach heuristic (`WorkspaceManager`), force-retry runs on failed deletes (`ChatViewModel`) |
| D11 | Bootstrap resolved the provider from the **in-memory mirror** (`currentPersistedProvider`) instead of the persisted settings it already awaited; a freshly recreated ViewModel could lose that race and configure the default `opencode-zen`, latching "no API key - add one in Settings" **silently** against an intact key | **Fixed** (2026-10-20, device-proven) — commit `9498a67`: provider resolved from the awaited DataStore emission; the three resolution helpers are suspend and await `first()`; the blank-key branch logs `provider=<id> storedCiphertext=<bool> providersWithKeys=…` before giving up. The silent branch was the debugging cost — **any "giving up" branch must log what it decided on** |

## 6. Capability gaps

Ordered by how much each matters for a coding agent. Detail in
[`capabilities.md`](capabilities.md).

1. **File diffs** — the agent writes files invisibly.
2. **Tool-call timeline** — summary per message, not a live view.
3. **Per-turn cost display**.
4. **Verify a second provider** — 13 provider ids are offered and the plumbing works, but only `opencode-zen` has completed a handshake. `hyper` (Charm Hyper, 100 credits/month free, no 402/403 plan gate) or `openrouter` with a `:free` model is the obvious candidate, since Zen's own free tier is withdrawn. See [providers.md](providers.md).
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

1. **Rotate the exposed key** and clear the stale value from engine state.
2. **Settle D3** — one real tool-using turn confirms or corrects the field names.
3. **Fix D1** — delete must not report success on transport failure.
4. **File diffs** — the largest remaining user-visible gap.
5. **Instrumentation tests** for the lock gate, so the auth bypass cannot return.
6. **D10** — delete the lease scaffolding once the connection is trusted.
7. **D2** — decide whether to ship with the unauthenticated engine API, and
   document the decision either way.
