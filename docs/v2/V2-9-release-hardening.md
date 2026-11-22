# V2-9 - Release hardening (opened 2026-11-22)

**Status: COMPLETE (hardening scope), 2026-11-22.** Owner approved opening
V2-9 for **hardening only** on 2026-11-22. Gate 7 (store submission) remains
deferred; this package does not lift it and does not prepare a listing, signing
key or rollout.

## 1. Authorized scope

The owner selected the hardening option, which is these three items:

1. **Permission default** - confirm the shipped default is ASK, not yolo.
2. **SecureKeyStore device coverage** - exercise the real AndroidKeyStore
   cipher on the phone, not only the JVM slot-layout stand-in.
3. **Key-rotation checklist** - a standing runbook for the credential that was
   exposed in tool output, plus a local scanner for credential-shaped tokens.

Explicitly **not** in this package: V2-7, V2-8, Play submission, release
signing, store listing, and the V2-0 carry-over rows (T6-01/T6-02, performance
proof, Diagnostics/About pinned version, screenshot suite, onboarding device
row). Those stay where the V2-0 sign-off recorded them.

## 2. Findings before changing anything

### 2.1 The permission default is already ASK

The old note "permission default is yolo" no longer describes the tree:

- `PermissionMode.ASK` is the declared default and `parse()` fails safe
  (`domain/model/PermissionMode.kt:16-34`).
- `AppSettings.permissionMode` defaults to `ASK`
  (`domain/model/AppSettings.kt:37`).
- V2-6 added the Settings switch and it writes the value
  (`SettingsContent.kt:410-452`, `SettingsViewModel.savePermissionMode`).
- The Chat pool is forced to ASK regardless of the setting
  (`ChatViewModel.kt:1233-1240`), so the legacy conversation engine cannot be
  auto-approving even if a later global setting ships.
- Pins: `PermissionModeTest` (null/blank/junk -> ASK), `SettingsContentTest`
  (the switch writes AUTO_APPROVE only when turned on).

**Disposition: already closed.** V2-9 records the evidence and changes no
source. The stale "yolo default" line is corrected in the release notes rather
than silently dropped.

### 2.2 SecureKeyStore has JVM coverage but not device cipher coverage

`SecureKeyStoreProviderScopeTest` (Robolectric) pins the per-provider slot
layout and legacy migration with a reversible stand-in, because Robolectric
ships no AndroidKeyStore provider. The real AES/GCM path - key creation,
encryption at rest, GCM auth-tag rejection, scoped deletion - has only been
exercised incidentally by manual use.

**Disposition: add an instrumented test** that runs against the real Keystore
on `bbf8ca69`. Test-only provider ids; cleanup after every test; no biometric
gate exists in this class by design (see its KDoc).

### 2.3 Key rotation was executed; the standing check was missing

`decisions-open.md` §3 records the closure on 2026-11-14: the owner rotated the
OpenCode Zen key at the provider and deleted the gitignored repo-root file.
The file was never tracked, so deletion alone was not the remediation - the
rotation was.

A 2026-11-22 scan of tracked files for full credential shapes found only two
deliberate synthetic fixtures in redaction tests (`KeyMaskTest`,
`AppLogTest`). No real key is tracked.

**Disposition: add a release checklist and a local scanner** so the check is
repeatable instead of a one-off memory. No key material is recorded.

## 3. Work items

| ID | Work | Evidence class |
|---|---|---|
| V2-9.1 | Record the ASK default with code and test pointers; no source change | CODE + TEST |
| V2-9.2 | `SecureKeyStoreInstrumentedTest`: real cipher round trip, plaintext-not-at-rest, tamper rejection/clear, provider-scoped clear | TEST (device) |
| V2-9.3 | `tools/check-secrets.ps1` and `docs/release-key-rotation.md` | CODE + DOC |
| V2-9.4 | Package record, package table and ADR updates | DOC |

## 4. Test plan

- **JVM suite:** unchanged count expected (no new JVM test in this package);
  the gate must stay at 924 tests / 97 classes / 0 failures.
- **Instrumented:** `SecureKeyStoreInstrumentedTest`, three tests, executed on
  `bbf8ca69` through the existing `AndroidJUnitRunner`. The class does not
  launch an activity, which avoids the API 29 background-activity restriction
  that blocked the O11 instrumentation attempt.
- **Gate:** `:app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest :app:lintRelease`.
- **Scanner:** `tools/check-secrets.ps1` must report PASS on the tracked tree
  (the two synthetic fixtures are allowlisted by SHA-256, not by path).

## 5. Limits

- The instrumented run is API 29 / arm64 on one device; no API 30+ Keystore
  behaviour is claimed.
- The test uses the app's real master-key alias with test-only provider ids.
  It does not exercise a biometric or auth-gated key because this class has
  none.
- Rotation is an owner action at the provider consoles; the agent cannot
  verify a console state. The checklist records what the owner must confirm.
- The local scanner covers tracked text files only. It is not a replacement
  for GitHub secret scanning or a history rewrite tool.

## 6. Closure (2026-11-22)

| Item | Result |
|---|---|
| V2-9.1 ASK default | Already shipped; evidence in §2.1. No source change |
| V2-9.2 real-Keystore coverage | `SecureKeyStoreInstrumentedTest`: **OK (3 tests)** on `bbf8ca69`, 0.273 s |
| V2-9.3 rotation checklist + scanner | `docs/release-key-rotation.md`; `tools/check-secrets.ps1` **PASS** (674 files, 2 allowlisted fixtures) |
| Gate | `v2-9-gate-1.log`: 924 tests / 97 classes / 0 failures; lint 0 errors / 1 pre-existing warning; androidTest APK rebuilt |
| Evidence | `docs/parity/2026-11-22-v2-9-hardening/` |

Still deferred and **not** claimed: Gate 7 submission/signing/listing, V2-7,
V2-8, and the V2-0 carry-over rows listed in §1.
