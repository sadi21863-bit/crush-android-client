# After Phase 2b.4 - Settings surface device evidence

Device: `bbf8ca69`, Redmi Note 7 Pro, API 29, arm64-v8a, 1080x2340.
APK: `app-debug.apk` built 2026-11-18 16:24 host clock from the working tree
that became commit `89e294b`; installed 16:50 (host file times canonical,
the device clock runs ahead).
Full local gate for that commit: 834 tests / 89 suites, 0 failures /
0 errors; lint 0 errors / 1 standing warning.

## Results

| Check | Result | Evidence | Note |
|---|---|---|---|
| Settings screen pixel parity | PASS | `06-settings-after-2b4.png` | The content region (y=100..2340) is **pixel-identical** to the 2b.1 baseline `../after-2b1/f25-after-settings.png`: 0 changed pixels. Only the status bar differs (clock/Signal icons). |
| Settings `Back` returns to the same chat | PASS | `11-chat-restored.png`, `13-settings-back-to-chat.png` | 0 changed pixels in y=100..2340 between chat-before-Settings and chat-after-Back. The composer and empty transcript are intact. |
| Provider selection inside Settings | FINDING, not a pass | `06-settings-after-2b4.png` -> `07-settings-groq-selected.png` | Tapping Groq (no stored key) persisted `provider_id=groq` and the root immediately reset to Onboarding. `MainActivity` recomputes `hasApiKey(store, settings.providerId)` and passes a new `startDestination` to `NavGraph` (`MainActivity.kt:216-239`), so the Settings stack is discarded. This is **pre-existing host behavior**, not introduced by the `SettingsContent` extraction: the content still dispatches `onSelectProvider(id)` exactly as the old screen did. Recorded as a new finding; do not fix inside an extraction batch. |
| Owner provider restored | PASS (manual) | `12-settings-reopened.png` | After the reset, `provider_id` was restored to `opencode-go` by rewriting the app's DataStore preferences protobuf (`files/datastore/settings.preferences_pb`) with the app force-stopped; host and device md5 matched. No credential file was read, printed, or modified. OpenCode Go is again the filled radio. |

## Limits

- The provider radio's selected state cannot be observed on the Settings
  screen after a switch to a key-less provider: the root navigates away
  first. Radio rendering and selection dispatch are pinned by
  `SettingsContentTest` (10 tests).
- Theme, keyboard, permission, tuning, and About rows were not re-driven on
  device in this walk; their rendering and dispatch are test-pinned. The
  2b.1 walk already covered the Settings entry/exit path.
- API 29 only. This device cannot prove API 30+ auth-gate behavior, AFA
  rationale, or wide/tablet layouts.
- The `07` screenshot is the **old** Onboarding screen (the APK predates the
  2b.5 extraction). It is evidence of the navigation reset, not 2b.5 parity.
