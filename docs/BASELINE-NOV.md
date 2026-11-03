# BASELINE-NOV.md — repo baseline snapshot, 2026-11-02 (P0 artifact)

The baseline document requested by
[crush-plan-2026-11-02.md](crush-plan-2026-11-02.md) §P0 "Reconcile current
tree and lock baseline" (whose artifact list names `BASELINE-NOV.md`). It
records, as measured facts with dates and evidence links, what the tree
contained and what had been proven at the time of writing. The P0 sibling task
(README drift correction) was delivered the same day, 2026-11-02.

Evidence-grade legend, used inline:

- **A** — publicly confirmed (upstream sources, GitHub-visible state)
- **B** — repo-reported: measured by this project's own runs (on-device or
  local gate) and recorded in a ledger doc or the run transcript
- **C** — proposal: planned, not proven (everything in that class is gathered
  in the closing section)

This doc was written in a docs-only session: no gradle command was run while
writing it. Every build/test fact below cites a run that already happened and
left a record — the repo's green gate runs and the device passes ledgered in
the docs listed here.

## 1. Repo revision (verified A)

- Latest commit observed in `git log` at writing time (2026-11-02, minutes
  before snapshot): **`bb76e49`** — full SHA
  `bb76e4964dd30e415069e6b19e9efe1e77d6c7b3`, 2026-11-02 22:27:28 +0530,
  "Docs: capabilities matrix adds the deepseek-v4-pro lane turn (22:14, OK) -
  all three Go paid lanes verified end to end".
- Tree hash recorded per the plan's step 1: **`6c0338c794b96298b328c14ebe9d6ccf06ea35c3`**
  (read in one run of `git rev-parse 'HEAD^{tree}'` during the writing
  session).
- Engine binary note for clean checkouts: `libcrush.so` is **not committed**
  (92 MB, fetched by `tools/fetch-crush.ps1`, gitignored via
  `app/src/main/jniLibs/*` with the junixsocket exception).

## 2. The gate — command and 2026-11-02 green status (B unless noted)

The full gate, locally and in CI (the workflow runs the identical task list,
`.github/workflows/android-ci.yml` — A):

```powershell
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintRelease
```

**CI is green.** Bring-up was measured 2026-11-01 with "first green pending" —
the pending item closed on 2026-11-02: monitor runs 12–20 on 2026-11-02,
latest success at the time of writing on commit `bb76e49` (B; on-record flake:
run 16 exited 1 in the unit-test step, anonymously unreadable, and the same
code passed in runs 18–20 on re-trigger — recorded in `95b2ebf`'s commit
message). CI checkouts build **without the embedded engine** (gitignored), so
CI proves compile + JVM suites + lint + androidTest-compile only; runtime
facts need real arm64 hardware (A — by construction).

Three green full-gate runs on 2026-11-02, machine-local PowerShell tees
(`gate-zen-copy.txt` 47m57s, `gate-egress2.txt` 32m36s, `gate-sync.txt`
26m11s; each transcript ends `BUILD SUCCESSFUL`). What each compiled, read
from the task lists in the tees:

| Tee (machine-local, not committed) | Wall time | Tasks beyond the four |
|---|---|---|
| `gate-zen-copy.txt` | **47m 57s** | `:app:assembleR8probe` (`minifyR8probeWithR8`, `packageR8probe`, `lintVitalR8probe`, `validateSigningR8probe`) → a signed minified probe APK; release-config compile under lint |
| `gate-egress2.txt` | **32m 36s** | same — `:app:assembleR8probe` rebuilt; `compileDebugAndroidTestKotlin` recompiled fresh |
| `gate-sync.txt` | **26m 11s** | plain gate exactly — the four tasks, androidTest compile UP-TO-DATE, no r8probe tasks |

Per-run counts and details: `lintRelease` ran clean in all three — lint was
re-enabled and green as of 2026-10-31
([decisions-open.md](decisions-open.md) item 1, LANDED `5325d68`: fresh
release lint "No issues found.", the old AGP/lint-vital crash defused with
reasoned waivers). The runner count recorded for the green
unit-test runs: **466 JVM tests** (B — count recorded during the session; the
tees prove `BUILD SUCCESSFUL` and gradle does not print its own test count).
Structural cross-check by `git grep -c '@Test'` in `app/src/test`: **466 at
the `2c1c629` tree** (engine-egress + session-view fixes) and **467 at
`bb76e49`** — one added by `36770d9` (the provider-wording pin in
`ModelChoiceTest.kt`). The README's legacy figure "455, grep-counted
2026-10-31" predates November's suites.

## 3. Test inventory at baseline

- **JVM**: 466 tests green in the last full-gate counting above; 467
  `@Test`-annotated methods in `app/src/test` at the baseline commit
  (reconciliation in §2). Coverage and the named suites (protocol, probing,
  session continuity, lock policy, diffing, …) are catalogued in the README
  "Testing" section — spot-checked structurally across the 2026-10-31
  tree-verification pass, which found all 17 then-named suites present.
- **Instrumented**: **8 tests, OK on device 2026-11-01** (`am instrument`,
  326 s): `MainActivityLockFlowTest` (6 — gate↔FLAG_SECURE contract,
  recreation-survival of the process-scoped lock state,
  fresh-process-holds-no-decrypted-key, real **AndroidKeyStore** roundtrip +
  per-provider isolation + tamper-clears) + `MainActivitySmokeTest` (2).
  Honest scope in-file: tests do not answer biometric prompts. Evidence:
  [external-review-triage.md](external-review-triage.md) "App-lock flow
  verified only manually" row (**closed 2026-11-01**);
  [capabilities.md](capabilities.md). The CI gate compiles
  `:app:assembleDebugAndroidTest` so test-code breakage cannot ship (A, from
  the workflow source).
- **What instrumentation cannot cover**, as ever: the child-process engine and
  real-device egress — covered by the dated device passes below, not by CI.

## 4. Engine bundle at baseline

- **Engine of record: upstream Crush v0.99.0** (A: upstream clean release,
  published 2026-10-29, GitHub-verified and checksums.txt-pinned;
  `tools/fetch-crush.ps1` defaults to 0.99.0 with its SHA-256 pin). Promoted
  to this repo's engine of record **2026-11-01**, replacing the
  instrumented `0.98.2+dirty` build (retired to the z-evidence archive).
  See README §Toolchain "Upstream pins" and
  [capabilities.md](capabilities.md).
- On-device app-diagnostics log (AppLog) examined 2026-11-02: engine
  **bundled=true**, version **0.99.0** (B — device-side log, not committed).
- Load-bearing property of this exact version: **`x-opencode-session` exists
  only from 0.99.0** (binary-string check: 2 occurrences in 0.99.0, 0 in
  0.98.2 — documented in
  [providers/opencode-go.md](providers/opencode-go.md)), so **OpenCode Go is
  only servable by this engine**. It also carries the OpenCode/Console-family
  plan-split model bases (`domain/provider/Providers.kt`).

## 5. Artifacts at baseline (B — measured in the 2026-11-02 session)

| Artifact | Size | Status |
|---|---|---|
| `app-release.apk` | **28.92 MB** | release R8 buildType; measured on the signed release-rigored run (r8probe identical); runtime-proven on device (below) |
| `app-r8probe.apk` | **28.92 MB** | `r8probe` buildType — `initWith(release)`: minify + R8 + proguard rules + release signing, `applicationIdSuffix=.r8probe`, non-debuggable (`run-as` REFUSED — part of the rigour) |
| debug + androidTest APKs | — | green in every gate run above |

**R8 proof closed 2026-11-02**: the release-rigored `r8probe` artifact
completed a real streaming turn on device — fresh onboarding → provider
re-selection → completed streaming turn 20:07 device-local ("Reply with
exactly: OK" → `OK`, bubble rendered, file-changes reported,
`CONNECT opencode.ai:443 ok` through the bridge in logcat). Details in
[external-review-triage.md](external-review-triage.md), "R8 minified
release: runtime unproven" row — **closed, proven on device 2026-11-02**.

## 6. Provider verification state at baseline (B — device evidence, ledgered)

Mirror of [capabilities.md](capabilities.md) (multi-provider row) as of
2026-11-02 evening, 13 Catwalk ids offered:

- **Verified end to end: `opencode-zen`, `openrouter`, `opencode-go`** —
  including OpenCode Go's **PAID lanes, all three measured 2026-11-02
  evening**: `deepseek-v4.1-flash` + `glm-5.3-flash` turns (21:55) and
  `deepseek-v4-pro` ("Reply with exactly: OK" → `OK`, 22:14), after ~1 h
  paid-lane provision lag following the Go subscription. The earlier 402s
  were allowance provisioning upstream, never a client defect —
  [providers/opencode-go.md](providers/opencode-go.md).
- **`hyper`**: real-key verified **to its $0 ceiling** — 23 live models all
  `402 authentic-but-unfunded`, `badKey=false`; a completed turn awaits
  funded credits. [providers/hyper.md](providers/hyper.md),
  [providers.md](providers.md).
- **`groq`**: probe-verified, parked as a test target (key rotation).
- Everything else: labelled **unverified**, never claimed working.
- OAuth stays unimplemented (no upstream surface for model traffic;
  see [providers.md](providers.md) §"why OAuth is absent").

## 7. Key architecture changes shipped in November 2026

With their ledger links, newest evidence first:

- **Root LICENSE + NOTICE.md exist (added 2026-11-02, `0741e77`).**
  App code: MIT © 2025-2026 sadi21863-bit. NOTICE.md: the embedded
  `libcrush.so` engine under **FSL-1.1-MIT** (Charmbracelet) with the
  OpenCode-derived MIT segment carried verbatim, plus the runtime-deps table.
  (Kills the old "no root LICENSE / unlicensed" state the README used to
  record.)
- **Engine egress fixed (2026-11-02)**:
  [engine-egress-dns-2026-11-02.md](engine-egress-dns-2026-11-02.md). The
  bundled Go engine could not resolve DNS on Android — no `/etc/resolv.conf`,
  so Go's pure resolver fell back to `127.0.0.1`/`[::1]:53` with nothing
  serving loopback DNS; standalone turns died (`read: connection refused`),
  while tethered windows only worked on borrowed DHCP forwarders. Fix:
  app-local **HTTP CONNECT bridge (`EngineProxy`)** — engine env
  `HTTPS_PROXY/HTTP_PROXY=http://127.0.0.1:<port>`, JVM resolver on the
  bridge side, end-to-end TLS, strictly loopback, lifecycle-bound to the
  engine. The 20:07 r8probe turn above is the proof the fix works.
- **Session-switch transcript bleed fixed (2026-11-02)**:
  [session-transcript-bleed-2026-11-02.md](session-transcript-bleed-2026-11-02.md)
  — a monotonic `sessionViewEpoch` guards the three unguarded writers
  (openSession replay, preflight, streamSend insert) so a switch-away chat
  can no longer write its transcript across the one on screen. Tests +
  defect doc in `2c1c629`.
- **Engine bundle promoted to v0.99.0 (2026-11-01)** — upstream clean
  release, checksums-verified fetch pin; OpenCode Go model access requires
  this version; plan-split bases for the OpenCode/Console family landed in
  `domain/provider/Providers.kt` (README §Toolchain).
- **Instrumented suite landed and passed on device (2026-11-01)**;
  `:app:assembleDebugAndroidTest` added to the CI gate
  ([external-review-triage.md](external-review-triage.md)).
- **Provider honesty fixes (2026-11-02, `36770d9` + `c0b13c0`)** — model-access
  failure wording names the ACTIVE provider (third Zen-hardcode found live in
  r8probe), and bootstrap syncs `state.providerId` to the resolved provider
  so the UI stops naming OpenCode Zen under a Go catalogue.

## 8. Device and signing prerequisites at baseline

- Device of record: **Redmi Note 7 Pro, Android 10 / API 29** — device
  instance `bbf8ca69` as recorded in the docs since 2026-10-18
  ([p1-attack-surface.md](p1-attack-surface.md) §"On-device verification")
  and used for the 2026-11-02 measurements
  ([engine-egress-dns-2026-11-02.md](engine-egress-dns-2026-11-02.md),
  the r8probe turn). x86_64 emulators cannot run this app (arm64 engine).
- Release-signing prerequisites **already present**: gitignored `keystore/`
  (guarded by `.gitignore`: `keystore/`, `*.jks`, `*.keystore`) holds the JKS
  and `keystore/credentials.properties` — the path the release signing
  config reads (`app/build.gradle.kts:32`). Nothing signing-related is
  committed, by design.

## 9. Reproducing this baseline

From a clean checkout:

1. `git checkout bb76e4964dd30e415069e6b19e9efe1e77d6c7b3` (tree hash:
   `6c0338c794b96298b328c14ebe9d6ccf06ea35c3`, recorded in §1).
2. `./tools/fetch-crush.ps1` — fetches the engine (defaults to 0.99.0
   behind the SHA-256 pin; the binary is not committed).
3. Run the gate:
   `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintRelease`
   — expect `BUILD SUCCESSFUL`; the unit-test task at this revision carries
   **467 `@Test` methods** (runner count 466 on record for the 2c1c629-tree
   runs, 467 structural at this commit — see §2); a clean CI-equivalent run
   proves the same set without the engine. Append `:app:assembleR8probe` for
   the signed minified artifact: expect **28.92 MB**, `run-as` refusing.
4. Device-only prongs (need arm64 hardware with a real key): boot the app,
   stream one turn, and run the instrumented suite (`am instrument` —
   baseline: OK, 8 tests, §3).

## 10. D-grade items NOT yet proven (at time of writing)

Everything written above is evidence-backed; the forward plan lives in
[crush-plan-2026-11-02.md](crush-plan-2026-11-02.md). Items honestly NOT
proven as of 2026-11-02 (`bb76e49`), graded **C / not started**:

1. **Multi-file edit proof — pending.** The diff pipeline is built,
   JVM-tested and closed the single-file device prong (2026-10-29, z6/z7),
   but a real turn editing **two or more source files** has never run on
   device. This is the plan's first P1 proof (§P1 step 1) and the README's
   standing open row in the Status table.
2. **ProjectRegistry / per-project workspace mapping — identity + persistence land measured.**
   The Chat/Code separation depends on it (the plan's
   code-experience layer, projected as
   `ProjectRegistry -> Explore/Build/Review`).
   Update 2026-11-03: ADR-0001 ("Project / workspace / session / run
   identity") is **PROPOSED + COMMITTED** on `main` (`b1e715f`), grounded in
   the real seams (`openWorkspace`, `RunHandle`, `sessionViewEpoch`). The
   plan's hard stop was measured, not assumed: `TwoProjectAttachTest` proved
   workspace attach is path-keyed and multi-capable on one engine process,
   and proved the SESSION boundary is NOT — crush v0.99.0 keeps sessions as
   a global pool per data dir. The ADR amended in place (`4207828`) to the
   measured fallback shape: **one engine instance per project** (own socket +
   own state dir), proven isolating sessions/workspaces exactly per project
   by `TwoEngineInstancesPerProjectTest` ("OK (2 tests)" on device). P2
   persistence followed on the same day: a `ProjectStore` seam with the
   in-memory `ProjectRegistry` fixture and a Room 2.8.5-backed
   `RoomProjectStore` (KSP 2.3.13), whose restart survival and idempotent
   legacy-workspace migration are proven by `ProjectStoreRoomPersistenceTest`
   (status: gate + device run recorded in the commit that lands them).
   Downgrade resolved: "no ProjectRegistry source exists" no longer holds;
   what is still absent is the P3 productization (Projects UI, DI wiring,
   Room adoption in production paths).
3. **Termux L1–L3 — not started.** The separate environment-related project
   has its own plan
   ([termux-project-plan.md](termux-project-plan.md)); the level taxonomy
   (L1 tools …) is design-only in
   [crush-plan-2026-11-02.md](crush-plan-2026-11-02.md).
4. **Local inference — not started.** The model-router plan (§"Provider
   strategy: Hyper, others and local inference") records it as an optional
   capability on capable hardware; no local model has ever been wired here.

Anything outside this list is either proven above (with its grade and doc)
or explicitly parked in [decisions-open.md](decisions-open.md).
