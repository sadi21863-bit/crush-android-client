# V2 redesign package (planning)

Status: **planning only. No V2 implementation is authorized.**

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
| V2-0 | in progress | reconciliation sign-off by owner |
| V2-1 | not started | V2-0 exit + owner approval of visual direction |
| V2-2 | not started | V2-1 approved spec |
| V2-3 | not started | V2-2 components |
| V2-4 | not started | V2-3 shell |
| V2-5 | not started | V2-3 shell |
| V2-6 | not started | V2-2 components |
| V2-7/8/9 | parked | separate owner approval |

## Rules that apply to every package

- Full gate: `:app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest :app:lintRelease` on JBR 25.
- Device claims need device/API/build/engine, steps, logs and screenshots.
- NOT PERFORMED stays NOT PERFORMED.
- No source change in V2-0. No preference, permission or persistence
  semantics change without an ADR.
