# Upstream outreach plan — Charmbracelet/crush engagement + public repo presentation

Status: **plan, not shipped work.** Nothing in "Part 1" has been sent, nothing in
"Part 2" has been recorded, nothing in "Part 3" has been applied to the repository
pages yet. This file packages up material measured on the device ledger so the
P12 phase of [crush-plan-2026-11-02.md §25](crush-plan-2026-11-02.md)
("Official collaboration and public recognition") can be executed in one pass.

Source material: [capabilities.md](capabilities.md),
[external-review-triage.md](external-review-triage.md),
[engine-egress-dns-2026-11-02.md](engine-egress-dns-2026-11-02.md),
[session-transcript-bleed-2026-11-02.md](session-transcript-bleed-2026-11-02.md),
[providers/opencode-go.md](providers/opencode-go.md),
[p1-attack-surface.md](p1-attack-surface.md), [README.md](../README.md).

Repository of record: `sadi21863-bit/crush-android-client` (local folder name is
not the repo name). The app is **unofficial and independent**; the bundled engine
is upstream Crush **v0.99.0** (published 2026-10-29, promoted in-tree 2026-11-01)
running as a child process over an app-private Unix-domain socket.

## Evidence honesty rules

Same grading as the roadmap, applied to every claim below:

| Grade | Meaning |
|---|---|
| A | Publicly confirmed (visible commit/workflow/release/repo pages) |
| B | Repository-reported device outcome (self-measured, not independently rerun) |
| C | Architecture proposal — designed, not present |
| D | Experimental — needs physical-device proof before anyone repeats it |

Two standing rules for anything this plan sends to the public:

- **Nothing is claimed beyond the ledger.** Behaviors not measured on the one
  physical device (Redmi Note 7 Pro, Android 10 / API 29) are marked D-grade or
  not mentioned. Features not built are *named as absent*, never implied.
- **No secrets.** Never paste provider keys, key-shaped strings, session-header
  values, or stored ciphertext into any post, caption, or screenshot. Provider
  settings screens are never shown on camera without the key column hidden.

---

# Part 1 — WHAT WE COULD UPSTREAM (prioritized)

Prioritization rule: *evidence strength today × generality to other hosts ×
ease of landing the ask.* "(a)–(f)" below follow the same letters as the
packaging request for traceability.

| Rank | Contribution | Kind | Evidence | Likely crush area |
|---|---|---|---|---|
| 1 | (b) Engine DNS on Android (static Go + no `resolv.conf`) | DOC-first (+tiny optional CODE) | B (device 2026-11-02) | engine startup/diagnostics, docs |
| 2 | (a) Child-process embedding + app-private UDS transport | DOC-first (+optional CODE if wanted) | B (device 2026-10-18) | `-H` listen scheme, docs |
| 3 | (c) Long-lived SSE + session continuity seams | DOC-ONLY | B (device, Oct–Nov 2026) | event publishing, workspace lifetime |
| 4 | (e) ASK-mode approval + question-batch delivery on a host | DOC-ONLY | B (device 2026-10-29 → 2026-11-01) | permission/question events, docs |
| 5 | (d) Provider gateway realities (402 classes, retirement, lag) | DOC-ONLY | B (probes, Oct–Nov 2026) | provider catalogue + error surfaces |
| 6 | (f) Zero-retention vault / key-at-rest pattern | DOC-first (CODE = proposal sketch) | B (app) / C (engine mechanism) | config/credential storage |

Sequencing note: do **not** open six threads at once. (b) is the freshest
full-strength finding and deserves its own issue; (a) continues an existing
maintainer-acknowledged thread; (c)+(e) share one "host contract" post, split
into focused issues only if maintainers engage; (d) and (f) are discussion-tier.

## (b) Rank 1 — Engine DNS on Android: static Go binary falls back to loopback :53

**What was measured (B, 2026-11-02).** The bundled engine (`libcrush.so`) is a
statically-linked Go binary spawned as an app child process. Android has no
`/etc/resolv.conf` (and `/system/etc/resolv.conf` is not app-writable), so Go's
pure resolver falls back to its compiled-in default nameservers
`127.0.0.1:53` / `[::1]:53` — and nothing on the phone serves DNS on loopback
(`ss -lntu`: no :53 listener). The defect existed as early as 2026-10-21 in
`crush.log`; the success record shows the truth: turns succeeded only while
the phone was tethered — the DHCP/DNS forwarder that tethering raises briefly
serves loopback :53 as a side effect — and standalone use died on every
provider call with `lookup … [::1]:53 … connection refused`. The engine never
had a resolver of its own; egress survived on borrowed listeners.
App-side fix, shipped and device-validated 2026-11-02 (19:05 debug turn, 20:07
R8/r8probe turn, user's unplugged LTE pass): the app runs a tiny loopback
**HTTP CONNECT bridge**, spawns the engine with
`HTTPS_PROXY/HTTP_PROXY` → `127.0.0.1:<free port>` (+ `NO_PROXY=127.0.0.1,
localhost,::1`); Go's `httpproxy` honours the env, so the engine dials only
127.0.0.1 — **zero DNS on the engine side, by construction**. The bridge
resolves the CONNECT host with the JVM's resolver (bionic/netd
network-agnostic) and relays raw bytes; TLS stays end-to-end.

**Why upstream should care.** Any statically-linked Go agent run as a child
process on Android — and the same class of host without a `resolv.conf`
(minimal containers, scratch images, embedded boards) — hits the identical
wall, and the symptom is a liar: tooling that resolves through the host's own
bionic/netd-backed resolver looks healthy while every engine-initiated turn
dies, so it reads as "network broken only when standalone" and burns hours on
the wrong layer. Crush itself can adopt the guard natively at near-zero cost:
(1) a one-time startup diagnostic when `/etc/resolv.conf` is absent, and (2) a
docs note that static/Android hosts should pass proxy env at spawn — the
engine already honours `httpproxy`, so this is documentation of existing
behavior, not a feature request. A deeper resolver hook is D-grade and should
not be proposed until someone needs it.

- **Likely crush area:** engine startup/diagnostics + host docs.
- **PR-shaped artifact:** DOC — a new upstream issue carrying the root-cause
  chain verbatim with a reproduction appendix
  (device evidence: `ls /etc/resolv.conf`, `ss -lntu`, `/proc/<pid>/environ`,
  the dated `[::1]:53 refused` bursts). Optional CODE — the single startup
  warning line, offered only if a maintainer says yes first.
- **Marker:** DOC-first; CODE optional-small. **Grade B**
  ([ledger](engine-egress-dns-2026-11-02.md)).

## (a) Rank 2 — Android child-process embedding with an app-private Unix-domain socket

**What was measured (B, 2026-10-18 + follow-ups).** Before 2026-10-18 the
engine listened on `tcp://127.0.0.1:<ephemeral>`. On-device, a *different UID*
(`adb shell`, uid 2000) fetched `GET /v1/version` → HTTP 200, and
`GET /v1/workspaces/{id}/config` **returned the plaintext provider key** —
loopback is shared by every app, and the engine's HTTP API (by upstream's own
design) has no authentication layer. The pivot: the engine is launched with
`-H unix://<app-private path>/crush.sock`; the socket is `srwx------` in a
0700 directory, and Linux `connect()` on an AF_UNIX inode requires MAY_WRITE
on the inode plus traversal through the directory chain, which the kernel
denies to every other UID — sibling apps, `adb shell`, and adbd included. The
client side uses junixsocket (`com.kohlschutter.junixsocket:junixsocket-common`
2.10.1) with a locally-shipped ARM JNI native (documented in
`app/build.gradle.kts` comments; the Maven `junixsocket-native-android`
coordinate was rejected for provenance reasons). Honest limit on record:
root/`CAP_DAC_OVERRIDE` reads the socket — a root-equivalent threat, outside
the sibling-app model. The app's security decision was **against cross-UID TCP
even with a gate**, because an unauthenticated privileged HTTP surface behind
an app-level gate is one bug away from the 2026-10 observation again.

**Why upstream should care.** Crush grants shell execution and stores the
provider key in plaintext in `crush.json` — two facts that are fine on a
single-user desktop and hostile on a multi-app phone. The `unix://` transport
is a kernel-enforced single-UID boundary that costs the engine almost nothing
and requires no auth machinery; making it documented (not incidental) turns an
accident of one Android client into a supported option for every host, and the
"documented scheme + optional token auth for TCP" ask is already acknowledged
as reasonable (upstream issue charmbracelet/crush#4092, closed 2026-10-21 and
converted to discussion #4107 by maintainer `andrinoff` — a genuine maintainer
interaction on our findings, with nothing landed there yet).

- **Likely crush area:** the `-H` listen scheme selection + docs.
- **PR-shaped artifact:** DOC — a compact writeup posted to discussion #4107
  (kernel argument + measured before/after, linking our
  [p1-attack-surface.md §8](p1-attack-surface.md)) plus an offer to send a
  docs patch describing `unix://` for hosts. Optional CODE — token auth for
  the TCP path, only if upstream asks for it.
- **Marker:** DOC-first; CODE optional. **Grade B** for the device experiment;
  the issue/discussion history is Grade A (public).

## (c) Rank 3 — Long-lived SSE + session continuity: the measured reconnect/race seams

**What was measured (B, Oct–Nov 2026).** The engine's SSE stream is
`data:`-only — no `event:`, no `id:`, no heartbeat — so **the stream is not
resumable**; a host must reconnect and resync from `/messages`, and message
updates are republished whole rather than as deltas. The workspace is reaped
after ~30 s **with no attached client** (attachment-based, not a fixed
interval), which is why a per-turn connection pattern fails structurally and
one long-lived connection is load-bearing. On top of that wire reality, the
app measured and fixed a family of host-side seams, each pinned by tests:

- workspace-switch kept the stale SSE socket (external review P1-03; fixed
  and pinned by `CrushConnectionWorkspaceSwitchTest`),
- an unbounded event channel that was not a bound (P1-06; now 512 frames,
  `DROP_OLDEST`),
- Stop not bound to the run it stops (P1-05; CAS-claimed immutable `RunHandle`),
- provider switch tearing the floor out of a running turn (P1-07; defer
  policy), and
- session-switch transcript bleed (2026-11-02): two unguarded writers shared
  one transcript across session boundaries; fixed with a monotonic
  `sessionViewEpoch` guarded writer
  ([session-transcript-bleed-2026-11-02.md](session-transcript-bleed-2026-11-02.md)).

**Why upstream should care.** These are consequences of the wire contract, not
bugs in our code: a mobile host with a flaky radio and a user who switches
chats mid-turn lands on every one of them. Publishing what is *contract* vs
what is *incidental* — session persistent, workspace handle ephemeral,
frame shapes fixed — lets third-party hosts be correct instead of lucky, and
the run-id asymmetry (below in (e)) is exactly the kind of thing only a
long-lived-shaped host notices.

- **Likely crush area:** SSE/event publishing, workspace lifetime, docs of
  the host contract.
- **PR-shaped artifact:** DOC — one consolidated "host contract questions"
  post containing captured frame traces (raw SSE captures already exist from
  the D3 wire-format confirmation and later sessions), split into focused
  issues only if maintainers engage (e.g., "add SSE `id:`/heartbeat for
  resumable streams", "document the attach-based reap").
- **Marker:** DOC-ONLY (CODE only if upstream asks). **Grade B** for every
  measured item; the one open edge (an in-flight bubble in a re-opened
  session finalises invisibly until replay) is known, deterministic,
  recoverable, and **D-grade — not offered upstream as-is**.

## (e) Rank 4 — ASK-mode approval delivery + question batches on a phone

**What was measured (B, 2026-10-25 → 2026-10-29 → 2026-11-01).** Engine half:
upstream v0.98.2 added session-scoped ask delivery plus pending-prompt
republish on `current-session` — the right primitive. Client half (our
finding, D12, closed 2026-10-29): frames without a run id —
`permission_request`, `question_batch_request`, and every `message` — were
delivered only to catch-all channels nobody subscribed to, so ask prompts
never surfaced *and* streaming text stayed hidden until `run_complete` painted
final text. Fixed by fanning anonymous frames to every registered run, pinned
by tests; the `question_batch_request` handler landed with a bounded
10-minute prompt wait; the full ask → approve → execute → finish cycle was
then measured twice on-device (z6/z7 in October; a two-turn cycle again 2026-11-01
on the promoted v0.99.0 build). An instrumented engine proved the pipeline end
to end: publish → workspace fan-in → session-filter → frame on the wire.

**Why upstream should care.** Upstream already made the correct engine-side
call (session-scoped delivery, pending-prompt republish); what does not exist
yet is a *documented host contract* for delivering prompts to a GUI without
run ids, whole-republish semantics, and bounded waits. This app is currently
the only measured Android (GUI-emulating, non-TUI) implementation of it —
complementary evidence, and a chance for upstream to freeze the shapes before
more third-party hosts guess at them.

- **Likely crush area:** permission/question event documentation (host
  protocol surface).
- **PR-shaped artifact:** DOC — writeup plus a concrete offer to draft the
  wire-shape documentation upstream (our raw captures and event-router tests
  are the evidence base). No upstream code change is needed.
- **Marker:** DOC-ONLY. **Grade B**.

## (d) Rank 5 — Provider gateway realities, measured from the wild

**What was measured (B, 2026-10 → 2026-11-02).**

- **OpenCode Go paid lanes:** after a same-evening subscription, every paid
  model probed `PAYMENT_REQUIRED: Insufficient account funds` while the free
  lane (`beam-free`) completed turns through the identical transport —
  **provisioning lagged the purchase by roughly an hour** (21:07 402 → 21:54
  `WORKS`, zero client changes; both the engine and the app-side base shapes
  returned identical 402 bodies, so the client is verifiably not in the
  failure path).
- **Retired-but-listed:** `iceman-contributor-free`, which the plan page
  lists as unlimited free, probes `RETIRED — Model is unavailable`.
- **Hyper (Charm's own service):** a real key verified only to its **$0
  ceiling** — 23 live models answered `402` with `badKey=false`
  ("authentic-but-unfunded"); a funded turn remains unmeasured and stays off
  every claim until it happens.
- **Zen free tier:** OpenCode-client-gated (`403 FreeTierError` even with a
  valid key — the gate checks the client, not the key), so third-party clients
  have no $0 Zen path at all. Views: [opencode-go.md](providers/opencode-go.md),
  [capabilities.md](capabilities.md), `docs/providers/*`.

**Why upstream should care.** hyper is Charm's own service and crush's
provider layer is what surfaces these states to the user. The measurements
give real-world semantics for the error classes a client UI must tell apart:
funding-gated (402 + `badKey=false` → "add credits", not "invalid key"),
retired-but-catalogued (listed ≠ selectable), and plan-provisioning lag
(don't blame the client during the first hour after purchase). Concrete ask:
does upstream have host guidance for distinguishing these, and should the
catalogue mark retired ids? Our own UI policy (probe-before-commit, fall back
to a verified lane) is offered as one measured answer.

- **Likely crush area:** Catwalk provider registry / model probe + error
  surfaces, docs.
- **PR-shaped artifact:** DOC — a discussion post with the dated probe table
  (values only; never keys). No API keys or key-shaped strings anywhere.
- **Marker:** DOC-ONLY. **Grade B** (hyper completion: D until funded).

## (f) Rank 6 — Zero-retention vault / key-at-rest pattern (host side)

**What was measured (B, app side).** Provider keys are AES-256/GCM-encrypted
per provider into **their own ciphertext slot** (`ct.<providerId>`) with the
master key in Android Keystore — one provider's rotation never destroys
another's (`SecureKeyStoreProviderScopeTest`); `KeySession.require()` never
caches plaintext and never sets the unlock bit (a full auth-bypass class,
pinned by `KeySessionLockTest`); the lock gate is an *input* to the chat layer
so a locked app cannot bootstrap the agent (external review P0-01); the
instrumented suite covers the real Keystore cipher, per-provider isolation,
and tamper-clear on device (part of the 8 passing tests, 2026-11-01);
`allowBackup=false` + data-extraction rules. UI-only badges never touch
ciphertext — they read metadata only, which is why a wrong-slot key renders an
honest provider-named error instead of spuriously decrypting. The honest
limit, stated everywhere: the *running engine* still holds a plaintext copy of
the key by crush's own design, so at-rest protection in the app is not
in-flight protection in the engine.

**Why upstream should care.** crush.json stores the provider key in plaintext
(on the engine's own design) — acceptable on a developer desktop, measurably
hostile on a phone where adb-accessible debuggable builds read app-private
files. A documented pattern for hosts — the host holds ciphertext in its
secure store and hands credentials to the engine at spawn — is worth an
upstream discussion. A mechanism that would *remove* the plaintext-at-rest
while the engine isn't running (e.g., accepting provider credentials via
spawn-time env/file descriptor instead of a persistent plaintext config
write) is a real design change we would happily prototype — proposed as a
question, never as a demand, and only after the discussion lands.

- **Likely crush area:** config/credential storage (engine config write path).
- **PR-shaped artifact:** DOC discussion first; CODE exists only as a
  proposal sketch until maintainers show interest.
- **Marker:** DOC-first; **CODE-proposal (proposal only, never a demand)**.
  **Grade B** for the app side; the engine-side mechanism is **C**.

---

# Part 2 — DEMO SCRIPT (90–120 s, physical device)

Ground rules for the recording:

- Device of record: Redmi Note 7 Pro (Android 10 / API 29), the only device in
  the evidence ledger. Record with the cleanest state available: a fresh
  onboarding on an R8/release build if convenient (r8probe install is fine),
  otherwise the current debug install.
- **Only show what the ledger already proves.** This build has **no Projects
  screen, no Termux levels, no local-model runtime, and no Chat→Code handoff**;
  captions must not imply any of them.
- Never show provider settings with keys visible; blur the key row or cut
  before entry. No key-shaped strings on screen, ever.
- Portrait, captions burned in (subtitles), ≤3 s of silence anywhere, total
  90–120 s.

## Shot list

| # | Time | Shot | Action on screen | Caption overlay (exact wording) | Evidence base |
|---|---|---|---|---|---|
| 1 | 0:00–0:06 | Cold start | App opens from launcher | `Unofficial Android client for the Crush coding agent · engine = upstream Crush v0.99.0 as a child process · app-private Unix socket — no TCP, no root` | README architecture; engine pin (2026-11-01 promotion) |
| 2 | 0:06–0:14 | Lock | Credential prompt on cold resume; cancel → still locked | `App lock: cancelling does not unlock · locked app cannot bootstrap the agent · 6 instrumented security tests on device (2026-11-01)` | MainActivityLockFlowTest; P0-01 fix |
| 3 | 0:14–0:28 | Provider | Settings → OpenCode Go → free-lane model chip | `Providers measured end-to-end 2026-11-02 · a failed probe un-commits and falls back to the verified free lane` | opencode-go.md addendum; capabilities row |
| 4 | 0:28–0:44 | Ask (chat) | Send a short question; tokens stream live | `Streaming turn on the device: ask-mode answers arrive live, not after the turn ends (fan-out fix, pinned by tests)` | D12; capabilities verified rows |
| 5 | 0:44–0:54 | ASK approval | Send a request that writes a file; ask card appears; tap Allow once | `ASK mode, allow-once · full ask → approve → execute → finish cycle measured on device · 10-min bounded prompt wait` | D12 + z6/z7 (README) + 2026-11-01 repeat |
| 6 | 0:54–1:10 | Edit + diff | The write completes; file-changes card renders the diff | `Real file edit on Android 10 · diff rendered, 0 unreadable (two-turn verification 2026-11-01)` | z7 + 2026-11-01 cycle on v0.99.0 |
| 7 | 1:10–1:18 | Review (honest gap) | Show the diff card once more, slowly | `Review today is this diff card — no Projects/Changed-Files screen yet · multi-file turn still unmeasured (honest gap, not claimed)` | capabilities "Not implemented" |
| 8 | 1:18–1:30 | Session continuity | Switch to another chat and back | `Session-switch bleed fixed 2026-11-02 (view-epoch guarded writers) · pinned by ChatSessionTranscriptGuardTest` | session-transcript-bleed doc |
| 9 | 1:30–1:43 | Standalone (optional, strongest) | Unplugged on LTE, one turn completes | `Standalone pass 2026-11-02: loopback CONNECT bridge, zero loopback-DNS dependency · pure LTE` | engine-egress-dns doc addendum |
| 10 | 1:43–1:54 | Facts card | Static card, 4 lines | `R8 minified, release-signed — 28.92 MB (2026-11-02) · 466+ JVM tests (467 @Test grep-counted 2026-11-02) · 8 instrumented tests OK on device · app MIT + engine FSL-1.1-MIT notice` | external-review-triage; LICENSE/NOTICE.md |
| 11 | 1:54–2:00 | Close | Repo URL card | `github.com/sadi21863-bit/crush-android-client · unofficial, MIT · not affiliated with Charm` | LICENSE; README voices |

Timing check: shots 1–11 span 2:00 with the optional; cut shot 9 to land ~1:47
if 120 s is the hard ceiling.

## GATED — do not record until proven

- **"Tests run" (agent executes a test suite/command): NOT recorded.** A real
  command run with exit code and log has never been captured from the engine
  on this device (capability matrix MODE-09 pending; no local toolchain;
  Termux Level 1 is unbuilt). Record this shot only after a measured run
  exists; until then the only honest test caption is the static facts card in
  shot 10.
- **Plan handoff: NOT a feature in this build.** The handoff composer is
  roadmap item P6. If narration mentions a plan, the honest wording is:
  *"Plans live in the chat — this build has no copy-to-project handoff; that's
  an explicit roadmap item."* Do not wire a transition implying handoff.
- **Never claim in captions:** "projects", "Termux", "local model", terms like
  "official" (the app is *unofficial* by design), "free credits", or any
  guarantee about providers. The Termux RUN_COMMAND wiki and the llama.cpp
  Android doc may be linked *in the repo* as future-work references only —
  never in the video.

Production notes: `adb screenrecord` (or the device's native recorder), burn
subtitles, then export; keep the raw screen recording in the local evidence
folder along with the turn timestamps used in captions. Post with a link to
the exact published release once one exists — write "see release notes" only
after a release note exists. The demo video is itself B-grade evidence until a
second human can reproduce a turn from the repo's own build instructions; say
so in the post if asked.

---

# Part 3 — REPO PRESENTATION PACK (copy-paste-ready)

## 3.1 GitHub About description (measured 159 characters)

```
Unofficial Android host for the Crush coding agent — engine child process, app-private Unix socket, streaming, approvals, diffs, app lock. Not a Charm project.
```

(159 chars including spaces, em dash counted as 1 char.)

## 3.2 Topics (13 — inside the 8–15 window)

```
android  kotlin  jetpack-compose  coding-agent  ai-agent  llm  go
unix-domain-socket  server-sent-events  mobile-security  app-lock
charmbracelet  crush
```

Add later, never now: `termux`, `local-llm`, `llama-cpp` — those surface only
after the corresponding phases are *measured*, and topic truthfulness is the
same rule as captions.

## 3.3 README hero paragraph (3–4 sentences, matches the existing voice)

> A native Android chat client that runs a **hidden local
> [Crush](https://github.com/charmbracelet/crush) agent** on-device — a static
> arm64 Go binary shipped inside the APK, spawned as a child process and
> reached over an app-private Unix-domain socket (never TCP). Streaming chat,
> session continuity, ASK-mode approvals, rendered file diffs and per-provider
> encrypted key slots are measured on physical arm64 hardware (Android 10 / API
> 29+), with 466+ JVM tests and 8 instrumented tests pinning the seams that
> green builds kept hiding. It is an independent, **unofficial** client: the
> engine's HTTP API ships no authentication, so isolation is kernel-enforced by
> the socket, and the repo carries its own MIT licence plus a FSL-1.1-MIT
> notice for the embedded engine. What is *not* built is stated just as
> plainly: no Projects screen, no Termux levels, no on-device model runtime.

Place directly under the `# Crush Android` heading, keeping the existing
"Status" section untouched below it.

## 3.4 Upstream engagement post (discussion — preferred shape; issue if discussions feel heavy)

**Title (draft):**

> Feedback wanted, not adoption: running Crush as an Android app child process — transport, DNS, SSE and event shapes from a third-party host

**Body (draft):**

---

Hi Charm team 👋

I maintain an **unofficial, independent Android client** that runs Crush as a
child process: the upstream `Android_arm64` engine binary ships inside an APK,
gets `exec()`'d, and a Compose UI talks to it over an **app-private
Unix-domain socket**. Current engine pin: **v0.99.0** (SHA-256-verified fetch
from your signed checksums). The app is MIT-licensed and carries a FSL-1.1-MIT
notice for the redistributed engine; it is clearly marked as not a Charm
project, and if the name "Crush Mobile" is a problem, tell us and we'll rename.

This is **not** a request to adopt or endorse the client. It is a request for
**API/compatibility feedback** on six things we measured against the engine on
real hardware (one device: Redmi Note 7 Pro, Android 10 / API 29), most of
them with raw captures. If any of them is already contract or planned, a
one-line "that's on purpose / that's not" from you is worth more to us than a
patch.

1. **`unix://` listen scheme.** Since 2026-10 we launch with
   `-H unix://…/crush.sock`; kernel permission checks on the AF_UNIX inode
   (MAY_WRITE + directory traversal, 0700 chain) are what closes the
   cross-app reach that loopback TCP offered (measured: `GET /v1/version` →
   HTTP 200 for uid 2000, and the config endpoint returned the provider key,
   before the pivot). Is `unix://` a documented, stable scheme we can build
   on, or incidental? This is a refresh of charmbracelet/crush#4092
   (https://github.com/charmbracelet/crush/issues/4092), which you moved to
   discussion #4107 on 2026-10-21 — happy to send a docs patch if that helps.

2. **DNS on Android (static Go + no resolv.conf).** Android has no
   `/etc/resolv.conf`, so the pure Go resolver in a static binary falls back
   to its compiled-in `127.0.0.1:53`/`[::1]:53` defaults, and nothing serves
   DNS there — every engine-originated provider call dies with
   `lookup … [::1]:53 … connection refused` while whatever host does its own
   JVM-side networking looks perfectly healthy. **This is a general property
   of spawning a static Go binary on Android/a minimal container**, not
   something specific to us. Our app-side fix: we spawn the engine with a
   loopback HTTP-CONNECT bridge and `HTTPS_PROXY`/`NO_PROXY` env — Go's
   `httpproxy` already honours it, so the engine dials zero names, and
   `CONNECT … ok` confirmed through the bridge on-device. Question: would
   upstream take (a) a one-time startup diagnostic when `resolv.conf` is
   absent, and/or (b) a docs note that Android/scratch-container hosts should
   pass proxy env? Full measurement chain + repro in our ledger
   (docs/engine-egress-dns-2026-11-02.md in the repo linked below).

3. **SSE resumability + workspace reap.** Frames are `data:`-only today (no
   `event:`/`id:`/heartbeat), so a host must reconnect and resync from
   `/messages`; the workspace is reaped ~30 s with no attached client
   (attachment-based). Both are workable when the client stays attached like
   a terminal session; both are load-bearing constraints for a mobile host
   with a flaky radio. Question: is
   "reconnect + full resync" the *intended* host pattern (then we'll spec our
   recovery around it), and is an optional `id:`/heartbeat something you'd
   consider?

4. **Event fan-out for run-id-less frames.** `permission_request`,
   `question_batch_request`, and `message` frames carry no run id (only
   `run_complete` echoes the client-minted run id); v0.98.2's session-scoped
   ask delivery + pending-prompt republish on `current-session` is the right
   primitive. Our host fans anonymous frames to every registered in-flight
   run — measured end-to-end (ask card → approve → tool run → finish, twice).
   Question: is "session-scoped delivery is the contract, the client fans out"
   the intended reading we should document our UI against?

5. **Provider error classes from the wild.** Measured against hyper (real
   key, unfunded): `402` responses with `badKey=false` — authentic-but-unfunded,
   which a client must present as "add credits", not "invalid key";
   against a listed-but-retired free model: `RETIRED/Model is unavailable`;
   and against a newly purchased paid plan: ~1 h of identical `402`s before
   the lane lit up with **zero client changes** — provisioning lag. Does
   upstream have (or want) host guidance to distinguish funding-gated /
   invalid-key / retired-model / provisioning states? happy to contribute our
   measured table.

6. **Key-at-rest on hosts.** crush.json's plaintext provider key is the
   engine's design and fine on a desktop; on a phone the host can hold keys
   AES-256/GCM-encrypted per provider in its own secure store and hand the
   engine only what it needs at spawn. One thing we would value your view on:
   an optional engine-side mechanism where a host streams credentials at
   spawn (env or file descriptor) instead of the engine reading and writing a
   plaintext-at-rest config copy. Would you be open to discussing that shape?
   (No code attached; happy to prototype against a sketch you bless.)

Everything above is measured, dated, and logged per finding (device ledger in
the repo linked below); our test posture is 466+ JVM unit tests + 8
instrumented tests passing on the device, and every capability page says what
is *not* built (no projects screen, no Termux, no local inference — those are
explicit roadmap items, not imminent).

If it's easier, we'll file focused issues for any subset of the above — tell
us which numbers interest you and we'll split them 1:1 with repro/instructions.

Repo: https://github.com/sadi21863-bit/crush-android-client
Upstream release we pinned: https://github.com/charmbracelet/crush/releases/tag/v0.99.0

Thanks for crush — the child-process + HTTP/SSE shape is what makes an Android
host viable at all.

---

**Post logistics:** post as a **discussion** first (the #4092 precedent above
was converted to a discussion by a maintainer — that is the venue upstream
prefers for this shape); keep every link real. Only four references are
allowed in any artifact produced from this plan, and all four are real:
(1) charmbracelet/crush pages (issues, discussions, releases), (2) this
repository, (3) the Termux RUN_COMMAND wiki
(https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent — future-work
mentions only), and (4) the llama.cpp Android doc
(https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md —
future-work mentions only). Do not fabricate tags, URLs, or maintainer quotes.

---

## Appendix — do-not-ship checklist for every artifact of this plan

- [ ] No API keys or key-shaped strings anywhere (posts, captions, screenshots).
- [ ] Every number captioned has a dated ledger line behind it.
- [ ] Unmeasured behavior marked D-grade or absent, never implied.
- [ ] "Unofficial / not affiliated with Charm" present in title card, About
      line, and post body.
- [ ] Termux / local-model / projects-screen language absent from video and
      About/topics (they live only in roadmap sections clearly marked
      unmeasured).
- [ ] Links limited to charmbracelet/crush pages, the Termux RUN_COMMAND
      wiki, the llama.cpp Android doc, and this repository.
