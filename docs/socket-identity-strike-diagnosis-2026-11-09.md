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
| `attempts>=2, serverRequests>=1, state=CONNECTING` | Request opened but CONNECTED was missed or not set | Reader/collector boundary — `subscriptions first` assumption re-audit |

## Acceptance rules

- Two consecutive clean full-suite runs (local + CI) with the executor knobs
  in → keep them as shipped.
- A future strike surfaces WITH a fingerprint → diagnose to its branch row
  above; no further budget bumps in any branch.
- Budget stays at 60s (3.8x headroom over the 15.75s ladder ceiling is the
  documented design bound; escalation is spent).
