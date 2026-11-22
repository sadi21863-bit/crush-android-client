# O11 — session-list loading row (2026-11-22)

Scope: V2-0 O11. The list-first surfaces must show a truthful loading row while
the session/chats fetch is in flight on a fresh workspace, and the copy must
name the surface (`Loading sessions...` for a project's Code sessions,
`Loading chats...` for Crush Chat).

## Method

The row normally exists only inside a sub-second window (`sessionsLoading =
true` until the fetch returns), and this device cannot be driven to that window:

- injected `adb input tap` intermittently lands ~200 px above the target inside
  scrollable content (real-finger taps are exact), so the list surface could not
  be opened inside the window;
- an instrumented activity launch is refused by API 29 background-start rules on
  this ROM: `ActivityTaskManager` logs the `START`, then never delivers it,
  while the same component launched from `am start` resumes at once;
- the loading spinner is an infinite animation, so on-device Compose
  `waitForIdle` never returns (`O11LoadingRowTest` hung twice, then the test
  process was killed; the test was removed rather than committed broken).

So the frame is captured from the **real product composable**, rendered by a
debug-only preview activity in the debug source set
(`app/src/debug/.../spike/SessionListPreviewActivity.kt`, manifest entry
`exported="true"` only because this ROM's shell cannot start a non-exported
activity). Nothing in that harness exists in a release build.

```
adb shell am start -n com.crush.mobile/.ui.screens.spike.SessionListPreviewActivity
adb shell am start -n com.crush.mobile/.ui.screens.spike.SessionListPreviewActivity --es surface chat
```

## Frames

| File | Surface | What it shows |
|---|---|---|
| `01-code-loading-sessions.png` | project `TestProject3` (Code) | `Loading sessions...` beside the indeterminate spinner, empty list, `New session` action |
| `02-chat-loading-chats.png` | Crush Chat | `Loading chats...` beside the spinner, empty list, `New chat` action |

Both captured on the Redmi Note 7 Pro (API 29) at 1080x2340, density 440,
light system theme, after installing the debug APK built from this commit.

## Live-state evidence (stall harness)

The host's own entry into this state was measured on the same device with the
debug-only launch extra before these frames were taken:

```
00:24:35.168 I/CrushSession: ensureAgentReady: ... is_ready=true is_busy=false
00:24:35.225 I/ChatViewModel: O11 session-list stall injected: 8000ms
00:24:44.956 I/ChatViewModel: O11 session-list stall injected: 8000ms
```

(transcribed from `files/app.log` during that run; the app log has since
rotated). The harness is `DebugStall` + `AppContainer.debugSessionListStallMs`,
armed by `adb shell am start ... --el crush.debug.stall_sessions_ms 8000`,
clamped to 15 s and inert in release. Pins: `DebugStallTest` and the two loading
rows in `ChatListContentTest`.

## Limit of this evidence

The preview activity renders the same composable with the same state the host
produces; it is not a capture of the live navigation window. The live window is
evidenced by the stall log and the unit pins. O11 is therefore recorded as
**PASS (rendered frame via debug preview; live window log only)** — not as a
manual end-to-end capture.
