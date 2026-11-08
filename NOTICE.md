# Third-party notices — Crush Mobile

LICENSE (the MIT text above) covers THIS REPOSITORY'S code: the Kotlin app,
its UI, its tests and its build scripts. It does not cover the embedded
engine binary, which is redistributed here on the terms below. This file is
the attribution an APK build must ship alongside the app.

## Embedded agent engine — `libcrush.so` (bundled binary)

- Product: **Crush**, the AI-coding agent CLI by Charmbracelet
  (https://github.com/charmbracelet/crush). The app packages this engine as a
  statically-linked Go binary (`arm64-v8a/libcrush.so`) and runs it as a
  SEPARATE CHILD PROCESS, speaking to it over a Unix domain socket. The
  engine is not linked into the app's own code.
- License: **FSL-1.1-MIT** (Functional Source License, v1.1, "MIT Future
  License"), Copyright 2025-2026 Charmbracelet, Inc.
  - Terms: https://fsl.software/FSL-1.1-MIT.template.md
  - Upstream license file:
    https://github.com/charmbracelet/crush/blob/main/LICENSE.md
  - Usage restriction in force for FSL versions: a "Competing Use" restriction
    applies for two years after each version's publication, after which each
    version becomes plain MIT on its own terms ("Grant of Future License").
    Distributing an unmodified engine inside a mobile client is a permitted
    distribution use, not a competing use; modifications are governed by the
    same terms.

## OpenCode-derived code inside the engine binary

The engine lineage contains code from **OpenCode**
(https://github.com/anomalyco/opencode, formerly https://github.com/sst/opencode).
Its pre-fork segment carries:

> Copyright (c) 2025-03-21 - 2025-05-30 Kujtim Hoxha
>
> MIT License — verbatim text equals the MIT permission block in our LICENSE
> file above. Full upstream text:
> https://github.com/anomalyco/opencode/blob/dev/LICENSE

## Runtime dependencies carried by the shipped APK

| Component | License | Source |
|---|---|---|
| AndroidX Compose, Material3, lifecycle, navigation, activity, fragment, core-ktx, DataStore (`androidx.datastore:datastore-preferences:1.2.1`), Room (`androidx.room:room-runtime` / `room-ktx` 2.8.5 — the KSP `room-compiler` is build-time only and ships nowhere) | Apache-2.0 | androidx packages on Google Maven |
| OkHttp + OkHttp-SSE 5.x | Apache-2.0 | https://github.com/square/okhttp |
| junixsocket (AF_UNIX transport, vendored native .so) | Apache-2.0 | https://github.com/kohlschutter/junixsocket |
| Gson 2.x | Apache-2.0 | https://github.com/google/gson |
| kotlinx-coroutines (android) | Apache-2.0 | https://github.com/Kotlin/kotlinx-coroutines |

AndroidX and the Kotlin libraries above are redistributed as dex; the Apache
2.0 texts are included upstream and referenced here. Test-only dependencies
(Robolectric, MockWebServer, JUnit 4, kotlinx-coroutines-test, and the
androidx.test / Compose-UI-test instrumented-test stack declared under
`androidTestImplementation`) are not shipped in the APK and are listed
in app/build.gradle.kts.

## Trademarks

OpenCode, Crush and Charm are trademarks of their respective owners; the
references above are for attribution only and do not imply endorsement or
affiliation.
