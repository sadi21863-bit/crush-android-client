# Corvid-Termux — separate project plan

> **Status: PLAN ONLY. Not started. Nothing in the existing app depends on this.**
> Decision taken 2026-10-04: the Termux-based approach becomes its own project.
> `OpenCode Chat` (bundled engine) continues independently and is not migrated.

---

## 1. Why this exists

Two different requests were conflated. Splitting them:

| Goal | Solved by this project? |
|---|---|
| **A real dev environment on the phone** — git, LSP, shell, toolchain | ✅ Yes. This is the entire point |
| 32-bit CPU support | ⚠️ Side effect, and a cheaper fix exists (see §7) |
| Fixing bugs in the existing app | ❌ No. Those are code bugs, not architecture |

The user who asked for this wanted an agent **inside a real Unix environment**, not
merely a chat UI over a bundled binary.

---

## 2. Verified facts (do not re-derive)

### Crush is Go, not Node.js

Crush is a **Go** program using Charm's Bubble Tea TUI.

- Crush repo languages: Go, Go Template, Shell, CSS, HTML, JS, Nix
- Charm blog: *"Kujtim reached for Go and the core of the Charm stack — Bubble Tea,
  Bubbles, Lip Gloss, and Glamour"*
- Verified directly: the bundled binary reports `"go_version":"go1.27.1"` from
  `GET /v1/version`

`npm install -g @charmland/crush` is a **distribution wrapper** that downloads the
Go binary. It is one of three install channels: Homebrew, npm, `go install`.

**Consequence:** `pkg install nodejs` is unnecessary and adds a failure mode
(npm's own arch detection). Prefer fetching the Termux-appropriate binary directly.

### Upstream ABI policy

Crush's `.goreleaser.yml` builds `goarch: [amd64, arm64, "386", arm]` but
**explicitly ignores** for android:

```yaml
ignore:
  - goos: android, goarch: amd64
  - goos: android, goarch: arm      # 32-bit ARM
  - goos: android, goarch: "386"
```

So `crush_*_Android_arm64.tar.gz` is the only Android artifact upstream ships.
This is a *release policy*, not a technical limit — see §7.

### Termux is a Linux userland, not Android

This is the key insight. Inside Termux you run the **Linux** build, not the Android
build. With **TermuxArch** (the F-Droid build for 32-bit devices) a 32-bit phone
gets a real armv7 Linux userland — which is a genuine 32-bit path that bundling
inside a single APK cannot offer.

### F-Droid, never Play

The Play Store version of Termux is deprecated. F-Droid is the only supported
source.

---

## 3. The hard part: the app ↔ Termux bridge

This is the make-or-break design question, and it is not optional. Verified from
Termux's own `RUN_COMMAND-Intent` wiki:

A third-party app needs **all three**, or intents are silently ignored:

1. `com.termux.permission.RUN_COMMAND` declared in the manifest **and manually
   granted** by the user via Settings → App info → Additional Permissions.
   *Declaring it is not sufficient.*
2. `allow-external-apps=true` written into `~/.termux/termux.properties`.
   **Our app cannot write this** — it is inside Termux's private storage. The user
   must edit the file.
3. On `targetSdk 30+`, a `<queries>` entry for `com.termux` (or
   `QUERY_ALL_PACKAGES`), or the service does not resolve at all.

Plus a declared `PluginResultsService` to receive stdout/stderr via `PendingIntent`.

**Design implication:** setup is inherently multi-step and partly manual. The
onboarding flow must *detect and verify* each precondition and tell the user
precisely what is missing, rather than failing opaquely.

---

## 4. Architecture

### Network: works cleanly

Android apps share the loopback interface, so the existing HTTP/SSE contract is
unchanged:

```
Corvid-Termux app ──http://127.0.0.1:PORT/v1──▶ Crush (running inside Termux)
```

`CrushApi`, `CrushEventStream`, the workspace/session model and the 30s
workspace-reap handling all carry over unchanged.

### Filesystem: does not work

```
app workspace:   /data/data/com.opencode.chat/files/     ← app can read
crush workspace: /data/data/com.termux/files/            ← app CANNOT read
```

Android's sandbox makes these mutually invisible. Consequences:

- No file picker or diff viewer over the agent's workspace
- `AppLog` / `EnginePaths.logFile()` point at the wrong directory
- The app's notion of "where is my workspace" is no longer true
- The agent's file edits are invisible to the app UI

Termux's `ContentProvider` can bridge *some* of this, but only for explicitly
granted paths and only with the permission in §3.

### Process control: lost

The bundled architecture `exec()`s the engine and holds a PID. That enables
`EngineSupervisor`: hang detection, restart, idle-shutdown control, dynamic tuning.

With an external engine the app has a **port, not a process**:

- Hang detection degrades to "is the port answering?"
- Restart means "ask Termux to re-run the command"
- Crush's 60s idle self-shutdown becomes a problem to solve, not a setting to own

---

## 5. ⚠️ Security: `termux-api` exposure

**Termux:API can send SMS, take photos, read location, and access the camera.**

The bundled engine runs with `PATH=/system/bin` and has no such surface.
An agent running inside Termux could potentially reach those capabilities.

This is a real capability increase and needs an explicit decision, not an
accident of packaging. Options to consider:

- Keep `termux-api` uninstalled (it is a separate APK — this is the default state)
- Ship with `termux-api` absent and document that installing it widens the agent's reach
- Add an explicit warning in onboarding if `termux-api` is detected

---

## 6. Phased plan

### Phase 0 — feasibility spike (do this first, 1–2 days)
Nothing else should be built until these are answered.

1. Install Termux (F-Droid) on a 64-bit test device
2. Fetch the **Linux arm64** Crush binary (not the Android one) into Termux
3. Determine how to start the **HTTP server headless** — the TUI normally owns the
   terminal. Find whether a `serve`/server-only mode exists, or whether the TUI
   must attach to a pty
4. From the *existing* app, probe `http://127.0.0.1:PORT/v1/version` and confirm
   loopback is reachable cross-app
5. Confirm the server survives the Termux session being backgrounded

**Kill criterion:** if step 3 has no clean answer, or the server cannot be started
without an interactive TUI, this project is not viable as designed. Stop here.

### Phase 1 — bridge
- `TermuxBridge`: detect Termux, check `allow-external-apps`, check the permission
- `EngineHandle` interface with `TermuxEngine` implementation
- Surface every missing precondition in onboarding, specifically
- Retry/backoff when Termux is not yet running

### Phase 2 — reconcile the state model
- Decide where sessions/workspaces live and how the app reads them
- Replace PID-based supervision with port-based liveness
- Decide who owns Crush's idle shutdown

### Phase 3 — surface the wins
- git integration (clone/branch/diff via Crush's tools)
- LSP servers actually usable
- real shell

### Phase 4 — only then, consider merging
If, and only if, the external engine proves more reliable than the bundled one,
revisit making it an in-app option. Until then it stays a separate product.

---

## 7. Cheaper alternative to check first

If the real goal is **32-bit support** rather than a dev environment, there is a
much cheaper path than a second app:

**Cross-compile Crush for `android/arm32` ourselves.**

- Pure Go, `CGO_ENABLED=0` — no NDK, no Termux
- Clone `charmbracelet/crush` at `v0.97.1`, run `GOOS=android GOARCH=arm go build`
- Drop the binary into `jniLibs/armeabi-v7a/`
- Upstream's exclusion is release policy, not a technical limit

This keeps one app, one architecture, process ownership, filesystem access and the
permission model. Cost: a pinned build step and a larger APK.

**Do this evaluation before committing to Termux.** A dev environment and 32-bit
support are different requirements with very different costs.

---

## 8. Open questions

- Does Crush have a headless/server-only mode, or does the TUI have to own a pty?
- Can `termux-wake-lock` / a foreground service keep Crush alive reliably on
  ColorOS and MIUI, or does the second app make survival *worse*?
- Is `allow-external-apps` acceptable security-wise, given it lets a granted app run
  arbitrary commands in Termux?
- What is the realistic install funnel for a non-technical friend?
- Should this be a separate Play Store listing, or a GitHub-release APK only?

---

## 9. Explicitly NOT in scope

- Migrating `OpenCode Chat` to this architecture
- Replacing the bundled engine
- Any change to the existing app's code
- 32-bit support for the existing app (see §7)
