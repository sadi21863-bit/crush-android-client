# Crush Android

A native Android chat client that runs a **hidden local [Crush](https://github.com/charmbracelet/crush) agent** on-device and streams replies over loopback HTTP/SSE.

The Crush engine (a Go binary) ships inside the APK and is `exec()`d at app start. It talks to **OpenCode Zen** for inference. Everything — Compose UI, engine lifecycle, SSE client — lives in this repo.

```
Compose UI  →  http://127.0.0.1:<ephemeral>/v1  →  libcrush.so (bundled Go binary)  →  OpenCode Zen
```

## Status

Working and verified end-to-end on a physical device (Redmi Note 7 Pro, Android 10 / API 29):

| Feature | State |
|---|---|
| Chat with streaming replies | ✅ verified on device |
| Multiple messages per session | ✅ verified — the historical LazyColumn-key crash is fixed |
| Session history, reopen, delete | ⚠️ partially verified — see [Known issues](#known-issues) |
| Resumes last session on launch | ✅ fixed, verified |
| Live model discovery + probing | ✅ verified — `probed N candidates, using space-bunny-free` |
| App lock (PIN / pattern / password) | ✅ fixed and verified — cancelling no longer bypasses it |
| Permission prompts (ask / always / never) | ✅ implemented |
| In-app diagnostics + crash reports | ✅ implemented |
| Thermal / memory-aware tuning | ✅ implemented |
| **arm64 devices only** | by design — see [Device support](#device-support) |

`minSdk 29` · `targetSdk 36` · `compileSdk 37` · arm64-v8a

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

An **x86_64 emulator cannot run this app** — the engine is an arm64 ELF binary. Google publishes arm64 system images for API 21–36, but the emulator rejects ARM guests on an x86_64 host:

```
FATAL | Avd's CPU Architecture 'arm64' is not supported by the QEMU2 emulator
 on x86_64 host. System image must match the host architecture.
```

This is why verification is done on real hardware.

## Fetching the engine

`tools/fetch-crush.ps1` downloads the official `Android_arm64` release of Crush
and ELF-verifies it (static PIE, AArch64, no `DT_NEEDED` entries, no `libc.so`
references, 64 KB segment alignment for Android 15+).

Place the result at:

```
app/src/main/jniLibs/arm64-v8a/libcrush.so
```

The filename **must** be `lib*.so` or Android will not extract it and `exec()`
fails with `ENOENT`.

## The load-bearing constraints

These are not style preferences. Changing any of them breaks the app.

**1. Never copy the engine to `filesDir` and `setExecutable()`.**
Android 10+ blocks `execve()` from the app home directory (SELinux W^X). The
binary ships in `jniLibs`, is extracted to `nativeLibraryDir` (read-only
`/data/app`, still permitted), and is exec'd from there.

**2. `useLegacyPackaging = true` is required.**
```kotlin
packaging { jniLibs { useLegacyPackaging = true } }
```
With it set to `false` the binary stays inside the APK and is never extracted,
so `exec()` fails. Do not "clean this up".

**3. Bind the engine to `127.0.0.1` only.**
Crush has **no authentication** and the agent can execute shell commands.
Binding `0.0.0.0` would expose that to your entire LAN.

**4. `/v1/health` returns an empty body.** Use `/v1/version` as the readiness probe.

**5. The port is ephemeral and changes every boot.** Never hardcode it.

**6. Message updates are republished whole, not as deltas.** Replace text; never append.

**7. Workspaces are reaped after ~30s** unless a client is attached. The workspace
*id* comes back for the same path, but sessions live in the engine process and
must be re-listed after a re-attach. `WorkspaceLease` handles this.

## Model selection is measured, never hardcoded

This is the part most likely to be broken by a future change, so the reasoning is
recorded here.

Zen's `GET /v1/models` is a **public** endpoint — it returns ~86 models with **no
API key at all** — and it still lists models that are retired. Measured with one
real key:

| Model | Result |
|---|---|
| `space-bunny-free` | **HTTP 200** |
| `deepseek-v4-flash-free` | HTTP 400 `Model is unavailable` (retired) |
| 30 other models | HTTP 402 `Model access is disabled` (not in this plan) |
| 53 other models | HTTP 403 `Model access is disabled` |

So being *listed* proves nothing. Concretely:

- A hardcoded model id caused a shipped build to fail every turn with
  `agent/init → 500 "large model not found in provider"`.
- Inferring "free" from a `-free` id suffix picked a **retired** model. There is
  no such thing as a hardcoded default here; `CrushSession.DEFAULT_MODEL` is `""`
  and the bootstrap refuses to guess.

Instead the app **probes**: it loads real prices from Crush's own provider
catalogue (`cost_per_1m_in`, not a name convention), shortlists zero-cost models
first, then sends a 1-token request to each candidate until one answers 200. The
first model that actually responds is what gets configured.

The cost is one token per probe. The benefit is that a retired or inaccessible
model can never reach `agent/init`.

## API key handling

- Encrypted with **AES-256-GCM**; the master key lives in the **Android Keystore**.
- The key is **not** auth-gated at the Keystore layer, so `KeySession.require()`
  must never mark the session unlocked — doing so is a full authentication
  bypass, since `isUnlocked` is what the lock gate trusts. Only the post-
  authentication path may set it.
- The app lock uses the platform credential screen (`KeyguardManager`) rather than
  `androidx.biometric`, which requires an AppCompat theme and crashed onboarding
  on an API 30+ device.
- Crush's own `crush.json` stores the key in **plaintext** (its design). It is
  app-private and backup-excluded, but it is a real exposure if the device is
  compromised.
- Backups are off: `allowBackup=false` plus `data_extraction_rules.xml`.

## Device support

**arm64-v8a only**, API 29+. On any other architecture the app shows an explicit
unsupported-device screen rather than failing obscurely.

Upstream Crush does not publish an `android/386` or `android/arm` build. For a
real development environment on 32-bit devices, see
[`docs/termux-project-plan.md`](docs/termux-project-plan.md) — a separate app
based on Termux, not a change to this one.

## Known issues

Honest list of what is **not** finished:

- **Delete is not yet confirmed working on device.** The workspace reaping
  behaviour means a delete issued minutes after the last send addresses a
  workspace Crush has already dropped. The code now re-opens with `force = true`
  and retries, and logs the exception *type* (the previous log printed only
  `null`, which hid the cause for several rounds) — but this has not been
  re-verified since the change.
- **Permission mode and diagnostics** are implemented but have not been
  exercised on a real device.
- **No Markdown or code-block rendering.** Assistant output is plain text, so
  code responses lose their formatting.
- **Tool calls are parsed but not rendered.** No timeline or diff view.
- **Single provider.** Only `opencode-zen` is wired up, though the engine
  advertises 42 providers.
- **First-launch app lock bypass.** On a *fresh* install the first session opens
  without a lock prompt, because there is no stored key at that moment. Later
  cold starts do prompt.
- **Session resume is not perfect.** Empty sessions created by older builds are
  still in some users' histories.
- **The 300-second Keystore auth window** is unrelated to the app lock now that
  the key is cached in memory per unlock, but it is easy to confuse when reading
  the code.

## Toolchain

| Component | Version |
|---|---|
| Gradle | 9.8.0 |
| AGP | 9.4.1 |
| Kotlin | 2.2.10 (supplied by AGP 9 — do **not** apply `kotlin.android`) |
| Compose BOM | 2026.09.00 |
| Build JDK | 25.0.3 (Android Studio's bundled JBR) |
| Bytecode target | 17 (deliberate) |

AGP 9 migration notes that silently broke the `exec()` architecture are in
[`README.md`](docs/toolchain.md) and in `app/build.gradle.kts`.

## Tests

```powershell
./gradlew :app:testDebugUnitTest
```

142 JVM unit tests. They cover protocol details, model probing and pricing,
permission modes, tuning policy, device profiles, lock policy, session
labelling, engine compatibility, and the real bootstrap HTTP order against a
path-keyed `MockWebServer`.

**Robolectric cannot test the things that actually break.** `SecureKeyStore`,
`KeySession`, `WorkspaceManager.reattachIfStale`, `ChatViewModel.send`, and the
child-process engine have no automated coverage — all three real crashes lived in
exactly that gap. Those need on-device testing:

```powershell
./tools/chaos.ps1        # Layer 1: engine lifecycle via adb (S01–S10)
./tools/chaos-ui.ps1     # Layer 2: UI + Keystore (S16–S21)
```

There is no `androidTest` instrumentation suite yet.

## Docs

| Document | Contents |
|---|---|
| [`docs/toolchain.md`](docs/toolchain.md) | Versions, AGP 10 contingency, backup plan |
| [`docs/project-analysis.md`](docs/project-analysis.md) | Scorecard, bugs, capability plan |
| [`docs/capabilities.md`](docs/capabilities.md) | What works vs. what does not |
| [`docs/dynamic-autotuning.md`](docs/dynamic-autotuning.md) | Tuning design and test plan |
| [`docs/ui-spec.md`](docs/ui-spec.md) | UI/UX spec and roadmap |
| [`docs/termux-project-plan.md`](docs/termux-project-plan.md) | Separate 32-bit / real-dev-env project |

## Licence and attribution

Crush is MIT-licensed, © Charm. This repository is not affiliated with Charm or
OpenCode Zen. You supply your own OpenCode Zen API key; it is never committed.
