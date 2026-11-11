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

**GATE 0 = CLOSED on both halves.** Code: `7ec34ed` (S-01), `3a3a233`
(S-02), `f5d24dc` (S-04), wording-honesty in S-03's ledger row. Device:
A03 restore-trust drive PASSED 2026-11-06. Residuals ride the E6 stress
pass: 4 JVM-only A03 variants (cancel / changed-crushrc / noConfig-flip
/ real-install migration), REST boot-window row, S-01 Failed-badge row.

Branch state truth (2026-11-11, after the P5 drive fold): HEAD/main/
origin-main = the P5 drive fold commit (docs-only, this commit: the
delete+re-import cycle, the discard leg closed live, the chat-side F04
consent bug found+proven+spec'd, the same-destination resume dispatch
salvage, the synthetic map extended) ← the P4-excursion fold commit
`7714bf0` (docs-only: the strand + RESUME + dispatch lifecycle closed
live on `1dda7d90`/`593a04a6`, the synthetic-input map extended, the
redaction blocks explained; CI run 42988745179 GREEN) ← `6c749fc` (docs:
the CI flake specimen fold; run 42760602680 GREEN) ← `20d2ce0` (test
fix: the rewrite-flip assert rides SIZE, not the same-millisecond mtime
race; CI 42760055813) ←
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
| 0 | trust + chat fail-closed + REST transport hardening (S-01, S-02, S-04 above) | **CLOSED, both halves** (supersedes this cell's 2026-11-05/`1d229de`-era PARTIAL reading): S-01 `7ec34ed` + S-04 `f5d24dc` + **S-02 `3a3a233`** (central ProjectTrustService, gate-before-bind on every path incl. restoreLastBinding) — **A03 device drive PASSED 2026-11-06** (capabilities §Gate-0 closures); **2026-11-08: S-01's deny-all scope was REOPENED by an external catalogue audit (9-id gap, proven by binary strings + upstream allToolNames) and RE-CLOSED by `a7e6d14` + live device re-proof (capabilities §S-01 catalogue parity)**; residuals ride the E6 stress pass: 4 JVM-only A03 variants, REST boot-window row, S-01 Failed-badge row + new: stale agent tool advertisement (engine-side, benign under the execution wall, rides the upgrade ladder) |
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
