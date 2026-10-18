# P1 — EngineGate Design Note: Per-Launch Bearer Token on Loopback TCP

**Status: SUPERSEDED as the primary mitigation (2026-10-18). The codebase pivoted to
Unix-domain-socket transport (see `EngineSockets.kt`, `p1-attack-surface.md` §4); the
engine now binds `unix://<app-private path>` and the gate module was never built —
the "module landed" note in earlier revisions was a subagent's false claim.**
*A per-gate-port bearer token remains the documented FALLBACK if UDS proves untenable
on-device. Nothing below is claimed as working on device.*

> **Non-claiming rule.** Until a fallback build reports pass, this document describes what
> EngineGate is *designed* to do — nothing is claimed as working on device. The accurate
> security statement today is: the engine is reachable ONLY through the app-private socket
> file; loopback TCP no longer exists. See
> [p1-attack-surface.md](p1-attack-surface.md) §4 for the pivot, and §2–§3 for why the
> old TCP spawn was an F01-grade exposure.

## 1. Threat model recap (5 lines)

1. The attacker is a **sibling app on the same device** — different UID, no root, no adb; it may hold `INTERNET` (a normal install-time permission).
2. **Loopback is not UID-partitioned on stock Android** — any UID can open a TCP connection to `127.0.0.1:<port>` and the kernel accepts it.
3. **Engine v0.98.1 ships no authentication** (upstream finding F01) — all 76 operations are invocable with zero credentials once the port is known, including the plaintext `api_key` read and `POST .../shell`.
4. **Port enumeration is feasible** — the port is ephemeral, but a loopback connect scan completes in seconds and `GET /v1/version` disambiguates the engine from other local listeners.
5. The full per-endpoint severity map (68 paths / 76 operations, READ-SENSITIVE → TOOL-EXEC) is in [p1-attack-surface.md](p1-attack-surface.md) §2–§3 — not repeated here.

## 2. Architecture

```
   all app HTTP clients (Compose UI, SSE collector, probes, tooling)
                    │        Authorization: Bearer <token>  ← required
                    ▼
        EngineGate  127.0.0.1:<gate-port, ephemeral>
                    │        blind byte relay (no HTTP re-encoding)
                    ▼
        libcrush.so 127.0.0.1:<engine-port, ephemeral>  — NO auth (v0.98.1)
```

- The gate owns a **second ephemeral loopback port** and fronts the engine. App-side
  clients are pointed at the gate — never at the engine port directly. A request without
  the correct Bearer token is rejected at the gate without any engine socket being opened.
- **Byte-forwarding property.** Once the token check passes, the gate relays raw bytes in
  both directions and performs **no re-encoding, re-framing, or parsing**: it never
  inspects `Content-Length`, never rewrites chunked bodies, and never joins or splits SSE
  `data:` frames. Because nothing between request and response is interpreted, SSE and
  `Transfer-Encoding: chunked` responses survive byte-identically — including frames
  split across TCP segment boundaries and interleaved stream events. This is what lets
  the gate sit in front of the long-lived workspace SSE connection without touching the
  "republishes whole messages, replace-never-append" protocol the app already relies on.

## 3. Token lifecycle

| Property | Design |
|---|---|
| Generation | `SecureRandom`, 32 bytes, base64url (43 chars), at **engine spawn**, in-process. |
| Storage | **Memory only.** Never written to disk, prefs, or logs; masked in any diagnostic output per the repo rule (`[A-Za-z0-9_\-]{25,}` → `[REDACTED]`). |
| Rotation | **Per engine restart.** Every spawned engine process gets a fresh token; the previous process's gate is torn down with it, so a stale token is rejected, never honoured. |
| Address carried by app state | The **gate port replaces the engine port as the ONLY address app state carries.** The engine's raw port stays inside the gate/spawner module; `CrushApi`, SSE clients, and probes never read it. |
| Comparison | Request Bearer token checked with a constant-time compare before any forwarding. |

## 4. What EngineGate does NOT protect (explicit)

1. **The engine's own listener stays open on its raw port.** The gate is client-side:
   a sibling app can still connect **directly** to the engine port and skip the token
   entirely. Until the transport itself is closed — private UDS (P1.2) or
   upstream-native auth (P1.6) — this residual risk stands. A per-launch token raises
   the bar from "one loopback scan" to "must also win the race for the gate's port and
   token", but it is **hardening, not a boundary** — the same honest caveat as
   [p1-attack-surface.md](p1-attack-surface.md) §7(ii).
2. **Admin-surface access via other local channels.** Reach through device-local
   privileged channels — adb shell / UID 2000, debuggable builds + `run-as` reading
   `crush.json` (plaintext by Crush's own design), backup extraction — is unchanged by
   this module. Debug builds therefore remain unshareable regardless of the gate.
3. **Device-local ADB / debuggability threats are out of scope.** The P1 threat model is
   a non-privileged sibling UID; an adb-level compromise is a different trust tier and is
   not addressed here.

## 5. Test matrix (module-level, all JVM)

Written against the gate module. The Status column stays **pending until the pipeline
actually runs** — "passed" is recorded only from real run output, never by assertion.

| ID | Assertion | Status |
|---|---|---|
| TG1 | **Auth reject** — missing Bearer, empty Bearer, wrong token, malformed header each → rejected; **no engine connection is established and zero bytes are forwarded**. | pending |
| TG2 | **Pass-through byte-exact** — request and response bytes round-trip unchanged (checksum equality); headers preserved verbatim. | pending |
| TG3 | **SSE split frames** — the relay must not buffer awaiting frame boundaries: chunks split at arbitrary byte offsets, interleaved events, unterminated stream → client receives a sequence identical to the engine's output and the stream stays open. | pending |
| TG4 | **Rotation** — engine restart → new token: old token rejected, new token accepted, gate port replaced, and app-state consumers re-resolve the new address. | pending |

## 6. Forward plan (the audit's remaining P1 items)

- **P1.2 — UDS experiment (pending).** Timeboxed check of whether the engine can listen
  on a **Unix domain socket** placed in the app-private directory (UID-sandbox-protected
  by file permissions — closes the transport surface entirely, where a token cannot).
  Upstream must be consulted; nothing is assumed about v0.98.1 supporting a UDS listen
  address or behaving identically on it.
- **P1.6 — upstream-native auth proposal (pending).** A shared-secret/token check
  implemented inside the engine itself is the only direction that closes **all rows** of
  the attack-surface map at the listener (an app-side proxy structurally cannot). The
  proposal is to be filed upstream in the Craft/charmbracelet repo —
  link target: <https://github.com/charmbracelet/crush> — and tracked there as the
  durable fix.

---

**Wiring: pending enumeration (CrushApi/SSE/3 probes). On-device attacker-UID test: pending.**
