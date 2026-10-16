# External review triage

Findings from `Crush_Android_Review` (15 October 2026, static ZIP diff review)
and **which ones were verified against the code, fixed, or are still open**.

The review is a static source read. It explicitly does not claim a build was run
or that anything was exploited. Its value here is that four of its findings
turned out to be real, and two of those were bugs I had introduced.

## Verified and fixed

| ID | Finding | Status |
|---|---|---|
| **P0-01** | Lock overlay is not an execution boundary | **fixed** |
| **P1-03** | Workspace change keeps the stale SSE socket | **fixed** |
| **P1-05** | Stop is not bound to the run it stops | **fixed** |
| **P1-06** | `Channel.UNLIMITED` is not a bound | **fixed** |
| **P2-09** | Comments still claim a 300s Keystore auth window | **fixed** |

### P0-01 — the lock was cosmetic

`MainActivity` composed `NavGraph` regardless of gate state and showed
`LockOverlay` on top. `ChatViewModel.bootstrap()` therefore booted the engine and
called `KeySession.require()`, decrypting the key **behind the lock screen**.

So cancelling the prompt appeared to do nothing: the privileged work had already
started. The lock controlled what was *visible*, not what *ran*.

Fixed by making the gate an input to the chat layer rather than an overlay on top
of it. `MainActivity` passes `isLocked` → `NavGraph` → `ChatScreen` →
`ChatViewModel.onLockStateChanged()`, and `bootstrap()` returns before any side
effect when locked. Unlock resumes, because the workspace was deliberately never
built while locked.

**What this does NOT fix:** work already in flight. An agent mid-turn is inside
the engine, and the lock does not cancel it. That is the honest limit of the fix
and is tracked as open below.

### P1-03 — stale socket on workspace change

`ensureAttached` updated `workspaceId` and then returned whenever the reader was
alive:

```kotlin
workspaceId = ws
if (running.get()) return   // old reader still blocked on the OLD socket
```

Crush re-attaches with a **new workspace id** on every restart, so this is the
common path, not an edge one. The field claimed the new workspace while the
thread stayed on the old socket, so events were misattributed and the reconnect
never happened — the workspace was then reaped after ~30s and the app received
silence until a turn failed.

Fixed by cancelling the in-flight call on a workspace or client change, which
unblocks the reader, and starting a new thread. `CrushConnectionWorkspaceSwitchTest`
compares **thread identity**, because state assertions cannot see this: the
connection still reported `CONNECTED` throughout.

### P1-06 — unbounded event queue

`Channel(Channel.UNLIMITED, BufferOverflow.DROP_OLDEST)` is not an overflow
strategy. `DROP_OLDEST` on an unbounded channel never drops anything, because
nothing is ever full, so a slow or absent consumer accumulates every token frame
for the life of the process. On a device with 0.7–1.0 GB free that is an OOM
waiting for a long answer.

Now bounded at 512 frames with `DROP_OLDEST`, which is where the policy actually
takes effect.

### P2-09 — comments contradicted the code

Three files still described a 300-second Keystore auth window. There is none:
removing that gate fixed an API 30+ onboarding crash. A stale comment here is
worse than none, because it invites the reader to believe `read()` proves
authentication. Rewritten to state that the grace period is a **UX** trade and
provides no protection for the key at rest.

### P1-05 — Stop was not bound to the run it stopped

`stopStreaming()` read the view model's mutable fields at tap time:

```kotlin
val job = streamJob
val ws = wsId
val sid = sessionId
```

Through the whole preflight — re-attach plus `newSession`, two network
round-trips — those fields still described the **previous** turn. So Stop in
that window cancelled a job that had already finished, asked the engine to
cancel the previous session, then the send path POSTED the new prompt anyway.
The user pressed Stop and the agent ran regardless. The review's suggested fix
was an immutable `RunHandle`.

Now a `RunHandle` is created the instant `send()` claims the turn, and Stop
reads only that. `stop()` is a CAS-claimed decision:

- **before the target is frozen** → `NothingPosted`: the local jobs are
  cancelled and `mayPost()` — checked on the way to the POST and per retry
  attempt — suppresses it, instead of firing a cancel at a run that does not
  exist yet;
- **after the target is frozen** → `CancelOnServer(ws, sid, port)`: the exact
  target this turn resolved to, immune to later field changes;
- **already stopped** → `AlreadyStopped`: an impatient double-tap cannot fire a
  second server cancel that might land after a newer run started.

The preflight/stream jobs are recorded on the handle *before* they are started
(`CoroutineStart.LAZY`), because a main-dispatcher `launch{}` runs eagerly to
its first suspension and a Stop in that window would otherwise find no job
recorded to cancel. `finalizeStoppedRun` now finishes only the handle's own
bubble rather than every streaming row, the cancellation exception caught on
stop is no longer painted as a model error, and a stopped turn keeps
`workActive` true until the server cancel has actually completed — which makes
CANCEL-01's promise true in the `finally` path too, not just the explicit one.

`RunHandleTest` (6 JVM tests) pins the decision itself: stop-before-freeze,
freeze-after-stop cannot resurrect the turn, the cancel names the frozen
target, second stop is inert, jobs are cancelled exactly once.

## Already addressed before this review

The review lists these as changes, and they are genuinely in place — but it
flags `KeySession.require()` as still able to decrypt while locked. That is
**correct and intentional**, and worth being precise about:

`require()` returns the plaintext key without caching it, and does not set
`isUnlocked`. `KeySessionLockTest` pins that. The distinction is that a
keystore read is no longer proof of authentication, so `require()` is
*authorisation by the caller*, not a credential gate. P0-01 above is the fix for
the caller that was not checking.

## Still open

| ID | Finding | Why it is not fixed |
|---|---|---|
| **P0-02** | Engine loopback is cross-app reachable | **Not fixable in the app layer.** Crush's HTTP server has no auth to enable and the spec declares no security scheme. Documented honestly in README § Security. |
| **P1-04** | SSE may not be `CONNECTED` before POST | Partly mitigated: `streamSend` checks `conn.state == CONNECTED` and falls back to a per-turn stream otherwise. A bounded readiness await would be better. |
| **P1-07** | Provider switch needs serialisation | `setProvider` was added this session and re-bootstraps, but it is not yet coordinated against an in-flight run by a single reducer. |
| **P1-08** | Kotlin 2.4.10 vs Compose plugin 2.4.20 | Build risk, not a proven failure. Deliberate and documented in `docs/toolchain.md`. |
| **P2-10** | Zen-specific model discovery remains | Real. `ZenApi`/`ZenRepository` are the legacy path; `ZenModelsApi` is now also used for provider-neutral probing but is still named for Zen. |
| **P2-11/12** | arm64-only; no local inference | Product limitations, not defects. |

## Proposed local-inference design

The review's larger proposal — a local llama.cpp runtime behind an
OpenAI-compatible endpoint, local-first on capable devices — is **deferred**, and
its phase 0 is exactly the work above. The recommendation is to finish phase 0
before starting it.

Note also that "OpenAI-compatible local endpoint" is not hypothetical here: with
Zen's free tier withdrawn, `openrouter` and a `:free` model is the available
route to testing today. See [providers.md](providers.md).

## Release gates the review proposes

Its checklist is good and most items are unmet. The two that are now enforced by
code rather than intention:

- ☐ **Locked app cannot bootstrap the agent** — now enforced in `bootstrap()`
- ☐ **SSE switch A→B delivers only B events** — now enforced and tested

The rest remain open, in particular "another app cannot invoke privileged Crush
API", which is P0-02 and is not solvable here.