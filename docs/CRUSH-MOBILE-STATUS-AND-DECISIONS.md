# Crush Mobile — status & decisions (pointer doc, 2026-11-05)

Standing answer to the owner's builder-context pack and the independent
audit (both pinned at `9dcfa0b`, 2026-11-05): where the work actually
stands past that pin, and what has been agreed. This file is a HUB of
pointers — it never copies long sections; each claim links the ledger
that owns it. Facts that moved after the pack's pin are restated here
from the repo's own records, with what was re-verified live marked.

Last verified: code HEAD `fe9e7e8` (2026-11-08 — the chat output save
surface, CI run 42403223894 green); only docs-only commits collect above it.

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
| `f5d24dc` | **S-04 CLOSED IN CODE:** REST transport fail-closed (per-call resolution, no plain-TCP fallback, honest boot-window wording) | ✅ completed-success |
| `3a3a233` | **S-02 CLOSED IN CODE — Gate-0's last blocker:** central ProjectTrustService (SHA-256 fingerprint consent, gate-before-bind on EVERY path incl. restoreLastBinding, Room v1→v2 additive trust columns, root-level F04 prompt behind an open lock gate, row-tombstone-is-consent-clear) + 13 verdict/roundtrip tests | ✅ completed-success; **A03 device drive PASSED same day** (capabilities §Gate-0 closures) |
| (this docs row) | **GATE 0 DEVICE-CLOSED** fold: A03 restore-trust drive measured (blocked-restore pair, zero execution pre-consent, marker-on-consent, `p536fa26e` rebind); + the incremental-dex launch-crash saga with its operational rule (clean-rebuild first) | — |
| (docs row 2, same day) | **E6/E5 device stress drive measured:** E6 PASS across 5 switches A<->B (stop-on-switch chains, fresh children 3141->3951->4912, children <=2 at every instant, per-instance socket rebuilds, zero stale retries/crashes, pid stable; two-row limitation: budget ceiling not exercisable) — **E5 NAV DEFECT measured+reproduced:** back ping-pongs Projects<->chat, exit impossible, code anchor `NavGraph.kt:64-66` (re-push stacking); engine-safe boot repeats only | — |
| `f516d1f` (2026-11-06) | **E5 nav fix:** Projects.onOpenProject navigates(Chat) with `popUpTo(chat, inclusive)` — replaces the stack back to a single chat entry instead of re-pushing; device re-verified same session (D1-D8: back-from-chat exits on both paths, row-open binds + stop-on-switch intact) | ❌ completed-failure — the pre-hardening socket-identity flake (capabilities §E5-fix+flake row); the E5 code itself rides every later green run |
| `526b3a6` (2026-11-07) | **S-04 socket-identity test hardened:** subscribe-before-attach (`CoroutineStart.UNDISPATCHED`) removes the publish-vs-subscribe window; budget 15s→30s (test pins identity+shape, not wall clock); no prod change — full diagnosis in capabilities; this is also the chain that gh-verifies E5 | ✅ completed-success (gh-verified 2026-11-07, run 42309481087, 3m45s) |
| `5f14dfc` (2026-11-07) | **Gate-2 batch 1 — P4 record surface:** per-turn collector → ReviewHistory runtime store (MAX_CARDS=8) + chat changed-files footer + "Review all turns" rail + review cards with hunks; collector after-image pairing pinned by tests (562→587) | ✅ completed-success (run 42320008115) |
| `c8dd154` (2026-11-07) | **Gate-2 batch 2 — revert + export rows:** per-file Revert (confirm dialog; POST-TURN GUARD truth table 25 rows; latest-completed-turn gate) + Export a copy (SAF CreateDocument, byte-exact read of disk-now) | ✅ completed-success (run 42344434695, 3m34s) |
| `bf5019a` (2026-11-07) | **Gate-2 export fix — found live on device:** Export a copy recorded the pending target but never called `exportLauncher.launch` (silent no-op: no DocumentsUI, no log); fix records AND launches in one callback with the bare-filename suggestion | ✅ completed-success (run 42390123731; device-proven byte-exact after fix) |
| `fe9e7e8` (2026-11-08) | **Chat output save surface (owner-directed batch):** code-block header Copy + Save-as-file chips (FULL block saved, never the display trim; fence tag = untrusted model output, mapped/sanitized suggestion) + message-level Copy message / Save as Markdown; saver mirrors the device-proven Review export contract; rows hidden while streaming and on failed cards | ✅ completed-success (run 42403223894; device-proven same morning: message save `message-062916.md` 72 bytes byte-exact + toast, code save `code.py` 35 bytes `56cc99b7…` od-verified equal to the displayed block + toast, failed-card guard live; S-01 untouched) |

**GATE 0 = CLOSED on both halves.** Code: `7ec34ed` (S-01), `3a3a233`
(S-02), `f5d24dc` (S-04), wording-honesty in S-03's ledger row. Device:
A03 restore-trust drive PASSED 2026-11-06. Residuals ride the E6 stress
pass: 4 JVM-only A03 variants (cancel / changed-crushrc / noConfig-flip
/ real-install migration), REST boot-window row, S-01 Failed-badge row.

Branch state truth (2026-11-08 morning): HEAD/main/origin-main =
`fe9e7e8` (chat output save surface) ← `8e0ab08` (P5/P6 prep +
research, docs) ← `40a6be4` (Gate-2 evidence fold, docs) ← `bf5019a`
(export launch fix) ← `c8dd154` (Gate-2 batch 2) ← `5f14dfc` (Gate-2
batch 1) ← `526b3a6` (test hardening) ← `f516d1f` (E5 nav fix) ←
`053ccb5` (Gate-1 fold, docs) ← code rows above. Pack-archaeology note (unchanged): `f5d24dc`
and the pack-referenced `1d229de`/`00964da` stay reachable for the
owner pack's SHA references.

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
  consent. Code-confirmed today (2026-11-05). **FIXED IN CODE**
  (`3a3a233`, Gate-0's last blocker): central ProjectTrustService
  (SHA-256 fingerprint consent, gate-before-bind on EVERY path incl.
  restoreLastBinding, Room v1→v2 additive trust columns, root-level F04
  prompt behind an open lock gate, row-tombstone-is-consent-clear) +
  13 verdict/roundtrip tests. Device acceptance: pack matrix row
  **A03 (restore-with-changed-crushrc)** — sentinel crushrc dropped against the last-bound project while the app
  is dead; cold boot must show the SAME F04 dialog and execute NOTHING
  before consent. **A03 device drive PASSED 2026-11-06** (capabilities
  §Gate-0 closures: blocked-restore pair, zero execution pre-consent,
  marker-on-consent, `p536fa26e` rebind).
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
| 0 | trust + chat fail-closed + REST transport hardening (S-01, S-02, S-04 above) | **CLOSED, both halves** (supersedes this cell's 2026-11-05/`1d229de`-era PARTIAL reading): S-01 `7ec34ed` + S-04 `f5d24dc` + **S-02 `3a3a233`** (central ProjectTrustService, gate-before-bind on every path incl. restoreLastBinding) — **A03 device drive PASSED 2026-11-06** (capabilities §Gate-0 closures); residuals ride the E6 stress pass: 4 JVM-only A03 variants, REST boot-window row, S-01 Failed-badge row |
| 1 | Gate-7 runtime registry ([`design-runtime-registry-2026-11-05.md`](design-runtime-registry-2026-11-05.md)); pack rows **E1/E3 SHIPPED** (`1d229de` phase-1 interlock + readiness/starting-watchdog); **E2/E4 SHIPPED** (`d818a9c` container registry + connection reclaim); **E6 device stress drive PASS** (2026-11-06, 5 switches, capabilities §Gate-1 device stress drive; two-row limitation noted); **E5 back-nav fix SHIPPED + device-verified** (`f516d1f`, D1-D8 all pass incl. back-from-chat exits on both paths; gh-verified via `526b3a6`); S-04 flake diagnosed + test hardened (`526b3a6`). Residuals: second imported project row to exercise the 2-non-legacy-children ceiling on device; optional nav-restore re-boot dedup | closed 2026-11-07 (residual drives optional) |
| 2 | Code v1 (Review surface, revert, workspace polish) | **surface DEVICE-PROVEN 2026-11-07** (capabilities §P4 Gate-2: multi-file turn +2 -0, hunks, byte-identical revert `dcb75b2b…`, multi-card review, export byte-exact after `bf5019a` fix; both P4 proof rows closed). Residuals: workspace polish, journal persistence of history across restarts (runtime-only store by design), history card cap choice (MAX_CARDS=8), model-rumor flake ops note (free models flap PAYMENT_REQUIRED/timeout — a retry works; failed turns leave no record lines, empty turns record `+0 -0` but create no cards); owner-directed follow-up batch: chat-output Copy + Save-as-file (app-side, S-01 deny-all untouched) — DELIVERED AND DEVICE-PROVEN 2026-11-08, `fe9e7e8` | ✅ surface proven; residuals queued |
| 3 | Frontend v2 | pack's **BLOCKED condition is lifted** — Gates 0+1 are closed (2026-11-06/07); not started beyond that (2026-11-08 refresh) |
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
