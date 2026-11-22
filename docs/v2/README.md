# V2 redesign package (planning and implementation)

Status: **V2 implementation is authorized.** V2-0 and V2-2 through V2-6 are
complete on the phone; V2-1 is complete for the phone scope (V2-A8 direction,
V2-A13 closure: tablet prototypes carried under O3). V2-9 is complete for its
hardening scope (V2-A15) and Gate 7 stays deferred. V2-7 is in progress and
V2-8 is an opened experiment (V2-A16/A17). See the package table below for
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
| `V2-0-signoff-2026-11-22.md` | **accepted (V2-A14)**: V2-0 complete; reconciles every old §9 gap against what shipped and names the carry-over rows (perf proof, Diagnostics/About pinned version, report test, screenshot/instrumentation suite, T6-01/T6-02, onboarding device row) |
| `ADR-REGISTER.md` | accepted, open and parked V2 decisions |
| `V2-1-design-foundation.md` | **approved** colour/type/spacing/shape/dimension/motion tokens (graphite accent, 15sp body, platform monospace), measured against the shipped theme; phone screen-state catalogue; navigation decision sheet; Q1-Q4 answered 2026-11-19 (V2-A8) |
| `V2-1-exit-reconciliation-2026-11-22.md` | **accepted (V2-A13)**: tasks 3/5/6 closed as superseded by the shipped V2-3/V2-4/V2-5 evidence (no prototype screens built); task 4 (tablet) carried under O3; §3 lists what the closure cannot claim |
| `V2-2-token-integration.md` | V2-2 evidence record: theme layer, semantic status roles, type reclassification counts, component pilot (Diagnostics), test list, gate status and honest limits |
| `V2-3-app-shell.md` | V2-3 adaptive shell record: three primary destinations, 600/840dp breakpoints, `navigatePrimary` back-stack rule, sheet de-duplication, test list, device evidence and honest limits |
| `V2-4-chat-redesign.md` | V2-4 chat record: scope/trims, F-B/F-C/F-E/F-J decisions, code map, test plan, evidence (complete) |
| `V2-5-code-projects-review.md` | V2-5 Code/Projects/Review record: V2-A9 Explore deferral, Review scoping, Projects current marker, provenance, evidence (complete on phone) |
| `V2-5-chat-code-ia-plan.md` | Corrected Chat/Code IA from the owner notebook: Crush Chat on legacy, Crush Code project sessions, list-first (V2-A11/A12) |
| `V2-5-chat-code-split-research.md` | Source-plan research for the Chat/Code split, drift list and legacy-history consequence |
| `V2-6-settings-onboarding.md` | Settings/Onboarding redesign record: V2-A4 trim, brief section order, Finding 13 copy, honest onboarding copy, evidence (complete on phone) |
| `V2-9-release-hardening.md` | **active (hardening only)**: ASK default recorded (already closed in code), real-Keystore instrumented coverage, key-rotation checklist + local scanner; Gate 7 stays deferred |
| `V2-7-intelligent-ui.md` | **in progress**: collapsed reasoning + on/off setting, unknown-part text fallback; host-owned closed `ui_block/v1` registry planned; no external UI framework |
| `V2-8-structured-ui-experiment.md` | **open (research complete, not built)**: debug-only read-only comparison of prompt-only blocks, A2UI alpha02 (blocked by default) and a local json-render-style adapter; exit criteria and hard boundaries |
| `ui-block-v1.md` | the closed `ui_block/v1` contract: six block types, limits, strict whole-document rejection, security posture, rendering map, producer status |
| `V2-7-8-codebase-inventory-2026-11-22.md` | read-only inventory of the assistant pipeline, renderers, metadata, security boundary and tests that V2-7/V2-8 must work with |
| `V2-7-8-web-research-2026-11-22.md` | external research: A2UI, json-render, AG-UI, MCP Apps, structured outputs, native Compose examples, security; verdicts and source table |
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
| V2-0 | **complete** | baseline ledger accepted by the owner 2026-11-22 (V2-A14): reconciliation gaps reconciled in `V2-0-signoff-2026-11-22.md`; carry-over rows (perf proof, Diagnostics/About pinned version + report test, screenshot/instrumentation suite, T6-01/T6-02, onboarding device row) stay open without being exit blockers |
| V2-1 | **complete (phone scope)** | token spec, state catalogue and a11y spec approved 2026-11-19 (V2-A8); closed 2026-11-22 (V2-A13) — tasks 3/5/6 superseded by the shipped, device-verified V2-3/V2-4/V2-5 screens (no prototypes built), task 4 (tablet) carried under O3. Limits: `V2-1-exit-reconciliation-2026-11-22.md` §3 |
| V2-2 | complete (exit criteria, 2026-11-20) | tokens in `ui/theme/`, component set piloted on Diagnostics, 871 tests green, lint 0 errors, full gate passed on attempts 2-4 (attempt 1 failed on a test-fixture typo), CI `44618219274` green; device evidence in `docs/parity/2026-11-20-v2-2-tokens/` (two defects found and fixed: card hairline, dark navigation bar). Partial task items: no screenshot-test harness; font-scale/reduced-motion/density NOT PERFORMED. F-J selection affordance is carried to V2-4 |
| V2-3 | complete (2026-11-20) | adaptive shell: bottom bar `<600dp`, rail `600-839dp`, extended rail `>=840dp` (extended rail not on hardware), single `navigatePrimary` rule, primary-only chrome; 885 tests green, lint 0 errors, CI run `44669754612` green; device pass in `docs/parity/2026-11-20-v2-3-shell/` (12 captures: bar, sheet, Back, IME, landscape rail, rotation). Limits: extended rail, API 30+, wide/tablet and instrumented tests NOT PERFORMED |
| V2-4 | complete (2026-11-20) | chat empty/transcript/composer/sheets/picker redesign plus F-B/F-C/F-E/F-J; tasks 4 and 7-cost/context trimmed by V2-A4; final gate 899 tests / 0 failures, lint 0 errors / 1 pre-existing warning; 30-capture device pass found and fixed F-L (user-prose contrast), F-M (empty tool-only bubble) and F-N (2.0x chip clipping); Retry verified after an owner-approved network cut; 2.0x font scale checked; CI `44734151566` green. Limits: frame timing, reduced motion, density, wide/tablet, provider switch and model loading/not-in-list device states NOT PERFORMED |
| V2-5 | complete (phone, 2026-11-21) | corrected IA V2-A11/A12: Crush Chat on legacy with deny-all policy, Crush Code project list → project home → project sessions, list-first tabs, Code-tab shell selection; Project-scoped Review, not-measured cards, IME bar; gate 9 green (917 tests, 0 failures; lint 0 errors / 1 pre-existing warning). Device pass completed 2026-11-21: IA + project-bound chat + IME (30-38, 40), populated Review (57), byte-exact export (58-60, 64), confirmed revert (66-67), two-project switching (80-94), changed-`crushrc` re-gate (72-74), legacy deny-all spawn line. Tablet/wide, 2.0x font, the accept branch, a not-measured card, the Chat-switch veto and a running session row are the listed limits |
| V2-6 | complete (phone, 2026-11-21) | Settings/Onboarding redesign on the V2-2 components; brief §4.7.4 section order; Finding 13 copy fixed; no new preferences (V2-A4/O5); provider round-trip and relaunch route verified on device (`17`-`21`), ASK restored; 2.0x font scale checked and the provider-badge squeeze fixed (`24`-`27`). Gate `v2-6-gate-1.log`: 919 tests / 96 classes / 0 failures, lint 0 errors / 1 pre-existing warning; the post-fix re-gate `v2-6-gate-4.log` is green. Limits: Onboarding device row, tablet/wide, Onboarding at 2.0x, and key save/delete on device NOT PERFORMED (see manifest); injected-tap harness quirk recorded |
| V2-7 | **complete (foundation)** | opened 2026-11-22 (V2-A16); closed same day (V2-A18): 7.1 reasoning Collapsed/Off, 7.2 unknown-part fallback, 7.3 `ui_block/v1` validator/renderer. No external UI framework. Evidence: `docs/parity/2026-11-22-v2-7-reasoning/`, `docs/parity/2026-11-22-v2-7-blocks/` |
| V2-8 | **active (experiment)** | opened 2026-11-22 (V2-A17): debug-only, read-only experiment; candidate A paste lab built and device-checked, model-run measurements pending. A2UI reference-only unless alpha + compileSdk are explicitly accepted; adoption needs every exit criterion. Evidence: `docs/parity/2026-11-22-v2-8-lab/` |
| V2-9 | **complete (hardening, 2026-11-22)** | ASK default recorded (already closed in code); `SecureKeyStoreInstrumentedTest` OK (3 tests) on `bbf8ca69`; `tools/check-secrets.ps1` PASS over 674 tracked files; gate 924 tests / 0 failures, lint 0 errors / 1 warning. Gate 7 submission/signing/listing remains deferred. Evidence: `docs/parity/2026-11-22-v2-9-hardening/` |

## Rules that apply to every package

- Full gate: `:app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest :app:lintRelease` on JBR 25.
- Device claims need device/API/build/engine, steps, logs and screenshots.
- NOT PERFORMED stays NOT PERFORMED.
- No source change in V2-0. No preference, permission or persistence
  semantics change without an ADR.
