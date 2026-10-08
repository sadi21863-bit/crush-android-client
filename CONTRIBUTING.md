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
./tools/chaos-ui.ps1     # UI + Keystore (S16-SS21)
```

An x86_64 emulator **cannot** run this app — the Crush engine is an arm64 ELF
binary, and the emulator refuses ARM guests on an x86_64 host.

## Things that will silently break the build or the app

- **Never** copy `libcrush.so` to `filesDir` and call `setExecutable()`. Android
  10+ blocks `execve()` from the app home directory. It must be exec'd from
  `nativeLibraryDir`.
- **Never** change `useLegacyPackaging` to `false`. The engine is then never
  extracted from the APK and `exec()` fails with `ENOENT`.
- **Never** bind the engine to `0.0.0.0`. Crush has no authentication and the
  agent can execute shell commands. Loopback excludes the LAN but *not* other
  apps on the device — see the Known issues section of the README.
- **Never** hardcode a model id. Zen's `/models` is a public endpoint that still
  lists retired models; ids must come from the live probe in `ModelChoice` /
  `ZenModelsApi`.
- **Never** make `KeySession.require()` mark the session unlocked. `isUnlocked`
  is the single input the app-lock gate trusts, so doing so is an authentication
  bypass.

## Reporting security issues

Please do not open a public issue for a vulnerability. The engine's loopback API
is unauthenticated and reachable cross-app; see the README for what is confirmed
and what is not.
