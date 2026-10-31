# v0.99.0 isolated experiment — evidence log

Branch: `v099-experiment` worktree build (suffixed `applicationId = .v099`,
so it coexists with the reference install and never disturbs the z-lab
0.98.2 engine baseline). Log opened 2026-10-31.

## Stage 1 — binary + build + boot (agent, complete)

- **Source provenance**: upstream release `v0.99.0` (published 2026-10-29),
  tarball `crush_0.99.0_Android_arm64.tar.gz`, sha256
  `d18cd1f961a4c587f26a50aad8ef1fed38ad4362bb6237807e57cda1c170f78c` verified
  against the signed release `checksums.txt` before use; the pin lives in
  this branch's `tools/fetch-crush.ps1` (default advanced to 0.99.0 for this
  branch only).
- **Static API-surface diff vs the in-tree 0.98.2** (printable-string
  extraction of both engines): net delta ≈ one config variant — `/v1/config`
  gains `host_theme`; new `/v1/docs/` OpenAPI hosting; permission machinery
  gains `PermissionRequest`/`Outcome`/observer types and lifecycle hooks
  (additive); **no `/v1/workspaces`, SSE frame, or attach-surface changes
  visible**. Prediction: near-drop-in for this client.
- **Build**: `assembleDebug` green in the worktree (10m57s cold, 36 tasks).
- **Install**: `adb install -r -t` → Success, package `com.crush.mobile.v099`
  alongside the reference app.
- **First boot**: MainActivity Displayed +4.2 s with ProfileInstaller; no
  FATAL/ANR; app sits on onboarding (fresh app data — expected).

## Stage 2 — engine-level verdict (needs one human tap)

The engine spawns only after onboarding, so the boot verdict on-device needs
the user to complete onboarding in the EXPERIMENT app (pink `.v099` icon):
pick a provider, paste a key of their choosing, and open the chat once.

Agent then reads `logcat` for (TAGs `CrushEngine`, `CrushMobile`,
`EngineSupervisor`):
1. `unix://` bind line on the v0.99.0 engine,
2. readiness within the 60 s gate over the UDS socket,
3. a workspace/session bootstrap + SSE connect,
4. the engine's own banner: buildinfo should read `v0.99.0` (check
   `go buildinfo` strings in logcat or via `dumpsys`? — the boot log line is
   authoritative).

## Verdict rules

- PASS = all four Stage-2 items observed → record "drop-in candidate";
  upgrade remains a USER decision (the evidence engine of record stays
  0.98.2+dirty until the main branch chooses).
- FAIL = bind/readiness/SSE wrinkle → capture the exact failure line here;
  the branch exists precisely to hold that diff without touching main.
