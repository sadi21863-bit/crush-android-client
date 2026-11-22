# Crush Mobile — status & decisions (pointer doc, 2026-11-05)

Standing answer to the owner's builder-context pack and the independent
audit (both pinned at `9dcfa0b`, 2026-11-05): where the work actually
stands past that pin, and what has been agreed. This file is a HUB of
pointers — it never copies long sections; each claim links the ledger
that owns it. Facts that moved after the pack's pin are restated here
from the repo's own records, with what was re-verified live marked.

Last verified: code HEAD `af178f8` (2026-11-14), docs HEAD `2fef885`. The
v0.100.0 merge `6800c0e` remains CI-green (run 43227604396); `7346e20` (the
fetch-default ratchet + `FetchScriptPinTest`) carries a local gate of
**689/0/0 across 74 suites** and CI run **43244539199 GREEN**; the #9 fix
`b7b4ae5` and the AFA review-hardening `af178f8` carry local gates of 715/76
and **716/0/0 across 76 suites** (lint 0 errors / 1 warning). CI run
**43592253901 GREEN** (3m12s) covers the `2fef885` push. Only docs commits
collect above the code HEAD. Later record: §5.10 (2026-11-19) re-verifies
the repository at code HEAD `1679f25`; the 2026-11-14 figures above remain
that baseline.

---

## 1. Commit chain (exact, sha-verified; rows added through 2026-11-14)

| SHA (2026-11-05 → 2026-11-14) | What | CI |
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
| `71aecdb` (2026-11-08) | **Repo-wide current-state docs refresh sweep** (subagent-audited, 27 files: README/provider toolchain/decisions-open/ADR-0001 dated status block/project-analysis correction; evidence folds untouched) | ❌ completed-failure — the KNOWN socket-identity test flake again on a loaded 2-core runner (`CrushConnectionSocketIdentityTest`, same shape as the 526b3a6 chain); the SAME tree plus `a7e6d14` is green (run 42454530745); docs-only, no code content |
| `a7e6d14` (2026-11-08) | **S-01 CATALOGUE PARITY** (external review confirmed via the shipped binary): deny list 22→**31 ids** (+lsp_rename/lsp_replace_symbol = LSP WRITES, lsp reads family, crush_info/crush_logs, built-in MCP-resource surfaces); exact-name filter semantics pinned; frozen-catalogue RATCHET test blocks outpaced upgrades (v0.100.0 additions named); device: instance config carries all 31 (run-as readback) + ZERO tool_call events across post-fix turns incl. the model's explicit crush_info/lsp_rename attempts — wall holds; **registered new defect: stale agent tool advertisement for long-lived sessions (upstream v0.99.0 agent toolset rebuild; benign under the execution wall; rides the engine-upgrade ladder)**; P5 reopening honored: acceptance runs on this artifact chain | ✅ completed-success (run 42454530745; local gate GREEN 28m12s) |
| `110766b` (2026-11-08) | **S-02 HARDENING (Finding A)**: trust consent TOCTOU closed — `Allow` carries the gate-observed fingerprint; `confirmAtGate` records ONLY the observed on-disk state; FileAppeared/Changed/Removed-since-gate outcomes write nothing and re-gate (F04/restore wiring); `recordConsent` = the explicit-approval writer only; 7 interleaving tests incl. the two hostile windows | ✅ completed-success (run 42507584055; local gate GREEN 6m41s covering both hardening batches) |
| `cfeae28` (2026-11-08) | **S-02/R4 HARDENING (Finding B)**: revert-vs-run race closed — one `opGate` Mutex across the entire revert (double-check + IO + card update); turn-record flips share the gate; `runSettled` under `NonCancellable`; `FileReverter.revert` opened clone-only for the tests' latch fake; latch-fake tests pin serialized double-revert honesty + beginTurn-waits-for-IO ordering | ✅ completed-success (run 42507584055) |
| `f1dc8eb` (2026-11-08) | **P6 HANDOFF BUILD PLAN** (docs-only): file-level batches A-G (Room v3 `handoffs` table on the project-Dao anti-clobber pattern; package model prefill; consent-sheet entry slots named 1:1 to the owner's decision; dispatch via `newSession` + verify-first template gated on decision-4; stat-walk fingerprint; restart-safe trace beside `restoreLastBinding`, never modifying it) — the FIVE §7 owner decisions restated as the build's gate | ✅ completed-success (run 42497875073; docs-only) |
| (docs row, 2026-11-08 late) | **P5 ACCEPTANCE FOLD** (capabilities §P5 closure record): plan-P5 contract closed on all four items + both proof rows on the 2026-11-05→2026-11-08 artifact chain (deny-all 31-id parity + device zero-execution wall; chat-pool instance separation under the Gate-1 registry; shared keys/separate grants structurally; injection regressions pinned; no-Termux/no-project proven structurally + by every drive). Residuals queued, NOT blockers: stale tool advertisement (engine-side, upgrade ladder), fresh-session advertisement confirmation (flake-pending), denial-points-at-Code message (polish — DELIVERED below), read-only allowlist (deferred) | — docs closure record |
| `b72da3e` (2026-11-09) | **P5 residual — the smart-transfer denial note** (decisions-open §10 polish): the CHAT pool's S-4 auto-deny now renders ONE centered system line per turn — `"write" is off in Chat - a project (Code mode) session can run this.` (blank-name fallback) — replacing the old fabricated assistant bubble that even picked up Copy/Save; turn-keyed + replay-idempotent (R1 shape pinned), render-only view state documented honestly; the engine-rejected-tool observability gap recorded in the KDoc (FAILED state ambiguity; v2 candidate); ChatDenialNoteTest 5 cases | ✅ completed-success (local gate GREEN 10m48s; CI run 42541803652 green) |
| `d65ead0` (2026-11-09) | **P6 BATCH A — the handoffs Room table** (plan §9 schema verbatim): `HandoffEntity` + `HandoffDao` (named guarded transitions `markApproved`/`markDispatched`(+at-dispatch fingerprint)/`abandon` on the `ProjectDao.updateTrust` anti-clobber pattern; no FK/no cascades by design — project-row removal must not destroy handoff traces), `MIGRATION_2_3` additive with the 4-state status CHECK (documented: fresh installs get Room DDL without CHECK — DAO guards cover every install), single builder path untouched, Robolectric tests incl. the open-at-v3 sqlite_master proof + restart-reread (606 tests total) | ✅ completed-success (run 42547014032; local gate GREEN 9m50s) |
| `6cf235b` (2026-11-09) | docs fold (the S-04 note batch's doc row) | ❌ completed-failure — the socket-identity test's THIRD CI strike (same loaded-runner shape; docs-only; later reruns green). Detection note: produced locally the same night by its own reproducible suite-order interaction; the budget-60s hardening `9aff1ef` follows |
| `9aff1ef` (2026-11-09) | **Socket-identity test hardening: budget 60s** (was 30s/15s) after THREE CI failures + a LOCAL reproduction tonight (60s parked in `StateFlowImpl.collect`, CONNECTED never published; the test passes in isolation in 26s → suite-order interference, not a broken mechanism); ladder headroom ~3.8× over the 15.75s honest-fail ladder; identity+shape pins intact per 526b3a6's discipline | ✅ local gate GREEN 54s warm (full suite with tests); CI run 42548458833 green |
| `e23389c` (2026-11-09) | **P6 BATCH B — the handoff package model** (recommended decisions adopted): plan §9 model + Batch A mappers (`toEntity` mints DRAFT only, fail-soft reference decode of the opaque-transport column); `prefill` takes the LAST assistant row passing the bubble-Copy conjunction with FAILED-turns-never-fall-back; `verifyContent` 5-pattern redaction net over the summary AND references (blocked verdicts name the pattern, never echo the secret); `FirstTurn.firstPrompt` assembles the verify-first first turn from the template copied **byte-for-byte** from the build plan (decision-4 binding), positional splice so user content cannot hijack placeholders, `(none)`/`(not recorded)` degradation forms; 23 pinned rows in 3 test files | ✅ local gate GREEN 16m13s (606 tests; CI watch in flight) |
| `df55b8f` (branch `engine-v0100-ladder`, 2026-11-09) | **V100 LADDER — strings-CONFIRM satisfied out-of-tree**: the official `crush_0.100.0_Android_arm64.tar.gz` (sha256 `cdb15f23…` byte-verified vs the release checksums.txt) downloaded to a temp dir, unpacked (99.4MB AArch64 PIE) and scanned — all 33 pinned ids present **incl. the four agent tools**, no additional built-in id surfaced (`agent_task` probe absent → snapshot exhaustive); the NEEDS-STRINGS-CONFIRM marker replaced by the done-record with artifact provenance; in-tree fetch + device rails remain the owner-gated next steps (jniLibs still 0.99.0) | branch commit; main untouched |
| `53bd2a7` (2026-11-09) | **P6 BATCH C — Chat→Code handoff consent sheet + dispatch route** (the sheet materializes Batch A's rows + Batch B's package): `HandoffConsent` contract layer (entry chip gate = the extracted Copy-gate conjunction `handoffEntryVisible`; provider stamp = model id else provider id; "Handoff HHmmss" session title; dispatch note line) + `HandoffConsentSheet` (stateless ModalBottomSheet: provenance line, editable summary fed ONLY by Batch B prefill over the tapped transcript prefix `take(idx+1)`, single-select Room projects destination picker, honesty consent card (agent-with-tools warning + ask-cards hint), Cancel/Confirm); ChatExport gains the third chip "Use this plan in Code" (same completed-assistant gate as Copy/Save); `ChatViewModel.confirmHandoff`: turn-in-flight guard → S-02 gate BEFORE any row write → redaction net → `insertDraft` (fingerprint NULL) → guarded `markApproved` (guard miss keeps the sheet open) → `dispatchHandoff`: trust clear+bind → startEngine (budget gate inside) → await Running → bind engine → **300ms settle beat BEFORE the view-epoch bump** → `newSession` → guarded `markDispatched` → view reset + "Handed off to <project>." → first-turn send; any failure before the DISPATCHED stamp leaves the row APPROVED+resumable (the Batch A guarantee); `markDispatched` fingerprint String→`String?` (Batch F deferral: CONTRACT ingredient now, VALUE at dispatch — deferred digest ≠ recorded-yet); 18 test rows (EntryChip 7 / ConsentContract 7 / DispatchSequencing 4: confirm sequencing, failed-dispatch-stays-resumable with a second-store restart reread, cancel-never-inserted, untouched-rewrite covenant). Commit carries the **SQLITE_CANTOPEN diagnosis**: Robolectric derives the per-test temp dir from the FULL sanitized test method name (`robolectric-<Class>_<method><random>\…\databases\<db>`), so long names pushed the SQLite path past Windows' 260-char MAX_PATH → open failed as CANTOPEN masquerading as a cross-test file lock (three long methods renamed short + a name-budget KDoc note; NOT reproducible on Linux CI) | ❌ completed-failure — run 42611516607 ONLY failed the socket-identity class (strike #5, first WITH fingerprint); every Batch C class present and green in the run's artifacts (downloaded + verified); lane fixed by `bf6afd0`, which re-ran Batch C's full main tier green |
| `df195b4` (2026-11-09) | **Socket-strike hardening lane** (after strike #4 consumed the local 60s budget): test-JVM executor knobs `maxHeapSize = 2g` + `forkEvery = 50` on `tasks.withType<Test>()` (`maxParallelForks` deliberately untouched — parallel forks court the same timing sensitivity and starve 2-vCPU hosts; NOT a budget bump — escalation spent, retry-until-green rejected); strike fingerprints on the horizon path (attempts / server requests / state / reader-alive so the next strike decodes to its branch row); `docs/socket-identity-strike-diagnosis-2026-11-09.md` (ledger, mechanics — 60s vs the 15.75s honest-fail ladder, ranked causes, branch table, acceptance rules). HONESTY: its commit message counted "clean-run #1/#2" from LOCAL gates only, but the doc rule needs local + CI consecutive — CI went red ~10 minutes later; corrected and counter-restarted by the `bf6afd0` row | ❌ rode run 42611516607 (the red was the lane's own diagnostic payload doing its job) |
| (docs row, 2026-11-09) | **capabilities fold — P5 residual-2 CLOSED + device upgraded**: the fresh-agent advertisement probe ran on device (GLM-5.3-Flash row after the picker drift; one hand tap, header tag verified) and the fresh agent answered verbatim "None are actually callable here: no tool definitions are attached to this chat…", DISTINGUISHING prompt prose from provided schemas — isolation stands: fresh-agent advertisement EMPTY vs the stale 7 ids long-lived pool agents still advertise, both upstream v0.99.0 semantics (S-01 stale-advertisement defect rides the v0.100.0 ladder: ratchet test + fresh-advertisement probe rerun); app.log evidence: session 95ec7be6, EngineProxy ok, "+0 -0 across 0 file(s)", ZERO tool lines across the ~17s turn. Same window: the Batch B build installed over the running app (`install -r` Streamed Install Success; Room v2→v3 opened clean; session pool intact on relaunch; the session model tag resets on relaunch — noted honestly) | ❌ rode run 42611516607 (docs-only) |
| `bf6afd0` (2026-11-09) | **STRIKE #5 DECODED AND FIXED — the producer-side state ledger**: fingerprint `attempts=3, serverRequests=2, state=CONNECTING, readerThreadAlive=true` (CI 42611516607, first fingerprinted strike) → CONNECTED REALLY published (on the 2xx headers, ladder reset) and the keep-open stream then broke under the runner's memory thrash (Gradle daemon metaspace exhaustion ~10s prior), the loop tail flipped CONNECTED→CONNECTING, attempt 3 hung on a zero-enqueued request — and the subscribed collector never snapshotted the transient: **StateFlow conflation legally absorbs values replaced between scheduling ticks; the subscriptions-first re-audit CONCLUDED necessary-but-not-sufficient**. Fix: all 7 state writes funnel through one `publish(next)` appending to a CopyOnWriteArrayList ledger BEFORE the flow write (flow semantics unchanged; `stateLedgerForTest()`); the healthy fast path untouched; ledger-proven CONNECTED at the horizon = the DOCUMENTED degraded-shape pass (still asserts the ladder retried ≥2 and a socket reached the server ≥1, then stops); every other shape still fingerprint-fails, with the new branch pinned (requests ≥1 + ledger WITHOUT CONNECTED = the response never reached the publish site). **Acceptance counter RESTARTED** (`df195b4`'s locals-only counting corrected; this change's own green CI re-arms it) | ✅ completed-success — local gate GREEN 5m16s (653 tests, 0 failures; socket class fresh 0.355s) + CI run 42618491593 GREEN 3m24s, both carrying the knobs + ledger change |
| (docs row `3303fa8`, 2026-11-09) | **Socket-strike-#5 fold**: diagnosis doc completed (ledger row 5 + branch row 4 RESOLVED + full mechanics addendum: a producer-side state-ledger record defeats the StateFlow-conflation window that a subscriptions-first re-audit proved necessary-but-not-sufficient), status-hub rows for the socket lane / Batch C / residual-2, and the ACCEPTANCE-COUNTER CORRECTION (`df195b4`'s "clean-run #1/#2" counted locals-only; doc rule = local AND CI consecutive; counter restarted at `bf6afd0`, re-armed by CI 42618491593 GREEN) | ✅ completed-success (docs-only; CI run 42619369445 GREEN) |
| (docs row `d0d3b45`, 2026-11-09) | **P6 BATCH C DEVICE VERIFICATION PASSED** (capabilities fold): handoff E2E proven on device `bbf8ca69` — entry chip "Use this plan in Code" live (the "correctly ABSENT" note superseded); consent sheet with full Batch B prefill + projects picker + honesty card; dispatch chain app.log-proven (chat-pool instance stopped → code instance `p536fa26e` Running 3162 ms → `bootstrap workspacePath=/storage/emulated/0/Test project folder` → `permissionMode=ASK`); pulled-Row truth `status=DISPATCHED`, `userApprovedAt=1794229822696`, `targetProjectId=536fa26e…`, `targetCodeSessionId=f3e78747…`, fingerprint NULL (the planned Batch F deferral), `sourceProvider=deepseek-v4.1-flash`; S-02 ordering proven on real stamps (`recordConsent` …654 < approve …696); verify-first first turn byte-for-byte (real bash executed: find+sha256 walk, ls -la + .git check); ask-cards live in the handoff session (`permission requested: bash (31163177/cd5aa293/adc3e8a4); pendingPermission SET`); the owner's confirmation turn ("continue") produced the full mismatch report (items 1-7) — the protocol itself CAUGHT the tree mutation (`.crushrc-ran.marker` mtime 2,281 s = 38m01s after the approval stamp; "+0 -0"; agent halted per template rule 4); HONESTY cross-match: the agent's independently computed sha256 `dcba1093…` equals the gate-recorded trust fingerprint (Batch F stat-walk reads the mechanism); zero FATAL, engine stayed Running; six open observations recorded (watchdog cancel of the first verify run; session-id log flip-flop transient; "Handoff: Tool" title propagation; merged transcript scroll; marker-writer mechanism unknown → Batch F; model tag dance) | ✅ completed-success (docs-only; CI run 42633841654 GREEN 3m34s) — device verification record, no code commit |
| `20e8347` (2026-11-09) | **P6 BATCH D — the post-approval handoff surface** (the four pieces Batch C's comments deferred to "Batch D's piece"): sheet MODES over Batch A's guarded machine (DRAFT = C unchanged, cancel stays silent; RESUME = an approved-but-undispatched row dispatches ON its existing approval — approved summary READ-ONLY, the ORIGINAL `userApprovedAt` rides the first turn, `markApproved` single-shot untouched; DISPATCHED = the durable trace's jump surface — no picker, no discard, the DAO guard refuses abandoning a DISPATCHED row); EXPLICIT DISCARD via guarded `abandon` (every other column untouched, stamp survives); NO-ALIAS REPLACE (plan :416) with the F04-mid-abandon dead-end FOUND AND FIXED BY REORDER (the new destination's gate+record completes BEFORE the abandon — the dialog's re-call repeats read-only steps, never a double-abandon); F04 REWIRE (Required/moved-inside-window publish to the screen-local CrushrcTrustDialog, Projects row-tap pattern, whose Allow re-calls the same entry through the identical path); openHandoffSheet classification via new `HandoffDao.rowsForSource` (newest DISPATCHED → jump, newest APPROVED → resume, else draft, else prefill); Open in Code = dispatch choreography extracted ONCE (`bindCodeForHandoff` — settle beat + liveness budget gate included) + `openSession`; the cold-boot extension beside restoreLastBinding stays Batch G. Tests 653 → 660 (7 rows: discard-abandons-approved, discard-refused-on-dispatched, rowsForSource newest-first ordering, no-alias replace DAO path, resume-stamp-keeps-approval, mode classification/exclusivity, resume decision helper) | ✅ completed-success — local gate verified PER-XML (660 tests, 0 failures, 0 errors; the observed re-run BUILD SUCCESSFUL with 90 tasks UP-TO-DATE after the first gate's client survived a host restart as a detached job — honesty recorded in the commit) + CI run 42653529569 GREEN 2m42s |
| `327a47c` (2026-11-09) | **P6 BATCH F — the project-tree stat-walk fingerprint** (the deferral closes) + the Batch C observations RESOLVED in the same window: `ProjectTreeFingerprint` (plan §8 f1) = stat-only walk (path/size/mtime rows sorted by path → SHA-256 lowercase hex, the trust service's hex discipline); snapshotter noise rule + depth cap riding, documented as the shared false-negative family; EXPLICIT marker rows for refused files AND refused directories (subtree unvisited; the first-cut keyed the dir branch on `listFiles()==null` only — its own test caught the bug BEFORE commit); missing root = deterministic empty digest; `Dispatchers.IO`, no content reads; `dispatchHandoff` computes AT dispatch fail-soft (unrunnable walk = NULL = "(not recorded)") and carries the digest to BOTH the row column (single-shot stamp) and the first turn's provenance line; the resume dispatch stamps its OWN fresh digest (the recorded tree is always AS DISPATCHED — the digest is the app's metric, the agent's verify turn re-walks semantically); tests 660→669 (7 determinism rows + DAO stamp row + real-digest render). OBSERVATIONS (docs/batch-c-observations-resolution-2026-11-09.md): ① the first-turn stall = the DESIGNED 20s stream-idle watchdog on a tool phase (cost recorded); ② the resume-grab flip-flop = the DESIGNED bootstrap tail racing the dispatch's ownership — the settle beat + epoch guard absorbed it (proof: the DB stamp kept f3e78747); ③ "Handoff: Tool" = engine auto-title display contract; ④ the merged scroll = engine-side session lineage (the minted id arrives as a full engine UUID) — one probe rides the D+F drive; ⑤ THE MARKER WRITER NAMED: the consented crushrc execution on every workspace re-attach (S1b semantics; the 19:18:23 mtime sits exactly inside the unlock re-attach window; no unknown writer exists); ⑥ the model tag dance = the unlock/bootstrap probe flapping (evidence on both windows) with the first-turn agent-model capture riding the drive and the stamp-to-target `setModel` as the decided fix IF they differ; no Batch G blocker | ✅ completed-success — local gate GREEN 8m55s per-XML (669 tests, 0 failures, 0 errors; first run 668+1 with the single failure the marker-branch bug, fixed and re-run) + CI run 42661917711 GREEN 4m4s |
| `20d2ce0` (2026-11-10) | **THE CI FLAKE FIX** (run 42758736412 - the chain's first failing run, on a DOCS commit - failed ProjectTreeFingerprintTest's rewrite row ON the runner and nowhere else in the class's life: a 3→3-byte rewrite on the fast runner landed in the same millisecond bucket as the original write, `lastModified()` returned one value for both walks, and the digest held EXACTLY as the metric's own docstring documents ("a same-size rewrite landing inside the filesystem's mtime resolution is DOCUMENTED, not discriminated"); the test's "usually mtime together" was the flake, asserting an outcome a stat-only walk never guarantees). The unconditional flip asserts now ride the stat changes a stat-only walk can ALWAYS see (a size-CHANGING rewrite, then the rename); the pure-mtime component stays pinned by the explicit setLastModified row; the comment carries the specimen verbatim. Metric AND its row shape unchanged (nanosecond precision considered and refused - frozen state) | ✅ completed-success — local gate 4m50s per-XML 673 tests / 0 failures / 0 errors across 70 suites + CI run 42760055813 GREEN 3m39s |
| `37b1ab5` (2026-11-10) | **THE WATCHDOG RETUNE — 20s → 60s STREAM IDLE** (specimen #3, the first proven TURN KILL: the owner granted the run's ls + view asks — "prompt CLOSED (grant ok)" 07:23:27 — and the engine executed the just-granted tools server-side, the phase that emits NO SSE frames; the frame loop, handed back from the parked-prompt loop AT the grant, raced the old 20s `STREAM_IDLE_MS` and cancelled the healthy run at 07:23:47, scan `+0 -0 across 0 file(s), 2 unreadable`; grant = proof of life, the race read it as silence; third specimen with the signature after the z4 ask 2026-10-29 and the D-drive verify run). The budget still catches a genuinely dead socket; the workspace's ~30s reap window stays the earlier honest terminator for corpses; `PROMPT_WAIT_MS` (10min) untouched. Follow-up in the comment only, not shipped: on idle expiry probe the run state first, cancel only when not busy. One comment reword rides along (L3775's present-tense "20s idle race" → "(then-20s, now-60s)") | ✅ completed-success — local gate 30m42s (cold daemon) per-XML 673 tests / 0 failures / 0 errors across 70 suites (census unchanged) + CI run 42757904775 GREEN 3m01s |
| `f979d78` (2026-11-10) | **THE D+F DRIVE FIX BATCH** (the owner's two frictions, closed by mechanism): the model flap - setModel now PERSISTS the verified choice (the AppSettings SELECTED_MODEL slot existed but was never written/read) and the bootstrap shortlist LEADS with the persisted id (pinned: an unlisted head is dropped silently), with the handoff stamp (`handoffStampAsModel`; provider-id stamps denote no model) as a one-shot ahead of it on dispatch/jump - the verify-first turn runs ON the user's model (drive: haiku flipped in and FAILED, glm worked); the redaction-net refusal renders INSIDE the sheet (the eleven dead-feeling Confirm taps = the `long-hex-secret` verdict hidden behind the sheet scrim; the snackbar stays); the replay-surviving RESUME BAR ("Handed off to X - Continue in Code", a sticky at the source chat's transcript top, built FROM the handoffs row via refreshHandoffResumeHint with its own F04 dialog state; the jump made sheet-independent, refreshed at openSession/newThread/dispatch/cold-boot; the target never self-barred). Drive evidence folded into capabilities: the fingerprint TRIPLE-MATCHED (row = screen = an independent stat walk, e0f610c1…), S-02 order repeated (the approve stamp = the engine-stop second), obs specimens collected (watchdog instance #2; haiku-then-glm; the 110-msg drawer title "GitHub Trending Analysis and PDF Report" = 4161054d's identity) | ✅ completed-success — local gate 12m per-XML (673 tests, 0 failures, 0 errors; 70 suites) + CI run 42676549043 GREEN 3m52s |
| (docs row, 2026-11-11 late) | **P5 DRIVE FOLD** (capabilities §P5 drive): delete+re-import cycle (all 4 DISPATCHED traces survived the tombstone; fresh row trust-NULL; gate at row-OPEN; Projects-site F04 Allow synthetic-OK, fp == SHA-256 of the pulled bytes); the DISCARD LEG closed live (strand #2 `d6b5b664` ABANDONED, status the ONLY changed column byte-level, stamp survived; ABANDONED head invisible to classification); strand #3's F04-mid-abandon leg **gate half proven, Allow half exposed THE CHAT-SIDE F04 CONSENT BUG** (each Allow re-calls confirmHandoff and NOTHING records consent on the chat side — 27 log cycles, zero DB writes, infinite loop; the Projects site works only via grantTrustAndOpen → recordConsent; the code comment's claim has no implementation; Cancel = the clean exit); the salvage closed the SAME-DESTINATION resume dispatch live (`b8cb2f12` → DISPATCHED, NO INSERT, stamp unchanged, session bound, consent re-stamped at the prelude; the dispatched verify-first turn ran read-only with honest mismatches + owner-granted ask cards); synthetic map extended (sheet Discard finger-only; F04-over-sheet Allow finger-only AND loop-broken; drawer pill = resume-last-pool-session); crushrc fixture learning (a bare-word crushrc breaks the engine config load → HTTP 500 bootstrap) + removed by owner decision | — docs closure record; the fix (chat-side recordConsent before the re-call) is the next code batch's head |
| `9a86a7e` (2026-11-11) | **THE CHAT-SIDE F04 CONSENT FIX**: `grantHandoffTrust` records consent for the pending ref's CURRENT bytes (service re-hashes at record time; stale fingerprints unpublishable), then runs the same-entry re-call whose second gate finds Allow; failure fail-closed (in-sheet notice, nothing approved); ordering extracted to `recordConsentThen`, pinned by HandoffConsentOrderTest (2 rows). The eight handoff lifecycle receipts move from android.util.Log to AppLog so they land in files/app.log (the DISCARDED receipt was DB-only because of the old sink). Device re-drive on this build CLOSED the F04-mid-abandon leg: Legacy consent fp == SHA-256 of the planted bytes; `ef5f3278` ABANDONED after gate+record (stamp untouched); new row `65770c54` DISPATCHED to Legacy (session bound); all four receipts in app.log; a single SYNTHETIC Allow drove the chain (the old "finger-only" claim corrected — the loop had masked registration) | ✅ completed-success — local gate 33m39s per-XML 675 tests / 0 failures / 0 errors across 71 suites + CI run 43032267552 GREEN 13:50:35Z |
| (docs row, 2026-11-11 night) | **P5 RE-DRIVE FOLD** (capabilities §fix-verified-live): the fixed build's end-to-end closure of the F04-mid-abandon leg with DB truth at every step; the missed-strand lesson (`61c964f3`: poll armed after the owner's pre-tap → baseline already held the stamp → banked a clean same-destination dispatch instead of a strand; confirm-ask order now strict); the synthetic-Attribution correction (F04-over-sheet Allow registers); the two-crushrc honest audit by the dispatched agent; fixture removal decision (Legacy now re-gates as REMOVED_SINCE_CONSENT next open) | — docs closure record |
| `4bc5fb7` (2026-11-12) | **P6 BATCH G — THE COLD-BOOT RESUME OF THE DISPATCHED TRACE (P6's last batch)**: `openLastDispatchedHandoff()` beside `restoreLastBinding` (bytes untouched; runs after it), `HandoffDao.latestDispatched` (insertion-order LIMIT 1), the same fail-closed gate with Required/moved publishing the root `PendingProjectTrust` (Allow lands the binding), the never-yank posture (adopt only when the target is active or nothing is bound; otherwise the row stays surfaceable), and the deterministic session half — the `pendingOpenSession` one-shot claimed by `findSessionToResume` only against the bound workspace's own listing (a plumbing mismatch = the wrong instance's bootstrap; the intent is left for the right one). DEVICE PROOF: cold boot on the newest trace (Legacy, crushrc-removed consent) → honest REMOVED_SINCE_CONSENT re-gate from BOTH paths (restore + the new handoff-resume line) → root F04 → synthetic Allow → noConfig consent (stamp 00:30:21) → binding `p077dc19e` → `resuming session b894b36f (25 msgs; the handoff trace's target)` with replay; proof row (b) closed; 9 rows untouched. Tests 675→676; local gate 19m27s per-XML 676/0/0; CI run 43082252173 GREEN | ✅ completed-success — CI 18:57:50Z |
| `281ce8c` (2026-11-12) | **THE ENGINE ACCEPTANCE KIT (the future-version test system) + v0.100.0 research/plan**: `EngineCatalogues` manifest (v0.99.0 29 ids / v0.100.0 33 strings-confirmed, no removals; PINNED_VERSION) + the rewritten ratchet requiring EVERY known catalogue to be fully denied (a future version = one manifest row; the red test names the deny gaps); deny list pre-grown with the four v0.100.0 sub-agent tools (inert on 0.99); `EngineApiContracts` (per-version permission endpoint/field + SSE shapes; `gapsFor("0.100.0")` pinned by tests = `/permissions/skip`→`/permissions/mode`, `yolo`→`permission_mode` enum, `sub_agent_report`, pinentry/SSH events; both manifests' version sets asserted equal); `tools/fetch-crush.ps1` gains the v0.100.0 SHA-256 pin from the signed release (default stays 0.99.0); `tools/engine-rails.ps1` read-only T3 device runner — LIVE 6 PASS / 0 FAIL on the pinned build (chat-pool config measured at `files/crush-chat/data/crush.json`, 31 ids; the bare-word-crushrc exit-127 finding recorded). Research + plan docs: `docs/research-engine-v0100-2026-11-12.md`, `docs/engine-acceptance-kit.md` (T0–T3, promotion/rollback), `docs/v0100-ladder-plan-2026-11-12.md` (adapter = the remaining code work; device rails owner-gated; Sysadmin/piped-permissions decisions explicit). Tests 676→680; local gate 18m45s per-XML 680/0/0 across 72 suites | ✅ local gate green; CI run 43122414797 GREEN |
| `8365481` (2026-11-12) | **THE ACCEPTANCE-KIT DOCS FOLD**: the T0–T3 kit, the adapter invariants, promotion/rollback rules and the v0.100.0 research/ladder plan written down beside the code; docs-only | ✅ CI run 43122805013 GREEN |
| `79c3815` (2026-11-12) | **THE VERSION-AWARE PERMISSION ADAPTER (ladder step 1, code half)**: the bound engine's OWN version line selects the wire family — v0.99.0 keeps `yolo` bool + `/permissions/skip`; v0.100.0+ sends `permission_mode` (`normal|yolo`; `modeFor` cannot produce the sysadmin value) and re-applies through `/v1/workspaces/{id}/permissions/mode`; an absent/unparseable version fails SAFE to the pinned family so a rollback binary always speaks its own protocol. Plumbing: `CreateWorkspaceRequest.yolo` nullable + new nullable `permission_mode` (Gson omits nulls → exactly one field per create), `CrushApi.setPermissionMode`, `WorkspaceManager.open` carries the wire and re-attaches in the SAME family, `CrushSession.openWorkspace(engineVersion)`, bootstrap reads the supervisor's version. Contracts split RAILS-PROVEN vs ADAPTER-SUPPORTED | ✅ completed-success — tests 680→685; local gate 13m16s per-XML 685/0/0 across 73 suites; CI run 43127356497 GREEN |
| `b0939f6` (2026-11-12) | **THE ADAPTER + CANDIDATE DOCS FOLD**: records the adapter's device-rail contract and the staged v0.100.0 candidate; the engine binary stays gitignored by design, so the fetch pin + T1 strings scan ARE the provenance chain (a fresh clone must run `tools/fetch-crush.ps1`) | ✅ CI run 43134117008 GREEN |
| `d1794e1` (branch `engine-v0100`, 2026-11-12) | **THE v0.100.0 CANDIDATE STAGED**: fetch verified the recorded signed-release SHA-256 and wrote the 94.8 MB AArch64 PIE; strings scan over the WRITTEN binary: 33/33 ids, `agent_task` probe absent, v0.100.0 strings, zero v0.99.0. Branch-only constants (`PINNED_VERSION` = 0.100.0; `IMPLEMENTED` stays 0.99.0 = rails-proven). Structural fact: `libcrush.so` is GITIGNORED by design — the candidate is a local artifact; the fetch pin + T1 scan are its provenance chain and a fresh clone must fetch. Branch gate GREEN 34m34s (685/0/0); main keeps v0.99.0 until the device rails pass | branch commit — device rails owner-gated |
| `d60f7c9` (2026-11-12) | **THE v0.100.0 MORNING RUNBOOK**: exact install / rails / rollback commands, including the trap that the gitignored binary never follows `git switch` — refetch and hash-verify per branch. `main` stays pinned to engine 0.99.0 until the rails close | ✅ CI run 43134345245 GREEN |
| `be35981` (2026-11-12) | **THE RE-ATTACH WIRE FIX (the live v0.100.0 404)**: the first v0.100.0 device run exposed that `reattachIfStale()` re-POSTed via the two-arg `open()` and dropped the stored `permissionModeWire`, so every re-attach fell back to the removed `/permissions/skip` and got HTTP 404 while the initial attach was fine. The stored wire now rides re-attach; the fail-safe for an absent OR unparseable version (`null`, `"nightly"`) follows `EngineCatalogues.PINNED_VERSION` instead of a hardcoded family. `BootstrapOrderTest` pins the re-attach family in BOTH directions (and its `drainRequests` now tracks the cumulative MockWebServer count — the first version hung the gate in `takeRequest`); `EngineProtocolTest` pins the JSON claim, the pin, and the unparseable form. Rail 1b added to the runner | ✅ completed-success — local gate 10m07s per-XML 687/0/0 across 73 suites |
| `84d4bfa` (2026-11-12) | **RAIL 1b PRESENTATION FIX + T3 DEVICE RAILS GREEN on v0.100.0**: the engine's version claim is a JSON object ending in a newline, so the `bootstrap permission wire: ... wire=MODE_ENUM` log record splits across two physical lines and the single-line regex missed it (the route itself was already correct: `open: permissionWire=normal`, zero 404s). The runner flattens newlines before matching. Re-run **8 PASS / 0 FAIL**; interactive rails measured on the candidate — F04 fresh consent on the imported folder (Cancel inert; Allow stamps consent BEFORE the attach, DB fingerprint == pulled SHA-256), project cold-boot gate with `bootstrap refused: locked`, Batch G `b894b36f (25 msgs)` + handoff `65770c54` untouched, E6 switch stress ≤2 children, and the rollback drill: main/0.99.0 opened the v0.100 engine state DB unchanged (goose 13 / sessions 2 / messages 25) — no one-way door | ✅ completed-success — local gate 1m06s (tree up-to-date), per-XML 687/0/0; device 8/0 + interactive rails |
| (docs row, 2026-11-12 late) | **T3 DEVICE-RAILS FOLD + RAIL 7b CLOSED (the last merge blocker)**: acceptance kit §5 records the scripted + interactive results and the legacy-row F04 correction (row tap bypasses by design since `4058588`; the Nov 5 proof was project row `536fa26e`); ladder plan gains step 4c; capabilities gains the v0.100.0 T3 paragraph. The reinstalled candidate re-ran the scripted rails **8/0** and the **same-destination dispatch** closed live on the owner's finger: from Legacy chat `b894b36f` → Legacy `077dc19e`, ONE insert `eb78ea9d`, consent `1794488827189` → APPROVED `1794488827242` → DISPATCHED `18:37:07.936` to engine session `755fcfd0`, first turn completed on v0.100 (zero 404/FATAL); full-row diff = one new row, all nine prior rows byte-identical (`65770c54` untouched), only `077dc19e.consented_at_ms` re-stamped; Legacy engine DB 2→3 sessions / 25→41 messages. Chain rows added for `8365481`/`79c3815`/`b0939f6`/`d60f7c9` with CI run IDs; branch-state paragraph moved to `d60f7c9` + the `engine-v0100` candidate stack | — docs closure record; merge next |
| `6800c0e` (2026-11-12) | **THE v0.100.0 MERGE — the engine of record is now v0.100.0**: `engine-v0100` merged into `main` (main's extra commits were docs-only; the merge tree's code is byte-identical to the gated branch tip `84d4bfa`). `EngineCatalogues.PINNED_VERSION` = `0.100.0`; `EngineApiContracts.IMPLEMENTED` stays `0.99.0` (RAILS-PROVEN remains the stricter claim and promotes only on a future rails pass). Device proof on `bbf8ca69`: scripted rails **8/0**; F04 fresh consent (Cancel inert; Allow stamps consent before the attach, DB fingerprint == pulled SHA-256); project cold-boot gate + resume `9c17ad6a`; Batch G cold boot resumed `b894b36f` (25 msgs) with `65770c54` untouched; E6 switch stress (children ≤2); same-destination dispatch `eb78ea9d` → engine session `755fcfd0`, first turn completed; rollback drill: main `d60f7c9` + engine 0.99.0 opened the v0.100 engine state DB unchanged (goose 13 / sessions 2 / messages 25) — **no one-way door**. Local gate on the merged tree 3m41s, per-XML **687/0/0 across 73 suites**; CI run **43227604396 GREEN** | ✅ completed-success — merge commit; CI GREEN |
| `7346e20` (2026-11-12) | **THE FETCH-DEFAULT RATCHET**: the merge exposed that `main` pinned 0.100.0 while `tools/fetch-crush.ps1` still defaulted to 0.99.0 — the binary is gitignored, so a fresh clone would have silently built against the previous engine (the adapter speaks both families, so nothing else failed). The default advances to 0.100.0 (the 0.99.0 checksum branch stays for the rollback drill), and `FetchScriptPinTest` pins BOTH halves: default == `PINNED_VERSION` and the matching 64-hex release checksum branch. Also folds the docs-audit pass (README status/pins/test counts, toolchain count, providers' engine trail, project-analysis scorecard, BASELINE snapshot warnings, the egress-bridge security note) | ✅ completed-success — local gate 689/0/0 across 74 suites; CI run **43244539199 GREEN** |
| `b7b4ae5` (2026-11-14) | **#9 — TOOL-ROW COMPLETION ON `tool_result` (real durations)**: the assistant frame's part-final `finished:true` is **not** execution-final (measured ~274 ms, before the permission gate), so live rows stay RUNNING through it; `RunHandle.completeTool` completes them on the live `role=tool` message, and the turn `finally` only settles leftovers without inventing a duration. Durations are engine-supplied (`metadata.start_time` → `end_time`, epoch ms) and render as e.g. `8.0s`. Device proof (session `7271d703`, `sleep 8`): the dialog showed a spinner with no check/chip, still spinning 1.5 s after Allow, check + `8.0s` only after `tool_result observed … duration=8031ms` (first probe `8037ms`) | ✅ completed-success — local gate green per-XML 715 tests / 76 suites / 0 failures; lint 0 errors / 1 warning |
| `3bd9107` (2026-11-14) | docs fold: #1 closed (owner rotated the key and removed the repo-root file; date only recorded) and the #9 device proof + capabilities/README/status rows folded | docs-only |
| `022ca46` (2026-11-14) | docs: storage-strategy research — AFA vs narrow SAF vs the two-flavor split, primary sources S1–S14; direct answer: live bidirectional sync is not equivalent to AFA (SAF is `content://` provider access; the native engine needs a POSIX cwd) | docs-only |
| `1581a37` (2026-11-14) | docs: **AFA-first decision** — attempt All Files Access on the first Play submission (declaration + demo video + review instructions; Document-management category), with the two-flavor SAF-mirror fallback pre-scoped. **The owner then deferred the release: no submission is in flight** | docs-only |
| `af178f8` (2026-11-14) | **AFA REVIEW HARDENING (prepared, not submitted)**: the Android 11+ AFA request now sits behind a just-in-time "All files access, explained" dialog (never at launch; "Not now" exits cleanly), and the importer refuses the volume root as a project (`FolderUriDecoder.primaryProjectPathFromTreeDocumentId`, unit-pinned) with a specific error. App-level boundary, not an OS sandbox; API 29 device cannot exercise the dialog, so proof is compile/unit-level | ✅ completed-success — local gate green per-XML 716 tests / 76 suites / 0 failures; lint 0 errors / 1 warning |
| `2fef885` (2026-11-14) | docs sweep + submission package: per-XML count census (716/76), AFA hardening and release-deferral folded across the ledger, `docs/play-afa-submission-2026-11-14.md` added | docs-only — CI run **43592253901 GREEN** (3m12s) |

**GATE 0 = CLOSED on both halves.** Code: `7ec34ed` (S-01), `3a3a233`
(S-02), `f5d24dc` (S-04), wording-honesty in S-03's ledger row. Device:
A03 restore-trust drive PASSED 2026-11-06. Residuals ride the E6 stress
pass: 4 JVM-only A03 variants (cancel / changed-crushrc / noConfig-flip
/ real-install migration), REST boot-window row, S-01 Failed-badge row.

Branch state truth (2026-11-14): code rows above the 2026-11-12 line are
`b7b4ae5` (#9 tool-row completion; local gate 715 tests / 76 suites /
0 failures, lint 0 errors / 1 warning), `3bd9107` (docs: #1 closed + #9 device
proof), `022ca46` (docs: storage-strategy research), `1581a37` (docs:
AFA-first decision). `af178f8` is the AFA review-hardening code commit (local
gate 716 tests / 76 suites / 0 failures; lint 0 errors / 1 warning); `2fef885`
is the docs sweep + submission package above it (CI run 43592253901 GREEN).
The 2026-11-12 chain below is retained as the v0.100.0 merge record.

Branch state truth (2026-11-12, post-merge): code HEAD = `7346e20` (the
fetch-default ratchet + `FetchScriptPinTest`; local gate 689/0/0, CI run
43244539199 GREEN) ← `6800c0e` (**THE v0.100.0 MERGE**; CI run 43227604396 GREEN) ←
`d60f7c9` (docs: the v0.100.0 morning runbook; CI 43134345245 GREEN) ←
`b0939f6` (docs: adapter + staged candidate folded; CI 43134117008) ←
`79c3815` (feat: the version-aware permission adapter; tests 680→685, gate
13m16s 685/0/0; CI 43127356497) ← `8365481` (docs: the acceptance-kit fold
+ research/ladder plan; CI 43122805013) ← `281ce8c` (the engine acceptance
kit: catalogue + API-surface manifests,
manifest-driven ratchets, 0.100 fetch pin, device rails runner; CI run
43122414797) ← `7cd768e` (docs: Batch G fold — P6 CLOSED, Gate 4 closed;
CI run 43085180506 GREEN) ← `4bc5fb7` (P6 Batch G: cold-boot resume of the
DISPATCHED trace; CI run 43082252173 GREEN) ← `1aaf43f` (docs: P5
re-drive fold; CI run 43057782456 GREEN) ← `9a86a7e` (the chat-side F04
consent fix + AppLog sink batch; CI run 43032267552 GREEN) ← `a6ab1af`
(docs: the P5 drive fold) ← `7714bf0` (docs: the P4-excursion fold; CI
run 42988745179 GREEN) ← `6c749fc` (docs: the CI flake specimen fold;
run 42760602680 GREEN) ← `20d2ce0` (test fix: the rewrite-flip assert
rides SIZE, not the same-millisecond mtime race; CI 42760055813) ←
`593f518` (docs: the retune fold + the drive's lateral proofs; CI
42758736412 FAILED - the fingerprint-test flake, fixed by the next
commit, first failing run of the chain) ← `37b1ab5` (watchdog retune
20s → 60s after specimen #3's proven turn kill; CI 42757904775) ←
`2f8bf35` (docs: the
drive-fix batch's capabilities fold) ← `f979d78` (the drive-fix batch:
model flap + resume bar; CI 42676549043) ← `a54a347` (docs: Batch F fold +
observations resolution; CI 42663017363) ← `327a47c` (P6 Batch F:
stat-walk fingerprint + observations resolution; CI 42661917711) ←
`20e8347` (P6 Batch D: post-approval handoff surface;
CI 42653529569) ← `d0d3b45` (docs: Batch C device-verification fold +
status rows; CI 42633841654) ← `3303fa8` (docs: strike-#5 fold +
acceptance-counter re-arm; CI 42619369445) ← `bf6afd0` (strike-#5
producer-side ledger) ←
`321d922` (capabilities residual-2 fold, docs) ← `df195b4` (socket
hardening lane: knobs + fingerprints + diagnosis doc) ← `53bd2a7`
(P6 Batch C) ← `83c4109` (docs: P6 Batch B fold + ladder
strings-CONFIRM) ← `e23389c` (P6 Batch B) ← `164a079` (docs: Batch A CI
fold + strike ledger) ← `9aff1ef` (socket budget 60s) ← `54a2184`
(docs: P6 Batch A fold) ← `d65ead0` (P6 Batch A) ← `6cf235b` (docs
fold; recorded strike #3) ← `b72da3e` (S-4 denial note) ← `21c54af`
(docs: ladder research corrected) — then the 2026-11-08 chain (kept
from the previous truth line, superseded as HEAD): `cfeae28` (revert
lock, Finding B) ← `110766b`
(trust consent race, Finding A) ← `f1dc8eb` (P6 build plan, docs) ←
`b4306d4` (S-01 parity fold, docs) ← `a7e6d14` (S-01 catalogue parity) ←
`71aecdb` (docs refresh sweep; CI flake recorded→re-run green) ←
`f78f6c4` (chat save batch fold, docs) ← `fe9e7e8`
(chat output save surface) ← `8e0ab08` (P5/P6 prep + research, docs) ←
`40a6be4` (Gate-2 evidence fold, docs) ← `bf5019a`
(export launch fix) ← `c8dd154` (Gate-2 batch 2) ← `5f14dfc` (Gate-2
batch 1) ← `526b3a6` (test hardening) ← `f516d1f` (E5 nav fix) ←
`053ccb5` (Gate-1 fold, docs) ← code rows above. Pack-archaeology note (unchanged): `f5d24dc`
and the pack-referenced `1d229de`/`00964da` stay reachable for the
owner pack's SHA references.

The v0.100.0 candidate WAS branch `engine-v0100`, cut from `79c3815`:
`0b231f5` (the T3 docs fold) ← `84d4bfa` (rail 1b newline flatten) ←
`be35981` (re-attach keeps the stored wire) ← `02bfb18` (JSON version
parse) ← `d1794e1` (candidate staged: fetch verified, strings scan clean,
constants bumped). It merged at `6800c0e`. `main` now carries engine
0.100.0; the engine binary is gitignored on every branch, so a checkout
never carries it — re-run `tools/fetch-crush.ps1` (the default now fetches
the pinned 0.100.0; `FetchScriptPinTest` keeps the default and the Kotlin
pin from drifting apart).

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

| Gate | Content | STATUS (last refreshed 2026-11-14) |
|---|---|---|
| 0 | trust + chat fail-closed + REST transport hardening (S-01, S-02, S-04 above) | **CLOSED, both halves** (supersedes this cell's 2026-11-05/`1d229de`-era PARTIAL reading): S-01 `7ec34ed` + S-04 `f5d24dc` + **S-02 `3a3a233`** (central ProjectTrustService, gate-before-bind on every path incl. restoreLastBinding) — **A03 device drive PASSED 2026-11-06** (capabilities §Gate-0 closures); **2026-11-08: S-01's deny-all scope was REOPENED by an external catalogue audit (9-id gap, proven by binary strings + upstream allToolNames) and RE-CLOSED by `a7e6d14` + live device re-proof (capabilities §S-01 catalogue parity)**; residuals ride the E6 stress pass: 4 JVM-only A03 variants, REST boot-window row, S-01 Failed-badge row + new: stale agent tool advertisement (engine-side, benign under the execution wall, rides the upgrade ladder) |
| 1 | Gate-7 runtime registry ([`design-runtime-registry-2026-11-05.md`](design-runtime-registry-2026-11-05.md)); pack rows **E1/E3 SHIPPED** (`1d229de` phase-1 interlock + readiness/starting-watchdog); **E2/E4 SHIPPED** (`d818a9c` container registry + connection reclaim); **E6 device stress drive PASS** (2026-11-06, 5 switches, capabilities §Gate-1 device stress drive; two-row limitation noted); **E5 back-nav fix SHIPPED + device-verified** (`f516d1f`, D1-D8 all pass incl. back-from-chat exits on both paths; gh-verified via `526b3a6`); S-04 flake diagnosed + test hardened (`526b3a6`). Residuals: second imported project row to exercise the 2-non-legacy-children ceiling on device; optional nav-restore re-boot dedup | closed 2026-11-07 (residual drives optional) |
| 2 | Code v1 (Review surface, revert, workspace polish) | **surface DEVICE-PROVEN 2026-11-07** (capabilities §P4 Gate-2: multi-file turn +2 -0, hunks, byte-identical revert `dcb75b2b…`, multi-card review, export byte-exact after `bf5019a` fix; both P4 proof rows closed). Residuals: workspace polish, journal persistence of history across restarts (runtime-only store by design), history card cap choice (MAX_CARDS=8), model-rumor flake ops note (free models flap PAYMENT_REQUIRED/timeout — a retry works; failed turns leave no record lines, empty turns record `+0 -0` but create no cards); owner-directed follow-up batch: chat-output Copy + Save-as-file (app-side, S-01 deny-all untouched) — DELIVERED AND DEVICE-PROVEN 2026-11-08, `fe9e7e8` | ✅ surface proven; residuals queued |
| 3 | Frontend v2 | **IN PROGRESS — Phase 2a shipped; Phase 2b.1–2b.5 code/test/device-complete (2026-11-18).** 2b.1 records stay in `after-2b1/MANIFEST.md` (Set A 10/10 captured, 9 PASS + `a02` PARTIAL; Set B 24 PASS / 0 FAIL / 4 NOT PERFORMED / 0 BLOCKED; flows 18/19/20/28 owner-performed). Findings 10/11 fixed in §5.4. Extraction batches: §5.5 Projects (`ProjectsContent` + 11 tests), §5.6 Review (`ReviewContent` + 10 tests, T2B3-01 +2 store gates), §5.7 Settings (`SettingsContent` + 10 tests), §5.8 Onboarding (`OnboardingContent` + 10 tests, T2B5-01), §5.9 Finding 12 fix + provider offer list (`LaunchRouteState` + 4 tests, `ProvidersTest` +3). Full four-task gate green (2026-11-18, incl. §5.9): 850 tests / 91 suites, 0 failures / 0 errors; lint 0 errors / 1 standing warning. Device: Finding 11 stopped-engine sheet shows the unavailable copy and lists sessions after the first keystroke; Finding 10 dotted-only folder gates before bind; Projects top bands pixel-identical, remove dialog/cancel PASS, flow 26 PASS; Review empty content pixel-identical, flow 24 Back PASS (populated card not performed); Settings pixel-identical to `f25-after-settings.png`, Back returns to the same chat with 0 changed pixels; Onboarding pixel-identical (old vs extracted), provider menu open/dismiss PASS. **Finding 12 FIXED in §5.9:** the launch route is pinned per process by `LaunchRouteState`, `NavGraph` takes a `startDestination` instead of deriving one from live settings, and settings are awaited before the graph composes - selecting a key-less provider now keeps the user in Settings. §5.9 also trims every picker to tested providers (`Providers.AVAILABLE`: Zen, OpenRouter, OpenCode Go, Groq; Settings additionally keeps any row with a stored key so a credential is never stranded); hidden ids stay resolvable. **Device-verified 2026-11-18** (`after-f12/MANIFEST.md`): tapping key-less OpenCode Zen stays in Settings, provider restored through the UI, Back returns to the same chat. Owner provider was restored without touching any credential (2b.4 walk: verified DataStore rewrite; §5.9 walk: through the UI). Device records: `after-2b2`, `after-2b3`, `after-2b4`, `after-2b5`, `after-f12`. **Revision 4.0 reviewed 2026-11-19 (§5.10):** hybrid authority - Revision 4.0 is the forward roadmap/design direction; the Rev 3.1 brief stays authoritative for the state contract, test registry, composer gates and parity standard. Active packages V2-0 to V2-6 are trimmed (Code stays project-bound with 3 destinations; pinned/follow, cost/context, new density/nav/response preferences deferred; V2-7/8/9 parked). **2026-11-22: V2-0 to V2-6 are complete** — V2-0 ledger accepted (V2-A14), V2-1 closed for the phone scope (V2-A13), V2-2…V2-6 shipped and device-verified. V2-9 hardening opened and completed the same day (V2-A15). V2-7 opened and completed the same day (V2-A16/A18); V2-8 opened as a debug-only experiment and closed as **not adopted** on 2026-11-23 (V2-A17/A19). |
| 4 | handoff (Chat→Code P6-style seam) | **CLOSED 2026-11-12** — Batches A–G shipped (`d65ead0`→`4bc5fb7`), both plan proof rows device-proven: (a) fresh-approval dispatch with the verify-first turn live (P4/P5 drives), (b) restart-resume — the cold boot re-gates honestly and opens the dispatched trace's OWN session (`4bc5fb7` fold in capabilities) |
| 5 | intelligent UI | QUEUED |
| 6 | compat / Termux L1+ | QUEUED (after modes; roadmap P7 dependency) |
| 7 | release | **DEFERRED by the owner (2026-11-14) — no Play submission is in flight.** The AFA-first submission package is prepared (`docs/play-afa-submission-2026-11-14.md`) and the import-flow review hardening is shipped (`af178f8`); the store-level app-lock batch (#3) stays parked with the release. Decision record: `decisions-open.md` §11 |
| 8 | runtime-resource hardening (#4) | OBSERVE — the stale-reader half is closed in code by the Gate-1 registry (`d818a9c`); the supervisor start-latency probe after a manual stop is the remaining measured gap (capabilities §residual) |

Numbering caution: the plan doc §7's "G1≈S0 … G9≈Termux" is the older
review-fold mapping, a DIFFERENT family. Use the pack's Gate 0–7 rows
above for status; map back to slices only when reading the plan doc.

### 5.1 V2 Phase 2a batch record (working tree, 2026-11-15)

Design source: `crush-mobile-v2-design-brief-state-contract-test-plan-2026-11-14.md`
Revision 3.1 (Appendix C). Phase 0 remains closed; this batch implements only
the approved 2a seam.

**Production (6 files, no UI change)**

- New: `ChatHostInputs.kt` (host inputs, readiness precedence, initial
  non-ready seed), `ChatAction.kt` (sealed action set and host effects),
  `ChatDispatch.kt` (pure draft gate, permission mapping, host-effect table),
  `ChatViewState.kt` (pure projection and view types).
- Additive: `ChatViewModel.hostState` (one combine over state, binding
  snapshot, key-tagged engine sample, settings, and key availability) and
  exhaustive `ChatViewModel.dispatch(action): ChatHostEffect`.
- Additive: `AppContainer.bindingSnapshot` (one atomic binding/key write in
  `applyBinding`) and `AppContainer.engineStateForKey` (sample tagged at the
  source). Existing flows and public APIs are unchanged.
- `ChatScreen.kt` is untouched; `hostState` is not collected by the UI yet.

**Tests (5 files, 36 tests: 9 / 7 / 6 / 6 / 8)**

`ChatViewStateTest` (T2A-01), `ChatGateTest` (T2A-02),
`ChatHostInputsTest` (T2A-03, including the 50-update sequence),
`ChatModelMetadataTest` (T2A-04 / T2A-04a, plus the no-price-accessor pin),
`ChatDispatchTest` (T2A-05, including the 37-row interaction ledger).
All are plain JVM: no Robolectric runner, no Compose harness.

**Gate evidence**

- Targeted: 22 chat-package suites / 219 tests, 0 failures.
- Full gate: `:app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest :app:lintRelease` → BUILD SUCCESSFUL;
  752 tests / 81 suites, 0 failures; lint 0 errors / 1 warning (the
  standing warning, not introduced here).

**Deviations recorded**

1. `ChatHostEffect.ConfirmHandoff` is an explicit host continuation beyond
   Appendix C.3's named set. Its `onTrustRequired` callback must open the
   host's trust dialog; treating the action as ViewModel-owned would silently
   drop the consent prompt.
2. Tests live in `app/src/test/java/com/crush/mobile/ui/screens/chat/` (the
   document's `ui/chat` is shorthand), matching the existing package.
3. `NoticeView` carries `deleteError` only. `stopNoticeHandled` is not a fact
   in `ChatUiState`, so projecting it would invent state; it stays
   content-local in 2b.1.
4. `ReloadModels` has no row of its own in the 37-row table. Today's model-chip
   tap both opens the sheet and fetches the catalogue, so ledger row 3 carries
   `OpenModels` and `ReloadModels`; this keeps every action subtype owned.
5. `keyAvailable` is a combine input (invalidation source) but not a
   `ChatHostInputs` field, per Appendix C.1's "gains only `snapshotReady`".

**Not performed**

- No device run and no screenshot/parity walk; C.4's pre-batch Set A/Set B
  evidence is not attached by this batch.
- T2B1-04 (`ChatHostCallbackTest`, Compose + Robolectric) is a 2b.1 artifact
  and was not created here.

### 5.2 V2 Phase 2b.1 batch record (working tree, 2026-11-15)

Design source: same Rev 3.1 brief, §§5.6, 6.3, Appendix C.2. Phase 0 remains
closed; this batch implements only the approved `ChatContent` seam.

**Production (6 main files; one new content layer)**

- New `ChatContent.kt` (~1,500 lines): the host-free Scaffold subtree extracted
  from `ChatScreen.kt` — top bar, snackbar host, transcript (resume bar,
  bubbles, tool rows, failed cards, file-changes rail), composer, session and
  model sheets, handoff sheet, permission/question dialogs, and the root trust
  dialog. Its signature is §5.6's fixed shape:
  `(state, onAction, pendingTrust, onTrustConfirm, onTrustDismiss)`. No
  `Context`, container, ViewModel, Activity or nav controller is passed in.
- `ChatScreen.kt` rewritten as the host (1,444 lines removed): it owns the
  ViewModel/state collection, `rememberDraftCallback` (the T2B1-04 freshness
  seam), the private `TrustOrigin` table (`Resume`, `OpenInCode`,
  `Confirm(summary, destinationId, mode)`), and the `ChatContent` call.
- `ChatHostInputs.kt`: `PendingTrustUi.reason` is nullable; null renders the
  plain first-consent shape, matching
  `CrushrcTrustDialog(reasonNote: String?)`.
- `ChatViewState.kt`: three additive projection fields —
  `ComposerView.prewarm`, `RunView.engineReady`, and
  `SessionItem.messageCount/cost/updatedAt` — so the content reads no
  settings, clock or engine object.
- `ChatDispatch.kt`: `permissionDismiss(requestId)` shares the Deny button's
  deny-only path, so a dialog dismiss can never approve.
- `SessionList.kt`: primitive `detail` overload keeps the projection
  clock-free.

The Compose/Robolectric harness change is test-scoped (`app/build.gradle.kts`:
`unitTests.isIncludeAndroidResources`, Robolectric's official Java 17+
`--add-opens` set; `app/src/test/resources/robolectric.properties`: plain
`android.app.Application`). No new dependency.

**Tests (4 files, 33 tests: 9 / 8 / 12 / 4)**

- `ChatContentTranscriptTest` (T2B1-01, 9): empty cold start, both roles,
  streaming Stop and no Send, running tool without a result, retry dispatch,
  free/paid marker, resume-bar target gating.
- `ChatContentGateTest` (T2B1-02, 8): all three permission decisions enabled
  and tappable at >=48dp, dismiss = `DenyPermission` (never an approval),
  question release with no option list, composer `canType`/`canSend`
  following.
- `ChatContentSheetsTest` (T2B1-03, 12): session sheet surface/rows/empty/
  loading, pick, immediate-delete pin, model sheet selection/free badge/error,
  handoff prefill + destination + Confirm payload, in-sheet refused notice,
  trust dialog confirm-once/cancel.
- `ChatHostCallbackTest` (T2B1-04, 4): the captured draft callback sees later
  `canType`/`prewarm` values and starts/dispatches exactly once per send, in
  both directions.

**Gate evidence**

- Four classes: 33 tests / 0 failures / 0 errors.
- Targeted chat package: 26 suites / 252 tests / 0 failures / 0 errors
  (2a's 22/219 plus 2b.1's 4/33).
- Full gate: `:app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest :app:lintRelease` → BUILD SUCCESSFUL in
  19m46s; 785 tests / 85 suites, 0 failures / 0 errors; lint 0 errors /
  1 standing warning (`NotShrinkingResources`, pre-existing).
- Device after-walk (2b.1 APK, Redmi Note 7 Pro API 29, 2026-11-15): Set A
  10/10 captured (9 PASS, `a02` PARTIAL; `a01` pixel-identical in the content
  region, 0 changed pixels); the original Set B walk was 15 PASS / 2 FAIL /
  7 NOT PERFORMED / 4 BLOCKED. Flow 23 (prewarm OFF) reproduced the
  pre-existing cold-start race; flow 11 reproduced the pre-existing `+`
  cross-binding variant. Both were then fixed and re-walked PASS, superseding
  those two rows. Flows 2 (IME send), 16 (offline model error), and 22
  (prewarm ON, then restored Off) also passed in the 2026-11-16 continuation,
  and flow 28 passed owner-performed (the owner entered the lock credential).
  The current Set B is 24 PASS / 0 FAIL / 4 NOT PERFORMED / 0 BLOCKED
  (§5.3). Full record:
  `docs/parity/2026-11-15-c4-baseline/after-2b1/MANIFEST.md`.

**Deviations recorded**

1. Resume-bar trust confirm deliberately preserves the pre-2b.1 sequence: it
   re-calls the entry WITHOUT recording consent, pinning the known infinite
   re-prompt defect. Sheet/jump origins record consent then re-call, matching
   the old `pendingHandoffTrust` block. Fixing the resume loop is out of 2b.1.
2. `PendingTrustUi.reason` is `String?` (null = plain first-consent), matching
   the existing dialog contract instead of inventing copy.
3. The three additive projection fields above (plus the primitive
   `SessionList.detail` overload) extend §5.6's list. They keep `ChatContent`
   free of settings/clock/engine state; each is derived by the host/projection,
   never read by the content.
4. Tests live in `ui/screens/chat/` (the brief's `ui/chat` is shorthand).
5. The Compose/Robolectric harness needed `isIncludeAndroidResources`, the
   official `--add-opens` set (including `jdk.internal.access`), and the plain
   `Application` property. These are test-harness changes, not dependencies.
6. `ChatContent` reaches `LocalContext` indirectly through
   `MessageActionsRow`/`rememberChatTextSaver` for clipboard/SAF. No host
   object crosses the content signature; recorded as a seam observation, not a
   new defect.
7. The refused handoff notice is rendered both inside the sheet and by the
   screen snackbar (pre-existing host behaviour kept byte-for-byte). The test
   scopes its assertion to the sheet's dialog.

**Pinned pre-existing defects (not fixed in 2b.1)**

Findings 10 and 11 were pinned after the 2b.1 walk, then the owner reversed the
same-day deferral and asked for both fixes. They are fixed in §5.4 and are no
longer pinned.

- Flow 13: session delete dispatches immediately; the sheet's confirmation
  dialog is unreachable (`ChatContentSheetsTest` pins the immediate delete).
- The resume trust loop from deviation 1.
- The question dialog exposes two release buttons ("Deny" and "Dismiss") where
  the target says exactly one release action.
- Active-session marking is colour-only; there is no selected semantics.
- Bubbles carry no role semantics, and `MarkdownText(fillMaxWidth)` makes both
  roles' text full-width, so position does not distinguish them either.
  T2B1-01 pins the assistant-only actions row instead.
- A blank catalogue model name falls back to the id via 2a's `displayName`.
- Flow 23 and flow 11 were the two after-walk FAILs. Both were fixed after the
  walk under an owner-authorized deviation; see §5.3.
- Findings 10 (dotted `.crushrc` outside the gate) and 11 (stopped-engine
  false empty sheet) are no longer in this list: both are fixed in §5.4.

**Not performed**

- The device after-walk is PARTIAL, not closed. Set A 10/10 captured (9 PASS
  plus `a02` PARTIAL); Set B is now 24 PASS / 0 FAIL / 4 NOT PERFORMED /
  0 BLOCKED after the post-review fixes and re-walk of flows 2, 11, 16, 22,
  and 23; flows 18, 19, 20, and 28 are owner-performed PASS (§5.3).
- Rows still open: 4 (stop unconfirmed), 13 (delete, not re-run - baseline FAIL
  and the T2B1-03 pin stand), 14 (loading), and 27 (notices) were not re-run or
  not reproducible. Flow 4's fault injection was attempted and blocked by
  SELinux (see §5.3). The 2026-11-17 retries of flows 27 and 14 changed no row:
  a confirmed Stop raises no snackbar by design, and the engine-stopped sheet
  demonstrated Finding 11 (fixed in §5.4) instead of the loading state.
- `a02` is PARTIAL: component styling matches, but the persisted session grew
  and the captured scroll position differs, so only `a01` has exact pixel
  parity.
- API 29 cannot prove the AFA rationale, wide layouts, or API 30+ behaviour.

### 5.3 Phase 2b.1 post-review fixes (committed 2026-11-16)

The owner rejected the partial after-walk as a checkpoint and authorized a
scope deviation: fix the two device failures instead of pinning them.

**Flow 23 - prewarm cold-start race (pre-fix observation)**

With Prewarm Off persisted, cold start still logged
`starting supervisor for instance 'chat' (prewarm on)` and started the engine.
Cause: `ChatScreen` collected `settings` with `initialValue = AppSettings()`,
which resolves AUTO to prewarm on this device, and the
`LaunchedEffect(activeInstanceKey)` read that first-frame value before the
persisted Off arrived.

Fix:

- `ChatHostInputs.prewarmAtComposition` = `snapshotReady && prewarm`. The
  `stateIn` seed is non-ready with prewarm false; the combine waits for the
  persisted settings flow.
- `ChatScreen` drops the separate `settings` collection and reads
  `host.inputs.prewarmAtComposition`. The effect now keys on
  `activeInstanceKey`, `snapshotReady`, and prewarm, and returns on the
  non-ready seed. Persisted Off therefore never starts the engine at
  composition; On starts once when the coherent snapshot lands.
- Test: `ChatHostInputsTest` +1 (`prewarm cannot fire before the coherent
  snapshot lands`), now 7 tests.

**Flow 11 - New chat cross-binding (pre-fix observation)**

`startChatSession()` called `setActiveChatPool()`, `bindProject()`, then
`newThread()` synchronously. `bindProject()` nulls the outgoing `Workspaces`
handle, so `newThread()` fell back to `bootstrap()`, which resumed the last
session. On a project -> chat-pool switch this left the project transcript and
title under a `Chat - no tools` subtitle.

Fix:

- `ChatViewModel.pendingNewChatKey` binds the intent to the instance that
  requested it.
- `startChatSession` clears the outgoing view (messages, active session, file
  changes, pending prompts, stop notice, resume hint), sets the pending key,
  and calls `newThread()`.
- `newThread` and its reattach fallback keep the intent when they must
  bootstrap.
- `doBootstrap` consumes the intent only on the matching instance, skips the
  last-session resume, mints a fresh chat session, and clears any old
  transcript. `freshSession` also covers a boot with nothing to resume.
- `bindProject` retires an intent when an overtaking binding switch changes the
  instance.
- Test: new `NewChatBootstrapTest` (3 rows) pins the instance-key matching.

**Evidence**

- Compile: `:app:compileDebugKotlin :app:compileDebugUnitTestKotlin` green.
- Targeted chat package: 27 suites / 256 tests, 0 failures / 0 errors.
- Full gate: `:app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest :app:lintRelease` → BUILD SUCCESSFUL in
  16m28s; 789 tests / 86 suites, 0 failures / 0 errors; lint 0 errors /
  1 standing warning.
- Device re-walk of flows 23 and 11 on the post-review APK (installed
  2026-11-15 20:54:58): **PASS**.
  - Flow 23, composition: `prewarm off; supervisor deferred to first input for
    p077dc19e`; `ps` showed only `com.crush.mobile`, no `libcrush.so`. The
    first keystroke started the engine:
    `[p077dc19e] engine Running after 3420ms`.
  - Flow 11: project `Handoff fingerprint mismatch audit` -> sheet New chat
    produced `New chat` / `Chat - no tools - engine stopped` with an empty
    transcript; typing started `[chat]` (Running after 3277 ms) and logged
    `new chat: minting a fresh session on 'chat'`, then
    `current-session reported: 57d2acca`. No project transcript survived.
  - Flow 22: Prewarm On cold start logged
    `starting supervisor for instance 'chat' (prewarm on)` and
    `[chat] engine Running after 3068ms` (`f22-prewarm-on.log.txt`). The
    setting was restored to Off (`f22-prewarm-restored-off.png`) and the next
    cold start logged `prewarm off; supervisor deferred to first input`.
- Device re-walk of flows 2 and 16 on 2026-11-16: **PASS**.
  - Flow 2: Keyboard -> `Enter sends`; the Gboard IME send arrow cleared the
    composer, started a turn, and persisted `IME-SEND-POSTFIX-2B1`
    (`f02-ime-send.log.txt`, `f02-ime-typed.png`, `f02-ime-sent.png`,
    `f02-ime-done.png`).
  - Flow 16: airplane mode on; the model sheet rendered
    `UnknownHostException: Unable to resolve host "opencode.ai"` in red
    (`f16-offline-model-sheet-postfix.png`); airplane mode restored off.
  - Flow 4 was attempted with an engine kill under a live turn, but SELinux
    denies `runas_app` signalling `libcrush.so`, so the unconfirmed-stop
    branch was not exercised; the plain Stop took the confirmed path. Flow 4
    stays NOT PERFORMED.
- Flow 28 (owner-performed, 2026-11-16 18:54-18:55): cold start logged
  `lock engaged; refusing to bootstrap` with no engine start; the owner
  entered the app-lock credential at 18:55:12 (`LockOverlay: unlocked`,
  `resume gate=OPEN ... freshUnlock=true`). The agent entered no credential.
- Flow 18 (owner-performed, 2026-11-16 20:24): the owner confirmed the
  already-consented `Legacy workspace`; the log shows `APPROVED` then
  `DISPATCHED; target session dd947578...`, with no trust dialog
  (`f18-f20-handoff.log.txt`, `f18-after-dispatch.png`).
- Flow 20 (owner-performed, 2026-11-16 20:48-20:50): a fresh sheet has no
  Discard (nothing written yet), so the walk staged an APPROVED row with an
  offline Confirm (bootstrap cannot become ready -> dispatch fails, row stays
  resumable). The owner reopened the sheet, saw `Discard this handoff`, and
  tapped it: `DISCARDED: the trace row is ABANDONED`; pulled row
  `status=ABANDONED`, `targetCodeSessionId=null`. No DISPATCHED stamp was
  written (`f20-discard.log.txt`, `f20-trace-row.txt`,
  `f20-after-discard.png`).
- Flow 19 (owner-performed, 2026-11-16 22:23): the first attempt (21:50) was
  void staging - the destination carried only `.crushrc`, which the gate does
  not probe, so it dispatched with no dialog. Re-staged with a 17-byte
  `crushrc` in `Test project folder 2`, sheet opened from session `c22de7a3`;
  the owner selected that different project and tapped Confirm. The gate
  logged `handoff blocked: consent required (crushrc present, no consent
  recorded)`; the owner tapped Allow, which recorded the file fingerprint
  (`1712a7a6...`) and dispatched to session `d59579a4`
  (`f19-consent-dialog.log.txt`, `f19-trace-row.txt`,
  `f19-after-allow.png`). The dotted-file execution is Finding 10, fixed in
  §5.4.
- 2026-11-17 retries (no row change):
  - Flow 27: a Stop on a live 3000-word turn truncated the reply and restored
    the send arrow with no snackbar (`f27-stop-live.png`,
    `f27-stop-after.png`, `f27-stop-settled.png`). The `Stop requested...`
    notice fires only when the server cancel is unconfirmed; forcing that
    needs the local engine killed mid-turn, which SELinux blocks (the same
    wall as flow 4).
  - Flow 14: the engine-stopped `TestProject3` sheet rendered `No chats yet`
    on its first visible slide frame, with no `Loading chats...` frame in a
    25 fps recording (`f14-stopped-sheet.mp4`, `f14-stopped-empty.png`,
    `f14-sheet-push-frame.png`); after the first keystroke started that
    workspace's engine the same sheet listed two 21h-old sessions
    (`f14-warm-list.png`, `f14-stopped-empty.log.txt`). Recorded as Finding 11.
    A further attempt on the genuinely fresh `F14Fresh2` import (20:53) opened
    the sheet while its engine was live but `connecting to agent`: the first
    visible sheet frame carried the full `No chats yet / Start one and it will
    appear here.` copy (`f14b-fresh-empty.png`, `f14b-fresh-slide.png`), and
    the app created its first session only afterwards (`no prior session with
    content; creating one`; `f14b-fresh-workspace.log.txt`). The empty copy is
    therefore correct with a live engine; Finding 11 is the stopped-engine
    early return. No `Loading chats...` frame exists in that 151-frame ~9.5 fps
    recording either (`f14b-fresh-workspace.mp4`, with a ~50 fps burst through
    the sheet animation): the local listSessions round trip completes before
    the first sheet frame, so the row stays NOT PERFORMED.

**Evidence summary**: `f23-postfix-coldstart.png`,
`f23-postfix-first-keystroke.png`, `f23-postfix-coldstart.log.txt`,
`f11-postfix-new-chat.png`, `f11-postfix-minted.png`, `f11-postfix.log.txt`,
`f22-prewarm-on3.png`, `f22-prewarm-on.log.txt`,
`f22-prewarm-restored-off.png`, `f02-enter-sends.png`,
`f02-ime-typed.png`, `f02-ime-sent.png`, `f02-ime-done.png`,
`f02-ime-send.log.txt`, `f16-offline-model-sheet-postfix.png`,
`f28-lock-owner.log.txt`, `f18-handoff-sheet-open.png`,
`f18-after-dispatch.png`, `f18-f20-handoff.log.txt`,
`f20-after-discard.png`, `f20-discard.log.txt`, `f20-trace-row.txt`,
`f19-restage-sheet-open.png`, `f19-consent-dialog.log.txt`,
`f19-trace-row.txt`, `f19-after-allow.png`, `f19-first-attempt.log.txt`,
`f19-first-attempt-row.txt`, `f19-first-attempt-session.png`,
`f19-dotmarker.txt`, `f27-stop-live.png`, `f27-stop-after.png`,
`f27-stop-settled.png`, `f14-stopped-empty.png`,
`f14-sheet-push-frame.png`, `f14-warm-list.png`, `f14-stopped-sheet.mp4`,
`f14-stopped-empty.log.txt`, `f14b-fresh-empty.png`,
`f14b-fresh-slide.png`, `f14b-fresh-workspace.mp4`,
`f14b-fresh-workspace.log.txt`.

**Deviation note**: this is a behavior fix, not extraction. It was explicitly
authorized by the owner after the partial after-walk was rejected. It does not
change the `ChatContent` signature or introduce new screen surfaces; the only
visible change is the corrected prewarm/New-chat state.

### 5.4 Findings 10/11 fix (working tree, 2026-11-17/18)

The owner reversed the 2026-11-17 deferral and asked for both findings to be
fixed before continuing Phase 2.

**Finding 10 - the dotted `.crushrc` was outside the trust gate**

- `ProjectTrustService` now probes BOTH project-root names the pinned engine
  executes: `crushrc` and `.crushrc`.
- A folder carrying only the plain `crushrc` keeps the legacy single-file
  SHA-256, so every consent recorded before this fix stays valid - the upgrade
  does not force a re-consent for the common case.
- Any state involving `.crushrc` (alone or beside the plain file) uses a
  composite, NAME-BOUND digest: a domain tag, the roster size, and each
  present file's name + declared size + bytes in canonical order. A
  byte-identical rename from `crushrc` to `.crushrc` therefore re-gates,
  because a shell script can branch on its own invocation path even when the
  bytes are identical. Adding, changing, or removing either file moves the
  fingerprint.
- `confirmAtGate` still persists only the gate-observed state; its
  appeared/changed/removed outcomes now cover the dotted file as well.
- The F04 dialog keeps the measured A05 copy. "crushrc" there is the config
  class name and covers `.crushrc`; no security claim was weakened.
- Tests: `ProjectTrustServiceTest` 16 -> 25. New rows cover dotted-only first
  consent and Allow, name distinction for identical bytes, pre-fix plain
  consent compatibility, adding/renaming/both-file/removal transitions, and
  a dotted file appearing inside the gate->record window.

**Finding 11 - the sessions sheet claimed `No chats yet` without asking**

- `ChatUiState.sessionsUnavailable` and `SessionListView.unavailable` carry
  the new fact to the sheet.
- `loadSessions()` no longer returns silently when `port` (or the workspace
  pool) is absent. It settles `sessionsLoading = false,
  sessionsUnavailable = true`, so the sheet can tell the truth: the list was
  never queried.
- An empty, unavailable list renders `Chats aren't available` plus
  `The engine isn't connected. Send a message to start it, then reopen this
  sheet.` instead of `No chats yet`. A cached NON-empty list still renders
  normally, and the next live listing clears the flag.
- The genuine empty state is preserved: a live engine that answers with an
  empty list still renders `No chats yet / Start one and it will appear here.`
- Tests: `ChatContentSheetsTest` 12 -> 14 (unavailable copy, cached-list
  precedence) and `ChatViewStateTest` 9 -> 10 (projection).

**Gate evidence**

- Targeted: 25 trust + 14 session-sheet + 10 projection + 11 Projects = 60
  tests, 0 failures / 0 errors.
- Full four-task gate: `:app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest :app:lintRelease` -> BUILD SUCCESSFUL in
  22m09s; 812 tests / 87 suites, 0 failures / 0 errors; lint 0 errors /
  1 standing warning (`NotShrinkingResources`).

**Device evidence (2026-11-18, post-fix APK)**

- Finding 11 PASS: `TestProject3` had two sessions and a stopped engine; its
  sheet rendered `Chats aren't available` / `The engine isn't connected. Send
  a message to start it, then reopen this sheet.` and no `No chats yet`
  (`f11b-stopped.png`). One typed character started the engine; the reopened
  sheet listed the 8-message session and `chat` (`f11b-started.png`,
  `f11b-live-list.png`).
- Finding 10 PASS: a fresh `DottedTrust3` folder carrying ONLY `.crushrc`
  raised F04 before any bind and had no marker at that moment
  (`f10-dotted-gate.png`). Cancel left no marker (`f10-dotted-cancel.png`).
  Allow recorded the composite fingerprint `9bc5fe26...` (plain SHA-256 of
  the same 32 bytes is `2445e106...`), and `.dotmarker3` appeared only after
  the first typed character started the engine (`f10-dotted-allowed-chat.png`,
  `f10-dotted-marker.png`).
- Record: `docs/parity/2026-11-15-c4-baseline/after-2b2/MANIFEST.md` and
  `findings-2b2.log.txt`.

**Not changed**

- Reason/log wording still says "crushrc" for both names. That is the config
  class the gate and the measured dialog already name; the dotted file is a
  crushrc-class config. A dotted-specific note is security-copy polish, not a
  gate gap.

### 5.5 V2 Phase 2b.2 batch record (working tree, 2026-11-18)

Design source: same Rev 3.1 brief, §§5.6, 6.5 and the 2b.1 extraction rules.

**Production (3 changed, 2 new; one new content layer)**

- New `ProjectsContent.kt`: the host-free Projects render tree - top bar,
  import button/copy, import and registry errors, loading/empty/list branches,
  project rows, the named remove confirmation, and the shared trust dialog.
  Local view state (the pending-remove row) stays `remember`-scoped here.
- New `ProjectsViewState.kt`: `ProjectsUiState` (projects, loading, busy,
  error) lifted out of the ViewModel so the content imports no Android/
  ViewModel type.
- `ProjectsScreen.kt` rewritten as the host: ViewModel/state collection,
  `filesDir`-derived `legacyPath`/`internalRoot`, the SAF/storage/All-files
  launchers and their rationale, row-open routing (legacy vs gated), and the
  trust/remove callbacks. Its public signature is unchanged.
- `ProjectsViewModel.kt`: state flow retyped to `ProjectsUiState`; no behavior
  change.

**Tests (`ProjectsContentTest`, 11)**

- Empty registry explanation; loading copy; busy import disable; registry +
  import errors; populated rows (label/path/environment); legacy row opens but
  has no Remove; imported-row remove is named, confirmed, cancel-safe, and
  fires once; internal-project copy names the workspace tree it deletes; busy
  disables the remove confirm; import/back callbacks; shared trust dialog
  confirm-once.
- Targeted: 11 tests / 0 failures / 0 errors.

**Deviations and scope notes**

- The plan's Projects coverage list names a "current marker". The as-built
  surface has none, and §4.7.2 assigns that target to the Phase 7 redesign.
  2b.2 is an extraction, so the suite pins the actual surface and does not
  invent the marker.
- Import permission flows stay host-owned (they launch system UI); the content
  receives only the decoded path result/error and the current state.

**Gate and device evidence**

- Full four-task gate: BUILD SUCCESSFUL in 22m09s; 812 tests / 87 suites,
  0 failures / 0 errors; lint 0 errors / 1 standing warning.
- Device parity (2026-11-18): the extracted screen's top band (header,
  Import folder, explanatory copy) and the first two project rows are
  **pixel-identical** to the 2b.1 baseline `f26-after-projects.png` (0 changed
  pixels in y=100..1200; lower rows differ only because the registry gained
  F14Fresh/F14Fresh2/DottedTrust3). The remove dialog names the folder and
  cancel leaves it untouched; opening a row replaces the stack and Back exits
  to the launcher (flow 26). Record:
  `docs/parity/2026-11-15-c4-baseline/after-2b2/MANIFEST.md`.
- Not claimed: full-screen pixel parity (registry content changed), the
  destructive Remove tap (owner scope), and API 30+/AFA/wide layouts.

### 5.6 V2 Phase 2b.3 batch record (working tree, 2026-11-18)

Design source: same Rev 3.1 brief, §§5.6, 6.5 and the 2b.1 extraction rules.

**Production (2 new, 1 rewritten; one new content layer)**

- New `ReviewContent.kt`: the host-free Review render tree - header/Back,
  process-scope copy, running-turn banner, export message, empty/list
  branches, turn cards (header/counts, superseded/read-only, unreadable and
  no-diff copy, per-file diff rows, revert/export actions), and the named
  revert confirmation. The pending-revert row stays `remember`-scoped here.
- New `ReviewViewState.kt`: `ReviewUiState` (cards, runInFlight,
  exportMessage) crosses the seam as pure data.
- `ReviewScreen.kt` rewritten as the host: container-scoped
  `ReviewHistory`, coroutine scope, SAF `CreateDocument` launcher, byte copy,
  export-message state, and the `ReviewContent` call. `ReviewRoute(onBack)`
  is unchanged.
- No engine, container, Context, or SAF launcher enters the content layer.
  `FileDiffRow` is reused from the chat content (module-internal composable).

**Tests**

- New `ReviewContentTest` (10): empty state; populated header/counts/actions;
  superseded read-only + disabled revert; running-turn banner + disabled
  revert; unreadable/no-diff copy; reverted-labelled path; revert confirm,
  naming, cancel and dispatch; export root/path dispatch; export message;
  Back callback.
- T2B3-01 extends `ReviewHistoryTest` (+2, now 14): `beginTurn` supersedes
  existing cards before the next record; the newest-eight cap preserves
  newest-first order.

**Gate evidence**

- Targeted Review content/store: 10 + 14 tests green.
- Full four-task gate: BUILD SUCCESSFUL in 11m00s; 824 tests / 88 suites,
  0 failures / 0 errors; lint 0 errors / 1 standing warning.

**Device evidence**

- Review empty state is **pixel-identical** to the 2b.1 baseline
  `f24-after-review.png` outside the status bar: 0 changed pixels in
  y=100..2340. Flow 24 (Chat -> Review -> Back to the same chat) PASS.
- A populated card was NOT PERFORMED on device: the process had no completed
  file-changing turn. The rows/actions are test-pinned in
  `ReviewContentTest`. Record:
  `docs/parity/2026-11-15-c4-baseline/after-2b3/MANIFEST.md`.

**Deviation notes**

- `timeLabel`/`SimpleDateFormat` moved with the cards into the content. It is
  clock-dependent but free of Android/Context types, matching the existing
  rendering contract; the tests assert structure, not pinned wall-clock text.
- Export remains host-owned because it needs the SAF launcher and
  `contentResolver`; the content only emits `(rootPath, path)`.

### 5.7 V2 Phase 2b.4 batch record (working tree, 2026-11-18)

Design source: same Rev 3.1 brief, §§5.6, 6.5 and the 2b.1 extraction rules.

**Production (2 new, 2 rewritten; one new content layer)**

- New `SettingsContent.kt`: the host-free Settings render tree - top bar,
  provider radios with stored-key badges, the API card (saved/hint, add/replace
  form, delete), appearance and keyboard chips, the permission switch with its
  safety copy, the tuning rows with AUTO/MANUAL truth and Reset, the About card,
  and the error snackbar. The in-progress key text and the replace/cancel flags
  stay `remember`-scoped here.
- New `SettingsViewState.kt`: `SettingsViewState` carries the settings snapshot,
  the provider list and selection, the vault ids, the error, the resolved tuning,
  and the device profile. All are plain domain values - no repository, Context,
  or coroutine scope crosses the seam.
- `SettingsScreen.kt` rewritten as the host: ViewModel/state collection,
  `Providers.resolve`, `resolvedTuning`, `deviceProfile`, and the write
  callbacks. `SettingsScreen(onBack, onOpenDiagnostics)` is unchanged.
- `SettingsViewModel.kt`: adds `saveTuning(overrides)` and `resetTuning()` so the
  content can hand back a computed override set instead of holding the
  repository. No other behavior change.

**Tests (`SettingsContentTest`, 10)**

- Provider rows (names, hints, unverified note, exactly one stored-key badge);
  provider selection dispatch; saved-key hint plus Replace/Delete; add-key flow
  with Save disabled until text and trimmed dispatch; theme and Enter-send chip
  dispatch; permission switch dispatch; tuning AUTO/MANUAL truth, an override
  dispatch, and Reset; About card diagnostics dispatch; error snackbar; Back.

**Gate evidence**

- Targeted: 10 tests, 0 failures / 0 errors.
- Full four-task gate: BUILD SUCCESSFUL in 16m12s; 834 tests / 89 suites,
  0 failures / 0 errors; lint 0 errors / 1 standing warning
  (`NotShrinkingResources`).

**Device evidence**

- Device `bbf8ca69` (API 29) returned after the extraction; the 2b.4 APK
  (`app-debug.apk`, built 16:24 host clock, commit `89e294b` tree) was
  installed without clearing app data.
- Settings content region (y=100..2340) is **pixel-identical** to
  `after-2b1/f25-after-settings.png`: 0 changed pixels. Only the status bar
  differs.
- Settings -> Back returns to the same chat: 0 changed pixels (y=100..2340)
  between chat-before-Settings and chat-after-Back.
- **Finding 12 (new, pre-existing host behavior, not 2b.4):** selecting a
  provider with no stored key (Groq) in Settings persists
  `provider_id=groq`, recomputes `hasApiKey`, and resets the root to
  Onboarding, discarding the Settings stack (`MainActivity.kt:216-239`).
  Reproduced on device: Settings `06` -> Onboarding `07`. The Settings
  content still dispatches `onSelectProvider(id)` exactly as before the
  split, so the extraction did not cause it; do not fix it inside an
  extraction batch. Fixed in §5.9 (2026-11-18): the launch route is now
  pinned per process and `NavGraph` no longer derives a start destination
  from live settings. The owner's `provider_id` was restored to
  `opencode-go` by rewriting the DataStore preferences protobuf with the app
  force-stopped (host/device md5 matched); no credential file was read or
  modified. Evidence: `docs/parity/2026-11-15-c4-baseline/after-2b4/MANIFEST.md`.
  **Re-verified on the post-fix build** (`after-f12/04-zen-selected.png`): the
  same tap now stays in Settings.
- **Finding 13 (new, copy-only, open, 2026-11-18):** Settings' API card reads
  "Add a OpenCode Zen key to start chatting." - wrong article before a vowel.
  Observed at `after-f12/04-zen-selected.png`; owner chose to record rather
  than rebuild. Suggested: "Add a key for OpenCode Zen to start chatting."
- Theme/keyboard/permission/tuning/About rows were not re-driven on device;
  their render and dispatch are pinned by `SettingsContentTest`.

**Deviation notes**

- The SettingsViewModel previously had no tuning writers: the tuning UI lived in
  `SettingsScreen` and called `settingsRepository.saveTuningOverrides` directly
  from a composable `rememberCoroutineScope`. The two ViewModel methods move that
  write across the seam without changing what is persisted.
- The content still computes the next `TuningPolicy.Overrides` from the current
  settings snapshot (a pure copy), because the host passes the snapshot in; the
  host owns the write.

### 5.8 V2 Phase 2b.5 batch record (working tree, 2026-11-18)

Design source: same Rev 3.1 brief, §§5.6, 6.5, 4.7.7 and the 2b.1 extraction rules.

**Production (2 new, 1 rewritten; one new content layer)**

- New `OnboardingContent.kt`: the host-free onboarding render tree - welcome
  mark, title/subtitle, provider dropdown with the free-tier label and the
  spoken "Provider: ..." phrase, per-provider key placeholder/hint, masked key
  field with the reveal toggle, error live region, and the
  Start chatting/Saving key button. The key draft, the provider-menu and
  reveal toggles, the saving flag, and the failure text stay `remember`-scoped
  here.
- New `OnboardingViewState.kt`: the provider catalogue and the selected row
  cross as plain domain values.
- `OnboardingScreen.kt` rewritten as the host: `SettingsViewModel`, provider
  selection, and the save coroutine (`saveApiKeyAwait`, then
  `saveProviderId` only on success). `OnboardingScreen(onCompleted)` is
  unchanged.
- No Context, ViewModel, container, or Keystore enters the content. CORR-001
  is preserved: no provider contact and no validation; the button reports only
  what the host write returned.

**Tests (`OnboardingContentTest`, 10, T2B5-01)**

- Provider slot name; free-tier label in the slot and the menu; provider
  selection dispatch; key hint and placeholder follow the selected provider;
  blank/whitespace key keeps Start chatting disabled; trimmed submit dispatch;
  saving state (disabled, "Saving key..."); a failed save surfaces the
  existing copy and restores the button; a successful save advances exactly
  once and restores the button; the reveal toggle flips the mask. No test
  asserts key validation (CORR-001).

**Gate evidence**

- Targeted: 10 tests, 0 failures / 0 errors (3m51s incremental).
- Full four-task gate: BUILD SUCCESSFUL in 9m43s; 844 tests / 90 suites,
  0 failures / 0 errors; lint 0 errors / 1 standing warning
  (`NotShrinkingResources`).

**Device evidence**

- Device `bbf8ca69` returned for the walk. The 2b.5 APK (built 17:07 host
  clock) was installed without clearing app data. Onboarding was surfaced
  through the Finding 12 path (Settings -> key-less Groq), which resets the
  root to the start destination.
- The extracted screen is **pixel-identical** to the old screen captured on
  the 2b.4 APK: 0 changed pixels in y=100..2340
  (`after-2b5/01-onboarding-2b5.png` vs
  `after-2b4/07-settings-groq-selected.png`). Only the status bar differs.
- The provider dropdown opens with every provider and the free-tier labels;
  Back dismisses it and the screen returns to the initial render with 0
  changed pixels (`02-onboarding-provider-menu.png`,
  `03-onboarding-menu-dismissed.png`).
- `provider_id` was restored to `opencode-go` by rewriting the DataStore
  preferences protobuf (host/device md5 matched); the next cold start landed
  on the working chat (`04-chat-restored.png`). No credential file was read
  or modified.
- Not performed: entering/saving a key on device (a real credential write);
  the save/advance/failure branches are pinned by `OnboardingContentTest`.
  API 29 only; no API 30+/AFA/wide claim. Record:
  `docs/parity/2026-11-15-c4-baseline/after-2b5/MANIFEST.md`.

**Deviation notes**

- The submit outcome crosses back through `onResult(Boolean)` rather than a
  suspend callback: the Keystore write is asynchronous and host-owned, and
  the content may not hold a coroutine scope for it. This is also what keeps
  "success advances" testable - the content calls `onCompleted` only after
  the host reports `true`.
- `SettingsViewModel` remains the onboarding host ViewModel; no new
  ViewModel or repository was added.

### 5.9 Finding 12 fix + provider offer list (2026-11-18)

Owner directives: fix Finding 12, and hide every provider the build has not
tested from the UI.

**Finding 12 - the root no longer re-routes on a provider switch**

- New `LaunchRouteState` (`ui/LaunchRouteState.kt`): the launch decision
  (chat vs onboarding) is taken ONCE per process and memoised. A probe that
  throws is not memoised, so an unreadable vault cannot pin a route.
  `AppContainer.launchRoute` holds it, following the `LockState` precedent:
  Android recreates the Activity around credential-screen closure, and a
  re-decided route would rebuild the NavHost around a different start
  destination and discard the restored back stack.
- `MainActivity` now collects settings with a NULLABLE initial value and
  waits for the first persisted snapshot before composing the graph. The old
  `initial = AppSettings()` named the resolver default provider while the
  real id was still in flight - the same mirror-vs-persisted race as the
  2026-10-20 "no API key" bug.
- `NavGraph` takes `startDestination` instead of `hasApiKey`; the old default
  derived it from live settings, so any provider write could change it.
  Explicit navigation after launch (onboarding -> chat, Projects row open)
  is unchanged.

Behaviour now: selecting a provider with no stored key keeps the user in
Settings, where the API card targets that provider and a key can be pasted.
A cold start with no key for the selected provider still lands on
Onboarding; a key saved later is reached through the existing explicit
navigate.

**Provider offer list - untested integrations are hidden**

- `Providers.offerable(extraIds)` returns `ALL.filter { isKnown || id in
  extraIds }`; `Providers.AVAILABLE` is the no-extras floor.
- Onboarding offers `AVAILABLE`. Settings offers
  `offerable(vaultIds + selectedProviderId)`: a credential already stored,
  or the provider the screen is currently configuring, can never be
  stranded behind a hidden row.
- `opencode-go` is now `isKnown = true`: the id was device-proven in the
  v099-experiment lane on engine 0.99.0, and it is the provider persisted on
  the owner's device. The old `false` was stale, not conservative - and once
  the pickers gate on `isKnown`, leaving it false would have hidden the
  provider the app actually runs.
- Offered today: OpenCode Zen, OpenRouter, OpenCode Go, Groq. Hidden but
  still resolvable, so a persisted id keeps booting: Hyper, Cerebras,
  DeepSeek, OpenAI, Anthropic, Google Gemini, Vertex AI, xAI, GitHub
  Copilot.
- Groq stays offered because it has completed handshakes on this build. It
  is NOT a viable free-tier target (8K TPM caps every agent shape - see
  docs/providers/groq.md); a paid Groq key is unaffected.

**Tests**

- `LaunchRouteStateTest` (4): the first decision is pinned across a vault
  change; onboarding pin; the vault is consulted exactly once; a failed
  probe is not pinned.
- `ProvidersTest` (+3): `opencode-go` is known; the pickers offer exactly
  the four tested ids in `ALL` order; a stored-key extra stays offerable
  and an unknown extra changes nothing.

**Gate evidence**

- Full four-task gate (`:app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest :app:lintRelease`, log
  `run-f12-gate.log`): **BUILD SUCCESSFUL in 20m 24s** - 850 tests / 91
  suites, 0 failures / 0 errors / 0 skipped; lint 0 errors / 1 standing
  warning (`NotShrinkingResources`). Net +6 tests and +1 suite over the
  2b.5 gate (844/90).

**Device evidence** (`docs/parity/2026-11-15-c4-baseline/after-f12/`,
device `bbf8ca69`, API 29, owner unlock entered by the owner)

- Settings offered **Zen, Hyper, OpenRouter, Go, Groq**. The four tested rows
  plus Hyper, which holds a stored key - the deliberate exception (hiding a
  configured credential would strand it); it keeps its "unverified on this
  build" note. Onboarding has no vault extras and so offers only the four
  (unit-pinned; a device check needs a data wipe).
- **Finding 12 fixed on device:** tapping key-less OpenCode Zen left the user
  IN Settings - radio moved, API card switched to "No API key stored". Under
  2b.4 the same tap replaced Settings with Onboarding
  (`after-2b4/07-settings-groq-selected.png`).
- Restored OpenCode Go **through the UI** (no DataStore rewrite needed now);
  `settings.preferences_pb` contains `opencode-go` only; Back returned to the
  same chat, which re-bootstrapped to `ready` on `deepseek-v4.1-flash`.
- True cold start (process verified dead, pid changed): logcat
  `gate=PROMPT coldStart=true … verified=false`, system biometric dialog
  auto-opened; after the owner's unlock the app was on **Chat** (engine idle
  until typing) - `after-f12/12-unlocked-after-coldstart.png`, log
  `gate=OPEN … verified=true`.
- Methodology correction recorded: an earlier pair of captures labelled
  "coldstart" were an Activity recreation in the SAME pid (`am force-stop` had
  not taken effect). They were renamed rather than deleted; they now serve as
  evidence that a recreation preserves the route and the unlock. A second
  correction is also recorded: the 0-byte captures taken mid-cold-start were a
  transient `screencap` failure, NOT `FLAG_SECURE` - the same window captures
  fine after unlock.
- Observed, not fixed (owner's call): the API card reads "Add a OpenCode Zen
  key to start chatting." - wrong article; suggested "Add a key for OpenCode
  Zen to start chatting."
- **End-to-end turn after the provider round-trip and a true cold start:
  PASS** (`after-f12/13-typed.png`, `14-turn-streaming.png`,
  `15-turn-reply.png`). Typed "Reply with exactly: ok"; the engine booted to
  `ready` on the first keystroke, the reply was exactly "ok", the finish was
  clean (`file changes: +0 -0`, no `finishError`), and the composer returned
  to idle. A throwaway chat `db2554b6` remains in Recents.

### 5.10 V2 Revision 4.0 review - owner decisions (2026-11-19)

Reviewed `Crush_Mobile_V2_Redesign_Plan_Revision_4_0_2026-11-19.md`
(610 lines) against the local November 14 Rev 3.1 brief
(`crush-mobile-v2-design-brief-state-contract-test-plan-2026-11-14.md`,
1,319 lines including Appendix C) and the repository at `1679f25`
(`main == origin/main`, clean). Revision 4.0 is planning-only; it
authorizes no implementation.

**Authority (owner, 2026-11-19): hybrid**

- Revision 4.0 is the forward roadmap and design direction.
- Rev 3.1 remains authoritative for the UI state contract (§5), the test
  registry (§6), the composer gates (§5.5.1) and the parity evidence
  standard (§8.5). A V2 package links those sections; it does not restate
  or silently replace them.
- Active V2.0 roadmap, design direction only: **V2-0 to V2-6**, trimmed as
  below.
- **V2-9 is opened for hardening only** (owner, 2026-11-22, V2-A15): ASK
  default evidence, real-Keystore coverage, key-rotation checklist. Gate 7
  store submission stays deferred.
- **V2-7 and V2-8 are opened** (owner, 2026-11-22, V2-A16/A17): V2-7 is the
  deterministic foundation (reasoning block + setting, unknown-part fallback,
  closed host registry); V2-8 is a debug-only structured-UI experiment.
- Parked, not in V2.0: none of the remaining packages. Gate 7 remains deferred
  by the owner (2026-11-14).

**Owner decisions that trim Revision 4.0**

1. **Code stays project-bound.** Three primary destinations - Chat,
   Projects, Settings. Review and Diagnostics stay pushed routes. Brief
   §4.6 is the shell authority: phone bottom bar below 600dp, rail
   600-839dp, drawer/extended rail at 840dp+. Revision O1 is therefore
   RESOLVED by the brief's shell plus the 3-destination decision, not an
   open prototype choice; the phone is not "drawer/overlay only when
   requested". V2-1's navigation prototype is narrowed to validating the
   brief's breakpoints, not choosing a different pattern.
2. **No brief deferral is reopened.** Pinned message + follow mode (brief
   decisions #3/#4), cost/context-window display (#8/#9), new
   density/navigation/response-rendering preferences, structured model UI,
   and release packaging stay backlog. V2-4 tasks 4 and 7 and V2-6 task 2
   are trimmed accordingly; V2-7/V2-8/V2-9 are parked. (All three were opened
   on 2026-11-22: V2-9 hardening V2-A15; V2-7/V2-8 V2-A16/A17.) Theme remains the
   existing setting; reduced-motion behavior stays with the V2-2
   accessibility work, not a new preference batch.
3. **Finding 13 copy is folded into V2-6** (copy only; the owner already
   declined a standalone rebuild).

**Package-level trims (the revision text itself is unchanged)**

| Package | Status | Applied trim |
|---|---|---|
| V2-0 | **COMPLETE** | Baseline re-lock at `1679f25`; O11 harness landed (`d5a0718`) and the loading row was captured on device on 2026-11-22; ledger accepted 2026-11-22 (V2-A14) — see `docs/v2/V2-0-signoff-2026-11-22.md`. |
| V2-1 | **COMPLETE (phone scope)** | Prototype within brief §4.6; no drawer-vs-tabs reopen. Closed 2026-11-22 (V2-A13): tasks 3/5/6 superseded by shipped evidence, tablet prototypes carried under O3 — `docs/v2/V2-1-exit-reconciliation-2026-11-22.md`. |
| V2-2 | ACTIVE | Tokens/components; density is a rendering check, not a user preference. |
| V2-3 | ACTIVE | 3 destinations; delete the dead `Screen.Models`; no 4th Code destination. |
| V2-4 | ACTIVE | Drop pinned/follow (task 4) and cost/context (task 7); keep empty state, transcript, composer, session sheet, picker and actions. |
| V2-5 | DONE (phone) | Code is project-bound; phone-first; tablet panes only after phone acceptance (still parked). |
| V2-6 | DONE (phone) | Settings/Onboarding redesign; Finding 13 copy; no new preferences. |
| V2-7 | **COMPLETE (foundation, 2026-11-22)** | 7.1 reasoning Collapsed/Off (device-verified), 7.2 unknown-part fallback, 7.3 `ui_block/v1` closed registry + debug preview. No external UI framework; V2-8 closed as not adopted (V2-A19). |
| V2-8 | **CLOSED (not adopted, 2026-11-23)** | 3 candidate-A runs, 1/3 schema-valid; runs 1-2 rejected for drift, run 3 rendered only with exact keys. Exit criteria not met; no production renderer. V2-7 registry remains. |
| V2-9 | **COMPLETE (hardening, 2026-11-22)** | ASK default evidence, real-Keystore instrumented coverage (3/3 on `bbf8ca69`), key-rotation checklist + scanner (PASS, 674 files). Gate 7 remains deferred; no submission, signing or listing work. |

**Corrections to Revision 4.0 (recorded here; the revision file is not edited)**

1. §1.3/O13: **CORRECTED 2026-11-19 - the revision is right.** The
   November 14 Rev 3.1 brief is on disk and is **1,319 lines / 74,654
   bytes**, including Appendix C. An earlier note in this section said 996
   lines; that figure came from `Measure-Object -Line`, which counts
   non-blank lines only (996 non-blank + 323 blank). There is no missing
   document and O13 is resolved. The line-by-line reconciliation runs
   against the full 1,319-line file.
2. §2.3: 2b.4 device parity was PERFORMED, not pending.
   `after-2b4/MANIFEST.md` records the Settings content region
   pixel-identical and Back returning to the same chat at `89e294b`. What
   was not device-driven: the theme/keyboard/permission/tuning/About rows
   (unit-pinned); provider selection was Finding 12, fixed in §5.9.
3. §2.3/O11: **CLOSED 2026-11-22.** The fresh-workspace loading row was the
   sub-second half that survived two manual retries. It now has a debug-only
   stall harness (logs `O11 session-list stall injected: 8000ms` on device) and
   a debug-only preview activity that rendered the real row for the device
   capture: `docs/parity/2026-11-22-o11-session-loading/` (Loading sessions /
   Loading chats). The live window is evidenced by the stall log plus unit
   pins, not by a manual end-to-end capture - the MANIFEST says so.
4. §2.2: "hardened against TOCTOU" overstates the service's contract.
   `ProjectTrustService` says check-at-bind is the practical contract,
   "not a TOCTOU impossibility proof". Use "gate-observed recording; no
   TOCTOU-free claim".

**Gaps carried into the active roadmap**

- V2-3: delete the dead `Screen.Models` object (`NavGraph.kt:16`; it has
  no `composable`). The brief's Phase 5 cleanup is otherwise lost.
- V2-6: explicitly preserve/redesign Diagnostics; the Models sheet is
  covered by V2-4's picker work.
- V2-0: O11's harness work is **done** (2026-11-22): `DebugStall` +
  `SessionListPreviewActivity`, device frames in
  `docs/parity/2026-11-22-o11-session-loading/`. No further parity attempt is
  needed for that row.

**Open-decision dispositions**

| ID | Disposition |
|---|---|
| O1 | RESOLVED - brief §4.6; 3 destinations, adaptive breakpoints |
| O2 | RESOLVED 2026-11-19 - owner chose the neutral graphite accent, 15sp body, platform monospace, and call-site reclassification; sections 4-8 approved (V2-A8). V2-2 authorized; V2-3 not |
| O3 | OPEN - tablet panes only after phone acceptance |
| O4 | BACKLOG - tool-activity visibility after usability evidence |
| O5 | BACKLOG - no preference set beyond existing Settings |
| O6/O7 | PARKED with V2-7/V2-8 |
| O8 | NO CHANGE - import-only/AFA stands; any policy change needs an ADR and store review |
| O9 | SEPARATE ROADMAP - not a V2 prerequisite |
| O10 | DEFERRED - deny-all remains |
| O11 | PASS 2026-11-22 - debug-only stall harness (logs on device) + debug-only preview activity captured both rows (`Loading sessions...` / `Loading chats...`) in `docs/parity/2026-11-22-o11-session-loading/`; live window evidenced by the stall log and unit pins |
| O12 | RESOLVED 2026-11-21 - Finding 13 copy fixed in V2-6 ("Add a key for <provider> to start chatting.") |
| O13 | RESOLVED - brief on disk is 1,319 lines; the earlier 996 figure counted non-blank lines only |

**Accepted plan-wide rules from Revision 4.0 (no behavior change)**

Evidence labels; baseline re-lock at the current SHA; build on the 2b
content/host seams; the `Project -> WorkspaceBinding -> Session -> Run`
identity chain and run-freeze invariants; the state-contract checklist;
the stop conditions; performance budgets set after the V2-1 prototype.

**What the revision gets right, verified locally**

- The 2b.1-2b.5 commit sequence and scope; Finding 12 fixed; the provider
  offer rule and `opencode-go` known state.
- The four-task CI gate on Temurin 21 with bytecode 17; CI builds an
  engine-less APK because only `libjunixsocket-native.so` is tracked in
  `jniLibs`.
- The session-list loading half and the populated Review card are honestly
  NOT PERFORMED; Finding 13 is open.
- Deny-all, trust, registry, handoff and Review statements match the
  ledger, subject to correction 4 above.

**Boundary.** This review moved docs only; no source, test or build file
changed. No V2 implementation is authorized.

**V2-0 (started 2026-11-19).** Baseline lock, source inventory, ADR
register and the Rev 3.1 reconciliation live in `docs/v2/`; planning only,
no implementation and no source change. Task 6 phone evidence landed the
same day in `docs/parity/2026-11-19-v2-0-baseline/` (15 captures): it
confirmed the missing Current marker, the stale/mislabelled Models sheet
and the missing engine version/contract on-device, and added F-E, a header
phase that read `connecting to agent` while no engine process existed.

**V2-1 (started 2026-11-19).** `docs/v2/V2-1-design-foundation.md` proposes
the token set and screen-state catalogue; it is not approved and changes
no source. Measuring the shipped theme found live contrast defects rather
than taste disputes: `tertiary` `#FD79A8` at **2.48:1** on white is used as
text for "key stored", "MANUAL" and the Review revert notice; success green
is **2.54:1**; warning amber is **1.51:1**; error red is **3.82:1**; and
the dark background `#0F0F1A` is blue-black, which the brief rejects. The
proposal keeps the existing diff pairs (7.87 / 6.57 light, 10.5 / 8.0 dark)
and offers a measured warm-neutral palette plus spacing, shape, type,
dimension and motion tokens. Four owner answers (Q1-Q4) and approval of
sections 4-8 are the visual-direction sign-off (gate O2). Prototype tasks
remain NOT PERFORMED because they add screens and need authorization; on
2026-11-19 V2-1 was PARTIAL, not done.

> **2026-11-22 — V2-1 closed (phone scope), V2-A13.** The owner approved
> `docs/v2/V2-1-exit-reconciliation-2026-11-22.md` option A: tasks 3, 5 and 6
> are closed as **superseded** by the shipped, device-verified V2-3/V2-4/V2-5
> screens (no prototype screens were built); task 4 (tablet/wide) remains
> NOT PERFORMED under **O3**. §3 of that record lists what the closure cannot
> claim. The paragraph above is retained as the 2026-11-19 record.

**Owner answers (2026-11-19) and the V2-A8 decision.** Accent =
**neutral graphite** `#3B3B36` light / `#D3D0C8` dark (10.62 / 12.17 on
paper / bg), not the proposed violet; body = 15sp; code font = platform
monospace for now; V2-2 scope = include the call-site reclassification.
Because the graphite accent is only 1.53:1 from `ink` in light and 1.29:1
in dark, interactive and selected state can no longer rely on hue:
underline, fill, weight, filled icons and a 2dp focus outline carry it.
Sections 4-8 are now binding on V2-2; V2-3 shell work is still not
authorized.

**A defect the approval uncovered.** Wiring the tokens found that the user
bubble paints `primaryContainer` while `MarkdownText` defaults to
`onSurface`, so the owner's own messages rendered at **2.05:1** in light
theme. The fix passes `onPrimaryContainer` explicitly at the bubble call
site; a JVM test pins the role pair.

### 5.11 V2-2 token integration (working tree, 2026-11-19/20)

V2-2 implements the approved section 4-8 contract: `ui/theme/Color.kt`
(warm neutral palette, neutral graphite accent, semantic status colours),
new `ui/theme/Tokens.kt` (`CrushColors`/`LocalCrushColors`, spacing, shape,
dimensions, type), `Theme.kt` (every M3 role the app reads is specified;
diff colours follow the compiled theme mode), `Type.kt` (Material slots
mapped onto the new scale). `ui/components/CrushComponents.kt` adds the
first shared component set, piloted on Diagnostics (read-only).

Call-site reclassification (Q4): `bodySmall` 52 -> 8 (the 8 are deliberate
metadata), `FontFamily.Monospace` 14 -> 1 (inside `CrushType.code`), and
`colorScheme.tertiary` 3 -> 0 outside the theme. Tool/diff status now reads
`toolRunning`/`toolSucceeded`/`toolFailed`/`pending` instead of overloading
`primary`. The neutral accent cannot carry state by hue alone (1.53:1 from
ink in light), so links stay underlined, selection uses a fill plus a
filled icon, and status is always written as words.

Evidence and open limits are tracked in `docs/v2/V2-2-token-integration.md`
(JVM token/contrast tests, Robolectric component tests, the four-task gate,
and the NOT PERFORMED device rows). No device visual claim is made in this
batch.

**Result (2026-11-20).** Four-task gate green on attempts 2-4 (871 tests,
0 failures, 0 skipped; lint 0 errors, 1 pre-existing warning); attempt 1
failed on a test-fixture typo ("Import a folder" vs "Import folder"), fixed.
Device pass on `bbf8ca69` (API 29) captured the new palette in Chat,
Settings (light and dark), About and the Diagnostics pilot. It found two
defects the JVM tests could not: a fill-only card that read as no container
(1.02:1 against paper) and a white system navigation bar in dark mode.
Both were fixed in the component/theme layer and re-verified on device.
A third finding, F-J (selected segments distinguished only by a subtle
fill under the neutral accent), is recorded for V2-4 rather than patched
here.

Task-list honesty: Revision 4.0's tasks 1-4 are done; task 5 is PARTIAL
(Compose tests exist, but the repo has no screenshot-test harness, so
device captures stand in); task 6 is PARTIAL (theme verified in tests and
on device, font-scale/reduced-motion/density checks NOT PERFORMED). The
V2-2 *exit* criteria are met, which is what "complete" means here.

### 5.12 V2-3 adaptive app shell (complete, 2026-11-20)

V2-3 moves primary navigation into an adaptive shell. Three destinations -
Chat, Projects, Settings - with the brief 4.6 breakpoints: bottom bar below
600dp, compact rail 600-839dp, extended rail at 840dp and above. Selection
is a filled icon + indicator fill + an always-visible label, never colour
alone (the neutral graphite accent cannot carry state by hue). Onboarding,
Review and Diagnostics render without shell chrome; Review and Diagnostics
keep their pushed-route Back arrows.

The shell is the single navigation source of truth: `navigatePrimary` uses
`popUpTo(Chat) { saveState = true }` + `launchSingleTop` + `restoreState`, so
repeated bar taps replace instead of stacking, Chat stays at the root, and a
Settings detour preserves the Chat `NavBackStackEntry` (and therefore its
ViewModel/run). The chat sheet lost its Settings/Projects buttons because
they duplicated the bar; Review stays contextual in the sheet.
`ChatDispatchTest`'s interaction ledger is now 35 rows / 22 action subtypes /
4 host continuations. Settings and Projects lost their top-bar back arrows:
they are primary destinations, and Back is the back-stack contract.

**Verification.** Final four-task gate
(`v2-3-gate-final-1120-083146.log`) green: 885 tests, 0 failures, 0 skipped,
95 suites; lint 0 errors / 1 pre-existing `NotShrinkingResources` warning.
`PrimaryDestinationsTest` (6) pins the routes and the 599.9/600/839.9/840
boundaries; `ShellNavigationTest` (8) pins bar/rail/extended rail rendering,
shell hiding on Review, no duplicate entries after Projects -> Settings ->
Chat, Back returning to Chat, Chat entry survival across a detour, and rapid
taps not stacking. CI run `44669754612` on `4994a95` is GREEN (completed
success 2026-11-20T03:48:45Z).

**Device pass** (`bbf8ca69`, API 29, app 1.0.0) in
`docs/parity/2026-11-20-v2-3-shell/` - 12 captures: light/dark bars,
selection affordance, the reduced sheet, Projects/Settings without back
arrows, Back -> Chat, IME above the bar, Legacy-workspace open -> Chat, and
rotation. Landscape (850.9dp physical) rendered the compact rail, not the
extended rail: the window excludes the display cutout and the 3-button nav
bar, so it is below 840dp (F-K). The extended-rail branch is Compose-tested
at 1000dp only. 2.0x font scale, reduced motion, density, API 30+, wide/
tablet and instrumented tests are NOT PERFORMED.

### 5.13 V2-4 chat redesign (complete, 2026-11-20)

V2-4 works on the existing `ChatViewState` -> `ChatAction` seam; no host,
dispatch or engine-boundary change. Scope is Revision 4.0 tasks 1, 2, 3, 5, 6,
7 (picker only), 8 and 9, with tasks 4 (pinned/follow/jump-to-bottom) and 7's
cost/context metadata trimmed by V2-A4. The working record, code map and
evidence table live in `docs/v2/V2-4-chat-redesign.md`.

Changes in the working tree:

- **Empty state (D2):** a pure `ChatViewState.emptyCopy()` replaces the
  prewarm-only line. A settled failure says "Agent unavailable"; a recoverable
  agent says "Agent needs attention"; prewarm-off cold start explains that
  typing starts the agent; a ready `Chats` binding says tools are off. A ready
  engine is never described as not started.
- **F-E header:** `RunView.statusLabel(kind)` derives the header status from
  `readiness` (the same snapshot as the binding), not the free-text `phase`.
  `ready`, `switching agent`, `agent not ready - send to retry` and the failure
  reason are now mutually consistent with the binding; `Chats` keeps its
  `no tools` hint. The status is a dot plus words, not success colour alone.
- **F-C picker visibility:** the top-bar control always renders a label
  (`displayName` -> suffix-stripped id -> `Model`) plus a dropdown arrow, with
  a stable "Choose model" accessible name.
- **F-B picker provenance/loading:** the sheet subtitle names the actual
  provider; `ModelCatalogueView.loading` is projected from a new
  `modelsLoading` flag around `loadModels`; loading, error and empty are
  distinct; `ReloadModels` finally has an emitter (header and warning banner);
  a selected model absent from the live list is named in a warning banner.
- **F-J selection:** `CrushChoiceChip` adds a filled check, accent border and
  semi-bold label; all Settings FilterChips migrate to it. The session sheet's
  active row now carries a check, the word `Current` and
  `stateDescription = "Current chat"`.
- **Transcript/markdown:** visible `You`/`Crush` role labels, 12dp message
  rhythm, user bubbles wrap, assistant prose caps at 640dp, heading sizes
  now inherit into nested bold/italic/code with a 12sp floor (level 6 was
  8sp), the bubble colour now reaches every prose block (F-L), and a
  tool-only row no longer draws an empty bubble (F-M).
- **Composer:** growth uses `CrushDimens.composerMaxLines` (8); the §5.5.1
  gates and the always-enabled streaming Stop are unchanged.

Evidence: the final four-task gate (attempt 5,
`v2-4-gate-1120-5.log`) is green: 899 tests, 95 suites, 0 failures, 0 errors,
0 skipped; lint 0 errors / 1 pre-existing `NotShrinkingResources` warning.
Attempts 2-4 were also green; attempt 1 failed one test because a
merged-semantics assertion used `hasAnyAncestor` on a clickable row, and the
assertion was corrected.

The device pass (`bbf8ca69`, API 29, 393dp) captured the empty state, ready
header, picker, model sheet, session states, streaming/Stop, session
switching, F-J chips, 2.0x font scale, process recreation and Retry in
`docs/parity/2026-11-20-v2-4-chat/` (30 stored captures plus a before-crop;
capture 09 was deleted because it showed the masked key chip). Retry needed
an owner-approved brief airplane-mode cut; after restore, Retry started a new
turn and the ASK dialog gated the tool, which was denied. The pass found three
real defects the tests could not see: **F-L**, user-bubble prose was near-black
on graphite because `MarkdownText` ignored its colour for prose blocks;
**F-M**, a tool-only row drew an empty filled bubble; and **F-N**, the Settings
chip rows clipped at 2.0x. All were fixed and re-captured (`12`, `15`, `31`).
CI is green on `ff3fef9` (`44715709979`), `c8c476a` (`44725769114`),
`d655d63` (`44727630308`), `e12750c` (`44733288902`) and `5f28ec0`
(`44734151566`).

NOT PERFORMED on device: frame timing (no baseline), reduced motion, density,
wide/tablet, API 30+, provider switch (model selection was exercised), and
instrumented tests. These stay open, not claimed.

### 5.14 V2-5 Code / Projects / Review (complete on phone, 2026-11-21)

V2-5 starts from the owner's "yes start" and the V2-A9 answer: ship **Build
(project chat) + Review**; defer Explore until a real read-only file browser
exists. No fourth primary destination, no fake Explore tab. The working
record is `docs/v2/V2-5-code-projects-review.md`.

Implementation batch 1:

- **Projects (F-A):** `ProjectsUiState` now carries the active project id and
  legacy-active fact projected from `container.activeBinding`; rows render a
  check + `Current` + `Current project` semantics, a written trust status
  (`Configuration approved` / `No approved configuration`), and a human
  identity (`Imported folder` / `App-managed workspace` / `App history
  workspace`) instead of the raw `L0_EMBEDDED` enum. `ProjectRef` carries
  `trusted` from the Room consent stamp. Loading/empty/error use the shared
  components; the SAF-cancel false error is fixed; the list refreshes on
  re-entry so consent is not stale.
- **Code identity:** the Chat header for a Project binding is now
  `<project label> · <status>` via `BindingView.headerStatus`; ChatPool keeps
  `Chat - no tools` and Legacy stays status-only. This uses the V2-4
  readiness snapshot.
- **Review scoping:** `ReviewHistory` is scoped by the run-frozen capture root:
  `beginTurn(root)` supersedes only that root's cards, `runningScopes` gates
  revert per project, and the 8-card cap is per root. The Review host filters
  by the active binding, so project A's cards cannot appear under project B.
- **Review truth:** a `snapshotUnavailable` turn is retained as "not measured"
  instead of disappearing; cards show project label, turn time, capture time
  and session; Review states that the app runs no tests and that export copies
  the file as it is on disk now. The surface has a standard top bar.
- **Rail:** the changed-files panel uses the shared card, says "not measured"
  when appropriate, and the diff hunk header now reports separate before/after
  counts instead of the same line count.
- **Shell IME (F-O, owner-reported):** the phone bottom bar was staying between
  the composer and the keyboard. `CrushShell` now hides it while the IME is
  visible and restores it on close; rails stay. Device re-verification is part
  of the pending pass.

**Owner IA correction (2026-11-20, V2-A11/A12).** The notebook makes the split
explicit: three tabs **Crush Chat / Crush Code / Settings**; Crush Chat uses
the legacy workspace and is Q&A-only; Crush Code contains projects and every
project has its own sessions. The implementation now:

- labels the tabs Crush Chat and Crush Code (Projects is the project list
  inside Crush Code, not a peer tab);
- binds Crush Chat to the legacy instance and applies the same readback-verified
  deny-all tool policy there (ChatPool is compatibility/archival);
- makes the Chat and Code routes list-first: Crush Chat shows chats, a Code
  project shows its own sessions, "New chat" mints a legacy chat and "New
  session" stays on the project engine;
- keeps the project home (identity, trust, latest measured turn, Open Code
  chat, Review) as the bridge into a project's sessions.

The IME bar fix (F-O) is included. The final gate on this corrected tree is
green: `v2-5-gate-1120-9.log`, 917 tests / 96 suites / 0 failures, lint 0
errors / 1 pre-existing warning. CI runs `44835596927` (`fff704d`),
`44836244335` (`266a6c4`) and `44867537034` (`682a4bc`) are green; this
evidence commit needs its own run.

The owner resumed the device pass on 2026-11-21 and it ran to completion on the
final build. Verified on the phone:

- Crush Chat binds the legacy engine and answers a Q&A turn with no tool rows
  (`33`), the Chat list shows new + old legacy chats (`34`), and the engine log
  shows the deny-all policy for the legacy `[]` instance (35 ids, readback
  verified) before each spawn.
- Crush Code lists only projects (`35`), the project home (`36`), project
  sessions (`37`), and the project-bound Code chat with the Code tab selected
  (`38`). "Open Code chat" lands on that project's own sessions (`88`, `93`).
- The IME fix (`40`, `51`) after the first IME build failed: `WindowInsets.ime`
  read 0 under adjustResize (`39`); gate 9 replaced it with visible-frame
  detection.
- A measured turn populated Review (`57`) and the project rows (`69`), the
  revert dialog named file/turn/project (`66`) and the revert deleted the
  created file (`67`), and the export was byte-identical on disk (`60`), with
  the app confirming the copy (`64`).
- Two projects switch Current cleanly (`80`, `88`, `91`, `92`-`94`).
- A changed `crushrc` re-gates on the cold-resume path (`72`); Cancel binds
  nothing (`73`); restoring the original bytes clears the prompt (`74`).

V2-5 is complete for the phone. Tablet/wide panes, the changed-`crushrc` accept
branch, a not-measured Review card, the Chat-switch veto Toast and a running
session row are not device-captured; the first is out of scope by decision
(V2-A4/O3) and the rest are listed in the capture manifest with their test
coverage.

Explore, tablet panes, Review history persistence and a test runner stay out of
scope. ChatPool sessions created before V2-A11 are archival, not migrated or
listed.

### 5.15 V2-6 Settings / Onboarding (complete on phone, 2026-11-21)

Revision 4.0's V2-6 was trimmed by V2-A4: no new preference set (O5 stays
closed), no density/navigation/reduced-motion/response-rendering control. What
shipped is the design pass plus the preserved contracts. Record:
`docs/v2/V2-6-settings-onboarding.md`; captures:
`docs/parity/2026-11-21-v2-6-settings/`.

- **Settings** now uses the V2-2 component set and tokens: section headers in
  the token scale, hairline `CrushCard`s, graphite primary/secondary actions,
  one spacing scale. The visible order follows brief §4.7.4: Provider/API key ->
  Permissions -> Appearance (theme, keyboard) -> Performance -> About with the
  Diagnostics entry. Every control still writes real state; there are no dead
  switches and no app-lock toggle. Permissions stays a switch whose OFF state is
  the safe one, with written status and the blast-radius warning.
- **Onboarding** keeps its behavior and gains the token hero/card plus an
  explicit honesty line: the key is saved on this device and is not checked
  until the first message (CORR-001 remains open; the screen no longer risks
  implying verification).
- **Finding 13 is fixed**: the missing-key line reads "Add a key for
  \<provider\> to start chatting." and is pinned by `SettingsContentTest`.
- **Preserved**: credentials remain write-only in the surface
  (`hasApiKey`/`vaultIds`/hint only, `SecureKeyStore` untouched), the tested
  provider offer rule and stored-key exception are unchanged, and provider
  selection still cannot navigate away or strand a key.

Evidence. Full gate `v2-6-gate-4.log` (post-2.0-fix): 919 tests / 96 classes / 0
failures / 0 errors / 0 skipped; lint 0 errors / 1 pre-existing
`NotShrinkingResources` warning. Device (2026-11-21): before `00`-`01`, after
`12`-`14`, permission states `15`-`16`, Finding 13 `20`, and the provider
round-trip `17`-`19` with the owner's Go restored in `21`. The cold start with OpenRouter reported
`ciphertext=true` and composed the Chat tree, so the launch route stayed Chat
rather than Onboarding. Selecting key-less Zen logged
`provider=opencode-zen storedCiphertext=false
providersWithKeys=hyper,openrouter,opencode-go` and did **not** substitute a
keyed provider; the restored Go bootstrap then reported
`ensureAgentReady ... is_ready=true` with `deepseek-v4.1-flash` re-resolved
(`22`). A 2.0x font-scale pass then caught the provider "key stored" badge
squeezing the hint column; the badge became a status line of its own and the
recheck passed (`24` before, `26`-`27` after), with the scale restored in
`28`.

Limits: Onboarding's device frame is NOT PERFORMED (reaching it would cost the
stored key), as are tablet/wide and Onboarding at 2.0x, and a device key
save/replace/delete (credential entry is owner-only). The pass also observed
that injected `adb input tap` was intermittently unreliable on Settings while
real-finger taps, drags, hover and keyboard all worked; the manifest records
that as a harness observation with no root cause claimed.

## 6. Upstream filings

- **2026-11-13: closed as NO ACTION.** The unfiled drafts (candidates 1–3 in
  [`upstream-outreach-drafts-2026-11-05.md`](upstream-outreach-drafts-2026-11-05.md),
  candidate 4 in [`upstream-outreach-plan.md`](upstream-outreach-plan.md))
  are not blocking anything and stay available; upstream issue #4222 remains
  the one filed item. Re-open only if the owner wants to file.
- On record: charmbracelet/crush **issue #4222** filed by the owner —
  gh-verified 2026-11-05 (OPEN, created 2026-11-03, author `sadi21863-bit`,
  "Plan catalog drift: agent/init 500s 'large model not found in provider
  config'…"); draft candidate 3's body cites it as the ask-delivery
  precedent. Any further filings need the owner's explicit go per the
  standing hygiene note in the drafts file.
