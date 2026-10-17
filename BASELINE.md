# BASELINE — 2026-10-17 (tag `baseline/2026-10-17`)

Executable evidence, not assertions. Every number here came from a real command
run on this date; reproduction commands are listed in \[Reproduce\](#reproduce).

## 1. Source freeze

| Fact | Value |
|---|---|
| Commit | `4e67aae608ada9778a0f48f94d7a5e2f7f39911a` (origin/main at freeze) |
| Tag | `baseline/2026-10-17` (annotated) |
| Working tree | clean (0 modified, 0 untracked at freeze) |
| Submodules | none |
| Tracked files | 154 |
| Kotlin source | 66 files / 12,288 lines (`app/src/main`) |
| Kotlin tests | 34 files / 4,533 lines (`app/src/test`) |
| Whole SHA-256 manifest | `docs/baseline/baseline-2026-10-17.sha256` |

The 15 October archive ZIP used by the external audit could not be located on
this machine; the archive-vs-current comparison was NOT performed and is
recorded as a limitation, not a pass.

## 2. Toolchain (as actually used today)

| Component | Version |
|---|---|
| JDK | Android Studio JBR **25.0.3** (`C:\Program Files\Android\Android Studio\jbr`) |
| Gradle wrapper | 9.8.0 |
| AGP | 9.4.1 |
| Kotlin (KGP) | 2.4.10 (project-owned via `android.builtInKotlin=false`) |
| Compose compiler plugin | 2.4.20 |
| Compose BOM | 2026.09.00 |
| minSdk / targetSdk / compileSdk | **29** / 36 / 37 (`app/build.gradle.kts:54`) |
| `$PATH` `java` | **1.8.0_251 — do NOT build with the PATH java**; harnesses set `JAVA_HOME=JBR` explicitly. A bare `gradlew` run without `JAVA_HOME` fails fast with "Gradle requires JVM 17 or later" |

## 3. Test evidence (from runner, not README)

`.\gradlew :app:testDebugUnitTest :app:assembleRelease` (detached, log
`streamerr-01.log`, 2026-10-17, 13m45s, exit 0):

| Suites | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| 36 | **386** | **0** | 0 | 0 |

(README previously claimed 213/331/335/364/371 at different times; 386 is the
runner-verified count as of this baseline.)

## 4. Engine binary + supply chain

| Artifact | SHA-256 |
|---|---|
| `app/src/main/jniLibs/arm64-v8a/libcrush.so` (v0.98.1, 92.5 MB) | `7bbfb0fe9b303526f8d831d8d91addda25093f60998ec9e7ef86b63f195aa052` |
| `crush_0.98.1_Android_arm64.tar.gz` tarball | `88f42f9302cbf72cd268d05b52395266acecd9b44afa4a1c4dec8c6cf0879ab3` |
| Upstream published `checksums.txt` (v0.98.1) | **matches the tarball exactly** (fetched 2026-10-17) |

The fetch script (`tools/fetch-crush.ps1`) pins this checksum by default as of
this baseline (previously default 0.97.1, which would downgrade the app to an
older engine if run today — hazard closed). Sigstore/cosign verification beyond
the checksum pin is still pending and is tracked as P7/F10.

## 5. Fresh-clone acceptance (P0 item 4)

Performed against a clean `--depth 1` clone of `origin` at `4e67aae` in
`%TEMP%\opencode\p0clone`:

1. Clone → HEAD `4e67aae` (verified).
2. `local.properties` written (sdk.dir only).
3. `tools/fetch-crush.ps1 -Version 0.98.1 -ExpectedSha256 <pin>` → network
   re-download, tarball verified against the upstream-anchored pin, extracted
   **byte-identical** to the main repo's `libcrush.so`.
4. `gradlew assembleDebug` → **BUILD SUCCESSFUL in 12m 23s** with no
   hidden local files beyond `local.properties`.

Clone artifacts: `c57faa0b...e6a5f app-debug.apk` (94.6 MB).
**Debug APK builds are NOT byte-deterministic** (differs from the main tree's
`b338a1d1...`; same commit, same inputs, different zip timestamps) — tracked
under release-engineering (P11 reproducible provenance), not a blocker here.

## 6. Endpoint contract reconciliation (P0 item 5)

Pinned spec `spec/crush-openapi.json`: **68 paths / 76 operations**.
App uses **20 paths / 23 operations** (the three doubled-verb paths are
`/workspaces`, `/{id}/agent`, `/{id}/sessions`).

**Zero verb/path drift** against the spec, verified operation by operation:
version, health, workspaces (GET/POST), agent (GET/POST), agent/init,
agent/main, current-session, sessions (GET/POST), sessions/{sid} (DELETE),
sessions/{sid}/messages, sessions/{sid}/filetracker/files, agent
sessions/{sid}/cancel, permissions/skip, permissions/grant, questions/answer,
questions/cancel, config/provider-key (POST), providers (GET), config/model
(POST), events (GET, SSE, `?client_id=`). The 48 unused paths are the Tier-2/3
surface documented in `docs/project-analysis.md`.

Findings from the reconciliation pass (full table in
`docs/baseline/p0-findings-2026-10-17.md`):

- **D1 (parked drift):** the spec requires a `client_id` **query param** on
  `POST .../current-session`; the app never sends it and the engine tolerates
  it on-device. Record, do not "fix" silently — it is upstream contract drift.
- D2–D6 (benign, documented): empty `{}` bodies on the two cancel endpoints
  where the spec declares none; the spec's `api_key: array[int]` bug against
  the real `json.RawMessage` string wire format (already documented in
  `CrushDtos.kt`); session-create over-required fields; lossy `/providers`
  response schema.
- **Six HTTP-issuing classes exist**, not two: `CrushApi`, `CrushConnection`
  (SSE), `CrushEventStream` (per-turn SSE fallback), `ZenModelsApi` (external
  provider APIs — outside this spec's jurisdiction), and three
  `HttpURLConnection` `GET /version` probes (`EngineSupervisor`, `CrushEngine`,
  `DiagnosticsReport`). All probes are verb-safe GETs.

## 7. Known open gaps at freeze (unchanged truth, listed honestly)

- Loopback is unauthenticated across app UIDs (external audit F01) — the P1
  security gate before any public distribution.
- Lock gate blocks bootstrap but does not stop an active run (F02).
- No verified file-editing/tool-call turn captured as a golden fixture (F04);
  permission prompts not device-tested (F05).
- Lint is disabled project-wide (`abortOnError=false`) — P11 item.
- No instrumentation (on-device JVM-only) suite — P11 item.
- Free-tier `:free` OpenRouter caps are per-request (20/min, 50/day); Groq is
  parked (8,000 TPM per-token cap cannot fit any agent request).

## Reproduce

```powershell
# source freeze
git rev-parse HEAD; git status --porcelain; git ls-files | measure -Line
# manifest
foreach ($f in git ls-files) { $p = Join-Path (pwd) $f; if (!(Test-Path $p)) { continue }; (Get-FileHash $p -Algorithm SHA256).Hash.ToLower() + "  " + $f }
# tests + artifacts
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease
Get-ChildItem app\build\test-results\testDebugUnitTest -Filter TEST-*.xml | % { ([xml](gc $_.FullName)).testsuite } | % { t+=tests ... }  # or read the XMLs
# engine provenance
.\tools\fetch-crush.ps1            # verify pin against network + tarball
Get-FileHash app\src\main\jniLibs\arm64-v8a\libcrush.so -Algorithm SHA256
```
