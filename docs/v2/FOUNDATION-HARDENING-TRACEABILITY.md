# Foundation hardening — unified traceability register (2026-11-28)

Baseline: `edac95d0458c684884a7e55e805ea7e5e7017da8` on
`v2-16-planning-baseline`. Five parallel source audits covered engine lifecycle
and isolation (A/B), execution/recovery/clocks/concurrency (C/D), networking and
security (E/F), persistence/UI/performance (G/H/I), and binary/CI/release/docs
(J/K). This register is the single working inventory; the final report will
record the implemented status and evidence.

Status vocabulary: SOURCE-VERIFIED, JVM-TESTED, ROBOLECTRIC-TESTED,
INSTRUMENTED-TESTED, DEVICE-VERIFIED, CI-VERIFIED, NOT-PERFORMED, UNAVAILABLE,
OWNER-DECISION-REQUIRED.

| ID | Requirement / finding | Implementation target | Acceptance test | Status / decision |
|---|---|---|---|---|
| A10 | Run-in-flight marker cleared with mutable instanceKey after project switch (HIGH) | Freeze `instanceKey` in `RunHandle`; use it in `abandonRun` and `setWorkActive(false)` | JVM: mark pA, switch to pB, finish run → pA marker cleared, pB untouched | IN SCOPE — implement |
| A9 | Older-epoch connection attach can evict newer reader | Guard `ConnectionRegistry.attach` against `previous.ownerEpoch > ownerEpoch` | JVM: attach epoch2 then epoch1 → epoch2 stays owner, late conn stopped | IN SCOPE — implement |
| A5 | Exec startup failure leaves egress bridge running | Stop bridge in exec catch before returning `Failed` | Robolectric fake processStarter throwing → no proxy | IN SCOPE — implement |
| A12 | Proxy socket leak if stop during CONNECT handshake | Register sockets immediately or close in `serve()` finally | JVM proxy test with blocked CONNECT + stop | IN SCOPE — implement |
| A7 | Stale socket file handling not proven | Document only (retry budget bounds it) | N/A | NOT-PERFORMED / low risk |
| A4 | `EngineSupervisor.restart()` sequencing latent; no production caller | No change; document | N/A | NOT APPLICABLE |
| B1 | Lossy `"p"+first8` instance key collisions | Guard same-key/different-ref.id as a real switch (no derivation change) | JVM: colliding prefix → distinct runtime or refused rebind | IN SCOPE — evaluate |
| B4 | Project deletion decision untested | Pure helper + JVM temp-dir test | JVM: imported folder survives, internal tree deleted | IN SCOPE — implement if feasible |
| C4/5 | Plain `runCatching` swallows `CancellationException` despite claim | Use `runCatchingNonCancellation` in store/container/VM writes | JVM: fake DAO throws CancellationException → escapes, no state write | IN SCOPE — highest value |
| C8 | `AlreadyApplied` does not reconcile controller | Read exact row, mark controller terminal with stored reason | JVM: row COMPLETED, controller RUNNING → controller COMPLETED | IN SCOPE |
| C13 | Controller lookup ignores explicit executionId | Explicit ID authoritative and fail-closed; run-ID fallback only when absent and unambiguous | `FoundationClosureTest` identity mismatch pin | FIXED-SOURCE + JVM-TESTED (2026-11-28 closure) |
| C14 | Declaration replacement unsynchronised | Serialize declaration map operations per key | Latch test: one ACTIVE per key | IN SCOPE |
| C1/15 | Superseded/retired events reach controller | Guard controller on `event.superseded`; permit terminal durable write only to own execution | Adapter→container→controller test | IN SCOPE |
| C3 | Stale/Conflict/IntegrityFailure collapse to one reason | Explicit reason map | Parameterized test: 3 outcomes → 3 reasons | IN SCOPE |
| C10/11/12 | Buffer head removal, clear sync, replay re-peek | Head-only removal, single atomic lifecycle monitor, tombstone, re-peek loop | `FoundationClosureTest` latch/order tests + 5 existing buffer pins | FIXED-SOURCE + JVM-TESTED (2026-11-28 closure) |
| C-replay | Deferred replay had no reliable retry trigger after ACK | `scheduleReplay` bounded delayed retries + startup re-drive + explicit invalidation on exhaustion | `FoundationClosureTest` unavailable-then-success; container scheduler source-verified only | FIXED-SOURCE + STORE JVM-TESTED; container scheduler NOT INTEGRATION-TESTED |
| C6 | Per-turn finishError handles only Applied | Handle all typed outcomes | JVM store+controller per outcome | IN SCOPE |
| C18 | Live chat bubble shows cancelled run as plain completion | Use `rc.cancelled` in bubble | Compose/state test | IN SCOPE |
| E-04 | Send loop has no `attempt++` (unbounded if policy flips) | For-loop bounded to 2 | State test: exactly 2 POSTs with policy true | IN SCOPE |
| E-05 | OkHttp `retryOnConnectionFailure(true)` can replay POST | Disable on REST POST client only | MockWebServer DISCONNECT_AT_START → 1 request | IN SCOPE |
| F-02 | logcat branch passes original throwable (unmasked) | Mask throwable before `Log.*` | Synthetic key never unmasked on either sink | IN SCOPE |
| F-03 | Crash archive / on-screen diagnostics unmasked | Mask at recorder choke point | Synthetic key absent from archive and `crash.text` | IN SCOPE |
| H-03 | Relative-time labels frozen at composition | Bounded ticking clock in list/content | Compose test with injected clock | IN SCOPE |
| I-01 | ExecutionController never prunes terminal records; O(n) publish | Bounded terminal-record policy | JVM: 1000 terminals → capped map, active intact | IN SCOPE |
| Epoch bump | `executionEpochs[key] = old+1` read-modify-write | `compute` atomic bump | JVM concurrent invalidation test | IN SCOPE |
| I-02 | Transcript unbounded (R26) | Owner decision: cap vs 10-hour profile | OWNER-DECISION-REQUIRED | DOCKET |
| H-05 | Accessibility 2.0 font / RTL / landscape / TalkBack speech | Hardware unavailable | NOT-PERFORMED | DOCKET |
| J1 | Extracted binary hash not locally re-derivable | Dated addendum with local hash + re-derivation command | Local `Get-FileHash` | DOC — implement |
| J5/J10 | CI engine-less; no branch runs | Dated addendum; no CI claim | Workflow re-read | DOC — implement |
| J6-J11 | Track B0-B6 acceptance not performed | Dated addendum, rows NOT-PERFORMED | Manual review | DOC — implement |
| J16-J19, H-06, I-05, F-09, E-06, F-01 | Stale docs/evidence claims | Dated addenda, no history rewrite | Cross-reference re-read | DOC — implement |
| J20 | LH-1 preparation only | No autonomous feature | Documentation check | PRESERVE |
| J21 | Gate 7 not authorized | Preserve boundary | N/A | NOT-PERFORMED |
| D3/X1/A3 | Prompt identity unresolved | No implementation; `prompt_ref` stays NULL | N/A | OWNER-DECISION-REQUIRED |
| D1/D2/D4/D5/D7 | Owner-controlled decisions open | Research + recommendation in docket | N/A | OWNER-DECISION-REQUIRED |
| R37 | Device fault bridge | Not implemented | N/A | BLOCKED |

Accepted V2-0…V2-9, V2-12…V2-15 and A1 contracts are preserved; no accepted test
or security control is weakened.
