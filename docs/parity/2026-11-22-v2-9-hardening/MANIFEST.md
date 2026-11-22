# V2-9 hardening evidence (2026-11-22)

Device `bbf8ca69`, Redmi Note 7 Pro, API 29, arm64-v8a. Debug build from the
V2-9 tree; test APK installed with `adb install -r -t`.

## Files

| File | What it shows | What it does not show |
|---|---|---|
| `01-securekeystore-instrumented.txt` | the real `SecureKeyStore` against the device AndroidKeyStore: three tests, `OK (3 tests)`, 0.273 s | API 30+ behaviour; a biometric or auth-gated key (this class has none); a fresh install with no master key already present |
| `02-secret-scan.txt` | `tools/check-secrets.ps1` PASS over 674 tracked files, two synthetic fixtures allowlisted by SHA-256 | git history, screenshots, transcripts or provider consoles |

## Commands

```powershell
# gate
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintRelease
# log: v2-9-gate-1.log

# install and run only this class
adb -s bbf8ca69 install -r -t app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb -s bbf8ca69 shell am instrument -w `
  -e class com.crush.mobile.SecureKeyStoreInstrumentedTest `
  com.crush.mobile.test/androidx.test.runner.AndroidJUnitRunner

# tracked-file credential scan
pwsh -File tools/check-secrets.ps1
```

## What the three tests assert

1. **Round trip + at-rest** - `write` then `read` returns the same value
   through the real Keystore; the `ct.<provider>` slot is non-blank and is not
   the plaintext.
2. **Tamper rejection** - flipping one base64 character produces a GCM
   auth-tag failure; `read` returns null and the slot is cleared.
3. **Scoped clear** - clearing one test provider leaves the other readable.

Test-only provider ids (`v2-9-selftest-a/b`) are cleared in `@After`. No
non-test slot is read, written or cleared.

## Gate result

- `:app:testDebugUnitTest` UP-TO-DATE at 924 tests / 97 classes / 0 failures
  (no main or JVM-test source changed; the XML census was re-read).
- `:app:assembleDebugAndroidTest` rebuilt (13:24:50).
- `:app:lintRelease`: 0 errors, 1 pre-existing warning.

## Limit statement

The instrumented test proves the real cipher path on API 29 hardware. It does
**not** prove auth-gating (deliberately absent; see the `SecureKeyStore` KDoc),
keystore invalidation after a factory reset or hardware migration, or API 30+
StrongBox behaviour. The scanner proves the tracked tree, not history.
