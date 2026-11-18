# after-f12 — device walk for §5.9 (Finding 12 fix + provider offer list)

Device: `bbf8ca69` (Redmi Note 7 Pro, **API 29**, arm64-v8a, 1080x2340).
Build: post-fix `app-debug.apk` from the §5.9 working tree (installed 2026-11-18
23:31:21). Engine on device: 0.99.0. Owner provider before the walk:
`opencode-go` (key stored).

All screenshots are `/sdcard` pulls, untouched apart from the rename of 08/09
(see "Corrections").

## Walk

| # | Capture | Result |
|---|---|---|
| 00 | `00-launch.png` | True cold start (fresh process). Route = **Chat** with the lock overlay on top; header "New chat / engine stopped" behind it. A stored key for the selected provider still routes to chat. |
| 01 | `01-unlocked.png` | Owner unlock (their fingerprint, entered by them). Chat `ready`, model `deepseek-v4.1-flash`. |
| 02 | `02-drawer.png` | Drawer → Settings reachable. |
| 03 | `03-settings.png` | Provider list = **OpenCode Zen, Charm Hyper, OpenRouter, OpenCode Go, Groq**. The four tested rows, plus Hyper **because its key is stored** (`key stored` badge): hiding a configured credential would strand it. Onboarding has no vault extras and therefore offers only the four. |
| 04 | `04-zen-selected.png` | **Finding 12 fixed.** Tapped key-less OpenCode Zen. The app **stayed in Settings**: radio moved to Zen, API card switched to "No API key stored / Add a … key". Under the 2b.4 build this same tap replaced Settings with Onboarding (`after-2b4/07-settings-groq-selected.png`). |
| 05 | `05-go-restored.png` | Restored OpenCode Go **through the UI** (no DataStore surgery this time). Still in Settings, "API key saved", Replace/Delete shown. |
| 06 | `06-back-chat.png` | Back → same chat, header "connecting to agent" (provider change re-bootstraps). |
| 07 | `07-chat-ready.png` | Chat `ready` with `deepseek-v4.1-flash`; the provider round-trip did not disturb the session stack. |

Persistence check (no secret printed): `run-as … grep -a -o 'opencode-[a-z]*'
files/datastore/settings.preferences_pb | sort -u` → `opencode-go` only.

## Corrections (kept, not hidden)

- **08 / 09 are NOT a cold start.** Originally named `08-coldstart.png` and
  `09-coldstart-late.png`; logcat showed the **same pid (21194)** across them,
  so `am force-stop` had not taken effect and the app only recreated the
  Activity. Renamed to `08-activity-recreate.png` /
  `09-activity-recreate-late.png`. What they DO show: an Activity recreation in
  a live process preserves the launch route and the unlock (gate=OPEN,
  `verified=true`), i.e. the `LaunchRouteState`/`LockState` process scope.
- **True cold start repeated** with the process verified dead
  (`pidof` empty before launch, pid 21723 after). Logcat:
  `resume gate=PROMPT coldStart=true away=-1s ciphertext=true wasUnlocked=false
  verified=false` → the system biometric dialog auto-opened. Pixels for this
  state could not be captured: the paused app sets `FLAG_SECURE`, and this ROM
  returns **0-byte** screencaps for the protected window (confirmed: Home
  captures fine at the same moment). The `PROMPT` decision is log-evidenced;
  the route beneath it is evidenced by 00 (same predicate, same build).
- Post-unlock states after that true cold start are likewise not photogenic on
  this ROM (FLAG_SECURE is set for the window's life once the app pauses), so
  the screenshots above come from the 23:32 session, before any pause.

## Not performed (honest scope)

- **Onboarding picker on device**: would need wiping app data and re-entering a
  key. The four-row offer list is pinned by `ProvidersTest` (exact id order)
  and `OnboardingContentTest` fixtures; Settings uses the same
  `Providers.offerable` seam and IS device-verified here.
- API 29 cannot demonstrate API 30+ behaviour or wide/tablet layouts (unchanged
  limitation).
- No credential was typed, read, or written by the agent; the owner performed
  the unlock.

## Defect observed, deliberately not fixed here

`04-zen-selected.png`: the API card reads **"Add a OpenCode Zen key to start
chatting."** Wrong article before a vowel. Copy-only; owner chose to record it
rather than rebuild. Suggested wording: "Add a key for OpenCode Zen to start
chatting."

## Verdict

- Finding 12: **FIXED on device** (04, and no navigation reset through 05/06/07).
- Untested-provider gating: **confirmed on Settings**; Hyper's presence is the
  intended stored-key exception, with its "unverified on this build" note.
- Cold-start routing still truthful: **chat for a stored key** (00), **PROMPT
  gate for a fresh process** (log).
