# V2 gap audit — external review verified against source

Status: **verification record 2026-11-23; resolution update 2026-11-23.** This
file answers one question per claim from the external product/architecture
review: *is it true in this tree?* The verification pass itself was read-only;
the defects it confirmed were then fixed in batches V2-AUDIT-1/2/3/4 and those
rows now read `FIXED (V2-12)`. A2, A6, A7/A7b, A12, A13 and A14's device-budget
half stay open on purpose. The diagnostics redaction gap (A9) was closed by the
earlier V2-11 test batch (three classes, focused-green 19 tests / 0 failures,
`v2-ledger-tests-1123g.log`) and keeps that verdict rather than being relabelled.

## Method and limits

- Read-only source verification against `225ce94` plus the uncommitted V2-11
  work (three new test classes, docs, and one source fix:
  `app/src/main/java/com/crush/mobile/ui/screens/diagnostics/DiagnosticsReport.kt`,
  a one-line `matches` → `containsMatchIn` fix for the crash-trace terminator).
  Every cited line was opened, not recalled.
- Behaviour claims were cross-checked against the existing test suite where one
  exists (named per row).
- **No runtime reproduction** was performed for this audit: no rail-width
  device pass, no forced permission-API failure, no concurrent start harness.
  Rows that need those are marked `needs reproduction` even when the source
  makes the mechanism clear. V2-12 later added JVM pins for the rail-width
  callback and the start gate (`CyclicBarrier`); it did not add a device,
  TalkBack or API 30+ pass.
- The external audit's own limitation stands: it was a source/documentation
  read, not a device test. This file shares that limit and does not upgrade it.

**Gate status:** Full four-task gate after V2-AUDIT-1/2/3/4: green — **997
tests / 104 suites / 0 failures / 0 errors / 0 skipped**; lint **0 errors / 1
pre-existing warning**; 34m01s (`v2-12-gate-2026-11-23.log`). The earlier V2-11
focused run (19 tests, 0 failures, `v2-ledger-tests-1123g.log`) remains the
evidence behind A9, which this gate carries forward.

## Resolution (2026-11-23)

Batches V2-AUDIT-1/2/3/4 are implemented and verified; the rows they cover say
`FIXED (V2-12)` below. Per-fix detail — changed symbols, test pins, and the
residual limits — is in [`docs/v2/V2-AUDIT-FIXES.md`](V2-AUDIT-FIXES.md).

Still open by decision: **A7/A7b** (V2-AUDIT-0, needs owner sign-off before the
behaviour change), **A2**, **A6**, **A12**, **A13**, and A14's 3-child PSS
measurement. Device, TalkBack and API 30+ checks remain **NOT PERFORMED**.

Verdict vocabulary:

| Verdict | Meaning |
|---|---|
| **CONFIRMED** | the cited code does what the audit says |
| **CONFIRMED-NUANCE** | the mechanism is real, but the stated impact or severity is wrong |
| **CLOSED IN V2-11** | was true, now pinned by a new test |
| **FIXED (V2-12)** | confirmed (or confirmed-with-nuance) and corrected in the V2-AUDIT-1/2/3/4 batch; the row names its test pin |
| **DESIGN CHOICE** | implemented as described; whether it is wrong is an owner decision |
| **NOT CONFIRMED** | could not be reproduced from source |

## Verdict table

| # | Finding | Verdict | Evidence (file:line) | Batch |
|---|---|---|---|---|
| A1 | Rail ignores the Chat run-veto | **FIXED (V2-12)** | Was **CONFIRMED-NUANCE**: `ui/navigation/CrushShell.kt:190` honoured the Boolean; `:230` (rail) and `:294` (extended rail) discarded it. But the veto only suppressed a binding reset (`MainActivity.kt:266-281`): the rail still landed on the project-bound chat and the Projects tab stayed selected; no run was abandoned. **Fix:** `selectPrimary` (`CrushShell.kt:182`) is now the shared path for bottom bar (`:210`), rail (`:244`) and extended rail (`:305`). Pin: `ui/navigation/ShellNavigationTest.kt` **16 tests** (6 new at 411/700/1000dp, veto + allowed per layout) | V2-AUDIT-1 — **done (V2-12)** |
| A2 | Project home is a modal sheet, not a destination | **DESIGN CHOICE** | `ui/screens/projects/ProjectsContent.kt:207`, `:365` | roadmap, owner decision — **open** |
| A3 | Review export hides current-vs-historical semantics | **FIXED (V2-12)** | Was **CONFIRMED**: copy `ReviewContent.kt:107-113`; per-card export `:285`; label `"Export a copy"` `:351`; source doc `:339-342` said the copy is byte-exact *as it sits on disk now*. **Fix:** label is now **"Export current file"** (`ReviewContent.kt:365`), with a read-only note for historical turns at `:121-133`. Pin: `ReviewContentTest` **13 tests** | V2-AUDIT-2 — **done (V2-12)** |
| A4 | Projects destination explains itself twice | **FIXED (V2-12)** | Was **CONFIRMED**: the same copy appeared at `ProjectsContent.kt:119-120` and `:190-191`. **Fix:** the second copy is removed; the surviving one is `ProjectsContent.kt:118-124`. Pin: `ProjectsContentTest` **15 tests** | V2-AUDIT-2 — **done (V2-12)** |
| A5 | Onboarding promises "70+ models from a single API key" | **FIXED (V2-12)** | Was **CONFIRMED**: `ui/screens/onboarding/OnboardingContent.kt:112` promised a cross-provider inventory while the provider is selected at `:122-148` and Settings already scopes keys per provider. **Fix:** the heading at `:112` now reads "Connect a provider and chat with its available models"; the selector stays provider-qualified (`:137-158`). Pin: `OnboardingContentTest` **12 tests** | V2-AUDIT-2 — **done (V2-12)** |
| A6 | `chat` route + `codeActive` jointly decide the selected tab | **CONFIRMED** | `CrushShell.kt:160-170`, `MainActivity.kt:261-265` | monitor; no rewrite — **open** |
| A7 | Permission-policy application failure is swallowed | **CONFIRMED** | `data/crush/WorkspaceManager.kt:234-235` (`skipPermissions`), `:237-243` (`setPermissionMode`): both log and continue; `:255` then runs `agent/init`; `open()` returns success | **V2-AUDIT-0** — **open** (owner sign-off) |
| A7b | A failed apply is *remembered as applied* on reattach | **CONFIRMED (audit missed)** | `WorkspaceManager.kt:282-295` re-applies the recorded `yoloMode`/wire mode; the recorded value is the request, never a read-back | **V2-AUDIT-0** — **open** (owner sign-off) |
| A8 | Engine budget check and reservation are not atomic | **FIXED (V2-12)** | Was **CONFIRMED-NUANCE**: `engine/EngineInstanceRegistry.kt:128-144` was check-then-act on a `ConcurrentHashMap` (`:85`); `Grain.STARTING` was set *after* the check and was not counted (`:199` counted live children only). **Fix:** `requestStart` now checks and reserves under `synchronized(lock)` (`EngineInstanceRegistry.kt:160-177`), and `othersHoldingSlots` (`:251-265`) counts live children **and** `Grain.STARTING`; a terminal `Failed(willRetry=false)` releases the reservation, a retrying failure keeps it, and `AppContainer.requestEngineStart` registers the runtime before the gate (`AppContainer.kt:902-907`). Pin: `EngineInstanceRegistryTest` **19 tests** (4 new, incl. the `CyclicBarrier` two-thread case) | V2-AUDIT-3 — **done (V2-12)** |
| A9 | Diagnostics redaction/tail has no tests | **CLOSED IN V2-11** | new `app/src/test/.../diagnostics/DiagnosticsReportTest.kt` (11 tests: block order, key masking in log tail/crash/notes, truncation marker, null-vs-false probe, empty crash section, `parseUncaughtFromLog` terminator/last-crash/mid-write/no-crash); focused run 11/11 green; the terminator test found and drove the `matches` → `containsMatchIn` fix. The 997-test V2-12 gate re-runs all 11 green. Screen-level, clipboard and share-path remain NOT PERFORMED | closed (V2-11) — green in the V2-12 gate |
| A10 | A failed Settings key save discards the draft | **FIXED (V2-12)** | Was **CONFIRMED**: `ui/screens/settings/SettingsContent.kt:267-269` closed the editor and cleared `newKey` before the async save reported; Onboarding already kept a `saving` state (`OnboardingContent.kt:41-42`). **Fix:** Save calls `onSaveKey(key) { ok -> }` and clears `replacing`/`newKey` only on `ok`; a failure keeps the editor and the draft (`SettingsContent.kt:279-301`). Pin: `SettingsContentTest` **17 tests** | V2-AUDIT-2 — **done (V2-12)** |
| A11 | Key deletion has no confirmation | **FIXED (V2-12)** | Was **CONFIRMED**: `SettingsContent.kt:254-259` called `onClearKey` directly; project removal does confirm (`ProjectsContent.kt:228-248`). **Fix:** Delete opens a dialog naming the provider, and `onClearKey` runs only from `confirm-delete-key` (`SettingsContent.kt:266-277`, dialog `:316-342`). Pin: `SettingsContentTest` **17 tests** | V2-AUDIT-2 — **done (V2-12)** |
| A12 | The app cannot answer agent questions | **CONFIRMED-NUANCE** | dialog `ui/screens/chat/ChatContent.kt:423-460` is honest about it; the raw payload is preserved (`ChatViewModel.kt:325-329`); the transport already exists: `data/crush/CrushApi.kt:477` `answerQuestions`, `:481` `cancelQuestions`. Only the choice UI + resolution state are missing | roadmap (large) — **open** |
| A13 | Registry retains every runtime for the process lifetime | **CONFIRMED** | `EngineInstanceRegistry.kt:85` `ConcurrentHashMap`; `stop()` (`:163-180`, `stopRuntime` at `:175`) stops the child but retains the registry entry; there is no removal API in the public surface (`:101-197`) | architecture decision — **open** |
| A14 | Budget comments no longer match the Chat/Code split | **FIXED (V2-12)** | Was **CONFIRMED (doc drift)**: `EngineInstanceRegistry.kt:43-48` and `ChatViewModel.kt:601-603` still said "chat + one project". **Fix:** comments now say two non-legacy projects, chat on the legacy key (`EngineInstanceRegistry.kt:53`, `ChatViewModel.kt:602-603`, `AppContainer.kt:244`). Doc-only; the 3-child PSS measurement is still NOT PERFORMED | doc fix — **done (V2-12)**; device budget open (V2-AUDIT-6) |
| A15 | Auto-approve enables on one tap (review item, unnumbered) | **FIXED (V2-12)** | `SettingsContent.kt:607-634`: `PermissionMode.AUTO_APPROVE` is now behind a confirmation that names the blast radius ("read, change and delete files and run commands"); the switch opens it (`:564-576`) and nothing changes until `confirm-auto-approve`. "Show only confirmed effective state" is **not** part of this fix — without the A7 read-back Settings cannot display what the engine actually has in force. Pin: `SettingsContentTest` **17 tests** | V2-AUDIT-4 — **done (V2-12)** |

## The two that gated a release

### A7 — permission application must fail closed (P0 candidate) — **OPEN**

**Status:** open after V2-12. The correction below is unchanged and still needs
owner sign-off (V2-AUDIT-0); no source change was made in the audit-fix batches.

The sequence in `WorkspaceManager.open()` is: create/attach → record the requested
mode → apply it (`runCatchingNonCancellation … onFailure { AppLog.w }`) →
`initAgent` → return the workspace. A rejected `permissions/mode` (or legacy
`permissions/skip`) therefore leaves the *engine's* effective policy at whatever
it was, while the app proceeds to configure and use the workspace.

Two amplifiers the review did not name:

1. **No read-back exists.** `open()` never asks the engine what mode is in force,
   and the client has no "get effective mode" call in this path. Even a
   successful HTTP 204 is accepted as proof.
2. **Reattach re-asserts the lie.** `reattachIfStale()` (`:282-295`) re-applies
   the mode recorded at open time. A failed apply is recorded as if it succeeded,
   so every later reattach repeats the wrong request and `open()` reports ready.

Proposed correction (V2-AUDIT-0, not implemented here):

- treat a failed policy application as `open()` failure, with a named result the
  UI can show ("engine rejected the permission policy");
- before `agent/init`, verify the effective mode if the engine exposes it;
  otherwise at minimum do not record success until the call returns 2xx;
- regression tests with a path-keyed `MockWebServer`: create 200 + mode 500 must
  fail the open and skip `agent/init`; the same for the legacy `skip` family;
- a failed reattach must not report "attached".

This is a behaviour change with a visible failure path, so it needs explicit
owner sign-off (V2-AUDIT-0 in the decision list below) — it is not a silent fix.

### A1 — one destination-selection path (P1, small) — **FIXED (V2-12)**

The bottom bar is the only layout that consults the veto
(`CrushShell.kt:188-192`); the rail and extended rail call the callback purely
for its side effect. The result on a rail-width device is a toast followed by a
navigation that does not change what is shown, because the binding was not
reset. Nothing unsafe happens, but the same tap behaves differently by width and
the user cannot tell why.

Correction: extract `selectPrimary(destination): Boolean`, use it in all three
layouts, and test the callback's `true`/`false` outcomes against each layout's
test tag (the shell already exposes `shell-bottom-bar`; the rails need the same).

**Resolution (V2-12).** `CrushShell.selectPrimary` (`CrushShell.kt:182`) is now
the single decision path, called by the bottom bar (`:210`), rail (`:244`) and
extended rail (`:305`); a `false` veto blocks navigation in every layout, not
just the binding reset. `ShellNavigationTest` is **16 tests**, with 6 new cases
at 411/700/1000dp asserting veto and allowed per layout. The source reading above
is kept as of `225ce94`.

## The rest, in release order

| Batch | Items | Size | Decision needed | Status |
|---|---|---|---|---|
| **V2-AUDIT-0** | A7/A7b fail-closed permission application + read-back/recording tests | medium | owner sign-off (behaviour change) | **OPEN** — no source change |
| **V2-AUDIT-1** | A1 shared destination selection + 6-case test matrix | small | none | **DONE (V2-12)** — `ShellNavigationTest` 16 |
| **V2-AUDIT-2** | A3 "Export current file" + current-vs-historical note; A4 footer copy; A5 provider-qualified onboarding; A10 keep key draft on failure; A11 delete confirmation naming the provider | small each | copy/UX approval | **DONE (V2-12)** — `ReviewContentTest` 13, `ProjectsContentTest` 15, `OnboardingContentTest` 12, `SettingsContentTest` 17 |
| **V2-AUDIT-3** | A8 atomic reservation (or an explicit single-thread contract + assertion) | small | none if documented | **DONE (V2-12)** — atomic under `lock`; `EngineInstanceRegistryTest` 19 |
| **V2-AUDIT-4** | auto-approve confirmation dialog + effective-state display after apply | small | product decision | **DONE (V2-12)** — confirmation shipped (`SettingsContentTest` 17); effective-state display deferred to the A7 read-back |
| **Questions** | A12 option/free-text UI over the existing `answerQuestions` transport | large | roadmap milestone | **OPEN** |
| **Architecture** | A13 runtime disposal contract + stress test; A14 total process budget including legacy (measure PSS with 3 children) | medium | owner decision | **A14 done (V2-12)** — comments corrected; A13 and the 3-child PSS remain **OPEN** |
| **Design** | A2 project home as a destination; review readability; relative-time freshness (`ChatListContent.kt:62`, `ChatContent.kt:1428` snapshot `now`) | medium | owner decision | **OPEN** |

## What the review got right that was already recorded

- Diagnostics redaction gap: open row in `V2-0-signoff-2026-11-22.md` §3, now closed
  by `DiagnosticsReportTest`.
- Review's process-local history, latest-turn-only revert and current-file export:
  V2-5 scoped and documented these; the audit's complaint is about *labelling*,
  which A3 accepts.
- Questions cannot be answered: recorded in the V2-5 limits; the audit correctly
  promotes it from "known limitation" to a product gap.
- No measured frame baseline: carried in the V2-0 sign-off; `tools/perf-baseline.ps1`
  now captures cold-start/gfxinfo/PSS/engine-boot so the claim can become a
  number. The capture itself does not create a budget.
- Manual tablet/TalkBack coverage absent: already NOT PERFORMED in the ledger.

## What this verification adds

1. **A7b**: the failed apply is not just logged and forgotten — it is recorded and
   replayed by `reattachIfStale`. This raises A7's severity.
2. **A12 nuance**: `answerQuestions` already exists in the client; this is a UI +
   state milestone, not an engine-capability gap.
3. **A8 nuance**: the race is real in the code but unreachable today because the
   only caller is the main-thread `startEngine()`. The fix is cheap; the priority
   is lower than the audit implies.
4. **A1 nuance**: the rail bug is a consistency defect, not a run-safety defect.
   The wording "could navigate away from a running project" overstates it.
5. **A5 evidence**: the onboarding promise contradicts the provider-scoped key
   model that V2-6 itself shipped, so this is a copy regression, not a new idea.

Status after V2-12: items 3 and 4 (A8, A1) and 5 (A5) are fixed; item 2 (A12)
and item 1 (A7b) stay open. The analyses above are the original readings, not
post-fix rewrites.

## Owner decision list (append to the V2 ADR register)

| ID | Decision | Recommendation | Status |
|---|---|---|---|
| V2-AUDIT-0 | Fail closed when the engine rejects the permission policy | **accept** — a security promise the engine does not enforce is worse than a visible error | **OPEN** — awaiting owner sign-off |
| V2-AUDIT-1 | Single destination-selection function for bar/rail/extended rail | accept (no semantic change; makes the veto uniform) | **accepted; done (V2-12)** |
| V2-AUDIT-2 | Review export label + projects copy + onboarding claim + key-draft retention + delete confirmation | accept; all copy/state, no data migration | **accepted; done (V2-12)** |
| V2-AUDIT-3 | Atomic engine-slot reservation | accept; document the current single-thread contract either way | **accepted; done (V2-12)** |
| V2-AUDIT-4 | Confirmation before enabling auto-approve; show only confirmed effective state | accept as product safety control | **accepted; done (V2-12)** — confirmation shipped, effective-state display follows A7 |
| V2-AUDIT-5 | Question answering milestone | schedule after V2-AUDIT-0..4; needs its own scope | **OPEN** — question milestone not started |
| V2-AUDIT-6 | Runtime disposal + total engine budget | schedule as architecture work; measure 3-child PSS first | **OPEN** — A13 + PSS still to do |
| V2-AUDIT-7 | Project home as a destination | keep on the roadmap; do not reopen V2-5 without a design pass | **OPEN** — roadmap |

## Reproduction backlog (if a claim is disputed)

| Claim | How to reproduce |
|---|---|
| A1 | launch on a 600-839dp width (or resize a freeform window), start a project turn, tap Crush Chat: toast appears, route stays, selection stays on Projects |
| A7 | path-keyed fake engine returning 500 on `permissions/mode`; call `open()`; observe `initAgent` still invoked and `open()` returning a workspace |
| A8 | two threads calling `requestStart` behind a `CyclicBarrier` with one live other engine; both must not pass |
| A10 | Settings → Replace key → type → Save with the Keystore writer forced to fail; observe the editor already closed and the draft gone |
| A12 | trigger an ask-flow question; observe only Deny/Dismiss |

Status after V2-12: A1, A8 and A10 now have automated pins (`ShellNavigationTest`,
`EngineInstanceRegistryTest` — the `CyclicBarrier` case is the A8 procedure
above — and `SettingsContentTest`); these manual steps remain the replay if a
pin is disputed. A7/A7b and A12 are still open.

No credential value is read, written or printed by any of these procedures.
