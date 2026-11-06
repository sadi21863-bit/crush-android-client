# Crush Mobile — status & decisions (pointer doc, 2026-11-05)

Standing answer to the owner's builder-context pack and the independent
audit (both pinned at `9dcfa0b`, 2026-11-05): where the work actually
stands past that pin, and what has been agreed. This file is a HUB of
pointers — it never copies long sections; each claim links the ledger
that owns it. Facts that moved after the pack's pin are restated here
from the repo's own records, with what was re-verified live marked.

Last verified: HEAD `f5d24dc` (2026-11-05/06 night).

---

## 1. Commit chain (exact, sha-verified 2026-11-05 against the local repo)

| SHA (2026-11-05) | What | CI |
|---|---|---|
| `8da8d66` (19:20) | docs parallel batch: Gate-7 runtime-registry design memo; live wedge **shape 2** captured (loss of delivery, not death); S6 ask-for-shell recon map; 3 upstream issue drafts | not recorded |
| `42536e8` (19:40) | docs: S5 no-card mystery RESOLVED in upstream source (v0.99.0 bash safe-read-only prefix bypass); `kill`/`killall` flagged as upstream-notable | not recorded |
| `9dcfa0b` (20:15) | docs: A06 card fixture MEASURED (mkdir probe, chained command, deny honored); plan supersession fold; upstream candidate 4 drafted. **← the owner pack + audit are pinned HERE; everything at this row and earlier is what they saw** | ✅ completed-success (gh-verified 2026-11-05) |
| `00964da` (21:25) | S6/Gate-7 phase-1 wedge work (pre-amend SHA of the same work; kept for the pack's records) | ✅ completed-success (gh-verified 2026-11-05) |
| `1d229de` (21:26) | **S6/Gate-7 phase 1 wedge fixes** (born-or-never interlock, readiness reorder, 90 s starting watchdog, +tests) — local gate GREEN 9m54s, 502 tests | ✅ completed-success (gh-verified 2026-11-05) |
| `ded85de` (23:0x) | docs: status hub + README transport facts + plan-header supersession + capabilities entries for the three owner-pack gaps | — |
| `d818a9c` | **Gate-7 phase 2 LANDED:** EngineInstanceRegistry (one runtime per key forever, never-evict, budget 2 non-legacy live children, lookup-or-noop stop) + ConnectionRegistry (owner-epoch supersession, defer-while-run-in-flight, revoke-if-own, reclaim predicate) + container/VM wiring + 28 pure tests | — |
| `7ec34ed` | **S-01 CLOSED IN CODE:** chat tool-policy fail-closed (typed verdict + verified-by-readback; Chat refuses to spawn on any failure shape) | — |
| `f5d24dc` (HEAD) | **S-04 CLOSED IN CODE:** REST transport fail-closed (per-call resolution, no plain-TCP fallback, honest boot-window wording) | local gate GREEN 9m13s shared across the three batches; CI pending-at-write |

Branch state truth: HEAD/main/origin-main = `f5d24dc`; `1d229de` and the
amend-sibling `00964da` are kept reachable for the pack's SHA references.

## 2. Capability ledger

Single source of truth: [`capabilities.md`](capabilities.md) — the
"Verified working / Implemented, unverified / Known defects" sections
carry their own per-row dates and device evidence. One-line device
timeline: **every verification rides one physical device (`bbf8ca69`,
arm64, Android 10 / MIUI); MIUI logcat is lossy for our lines, so
`app.log` is the evidence channel since `8eb0477`** (see the S5 drive
section and the 2026-11-05 A11/A12 entry there).

## 3. Risk register (owner-pack IDs retained)

Owner-pack references below are the pack/audit finding IDs
(S-xx / P0-x / P1-x / A-xx) as issued 2026-11-05 at pin `9dcfa0b`.

### Blocking (Gate-0 / Gate-1 scope)

- **S-01 (pack P0-2) — chat tool-policy injection is FAIL-OPEN.**
  `CrushEngine.kt` (start()'s pre-spawn window, ~:253-257 as read at
  `1d229de`) wraps `injectChatToolPolicy` in `runCatching { ... }
  .onFailure { log; }` and spawns anyway — an injector failure starts the
  chat engine with tools visible under a "Chat - no tools" label.
  Code-confirmed in repo (2026-11-05). **FIXED IN CODE** (`7ec34ed`,
  same evening): typed verdict + verified-by-readback; Chat now REFUSES
  to spawn on any failure shape with an honest `Failed(willRetry=true)`
  badge. Device acceptance row (the Failed-badge shape + a healthy reboot
  of the pool) queued for the next drive.
- **S-02 (pack P0-1) — last-binding restore bypasses the crushrc trust
  dialog.** `AppContainer.kt:334-359` `restoreLastBinding()` calls
  `applyBinding(EngineBinding.Project(ref))` directly (:358); the F04
  consent gate lives only in the interactive `ProjectsScreen.kt:337-361`
  path. A cold restore onto an imported folder carrying a project-root
  crushrc executes it (S1b-measured bash, twice per attach) with NO
  consent. Code-confirmed today (2026-11-05). **Fix queued next.**
  Device acceptance: pack matrix row **A03 (restore-with-changed-crushrc)**
  — sentinel crushrc dropped against the last-bound project while the app
  is dead; cold boot must show the SAME F04 dialog and execute NOTHING
  before consent.
- **S-03 — upstream safe-list waves `kill`/`killall` and wrapper args
  through without approval (v0.99.0).** Upstream candidate **4** in
  [`upstream-outreach-plan.md`](upstream-outreach-plan.md)
  (§Candidate 4; discussion-first form — the **wording honesty** rule:
  a confirm-the-floor + release/docs ask, never a vulnerability report).
  Fixed on upstream main 2026-10-30; no tagged release carries it yet
  (pin-floor ask). File awaits owner go + its owe-list (device measure).
- **S-04 (pack P1-6; review lineage F11) — REST keeps the silent
  plain-TCP fallback.** `CrushApi.kt:101` (stream client, feeding
  `client`): `okClient(socketPath, plain) ?: plain` — configured-but-
  absent UDS socketPath silently falls back to `127.0.0.1` plain HTTP,
  the pre-2026-10-18 unisolated transport (`p1-attack-surface.md` §4/§8).
  Code-confirmed (2026-11-05). **FIXED IN CODE** (`f5d24dc`, same
  evening): typed per-call `RestTransport` (Uds/Unavailable/TcpForTests),
  init guard against the bare construction, honest scrubbed boot-window
  wording, strictly better heal-on-bind than the old pinned client.
  Device acceptance (boot with a pre-binding attach: the ladder runs
  without any TCP attempt) queued for the next drive.

### Standing risks (not blocking Gate-0)

- **A07 (pack)** — duplicate/aliased imports converge to one canonical
  row (plan §7 A07; local lineage in the import-removal defect entries).
- **Crash-atomic removal (pack A08/A15)** — a crash during import/removal
  must never strand rows or touch user files (plan §7 A10 family).
- **Snapshot honesty** — "not measured" ≠ "no changes": coverage limits
  stated, never an unqualified "clean" (plan §7 A13 / F10).
- **3k-line ChatViewModel extraction** — gradual RunCoordinator-style
  extraction, no rewrite (plan §7 F12; pack sizes it at ~3k lines).

## 4. Agreed product decisions (header rows only — details live in the owners)

Accepted by the owner 2026-11-05 against the pack (header **00**, §5);
mirrored locally with their full reasoning in the plan doc
[`chat-code-modes-plan-2026-11-04.md`](chat-code-modes-plan-2026-11-04.md) §5
(recorded 2026-11-04, user-answered):

| Decision | Choice |
|---|---|
| Q1 navigation | choose-at-creation; tabs + in-session toggle backlogged selectively |
| Q2 chat tool policy | deny ALL tools in v1; smart-transfer + read-only allowlist deferred |
| Q3 default mode | fixed Chat default; Settings toggle backlogged |
| Q4 legacy history | kept, on user delegation; never a silent wipe |

## 5. Gate roadmap (owner-pack gate numbering; STATUS is the row's truth)

| Gate | Content | STATUS (2026-11-05, at `1d229de`) |
|---|---|---|
| 0 | trust + chat fail-closed + REST transport hardening (S-01, S-02, S-04 above) | **PARTIAL** — S-01 landed (`7ec34ed`) + S-04 landed (`f5d24dc`); **S-02 (restore trust) still OPEN, next batch** |
| 1 | Gate-7 runtime registry ([`design-runtime-registry-2026-11-05.md`](design-runtime-registry-2026-11-05.md)); pack rows **E1/E3 SHIPPED** (`1d229de` phase-1 interlock + readiness/starting-watchdog); **E2/E4 SHIPPED** (`d818a9c` container registry + connection reclaim); **E5/E6 QUEUED** (stale stacked entries/back nav; device stress drive) | partial |
| 2 | Code v1 (Review surface, revert, workspace polish) | QUEUED |
| 3 | Frontend v2 | **BLOCKED per pack until Gates 0+1 land** |
| 4 | handoff (Chat→Code P6-style seam) | QUEUED |
| 5 | intelligent UI | QUEUED |
| 6 | compat / Termux L1+ | QUEUED (after modes; roadmap P7 dependency) |
| 7 | release | QUEUED |

Numbering caution: the plan doc §7's "G1≈S0 … G9≈Termux" is the older
review-fold mapping, a DIFFERENT family. Use the pack's Gate 0–7 rows
above for status; map back to slices only when reading the plan doc.

## 6. Upstream filings

- Awaiting owner go: [`upstream-outreach-drafts-2026-11-05.md`](upstream-outreach-drafts-2026-11-05.md)
  (candidates 1–3 fully drafted, nothing filed) and candidate 4 in
  [`upstream-outreach-plan.md`](upstream-outreach-plan.md) (the S-03
  discussion-first draft).
- On record: charmbracelet/crush **issue #4222** filed by the owner —
  gh-verified 2026-11-05 (OPEN, created 2026-11-03, author `sadi21863-bit`,
  "Plan catalog drift: agent/init 500s 'large model not found in provider
  config'…"); draft candidate 3's body cites it as the ask-delivery
  precedent. Any further filings need the owner's explicit go per the
  standing hygiene note in the drafts file.
