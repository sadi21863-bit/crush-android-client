# V2 gap audit — external review verified against source

Status: **verification record, 2026-11-23.** This file answers one question per
claim from the external product/architecture review: *is it true in this tree?*
It does not fix the confirmed defects; each one is assigned a severity, an exact
location and a proposed batch. The one exception is the diagnostics redaction
gap, which was closed by the V2-11 test batch and is marked accordingly; that
batch's three classes are focused-green (19 tests / 0 failures,
`v2-ledger-tests-1123g.log`), with the full four-task gate still pending.

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
  makes the mechanism clear.
- The external audit's own limitation stands: it was a source/documentation
  read, not a device test. This file shares that limit and does not upgrade it.

**Gate status:** Full four-task gate: green — 981 tests / 104 suites / 0
failures / 0 errors / 0 skipped; lint 0 errors / 1 pre-existing warning
(`v2-11-gate-2026-11-23.log`). The V2-11 focused run (19 tests, 0 failures) and
the full gate are the executed evidence behind the A9 closure.

Verdict vocabulary:

| Verdict | Meaning |
|---|---|
| **CONFIRMED** | the cited code does what the audit says |
| **CONFIRMED-NUANCE** | the mechanism is real, but the stated impact or severity is wrong |
| **CLOSED IN V2-11** | was true, now pinned by a new test |
| **DESIGN CHOICE** | implemented as described; whether it is wrong is an owner decision |
| **NOT CONFIRMED** | could not be reproduced from source |

## Verdict table

| # | Finding | Verdict | Evidence (file:line) | Batch |
|---|---|---|---|---|
| A1 | Rail ignores the Chat run-veto | **CONFIRMED-NUANCE** | `ui/navigation/CrushShell.kt:190` honours the Boolean; `:230` (rail) and `:294` (extended rail) discard it. But the veto only suppresses a binding reset (`MainActivity.kt:266-281`): the rail still lands on the project-bound chat and the Projects tab stays selected. No run is abandoned | V2-AUDIT-1 (small) |
| A2 | Project home is a modal sheet, not a destination | **DESIGN CHOICE** | `ui/screens/projects/ProjectsContent.kt:207`, `:365` | roadmap, owner decision |
| A3 | Review export hides current-vs-historical semantics | **CONFIRMED** | copy `ReviewContent.kt:107-113`; per-card export `:285`; label `"Export a copy"` `:351`; source doc `:339-342` says the copy is byte-exact *as it sits on disk now* | V2-AUDIT-2 (copy only) |
| A4 | Projects destination explains itself twice | **CONFIRMED** | `ProjectsContent.kt:119-120` and `:190-191` | V2-AUDIT-2 |
| A5 | Onboarding promises "70+ models from a single API key" | **CONFIRMED** | `ui/screens/onboarding/OnboardingContent.kt:112`; provider is selected at `:122-148`, and Settings already scopes keys per provider | V2-AUDIT-2 |
| A6 | `chat` route + `codeActive` jointly decide the selected tab | **CONFIRMED** | `CrushShell.kt:160-170`, `MainActivity.kt:261-265` | monitor; no rewrite |
| A7 | Permission-policy application failure is swallowed | **CONFIRMED** | `data/crush/WorkspaceManager.kt:234-235` (`skipPermissions`), `:237-243` (`setPermissionMode`): both log and continue; `:255` then runs `agent/init`; `open()` returns success | **V2-AUDIT-0** |
| A7b | A failed apply is *remembered as applied* on reattach | **CONFIRMED (audit missed)** | `WorkspaceManager.kt:282-295` re-applies the recorded `yoloMode`/wire mode; the recorded value is the request, never a read-back | **V2-AUDIT-0** |
| A8 | Engine budget check and reservation are not atomic | **CONFIRMED-NUANCE** | `engine/EngineInstanceRegistry.kt:128-144` is check-then-act on a `ConcurrentHashMap` (`:85`); `Grain.STARTING` is set *after* the check and is not counted (`:199` counts live children only). Currently single-caller on the main thread (`ChatViewModel.kt:607` from `startEngine`), so the race is structural, not observed | V2-AUDIT-3 |
| A9 | Diagnostics redaction/tail has no tests | **CLOSED IN V2-11** | new `app/src/test/.../diagnostics/DiagnosticsReportTest.kt` (11 tests: block order, key masking in log tail/crash/notes, truncation marker, null-vs-false probe, empty crash section, `parseUncaughtFromLog` terminator/last-crash/mid-write/no-crash); focused run 11/11 green; the terminator test found and drove the `matches` → `containsMatchIn` fix. Screen-level, clipboard and share-path remain NOT PERFORMED | closed |
| A10 | A failed Settings key save discards the draft | **CONFIRMED** | `ui/screens/settings/SettingsContent.kt:267-269` closes the editor and clears `newKey` before the async save reports; Onboarding already keeps a `saving` state (`OnboardingContent.kt:41-42`) | V2-AUDIT-2 |
| A11 | Key deletion has no confirmation | **CONFIRMED** | `SettingsContent.kt:254-259` calls `onClearKey` directly; project removal does confirm (`ProjectsContent.kt:228-248`) | V2-AUDIT-2 |
| A12 | The app cannot answer agent questions | **CONFIRMED-NUANCE** | dialog `ui/screens/chat/ChatContent.kt:423-460` is honest about it; the raw payload is preserved (`ChatViewModel.kt:325-329`); the transport already exists: `data/crush/CrushApi.kt:477` `answerQuestions`, `:481` `cancelQuestions`. Only the choice UI + resolution state are missing | roadmap (large) |
| A13 | Registry retains every runtime for the process lifetime | **CONFIRMED** | `EngineInstanceRegistry.kt:85` `ConcurrentHashMap`; `stop()` (`:163-180`, `stopRuntime` at `:175`) stops the child but retains the registry entry; there is no removal API in the public surface (`:101-197`) | architecture decision |
| A14 | Budget comments no longer match the Chat/Code split | **CONFIRMED (doc drift)** | `EngineInstanceRegistry.kt:43-48` and `ChatViewModel.kt:601-603` still say "chat + one project"; the drift is two non-legacy projects; chat now runs on legacy | architecture decision + doc fix |

## The two that should gate a release

### A7 — permission application must fail closed (P0 candidate)

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

### A1 — one destination-selection path (P1, small)

The bottom bar is the only layout that consults the veto
(`CrushShell.kt:188-192`); the rail and extended rail call the callback purely
for its side effect. The result on a rail-width device is a toast followed by a
navigation that does not change what is shown, because the binding was not
reset. Nothing unsafe happens, but the same tap behaves differently by width and
the user cannot tell why.

Correction: extract `selectPrimary(destination): Boolean`, use it in all three
layouts, and test the callback's `true`/`false` outcomes against each layout's
test tag (the shell already exposes `shell-bottom-bar`; the rails need the same).

## The rest, in release order

| Batch | Items | Size | Decision needed |
|---|---|---|---|
| **V2-AUDIT-0** | A7/A7b fail-closed permission application + read-back/recording tests | medium | owner sign-off (behaviour change) |
| **V2-AUDIT-1** | A1 shared destination selection + 6-case test matrix | small | none |
| **V2-AUDIT-2** | A3 "Export current file" + current/historical badge; A4 footer copy; A5 provider-qualified onboarding; A10 keep key draft on failure; A11 delete confirmation naming the provider | small each | copy/UX approval |
| **V2-AUDIT-3** | A8 atomic reservation (or an explicit single-thread contract + assertion) | small | none if documented |
| **V2-AUDIT-4** | auto-approve confirmation dialog + effective-state display after apply | small | product decision |
| **Questions** | A12 option/free-text UI over the existing `answerQuestions` transport | large | roadmap milestone |
| **Architecture** | A13 runtime disposal contract + stress test; A14 total process budget including legacy (measure PSS with 3 children) | medium | owner decision |
| **Design** | A2 project home as a destination; review readability; relative-time freshness (`ChatListContent.kt:62`, `ChatContent.kt:1428` snapshot `now`) | medium | owner decision |

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

## Owner decision list (append to the V2 ADR register)

| ID | Decision | Recommendation |
|---|---|---|
| V2-AUDIT-0 | Fail closed when the engine rejects the permission policy | **accept** — a security promise the engine does not enforce is worse than a visible error |
| V2-AUDIT-1 | Single destination-selection function for bar/rail/extended rail | accept (no semantic change; makes the veto uniform) |
| V2-AUDIT-2 | Review export label + projects copy + onboarding claim + key-draft retention + delete confirmation | accept; all copy/state, no data migration |
| V2-AUDIT-3 | Atomic engine-slot reservation | accept; document the current single-thread contract either way |
| V2-AUDIT-4 | Confirmation before enabling auto-approve; show only confirmed effective state | accept as product safety control |
| V2-AUDIT-5 | Question answering milestone | schedule after V2-AUDIT-0..4; needs its own scope |
| V2-AUDIT-6 | Runtime disposal + total engine budget | schedule as architecture work; measure 3-child PSS first |
| V2-AUDIT-7 | Project home as a destination | keep on the roadmap; do not reopen V2-5 without a design pass |

## Reproduction backlog (if a claim is disputed)

| Claim | How to reproduce |
|---|---|
| A1 | launch on a 600-839dp width (or resize a freeform window), start a project turn, tap Crush Chat: toast appears, route stays, selection stays on Projects |
| A7 | path-keyed fake engine returning 500 on `permissions/mode`; call `open()`; observe `initAgent` still invoked and `open()` returning a workspace |
| A8 | two threads calling `requestStart` behind a `CyclicBarrier` with one live other engine; both must not pass |
| A10 | Settings → Replace key → type → Save with the Keystore writer forced to fail; observe the editor already closed and the draft gone |
| A12 | trigger an ask-flow question; observe only Deny/Dismiss |

No credential value is read, written or printed by any of these procedures.
