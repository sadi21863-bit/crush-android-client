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

**ARTIFACT PROVENANCE (structural, discovered 2026-11-12):** `libcrush.so` is
GITIGNORED by design (`.gitignore: app/src/main/jniLibs/arm64-v8a/*`) — the
engine binary is a LOCAL artifact, never a git object, and CI never builds
with a checked-in engine. A fresh clone MUST run `tools/fetch-crush.ps1`; the
script's recorded SHA-256 (from the signed release's `checksums.txt`) plus the
T1 strings scan over the WRITTEN file are the provenance chain. A version
branch therefore carries the constants + docs, not the binary — the device
build from that checkout is the candidate.

Parser/route note: the engine's version string is logged at boot
(`bindEngine state=Running(... version={"version":"vX.Y.Z"...})`) — that line is
the device's own statement of which artifact is running.

**Adapter invariants learned live on the first v0.100.0 T3 run (2026-11-12) —
every future bump re-checks all three:**

1. The version claim is the WHOLE JSON object (`{"version":"v0.100.0",...}`),
   not a bare semver. Parse the quoted `version` field first. A
   leading-semver-only parser returned null, silently chose the pinned family,
   and produced a live `404` on `/permissions/skip`.
2. A re-attach must replay the SAME permission wire family the workspace was
   opened with. Making `open()` version-aware is not enough: `reattachIfStale()`
   re-POSTs the workspace, and if it drops the stored wire it falls back to the
   old family — a live `404` on every re-attach (the ~30 s reap vs 15 s lease
   makes that hit normal turns).
3. The unknown-version fail-safe follows `EngineCatalogues.PINNED_VERSION` (the
   engine actually shipped), never a hardcoded family.

The JVM gate pins all three (`EngineProtocolTest` JSON rows;
`BootstrapOrderTest` re-attach both directions). T3 still greps `files/app.log`
for `permissions/skip ... 404` and `setPermissionMode ... failed`: **zero is
the pass**, because only the device proves which route actually ran.

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
| 1b | Permission route | `bootstrap permission wire: ... engineVersion={"version":"v<row>"...} wire=<family>` then `open: permissionWire=<value>` on every attach/re-attach; ZERO `permissions/skip ... 404` and ZERO `setPermissionMode ... failed` in `files/app.log`. The wire must match the row's API contract (v0.99 = SKIP_FLAG/null; v0.100+ = MODE_ENUM) |
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
9. Install + device rails (T3) green — including rail **1b permission route**
   (`bootstrap permission wire` + zero skip/mode 404s; the adapter's only true
   proof is the device log).
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
- 2026-11-12 candidate run: the FIRST v0.100.0 T3 run exposed an adapter bug
  that no JVM test had covered — the initial attach spoke `MODE_ENUM`, but
  every `reattachIfStale()` fell back to the removed `/permissions/skip` and
  404'd. The runner now carries **rail 1b** (two checks: the version-appropriate
  family was chosen; zero permission-route 404s), and `BootstrapOrderTest` pins
  both directions in the JVM gate, so a future bump cannot pass on the wrong
  family. Fixed + re-run green.
- **T3 MEASURED 2026-11-12 on candidate `be35981` + `84d4bfa` (device
  `bbf8ca69`, engine v0.100.0).**
  - Scripted half: **8 PASS / 0 FAIL** (`-ExpectedVersion 0.100.0
    -MinDenied 33`) — version, bootstrap health (attempts=1, failures=0,
    one designed locked-refusal), rail 1b (`wire=MODE_ENUM`,
    `open: permissionWire=normal`, zero route 404s), on-disk deny readback
    **35 ids**, no FATAL.
  - Rail 5 (F04, imported folder): swapped `Test project folder/crushrc`
    bytes → row tap → CHANGED_SINCE_CONSENT dialog. Cancel: marker
    unchanged, zero supervisor starts, DB untouched. Allow: consent stamp
    `1794484916475`, engine start `17:31:56`, marker `1794484921`, DB
    fingerprint == SHA-256 of the pulled bytes. Consent provably precedes
    attach.
  - Rail 7 (restore): project binding cold boot → restore + gate while
    locked → `bootstrap refused: locked` → unlock → `resuming session
    9c17ad6a`; Batch G cold boot → `handoff resume: restoring project
    'Legacy workspace' + trace session b894b36f` → `resuming session
    b894b36f (25 msgs; the handoff trace's target)`; handoff `65770c54`
    stayed DISPATCHED with its stamp byte-identical.
  - Rail 8 (E6): Legacy↔Test switches logged stop-on-switch, children
    never exceeded 2 (prewarm + active), ended at 1, no FATAL.
  - Rail 9 (rollback): main `d60f7c9` + engine 0.99.0 (release hash
    verified) installed over the v0.100 data. v0.99.0 booted, unlocked,
    resumed `b894b36f` with all 25 messages, and the engine state DB was
    unchanged after the open (goose `goose_db_version` 13, sessions 2,
    messages 25). **v0.99 opens v0.100 data; no one-way door.**
  - Correction for future runs: the **legacy row tap intentionally bypasses
    F04** (commit `4058588`: legacy binds the unkeyed shared engine) — only
    imported project rows gate. The Nov 5 F04 proof was on the project row
    `536fa26e`, not legacy. A benign crushrc planted at
    `files/workspace/crushrc` therefore executes on a legacy row tap with no
    dialog; it is app-private and the handoff/restore paths for the Legacy
    *project row* do gate. Recorded as pre-existing design, not a v0.100
    regression.
  - Rail 7b (same-destination dispatch, owner finger) — **MEASURED on the
    reinstalled candidate**: source chat `b894b36f` (inside Legacy
    `077dc19e`) → sheet → Legacy preselected → owner's Confirm. ONE insert
    (`eb78ea9d`), APPROVED at `1794488827242`, DISPATCHED at
    `18:37:07.936` on the first try to engine session
    `755fcfd0-0462-47e6-b27e-f4e3d0276061`; the gate's consent stamp
    `1794488827189` precedes the approval by 53 ms (S-02 order). DB diff:
    exactly one new row, **all nine prior handoff rows byte-identical**
    (including `65770c54` — status, target session `b894b36f`, stamp
    `1794412509337` and summary hash unchanged); the only project change was
    `077dc19e.consented_at_ms` (the gate re-stamp). The new session landed in
    `files/crush-p077dc19e/state/crush.db` (sessions 2→3, messages 25→41,
    title "Handoff fingerprint mismatch audit") and the first turn completed
    (six granted bash asks, `open: permissionWire=normal` on the v0.100
    family, `file changes: +0 -0`); zero 404/FATAL.
- Remaining before merge: the docs fold only. Rails 3/4 and the
  `sub_agent_report` SSE render check stay advisory (the deny readback and
  tool_call parsing cover the same config/parse surface) plus the two
  product decisions named in the ladder plan (Sysadmin Mode surface; piped
  permissions).
