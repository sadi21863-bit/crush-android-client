# MANIFEST — V2-16 Phase 0 evidence (Checkpoint 1)

Date: 2026-11-26. Device: Redmi Note 7 Pro, **API 29**, arm64-v8a, serial
`bbf8ca69`. App: debug `com.crush.mobile` 1.0.0 from the V2-16 planning tree.
Engine: `v0.100.0` (`build_id 2my2izixtou8`). Instance: project `pd871eee8`
for `Test project folder 2`; session `0e405d83-3033-4600-9222-88cd64e4320e`.

No credentials are stored in this directory. The provider key that the engine
returned in a workspaces response was printed once in a tool response and is
**not** persisted here; it must be rotated (see the engine-contract report §5).

| File | What it shows |
|---|---|
| `v2-16-p0-sse.log` | First SSE listener: a `client_session` event for the app's client. |
| `v2-16-p0-sse2.log` | New-client SSE: `client_session` for the probe client, then the republished `question_batch_request` with batch `dadf170b…`, question `27369be9…`, same session. |
| `p0-messages.json` | Session history after cancel + answer: `User cancelled this question` (is_error true) and `Q1: Proceed again?\nUser answered: yes` (is_error false) on the matching `tool_call_id`. |
| `p0-body.json` | The `current-session` POST body (`session_id`). |
| `p0-answer.json` | The answer replay body used twice (batch `7e606a13…`, question `2c3d3a4c…`, `yes:true`). |
| `v2-16-p0-unlocked.png` | App unlocked, project session list. |
| `v2-16-p0-newsession.png` | New code session before the first question. |
| `v2-16-p0-typed.png` | The question prompt typed in the composer. |
| `v2-16-p0-question-parked.png` | The parked question dialog for `Continue now?`. |
| `v2-16-p0-current.png` | Session list after the process-kill experiment; no dialog. |

Raw evidence also lives in the device's `files/app.log` (timestamps quoted in the
engine-contract report) and in `v2-16-phase0-androidtest-build-*.log` at the repo
root (build-only; the instrumentation probe was abandoned because `am instrument`
kills the app it measures).

## Checkpoint 2 (P0-E2 / P0-E3 / P0-E4), same date

| File | What it shows |
|---|---|
| `v2-16-p0-app-1039-1052.log` | app log 10:39–10:52: two project engines, 2/2 budget refusal, project-switch cancel, B2/B3/B5 runs, 60 s busy extensions. |
| `v2-16-p0-app-checkpoint2.log` | app log 11:21+: the complete 240 s timeline (60/120/180 s extensions, cancel at 240 s), permission mode transitions. |
| `v2-16-p0-logcat-kills.txt` | logcat kill reasons: `OneKeyClean` at 11:17:19 (app spared), **`GarbageClean: kill com.crush.mobile` at 11:18:52**. |
| `v2-16-p0-session-822d13c1.json` | 62-message engine history: B2/B3/B5/B7 completions, B4/B6 “tool call was interrupted”, the 240 s run's `context canceled`, and all tool-class results. |
| `v2-16-p0-b2-done.png` | turn completed while backgrounded (`B2DONE`). |
| `v2-16-p0-b4-interrupted-card.png` | engine-death recovery UI: **“This reply failed / Interrupted / Retry”**. |
| `v2-16-p0-b4-gate.png` | 0-byte capture — the pull failed; not evidence. |
| `v2-16-p0-b4-after.png`, `v2-16-p0-b4-session.png`, `v2-16-p0-b6-state.png` | session lists after the process/package kills. |
| `v2-16-p0-b7-recents.png`, `v2-16-p0-b7-cleaner.png` | MIUI recents (clean-all affordance) and the Cleaner app. |
| `v2-16-p0-ask-restored.png` | Settings showing `ASK EACH TIME` after the experiment. |

No provider key value appears in these files; commands selected fields only.
