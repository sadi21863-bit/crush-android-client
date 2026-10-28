# Open user decisions — 2026-10-27

Decisions no agent can make alone. Each one is either a money/security trade,
a product stance, or a task assigned to the human (creating credentials).
Everything here is distilled from [`deep-analysis-2026-10-27.md`](deep-analysis-2026-10-27.md)
§4; this file exists so the backlog can say "awaiting a human call" instead of
silently rotting.

## 1. Re-enable lint and decide on R8/minify

**Status: OPEN — blocked on triage of the AGP/lint crash.**

Current state (both deliberate, both commented in [`app/build.gradle.kts`](../app/build.gradle.kts)):

- Lint is fully switched off (`checkReleaseBuilds=false`, `abortOnError=false`,
  lines 155–169). Lint's "vital" pass crashes the release build outright —
  `AsyncExecutionService.getService must not return null` thrown while parsing
  Kotlin imports in `SettingsViewModel.kt`. That is an AGP/lint defect, not a
  code defect; disabling it was the defusal.
- R8/minify stays off (line 92–94 rationale: minify + a Go binary in `jniLibs`
  adds risk for no size win on a 92MB payload dominated by the engine).

**The decision:** triage the lint crash (AGP version pitch, or a lint-only
isolate) and then choose: re-enable lint, and either enable minify or confirm
the size argument stands. Neither may be flipped while the crash is untriaged.

## 2. Create the release keystore and ship one signed assembleRelease

**Status: OPEN — human credential work.**

No signed release has ever been produced: every artifact so far was unsigned
or debug. `requireReleaseSigning` and the credentials loader already exist in
`app/build.gradle.kts` (lines 32–34, 99–117) and `keystore/` is gitignored;
only the keystore + `keystore/credentials.properties` are missing.

**The decision/task:** create the keystore, write the four properties
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`), then run
`.\gradlew :app:assembleRelease -PrequireReleaseSigning` and install the APK
on the reference device. Until this happens the README's "debug builds are
not shareable" rule means the app still cannot be handed to anyone.

## 3. Rotate/delete the plaintext test key file at repo root

**Status: OPEN — key is live, file confirmed present 2026-10-27.**

`"opencode api key for testing.txt"` sits at the repo root. It is gitignored
(`*key*.txt`, `.gitignore:15`) so it will never be committed, but the key
itself is real and live. The old world-readable `/sdcard/zenkey.txt` fallback
was deliberately removed from the spike harness (`FullE2ETest.kt` comment);
this repo-root paste-file is what remains of that workflow.

**The decision:** rotate the OpenCode Zen key at the provider, delete the
file, and pick a stated key-transfer procedure (paste directly into Settings)
instead of relying on a gitignore to make an on-disk key safe.

## 4. Injector-vs-engine concurrent `crush.json` write needs a design

**Status: OPEN — deferred, needs a design rather than a patch.**

When the app's `EngineConfigInjector` rewrites `crush.json` with the provider
key and the engine writes its own config changes concurrently, one write can
lose the other (lost-update, `EngineConfigInjector.kt:134–167`). Any fix is a
single-writer/ownership design decision (e.g. keys injected over the socket
only, engine as sole writer of `crush.json`), which is why it is deferred
rather than patched.

## 5. No-lockscreen fail-open: confirm or block a store release

**Status: OPEN — currently a deliberate beta stand.**

With no lock configured, the app opens without any lock prompt
(`DeviceAuth.kt:54–62`; README "First-launch app lock bypass", D9). This is
documented and deliberate while in beta.

**The decision:** either confirm this stand (beta, not for store), or declare
that a store release requires fail-closed behavior first. Do not ship to a
store while the stance is unresolved-by-silence.

## 6. Key at rest in plaintext inside `crush.json`

**Status: OPEN — documented trade, still deserves its guard.**

The provider key lives plaintext in `crush.json` (engine requires it there;
`EngineConfigInjector.kt:36–38/129`). The README documents it, including why
shareable debug builds are a hazard because of it. The suggestion from the
analysis: add an agent-prompt guard note so the embedded agent refuses to
echo the key (or flags any prompt that asks for it).

**The decision:** accept as documented trade, or fund the guard note /
out-of-file key injection design (ties into decision 4).

## 7. Backlog: cross-provider verified-model persistence

**Status: DEFERRED — for cross-agent API design, not an ask.**

Bootstrapping per resume costs up to three one-token probes plus a duplicate
catalogue fetch (`ChatViewModel.kt:634/654/745`). Persisting which model ids
each provider has already verified would kill that cost, but the item was
parked pending an API design that outlives this session. Kept here so it
stays visible next to the decisions it must not be confused with.
