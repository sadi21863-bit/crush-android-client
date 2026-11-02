# Engine egress defect — DNS resolution on-device (fixed 2026-11-02)

## What the user saw

> "When the phone is connected to the computer, the app works perfectly — it
> answers questions perfectly. But when I disconnect the phone from the
> computer and use the app independently, it does not answer, and all the
> replies fail."

Banners read:

```
Provider Error: retry error: Post "https://opencode.ai/zen/go/v1/chat/completions":
dial tcp: lookup opencode.ai on [::1]:53: read udp [::1]:49136->[::1]:53: read: connection refused
```

## Root cause chain (all measured on device `bbf8ca69`, 2026-11-02)

1. The agent engine (`libcrush.so`) is a **statically linked Go binary** running
   as a child process of the app.
2. Android has **no `/etc/resolv.conf`** (`ls /etc/resolv.conf` → not found;
   `/system/etc/resolv.conf` → not found), and `/system/etc/hosts` is
   system-writable only — nothing an app can do about it.
3. Go's pure resolver (= no cgo in a static binary), finding no `resolv.conf`,
   falls back to its compiled-in default nameservers **`127.0.0.1:53` and
   `[::1]:53`**.
4. **Nothing on the phone serves DNS on loopback** (`ss -lntu`: no `:53`
   listener). Every engine-originated provider call therefore fails at the
   first packet it ever sends: `read: connection refused`.
5. The app-side JVM (OkHttp) calls — catalogue fetch, model probes, prices —
   use **bionic's resolver through netd**, which works on any network. That is
   why bootstrap/probing looks healthy while real turns die: the split exactly
   matches the symptom (probes fine in every log; turns failed at
   `chat/completions`).

The engine's environment has always confirmed this: `EnginePaths.env()`
passed CRUSH_* dirs, HOME, TMPDIR, PATH=... and **nothing resolver-related**;
`/proc/<pid>/environ` verified the same.

## Why it ever worked at all — the borrowed-listener window

Track record from the engine's own `crush.log`:

- **2026-10-21 04:24Z**: identical `[::1]:53 refused` on `openrouter.ai` — the
  defect predates today.
- **2026-11-02 02:45Z** (08:15 local): `ModelProvider called … beam-free` with
  **no error after it** — the turn that DID succeed.
- **2026-11-02 05:39Z → 06:07Z + 12:03Z** (11:09–11:37 and 17:33 local — the
  user's standalone window): three identical bursts of
  `Agent stream returned error … dial tcp: lookup … [::1]:53 … refused`.

The successful windows lined up with the phone being **tethered**
(`Tether state: rndis0 - TetheredState / wlan0 - TetheredState` in
`dumpsys connectivity`) — the DHCP/DNS forwarder that tethering raises can
serve loopback port 53 as a side effect. Unplug / stop tethering → no
listener → every reply dies. The engine never had a resolver of its own:
egress survived on borrowed listeners — never guaranteed, and never the
app's own mechanism.

(Precedent on record: the standalone-day audit, 2026-10-25, documented the
same error string inside `ChatViewModel`'s replay-row comment as "a DNS
outage" — treated then as a *rendering* defect. The engine's resolver was the
never-fixed root.)

## The fix (app-side; the engine binary is upstream/bundled)

`EngineProxy` + wiring in `CrushEngine`:

- The app runs a tiny **HTTP CONNECT bridge** bound to `127.0.0.1` on a free
  unprivileged port. The engine is spawned with
  `HTTPS_PROXY/HTTP_PROXY=http://127.0.0.1:<port>` (+ `NO_PROXY=127.0.0.1,
  localhost,::1`).
- Go's `httpproxy` honours those env vars, so the engine now dials **only
  `127.0.0.1` — zero DNS on the engine side, by construction**.
- The bridge resolves the CONNECT host with the **JVM's resolver**
  (bionic/netd — network-agnostic: cellular, Wi-Fi, whatever exists) and then
  relays raw bytes. TLS stays end-to-end engine↔provider; the bridge cannot
  see plaintext, only `CONNECT host:port`.
- The listener is strictly loopback; it relays only; it starts with the
  engine and stops with it (`stopBlocking`).

## Secondary defects fixed in the same batch

1. **`HTTP 200` with an empty `data[]` from the models endpoint** silently
   produced a *valid error-free empty catalogue* → the bootstrap logged
   `no usable model: null` and **stranded the chat dead** (engineReady=false,
   no retry). Measured twice on 2026-11-02 (02:15 + 12:44 local) where the
   same fetch succeeded seconds earlier/later. Now labelled by
   `ZenModelsApi.fetch` as a server flake, with **one bounded retry** in the
   bootstrap (1.2 s) before declaring the dead state.

## Validation plan (truth-ledger style)

- Unit: `EngineProxyConnectTest` (handshake + relay + 502 + resolver seam),
  `ChatSessionTranscriptGuardTest` (the session-bleed companion,
  [session-transcript-bleed-2026-11-02.md](session-transcript-bleed-2026-11-02.md)).
- Device: after install, a real turn must complete **while the engine log
  shows `CONNECT … ok` through the bridge** rather than any `<host>:53`
  attempt; full standalone proof is the user's unplugged test — the fix
  removes the loopback-DNS dependency entirely, so any working network path
  suffices.

## 2026-11-02 19:05 + 20:07 addendum: BOTH device plans passed

- **Main app (debug build, committed `2c1c629`)**: `bridge up on
  127.0.0.1:38849` at 19:01:17, then `CONNECT opencode.ai:443 ok` at
  19:05:28 completing a full streaming turn ("Reply with exactly: OK" →
  `OK`). The engine's OWN startup calls, which used to die:
  `CONNECT hyper.charm.land:443 ok`, `CONNECT catwalk.charm.land:443 ok`,
  `CONNECT api.github.com:443 ok` — all through the bridge.
- **User's unplugged test (19:0x)**: the user confirmed independent use
  works — the definitive standalone pass, on pure Airtel LTE with WiFi off
  and USB disconnected.
- **R8 runtime proof (20:07)**: the `r8probe` build (minified, R8,
  release-signed, non-debuggable — `run-as` refused, as intended) ran its
  OWN fresh bootstrap + a completed streaming turn through ITS bridge
  instance, closing the review item's mandated check. Recorded in the
  external-review triage.
- **Found live during the r8probe drive — the THIRD Zen-hardcode**: a Go key
  under the Zen-labelled default rendered "OpenCode Zen rejected this API
  key". `ModelChoice.explainFetchFailure` now takes the ACTIVE provider's
  display name (same defect class as the model sheet + no-model verdict;
  `ModelChoiceTest` pins the provider-naming behavior). The pitfall itself —
  a key stored under the wrong provider slot at onboarding — is honest app
  behavior (badges + row copy now name the provider); no code change was
  made for the mechanics.
