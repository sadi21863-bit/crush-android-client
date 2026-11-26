# Security incident — provider key exposed in tool output

ID: **SEC-2026-11-26-01**. Date: 2026-11-26. Phase: V2-16 Phase 0 (P0-E1).
Status: **rotation performed by the owner 2026-11-26 ~10:02; old-key
invalidation owner-attested but not independently verified by the agent.**
The incident record stays open until the owner confirms invalidation in the
provider console.

## What happened

During P0-E1 workspace discovery, `GET /v1/workspaces` (over the app's private
Unix socket, as the app UID) returned the full workspace object including
`config.providers.<id>.api_key` in plaintext. The response was printed into the
agent's tool output, so the provider key entered the session transcript.

The value is **not** reproduced here or in any report.

## Impact

- Credential exposed to the conversation/transcript, not to another app on the
  device: the socket is app-private (`srwx------`, app UID) and the request was
  made via `run-as`.
- The key remains in the engine's own app-private configuration by Crush's
  design (plaintext config, backup-excluded). That file is not the incident.
- No evidence file, report, screenshot, app log or crash archive contains the
  value (scan below).

## Containment and scan (2026-11-26, counts only)

| Location | `oc_sk_` hits |
|---|---|
| `docs/v2` reports | 0 |
| `docs/parity/v2-16-phase0` evidence | 0 |
| device `files/app.log` | 0 |
| device `files/crash-archive.txt` | 0 |
| device `files/phase0-probe.txt` | 0 |

No redaction was needed in the evidence set.

## Required actions

1. **Rotate the key in the OpenCode Zen console** (owner; the agent has no
   console access).
2. Enter the replacement key in the app (Settings → provider key), which stores
   it through `SecureKeyStore`.
3. Confirm the old key is no longer valid (owner checks the console / a provider
   probe; the agent will not use the old value).
4. Re-run the scan above after rotation.
5. Keep this record with the B-G5 security evidence.

### Rotation status (updated 2026-11-26 during Checkpoint 2)

- The owner changed the app's API key mid-activity at ~10:02; the app
  restarted. `shared_prefs/secure_api_keys.xml` was rewritten at 10:02
  (metadata only; the value was never read or printed by the agent).
- The replacement key works: after the restart the live model probe succeeded
  (`probed 1 candidate(s), using deepseek-v4.1-flash (of 27 listed)` at
  10:05:06 and again at 10:32:29). The Settings screen shows a key stored
  with a masked suffix; no value was printed.
- Old-key invalidation is **owner-attested, not agent-verified**: the agent has
  no provider console and did not use the old value again.
- The exposure scan in this document was performed before rotation; no new
  credential was written into any report or evidence file. Checkpoint 2
  evidence commands selected ids/status only and never dumped workspace or
  config objects.

## Proposed register amendment (not applied)

The approved risk register (Revision 3) is frozen and is **not** edited in
place. Proposed row for the next revision:

| ID | Class | Sev | Finding | Track | Proposed status |
|---|---|---|---|---|---|
| R35 | Risk | High | The engine's `/v1/workspaces` list returns provider API keys in plaintext; any local client with socket access, or any diagnostic that dumps a workspace object, exposes the credential. The app must never log/export workspace config, and diagnostics must select fields explicitly. | B (B-G5) + A (diagnostics) | open |

Related: B-G5 (security verification), and the Phase 0 evidence standard
("select fields, never dump objects").

## Lesson for the Phase 0 method

- Never dump a workspace/config object whole, even over the private socket.
- Use `grep -o`/field selection for ids.
- Treat any engine response that contains `config` as credential-bearing.
