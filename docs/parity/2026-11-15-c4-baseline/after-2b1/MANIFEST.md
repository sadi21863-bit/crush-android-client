# C.4 post-batch parity walk — 2b.1 build — 2026-11-15

Design source: `crush-mobile-v2-design-brief-state-contract-test-plan-2026-11-14.md`
Rev 3.1, Appendix C.4. This is the **after** half of the 2b.1 parity pair.
The before half is `../MANIFEST.md`; this record does not modify it.

## Device and build under test

| Fact | Value |
|---|---|
| Device | Xiaomi Redmi Note 7 Pro (`violet`) |
| Android API | 29 |
| ABI | arm64-v8a |
| Display | 1080x2340, density 440 |
| App package / version | `com.crush.mobile` 1.0.0 (debuggable) |
| APK built | 2026-11-15 17:15 host time (post-review four-task gate) |
| APK installed | 2026-11-15 20:54:58 host time (post-review build) |
| Continuation walk | 2026-11-16 18:54-19:30 host time (flows 2, 22 restore, 16; flow 4 attempt) |
| Source | 2b.1 working tree on HEAD `6c21ff0` (uncommitted) |
| Engine | Crush v0.100.0, contracts 0.99.0 |
| Capture host | `aditya` Windows host; device clock drifts, so host file times are canonical |

Protocol: a fresh full-resolution screenshot was taken before every tap
sequence. Sheet scrolling, sheet Confirm/Dispatch/Discard, and app-lock
credentials remain owner-only. Screenshots live beside this file.

Pixel comparisons exclude the status and navigation bars (changing clock and
notification icons).

## Set A — 10 states

| # | State | After evidence | Status | Note |
|---:|---|---|---|---|
| 1 | Empty chat | `a01-after-plus.png` | PASS | Content region (y=100..2150) is **pixel-identical** to `../a01-empty-chat.png`: 0 changed pixels. |
| 2 | Populated transcript | `a02-after-project-top.png`, `a02-after-project-scroll.png` | PARTIAL | Same screen class and component styling (tool rows, sub-agent card, bubbles, actions row); the persisted session grew and the captured scroll position differs, so a pixel diff against the baseline is not meaningful. |
| 3 | Streaming + Stop | `a03-after-streaming.png`, `f03-streaming-3000.png` | PASS | Stop button replaces Send, input clears, "thinking..." placeholder renders. |
| 4 | Permission dialog | `a04-after-permission-dialog.png` | PASS | `write` request, path, and all three decisions at 48dp. |
| 5 | Question dialog | `a05-after-question-dialog.png` | PASS | Title and release copy render; the two release buttons ("Dismiss", "Deny") are the pinned pre-existing shape. |
| 6 | Session sheet | `a06-after-project-session-sheet.png` | PASS | Layout matches the baseline; row counts/cost differ because the project session grew (61 msg / $0.02 / 5h vs 36 / $0.01 / 10h). |
| 7 | Model sheet | `a07-after-project-model-sheet.png` | PASS | Sheet content and selected row match the baseline exactly. |
| 8 | Handoff consent sheet | `a08-after-handoff-sheet.png` | PASS | Prefill "OK", source session id, destination list. Confirm/Discard are owner-only and were not tapped. |
| 9 | Error card with Retry | `a09-after-error-card.png` | PASS | A denied `fetch` produced "This reply failed / no reply received" and an enabled Retry affordance. |
| 10 | File-changes panel | `a10-after-file-changes.png` | PASS | "1 file changed +1 -0", `parity-after-2b1.txt` marked new, "Review all turns" present. |

Set A result: **10/10 captured; 9 PASS, 1 PARTIAL** (a02 scroll/session
difference only). a01 is the one exact pixel-parity proof.

## Set B — 28 flows

| # | Flow | Status | Evidence / note |
|---:|---|---|---|
| 1 | Send typed | PASS | `f01-typed.png`, `a03-after-streaming.png`; input cleared, user row added, streaming began. |
| 2 | Send via IME | PASS (post-fix) | `f02-enter-sends.png` (Keyboard -> `Enter sends` selected), `f02-ime-typed.png` (draft in the composer, Gboard send arrow), `f02-ime-sent.png` (composer cleared, Stop shown), `f02-ime-done.png` (reply complete). `f02-ime-send.log.txt` records the persisted user row `IME-SEND-POSTFIX-2B1` and the started assistant turn. Observation: the engine-stopped `New chat` placeholder restored the last pool session (`IME-SEND-OK`) when the engine started; the IME turn landed there. |
| 3 | Stop confirmed | PASS | `f03-streaming-3000.png`, `f03-stopped-3000.png`, `f03-stopped-3000-plus5s.png`; result frozen (0 changed content pixels over +5 s), no snackbar. Re-exercised 2026-11-16 19:26 during the flow-4 attempt: the essay froze mid-paragraph and the Stop button returned to Send, no snackbar. |
| 4 | Stop unconfirmed | NOT PERFORMED | The non-confirming branch needs `crushApi.cancel` to fail. Attempted fault injection 2026-11-16 19:26: `run-as com.crush.mobile kill -9 $(pidof libcrush.so)` is denied (SELinux `runas_app` cannot signal the engine; toybox reports `kill: unknown pid`), and adb shell lacks CAP_KILL, so the engine could not be killed under the turn. A normal Stop then took the confirmed path (row 3). Unchanged from baseline. |
| 5 | Retry | PASS | `f05-after-retry.png`; Retry re-sent the preceding user prompt as a new turn and re-raised its permission dialog. |
| 6 | Permission — Allow once | PASS | `f06-after-allow-once.png`, `f06-after-write-bottom.png`; write executed, `WRITE-DONE`, file-changes panel appeared. |
| 7 | Permission — Always | PASS | `f07-after-always.png`; `parity-after-2b1-always.txt` contains exactly `always-grant`. |
| 8 | Permission — Deny | PASS | `f08-webfetch-dialog.png`, `f08-after-fetch-deny.png`; `fetch` row carries the warning marker and no content was fetched. |
| 9 | Permission — dismiss | PASS | `f09-fetch-dialog2.png`, `f09-after-fetch-dismiss.png`; Back dismissed the dialog, fetch stayed unexecuted. |
| 10 | Question release | PASS | `a05-after-question-dialog.png`, `f10-after-question-release.png`; Deny released the run and claimed no answer. |
| 11 | New chat | PASS (post-fix) | `f11-postfix-sheet.png`, `f11-postfix-new-chat.png`, `f11-postfix-minted.png`; project -> sheet New chat gave `New chat` / `Chat - no tools - engine stopped` with an empty transcript; typing started the pool engine and minted session `57d2acca` with no project bleed. Log: `f11-postfix.log.txt`. The pre-fix cross-binding is `f11-after-project-new-chat.png`. |
| 12 | Session open | PASS | Session sheet picks opened the selected session; project reopen restored `Reply Exactly OK`. |
| 13 | Session delete | NOT PERFORMED | Destructive, so not re-run; the baseline FAIL (immediate delete) and T2B1-03's pin remain the evidence. |
| 14 | Session empty/loading | NOT PERFORMED | `Loading chats...` is the `loading && sessions.isEmpty()` branch; it sits in a sub-second window between bootstrap and the first list. A cold start with the engine stopped instead rendered the explanatory `No chats yet` state immediately. Not captured; unchanged from baseline. |
| 15 | Model pick | PASS | `f15-after-model-pick.png`, `f15-model-restored.png`; chip changed to `deepseek-v4-pro` and back to `deepseek-v4.1-flash`. |
| 16 | Model error | PASS (post-fix) | `f16-offline-model-sheet-postfix.png`; airplane mode on, model sheet opened: `UnknownHostException: Unable to resolve host "opencode.ai": No address associated with hostname` rendered in red in-sheet, list not blank. Airplane mode restored off (`airplane_mode_on=0`). |
| 17 | Handoff chip | PASS | `a08-after-handoff-sheet.png`; sheet opened with prefill and destinations. |
| 18 | Handoff confirm - same destination | PASS (owner-performed) | `f18-handoff-sheet-open.png`, `f18-after-dispatch.png`, `f18-f20-handoff.log.txt`; the owner selected `Legacy workspace` (already consented), scrolled, and tapped Confirm: `handoff 4b301e82... APPROVED` -> `DISPATCHED; target session dd947578...`, no trust dialog. Source chat session `4475a1d3`; the dispatched target session is `dd947578`. |
| 19 | Handoff confirm — new destination | PASS (owner-performed) | `f19-restage-sheet-open.png`, `f19-consent-dialog.log.txt`, `f19-trace-row.txt`, `f19-after-allow.png`. Source session `c22de7a3` (TestProject3); the owner selected a different project, `Test project folder 2`, and tapped Confirm. The gate blocked with `handoff blocked: consent required (crushrc present, no consent recorded)` (22:23:24), which raised the trust dialog; the owner tapped Allow (22:23:27). The Allow recorded `trust_fingerprint=1712a7a6...` (SHA-256 of the 17-byte `crushrc`) and the dispatch completed to session `d59579a4...` (22:23:33). First attempt was void staging: the destination carried only `.crushrc`, which the gate does not probe, so it allowed without a dialog (`f19-first-attempt-*`); see Finding 10. |
| 20 | Handoff discard | PASS (owner-performed) | `f20-after-discard.png`, `f20-discard.log.txt`, `f20-trace-row.txt`. Staging: airplane mode on, owner selected `Test project folder 2` and tapped Confirm; the bind could not bootstrap offline, so the row stayed APPROVED (`handoff ac185118... APPROVED`, `bootstrap wait failed`), giving a resumable row. The owner then reopened the sheet, which rendered `Discard this handoff`, and tapped it: `handoff ac185118... DISCARDED: the trace row is ABANDONED`. Pulled row: `status=ABANDONED`, `targetCodeSessionId=null` (no dispatch); the late dispatch wait timed out at 20:50:10 with no DISPATCHED stamp. Note: on a fresh draft the sheet has no Discard by design (nothing written); see Finding 9. |
| 21 | Handoff resume | PASS | `f21-after-continue-in-code-plus5s.png`, `f21-after-continue-retry.png`; resume bar visible, Continue in Code jumped to the target `Handoff fingerprint` session and the bar disappeared. |
| 22 | Prewarm ON | PASS (post-fix) | `f22-prewarm-on3.png`; cold start logged `starting supervisor for instance 'chat' (prewarm on)` and `[chat] engine Running after 3068ms` (`f22-prewarm-on.log.txt`). Restored to Off 2026-11-16 18:54 (`f22-prewarm-restored-off.png`); the next cold start logged `prewarm off; supervisor deferred to first input for chat`, so the persisted setting is Off again. |
| 23 | Prewarm OFF | PASS (post-fix) | `f23-postfix-coldstart.png` + `f23-postfix-coldstart.log.txt`; cold start logged `prewarm off; supervisor deferred to first input` and no `libcrush.so`. First keystroke (`f23-postfix-first-keystroke.png`) started the engine, Running after 3420 ms. The pre-fix race is `f23-coldstart-prewarm-off.log.txt`. |
| 24 | Nav Chat -> Review -> back | PASS | `f24-after-review.png`, `f24-after-review-back.png`; Review opened (empty history in chat pool) and Back returned to the same chat. |
| 25 | Nav Chat -> Settings -> back | PASS | `f25-after-settings.png`, `f25-after-settings-back.png`; Back returned to the same chat and transcript. |
| 26 | Nav Chat -> Projects -> open project -> back | PASS | `f26-after-projects.png`, `f26-after-open-project2.png`, `f26-after-project-back.png`; Back exited to the launcher, the documented single-chat-entry invariant. |
| 27 | Notices | NOT PERFORMED | No stop/delete/handoff-failure snackbar was triggered in this walk. |
| 28 | Lock | PASS (owner-performed) | Cold start 2026-11-16 18:54:58 logged `resume gate=PROMPT coldStart=true` and `lock engaged; refusing to bootstrap`; the engine did not start. The owner entered the app-lock credential at 18:55:12 (`LockOverlay: unlocked`; `resume gate=OPEN ... freshUnlock=true`), then the chat bootstrapped on the first turn. The agent entered no credential. |

Set B result (post-fix): **24 PASS / 0 FAIL / 4 NOT PERFORMED / 0 BLOCKED**.
The original walk's 15 PASS / 2 FAIL counts are superseded by the post-review
re-walk of flows 2, 11, 16, 22, and 23. Open rows: 4, 13, 14, 27
(NOT PERFORMED).

## Findings

1. **Prewarm OFF still starts the engine on cold start (flow 23, pre-fix
   observation; fixed post-review).** Settings showed `Prewarm engine on
   launch = Off (MANUAL)` and the stored value survived, but a clean
   force-stop + relaunch logged `starting supervisor for instance 'chat'
   (prewarm on)` and the engine was Running before the first keystroke.
   Source: `ChatScreen.kt` collected `settings` with
   `initialValue = AppSettings()` and started the supervisor in
   `LaunchedEffect(activeInstanceKey)` using that first-frame `prewarm`. The
   post-review fix gates on the coherent host snapshot; the re-walk passed
   (row 23 above).
2. **`+` in a project can cross-bind the transcript (flow 11, pre-fix
   observation; fixed post-review).** Tapping `+` while `Reply Exactly OK`
   was bound switched the subtitle to `Chat - no tools - engine stopped` while
   the title and project transcript stayed; the session sheet then listed the
   project session under `Chats - conversation only`. The pre-fix
   `startChatSession()` fallback acted on the old `Workspaces` manager. The
   post-review fix binds the intent to the chat-pool instance; the re-walk
   passed (row 11 above).
3. **Always grants are workspace-persisted.** `write` and `bash` auto-ran in
   all three visited workspaces because earlier "Always" decisions survive in
   the project/workspace state. A clean Deny/dismiss prompt was obtained with
   `fetch`, which had never been granted. This is expected behaviour, not a
   defect; it is why flows 8-9 use `fetch`.
4. **App lock engages on cold start.** The first cold-start screenshot was
   0 bytes (FLAG_SECURE) and the log recorded `lock engaged; refusing to
   bootstrap`. On 2026-11-16 the owner entered the app-lock credential
   (`LockOverlay: unlocked`, `freshUnlock=true` at 18:55:12); the agent
   entered no credential. Flow 28 is recorded as owner-performed PASS.
5. **Retry after a denied tool creates a failed card.** The denied `fetch`
   turn ended as `This reply failed / no reply received` with Retry enabled;
   tapping Retry re-sent the prior user prompt and re-raised the permission
   dialog. This matches the baseline's flow-5 shape.
6. **External deletion is visible in the file-changes honesty line.** After
   `parity-after-2b1-denied.txt` was removed by the harness, the panel showed
   `1 file(s) not shown (unreadable, binary, or too large)`. That is the
   designed honesty surface reacting to the harness edit, not an app defect.
7. **Test side effects.** The walk created
   `Test project folder 2/parity-after-2b1.txt` and
   `.../parity-after-2b1-always.txt`; `.../parity-after-2b1-denied.txt` was
   created by the Always grant and removed by the harness. Test turns were
   appended to `Reply Exactly OK` and to the Legacy workspace's
   `Handoff fingerprint` session. No owner session was deleted.
8. **Cold-start placeholder restores the last session (observation, not a
   FAIL).** With the engine stopped, the chat surface shows `New chat` /
   `Engine starts when you begin typing`. The first typed turn started the
   engine and then restored the last pool session (`IME-SEND-OK`), moving the
   draft into that session rather than minting a fresh one. This is the
   existing resume-last-session behaviour, not the flow-11 `+` path, which
   the post-review fix binds explicitly. Recorded because the placeholder
   reads as a new chat until bootstrap finishes.
9. **Discard needs a written row (flow 20 staging note, not a defect).** A
   fresh handoff sheet (DRAFT prefill, nothing written yet) renders only
   Cancel + Confirm; `Discard this handoff` exists only when the sheet carries
   `existingDraft` or `approvedRow`. The owner's first attempt on a fresh
   sheet found no Discard, which matches the contract: there is nothing to
   abandon. The walk staged an APPROVED row by confirming to a project while
   offline (bind cannot bootstrap, dispatch fails, row stays resumable), then
   the reopened sheet offered Discard. Observation while staging: after the
   discard, the delayed dispatch-failure notice still reads "the approved plan
   stays resumable" although the row is already ABANDONED - copy-only, no
   state effect; pinned, not fixed.
10. **The trust gate does not probe a dotted `.crushrc` (pinned security
    finding).** `ProjectTrustService.CRUSHRC_FILE_NAME` is `"crushrc"` (no
    dot), so a folder whose only config is `.crushrc` gates as "no config"
    and is allowed. The pinned engine on the device (v0.100.0) executes
    `.crushrc` on attach: `TestProject3` carried only `.crushrc` (30 B,
    `echo dotted > .dotmarker`); after the app bound it at 22:18:37, the
    marker appeared (`.dotmarker`, 7 B, `dotted`) with no consent for that
    content, and the dispatched code session's tree walk independently
    flagged the side effect. The S1b probe established `crushrc` execution
    but did not test the dotted discovery path documented as priority 1.
    This is pre-existing S-02 scope, not a 2b.1 regression. Pinned for an
    owner decision - the gate must fingerprint every file the engine may
    execute, or the engine must be proven not to read `.crushrc`; not fixed
    here. Evidence: `f19-dotmarker.txt`, `f19-first-attempt.log.txt`.

## Post-review fix addendum (2026-11-15 late)

The owner rejected the partial checkpoint and authorized fixing the two FAILs
instead of pinning them.

- **Flow 23** now reads prewarm from the coherent host snapshot
  (`ChatHostInputs.prewarmAtComposition`) and gates the composition start on
  `snapshotReady`, so a persisted Off cannot be overwritten by the AUTO seed.
- **Flow 11** now binds the New chat intent to the chat-pool instance
  (`pendingNewChatKey`), consumes it at bootstrap, mints a fresh session, and
  clears the outgoing project transcript/session id before the boot.
- Targeted chat package: 27 suites / 256 tests, 0 failures.
- Full gate: 789 tests / 86 suites, 0 failures; lint 0 errors / 1 warning.
- **Device re-walk: PASS** on the post-review APK (installed 2026-11-15
  20:54:58).
  - Flow 23, composition: `prewarm off; supervisor deferred to first input
    for p077dc19e`; only `com.crush.mobile` in `ps`, no `libcrush.so`.
    First keystroke: `libcrush.so` started and
    `EngineSupervisor: [p077dc19e] engine Running after 3420ms`.
  - Flow 11: project `Handoff fingerprint mismatch audit` -> sheet New chat
    produced `New chat` / `Chat - no tools - engine stopped`, empty
    transcript; typing started `[chat]` (Running after 3277 ms) and logged
    `new chat: minting a fresh session on 'chat'`, then
    `current-session reported: 57d2acca`. No project transcript survived the
    switch.
  - Flow 22: Prewarm On cold start logged
    `starting supervisor for instance 'chat' (prewarm on)` and
    `[chat] engine Running after 3068ms` (`f22-prewarm-on.log.txt`). The
    setting was restored to Off (`f22-prewarm-restored-off.png`) and the next
    cold start logged `prewarm off; supervisor deferred to first input for
    chat`, so the persisted value is Off again.
  - Flow 2 (2026-11-16 19:03-19:16): Keyboard -> `Enter sends`; typing the
    draft and tapping Gboard's IME send arrow cleared the composer, started a
    turn, and persisted the user row `IME-SEND-POSTFIX-2B1`
    (`f02-ime-send.log.txt`). The reply completed.
  - Flow 16 (2026-11-16 19:28): airplane mode on; the model sheet rendered
    `UnknownHostException: Unable to resolve host "opencode.ai"` in red
    instead of a blank list (`f16-offline-model-sheet-postfix.png`). Airplane
    mode restored off.
  - Flow 28 (2026-11-16 18:54-18:55): cold start logged
    `lock engaged; refusing to bootstrap` with no engine start; the owner
    entered the app-lock credential at 18:55:12 (`LockOverlay: unlocked`,
    `resume gate=OPEN ... freshUnlock=true`). Owner-performed; the agent
    entered no credential.
  - Flow 18 (2026-11-16 20:24): the owner confirmed the already-consented
    `Legacy workspace`; the log shows `APPROVED` then `DISPATCHED; target
    session dd947578...`, no trust dialog (`f18-f20-handoff.log.txt`,
    `f18-after-dispatch.png`).
  - Flow 20 (2026-11-16 20:48-20:50): offline Confirm left an APPROVED row,
    the owner reopened the sheet and tapped `Discard this handoff`. The log
    shows `DISCARDED: the trace row is ABANDONED`; the pulled row is
    `ABANDONED` with `targetCodeSessionId=null` (`f20-discard.log.txt`,
    `f20-trace-row.txt`, `f20-after-discard.png`). The later dispatch timeout
    did not write a DISPATCHED stamp.
  - Flow 19 (2026-11-16 22:23): re-staged after the first attempt's file-name
    mistake. With `crushrc` (17 B) in `Test project folder 2` and an existing
    no-crushrc consent, the owner confirmed from session `c22de7a3`; the gate
    logged `handoff blocked: consent required (crushrc present, no consent
    recorded)` and raised the trust dialog. Allow at 22:23:27 recorded the
    file fingerprint and the dispatch completed to session `d59579a4`
    (`f19-consent-dialog.log.txt`, `f19-trace-row.txt`,
    `f19-after-allow.png`). The first attempt (21:50) dispatched without a
    dialog because the folder carried only `.crushrc`; that is recorded as
    void staging plus Finding 10 (`f19-first-attempt-*`).

## What this record does NOT claim

- It is not full pixel parity for Set A: only a01 is an exact pixel proof;
  a02 is PARTIAL because the persisted session and scroll position differ.
- It does not prove API 30+ behaviour, the AFA rationale, or wide/tablet
  layouts; the device is API 29 only.
- It does not close the not-reproduced rows (4, 13, 14, 27). Flow 13's
  baseline FAIL and its T2B1-03 pin remain the delete evidence. Flow 4's
  unconfirmed-stop branch could not be forced: `run-as` cannot signal
  `libcrush.so` under SELinux, so a plain Stop took the confirmed path.
  Flow 28 is owner-performed: the owner entered the lock credential; no
  credential was entered by the agent.
- Finding 10 is a pinned security gap, not a passing flow: the gate
  fingerprints `crushrc`, but the engine also executes a dotted `.crushrc`,
  so a dotted-only folder is trusted as no-config. It needs an owner decision
  (gate both names, or constrain the engine); 2b.1 leaves it unchanged.
- The pre-fix flow 23/11 findings are retained above as the before/after
  contrast; the post-review rows are the current result.
- This record and the Phase 2a + 2b.1 tree it covers are committed together
  (2026-11-16); the repository log carries the hash.
