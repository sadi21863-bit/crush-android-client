# V2-1 exit reconciliation (2026-11-22)

Status: **PROPOSAL — awaits the owner's decision.** Docs only; no source,
test or build change. Nothing here closes V2-1 by itself.

## 1. Why this record exists

`V2-1-design-foundation.md` records tasks 3-6 as **NOT PERFORMED** because
"they add screens and need authorization" (§1, §7, §10). That was correct on
2026-11-19: the shell, the chat states and the project surfaces did not exist
yet, so a prototype was the only way to see them.

Since then V2-2 to V2-6 shipped those surfaces **and verified them on the
phone**. The decisions the prototypes were meant to inform have already been
made, built and measured against hardware. Building throwaway prototype
screens now would answer no open question and would duplicate shipped UI.

So the honest options are:

- **(A, recommended)** close V2-1 for the phone scope, cite the shipped
  evidence, and carry the one genuinely unbuilt item (tablet/wide
  prototypes, task 4) under open decision **O3**;
- **(B)** authorize new prototype screens anyway, as a visual exercise.

This record supports (A) and states exactly what it would and would not
claim. The owner decides in §6.

## 2. Task-by-task disposition

Wording is from Rev 4.0 as recorded in `V2-1-design-foundation.md` §1.

| Task | Rev 4.0 wording | Disposition | Evidence |
|---|---|---|---|
| 1 | Colour, type, spacing, shape, elevation, motion, icon tokens | **DONE / APPROVED** (V2-A8) | `V2-1` §4; shipped in V2-2 (`docs/v2/V2-2-token-integration.md`) |
| 2 | Shared components and state variants | **DONE via V2-2** (state catalogue + component library, piloted on Diagnostics) | `V2-2` record; `docs/parity/2026-11-20-v2-2-tokens/` |
| 3 | Phone navigation prototypes (drawer / bottom bar / switcher) | **SUPERSEDED by the shipped shell** | V2-3: bottom bar below 600dp, compact rail 600-839dp, `navigatePrimary` back rule; 12 device captures `docs/parity/2026-11-20-v2-3-shell/` (`01`, `03`-`10`, `12`). The narrowed question — validate brief §4.6's breakpoints — was answered on hardware, including a landscape rail (`08`, `09`) that no prototype could have measured. Extended rail (>=840dp) remains untested for lack of a device |
| 4 | Tablet rail/sidebar and list-detail prototypes | **NOT PERFORMED — deferred under O3** | No device above 393dp is attached (V2-0 §6); O3 keeps tablet panes out until phone acceptance; brief §7 specifies, not validates, wide windows |
| 5 | Chat empty/populated/streaming/error prototypes | **SUPERSEDED by V2-4** | V2-4 shipped all four states plus sheets/picker and actions; 30-capture pass `docs/parity/2026-11-20-v2-4-chat/`; defects found on device and fixed: F-L user-prose contrast, F-M empty tool-only bubble, F-N 2.0x chip clipping. Limits recorded there (frame timing, reduced motion, density, wide) |
| 6 | Project home, project chat, Review, file-change prototypes | **SUPERSEDED by V2-5** (plus V2-6 for Settings/Onboarding) | `docs/parity/2026-11-20-v2-5-code/`: `35` project list, `36` project home, `37` sessions, `38` project-bound chat; `57` populated Review; `58`-`60`/`64` byte-exact export; `66`-`67` confirmed revert; `80`-`94` two-project switching and changed-`crushrc` re-gate. Settings/Onboarding: `docs/parity/2026-11-21-v2-6-settings/` |
| 7 | Theme, density, accessibility behavior | **DONE (spec)**, with the recorded gaps | `V2-1` §8; V2-2 limit rows: reduced motion, density and font-scale device rows NOT PERFORMED |
| 8 | Validate flows against current app behavior | **PARTIAL -> upgraded by the later packages** | Flows actually walked on device: tab switch + system Back (V2-3 `11`, `12`); sheet de-duplication (`02`); IME (`10`, and V2-5 `40`, `51` after the failure `39`); project open / new session / list reload (V2-5 `36`-`38`, `42`, `45`); Settings -> provider -> relaunch route (V2-6 `17`-`19`); permission toggle write/restore (`15`, `16`); Finding 13 (`20`); Retry after an owner-approved network cut (V2-4 record); O11 loading rows (`docs/parity/2026-11-22-o11-session-loading/`). Still not walked: tablet/wide, Onboarding key entry/save/delete, reduced motion |

Task 8 is the only row whose *nature* changed rather than its status: it began
as "no walkthrough exists" and is now "walkthrough exists for every phone flow
that has a device dependency, with named gaps".

## 3. What a V2-1 closure still cannot claim

Closing V2-1 (option A) would assert the **phone** design foundation is
settled. It would not claim:

- tablet/foldable layout (task 4) — O3, no hardware;
- extended rail >=840dp (task 3's widest branch);
- API 30+ behaviour, or any runtime above API 29 (V2-0 device limits);
- reduced motion, density and 2.0x device rows that the package records mark
  NOT PERFORMED;
- Onboarding key entry/save/delete on device (owner credential only);
- that a prototype was rendered — option A builds none. The evidence is the
  shipped screens, which is a stronger class of evidence than a prototype.

## 4. Why not build the prototypes anyway

- The navigation question was **narrowed by the owner** (status doc §5.10
  trim 1): validate brief §4.6's breakpoints, not choose a pattern. V2-3 did
  exactly that on hardware, including orientation and the IME.
- The chat and project surfaces were **designed and then corrected by device
  evidence** (F-L/F-M/F-N, Finding 13, the list-state fix, the IME failure
  `39`). A prototype rendered before those corrections would have encoded
  states the shipped app no longer has.
- Brief §4.6 forbids deciding by intuition. It does not require a prototype
  once the decision has been made and verified; that reading would demand a
  prototype of the prototype for every later package.
- Every prototype screen costs maintenance and widens the debug surface.

## 5. Evidence index

| Claim | Path |
|---|---|
| Tokens and state catalogue | `docs/v2/V2-1-design-foundation.md` |
| Token/component implementation | `docs/v2/V2-2-token-integration.md`, `docs/parity/2026-11-20-v2-2-tokens/` |
| Shell + navigation on device | `docs/parity/2026-11-20-v2-3-shell/` (12 captures) |
| Chat states on device | `docs/parity/2026-11-20-v2-4-chat/`, `docs/v2/V2-4-chat-redesign.md` |
| Project/Review/file-change on device | `docs/parity/2026-11-20-v2-5-code/`, `docs/v2/V2-5-code-projects-review.md` |
| Settings/Onboarding on device | `docs/parity/2026-11-21-v2-6-settings/`, `docs/v2/V2-6-settings-onboarding.md` |
| Loading rows (O11) | `docs/parity/2026-11-22-o11-session-loading/` |
| Device limits | `docs/v2/V2-0-baseline-lock-2026-11-19.md` §6 |

## 6. Decision requested

1. **Approve option A?** Then V2-1 is recorded **COMPLETE (phone scope)**,
   task 4 is carried under O3, and the package tables in
   `docs/v2/README.md` and `docs/CRUSH-MOBILE-STATUS-AND-DECISIONS.md` §5.10
   move V2-1 from "partial" to "complete (phone)" with §3 as the stated
   limits.
2. **Or approve option B?** Then name which screens (tasks 3, 5, 6; task 4
   remains blocked) and they become an authorized package item with its own
   evidence bar.

No source change follows either way. Only the status records move.
