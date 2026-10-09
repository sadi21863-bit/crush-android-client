# Toolchain & AGP 10 Contingency

## Current state (verified on device, 2026-10-02)

| Component | Version | Notes |
|---|---|---|
| Build JDK | **25.0.3** (Android Studio JBR) | set in `build-install.bat` |
| Gradle | 9.8.0 | AGP 9.4 requires >= 9.6 |
| AGP | 9.4.1 | latest patch |
| Kotlin (KGP) | **2.4.10** | project-owned, current stable line |
| Compose compiler plugin | **2.4.20** | versioned independently of Kotlin |
| Bytecode target | 17 | see "Why bytecode stays 17" |

Warm build: **1m46s**. Cold build from empty caches: ~1h (one-time).
Measured on this host, warm `testDebugUnitTest + assembleRelease` runs
**4–10 minutes** depending on what changed.

### On the KGP / Compose-compiler version split

The two versions differ (`2.4.10` vs `2.4.20`) **by design, not by accident.**
`gradle.properties` sets `android.builtInKotlin=false`, so this project owns the
Kotlin version instead of inheriting AGP's, and the Compose compiler plugin is
versioned on its own track.

An external audit recommended matching them at a tested version, on the grounds
that Kotlin's own guidance treats them as sharing one version reference. That is
a reasonable de-risking step, but it is a **change to be made deliberately**, not
a bug to be fixed: the build is green across 213 unit tests and repeated signed
release builds on AGP 9.4.1 / Gradle 9.8 with the split in place. If aligning,
change both to 2.4.20 in one commit and re-run the full suite.

## The AGP 10 problem

Two opt-outs in `gradle.properties` exist ONLY to allow Kotlin 2.4.10:

```properties
android.builtInKotlin=false
android.newDsl=false
```

AGP 10 removes both. When it arrives:
- `android.builtInKotlin=false` stops having any effect; built-in Kotlin becomes mandatory
- The new DSL likely becomes the only option
- **AGP 10 then dictates the Kotlin version**, and 2.4.10 may be forced down

## Backup plan (in priority order)

### Step 0 — Do nothing yet
AGP 10 is not released. Latest stable is 9.4.1. There is nothing to action today.
This document exists so the next agent does not rediscover it from scratch.

### Step 1 — Revert to AGP-managed Kotlin (2-line change, guaranteed)
Delete both properties from `gradle.properties`:

```properties
# android.builtInKotlin=false    <- delete
# android.newDsl=false           <- delete
```

and remove the `kotlin.android` plugin from `app/build.gradle.kts`.

Result: Kotlin drops to whatever AGP ships (2.2.10 for AGP 9.x). **The app
still builds and behaves identically** — no `.kt` source changes are required.
This is the safe landing strip.

### Step 2 — Prefer built-in Kotlin BEFORE upgrading AGP
Proactively do Step 1 while still on AGP 9. That way the AGP 10 upgrade itself
is uneventful.

### Step 3 — Investigate the supported override
AGP docs state built-in KGP is a **floor, not a ceiling**, which implies a
higher version should be declarable while staying on built-in Kotlin. The
mechanism is UNCONFIRMED — a `buildscript { classpath(...) }` override was
tried and produced:

```
Unexpected lock protocol found in lock file. Expected 3, found 0.
```

on `:app:processDebugNavigationResources`. That error is a **misleading symptom**
of a second KGP on the classpath, not a real file-lock problem. The correct
mechanism (possibly `com.android.built-in-kotlin` plus a version property) was
never found. Verify with JetBrains/AGP docs before assuming it is impossible.

## Risks if AGP 10 lands and nothing was done

| Risk | Severity | Recovery |
|---|---|---|
| Build fails on removed properties | Low | Delete 2 lines |
| Kotlin silently downgraded | Low | Cosmetic; Step 1 |
| App code breaks | **None** | No `.kt` changes needed |
| Runtime behaviour changes | **None** | Toolchain-only concern |

**Overall: no user-facing risk.** This is a build-config chore, not an app risk.

## Why bytecode target stays 17

The build JDK and the bytecode target are different things:

- **Build JDK** (25.0.3) merely *runs* Gradle and the Kotlin compiler. Can be new.
- **Bytecode target** decides the **minimum Android version** that can run the APK,
  and D8 must parse every class file version.

Kotlin can emit up to `JVM_26`, but raising this would shrink device support for
**zero** build-speed gain. Verified empirically: moving the build JDK 17 -> 25
produced a *faster* warm build (1m46s) with no bytecode change.

## Regression gate after any toolchain change

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\chaos.ps1
```

Must report `10 passed, 0 failed`. Key behavioural assertions (not just
"did not crash"):
- `S01` engine pid **changes** after an injected kill (recovery works)
- `S09` `supervisor restarts=0` (engine never idles out)
- No duplicated `EngineSupervisor` log lines (single supervisor instance)
