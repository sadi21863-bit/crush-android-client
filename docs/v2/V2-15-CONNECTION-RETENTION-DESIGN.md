# V2-15 L3 — connection tombstone retention: design and implementation

Status: **implemented, 2026-11-24.** The key + ownerEpoch design replaced the
weak-tombstone recommendation after the adversarial review. Production changes
are limited to `ConnectionRegistry` and `ChatViewModel`; the 15 existing
registry cases were retained/adapted and 10 adversarial cases added. Focused
suite 25 green; full gate **1040 tests / 111 suites / 0 failures**, lint 0
errors / 1 pre-existing warning (`v2-19-l3-gate.log`). Committed with the V2-15
batch as `1990118` on `v2-15-lifecycle-hardening` (pushed, not merged to
`main`).

## 0. Review and implementation verdict

| Question | Answer |
|---|---|
| Does the earlier weak-tombstone recommendation survive? | **No, not as the first choice.** It is safe only while a caller holds the connection strongly; it does not help a delayed callback that has only a key/epoch, and its GC semantics are harder to pin. |
| Is a lightweight identity/generation token safer? | **Yes**, if the token is the pair the registry already has: `(instanceKey, ownerEpoch)`. The tombstone map exists only because `isSuperseded` is not given the key. |
| Smallest safe solution | **Pass the key used at attach** and compute supersession from the live entry only. Delete `registrationKey`; no weak refs, no token history, no GC. |
| Does this preserve the current contract? | Yes: "superseded" remains "a different owner epoch currently holds this key"; a revoked key with no newer owner is still not superseded. |

The owner's preference for a lightweight identity/generation token is the right
direction. It is also less code than weak tombstones, not more.

## 1. Current state and the one use that forces the tombstone

`ConnectionRegistry` (`data/crush/ConnectionRegistry.kt`):

| Map | Contents | Lifetime |
|---|---|---|
| `entries` | `key -> Entry(conn, ownerEpoch)` | live registration only |
| `registrationKey` | `conn -> key` | **never removed** |
| `pending` | deferred stops per key | flushed at run end |

`registrationKey` has exactly one consumer: `isSuperseded`'s fallback when the
connection was no longer the live `entries[key]` (then `:202-210`). It existed
because the old signature was `isSuperseded(ownerEpoch, conn)` and the connection
did not carry the key it was registered under. That map was a process-lifetime
lookup from an object to its registration key; it is **deleted** in the
implementation.

The caller **does** know the key: `ChatViewModel.attachEventStream` registers
with `instanceKey` (`:1814-1818`). The implementation captures that exact value
in `registeredConnectionKey` at attach and asks
`isSuperseded(registeredConnectionKey, connectionOwnerEpoch)`. The missing piece
was one field, not a memory structure.

## 2. Precise state model

For one registry key `k`:

```
                 attach(k, A, epochA)                 revoke(k)/supersede
Empty -------------------------------> Live(A, epochA) ------------------> Empty
                                           |
                                           | attach(k, B, epochB)
                                           v
                                     Live(B, epochB)
```

- A **connection registration** is the immutable pair `(k, epoch)` captured at
  attach. The connection object is the live payload; the pair is the identity
  that survives the object.
- `entries[k]` is the only current-owner truth.
- `isSuperseded(k, epoch)` = `entries[k]?.ownerEpoch?.let { it != epoch } ?: false`.

This is the same truth table as today, with the key supplied instead of
recovered from the connection object.

## 3. Invariants

| ID | Invariant |
|---|---|
| I1 | At most one live `entries[k]` exists; `attach` replaces atomically under `lock`. |
| I2 | A connection's registration pair `(k, epoch)` is immutable for that connection's lifetime. |
| I3 | `isSuperseded(k, e)` is true **iff** `entries[k]` exists with a different epoch; false when the entry is absent. |
| I4 | A same-VM rebuild (same epoch, new connection) is not superseded; the live entry is the new connection. |
| I5 | A revoked key with no newer owner is not superseded (own revoke); this is the current contract and is preserved. |
| I6 | The registry holds no strong reference to a stopped/superseded connection; only `entries[k]` (live) and `pending` (deferred stop) may hold one. |
| I7 | `entries` reads/writes are linearizable at the map operation; attach/revoke logical transitions are serialized by `lock`. |

I5 is the subtle one: the contract does **not** promise "once superseded,
forever superseded". A newer owner that attaches and then revokes leaves the
key empty; an older epoch then sees false. If the product wants monotonic
supersession, that is a separate decision (see §7), not part of the retention
fix.

## 4. Adversarial scenarios

| # | Scenario | With key + epoch | Deterministic? |
|---|---|---|---|
| 1 | A is replaced by B while A has pending callbacks | `entries[k]=B`; A's callback calls `isSuperseded(k, epochA)` → true | Yes |
| 2 | A's callback runs after B registers | Same as 1; no object identity needed | Yes |
| 3 | A weak reference to A is cleared before a delayed callback | No weak reference exists; the callback carries `(k, epochA)` values | Yes |
| 4 | Concurrent registration and revocation for the same project | attach/revoke serialize on `lock`; final `entries[k]` is one of the operations' states; `isSuperseded` reads the atomic map | Yes (one linearization point) |
| 5 | Two connections use an equivalent registration key | Same key means same logical surface; `entries[k]` picks the live one; epoch decides supersession | Yes |
| 6 | Rebinding while an operation is in flight | New entry lands; old `isSuperseded` true if epoch differs; deferred stop/flush unchanged | Yes |
| 7 | A connection is revoked while its router processes a request | Entry removed; old `isSuperseded` false if no newer owner, true if one exists; router close path unchanged | Yes |
| 8 | GC between registration, lookup and callback | Nothing in the predicate is GC-sensitive; `k` and epoch are values | Yes |

## 5. Questions answered

1. **Deterministic after A is unreachable?** Yes. The predicate uses
   `entries[k]` and an epoch value; it never dereferences A.
2. **Can weak collection cause an old connection to be treated as current?**
   With the key/epoch design there is no collection to race. With the earlier
   weak map, a caller that still holds A keeps the tombstone alive; a caller
   that does not hold A cannot pass it. The real risk was a callback holding
   only an epoch/null, which weak tombstones cannot help; key/epoch solves that.
3. **Is registration identity sufficient to distinguish generations?**
   `(k, ownerEpoch)` is sufficient for **supersession between owners**. The
   connection object distinguishes same-epoch rebuilds, but those must not be
   superseded, so the object is not part of the decision.
4. **Would a monotonic generation/token be safer?** `ownerEpoch` already is a
   process-monotonic token (`AtomicLong` in `ConnectionRegistry.nextOwnerEpoch`)
   and is unique across VMs. A per-registration sequence adds no safety for the
   current contract; a per-key `lastOwnerEpoch` would add "once superseded"
   semantics, which is a product change (see §7).
5. **Tombstone cleanup without a global lock?** There is no tombstone to clean.
   `isSuperseded` is a single `entries[k]` read; `attach`/`revoke` keep their
   existing `lock`.
6. **Synchronization for registration/revocation races?** Keep `attach`,
   `revoke`, `revokeIfOwn`, `flushDeferredRevocation` under `lock`. A lock-free
   `isSuperseded` read is atomic on `ConcurrentHashMap`; it can observe the
   state before or after a transition, exactly the current one-beat tolerance.
   If strict ordering is desired, read under `lock`; not required for the
   contract.
7. **Memory cost?** Current: one full `CrushConnection` graph per attach,
   retained forever. Weak: graph retained while any caller/reader holds it.
   Key+epoch: no per-attach registry entry; the VM stores one key string and one
   long (the epoch it already has). Optional per-key `lastOwnerEpoch` would be
   one small entry per key, bounded by distinct instances.

## 6. Weak references versus identity tokens

| Property | Weak tombstones | Key + ownerEpoch (recommended) |
|---|---|---|
| Determinism for a delayed callback | Needs the callback to hold the connection strongly | Values only; always deterministic |
| GC race | Possible in principle; mitigated by strong caller, hard to test | None |
| Identity collision | Weak map uses equals/hashCode; must forbid override | No map keyed by object |
| API change | none | `isSuperseded(key, epoch)`; add `connectionKey` field |
| Memory | Full graph while referenced | No registry history |
| Preserves I5 | yes | yes |
| Failure mode | caller with only epoch/null cannot be judged | caller must pass the key used at attach |
| Verdict | fallback only | **recommended** |

A **lightweight token** is still useful as vocabulary: the pair
`(connectionKey, ownerEpoch)` *is* the registration token. Persist that pair in
the VM, not the connection object.

## 7. Optional stronger contract (not recommended in this batch)

If the product decides "once a newer owner has taken this key, an older epoch is
superseded forever, even if the newer owner later revokes", add a per-key
`lastOwnerEpoch: ConcurrentHashMap<String, Long>` updated on attach. Then:

```
isSuperseded(k, e) = entries[k]?.ownerEpoch?.let { it != e }
    ?: (lastOwnerEpoch[k]?.let { it > e } ?: false)
```

This is bounded by distinct keys (small), still no connection tombstone. It
changes I5 and needs an owner decision; do not smuggle it into the retention
fix.

## 8. Implemented design (2026-11-24)

1. `ChatViewModel.registeredConnectionKey` stores the exact `instanceKey` used
   at a successful `attach`; it is read by the stale check and by `onCleared`,
   never by the mutable current binding.
2. The stale check calls
   `isSuperseded(registeredConnectionKey, connectionOwnerEpoch)`; `onCleared`
   revokes the same captured key, not `instanceKey`.
3. `ConnectionRegistry.isSuperseded(key, ownerEpoch)` returns
   `entries[key]?.ownerEpoch?.let { it != ownerEpoch } ?: false`.
4. `registrationKey` is deleted; `entries`, `pending`, `lock`, attach/revoke,
   deferred flush and the epoch allocator are unchanged.
5. `ConnectionRegistryTest` was updated for the new signature and expanded to
   25 cases; no case was removed or weakened.
6. No weak references, LRU, eviction or runtime changes were introduced.

Failure modes pinned:

- **Wrong key**: the authoritative key is captured at attach. The stale check
  and `onCleared` both use `registeredConnectionKey`.
- **Null/never-registered connection**: no captured key means the check is
  skipped; no guessed key is passed.
- **Same-epoch rebuild**: not superseded; the live entry is the new connection.
- **Empty key state**: false (I5); a newer owner that has since revoked does not
  permanently supersede an older epoch unless §7 is adopted.

## 9. Deterministic regression tests (implemented)

All existing `ConnectionRegistryTest` cases are retained, updated for the new
signature, and the 10 adversarial cases below are implemented (25 total).

1. A → B replace: `isSuperseded(k, epochA)` true; `isSuperseded(k, epochB)`
   false; B is the live entry.
2. Delayed A callback after B attach: true even when A is no longer referenced
   by the registry (pass values only).
3. A revoked, no newer owner: false; a same-epoch re-bind is allowed.
4. A attaches, B attaches, B revokes: A sees false (I5). Mark this as the
   current contract; if §7 is ever adopted, this expectation changes explicitly.
5. Same VM, same epoch, rebuilt connection: old false, new false (same owner);
   live entry is the new connection.
6. Rebinding with an in-flight run: deferred stop pins the old connection; after
   the new attach, old `isSuperseded(oldKey, epochOld)` true; flush stops only
   the pinned one.
7. Concurrent attach/revoke barrier: exactly one final live entry; no duplicate
   live reader; `isSuperseded` for the losing epoch is true while the winner is
   live.
8. Unknown key: false (never registered).
9. Retention: after N attach/revoke cycles, the registry exposes **no
   per-connection history** (no `registrationKey` map). A test seam can assert
   `inspect/entries` size is 0 for revoked keys; no GC test is needed.
10. On-cleared stale touch: after `revokeIfOwn`, an old callback with
    `(key, epoch)` and no connection object gets the I5 answer.

## 10. Evidence and authorship

| Path | Role |
|---|---|
| `data/crush/ConnectionRegistry.kt` | `entries`/`pending`, key+epoch predicate, no tombstone map |
| `ui/screens/chat/ChatViewModel.kt` | `registeredConnectionKey` at attach, stale check and `onCleared` use it |
| `app/src/test/.../ConnectionRegistryTest.kt` | 25 cases: 15 retained/adapted + 10 adversarial |
| `docs/v2/V2-15-RUNTIME-LIFECYCLE-AUDIT.md` | L3 finding |
| this document | adversarial review, implemented design and evidence |

## 11. Implementation evidence

**Production files:** `ConnectionRegistry.kt` (remove `registrationKey`, new
`isSuperseded(key, ownerEpoch)`, test-only retained-count seam),
`ChatViewModel.kt` (`registeredConnectionKey`, stale check, `onCleared` revoke
by captured key). **Test file:** `ConnectionRegistryTest.kt` (25 cases; no case
removed or weakened).

**Retention model:** old = `conn -> key` strong map, one full `CrushConnection`
graph per attach, never removed. New = current live entry only; no per-attach
history. A stopped/superseded connection is referenced only by its own VM while
that VM still needs it.

**Semantics verified by tests:**

1. A newer owner supersedes an older owner for the same key.
2. A connection is not superseded by its own registration.
3. Same-epoch rebuilds are not superseded.
4. Revoking a key with no newer owner does not supersede an older epoch.
5. Revoking a newer owner does not create permanent supersession history.
6. Unknown keys return false.
7. Delayed callbacks carrying the captured `(key, epoch)` are deterministic
   after replacement; no connection object or registry history is required.

**Gate evidence:**

| Run | Result |
|---|---|
| Focused `ConnectionRegistryTest` | **25/25 green**, 7m15s (`v2-15-l3-focused.log`) |
| Full gate `v2-19-l3-gate.log` | **BUILD SUCCESSFUL in 30m01s**; **1040 tests / 111 suites / 0 failures / 0 errors / 0 skipped**; lint **0 errors / 1 pre-existing warning** |

**Limits:** there is no JVM `ChatViewModel` harness; the `registeredConnectionKey`
capture and its two uses are source-inspected, not unit-tested. No device,
instrumented or TalkBack run covers L3. L1/L2's previously recorded evidence
gaps (container-level shutdown mapping; real spawned-engine timeout after proxy
startup) remain unchanged. The V2-15 batch is committed as `1990118` on
`v2-15-lifecycle-hardening` (pushed, not merged to `main`); the later L2
terminal-startup correction is recorded in
`V2-15-POST-PUSH-AUDIT-CORRECTION.md`.

The implementation changed two production files and one test file; no
dependency, engine, workflow or unrelated UI change was made.

