# C.4 pre-batch parity baseline — 2026-11-15

Design source: `crush-mobile-v2-design-brief-state-contract-test-plan-2026-11-14.md`
Rev 3.1, Appendix C.4 ("Pre-batch baseline"). This manifest records the
**before** half of the 2b.1 parity pair only. The "after" walk against the
2b.1 build is a separate record: [`after-2b1/MANIFEST.md`](after-2b1/MANIFEST.md).
Nothing here is a 2b.1 parity claim.

## Device and build under test

| Fact | Value |
|---|---|
| Device | Xiaomi Redmi Note 7 Pro (`violet`) |
| Android API | 29 |
| ABI | arm64-v8a |
| Display | 1080×2340, density 440 |
| App package / version | `com.crush.mobile` 1.0.0 (debuggable) |
| APK install time | 2026-11-14 14:13 (host clock) |
| Source baseline | `bbf8ca69` (code-identical to HEAD `6c21ff0`; `af178f8..HEAD` is docs-only) |
| Engine | Crush v0.100.0, contracts 0.99.0 |
| Capture host | `aditya` Windows host; device clock drifts, so all timestamps below are host file times |

Protocol: a fresh full-resolution screenshot was taken before every tap
sequence. Sheet scrolling, sheet Confirm/Dispatch/Discard, and app-lock
credentials are owner-only; where those were required the row is recorded as
BLOCKED rather than performed by the agent.

Status legend: PASS / FAIL / PARTIAL / BLOCKED (owner-only step) /
NOT PERFORMED (not reproducible or not reached).

## Set A — 10 states

All files live beside this manifest. Status is PASS in the sense "captured from
the pre-2b.1 build"; visual comparison against the 2b.1 build is the later
"after" step.

| # | State | File | Host time | Status |
|---:|---|---|---|---|
| 1 | Empty chat | `a01-empty-chat.png` | 2026-11-15 06:01:22 | PASS |
| 2 | Populated transcript | `a02-populated-transcript.png` | 2026-11-15 00:45:43 | PASS |
| 3 | Streaming + Stop | `a03-streaming-stop.png` | 2026-11-15 00:51:26 | PASS |
| 4 | Permission dialog | `a04-permission-dialog.png` | 2026-11-15 01:04:26 | PASS |
| 5 | Question dialog | `a05-question-dialog.png` | 2026-11-15 05:56:41 | PASS |
| 6 | Session sheet | `a06-session-sheet.png` | 2026-11-15 00:49:05 | PASS |
| 7 | Model sheet | `a07-model-sheet.png` | 2026-11-15 00:49:32 | PASS |
| 8 | Handoff consent sheet | `a08-handoff-consent.png` | 2026-11-15 00:50:19 | PASS |
| 9 | Error card with Retry | `a09-error-card.png` | 2026-11-15 01:11:49 | PASS |
| 10 | File-changes panel | `a10-file-changes.png` | 2026-11-15 01:04:53 | PASS |

Pixel comparison for the later "after" pass must exclude the status and
navigation bars: the device clock changes between captures.

## Set B — 28 flows

| # | Flow | Status | Evidence / note |
|---:|---|---|---|
| 1 | Send typed | PASS | One user row, streaming began, input cleared |
| 2 | Send via IME | PASS | `f02-ime-send-before.png`, `f02-ime-send-after.png` |
| 3 | Stop confirmed | PASS | `f03-streaming-before-stop.png`, `f03-streaming-stopped.png`; result frozen at +1s and +7s; no `stopNotice` |
| 4 | Stop unconfirmed | NOT PERFORMED | A non-confirming stop was not reproducible on demand; the snackbar was not exercised |
| 5 | Retry | PASS | Prior user text re-sent as a new turn; failed pair remained visible |
| 6 | Permission — Allow once | PASS | Tool executed; dialog closed |
| 7 | Permission — Always | PASS | Tool executed; dialog closed |
| 8 | Permission — Deny | PASS | Tool did not execute; run continued |
| 9 | Permission — dismiss | PASS | Same observable as Deny |
| 10 | Question release | PASS | Run released; no answer claimed |
| 11 | New chat | PASS | FINDING: the first `+` tap calls `newThread()` before `Workspaces` is handed out, falls back to `bootstrap()` and RESUME an existing chat-pool session; the second tap minted fresh session `4475a1d3`. Recorded; not changed in 2b.1 |
| 12 | Session open | PASS | Transcript replaced; binding unchanged |
| 13 | Session delete | **FAIL** | Pre-existing defect: the trash button deletes immediately, `pendingDelete` in `SessionSheet` is unreachable. Log `deleteSession confirmed … f5aaada4-…`; evidence `f13-delete-no-confirmation.png`. **Do not fix in 2b.1**; T2B1-03 pins today's behaviour |
| 14 | Session empty/loading | NOT PERFORMED | The transient loading state was not captured; no reliable reproduction on the dev workspace |
| 15 | Model pick | PASS | Chip updated; selection persisted |
| 16 | Model error | PASS | `f16-offline-model-sheet.png`; error text rendered in-sheet, list not blank |
| 17 | Handoff chip | PASS | Sheet opened with prefill and destinations |
| 18 | Handoff confirm — same destination | BLOCKED | Sheet Confirm is owner-only; not tapped by the agent |
| 19 | Handoff confirm — new destination | BLOCKED | Sheet Confirm plus the trust prompt are owner-only |
| 20 | Handoff discard | BLOCKED | Sheet Discard is owner-only |
| 21 | Handoff resume | PARTIAL | DISPATCHED resume bar observed; "Continue in Code" not tapped (would leave Chat) |
| 22 | Prewarm ON | PASS | `starting supervisor … (prewarm on)` at composition |
| 23 | Prewarm OFF | NOT VERIFIED | Prewarm set Off (MANUAL), but the cold-start log still said `starting supervisor for instance 'chat' (prewarm on)`. Settings-load race versus a real defect is unresolved; clean re-test queued |
| 24 | Nav Chat → Review → back | NOT PERFORMED | Device locked before the walk; owner unlock required |
| 25 | Nav Chat → Settings → back | PASS | Back returned to the same chat |
| 26 | Nav Chat → Projects → open project → back | NOT PERFORMED | Device locked before the walk; owner unlock required |
| 27 | Notices | NOT PERFORMED | Requires a triggered stop/delete/handoff failure; none reproduced in this pass |
| 28 | Lock | BLOCKED | App-lock credentials are owner-only; not entered by the agent |

## Flow 13 baseline evidence

`f13-delete-no-confirmation.png` shows the session row gone after one trash tap
with no dialog. The log line recorded for the same tap was
`deleteSession confirmed … f5aaada4-…`. This is the pre-2b.1 behaviour that
T2B1-03 pins; fixing it is out of scope for the extraction.

## What this manifest does NOT claim

- It is not the 2b.1 "after" walk. All 10 states and all 28 flows must be
  re-walked against the 2b.1 build, and any mismatch blocks the batch.
- It does not prove API 30+ behaviour, AFA rationale, or wide/tablet layouts;
  the device is API 29 only.
- Rows 4, 14, 23, 27 are not passes in this baseline. The after-2b1 re-walk
  closed 18, 19, 20, and 28 owner-performed; see
  [`after-2b1/MANIFEST.md`](after-2b1/MANIFEST.md).
