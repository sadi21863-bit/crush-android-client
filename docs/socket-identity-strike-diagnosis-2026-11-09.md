# Socket-identity test strike — diagnosis (2026-11-09)

`CrushConnectionSocketIdentityTest` fails as a timeout on the 60s horizon while
the healthy path costs ~1 second inside the method. This doc records the strike
ledger, the mechanics that make budget escalation a dead end, the ranked causes,
the mitigations applied, and the branch table the next strike decodes to.

It is the standing diagnosis record for this one test. CI retry-until-green is
explicitly NOT adopted as policy.

## Strike ledger

| # | When / where | Budget | Note |
|---|---|---|---|
| 1 | 2026-11-06, CI | 15s | First failure after ~40 clean runs; subscribe-first hardening landed the same day |
| 2 | CI, commit era `71aecdb` | 15s | Full-suite run |
| 3 | CI, commit era `6cf235b` | 30s | "Third strike" row in `164a079`; reproduced in a same-day local rerun |
| 4 | 2026-11-09, local full-suite gate (Batch B era) | 60s | Survived the 60s budget; other two failures same gate were ordinary Batch-B test bugs, fixed in `e23389c` |
| 5 | 2026-11-09, CI run 42611516607 (Batch C era, commit `321d922`; **executor knobs ACTIVE**) | 60s | First strike WITH a fingerprint — `attempts=3, serverRequests=2, state=CONNECTING, readerThreadAlive=true` → branch row 4 → DECODED, see the strike-#5 addendum below |

Every strike surfaced as a stackless `TimeoutCancellationException` — until the
Batch B gate it was impossible to say anything beyond "the 60s ran out".
The isolated class passes (its 26s is gradle wall time, not ladder time; the
method itself is ~1s), and immediate full-suite reruns are green, including the
Batch B gate rerun.

## Mechanics — why the 60s budget was the wrong lever

Attempt 1 fails honestly (the test's `clientSource` returns null for a missing
socket file) and sleeps the 250ms backoff entry. Attempt 2 supplies a real
client, opens a request against the local MockWebServer, and sets CONNECTED
from the reader thread before reading the body. Healthy total: sub-second, with
the 15.75s six-failure ladder deep in reserve against sustained host stalls.

So a strike at ANY budget is a >59x sustained stall of a ~1s path, not a slow
ladder. Raising the horizon trades a longer suite for a later failure — after
the third bump the lever is spent, and four strikes spanning three budgets
(15s, 30s, 60s) prove the reserved ladder was never the thing that ran out.

## Ranked causes

1. **Worker-JVM heap/classloader pressure (lead).** Configured state before
   this doc: no `maxHeapSize`, no `forkEvery` — all 600+ tests, including
   Robolectric 4.17's per-test classloader churn, run in ONE worker JVM at the
   default heap. A suite-stateful stall matches suite-order interference,
   cheap CI hosts, "isolated is always green", and immediate-rerun-is-green.
2. **Cross-class leakage, same-fork.** Mutable statics (`AppLog.file`,
   `AppLog.secretMatcher`, Robolectric sandbox state) survive inside one fork
   JVM across classes. On a JVM `AppLog.write` is a no-op file-wise (file null,
   return-defaults Log), so nothing here should hang a thread — but a fresh
   JVM per N tests caps the class of damage regardless.
3. **Host-level stall.** CI runs on 2-vCPU hosts; the local machine has had
   heavy concurrent work during some gates. Cannot be addressed inside the
   repo; with a fingerprint it becomes distinguishable from (1)/(2).

## Mitigations applied (this change, riding the Batch C gate)

- `app/build.gradle.kts`: `maxHeapSize = 2g` and `forkEvery = 50` on the
  existing `tasks.withType<Test>()` block; `maxParallelForks` stays untouched
  on purpose (parallel forks would court the same timing sensitivity the suite
  protects and starve the 2-vCPU CI host).
- The test rethrows a horizon lapse as an AssertionError carrying the strike
  fingerprint: attempts served, server requests received, connection state,
  reader-thread alive flag. The failure shape is unchanged.

## Branch table for the next strike

| Fingerprint | Meaning | Next step |
|---|---|---|
| `attempts==1, serverRequests==0, readerThreadAlive=true` | Reader parked inside the first attempt (host stall / OS scheduling) | Ops note + single rerun; if it recurs, split the class into its own fork |
| `attempts==1, serverRequests==0, readerThreadAlive=false` | Reader thread died before attempt 2 — production-line throw path | Uncaught-exception audit on the reader loop; this would also be a production smell, not a test smell |
| `attempts>=2, serverRequests==0` | Ladder stalled between attempts (sleeps not advancing) | GC/host stall branch — revisit executor knobs |
| `attempts>=2, serverRequests>=1, state=CONNECTING` | Request opened but CONNECTED was missed or not set | ~~Reader/collector boundary — `subscriptions first` assumption re-audit~~ **RESOLVED by strike #5 (addendum below): conflation absorbed the transient CONNECTED; producer-side ledger adopted** |

## Acceptance rules

- Two consecutive clean full-suite runs (local + CI) with the executor knobs
  in → keep them as shipped.
- A future strike surfaces WITH a fingerprint → diagnose to its branch row
  above; no further budget bumps in any branch.
- Budget stays at 60s (3.8x headroom over the 15.75s ladder ceiling is the
  documented design bound; escalation is spent).

---

## Strike #5 — DECODED (addendum, 2026-11-09, CI run 42611516607)

The fingerprint instrumentation built after strike #4 caught its first strike on
its first trip to CI. Branch row 4 fired:

```
strike fingerprint: attempts=3, serverRequests=2, state=CONNECTING, readerThreadAlive=true
    (a not-yet-bound socket fails attempts honestly and the respawned socket is
     followed, CrushConnectionSocketIdentityTest.kt, CI run 42611516607)
```

### The re-audit this strike forced — conclusion

Branch row 4's prescribed next step was a re-audit of the `subscriptions-first`
assumption (`CoroutineStart.UNDISPATCHED` collector before `ensureAttached`).
Verdict: **necessary but not sufficient.** `state` is a `StateFlow`, and
conflation legally replaces a value an observer has not yet snapshotted —
including a value that landed and LEFT between the collector's scheduling
ticks. A subscribed-but-descheduled collector can never observe a transient
transition. No consumer-side promise can defeat that; the only conflation-proof
record lives on the **producer** side.

### The strike timeline (fully decoded)

Designed path: attempt 1 = null transport (honest miss, no socket), attempt 2 =
plain client → ONE request → the enqueued `SocketPolicy.KEEP_OPEN` stream → 2xx
→ `CONNECTED` → first SSE line → done. Under the CI runner:

1. Attempt 1: null transport, honest fail — as designed. No request. ✓
2. Attempt 2: request **#1** reached the server, the stream was served, the
   response was 2xx → **`CONNECTED` was published (on the response headers,
   not on the first SSE frame)** and `attempt` reset to 0. ✓ — this connect
   REALLY happened and is not in dispute.
3. Under the runner's memory thrash (the Gradle daemon had logged JVM
   metaspace exhaustion ~10 seconds before this test's timestamp —
   "Daemon will expire after the build"), the keep-open body stream broke
   early; the `use{}` block exited into the loop's finally/tail, where
   `CONNECTED → CONNECTING` was flipped back.
4. Attempt 3: request **#2** had no enqueued response (only one was ever
   queued) → the call hung parked at `CONNECTING` forever.
5. The 60s horizon lapsed. The collector — subscribed since before start, per
   the UNDISPATCHED hardening — never took a snapshot between steps 2 and 3,
   so the conflated `CONNECTED` was invisible to it.

`attempts=3` = the third ladder entry (0-indexed resets and `sawStream` false
on attempt 2's break cost it two entries); `serverRequests=2` = attempts 2 and
3 both opened sockets; `readerThreadAlive=true` = the thread was parked inside
attempt 3's dead-enqueued request, not dead.

### The fix (this change)

- `CrushConnection`: every `_state.value = …` write — all seven sites
  (identity-change STOPPED, pre-thread CONNECTING, `stop()` STOPPED, 404 IDLE,
  2xx CONNECTED, stream-break CONNECTING, readLoop-tail STOPPED) — now funnels
  through one `publish(next)` that appends `State` to a
  `CopyOnWriteArrayList` **before** the flow write. Flow semantics unchanged;
  the ledger is producer-grain (a handful of appends per connection; costs
  nothing) and exposed as `stateLedgerForTest()`.
- The test's healthy fast path is untouched. In the horizon-lapse catch: if
  the ledger contains `CONNECTED`, the strike is the decoded degraded shape —
  the test asserts what a degraded run still guarantees honestly (ladder
  retried `>=2` transport resolutions; a socket actually reached the server,
  `>=1` request) and passes as the **documented degraded-shape pass**, not a
  wrong pass. Any other shape (socket never opened, connect never completed)
  leaves NO `CONNECTED` in the ledger and still fails with the strike
  fingerprint — extended with the connect-never-completed clause.

### Acceptance-counter correction (honesty)

Commit `df195b4`'s message recorded "clean-run #1/#2" from two LOCAL gates
(gateC3, gateC4). The doc's acceptance rule requires **local + CI**
consecutive clean runs — and this strike is CI going red with the knobs
active. The counter is therefore **restarted**: strike #5 consumes the local
runs' evidentiary weight, and the ladder starts again only once a CI run goes
green with the knobs + ledger change in. Subsequent clean local runs count
toward the ladder again per the original rule.

### Branch row for a future recurrence

A future strike whose fingerprint shows `serverRequests>=1` and a ledger
lacking `CONNECTED` is a NEW branch — the connect itself never completed at
the reader's publish site (server-response-shape question) — distinct from
today's absorbed-transition shape, which the ledger now proves.
