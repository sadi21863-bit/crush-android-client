# Crush Android

A native Android chat client that runs a **hidden local [Crush](https://github.com/charmbracelet/crush) agent** on-device and streams replies over loopback HTTP/SSE.

The Crush engine (a Go binary) ships inside the APK and is `exec()`d at app start. It talks to **OpenCode Zen** for inference. Everything — Compose UI, engine lifecycle, SSE client — lives in this repo.

```
Compose UI  →  http://127.0.0.1:<ephemeral>/v1  →  libcrush.so (bundled Go binary)  →  OpenCode Zen
```

Crush ships for macOS, Linux, Windows and BSD. **Android is not one of those platforms** — this repository is an independent client for the bundled engine, not an official Charm project.

## Status

Verified end-to-end on a physical arm64 device (Redmi Note 7 Pro, Android 10 / API 29):

| Capability | State |
|---|---|
| Chat with streamed replies | ✅ verified |
| Multi-message conversations | ✅ verified — 64 messages in one session |
| App lock (PIN / pattern / password) | ✅ verified — cancelling no longer bypasses it |
| Session history, resume, delete | ✅ verified |
| Markdown rendering | ✅ verified — code blocks, inline code, emphasis, lists |
| Live model discovery with probing | ✅ verified — never hardcoded |
| Long-lived workspace SSE | ✅ verified — no ~30s reap between turns |
| Tool-call visibility | ⚠️ built; engine wire format still unconfirmed |
| File diffs | ⬜ not started |
| Permission prompts (ask / always / never) | ⚠️ implemented, not device-tested |
| In-app diagnostics + crash reports | ⚠️ implemented, not device-tested |

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

**3. Bind the engine to `127.0.0.1` only.**
Crush has **no authentication** and the agent can execute shell commands.
Binding `0.0.0.0` would expose that to the whole LAN. See
[Security](#security) for why loopback is not the end of the story.

**4. `/v1/health` returns an empty body.** Use `/v1/version` as the readiness probe.

**5. The port is ephemeral and changes every boot.** Never hardcode it.

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

**The engine's API is unauthenticated.** Crush grants shell execution and its
`crush.json` stores the provider key in **plaintext** (Crush's own design).

Confirmed on-device: `GET /v1/version` and `GET /v1/workspaces` both return
`HTTP 200` to a **different app UID** (`adb shell`, uid 2000) with no credentials.
Android's loopback is shared by every app on the device, so it is not an
isolation boundary.

**Not confirmed:** command execution and key exfiltration. Creating a workspace
requires a registered `client_id`, and an unregistered one is refused, so no
shell command could be attached during testing. The exposure is real; total
compromise is unproven. Treat it as a known limitation rather than a resolved
item.

App-side mitigations:

- API key encrypted with **AES-256/GCM**; master key in the **Android Keystore**.
- The key is **not** auth-gated at the Keystore layer, so `KeySession.require()`
  must never mark the session unlocked — doing so is a full authentication
  bypass, since `isUnlocked` is what the lock gate trusts. Only the
  post-authentication path may set it.
- App lock uses the platform credential screen (`KeyguardManager`) rather than
  `androidx.biometric`, which requires an AppCompat theme and crashed onboarding
  on an API 30+ device.
- Backups disabled: `allowBackup=false` plus `data_extraction_rules.xml`.
- **Debug builds are not shareable.** `debuggable=true` lets anyone with adb run
  `run-as` and read `crush.json`, which holds the plaintext key. Only sign and
  share release builds.

**If you received a shared build of this app, rotate your OpenCode Zen key.**

## Known issues

- **Tool-call wire format is unconfirmed.** The agent's tool activity is parsed
  and rendered, but the engine's exact field names were not verified against a
  real tool-using turn. The parser reads each field through a list of candidate
  names and degrades to `null` rather than guessing, and logs the keys it
  observes so the names can be corrected without guessing again.
- **Delete treats transport failures as success.** A timeout or dropped
  connection is reported as "deleted" on the grounds that a session cannot
  outlive its workspace. Sessions are in fact persistent engine state, so that
  reasoning is wrong and a failed delete can reappear.
- **No file diffs.** When the agent writes a file, the user sees prose describing
  it, not the change. This is the largest remaining gap against a real coding
  agent.
- **No tool-call timeline or per-turn cost.** Tool activity is a per-message
  summary, not a live view of what the agent is doing.
- **First-launch app lock bypass.** On a *fresh* install the first session opens
  without a lock prompt, because there is no stored key at that moment. Later
  cold starts do prompt.
- **Permission prompts and diagnostics are unverified on device.**
- **Single provider.** Only `opencode-zen` is wired up.
- **`WorkspaceLease` and the force-retry paths are now redundant.** They predate
  the long-lived connection and remain as dead scaffolding.
- **No `androidTest` instrumentation suite.** See [Testing](#testing).

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
> is green across 213 tests and repeated release builds, so it is a real risk to
> weigh rather than a demonstrated fault. Aligning to 2.4.20 is the safer change
> if made deliberately.

AGP 9 migration notes that silently broke the `exec()` architecture are in
[`app/build.gradle.kts`](app/build.gradle.kts).

Upstream pins: **Crush v0.97.1** (latest stable as of this writing; a `nightly`
tag also exists and is not used), **Bubble Tea is not used** — it is Crush's
terminal UI framework, compiled into the binary but unreachable from Compose.

## Testing

```powershell
./gradlew :app:testDebugUnitTest     # 213 JVM unit tests
```

Coverage is broad for pure logic — protocol details, model probing and pricing,
session continuity, Markdown, permission modes, tuning policy, lock policy,
engine compatibility, and the real bootstrap HTTP order against a path-keyed
`MockWebServer`.

**Robolectric cannot test the things that actually break.** `SecureKeyStore`,
`WorkspaceManager.reattachIfStale`, `ChatViewModel.send`, and the child-process
engine have no automated coverage — every real crash in this project lived in
exactly that gap. Two of them (`NetworkOnMainThreadException` in delete,
`newSession` on every re-attach) were invisible to unit tests and only surfaced
on a device.

Device scripts:

```powershell
./tools/chaos.ps1        # Layer 1: engine lifecycle via adb (S01–S10)
./tools/chaos-ui.ps1     # Layer 2: UI + Keystore (S16–S21)
```

## Docs

| Document | Contents |
|---|---|
| [`docs/capabilities.md`](docs/capabilities.md) | What works vs. what does not, per feature |
| [`docs/toolchain.md`](docs/toolchain.md) | Versions, AGP 10 contingency, backup plan |
| [`docs/project-analysis.md`](docs/project-analysis.md) | Architecture, known defects, capability plan |
| [`docs/dynamic-autotuning.md`](docs/dynamic-autotuning.md) | Thermal/memory tuning design and results |
| [`docs/ui-spec.md`](docs/ui-spec.md) | UI/UX spec and roadmap |
| [`docs/termux-project-plan.md`](docs/termux-project-plan.md) | Separate 32-bit / real-dev-environment project |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | Attribution convention, build and test rules |

## Licence and attribution

**Crush is licensed under FSL-1.1-MIT, not MIT.** See
[charmbracelet/crush LICENSE.md](https://github.com/charmbracelet/crush/blob/main/LICENSE.md).
This app redistributes the Crush binary, so its terms apply directly.

FSL-1.1-MIT grants use and redistribution for permitted purposes, requires that
redistributions retain and link the licence and copyright notices, restricts
competing commercial use, and **converts each version to MIT on the second
anniversary of its availability**. The bundled build is **v0.97.1**; confirm its
future-MIT date before relying on MIT terms for that version.

> An earlier revision of this README stated "Crush is MIT-licensed". That was
> wrong. Verify licence claims against upstream before repeating them.

There is **no root `LICENSE` file** yet — the third-party notice for the embedded
engine still needs adding, and legal review of FSL "Competing Use" is
recommended before any commercial distribution.

Bubble Tea is MIT-licensed. This repository is not affiliated with Charm or
OpenCode Zen. You supply your own OpenCode Zen API key; it is never committed.