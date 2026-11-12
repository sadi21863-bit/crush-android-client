# Engine acceptance kit — testing any future Crush engine version (2026-11-12)

Status: **THE SYSTEM, SHIPPED IN CODE** — manifest + ratchet + fetch pins live
in `main`; the v0.100.0 row is pre-staged (strings-CONFIRMED out-of-tree) and
its device rails are the owner-gated next step. This document is the runbook
every future engine version follows, and the v0.100.0-specific execution plan
is [`v0100-ladder-plan-2026-11-12.md`](v0100-ladder-plan-2026-11-12.md).

An engine is an **executable artifact we ship and exec**: provenance, catalogue,
and runtime behavior are the three axes, and each has a tier below. The kit's
rule: **no engine version ships uncovered and unproven; every claim names its
tier and its evidence** — the same standard this repo applies to every other
security surface.

## 1. The artifacts (what the kit IS)

| Artifact | File | Role |
|---|---|---|
| Catalogue manifest | `app/src/main/java/com/crush/mobile/data/crush/EngineCatalogues.kt` | one row per engine version (`version`, `builtinTools`), `PINNED_VERSION`, `missingDenials()` |
| API-surface contract | `app/src/main/java/com/crush/mobile/data/crush/EngineApiContracts.kt` | one row per engine version: permission endpoint/field mechanism + new SSE shapes; `gapsFor(version)` = the upgrade-work list |
| Ratchet tests | `app/src/test/java/com/crush/mobile/data/crush/EngineConfigInjectorChatPolicyTest.kt` | **every** known catalogue row must be fully denied; manifest shape (uniqueness, pinned row present, exact v0.99→v0.100 delta) |
| Contract tests | `app/src/test/java/com/crush/mobile/data/crush/EngineApiContractTest.kt` | the PINNED surface stays gap-free; the NEWEST surface's gaps are EXACTLY the documented upgrade work; both manifests know the same versions |
| Acquisition | `tools/fetch-crush.ps1` | downloads the OFFICIAL `crush_<v>_Android_arm64.tar.gz`, hard SHA-256 pin (fails closed on unpinned), AArch64-PIE structure check, extracts to `jniLibs/arm64-v8a/libcrush.so` |
| Device rails runner | `tools/engine-rails.ps1` | read-only tier-T3 checks from the app's own evidence: engine version line, bootstrap health, on-disk deny readback, tool-wall observation, crash scan |
| Device runbook | this doc, §4 | the scripted device rails every bump runs |
| Evidence ledger | `docs/capabilities.md` + status hub | one fold per shipped engine, with the tier results |

The two manifests are deliberately joined: a version is "known" only when BOTH
its tool catalogue and its API surface are recorded, and a test asserts the
version sets match — the ratchet answers "are the tools denied?", the contract
answers "does the app speak this engine?", and neither can drift alone.

Parser/route note: the engine's version string is logged at boot
(`bindEngine state=Running(... version={"version":"vX.Y.Z"...})`) — that line is
the device's own statement of which artifact is running.

## 2. The tiers

**T0 — MANIFEST (host, minutes).** Add the new version's row to
`EngineCatalogues.KNOWN` BEFORE touching any binary. Provenance: upstream
`internal/config/config.go` `allToolNames()` for the tag when reachable, else a
`strings` scan of the OFFICIAL Android artifact (record which, and the scan
command, in the row's fold). Never infer ids from release notes alone — the
v0.100.0 row began as release-note ids and was then STRINGS-CONFIRMED.

**T1 — HOST ARTIFACT (host, minutes).** `tools/fetch-crush.ps1 -Version <v>
-ExpectedSha256 <hash from the SIGNED release's checksums.txt>`; the script
verifies the tarball hash BEFORE extraction, then structure (PIE, e_machine
183). Then re-run the strings scan over the artifact the script wrote and
assert: every manifest id present; the exhaustiveness probes absent
(“agent_task” for v0.100.0); **any new id → T0 first** (add to the row and the
deny list, re-run the gate), never "we'll deny it later".

**T2 — JVM GATE (host, ~20–35 min).** Full gate:
`:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
:app:lintRelease`. The ratchet test is the gate that BLOCKS a version whose
catalogue is not fully denied; a red ratchet names the missing ids.

**T3 — DEVICE RAILS (device, ~30–45 min, owner-driven where noted).** Every
row below is an assertion with its evidence sink (`files/app.log`, DB pull, or
screenshot). Run on a BRANCH build; main advances only after green.

| # | Rail | Pass evidence |
|---|---|---|
| 1 | Boot + attach | `bindEngine ... version=v<row version>`; `bootstrap instance=...`; no `bootstrap failed` |
| 2 | Deny readback | the instance config on disk carries ALL `CHAT_DISABLED_TOOLS` ids; spawn gate logs `policy installed/verified` (readback verifier). The chat pool's config lives at `files/crush-chat/data/crush.json` (measured 2026-11-12 — `cfg/` is empty; `data/` is the chain's real file) |
| 3 | Zero-tool wall (Chat) | a CHAT-pool turn that asks for shell/read/write yields ZERO `observed tool_call` events + the denial note renders |
| 4 | Advertisement probe | a FRESH agent listing attempt reports no callable tools; long-lived stale advertisement recorded as an upstream fact if present |
| 5 | crushrc semantics | plant the benign sentinel in an imported folder → F04 Required → Allow → sentinel executes on attach. MEASURED 2026-11-12: the engine SOURCES the workspace-root crushrc as a shell script — a bare-word file hard-failed config load with exit 127, an `echo` line loads and runs. Execution-count per attach is RE-MEASURED per version and recorded (upstream source shows one execution per config path per `config.Load`, plus `ReloadConfig`; the earlier "twice per attach" note is app-side observation until re-proven) |
| 6 | Session/API shapes | sessions list/create + messages replay on the bound workspace; SSE `observed tool_call shape` lines for v0.100+ include sub-agent report parts / structured question answers — the UI must render unknown parts as text and never crash |
| 7 | Handoff E2E | one same-destination resume dispatch (no insert, stamp survives) and one cold-boot resume (Batch G: the trace's own session opens deterministically) |
| 8 | E6 switch stress | A↔B project switches: stop-on-switch, children ≤ budget, no stale retries |
| 9 | Rollback drill | reinstall the previous app build; the previous engine binds and the app boots (record whether the newer engine's data dir is rejected, and say so) |

Owner-tap rails: 2–3 need a physical finger only where the sheet's Confirm/
Dispatch/Discard or the lock credential is involved; everything else is
synthetic (the accepted synthetic map in `docs/capabilities.md`).

## 3. Promotion, channels, rollback

- **Promotion rule:** branch → T0–T2 green → T3 green on device → merge to
  `main`; **any** red tier keeps `PINNED_VERSION` where it was.
- **Channels:** the engine of record is ALWAYS a stable tag with a recorded
  checksum. Nightlies (or any pre-release) ride a branch and the SAME rails,
  labelled `nightly-YYYYMMDD` in the manifest row; a nightly never becomes the
  default.
- **Rollback:** revert the `jniLibs` binary + `PINNED_VERSION` (and the fetch
  pin if it was new); manifest rows are additive and harmless to keep. The
  engine's session data lives under its own per-instance dirs — the rollback
  drill (rail 9) is what proves the older engine still opens them; if it does
  not, the rollback note must say so and the data dirs are preserved untouched.
- **Evidence:** fold the tier results into `docs/capabilities.md` (one bold
  paragraph per engine, artifact provenance included) and add the status-hub
  chain row.

## 4. Adding a version — the exact order (copy-paste)

1. `git switch -c engine-vX.Y.Z`
2. Add the row to `EngineCatalogues.KNOWN` (provenance in the comment).
3. Local JVM run of the ratchet test → it names every deny gap.
4. Add those ids to `EngineConfigInjector.CHAT_DISABLED_TOOLS` (deny FIRST).
5. Record the tarball's checksum in `tools/fetch-crush.ps1` (from the SIGNED
   release's `checksums.txt`; a wrong hash means STOP, not retry).
6. Fetch; re-run the strings scan over the written `libcrush.so`; new ids →
   step 2.
7. Bump `EngineCatalogues.PINNED_VERSION`.
8. Full gate (T2) green.
9. Install + device rails (T3) green.
10. Fold evidence; merge; record the chain row.

## 5. v0.100.0 kit state (as of 2026-11-12)

- T0: catalogue row added (33 ids; strings-confirmed 2026-11-09 out-of-tree,
  the `agent_task` probe absent → exhaustive) AND the API-surface row added —
  `gapsFor("0.100.0")` pins exactly four upgrade items: the permission
  endpoint (skip→mode), the workspace permission field (yolo→enum), and the
  two new SSE families (`sub_agent_report`, pinentry/SSH events).
- T1: **FETCHED AND VERIFIED IN-TREE on branch `engine-v0100`** — the
  pinned SHA-256 matched the signed release, the written
  `libcrush.so` (94.8 MB AArch64 PIE) strings-scan is 33/33 catalogue ids,
  the `agent_task` probe is absent (exhaustive), the version strings say
  v0.100.0 and v0.99.0 occurs zero times; `PINNED_VERSION` = "0.100.0" on
  the branch only. `main` stays v0.99.0 until the rails pass.
- Deny list: pre-grown with `agent_dispatch`/`agent_send`/`agent_status`/
  `agent_stop` on `main` (inert under v0.99.0; fully covers the v0.100.0 row).
- Rails runner: `tools/engine-rails.ps1` shipped; a live run on the pinned
  build passes 6/0 (version v0.99.0, bootstrap health, on-disk deny readback
  count=31 via `files/crush-chat/data/crush.json`, no FATAL) — the tool-wall
  and interactive rows stay observations by design.
- Remaining: T2 (this batch's gate), the permission adapter (implemented in
  code by `EngineProtocol` — the dual-family DTO/API/WorkspaceManager/Session
  path with unknown versions failing safe to the pinned family; its v0.100
  half is proven at the rails, not by the JVM gate alone), and T3 (owner +
  device), plus two product decisions named in the ladder plan (Sysadmin Mode
  surface; piped permissions).
