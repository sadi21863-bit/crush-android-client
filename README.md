# Crush Android

A native Android chat client that runs a **hidden local [Crush](https://github.com/charmbracelet/crush) agent** on-device and streams replies over an **app-private Unix-domain socket** (one long-lived SSE stream; never loopback HTTP/TCP — see [Security](#security)).

The Crush engine (a Go binary) ships inside the APK and is `exec()`d at app start. It talks to the provider the user configures in Settings — thirteen Catwalk-registered ids are offered (see [docs/providers.md](docs/providers.md)) — for inference. Everything — Compose UI, engine lifecycle, SSE client — lives in this repo.

```
Compose UI  →  app-private Unix-domain socket  →  libcrush.so (bundled Go binary)  →  OpenCode Zen / other provider
```

Crush ships for macOS, Linux, Windows and BSD. **Android is not one of those platforms** — this repository is an independent client for the bundled engine, not an official Charm project.

## Status

> **Last verified:** `fe9e7e8` (2026-11-08; CI run 42403223894 green). The newest
> rows (Review screen, chat save surface, chat tool policy, Projects) came from
> the 2026-11-07/08 device drives; the full Chat/Code mode work, the S5
> acceptance drive and the gate roadmap live in
> [docs/capabilities.md](docs/capabilities.md) and
> [docs/CRUSH-MOBILE-STATUS-AND-DECISIONS.md](docs/CRUSH-MOBILE-STATUS-AND-DECISIONS.md).

Verified end-to-end on a physical arm64 device (Redmi Note 7 Pro, Android 10 / API 29):

| Capability | State |
|---|---|
| Chat with streamed replies | ✅ verified |
| Multi-message conversations | ✅ verified — 64 messages in one session |
| App lock (PIN / pattern / password) | ✅ verified — cancelling no longer bypasses it |
| Session history, resume, delete | ✅ verified — a failed delete no longer reports success |
| Markdown rendering | ✅ verified — code blocks, inline code, emphasis, lists |
| Live model discovery with probing | ✅ verified — never hardcoded |
| Long-lived workspace SSE | ✅ verified — no ~30s reap between turns |
| Tool-call visibility | ✅ built — wire format CONFIRMED 2026-10-25 (D3): tool_call parts carry data {id, name, input (args as a JSON string), finished (bool)}; parser reads data first, pinned by unit tests |
| File diffs | ✅ built and JVM-tested — a real edited-file turn rendered on device 2026-10-29 (z6/z7: `diff-created(+1/-0)`, diff-edit routes, `snapKeys=[4 files]`, `0 unreadable` after the `readNBytes` API-33 defect was fixed); a multi-file, real-code-edit turn is still untested |
| Permission prompts (ask / always / never) | ✅ ask-mode grant verified end-to-end on device 2026-10-29 (z6/z7: ask card rendered, automated Allow-once granted, turn resumed); always / never modes not device-walked |
| In-app diagnostics + crash reports | ⚠️ implemented — the on-device log path was exercised during the 2026-10-29 D12 pass; the screen/crash-export flow itself is not fully walked |
| Review screen (revert + export) | ✅ device-proven 2026-11-07 ([capabilities.md](docs/capabilities.md) Gate-2): reverting restores the workspace record, and the "Export a copy" no-op found live in the first drive was fixed same-day (`bf5019a`) |
| Chat save/copy surface | ✅ device-proven 2026-11-08 (`fe9e7e8`): code-block Copy/Save chips plus per-message "Copy message" / "Save as Markdown" through the SAF `CreateDocument` picker (`ChatExport.kt`) |
| Chat tool policy (deny all) | ✅ shipped 2026-11-05 (S-01): `EngineConfigInjector.injectChatToolPolicy` writes `options.disabled_tools` and readback-verifies; the `chatPolicyGate` fails closed (`CrushEngine.kt`, pinned by unit tests) |
| Projects mode (import-only) | ✅ built — an imported folder becomes its own keyed engine instance (`crush-<key>.sock`); the runtime registry caps liveness at two live engines, chat + one project (`AppContainer.kt`, `EngineInstanceRegistry.kt`) |

`minSdk 29` · `targetSdk 36` · `compileSdk 37` · arm64-v8a only

**Read the [Known issues](#known-issues) section before judging this.** Several things that look finished are not.

## Build from source

The engine binary is **not committed** (92 MB; see [Fetching the engine](#fetching-the-engine)).

```powershell
# 1. Fetch the engine binary (~92 MB, once)
./tools/fetch-crush.ps1

# 2. Build (JDK 17+ required for CLI builds)
./gradlew :app:assembleDebug

# 3. Install on a physical arm64 device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

An **x86_64 emulator cannot run this app.** The engine is an arm64 ELF binary. Google publishes arm64 system images for API 21–36, but the emulator refuses ARM guests on an x86_64 host:

```
FATAL | Avd's CPU Architecture 'arm64' is not supported by the QEMU2 emulator
 on x86_64 host. System image must match the host architecture.
```

This is why verification is done on real hardware.

## Fetching the engine

`tools/fetch-crush.ps1` downloads the official `Android_arm64` release of Crush
and ELF-verifies it. Place the result at:

```
app/src/main/jniLibs/arm64-v8a/libcrush.so
```

The filename **must** be `lib*.so` or Android will not extract it and `exec()`
fails with `ENOENT`.

## Architecture

### The load-bearing constraints

These are not style preferences. Changing any of them breaks the app.

**1. Never copy the engine to `filesDir` and `setExecutable()`.**
Android 10+ blocks `execve()` from the app home directory (SELinux W^X). The
binary ships in `jniLibs`, is extracted to `nativeLibraryDir` (read-only
`/data/app`, still permitted), and is exec'd from there.

**2. `useLegacyPackaging = true` is required.**
```kotlin
packaging { jniLibs { useLegacyPackaging = true } }
```
With it `false`, the binary stays inside the APK and is never extracted, so
`exec()` fails. Do not "clean this up".

**3. Bind the engine to a Unix-domain socket only — never TCP.**
The engine has **no authentication** and the agent can execute shell commands.
It is launched with `-H unix://<app-private path>/crush.sock`; Android keeps a
filesystem socket owned 0700/0600 in a 0700 directory reachable only by the
app's UID. `tcp://127.0.0.1:$port` (the pre-2026-10 arrangement) was *not*
isolation: loopback is shared by every app on the device. See
[Security](#security) and [docs/p1-attack-surface.md](docs/p1-attack-surface.md).

**4. `/v1/health` returns an empty body.** Use `/v1/version` as the readiness probe.

**5. The engine binds one Unix-domain socket path per instance — never a TCP port.**
Since the 2026-10-18 `unix://` promotion there is no port: each engine
instance binds its own app-private socket (`crush.sock` for the legacy
pool, `crush-chat.sock` for the chat pool, `crush-<key>.sock` for keyed
projects). Never hardcode an absolute path — it is install- and
device-dependent and can differ across engine versions.

**6. Message updates are republished whole, not as deltas.** Replace text; never append.

### Why there is one long-lived SSE connection

Crush reaps a workspace that has **no attached client** after roughly 30 seconds.
Opening a stream per turn therefore guarantees the workspace dies between turns.

That single fact was the root of a long chain of defects in this project: sends
failing with `workspace not found`, delete addressing a dead workspace, a fresh
session minted on every re-attach (so conversations split every third message),
and a `WorkspaceLease` heuristic plus force-retry paths written purely to paper
over it.

The app now holds **one workspace-scoped connection** for the workspace's
lifetime. It also fixes an ordering bug for free: the collector is already
running, so "subscribe before prompting" is trivially true. With per-turn
`callbackFlow`, the reader only started on collection — i.e. *after* the POST —
so an event arriving quickly was silently dropped.

### Model selection is measured, never hardcoded

This is the part most likely to be broken by a future change, so the reasoning
is recorded here.

Zen's `GET /v1/models` is a **public** endpoint — it returns ~86 models with **no
API key at all** — and it still lists retired models. Measured with one real key:

| Model | Result |
|---|---|
| `space-bunny-free` | **HTTP 200** |
| `deepseek-v4-flash-free` | HTTP 400 `Model is unavailable` (retired) |
| 30 other models | HTTP 402 `Model access is disabled` (not in this plan) |
| 53 other models | HTTP 403 `Model access is disabled` |

So being *listed* proves nothing. Concretely, in this project's history:

- A hardcoded model id shipped as a build that failed every turn with
  `agent/init → 500 "large model not found in provider"`.
- Inferring "free" from a `-free` id suffix picked a **retired** model.
  There is no hardcoded default; `CrushSession.DEFAULT_MODEL` is `""` and
  bootstrap refuses to guess.

Instead the app **probes**: it loads real prices from Crush's own provider
catalogue (`cost_per_1m_in`, not a name convention), shortlists zero-cost models
first, then sends a 1-token request to each candidate until one answers 200.

`ModelPrice` models absence as **null, not 0.0**. A model that is free to send
but whose output price is undocumented is *not* known-free — the reply is itself
output, so an assumed zero could turn into a real bill.

The cost is one token per probe. The benefit is that a retired or inaccessible
model can never reach `agent/init`.

## Security

**The engine's HTTP API has no authentication** (no token, origin, or credential
check of any kind). Crush grants shell execution, its `crush.json` stores the
provider key in **plaintext** (Crush's own design), and
`spec/crush-openapi.json` declares **no security scheme of any kind**.

What makes that survivable is the transport. Since 2026-10-18 the engine is
launched with `-H unix://<filesDir>/crush.sock` — a filesystem socket inside the
app-private directory, `srwx------`, in a 0700 directory, both owned by the app
UID. Linux `connect()` on an AF_UNIX inode requires MAY_WRITE on that inode plus
traversal through the directory chain, which the kernel denies to every other
UID: sibling apps, `adb shell` (uid 2000), and adbd included. The previous
`tcp://127.0.0.1:$port` bind offered no such boundary — on-device,
`GET /v1/version` and `GET /v1/workspaces` returned `HTTP 200` to **a different
app UID** with no credentials, and `GET /v1/workspaces/{id}/config`
**returned the plaintext provider key** (both recorded in
[docs/p1-attack-surface.md](docs/p1-attack-surface.md) §4/§8).

The root-kit caveat is honest: root/adbd with `CAP_DAC_OVERRIDE` can still read
the socket. That is a root-equivalent threat, outside the sibling-app model.

Upstream has been asked for a documented `unix://` scheme plus optional token
auth for TCP: [charmbracelet/crush#4092](https://github.com/charmbracelet/crush/issues/4092).

App-side mitigations:

- API key encrypted with **AES-256/GCM**; master key in the **Android Keystore**.
  Each provider's key is encrypted into **its own slot** (`ct.<providerId>`), so
  holding a key for several providers is supported and one never overwrites
  another. This protects keys at rest in the app; the running engine still holds
  a plaintext copy by its own design.
- The key is **not** auth-gated at the Keystore layer, so `KeySession.require()`
  must never mark the session unlocked — doing so is a full authentication
  bypass, since `isUnlocked` is what the lock gate trusts. Only the
  post-authentication path may set it.
- App lock uses the platform credential screen (`KeyguardManager`) rather than
  `androidx.biometric`, which requires an AppCompat theme and crashed onboarding
  on an API 30+ device.
- Backups disabled: `allowBackup=false` plus `data_extraction_rules.xml`.
- The workspace is app-private (`filesDir/workspace`), so a local attacker cannot
  drop a file in it through another app's storage.
- **Debug builds are not shareable.** `debuggable=true` lets anyone with adb run
  `run-as` and read `crush.json`, which holds the plaintext key. Only sign and
  share release builds.

**If you received a shared build of this app, rotate the provider key(s) you loaded into it.**

## Known issues

- **ASK-mode ask cards: two defects, both fixed and re-verified live on device (D12 closed 2026-10-29).**
  Captured live 2026-10-25 on engine v0.98.1: the engine never published
  permission asks at all (that half needed the v0.98.2 engine). On
  2026-10-27 an instrumented v0.98.2 engine proved the ask WAS published,
  session-filtered, and written to the live SSE stream — yet the app showed
  no card and no "permission requested" log. The second defect was the
  app's `CrushEventRouter`: events with no run id (`permission_request`,
  `question_batch_request`, and every `message` frame) went only to
  catch-all channels, and nothing in the app subscribes to catch-alls, so
  the frames were silently dropped. The fix fans anonymous events to every
  registered run (i.e. the in-flight turn) and is pinned by unit tests.
  Side effect of the same bug now fixed: streaming text renders live
  instead of appearing only when the turn ends. The 2026-10-29 device pass
  closed the loop with a full ask-then-grant turn (z6/z7: ask card rendered,
  automated Allow-once granted, file written, turn resumed; evidence
  `build/d12-evidence/`), and the `question_batch_request` handler shipped
  in `3235f7e`. A parked prompt is bounded by `PROMPT_WAIT_MS` = 10 minutes
  (docs/decisions-open.md item 8). See docs/project-analysis.md D12.
- **Tool-call wire format — CONFIRMED (D3, 2026-10-25).** `tool_call` parts
  arrive as `{"type":"tool_call","data":{"id","name","input","finished"}}`,
  where `input` is the tool arguments as a JSON string and `finished` is a
  boolean (absent while streaming). The parser reads `data` first, claims
  COMPLETED only on `finished:true`, and mines `file_path` from `input`; unit
  tests pin the captured shapes. See docs/capabilities.md.
- **Engine API has no HTTP-level authentication.** See [Security](#security).
  Not fixable in the app layer: the engine offers no auth to enable. The
  transport, however, is now a kernel-enforced app-private Unix-domain socket,
  so cross-app reach — the actual exploit path — is closed.
- **The sibling-app attack surface is closed by architecture (2026-10-18).**
  The old loopback-TCP arrangement (any UID, any port) where the per-launch
  EngineGate was planned no longer exists; the engine binds only
  `unix://<app-private path>`. The gate itself was never built — superseded.
  Evidence: [docs/p1-attack-surface.md](docs/p1-attack-surface.md) §4/§8,
  decision record [docs/p1-gate-design.md](docs/p1-gate-design.md).
- **Delete's 404 is ambiguous.** A 404 on a workspace the ~30s reap has already
  taken cannot be told apart from a 404 on the session itself. The flow
  re-attaches first and force-retries once, but "session gone" is still inferred
  rather than confirmed, so a stale `AlreadyGone` remains possible.
- **No tool-call timeline or per-turn cost.** Tool activity is a per-message
  summary, not a live view of what the agent is doing.
- **File diffs: the real-turn prong is now checked (2026-10-29).** The whole
  path is built and JVM-tested (snapshot → tracked files → diff → render),
  and the first measured device turn tracked zero files. The 10-29 pass then
  surfaced a worse miss: `WorkspaceSnapshotter.looksBinary()` used the
  Java-11 `readNBytes`, so on the API 29 device EVERY non-empty file captured
  as binary — no diff content ever stored, and the card reported
  `N file(s) not shown (unreadable…)` for healthy files (deep-analysis-2026-10-27.md
  §9 Defect B). After the portable 512-byte read fix, verification turn z7
  closed the loop: `diff-created(+1/-0)`, diff-edit routes on the earlier
  files, `snapKeys=[4 files]`, `0 unreadable`. Still untested: a multi-file
  turn editing real code.
- **A slow turn still strands a bubble.** `streamSend`'s `finally` now finalises
  the live bubble, but a turn whose provider simply never replies leaves an
  empty bubble marked "no reply received". That is honest. A FAILED turn
  (finish `reason:"error"`) now shows the provider's own wording instead —
  including nested-object `details`, which previously crashed the stream
  update and masked the server's message.
- **First-launch app lock bypass.** On a *fresh* install the first session opens
  without a lock prompt, because there is no stored key at that moment. Later
  cold starts do prompt.
- **Diagnostics/crash-export is still under-verified on device; permission
  ask mode is verified.** The ask-mode prompt was verified end-to-end on
  2026-10-29 (Status table, D12), but the always/never permission modes and
  the diagnostics screen/crash-report export have not been device-walked
  (the log path itself was exercised during that pass).
- **The `FALLBACK_PROBE_MODEL` literal was removed.** The early agent init now
  runs only when a model is actually chosen; with none selected, no retired
  hardcoded id is fed to the provider any more (that guess was the "large model
  not found in provider" a friend hit on a fresh install). Discovery-first
  bootstrap probes a live id before the app reports ready.
- **Multi-provider plumbing is in; three providers verified to varying degrees.** See [docs/providers.md](docs/providers.md). Thirteen Catwalk ids are offered and the provider is chosen in Settings; `opencode-zen` and `openrouter` have completed handshakes on a device, `groq` was probe-verified but is parked as a test target after a key rotation - everything else is labelled unverified rather than claimed working.
- **OAuth is not implemented.** Crush exposes /config/refresh-oauth and a Copilot import; the app calls neither. The endpoint that *initiates* an OAuth flow is not in the spec at all, so the redirect capture would have to be built from scratch.
- **`WorkspaceLease` and the force-retry paths are NOT dead scaffolding.** An
  earlier revision of this README called them redundant relics of the
  per-turn-connection era. Re-checked 2026-10: they are the path the send loop
  takes whenever the shared connection is **not** `CONNECTED`. Live at
  `WorkspaceManager.kt:132` and in the ChatViewModel send loop.
- **Instrumented suite — landed and verified ON DEVICE (2026-11-01).** The
  one-time gap ("no `androidTest` instrumentation suite"; the app-lock flow
  covered only by a JVM policy suite while the real credential + Keystore path
  ran on nothing) is closed: **8 `androidTest` tests OK on device** in 326 s
  via `am instrument` — `MainActivityLockFlowTest` (6: gate↔FLAG_SECURE
  contract, recreation-survival of the process-scoped lock state,
  fresh-process-holds-no-decrypted-key, and a **real AndroidKeyStore**
  roundtrip with per-provider isolation + tamper-clears, which the JVM suite's
  stand-in cannot reach) plus the rebuilt `MainActivitySmokeTest` (2).
  Throwaway provider slots are cleaned afterwards (0 residue in
  `secure_api_keys.xml`). The CI gate now also compiles the test APK
  (`:app:assembleDebugAndroidTest`) so test-code breakage cannot ship. Honest
  scope, documented in-file: the tests do not answer real biometric prompts —
  those unlock paths carry standing hands-on evidence instead. Evidence:
  [docs/external-review-triage.md](docs/external-review-triage.md)
  ("App-lock flow verified only manually" row, closed),
  [docs/capabilities.md](docs/capabilities.md).
- **Store/R8/keystore decisions: largely landed; residue still parked.** The
  parking item's two "never shipped" clauses are now closed: lint is green
  since 2026-10-31 (`5325d68` — re-enabled with waivers, fresh release lint
  "No issues found."), and signed release-R8 ran on device 2026-11-02 — the
  `r8probe` signed minified buildType (release signing + R8) completed a
  real streaming turn with a 28.92 MB APK (triage row "R8 minified release:
  runtime unproven" — closed). Still parked: a plaintext test key file sits
  at the repo root (decisions-open item 3), and a few other human-gated
  trades are catalogued with their evidence in
  [docs/decisions-open.md](docs/decisions-open.md) — whose own "keystore +
  credentials.properties are missing" note (item 2, OPEN) now predates the
  evidence above.

## Toolchain

| Component | Version |
|---|---|
| Gradle | 9.8.0 |
| AGP | 9.4.1 |
| Kotlin (KGP) | **2.4.10** — project-owned via `android.builtInKotlin=false` |
| Compose compiler plugin | 2.4.20 — versioned independently, tracks the 2.4 line |
| Compose BOM | 2026.09.00 |
| Build JDK | 25.0.3 (Android Studio's bundled JBR) |
| Bytecode target | 17 (deliberate) |

The KGP/Compose-compiler split is intentional and load-bearing, not an accident:
`android.builtInKotlin=false` in `gradle.properties` means the project owns the
Kotlin version. Both opt-outs exist only to allow that, and AGP 10 removes both.
See [`docs/toolchain.md`](docs/toolchain.md) for the contingency plan.

> An external audit flagged the 2.4.10 / 2.4.20 split as a compatibility
> hazard and suggested matching them. That is worth considering, but the build
> is green across 594 JVM unit tests and repeated release builds, so it is a
> real risk to weigh rather than a demonstrated fault. Aligning to 2.4.20 is
> the safer change if made deliberately.

AGP 9 migration notes that silently broke the `exec()` architecture are in
[`app/build.gradle.kts`](app/build.gradle.kts).

Upstream pins: the engine of record is **Crush v0.99.0** since the
2026-11-01 promotion (`0.98.2+dirty` instrumented build retired to the
z-evidence archive; v0.99.0 is the upstream clean release, published
2026-10-29). **`tools/fetch-crush.ps1` defaults to 0.99.0** with its SHA-256
pin from the signed release's checksums.txt; the earlier 0.98.x pins remain
for historical fetches. Two facts carried into the promotion from the
v099-experiment lane (that branch, commits `55dae84` back to `d326e60`,
holds the device evidence): OpenCode Go model access REQUIRES this engine
(the `x-opencode-session` header exists only from 0.99.0), and the model
layer gained plan-split bases for the OpenCode/Console family (see
`domain/provider/Providers.kt`). Earlier pins for the record: 0.98.2 was the
2026-10-27 instrumented upgrade whose D12 ask-delivery evidence (z6/z7
sessions) was gathered on-device with it; 0.98.1 was the 2026-10-18 upgrade
for the `unix://` listen scheme; the older 0.97.1 binary is backed up under
`engine-backups/`. A `nightly` tag also exists and is not used. **Bubble Tea
is not used** — it is Crush's terminal UI framework, compiled into the
binary but unreachable from Compose.

## Testing

```powershell
./gradlew :app:testDebugUnitTest :app:assembleRelease   # 594 JVM unit tests (594 @Test methods across 57 files, grep-counted 2026-11-08)
```

Coverage is broad for pure logic — protocol details, model probing and pricing,
session continuity, Markdown, permission modes, tuning policy, lock policy,
engine compatibility, line diffing, workspace snapshotting, and the real
bootstrap HTTP order against a path-keyed `MockWebServer`.

The suites worth naming, because each pins a defect a green build had hidden:

| Suite | What it pins |
|---|---|
| `DeleteOutcomeTest` | a dropped socket is **not** a successful delete |
| `CrushIoThreadingTest` | the request actually reaches the server |
| `KeySessionLockTest` | `require()` never unlocks the app |
| `SupervisorHysteresisTest` | the **caller's** calm-run contract, where the off-by-one lived |
| `LineDifferTest` | line numbers point at the line they describe |
| `FileChangeCollectorTest` | a real before-image, on a real temp filesystem |
| `RotateKeepingTailTest` | the newest log lines survive rotation |
| `AppLockPolicyTest` | cancel never becomes an unlock |
| `SecureKeyStoreProviderScopeTest` | one credential slot per provider — a second key never destroys the first |
| `RunHandleTest` | a stop during preflight suppresses the POST instead of cancelling the previous run |
| `ProviderSwitchPolicyTest` | a provider switch during a run defers to run exit instead of killing the reply |
| `LockStateTest` | the lock gate's timestamps survive the OS recreating the activity mid-unlock |

**Robolectric cannot test the things that actually break.** The real Keystore
cipher (Robolectric ships no AndroidKeyStore provider — the vault suite swaps in
a reversible stand-in to reach the slot layout), `WorkspaceManager.reattachIfStale`,
`ChatViewModel.send`, and the child-process engine have no automated coverage —
every real crash in this project lived in
exactly that gap. Two of them (`NetworkOnMainThreadException` in delete,
`newSession` on every re-attach) were invisible to unit tests and only surfaced
on a device.

Device scripts:

```powershell
./tools/chaos.ps1        # Layer 1: engine lifecycle via adb (S01–S10)
./tools/chaos-ui.ps1     # Layer 2: UI + Keystore (S16–S21)
```

## Continuous integration

`.github/workflows/android-ci.yml` runs the same gate on every push and
pull request:
`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintRelease`
(`:app:assembleDebugAndroidTest` was added 2026-11-01 with the instrumented
suite, so test-code breakage cannot ship).
The engine binary is gitignored, so a CI checkout builds a gate APK
**without the embedded engine** — CI proves the code compiles and all
JVM suites pass; runtime behavior still needs real arm64 hardware (see
[Build from source](#build-from-source)).

**Provenance manifest (MODE-25).** Every run — success or failure — attaches
a provenance manifest (also a downloadable workflow artifact) that reports
which certification profile the run represents, measured from the checkout
itself: `engine_blob PRESENT/ABSENT`, the debug APK's sha256 and size, and a
plain-language `certifies` row saying what a green run proves (compile + JVM
suites + lint) and what it does not (on-device engine behavior). The device
gate evidence — real arm64 hardware — is recorded in
[docs/capabilities.md](docs/capabilities.md) and the docs ledger.

**First green recorded 2026-11-02.** The bring-up day's pending item closed on
2026-11-02, and the gate has stayed green: monitor runs 12–20 completed on
2026-11-02, latest success at the time of writing on commit `bb76e49`. One
flake is on record, not on code: run 16 exited 1 in the unit-test step on code
that passed identically in runs 18–20 (re-trigger documented in the commit
message of `95b2ebf`). Locally, the same day produced three green full-gate
runs — 47m57s, 32m36s and 26m11s (machine-local tee logs `gate-zen-copy`,
`gate-egress2`, `gate-sync`; the first two also built the signed R8 probe
artifact via `:app:assembleR8probe`).

Bring-up facts (measured 2026-11-01, first green recorded 2026-11-02):

- **Runner SDK**: newer `ubuntu-latest` images no longer ship an Android
  SDK, and `android-actions/setup-android@v3` failed on the migrated
  image. The workflow provisions directly from Google's
  `commandlinetools` zip, accepts licenses, installs `platform-tools`,
  and writes `sdk.dir` into a generated `local.properties`.
- **Log access**: the job-log endpoint needs repo admin (measured HTTP
  403 with the push credential), so the failing gate step prints the
  head/tail of the gradle output as `::error::` check-run annotations —
  publicly readable without any log download.
- **Wrapper defect CI caught**: the committed unix `gradlew` invoked
  `java -jar gradle-wrapper.jar`, but the Gradle 9.x wrapper jar carries
  no `Main-Class` manifest attribute — instant
  `no main manifest attribute` exit 1 on Linux. The local lane never
  exercised it (Windows runs `gradlew.bat`, which uses the classpath
  form). Fix: regenerate the wrapper scripts from the tool itself.

## Docs

| Document | Contents |
|---|---|
| [`docs/capabilities.md`](docs/capabilities.md) | What works vs. what does not, per feature |
| [`docs/CRUSH-MOBILE-STATUS-AND-DECISIONS.md`](docs/CRUSH-MOBILE-STATUS-AND-DECISIONS.md) | Where the work stands now: commit chain, gate roadmap, agreed decisions, risk register |
| [`docs/chat-code-modes-plan-2026-11-04.md`](docs/chat-code-modes-plan-2026-11-04.md) | Chat/Code mode split: decisions, shipped slices, verification matrix |
| [`docs/toolchain.md`](docs/toolchain.md) | Versions, AGP 10 contingency, backup plan |
| [`docs/project-analysis.md`](docs/project-analysis.md) | Architecture, known defects, capability plan |
| [`docs/p1-attack-surface.md`](docs/p1-attack-surface.md) | The mapped sibling-app surface and the UDS mitigation evidence |
| [`docs/external-review-triage.md`](docs/external-review-triage.md) | Every external finding, verified/fixed/open, with evidence |
| [`docs/providers.md`](docs/providers.md) | Provider ids, adding one, and why OAuth is absent |
| [`docs/dynamic-autotuning.md`](docs/dynamic-autotuning.md) | Thermal/memory tuning design and results |
| [`docs/baseline-2026-10-16.md`](docs/baseline-2026-10-16.md) | What the pre-release checklist verified, measured, and did **not** |
| [`docs/ui-spec.md`](docs/ui-spec.md) | UI/UX spec and roadmap |
| [`docs/termux-project-plan.md`](docs/termux-project-plan.md) | Separate 32-bit / real-dev-environment project |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | Attribution convention, build and test rules |

## Licence and attribution

**Crush is licensed under FSL-1.1-MIT, not MIT.** See
[charmbracelet/crush LICENSE.md](https://github.com/charmbracelet/crush/blob/main/LICENSE.md).
This app redistributes the Crush binary, so its terms apply directly.

FSL-1.1-MIT grants use and redistribution for permitted purposes, requires that
redistributions retain and link the licence and copyright notices, restricts
competing commercial use, and **≥ converts each version to MIT on the second
anniversary of its availability**. The bundled build is now **v0.99.0** (the
upstream clean release, published 2026-10-29; promoted to this repo's engine
of record 2026-11-01 — see the Upstream pins note in Toolchain), so its
future-MIT date computes to two years from publication; confirm it against
upstream before relying on MIT terms for that version. The backup of the
retired v0.97.1 binary under `engine-backups/` has its own future-MIT date.

> An earlier revision of this README stated "Crush is MIT-licensed". That was
> wrong. Verify licence claims against upstream before repeating them.

The licence gap that this section used to record is closed. The repo root now
carries both files (added 2026-11-02, commit `0741e77`):

- **[`LICENSE`](LICENSE) — MIT, © 2025-2026 sadi21863-bit (Crush Mobile).**
  It covers THIS REPOSITORY'S code: the Kotlin app, its UI, its tests and its
  build scripts.
- **[`NOTICE.md`](NOTICE.md) — the third-party attribution an APK build must
  ship alongside the app.** The embedded engine `libcrush.so` is
  **FSL-1.1-MIT** (Copyright 2025-2026 Charmbracelet, Inc., terms linked there),
  the OpenCode-derived segment inside the engine lineage carries its MIT text
  verbatim, and the runtime dependencies surfaced in the shipped APK are
  tabled (AndroidX/Compose, OkHttp + OkHttp-SSE, junixsocket, Gson,
  kotlinx-coroutines — all Apache-2.0; test-only deps ship nowhere).

One caveat survives the paperwork: legal review of FSL "Competing Use" is
still recommended before any commercial distribution of the bundled engine.

Bubble Tea is MIT-licensed. This repository is not affiliated with Charm or
OpenCode Zen. You supply your own provider API keys; they are never committed.

## Tree-verification pass 2026-10-31

Spot-checked against the current tree; items not listed above were found accurate:

- All 17 named test suites exist, including `RotateKeepingTailTest`,
  `RetentionPruneTest`, `SecretMaskingTest` (top-level classes inside
  `AppLogTest.kt`); `ChatStateMergeTest` added 2026-10-31 (`ce43c7a`).
  Instrumentation suite note: this October-31 pass said none existed; the
  suite since landed and runs on device - see the `androidTest` section
  above (8 tests OK, 2026-11-01).
- `minSdk 29` / `targetSdk 36` / `compileSdk 37` (`app/build.gradle.kts:58/59/44`;
  anchors re-checked 2026-11-08 — they were 54/55/40 when this pass ran),
  `useLegacyPackaging = true`, `android.builtInKotlin=false`
  (`gradle.properties:42`), Kotlin 2.4.10 / Compose-compiler 2.4.20 / BOM
  2026.09.00, Gradle wrapper 9.8.0 all re-confirmed.
- `CrushSession.DEFAULT_MODEL` is still `""`; `Providers.ALL` offers exactly
  13 Catwalk ids; no `FALLBACK_PROBE_MODEL` literal anywhere in the tree.
- `WorkspaceManager.kt:132` (least-recent-attach field) and the D12 fan-out +
  `mergeReplay`/`modelSelection` fixes in `ChatViewModel.kt` (`ce43c7a`),
  the D13 error card ("This reply failed" + Retry, `0dca731`), and the
  a67aee5 UI batch ("Unlock Crush Mobile", "Open chats list", save spinner,
  StatusRow spoken merge) all verified present.
- D11 commit `9498a67`, baseline commit `4e67aae…` and tag
  `baseline/2026-10-17` verified. `WorkspaceLease`/force-retry still live.
- Not run this session: any gradle command (forbidden here), so 455 is a
  structural `@Test` count, not a runner count. (Re-checked 2026-11-08 the same
  way, still no gradle: the grep count is now **594** across 57 files.)

- Engine of record updated later in the day: the bundled build is now
  **v0.99.0** (promoted 2026-11-01, published 2026-10-29); see the Upstream
  pins note in Toolchain for the pin and the engine-of-record trail.