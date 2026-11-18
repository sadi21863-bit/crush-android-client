# After Phase 2b.5 - Onboarding surface device evidence

Device: `bbf8ca69`, Redmi Note 7 Pro, API 29, arm64-v8a, 1080x2340.
APK: `app-debug.apk` built 2026-11-18 17:07 host clock from the working tree
with the `OnboardingContent` extraction; installed 17:23 (host file times
canonical, the device clock runs ahead).
Full local gate: 844 tests / 90 suites, 0 failures / 0 errors; lint 0 errors
/ 1 standing warning (`NotShrinkingResources`).

## Results

| Check | Result | Evidence | Note |
|---|---|---|---|
| Onboarding render parity | PASS | `01-onboarding-2b5.png` vs `../after-2b4/07-settings-groq-selected.png` | The content region (y=100..2340) is **pixel-identical**: 0 changed pixels. Only the status bar differs (clock/icons). The baseline is the OLD screen captured on the 2b.4 APK; the new capture is the extracted `OnboardingContent`. |
| Provider menu open/dismiss | PASS | `02-onboarding-provider-menu.png`, `03-onboarding-menu-dismissed.png` | The dropdown lists every provider with the free-tier labels (OpenRouter, Groq, Google Gemini). `Back` dismissed it; the dismissed screen is pixel-identical to the initial render (0 changed pixels in y=100..2340). |
| Owner provider restored | PASS (manual) | `04-chat-restored.png` | After the walk, `provider_id` was restored from `groq` to `opencode-go` by rewriting the DataStore preferences protobuf with the app force-stopped (host/device md5 matched). The next cold start prompted once and landed on the working chat. No credential file was read, printed, or modified. |

## How onboarding was reached

Onboarding is the root only when the selected provider has no stored
ciphertext. The walk used the Finding 12 path: Settings -> select Groq (no
key) -> the root resets to Onboarding. That is a real pre-existing host
behavior, not a 2b.5 mechanism. A clean-install first run was not
manufactured: it would wipe the owner vault and project registrations.

## Limits

- This proves the extracted screen is pixel-identical to the pre-extraction
  screen on API 29. It does not exercise "save a key" on device (entering a
  key would be a credential write); the save/advance/failure branches are
  pinned by `OnboardingContentTest` (10 tests).
- API 29 only; no API 30+ auth-gate, AFA, or wide/tablet claim.
- The device clock runs ahead of the host; host file times are canonical.
