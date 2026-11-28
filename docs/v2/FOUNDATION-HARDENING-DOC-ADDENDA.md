# Foundation hardening — dated documentation addenda (2026-11-28)

This file records corrections to stale or superseded statements without
rewriting historical records. Each row names the historical document and the
current source-verified position. Historical lines stand as written.

| ID | Historical statement | Corrected position (2026-11-28) |
|---|---|---|
| J1 | `BASELINE.md:59-61` records only the v0.98.1 blob hash `7bbfb0fe…` | The on-disk `app/src/main/jniLibs/arm64-v8a/libcrush.so` is 99,418,406 bytes, SHA-256 `326206A5…7B78E`, ELF AArch64 PIE. `tools/fetch-crush.ps1` pins tarball SHA-256 `cdb15f23…afeaa` and verifies before extraction. The extracted hash is not reproducible from the checkout alone because the binary is gitignored; re-derivation requires an authorized network fetch. |
| J4 | README emulator/support wording is unqualified | Supported: API 29+, arm64-v8a only. x86_64 emulator and ARM64-on-x86 translation are NOT-PERFORMED/unsupported per existing records. `EngineCompatibility.kt:54-75` gives named rejection for missing ABI/binary. |
| J5/J10 | Historic green run IDs are cited in README; A1/A2 records say no branch CI run | One tracked workflow `.github/workflows/android-ci.yml` exists, engine-less by design (no engine blob, gateway scripts or engine rails). No GitHub Actions run is claimed for `v2-16-planning-baseline`. Engine-inclusive CI (B4/R29) is not met. |
| J6–J11 | Track B0–B6 implied as planned/accepted in early records | B0–B6 acceptance remains NOT-PERFORMED or BLOCKED: no API 30+/tablet/RTL device, no spoken TalkBack capture, X4 storage policy unresolved, security verification not repeated on an A8 build, R37 fault bridge not implemented. |
| J16 | `README.md:15,385,417` says "Last verified 2026-11-14", 716 JVM tests / 76 suites | Later recorded gates: 1087/117 (V2-15), 1129/121 (A1), 1156/125 and 1169/127 (A2 passes), with archived XML/log under `docs/parity/v2-16-phase0/`. |
| J17 | `V2-16-PLAN.md:14` still says "Approval status: pending" | §1/§18 of the same plan record approval on 2026-11-26; §14 is historical text preserved by §17. |
| J18 | `A2-STAGE2-COMPLETION.md` says HEAD `b72e572` and "no commit/push" | That report was committed in `edac95d0458c684884a7e55e805ea7e5e7017da8`, which is the reviewed Stage 2 checkpoint. A2 slice 1 remains unaccepted. |
| J19 | `V2-15-RECONCILIATION.md:103` calls D7 "unresolved by design" | D7 is settled by V2-A20 (`ADR-REGISTER.md:39`). D4 emulator qualification remains open. |
| H-06 | `V2-16-CONCURRENCY-MEASUREMENT.md:99-100` and `V2-16-BACKGROUND-MEASUREMENT.md:237-243` call budget refusal log-only and a switch cancelling the run | At HEAD the refusal is surfaced in `ChatListContent.kt:110-124` via `ChatViewModel.kt:618-639`; a project switch does not cancel the run (`AppContainer.kt` stops the outgoing engine only when no run is in flight). |
| I-05 | `tools/chaos.ps1:78` and `chaos-ui.ps1:216` treat `Crash-Engine` S01 as a pass signal | `SpikeActivity` is `exported=false` in debug, so the command is unreachable; S01 recovery claims must be demoted to SKIPPED unless a reachable debug-only fault seam is authorized. |
| F-09 | `p1-attack-surface.md:32-53,154-227` describes a pre-UDS TCP model; `PermissionMode.kt:10-13` says app-private-only blast radius | Current transport is UDS only; imported projects live in user-visible shared storage. `p1-attack-surface.md:263-327` contains the UDS closure. |
| E-06 | `CrushApi.kt:19-25` still describes 127.0.0.1 TCP posture; orphan KDoc at `:498-506` | Transport is UDS; the canonical key-normalization doc is at `:594-620`. |
| F-01 | Session `CLAUDE.md` claims `auth-legacy` Keystore gating on API 29 | `SecureKeyStore.kt:25-53` has no `setUserAuthenticationRequired`; the app-level lock gate and in-memory key drop are the controls. This was a deliberate decision after API-30 onboarding crashes. |

No historical report was rewritten. No status row was promoted by an addendum
alone; acceptance and release decisions remain with the owner.
