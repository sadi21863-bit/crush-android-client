# V2-0 fresh phone baseline - 2026-11-19

**Purpose:** V2-0 task 6 evidence on the connected device. This set is
additive: it does not replace `docs/parity/2026-11-15-c4-baseline/`, which
remains the full parity baseline. No product source, test or build file was
changed by this run.

## Device and build of record

| Fact | Value |
|---|---|
| Device | Xiaomi Redmi Note 7 Pro (`violet`), serial `bbf8ca69` |
| Android | 10 (API 29) |
| Display | 1080x2340, density 440 (about 393dp wide: compact) |
| App | `com.crush.mobile` 1.0.0 (versionCode 1) |
| Installed build | `lastUpdateTime` 2026-11-18 23:31, matching the Finding 12 gate build; the APK commit cannot be proven from the device |
| Engine binary | present (`Engine file: installed`); engine process was **not running** during these captures |
| Engine version | **not re-queried this session** - Diagnostics does not expose it; last recorded value stays v0.100.0 / contracts 0.99.0 |
| Selected provider | OpenCode Go (Settings capture 02); stored keys shown for Charm Hyper, OpenRouter, OpenCode Go |
| Session | cold start, then UI navigation only; **no prompt was typed and no engine turn was run** |

Clock note: status-bar times are device-local (22:00-22:30). `app.log` uses
the same clock and is quoted below.

## Method

- `screencap -p /sdcard/<name>.png`, then `adb pull` into this folder, then
  delete the device copy. No `adb exec-out > file` (corrupts on this host).
- Taps use real 1080x2340 pixels. `uiautomator dump` fails on this ROM, so
  coordinates are derived from the captures themselves.
- Log excerpts come from the app's own `files/app.log` via `run-as`.
- Screenshots show the app's own masked key chip; **no key material is
  recorded in this manifest**.

## Captures

| File | State | What it establishes |
|---|---|---|
| `00-coldstart-under-credential-prompt.png` | app surface captured while the system credential UI was up | methodology: `screencap` returns the app beneath a secure system window - a screenshot cannot prove "no lock prompt" (see C1) |
| `01-sessions-sheet-unavailable.png` | sessions sheet, engine stopped | the `sessionsUnavailable` state is user-visible and explicit: "Chats aren't available - send a message to start it"; the sheet names the active project "DottedTrust3"; the four entries are New chat / Settings / Projects / Review changes |
| `02-settings-top.png` | Settings, top | provider rows and the selected Go radio; stored-key chips; Keyboard and Permissions sections begin below the fold |
| `03-settings-keyboard-permissions.png` | Settings, middle | Keyboard choice; Permissions "Auto-approve agent actions" OFF with "ASK EACH TIME"; device profile line reads `MID - API 29 - 3615MB RAM - 8 cores` |
| `04-settings-performance-about.png` | Settings, bottom | Performance AUTO/MANUAL rows (prewarm MANUAL/OFF); About shows **Version 1.0.0 only** - no build number, engine version or contract level |
| `05-diagnostics-status.png` | Diagnostics | App version 1.0.0 (1), API 29, device, ABIs, engine file installed, engine stopped, engine responding "not checked"; Copy/Share present; **no engine version or contract level** |
| `05b-diagnostics-log.png` | Diagnostics scrolled | the full cold-start log excerpt quoted below, readable on the device |
| `06-sheet-after-back.png` | sessions sheet, second open | Back from Diagnostics to Settings to Chat returns to the same chat surface; a further tap re-opens the sheet |
| `07-projects.png` | Projects, top | import-only header, legacy row without a delete control, named Remove on imported projects |
| `08-projects-lower.png` | Projects, bottom | `DottedTrust3` (the active binding named in capture 01) is styled **identically to every other project** - no Current marker |
| `09-review-empty.png` | Review changes | honest empty state ("Nothing collected yet") plus the process-scope/eligibility copy |
| `10-model-sheet.png` | Models sheet | sheet opens; subtitle says "Live list from **OpenCode Zen**" while Settings shows Go selected; no loading state; no selection mark on a new chat |
| `10b-model-sheet-lower.png` | Models sheet scrolled | free-named rows (`Hy4 flash Free`, `IcEman Contributor Free`, `LongCat 2.5 Free`) render **no FREE badge**; the list is long and unstyled per row |
| `11-chat-empty-stale-phase.png` | Chat, engine stopped | empty state copy; subtitle reads `Chat - no tools - connecting to agent` although no engine process exists |
| `12-composer-focused.png` | Chat, composer focused | focus ring and keyboard: input handling is alive during the stale-phase observations (rules out a hang) |

## Log evidence (verbatim, app.log)

```
21:59:55.372 I/Main: resume gate=PROMPT coldStart=true away=-1s ciphertext=true wasUnlocked=false verified=false freshUnlock=false
21:59:56.763 I/ChatViewModel: lock engaged; refusing to bootstrap
21:59:56.826 I/Lifecycle: restoring last binding: project 'DottedTrust3'
21:59:56.830 I/App: pause MainActivity@73172977
22:00:04.493 I/DeviceCredential: credential result ok=true
22:00:04.611 I/LockOverlay: unlocked
22:00:04.618 I/Main: resume gate=OPEN coldStart=false away=0s ciphertext=true wasUnlocked=true verified=true freshUnlock=true
22:11:52.979 D/ChatViewModel: bindEngine state=Stopped instance=chat
```

`ps -A` at 22:30 listed `com.crush.mobile` (pid 7480) and **no engine
process**. From 22:11 to 22:30 the subtitle still read `connecting to agent`.
That is the stale-phase observation, F-E below.

## Findings from this run (observed, not fixed)

| ID | Finding | Evidence |
|---|---|---|
| F-A | **No "Current" marker.** The active project is named in the sessions sheet, but no project row is visually distinguishable, and `ProjectsViewState` carries no active field | 01, 08 |
| F-B | **Models sheet provenance is stale or mislabelled.** Settings selects OpenCode Go; the sheet says "Live list from OpenCode Zen". No loading state; no FREE badge on free-named rows | 02, 10, 10b |
| F-C | **The model picker is invisible on a new chat.** It is a `TextButton` whose label is the selected model id (`orEmpty()`), so with no selection it renders as empty space at the top-right, still tappable | 10, `ChatContent.kt` top-bar actions |
| F-D | **About and Diagnostics omit engine version and contract level.** Diagnostics reports only "engine file installed / stopped / not checked" | 04, 05 |
| F-E | **Stale phase text.** `connecting to agent` persisted ~18 minutes while `bindEngine state=Stopped` and no engine process existed; UI input was alive throughout | 11, 12, log, `ps -A` |
| F-F | `sessionsUnavailable` is honest and actionable | 01 |
| F-G | Review empty state is honest; populated card remains unproven | 09 |

F-A, F-B and F-D independently confirm gaps already listed in
`../../../v2/V2-0-reconciliation.md`. F-E is **new** and belongs in the
V2-4/V2-6 carry list: a header that states a phase must be derived from the
same snapshot as the binding, not left behind by the previous instance.

## Corrections made during this run

- **C1 - the first reading of capture 00 was wrong.** I first wrote that the
  app "landed directly in Chat with no lock prompt". The log proves the
  opposite: `gate=PROMPT coldStart=true`, the activity paused as the system
  credential UI opened, `credential result ok=true` at 22:00:04, and only
  then `gate=OPEN ... freshUnlock=true`. The screenshot showed the app
  *beneath* the secure window. Record: a screencap is not evidence of the
  absence of a system prompt, and this cold start **did** prompt exactly
  once. The credential was entered on the device by the owner, not by the
  agent.
- **C2 - no hang occurred.** Two taps in the top bar produced no visible
  change and no log line, which briefly looked like a freeze. The composer
  then focused and opened the keyboard, `pidof` stayed at 7480, and logcat
  had no ANR line. The taps were geometry misses: the `+` sits **left** of
  the model button. A tap believed to be "models" actually hit `+`
  (new chat), which stopped the project instance and rebound `chat`
  (log 22:11:46-22:11:52).
- **C3 - no turn, no spend.** Nothing was typed into the composer and no
  engine was started for a model call. The engine process count stayed zero.

## Not performed

- **Lock overlay screenshot** - the credential UI is a secure system window;
  `screencap` returns the app surface instead. Log lines are the evidence.
- **Populated Review card** - needs a file-changing turn; not run.
- **Populated transcript / streaming** - would require starting a turn; the
  2026-11-15 `after-f12` captures remain the transcript evidence.
- **Onboarding** - not reachable without clearing app data; clearing would
  destroy the owner's state and is out of V2-0 scope.
- **Dark theme** - would change the owner's persisted appearance preference;
  not requested.
- **API 30+, AFA rationale, tablets/foldables/landscape, instrumentation
  execution** - device limits (API 29, 393dp compact).
- **Engine version re-query** - Diagnostics does not expose it and no engine
  was started.

## Boundary

Evidence only. This folder changes no product source, test, Gradle file or
navigation code. It authorizes no V2 implementation.
