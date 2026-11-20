# V2 redesign package (planning and implementation)

Status: **V2 implementation is authorized.** V2-1 direction is approved
(V2-A8); V2-2, V2-3 and V2-4 are complete. See the package table below for
per-package evidence and limits.

Authority (owner, 2026-11-19): Revision 4.0 is the forward roadmap and
design direction. The November 14 Rev 3.1 brief remains authoritative for
the UI state contract (§5), test registry (§6), composer gates (§5.5.1) and
parity evidence standard (§8.5). See
`../CRUSH-MOBILE-STATUS-AND-DECISIONS.md` §5.10 for the review record and
the owner's trims.

## Files

| File | Purpose |
|---|---|
| `V2-0-baseline-lock-2026-11-19.md` | locked SHA/toolchain/source inventory, parity ledger re-read, device limits, research log |
| `V2-0-reconciliation.md` | line-by-line reconciliation of the Rev 3.1 brief against the locked tree (task 4) |
| `ADR-REGISTER.md` | accepted, open and parked V2 decisions |
| `V2-1-design-foundation.md` | **approved** colour/type/spacing/shape/dimension/motion tokens (graphite accent, 15sp body, platform monospace), measured against the shipped theme; phone screen-state catalogue; navigation decision sheet; Q1-Q4 answered 2026-11-19 (V2-A8) |
| `V2-2-token-integration.md` | V2-2 evidence record: theme layer, semantic status roles, type reclassification counts, component pilot (Diagnostics), test list, gate status and honest limits |
| `V2-3-app-shell.md` | V2-3 adaptive shell record: three primary destinations, 600/840dp breakpoints, `navigatePrimary` back-stack rule, sheet de-duplication, test list, device evidence and honest limits |
| `V2-4-chat-redesign.md` | V2-4 chat record: scope/trims, F-B/F-C/F-E/F-J decisions, code map, test plan, evidence (complete) |
| `V2-5-code-projects-review.md` | V2-5 Code/Projects/Review record: V2-A9 Explore deferral, Review scoping, Projects current marker, provenance, evidence (in progress) |
| `V2-5-chat-code-ia-plan.md` | Corrected Chat/Code IA from the owner notebook: Crush Chat on legacy, Crush Code project sessions, list-first (V2-A11/A12) |
| `V2-5-chat-code-split-research.md` | Source-plan research for the Chat/Code split, drift list and legacy-history consequence |
| `../parity/2026-11-19-v2-0-baseline/MANIFEST.md` | fresh task-6 phone capture set: device/build, 15 captures, findings F-A to F-G, corrections C1 to C3 |

## Scope

- Active: V2-0 to V2-6, as trimmed in §5.10.
- Parked: V2-7 deterministic intelligent UI, V2-8 structured model UI,
  V2-9 release/rollout.
- Code stays project-bound; three primary destinations: Chat, Projects,
  Settings. Review and Diagnostics are pushed routes.
- Deferred (not reopened): pinned message/follow, cost/context display, new
  density/navigation/response-rendering preferences, release packaging.

## Package status

| Package | Status | Entry condition |
|---|---|---|
| V2-0 | in progress | reconciliation sign-off by owner; task 6 evidence landed |
| V2-1 | partial (direction approved) | token spec, state catalogue and a11y spec approved 2026-11-19 (V2-A8); prototype/flow tasks 3-6 still NOT PERFORMED, so the V2-1 exit criteria are not met |
| V2-2 | complete (exit criteria, 2026-11-20) | tokens in `ui/theme/`, component set piloted on Diagnostics, 871 tests green, lint 0 errors, full gate passed on attempts 2-4 (attempt 1 failed on a test-fixture typo), CI `44618219274` green; device evidence in `docs/parity/2026-11-20-v2-2-tokens/` (two defects found and fixed: card hairline, dark navigation bar). Partial task items: no screenshot-test harness; font-scale/reduced-motion/density NOT PERFORMED. F-J selection affordance is carried to V2-4 |
| V2-3 | complete (2026-11-20) | adaptive shell: bottom bar `<600dp`, rail `600-839dp`, extended rail `>=840dp` (extended rail not on hardware), single `navigatePrimary` rule, primary-only chrome; 885 tests green, lint 0 errors, CI run `44669754612` green; device pass in `docs/parity/2026-11-20-v2-3-shell/` (12 captures: bar, sheet, Back, IME, landscape rail, rotation). Limits: extended rail, API 30+, wide/tablet and instrumented tests NOT PERFORMED |
| V2-4 | complete (2026-11-20) | chat empty/transcript/composer/sheets/picker redesign plus F-B/F-C/F-E/F-J; tasks 4 and 7-cost/context trimmed by V2-A4; final gate 899 tests / 0 failures, lint 0 errors / 1 pre-existing warning; 30-capture device pass found and fixed F-L (user-prose contrast), F-M (empty tool-only bubble) and F-N (2.0x chip clipping); Retry verified after an owner-approved network cut; 2.0x font scale checked; CI `44734151566` green. Limits: frame timing, reduced motion, density, wide/tablet, provider switch and model loading/not-in-list device states NOT PERFORMED |
| V2-5 | in progress, device pass skipped (2026-11-21) | corrected IA V2-A11/A12: Crush Chat on legacy with deny-all policy, Crush Code project list → project home → project sessions, list-first tabs, Code-tab shell selection; Project-scoped Review, not-measured cards, IME bar; final gate 8 green (917 tests, 0 failures; lint 0 errors / 1 pre-existing warning); CI `44835596927` green; user-visible device pass NOT PERFORMED by owner choice |
| V2-6 | not started | V2-2 components |
| V2-7/8/9 | parked | separate owner approval |

## Rules that apply to every package

- Full gate: `:app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest :app:lintRelease` on JBR 25.
- Device claims need device/API/build/engine, steps, logs and screenshots.
- NOT PERFORMED stays NOT PERFORMED.
- No source change in V2-0. No preference, permission or persistence
  semantics change without an ADR.
