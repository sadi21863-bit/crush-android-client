# Contributing

## Commit attribution

Commits are authored by the repository owner. When AI assistance was used, the
commit carries a generic trailer naming the assistance rather than any specific
model or vendor product:

```
Co-Authored-By: AI Assistant <noreply@anthropic.com>
```

An earlier revision of this repository's history used a model-specific name in
that trailer. That was inaccurate and has been superseded by the convention
above.

## Before you open a pull request

```powershell
./gradlew :app:testDebugUnitTest     # must be green
```

Then, if you have a physical arm64 device attached:

```powershell
./tools/chaos.ps1        # engine lifecycle (S01-S10)
./tools/chaos-ui.ps1     # UI + Keystore (S16-S21)
```

An x86_64 emulator **cannot** run this app — the Crush engine is an arm64 ELF
binary, and the emulator refuses ARM guests on an x86_64 host.

## Things that will silently break the build or the app

- **Never** copy `libcrush.so` to `filesDir` and call `setExecutable()`. Android
  10+ blocks `execve()` from the app home directory. It must be exec'd from
  `nativeLibraryDir`.
- **Never** change `useLegacyPackaging` to `false`. The engine is then never
  extracted from the APK and `exec()` fails with `ENOENT`.
- **Never** bind the engine to TCP — not `0.0.0.0`, and not `127.0.0.1` either.
  The engine is launched with an app-private Unix-domain socket (`-H unix://…`);
  any TCP listener re-opens the sibling-app surface documented in
  [docs/p1-attack-surface.md](docs/p1-attack-surface.md), because on Android
  loopback is shared by every app on the device.
- **Never** hardcode a model id. Zen's `/models` is a public endpoint that still
  lists retired models; ids must come from the live probe in `ModelChoice` /
  `ZenModelsApi`.
- **Never** make `KeySession.require()` mark the session unlocked. `isUnlocked`
  is the single input the app-lock gate trusts, so doing so is an authentication
  bypass.

## Reporting security issues

Please do not open a public issue for a vulnerability. The engine's HTTP API
still has no authentication; the transport is a kernel-enforced app-private
Unix-domain socket, so cross-app reach is closed but same-UID (and
root-equivalent) access remains — see the README Security section for what is
proven and what the limits are.

## Tree-verification pass 2026-10-31

- Confirmed `tools/chaos-ui.ps1` defines S16/S17/S18/S21 scenario functions
  (S18 is deliberately opt-in), so "(S16-S21)" (typo "SS21" fixed here) and
  README's range wording are both honest.
- The five "never" rules re-verified in code: exec from `nativeLibraryDir`
  via jniLibs + `useLegacyPackaging = true` (`app/build.gradle.kts:181`),
  UDS-only launch (`-H unix://`), no hardcoded model id
  (`CrushSession.DEFAULT_MODEL = ""`, probing from `ModelChoice`/`ZenModelsApi`),
  and `KeySession.require()`'s documented non-caching contract.
- Not re-verifiable this pass: whether any specific past commit carried the
  trailer (conditional on AI assistance); the convention statement itself is
  untouched.

