# Crush engine v0.100.0 research + upgrade-ladder brief (2026-11-08 evening)

Status: RESEARCH READY — gathered from live GitHub data (release notes, asset
lists, compare trees, issue tracker) plus web sources while the S-02
hardening gate ran. NO CODE changes; this doc is the decision brief for the
post-P6 engine-upgrade branch the owner plans ("test it in a separate github
branch" after Phase 6). Facts verified against `gh release view` / `gh api`
data on 2026-11-08.

## 1. Release timeline (verified)

| Tag | Date | Commit | Note |
|---|---|---|---|
| v0.99.0 | 2026-10-29 | e38655a | OUR PIN (promoted into the app 2026-11-01; binary strings `v0.99.0` + `go1.27.1` verified in jniLibs) |
| v0.100.0 | 2026-11-05 17:47 | c7854d5 | "Subagents, Sysadmin Mode, PGP/SSH Prompts" |
| nightly | 2026-11-08 00:45 | (auto) | **19 commits after v0.100.0** (subjects in §4) |

Pack lines v0.98.x are before our window. gh-verified asset list for
v0.100.0: 67 artifacts.

## 2. v0.100.0 — what actually changed (from the release notes)

- **Subagents** (`965d1a7`/`938fef2`): Crush can dispatch DETACHED sub-agent
  tasks, message them mid-run, move them to a different model, stop them, and
  receive a **sub-agent report as its own message part** (`4efcd2c`). Dozens
  of sub-agent lifecycle fixes (reports queued until read, parents told why a
  sub-agent stopped without a report, history hygiene `1662cbd` keeps
  sub-agent prompts OUT of the prompt history, capped forgetting of finished
  sub-agents, cancel/stop/report racing fixes).
- **Sysadmin Mode** (`44cb8e3`): a permission mode where Crush can run ANY
  command ("be careful"); reachable via command palette / Shift+Tab cycle +
  `9910443`.
- **Configurable safe/blocked commands** (`526da24`, `46c7bc4`):
  `permissions safe|block|unblock` shape the dangerous-command list per
  project; yolo mode **stops asking about dangerous commands** (`c42442d`)
  and blocks them instead.
- **Permission-semantics hardening wave**: parsed-command auto-approval
  instead of prefix (`d3b8997`), backslash-escape rejection in literal words
  (`7f1500e`), an unreadable command no longer waives its neighbours'
  checks (`15c37e3`), block list applied to scripts and builtins
  (`ac2ce4a`), chaining detected by parsing (`7690581`), common evasions
  hardened (`963a600`), case-insensitive dangerous matching (`ab1125f`),
  plain-words danger sentences (`5951fe1`), "make a dangerous permission
  prompt harder to approve by reflex" (`e498ee2`), session approvals SCOPED
  to what was actually approved (`fd40067`), the server reports the
  permission mode it ACTUALLY enforces (`95d807a`).
- **PGP/SSH in prompts** (`16b7ee1`, `a5a7820`, `8a3ff03`): custom pinentry
  and sshpass-style drivers so GPG/SSH secrets can be typed at agents.
- **Shell/robustness**: memory cap for kept command output (`f88e147`),
  pubsub keeps only the newest pending update for slow subscribers
  (`2000857`), title generation can't outlive its run (`280ea89`),
  truncated/provider-stopped responses surfaced (`9345eef`).

## 3. THE ANDROID BUILD — verified true (correction applied 2026-11-09)

The ladder branch consumed this fact FIRST, so the record must be exact:
**the official Android artifact is NOT new in v0.100.0** — v0.99.0 already
shipped the identically structured `crush_0.99.0_Android_arm64.tar.gz`
(+ sbom), and THIS repo's fetch path was recorded against it. What
v0.100.0 adds is new CONTENT (subagents, Sysadmin Mode, the four agent
tools), not a new artifact shape. The branch's script comment states the
verified why, and the v0.100.0 asset was independently cross-checked:
`crush_0.100.0_Android_arm64.tar.gz` byte-hash equals its checksums.txt
line (`cdb15f23393c7e334b059a37d2c41a12fcb5f4d02d6e940795b7707e4b0afeaa`),
unpacks to `crush_0.100.0_Android_arm64/{LICENSE,README,completions,
manpages,crush}`, and the binary is an AArch64 PIE (e_type=3, e_machine=
183, ~94.8 MB). The full per-platform flow rides the release (deb/rpm/
Windows zips/BSD/netbsd/openbsd + **Alpine `apk` packages — NOT Android**;
naming trap worth recording). checksums + sigstore (`checksums.txt`,
`checksums.txt.sigstore.json`, packslip.sigstore.json) ride the release.
IMPACT for us: the ladder takes the OFFICIAL android arm64 artifact into
`app/src/main/jniLibs/arm64-v8a/libcrush.so` with checksum verification —
`tools/fetch-crush.ps1` on the `engine-v0100-ladder` branch is already
rebuilt for exactly this (Version 0.100.0; strict checksum parse BEFORE
extraction; `-ExpectedSha256` still wins).

## 4. Upcoming updates — 19 nightly commits after v0.100.0 (subjects, gh-verified)

`exit cleanly when the TUI is started without a terminal`; `render question
answers from structured data, not parsed text (#3903)`; `preserve JSON
number precision in fetch (#4131)`; `theme the exit banner`; `show a sent
prompt instantly in long chats`; `persist model display name on messages
(#4243)`; `permissions: yolo mode blocks dangerous commands without
asking`; `CRUSH_DATA_DIR env var to override the project data directory
(#4245)`; `stop a cancelled or silent stream instead of hanging the turn`;
own cancel/timeouts in one request guard; rename cancelled-tool render
helper; pin fantasy v0.46.0; ci/golangci updates; keep scrolled lists
anchored; clamp dialog/shell scroll offsets.

Notable for OUR app: the **cancelled/silent-stream turn-hang fix** touches
exactly our free-model "no reply received" family behavior; the
sub-agent/question-render changes touch our SSE payload renderer (`ToolActivity`
shape logs); `CRUSH_DATA_DIR` maps to our per-instance state-dir philosophy.

## 5. The owner's upstream footprint (verified today)

- **#4092** (CLOSED): "server: Unix-domain-socket listen address works but
  is undocumented; consider optional token auth for TCP listeners" — THE
  OWNED CONTRIBUTION this build's transport lane is derived from (S-04
  fail-closed UDS-transport design rode the same semantics; the docs+
  auth idea drew our attention as an important support).
- **#4087** (CLOSED): Groq provider unusable out of the box - catalogue only
  contains retired ids (404s) and TPM-cap trouble - the provider-catalog
  drift theme the app's catalogue honesty rides.
- **#4222** (OPEN, filed by the owner): "Plan catalog drift: agent/init 500s
  'large model not found in provider config' for a model the plan's /models
  list and gateway still serve (opencode-go / beam-free)" — EXACTLY tonight's
  Beam Free case on the desktop side; our app HIDES beam-free until the
  engine knows the id (picker gate, earlier today), and upstream crush is
  still owed the fix. If it lands in a future tag, Beam Free becomes usable
  END-TO-END in our app on the Go plan without any custom provider hack.
- gh search found NO commits authored by the user in crush (the contribution
  is issue-driven; the `chore(legal): @Axenide has signed the CLA` line in
  nightly is unrelated).

## 6. The upgrade ladder (decision framework for the post-P6 branch)

Rule from the S-01 catalogue-parity batch: the RATCHET TEST blocks any
engine bump until `CHAT_DISABLED_TOOLS` re-covers the whole catalogue.

1. `tools/fetch-crush.ps1` Version bump v0.99.0 -> v0.100.0 (or nightly),
   artifact = the official `Android_arm64` tar.gz + checksum/sigstore
   verification (NEW possible since this release).
2. Catalogue re-diff (strings + `allToolNames()`): v0.100.0 grows agent
   tools `agent_dispatch`, `agent_send`, `agent_status`, `agent_stop` -
   the Chat deny list must gain them BEFORE the first spawn gate passes.
3. Permission-mode surface decision (owner): Sysadmin Mode must NEVER be
   reachable from the Chat pool (deny-all, forced ASK, and the pool's
   permission surface is not user-selectable today - verify on the branch);
   in Code mode, whether to expose Sysadmin is an explicit product decision
   that MUST default to the current ASK/Auto-approve pair.
4. Sub-agent message parts + question-structured answers reach the SSE
   pipe: the app's renderer must tolerate unknown part types (render as
   text, never crash) - the ToolActivity logger's "observed tool_call
   shape" lines are the live canary.
5. `CRUSH_PERMISSIONS_PIPED=1` (v0.99.0): the HOST answers permission
   prompts; our app IS the host - an owner decision whether to adopt it
   (it would change the ask-card UX model).
6. Beam Free (#4222) re-test as the upgrade's model-side acceptance:
   pick it, run a turn; if agent/init 500s, the issue is still open.
7. Device gates: the S-01 policy readback, the trust A03 flow, the E6
   switch stress, and the LSP-family deny probe re-run on the branch
   build BEFORE any promotion to main.

Not in scope until the ladder lands: anything from the v0.99.0 hooks
framework for OUR flows (PostToolUse hooks could later implement honest
redaction of tool outputs app-side).

## 7. Verification note

Facts above were pulled live from `gh release view/list/api` and the
web-released notes; the Android artifact name, the 67-artifact asset list,
the 19-commit nightly delta, and the three issue states are all
gh-verified today. The "apk" files are Alpine packages (Linux), NOT
Android - record so nobody greps the wrong artifact.
