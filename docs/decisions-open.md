# Open user decisions — 2026-10-27

Decisions no agent can make alone. Each one is either a money/security trade,
a product stance, or a task assigned to the human (creating credentials).
Everything here is distilled from [`deep-analysis-2026-10-27.md`](deep-analysis-2026-10-27.md)
§4; this file exists so the backlog can say "awaiting a human call" instead of
silently rotting.

## 1. Re-enable lint and decide on R8/minify

**Status: LANDED 2026-10-31 — commit `5325d68`.** The batch (4 code fixes
+ `app/lint.xml` waivers with reasons + the two defusal overrides deleted +
CI gained `:app:lintRelease`) is gated and green in one run:
`:app:testDebugUnitTest :app:assembleDebug :app:lintRelease
:app:lintVitalRelease` → BUILD SUCCESSFUL (455/455 tests; the fresh full
release lint analysis reports **"No issues found."**; the vital analyze
pass ran clean; `lintVitalReportRelease` skipped itself as there are no
vital findings). The gate proves both defused paths in one go — the exact
historical crash pass is re-enabled and green. APK installed on device
(`bbf8ca69`).

Two recorded numbers from the same day's analysis:

- AGP landscape: Google Maven tops at stable **9.5.0** (our AGP 9.4.1 is
  one behind; candidate for a deliberate review-time upgrade) and
  9.6.0-alpha02 (metadata 2026-10-28). **AGP 10 is not released** — the
  `builtInKotlin=false`/`newDsl=false` opt-outs stay in place, exactly per
  their in-file comments.
- APK composition (debug, `app-debug.apk` on disk 122.2 MB): engine .so
  92.7 MB raw, **14 `classes*.dex` totalling 66.95 MB raw** (the debug DEX
  carries the debug-only Compose tooling at 12.4 + 10.2 MB). The recorded
  "engine dominates, R8 has no size win" rationale is therefore numerically
  STALE for the debug artifact; a release-DEX measurement (pending, from an
  unsigned `assembleRelease`) supplies the honest R8 ceiling. The reflection
  risk half of the rationale (junixsocket/Gson) is untouched and still the
  hard part.

---

### R8 ceiling — measured 2026-10-31 (release artifact)

Unsigned `app-release.apk` (73.2 MB on disk) holds **47.76 MB of DEX across
3 files** (30.71 + 9.58 + 7.47 MB, stored uncompressed) while the engine
`.so` is 92.7 MB raw but only **24.84 MB compressed**. The compressed APK is
therefore ~66% DEX by mass and ~34% engine — the recorded "92 MB engine
dominates the payload, R8 has no size win" rationale (retired from
`build.gradle.kts` in `5325d68`) had the composition backwards. A typical
R8 shrink on a dependency-heavy Compose app cuts such DEX half or better;
**the measured ceiling is an APK of roughly ~45–55 MB** (a 20–30 MB cut),at the cost of keep-rules for junixsocket / Gson / OkHttp-SSE + Compose and
a device-verified run before commit. Updated recommendation on the R8
sub-decision: worth a gated, isolated attempt now that lint is green —
build-config-only batch (`isMinifyEnabled = true` for release + the
`proguard-rules.pro` keep-rules) — with the mandatory device sanity check
(one UDS turn end-to-end). The risk/effort call stays the user's.
> **Attempt executed and BEAT the ceiling: 2026-10-31, landed `37c93cb`.**
> First pass failed exactly where R8 predicted it would - four missing
> classes (junixsocket's compile-only annotations + a desktop-only `java.rmi`
> path), adopted verbatim as `-dontwarn` rules from R8's own
> `missing_rules.txt`. Second pass: `:app:testDebugUnitTest
> :app:assembleDebug :app:assembleRelease :app:lintRelease` -> BUILD
> SUCCESSFUL (24m30s; release lint green; vital analyze clean; no vital
> findings). **Measured: 73.2 -> 28.8 MB on disk; DEX 47.76 MB across 14
> files -> 3.39 MB in ONE classes.dex**; engine .so untouched at 92.7 MB
> raw; 40.8 KB obfuscation map (mapping.txt, build-only) captured for
> future stack-trace deobfuscation. Remaining verification (mandatory,
> blocked on decision 2's keystore): install the SIGNED minified APK on
> device and run one end-to-end UDS turn - the reflected code has to prove
> itself at runtime before this reads done-done. The debug artifact stays
> unminified and the device stays on the latest debug build.

Current state (both deliberate, both commented in [`app/build.gradle.kts`](../app/build.gradle.kts)):

- Lint is fully switched off (`checkReleaseBuilds=false`, `abortOnError=false`,
  lines 155–169). Lint's "vital" pass crashes the release build outright —
  `AsyncExecutionService.getService must not return null` thrown while parsing
  Kotlin imports in `SettingsViewModel.kt`. That is an AGP/lint defect, not a
  code defect; disabling it was the defusal.
- R8/minify stays off (line 92–94 rationale: minify + a Go binary in `jniLibs`
  adds risk for no size win on a 92MB payload dominated by the engine).

**The decision:** triage the lint crash (AGP version pitch, or a lint-only
isolate) and then choose: re-enable lint, and either enable minify or confirm
the size argument stands. ~~Neither may be flipped while the crash is
untriaged.~~ **Triage VERDICT 2026-10-31: the crash does not reproduce.**

Same-day reproduction, both variants invoked explicitly:

- `./gradlew.bat :app:lintDebug` — `BUILD SUCCESSFUL`, ~6m03s (fresh analysis,
  `results-debug` written 12:20:06).
- `./gradlew.bat :app:lintRelease` — `BUILD SUCCESSFUL`, 48s (full release
  analysis completed today at 12:43:16; `/app/build/reports/lint-results-*.txt`
  on disk, debug "1 error, 33 warnings", release "1 error, 34 warnings").

The historical crash was the `lintVitalRelease` pass — exactly what
`checkReleaseBuilds = false` disables — and the AGP surface evidently changed
under it (AGP 9.4.1 today as opposed to the crash-era plugin state). The
immediate blocker is gone; what remains before flipping the flags is a small
enablement batch (one gated commit):

1. Fix the one lint ERROR — `LockOverlay.kt:66` casts `LocalContext` to
   Activity (`ContextCastToActivity`); 2026-10-31 UI-batch code, and
   `LocalActivity` is the intended API. Its `ModifierParameter` neighbor
   (:64) goes along.
2. Fix the findings with real correctness value:
   `SessionList.kt:57` money formatting under `DefaultLocale` (a Turkish
   locale shifts the decimal separator in "$x.xx"), the ViewModel-context
   retention lint flags at `ChatViewModel:294` (`SecureKeyStore(context)`
   keeps an Activity-context reference in a ViewModel that outlives
   rotation — pass an Application context), the empty `super.onCleared()`
   (`EmptySuperCall` ChatViewModel:964), and the `ObsoleteSdkInt`/v26-resource
   simplifications if wanted.
3. Waive the DELIBERATE findings with a dated `lint.xml`: `SdCardPath` x8
   (engine-log redaction and path mapping code that must hardcode
   `/data/user/0/`), `OldTargetApi 36` (compileSdk 37/target 36 is a pinned
   decision), `ChromeOsAbiSupport` (arm64-only by design), and the curated
   dependency pins (`GradleDependency`/`NewerVersionAvailable`).
4. Flip `checkReleaseBuilds = true` (warnings stay warnings; keep
   `abortOnError` semantics so nosy findings cannot block a build) and
   refresh the now-false crash comment at `build.gradle.kts:156–165`,
   citing this triage.

**R8/minify stays a separate call** — the recorded size rationale (92 MB
engine dominates the APK; DEX shrinkage gains are marginal against the
socket/Gson reflective risk) is untouched by today's result.

## 2. Create the release keystore and ship one signed assembleRelease

**Status: OPEN — human credential work.**

No signed release has ever been produced: every artifact so far was unsigned
or debug. `requireReleaseSigning` and the credentials loader already exist in
`app/build.gradle.kts` (lines 32–34, 99–117) and `keystore/` is gitignored;
only the keystore + `keystore/credentials.properties` are missing.

**The decision/task:** create the keystore, write the four properties
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`), then run
`.\gradlew :app:assembleRelease -PrequireReleaseSigning` and install the APK
on the reference device. Until this happens the README's "debug builds are
not shareable" rule means the app still cannot be handed to anyone.

## 3. Rotate/delete the plaintext test key file at repo root

**Status: OPEN — key is live, file re-confirmed present 2026-10-31.**

`"opencode api key for testing.txt"` sits at the repo root. It is gitignored
(`*key*.txt`, `.gitignore:15`) so it will never be committed, but the key
itself is real and live. The old world-readable `/sdcard/zenkey.txt` fallback
was deliberately removed from the spike harness (`FullE2ETest.kt` comment);
this repo-root paste-file is what remains of that workflow.

**The decision:** rotate the OpenCode Zen key at the provider, delete the
file, and pick a stated key-transfer procedure (paste directly into Settings)
instead of relying on a gitignore to make an on-disk key safe.

## 4. Injector-vs-engine concurrent `crush.json` write needs a design

**Status: OPEN — direction chosen 2026-10-31 (user adopted the
recommendation), design doc pending. Chosen direction: single-writer
— key injection moves to the socket only, the engine becomes the sole
writer of `crush.json`; the app injects at bootstrap and on key
change. The guard note this trades against (item 6) folds into the
same design.**

When the app's `EngineConfigInjector` rewrites `crush.json` with the provider
key and the engine writes its own config changes concurrently, one write can
lose the other (lost-update, `EngineConfigInjector.kt:134–167`). Any fix is a
single-writer/ownership design decision (e.g. keys injected over the socket
only, engine as sole writer of `crush.json`), which is why it is deferred
rather than patched.

## 5. No-lockscreen fail-open: confirm or block a store release

**Status: DECIDED 2026-10-31 — the beta-only stand is blessed by the
user; a store-distribution build additionally requires the fail-closed
lock first (fail-open stays a documented beta property until that
ships).**

With no lock configured, the app opens without any lock prompt
(`DeviceAuth.kt:54–62`; README "First-launch app lock bypass", D9). This is
documented and deliberate while in beta.

**The decision:** either confirm this stand (beta, not for store), or declare
that a store release requires fail-closed behavior first. Do not ship to a
store while the stance is unresolved-by-silence.

## 6. Key at rest in plaintext inside `crush.json`

**Status: DECIDED 2026-10-31 — accepted as a documented trade; the
agent-prompt guard folds into the decision-4 design (single-writer
key path) instead of landing as a separate patch.**

The provider key lives plaintext in `crush.json` (engine requires it there;
`EngineConfigInjector.kt:36–38/129`). The README documents it, including why
shareable debug builds are a hazard because of it. The suggestion from the
analysis: add an agent-prompt guard note so the embedded agent refuses to
echo the key (or flags any prompt that asks for it).

**The decision:** accept as documented trade, or fund the guard note /
out-of-file key injection design (ties into decision 4).

## 7. Backlog: cross-provider verified-model persistence

**Status: DEFERRED — for cross-agent API design, not an ask.**

Bootstrapping per resume costs up to three one-token probes plus a duplicate
catalogue fetch (the catalogue+probe block in `ChatViewModel.kt`, ~lines
691–815 as of 2026-10-31; previously cited at 634/654/745 before the file
grew). Persisting which model ids
each provider has already verified would kill that cost, but the item was
parked pending an API design that outlives this session. Kept here so it
stays visible next to the decisions it must not be confused with.

## 8. How long may the agent wait on ONE ask or question dialog?

**Status: DECIDED 2026-10-31 — 10 minutes confirmed as the parked-prompt
ceiling.**

The 2026-10-29 device-verification pass removed the old 180 s whole-turn
timeout (it could only ever kill healthy turns: a user still reading an ask,
or a long but steadily-streaming agentic turn). Real engine silence is now
caught by the 20 s per-frame idle deadline, and a PARKED prompt (the dialog
is standing, the engine is waiting on the decision) is bounded by
`PROMPT_WAIT_MS` = **10 minutes** after which the turn ends with an awaited
server-side cancel (`ChatViewModel.kt` companion).

**The decision:** confirm 10 minutes as the parked-prompt ceiling, or pick a
different bound (longer / shorter / never-ending interrupted by manual Stop).
The trade: a shorter bound cleans abandoned dialogs sooner but can cancel a
genuinely reading user mid-thought; "never" relies on the Stop button and
app teardown only. Unless you choose otherwise, 10 minutes stands.

## 9. Project creation model and per-row removal

**Status: DECIDED 2026-11-04 (user) — IMPORT-ONLY creation, type-honest
removal, legacy row permanent.**

Decided during the import-folder verification drive. Projects are created
ONLY via Import folder (the desktop-app model). The "New project"
minted-internal-repo path (`filesDir/projects/<uuid>/repo`) is REMOVED: its
content was invisible to the user's file manager, which is exactly the
failure import-folder exists to fix. Removal is type-honest per row kind:

- Imported (external) folder = USER DATA: removal drops only the app's
  row/reference; the folder and every file stay untouched. Re-importing the
  same folder later starts a FRESH project (fresh row id → fresh engine
  state dir — no session bleed back into the old one).
- App-minted internal repo = app-owned hidden data: deleted WITH its row
  (the `filesDir/projects/...` tree plus its `filesDir/crush-<key>` engine
  state dir; a removed row's UUID key can never recur).
- Legacy workspace row = PERMANENT: it hosts the app's existing chat
  history and the idempotent entry migration re-creates it; no Remove
  affordance is rendered, and the VM also refuses a removal if one is
  driven programmatically.

Removal additionally REFUSES while a run is in flight on that row's engine
("a run is active on this project's engine — remove it after the run
finishes"); otherwise: stop the engine instance → drop the row → delete the
internal trees.

Implementation (2026-11-04 cycle): `ProjectDao.deleteById` →
`RoomProjectStore.remove` (row-only) → `ProjectsViewModel.removeProject`
(legacy guard + run-in-flight guard + ordered IO) → `ProjectsScreen`
(single full-width Import button; per-row Remove icon with a type-honest
confirmation dialog). Verified on device the same evening: the import-only UI, per-row removal with the
legitimate engine-stop lines, and the imported folder byte-intact on the cancel
path — the drive itself caught two first-use defects (literal `%s` in the dialog,
empty minted-slot wrapper left behind) which are fixed in the same-day slot-fix
batch (see capabilities.md).

No open residue. One follow-up noted for the backlog: clearing legacy chat
history is a separate future feature ("delete chats"), NOT a project
delete — the legacy row itself stays.

## 10. Chat/Code modes — navigation, tool policy, default mode, legacy history

**Status: DECIDED 2026-11-04 (user) — folded into
[`chat-code-modes-plan-2026-11-04.md`](chat-code-modes-plan-2026-11-04.md) §5;
implementation NOT started, awaiting the user's explicit go.**

- **Navigation (Q1) → choose-at-creation:** mode picked in the new-session
  flow; Chats grouped separately from project sessions. Two-tab restructure
  (b) and in-session toggle (c) held as SELECTIVE backlog, not v1; (c)'s
  natural place is the Chat→Code handoff seam (roadmap §9 / P6).
- **Chat tool policy (Q2) → deny ALL tools in v1** (pure conversation).
  Backlog in expected order: "smart transfer" — a denied write/shell request
  in Chat surfaces a chat-mode message pointing at Code mode (denial message
  rides S4); interactive transfer-to-Code action later (the Q1(c)/P6 seam);
  read-only allowlist only after the S1 engine probe proves tools strictly
  read-only.
- **Default mode (Q3) → fixed Chat default** for new sessions; a Settings
  option is backlog, not v1.
- **Legacy history (Q4) → leave untouched.** User explicitly offered
  "wipe all + fresh start OR your choice" — the recorded standing
  recommendation is KEEP (history is real data; a prettier list is not worth
  the loss), adopted on the user's delegation and subject to their final
  confirmation at go-time. A deliberate, fully-disclosed "clear legacy
  history" action can be built later if a fresh start is truly wanted —
  never a silent wipe (item 9 above already reserved "delete chats" as a
  separate future feature; the legacy row itself stays permanent).

## Tree-verification pass 2026-10-31

- Every citation re-checked and accurate: lint flags at
  `app/build.gradle.kts:167–168` (inside the cited 155–169), minify
  rationale at 93–94, signing block 111–114 inside 99–117,
  `requireReleaseSigning` property verified; `DeviceAuth.kt:54–62` is the
  exact no-lockscreen fail-open branch; `EngineConfigInjector` writes
  `<globalDataDir>/crush.json` and skips old cost fields (`P2-21` at :185).
- `.gitignore:15` (`*key*.txt`) verified; the test-key file exists on disk,
  untracked; `keystore/` gitignored, no keystore present yet — items 1–3
  remain genuinely open.
- `PROMPT_WAIT_MS = 600_000L` (10 min) confirmed in the ChatViewModel
  companion with the awaited-server-cancel path — item 8's description
  matches the code.
- Items 4/5/6/7 remain open as written; no tree change observed.


## Adopted 2026-10-31 (user: "I will go with that")

Item-by-item adoption of the agent's recommendation set:

- **1 lint/R8** — GO on the enablement batch (see item 1 above); **R8 stays
  OFF**; AGP-10 opt-out flags (`gradle.properties:42/47`) intentionally STAY
  until an actual AGP-10 upgrade, because they are what permits the pinned
  KGP 2.4.10 (their exit condition is that migration, not this year).
- **2 keystore** — user creates it (command handed over 2026-10-31);
  signing + device verification follows the stopBlocking re-measure.
- **3 test key** — rotate at provider, delete the file (user; unchanged).
- **4 injector** — direction chosen (see item 4); design doc is the next
  artifact, before any code.
- **5 lock** — beta-only blessed (item 5 above).
- **6 key at rest** — accepted as trade (item 6 above).
- **7 model persistence** — stays deferred.
- **8 prompt wait** — 10 minutes confirmed (item 8 above).

Order of play from here: stopBlocking + backgrounding re-measure (user
drives two turns, agent measures from adb) → signed release →
androidTest smoke suite → decision-4 design doc.
Context: CI shipped this day (`3162bbe`); v0.99.0 evaluation deferred
("NOT adopted", capabilities.md); Hyper real-key turn deferred until the
user funds credits.
