# V2-15 Phase F — device matrix and verification

Date: 2026-11-25. Status: **executable rows performed on the available device;
unavailable rows explicitly NOT PERFORMED.** Phase F is verification only: no
production code, dependency, workflow or V2-15 Phase E behaviour was changed.

This record closes the deferred matrix rows from
`V2-15-RECONCILIATION.md` §7 and the Phase E "no device/TalkBack run" gap. It
does not reopen Phase E and does not change the accepted Phase E evidence.

## 1. Device, build and method

| Item | Value |
|---|---|
| Device | Redmi Note 7 Pro (`violet`), **API 29** (Android 10), arm64-v8a, 1080x2340, 440 dpi |
| Serial | `bbf8ca69` |
| Locale / font / RTL | `en-IN`, `font_scale=1.0`, `debug.force_rtl=0` (restored after the RTL attempt) |
| Build | debug `com.crush.mobile` 1.0.0 from this working tree; `app-debug.apk` + `app-debug-androidTest.apk` |
| androidTest build | rebuilt 2026-11-25, BUILD SUCCESSFUL in 13m23s (`v2-76-phaseF-androidtest-build.log`) |
| Install | `adb install -r` for both APKs, so the live credential prefs were not wiped |
| Engine | bundled `libcrush.so`, reported `v0.100.0` (`build_id 2my2izixtou8`, go1.27.1) |
| Provider used for the live turn | `opencode-go`, model `DeepSeek V4.1` (free tier) |

Method:

- Instrumented classes were run **individually** with
  `am instrument -w -r -e class <Class>#<test>`, so one failure or one process
  kill cannot contaminate another class.
- "Test time" below is the JUnit `Time:` line. "Wall" is the PowerShell
  stopwatch around `adb`. On this ROM the two differ by minutes (see §5.1);
  no timing claim is made from wall time.
- Credential material: only file metadata was inspected. One deviation is
  disclosed in §4.
- Every screenshot path below is a file name inside
  `docs/parity/v2-15-phase-f/` (see its `MANIFEST.md`), produced by
  `screencap` + `adb pull`; no screenshot was edited. The raw instrumented logs
  listed here are stored in the same directory.

## 2. Instrumented results

| Class | Tests | Result | JUnit time | Log |
|---|---|---|---|---|
| `SecureKeyStoreInstrumentedTest` | 3 | **3/3 PASS** | 0.287s | `v2-64-phaseF-securekeystore.log` |
| `ProjectStoreRoomPersistenceTest` | 2 | **2/2 PASS** | 0.286s | `v2-65-phaseF-room.log` |
| `MainActivitySmokeTest` | 2 | **2/2 PASS** | 401.11s | `v2-66-phaseF-smoke.log` |
| `TwoProjectAttachTest` | 1 | **1/1 PASS** | 4.293s | `v2-77-phaseF-twoproject-rerun.log` |
| `TwoEngineInstancesPerProjectTest` | 1 | **1/1 PASS** | 6.463s | `v2-78-phaseF-twoengine-rerun.log` |
| `MainActivityLockFlowTest` | 6 | **5 PASS / 1 BLOCKED** | see §2.2 | `v2-79`…`v2-83` |

Total: **14 test methods passed, 1 blocked, 0 assertion failures on app
behaviour.**

### 2.1 The two engine-isolation classes

`TwoProjectAttachTest` and `TwoEngineInstancesPerProjectTest` first failed after
the engine moved to `v0.100.0`, because the tests still omitted the permission
wire and the engine now requires the explicit mode. They are test-only fixes:
both now pass `EngineProtocol.modeFor(PermissionMode.ASK)` (`"normal"`) through
`WorkspaceManager.open(..., permissionModeWire)`. No production code was
changed for them. First (failing) runs are `v2-74`/`v2-75`; the passing reruns
are `v2-77`/`v2-78`.

### 2.2 `MainActivityLockFlowTest` — six tests

| # | Test | Result | Evidence |
|---|---|---|---|
| 1 | `keySessionRequireServesTheKeyButNeverCachesIt` | **PASS** | `v2-79` (`STATUS_CODE 0`) |
| 2 | `freshProcessWithStoredCredentialHoldsNoDecryptedKey` | **PASS** | `v2-79` |
| 3 | `realCipherTamperedCiphertextClearsTheSlot` | **PASS** | `v2-79` |
| 4 | `foregroundWindowFlagAgreesWithTheLockGate` | **PASS** | `v2-81` (1.432s test time) |
| 5 | `secureKeyStoreRealCipherRoundTripAndPerProviderIsolation` | **PASS** | `v2-80` (0.149s, 5.76s wall) |
| 6 | `recreationKeepsGateDecisionAndLockStateObject` | **BLOCKED** | `v2-82`, `v2-83` |

Run history, all of it recorded:

- **First suite run (`v2-79`, 689.84s wall).** Tests 1–3 passed. Test 4 had just
  started when MIUI's `OneKeyClean` killed the instrumentation process (signal
  9) at 11:59:49.892 while `ConfirmDeviceCredentialActivity` was open. Logcat
  shows `OneKeyClean` killing many processes and `BiometricService: Binder
  died, killing ConfirmDeviceCredential`. This is an **external kill, not a
  test failure**, and it is not counted as one.
- **Test 5 rerun alone (`v2-80`)** because the kill prevented it from running in
  the suite: PASS in 0.149s.
- **Test 4 rerun alone (`v2-81`).** `ActivityScenario` waited ~24 minutes for
  its activity start. Issuing the exact launcher intent
  (`am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER
  -n com.crush.mobile/.MainActivity`) unblocked it; the test then passed in
  1.432s. A plain `am start -n …/.MainActivity` was ignored by
  `ActivityScenario` (`activity.getIntent()` did not match the scenario's
  `flg=0x10008000` intent) and did not unblock it.
- **Test 6, attempt A (`v2-82`).** No unlock: `ActivityScenario.recreate()` threw
  `Activity never becomes requested state "[RESUMED]" (last lifecycle transition
  = "PAUSED")`. The auto credential prompt puts `MainActivity` in PAUSED for as
  long as the system dialog is up, so `recreate()` cannot reach RESUMED.
- **Test 6, attempt B (`v2-83`).** Unlock during the prompt: `recreate()`
  completed, but the pre-auth snapshot was `PROMPT` and the post-recreate gate
  was `OPEN` (`expected:<PROMPT> but was:<OPEN>`), because the owner's
  authentication legitimately opened the gate.

**Verdict: BLOCKED by test design, not an app defect.** The test snapshots the
gate before `recreate()` and requires it unchanged; on this device the only two
possible outcomes are (a) the credential dialog pauses the activity so
`recreate()` times out, or (b) a real unlock changes `PROMPT → OPEN` while the
scenario is parked. It cannot pass on any device that auto-prompts through a
system credential activity. The behaviour it targets is still pinned:
`LockStateTest` (9 JVM pins) covers the decision inputs, and the hands-on
double-prompt story (2026-10-20) covered the real auth path. A deterministic
device version would have to establish the pre-unlocked state through the
production `LockState.noteUnlock()` entry point before launching; that is a
test-design change, deliberately **not** made in a verification phase.

## 3. UI matrix rows

| Row | Verdict | Evidence |
|---|---|---|
| 1.3x font scale | **PASS** | `v2-70` (Settings), `v2-71` (chat list), `v2-92` (Code), `v2-93` (Settings bottom); no clipping, overlap or lost controls. Restored to 1.0. (`v2-72` is mislabeled — it shows the chat list, not the Settings bottom.) |
| Clipboard copy (Diagnostics) | **PASS** | `v2-88`: "Diagnostics copied" toast. API 29 blocks `dumpsys clipboard` for the shell, so the toast is the evidence, not clipboard read-back. |
| Share (Diagnostics) | **PASS** | Tapping Share resumed `android/com.android.internal.app.ChooserActivity` with `act=android.intent.action.CHOOSER`. `v2-89` is 0 bytes because the chooser surface blocks `screencap`; the evidence is the resumed component, not an image. |
| A12 question dialog, `yes_no` | **PASS** | Real engine question, §3.1; `v2-115` (dialog, Submit disabled), `v2-116` (Yes selected, Submit enabled), `v2-117` (answer accepted, run finished). |
| A12 `single_choice` / `multi_choice` / `free_text` on device | **NOT PERFORMED** | Only `yes_no` was induced; the engine asked one question type. The other three remain JVM/Robolectric-only (Phase E). |
| TalkBack traversal | **PASS (scoped)** | §3.2; `v2-99`…`v2-103`. Focus traversal and grouping verified; speech content not captured. |
| Wide window + extended rail | **PASS (simulated)** | §3.3; `v2-118` (Code), `v2-119` (Chat), restored `v2-120`. Not tablet hardware. |
| RTL layout | **NOT PERFORMED** | §3.4; `debug.force_rtl` is ignored by this ROM. |
| API 30+ runtime | **NOT PERFORMED** | The only device is API 29. |
| Tablet / foldable hardware | **NOT PERFORMED** | No such device; the wide row is a `wm size` simulation. |

### 3.1 A12 question dialog — live engine turn

Induced on `Test project folder 2` (Crush Code, `ASK` permission mode) with a
throwaway prompt. The engine (DeepSeek V4.1 via `opencode-go`) called its
`question` tool:

```
13:19:14.214 I/ToolActivity: observed tool_call shape: ... name=question
13:19:14.577 I/ChatViewModel: question batch requested (raw {"id":"cb8058de-…",
  "questions":[{"id":"38fecd57-…","type":"yes_no",
  "question":"Would you like me to continue working on this project?",
  "description":"Answer yes to proceed with the next task, or no to pause here."}]})
13:19:14.578 W/ChatViewModel: prompt-wait: entering (run 3133e6b2-…)
13:22:55.978 W/ChatViewModel: prompt-wait: frame received; loop resumes
13:22:55.981 I/ChatViewModel: question batch cb8058de-5ae answered (1 responses)
```

The dialog rendered the question, the engine's description, `Yes`/`No` radios,
`Cancel` and a **disabled** `Submit` (`v2-115`). Selecting `Yes` enabled Submit
(`v2-116`). Submit posted the answer, the run resumed and the assistant replied
"You answered yes. The question is answered, so I'm stopping as instructed."
(`v2-117`). No answer read-back endpoint exists: the engine's own reply is the
proof it accepted the id.

Observation (cosmetic, not fixed): a single-question batch renders the same
sentence twice — once as the dialog title and once as the prompt body — because
`PendingQuestion.displayTitle()` falls back to the first question's text while
the body also renders `prompt.text`.

### 3.2 TalkBack

TalkBack (`com.google.android.marvin.talkback`) was enabled over adb
(`enabled_accessibility_services` + `accessibility_enabled=1`), the app relaunched
into a fresh process, and the screen traversed with DPAD next/previous.

- `v2-94`: TalkBack active with its focus indicator.
- `v2-99`: Settings; focus ring on the `5 min` timeout button.
- `v2-100`: Settings; focus ring on `Report a problem`.
- `v2-101`: bottom navigation; focus ring wraps the whole `Crush Code` item
  including its label (grouped as one control).
- `v2-102`: chat list; focus ring on the `Crush Chat` navigation item.
- `v2-103`: Settings, scrolled; focus returns to `5 min` (traversal is stable
  across a scroll).

What this proves: app controls and navigation items are reachable, focus is
drawn on them, and TalkBack receives their windows. What it does **not** prove:
the spoken label text. TalkBack's utterance text was not captured (speech is not
logged at the default verbosity), so the audible output is unverified.

Environmental observation: while TalkBack is on, this ROM emits a continuous
`E/talkback: EventTypeWindowContentChangedFeedbackRule: windowContentChanged:
error, node is null` stream — 55 events per 6s with the app in the foreground
and **53 per 6s with the launcher in the foreground**. It is ROM/TalkBack noise,
not an app-specific event flood.

### 3.3 Wide window / extended rail (simulated)

`shellLayoutFor()` switches to `ExtendedRail` at `widthDp >= 840`
(`PrimaryDestinations.kt:55-62`). The phone was driven to a 1066 dp window with
`wm size 1600x1000` + `wm density 240`:

- `v2-118`: Code sessions — left extended rail ("Crush Mobile" header, labelled
  `Crush Chat` / `Crush Code` / `Settings`) beside the content pane.
- `v2-119`: Chat list at the same width; the rail keeps its selection state.
- `v2-120`: after `wm size reset` + `wm density reset`, the native 1080x2340 /
  440 dpi layout and bottom bar are back.

This exercises the breakpoint and the extended-rail composable on a real device
with real Compose measurement. It is **not** a tablet claim: no foldable/tablet
hardware, no multi-window split, no landscape-tablet posture.

### 3.4 Why RTL is NOT PERFORMED

Three attempts, all recorded:

1. `settings put global debug.force_rtl 1`, force-stop, relaunch → still LTR
   (`v2-90`).
2. Rotation to force a configuration change → still LTR (`v2-91`).
3. The launcher and system UI also stayed LTR, so this ROM ignores
   `debug.force_rtl` globally rather than the app refusing it.
   `am start` has no `--locale` option on API 29, and per-app locales are API 33+.

The only remaining path is changing the system locale to an RTL language and
rebooting twice on the owner's personal phone. That was judged out of scope for
a verification pass; the row stays NOT PERFORMED rather than being claimed.

## 4. Credential and data state after the run

- `adb install -r` preserved app data; `allowBackup=false` remains in force.
- `shared_prefs/secure_api_keys.xml` was 664 bytes / 2026-11-22 13:25 before the
  instrumented runs and 840 bytes / 2026-11-25 12:27 after them. The live slot
  set is **`hyper`, `openrouter`, `opencode-go`**; the throwaway
  `v2-9-selftest-*` slots written by the tests were cleaned up by their `@After`
  and are absent. The app was configured with `opencode-go` during the session
  (`ChatViewModel: providerId=opencode-go`); this phase wrote no key.
- **Deviation disclosed:** to confirm which slots survived, the XML was read
  once through a regex that emitted only `name="…"` attribute values
  (`iv.*`/`ct.*`). No ciphertext, IV or plaintext was printed, and no
  credential value is recorded anywhere in this report.

## 5. Environment findings (not row verdicts)

### 5.1 MIUI defers `ActivityScenario` launches

The lock-suite process started at 11:48:25, but tests 1–3 only ran at
11:59:46–48 — roughly 11 minutes later. Test 4 then sat until an explicit
launcher-category `am start` was issued. `MainActivitySmokeTest` shows the same
class of delay: `Time: 401.11` for two tests whose assertions take
milliseconds. The app was not hung (`alive t=…` heartbeats continued at 30s
intervals the whole time) and bootstrapping worked once the activity resumed
(`ChatViewModel: permissionMode=ASK providerId=opencode-go`). The delay is
MIUI's background-activity-start policy for an instrumentation process, not an
app defect. Wall-clock numbers from this device must not be read as test
durations, and a headless run on this ROM needs an explicit foreground start.

### 5.2 Log masking false positive

The question-batch log line prints `"tool_call_id":"[redacted-key]"`: `AppLog`'s
key masker matches the engine's UUID-shaped tool-call id. This is log-cosmetic
(the value is an id, not a secret) and does not affect the wire payload.

## 6. What this phase does not claim

- No API 30+, no tablet/foldable hardware, no real extended-rail hardware, no
  RTL, no TalkBack speech, no `single_choice`/`multi_choice`/`free_text` device
  dialog, no cross-process question persistence and no answer read-back.
- No JVM or Robolectric suite was rerun in Phase F; the last full gate remains
  `v2-61-phaseE-followup-gate2.log` (1087/117/0). The instrumented APK was
  rebuilt from the current tree, so the compiled state matches the working tree.
- No CI run, PR, merge or issue exists for Phase F.
