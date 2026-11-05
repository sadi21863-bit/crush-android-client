# Upstream-issue candidate drafts — 2026-11-05 (analysis only, NOTHING filed)

Prepared from docs/upstream-outreach-plan.md's three candidates, verified
against the device ledger (engine-egress-dns-2026-11-02.md,
capabilities.md) and live upstream checks: release `v0.99.0` is real
(published 2026-10-29), issue #4092 closed 2026-10-21, discussion #4107
has a 2026-11-04 maintainer reply ("finishing client/server mode…
preparing full documentation"). Drafts below are NOT posted anywhere.

Standing hygiene: no keys/key-shaped strings/header values; no device
branding (only "a resourced Android ARM64 device (Android 10)"); our app's
name never appears. Cross-candidate caution: the F06 leak, the
Starting→Running wedge, ~55–60 s respawn and A11/A12 are OUR supervisor/
runtime artifacts (S5 instrumentation) — never to be attributed to engine
behavior upstream.

---

## Candidate 1 — DNS on DNS-less hosts (plan rank 1(b)) — new issue

**Title (draft)**

> crush v0.99.0 on hosts without `/etc/resolv.conf` (Android-as-child-
> process, scratch containers): the pure Go resolver dials compiled-in
> `127.0.0.1:53`/`[::1]:53` and every engine-originated provider call
> fails — ask: one startup diagnostic + docs note on proxy env

**Body (draft)**

A statically linked crush server running on a host with no
`/etc/resolv.conf` cannot resolve anything: Go's pure resolver falls back
to its compiled-in default nameservers (`127.0.0.1:53`, `[::1]:53`), and
on such hosts nothing serves DNS on loopback. The symptom is a liar —
components that resolve through the host OS resolver look healthy while
every engine-originated provider call dies at the first DNS packet with
`lookup <host> on [::1]:53: connection refused`. Measured end-to-end on a
resourced Android ARM64 device (Android 10) where the upstream v0.99.0
server runs as a child process; worked around since the engine honors
httpproxy env by spawning with `HTTPS_PROXY`/`HTTP_PROXY` via a loopback
bridge — zero engine-side DNS by construction.

Minimal repro (maintainer-runnable; listen scheme orthogonal):
1. Any static v0.99.0 build on a DNS-less host (scratch container equally
   demonstrable; official `Android_arm64` release binary as example).
2. Confirm no `/etc/resolv.conf` anywhere and nothing listening on `:53`
   (including loopback).
3. Trigger any provider call (bootstrap itself fetches the model
   catalogue). Expected: `lookup <host> on [::1]:53 … connection refused`.
4. Contrast: non-engine processes on the same host resolve fine — the
   failure follows the engine process, not the network.

Ask: (a) one-time startup diagnostic when `/etc/resolv.conf` is absent
+ (b) a docs note that proxy env sidesteps DNS entirely (engine already
honors). Stdlib context is golang/go#10714 (cite, don't fix Go).

Evidence timeline (one device, engine log): identical `[::1]:53 refused`
burst 2026-10-21T04:24Z; further bursts 2026-11-02 05:39Z→06:07Z and
12:03Z; a tethering-window success 02:45Z (borrowed listener, not engine
capability); fix validation on v0.99.0: bridge up 19:01 local, full
streaming turn via `CONNECT …:443 ok` at 19:05, repeated on a minified R8
build 20:07. Engine's `/proc/<pid>/environ` carries no resolver-related
env.

**Status:** Grade B (self-measured); fully maintainer-reproducible;
fileable as assembly (ledger already holds root-cause). Duplicates: none
found (closest: #3388 static link errors at install; Termux trio
#793/#1756/#1802 — different symptoms).
**Owe:** the four-item repro appendix verbatim from the ledger; honest
cross-version framing (bursts on v0.98.x era, fix validated on v0.99.0);
optional one-line startup-warning CODE held until a maintainer answers.

---

## Candidate 2 — unix:// transport + app-private socket (rank 2(a)) — refresh #4092 → discussion #4107 (NOT a new issue)

**Title (draft comment in #4107, follow-up)**

> Status update after ~3 weeks on `unix://` as the only supported
> transport — measured before/after (TCP-listen vs unix socket), kernel
> argument, and an offer to test the incoming client/server docs

**Body (draft, condensed)**

Our embed runs upstream v0.99.0 around the clock over
`-H unix://<app-private>/crush.sock`; we can validate any docs the moment
they land. Before/after measured on a resourced Android ARM64 device:
- BEFORE (TCP listen, 2026-10-18): another process UID on the same device
  (adb shell, uid 2000) reached `GET /v1/version` (200) and
  `GET /v1/workspaces/{id}/config` returned the provider key in
  plaintext; on multi-app OSes the auth-less HTTP API was effectively
  public to sibling processes.
- AFTER (unix socket, 0700 parent, engine-UID-owned `srwx------`): the
  same uid-2000 probe gets Permission denied/unreachable (AF_UNIX connect
  needs MAY_WRITE on the inode + directory-chain traversal; root/
  CAP_DAC_OVERRIDE remains outside the sibling-app threat model).
Because the engine grants shell execution and stores the provider key in
plaintext config, the listener is the one place a multi-app host can
enforce a KERNEL boundary with zero auth machinery. Asks, unchanged:
(1) recognize/test `unix://` as a supported listen scheme (one fixture
locks it in); (2) the optional TCP token-auth from the original thread;
(3) a short "transport for sandboxed embedders" docs paragraph.

**Status:** thread history Grade A (public), device experiment Grade B.
Duplicate-threat is our own thread (venue = #4107) plus overlap with the
in-flight client/server docs — frame as offer-to-validate + fill-the-gap,
retract ours if their docs cover it. Adjacent, not dups: #4210 (server
bash-tool env), #2280 (android arm, closed).
**Owe:** one v0.99.0 re-drive of the before-state (TCP flag + uid-2000
probes, keys redacted) so before/after sits on the pinned version; keep
our runtime-management numbers OUT of the post; link only per the plan's
allowlist.

---

## Candidate 3 — events-stream host contract (rank 3(c), with (e) split on engagement) — consolidated discussion post

**Title (draft)**

> What in the events stream is contract and what is incidental? measured
> on v0.99.0: data-only SSE (no event:/id:/heartbeat → no resume),
> attach-based workspace reap (~30 s no-attached-client), whole-frame
> republish — proposing the shapes be frozen in the host docs

**Body (draft, condensed)**

For a third-party GUI host, the events stream's current shape is workable
but undocumented. Measured raw repeatedly since 2026-10: (1) frames are
`data:`-only — no `event:`, no `id:`, no heartbeat — so the stream is NOT
resumable; a host must reconnect and resync from `/messages`, and message
updates republish whole rather than as deltas. (2) The workspace is reaped
~30 s with no attached client — attachment-based, not interval — which is
why a per-turn connection pattern fails structurally and one long-lived
connection is load-bearing. (3) Frame shapes are workable but unlisted;
v0.98.2's session-scoped ask delivery + pending-prompt republish is the
right engine-side primitive (see #4222) — is that the intended contract,
or is per-frame run-id on the table?

Minimal repro (v0.99.0, unix socket, curl only): `/v1/version` ok → one
streaming turn → `curl -N .../events` shows only `data:` lines
incl. across a mid-turn reconnect where the host replays from
`/messages`; with nothing attached, a stop working after ~30 s until
re-attach.

Ask: publish contract-vs-incidental (session persistent, workspace handle
ephemeral, frame shapes fixed); optional `id:`/heartbeat for resumability
on flaky-radio hosts; question-first — we'll spec to whatever upstream
declares intended.

**Status:** best-supported theme (engine-facts table Grade B, pinned
against v0.97.1→v0.98.1 with v0.99.0 carried by API-surface binary diff +
continuous daily UDS operation since 2026-11-01); maintainer-reproducible
with curl. Duplicates/adjacents: #3579 (full parts-blob rewrite ~33 ms —
same family; cite and tie our ask to it), #3580 (compression — supports
"republish is expensive"), #3278 (subscriber/publisher race), #990
(IDE-integration demand signal). No issue found for the ~30 s attach reap
or SSE id/heartbeat.
**Owe:** re-pin the capture set on v0.99.0 (frames, reap window
timestamps, 60 s no-heartbeat); export/redact existing D3-era captures to
the 4–5-frame minimal form; keep (e) run-id material as the split-out
follow-up, with question/permission delivery wording framed as
compat history.
