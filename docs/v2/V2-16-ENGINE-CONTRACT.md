# V2-16 Phase 0 — P0-E1 engine contract

Date: 2026-11-26. Status: **evidence report, Checkpoint 1.** Produced under the
authorized V2-16 Phase 0. No production code, no commit. Baseline branch
`v2-16-planning-baseline` at `9192bc5`; device experiments were run against the
installed debug build.

## 1. Environment

| Item | Value |
|---|---|
| Device | Redmi Note 7 Pro, **API 29**, arm64-v8a, serial `bbf8ca69` |
| App | debug build `com.crush.mobile` 1.0.0, installed from the current tree |
| Engine | `v0.100.0`, commit unknown, build `2my2izixtou8`, go1.27.1, android/arm64 |
| Instance used | project engine `pd871eee8` for `Test project folder 2` |
| Workspace | `a4efa64d-4b0d-484f-9dc6-26bf470f437b` (path `/storage/emulated/0/Test project folder 2`) |
| Session | `0e405d83-3033-4600-9222-88cd64e4320e` |
| Transport | Unix socket `files/crush-pd871eee8.sock`, reached as the app UID with `run-as` + `curl --unix-socket` |
| Model | `deepseek-v4.1-flash` via `opencode-go` |

Evidence files (repo): `docs/parity/v2-16-phase0/`
— `v2-16-p0-sse.log`, `v2-16-p0-sse2.log`, `p0-messages.json`, `p0-body.json`,
`p0-answer.json`, and the four UI captures.

## 2. Method

1. Park a real `yes_no` question through the app (Crush Code → project chat →
   prompt instructing the `question` tool).
2. Read the raw `question_batch_request` payload from `app.log`.
3. Open a **new** SSE client with a fresh `client_id` and POST
   `/current-session` for the same session (the protocol act a restarted app
   performs after reattach).
4. Record whether the pending question is republished and whether the ids match.
5. POST `/questions/cancel` twice and `/questions/answer` twice with the exact
   same body, recording status and response.
6. Read `/sessions/{id}/messages` and search for the submitted answer.
7. Attempt process-death variants (`am kill`, `am crash`, direct kill) and
   record which processes survive.

Raw HTTP was executed as the app UID, never over TCP:

```
adb shell run-as com.crush.mobile curl -sN --unix-socket \
  /data/user/0/com.crush.mobile/files/crush-pd871eee8.sock \
  "http://localhost/v1/workspaces/<ws>/events?client_id=<uuid>"
```

## 3. Results at a glance

| # | Question | Result | Evidence |
|---|---|---|---|
| Q1 | Is a parked prompt republished after a new client reports `current-session`? | **Yes** | `v2-16-p0-sse2.log` |
| Q2 | Does the republished prompt carry the same batch/question ids? | **Yes** | same event: batch `dadf170b…`, question `27369be9…` |
| Q3 | Is `POST /questions/answer` idempotent for the same batch/answer? | **Same-request replay safety demonstrated** — sequential replay after a received response: 200 `{"resolved":true}`, then 200 `{"resolved":false}`. A true lost-ack (response suppressed) was **not** fault-injection tested. | clean `-i` run: `HTTP/1.1 200 OK`, 19-byte body |
| Q4 | Can a submitted answer be uniquely identified from history after a lost ack? | **Yes for the tested `yes_no` prompt only** — by `tool_call_id` + textual content. Not established for multi-choice or free-text. | `p0-messages.json` |
| Q5 | Is `cancel` idempotent, and does it succeed when no run exists? | **Yes at HTTP level** — 200 `{"resolved":true}` then 200 `{"resolved":false}`; no error when nothing is pending | `curl` output, `app.log` 08:13:46 |
| Q6 | Does an active run or open SSE suppress the engine idle timeout? | **Inconclusive / not performed** (see F6) | — |

## 4. Findings

### F1 — A parked question is republished on `current-session` (observed)

After Q1 was parked, a new SSE client (`client_id` `00000000-0000-4000-8000-0000000000aa`)
received, in order:

1. `client_session` (updated) for that client and the session;
2. `question_batch_request` (created) with the **same** `id`
   `dadf170b-6839-4d78-8f18-e9ad7f60a1f2`, the same `session_id`, the same
   `tool_call_id` and the same question id `27369be9-…`.

Interpretation: the engine's pending-prompt republish path works for a **new
client on a live engine**. This is the protocol act a restarted app performs.
It does **not** establish that the engine survives app-process death (F5).

### F2 — Cancel is HTTP-idempotent and reports whether it resolved (observed)

With a question parked:

- first `POST /questions/cancel` → `{"resolved":true}`, HTTP 200;
- second → `{"resolved":false}`, HTTP 200;
- the app received frames and the run ended (`app.log` `prompt-wait: frame
  received; loop resumes`, then `file changes: +0 -0`);
- the session history gained a tool result
  `{"content":"User cancelled this question","is_error":true}`.

Interpretation: a retry of cancel is safe; the boolean distinguishes "resolved
something" from "nothing to resolve", which is a usable lost-ack signal.

### F3 — Answer replay is HTTP-idempotent (observed)

With a second question parked (`7e606a13…`, question `2c3d3a4c…`):

- first `POST /questions/answer` with the exact body →
  `{"resolved":true}`, HTTP 200;
- second identical POST → `{"resolved":false}`, HTTP 200;
- a third clean `-i` POST → `HTTP/1.1 200 OK`, body length 19
  (`{"resolved":false}`).

Interpretation: replaying the same answer does **not** error and does **not**
double-apply. That is **same-request replay safety**, not lost-ack recovery:
the experiment received the first response and then replayed. A true lost-ack
fault injection (suppress the response after the engine processed it) was not
performed, so the recovery path is strongly supported but not proven. It also
does not create *exactly-once execution* of the surrounding run: it only makes
the answer POST replay-safe.

### F4 — Read-back exists, but is textual (observed)

`GET /sessions/{id}/messages` after the answer contains:

```json
{"type":"tool_result","data":{
  "tool_call_id":"call_104c9603668e426987a7f78f",
  "name":"question",
  "content":"Q1: Proceed again?\nUser answered: yes",
  "is_error":false}}
```

The question event carries the same `tool_call_id`. There is **no batch id and
no structured selected-id field** in the tool result.

Interpretation: a lost acknowledgement can be resolved by reading history and
matching the question's `tool_call_id`; the answer itself must be parsed from
the textual content (`User answered: yes`). **Scope: the tested `yes_no` prompt
only.** Multi-choice and free-text formats were not exercised and must not be
assumed; A3 must keep an ambiguous outcome when the answer cannot be identified
reliably.

Caveat observed on the device: the app's own `app.log` masks the engine's
`tool_call_id` as `[redacted-key]` (the key masker matches the UUID shape). The
app must retain the raw `tool_call_id` from the parsed event in its execution
record; it cannot recover it from the log.

### F5 — App-process death kills the engine on this ROM (observed)

| Mechanism | App process | Engine children | Notes |
|---|---|---|---|
| `am kill com.crush.mobile` (backgrounded) | killed | **both killed** | `ps` empty after the command; `app.log` ends at `pause/stop` |
| `am crash com.crush.mobile` | killed | **killed** | same `ps` result |
| `run-as … kill -9 <pid>` | alive | alive | `kill: unknown pid` (refused) |
| `run-as … sh -c 'kill -9 <pid>'` | alive | alive | shell usage error / not delivered |
| `run-as … toybox kill -9 <pid>` | alive | alive | `kill: unknown pid` |
| `adb shell kill -9 <pid>` | alive | alive | `Operation not permitted` (SELinux) |

Interpretation: on this device there is **no supported way found to kill only
the app process**. Every tested mechanism that kills the app also kills the
engine child. `am force-stop` and the MIUI `OneKeyClean` path were **not**
re-tested; both are expected to kill the package's processes as well. The
"engine survives app death" scenario is therefore **not producible with the
tested mechanisms**, and the republish result in F1 must not be read as proof of
process-death recovery.

With a question parked, `am kill` destroyed app + engine; after relaunch the app
showed the project session list with no question dialog (the engine that held
the batch no longer existed). This is an observed absence, not an event.

### F6 — Idle-timeout suppression is inconclusive (not performed)

The engine idle timeout is 15 minutes (AUTO on this device) and is baked into
the child's environment at launch. Two confounders prevented a valid test:

- while the app is alive, the supervisor's health probe (`/v1/version`) runs on
  the configured interval and is a request the engine may count as activity;
- the only app-death mechanisms available kill the engine too (F5), so the
  "no client, engine still alive" state cannot be created.

Changing the timeout to 5 minutes would require an engine restart, which would
destroy the parked prompt before the measurement, and the health probes would
still confound "SSE suppresses idle" versus "probe suppresses idle".

**Verdict: `not-performed`.** The plan's question about active-run/SSE idle
suppression remains open and needs a device where the engine survives app death
(or an engine-side unit/contract test).

### F7 — Incidental protocol observations

- The republished event is `question_batch_request` type `created`; the engine
  also emits `question_batch_notification` (`batch_id`, `session_id`) when the
  prompt is resolved.
- The question payload includes `confirm_title` / `confirm_description`. The
  engine sent `confirm_title` equal to the question text, which explains the
  duplicated sentence in the Phase F dialog: it is engine-provided, not an app
  duplication bug.
- The tool result for a cancel is `is_error:true` with
  `"User cancelled this question"`; the tool result for an answer is
  `is_error:false` with `"Q1: … User answered: <value>"`.
- The answer/cancel endpoints return `{"resolved": <bool>}` with HTTP 200 in
  both the first and the replay case.

## 5. Security finding (must be handled before release)

`GET /v1/workspaces` returned the full workspace object including
`config.providers.<id>.api_key` **in plaintext**. The value was printed into
this session's tool output; it is not reproduced here. The app's own records
warn that `/v1/workspaces/{id}/config` returns the raw key, but the **list**
endpoint does too, which makes any broad diagnostic dump a credential leak.

Actions:

1. Treat the exposed provider key as compromised; **rotation is pending (owner
   action; this agent has no provider-console access)**. After rotation, update
   the app's stored key through Settings.
2. Any future evidence command must select fields explicitly, never dump a
   workspace/config object whole.
3. Record this as a release-readiness/security item (Track B, B-G5): the engine
   exposes the key to any local client with socket access; the app must never
   log or export it.

Exposure scan performed on 2026-11-26 (counts only, values never printed):

| Location | `oc_sk_` hits |
|---|---|
| `docs/v2` reports | **0** |
| `docs/parity/v2-16-phase0` evidence | **0** |
| device `files/app.log` | **0** |
| device `files/crash-archive.txt` | **0** |
| device `files/phase0-probe.txt` | **0** |

The key remains in the engine's own app-private configuration by Crush's design
(plaintext config, backup-excluded); rotating and re-entering it replaces that
value. The incident is the **transcript/tool-output exposure**, not a device
file leak.

## 6. What this means for the plan

| Decision/row | Effect |
|---|---|
| **D3** (prompt durability) | Republish is real for a live engine, so the "republish + cancel-on-attach" option is viable **when the engine survives**; the engine-death case still requires a visible `lost` outcome. App-side persistence is not required for the republish path, but is required to record that an execution existed and was lost. **D3 remains open; this checkpoint does not decide it.** |
| **A3** (prompt policy) | Store `tool_call_id` (available in the question event) as the read-back key; parse `tool_result.content` for the answer; treat `is_error` as the cancel signal. Keep the ambiguous outcome for any answer that cannot be identified; do not generalize beyond `yes_no` yet. |
| **C6** (lost ack) | **Same-request replay safety** is demonstrated (200 + `resolved:false`); a lost-ack retry is supported but **not** fault-injection tested. Exactly-once execution is still not claimed. |
| **R32 / D4** (idle) | Unverified on this device. Do not set a short idle timeout expecting cleanup while the app is alive; health probes are a confounder. **D4 remains open.** |
| **R20** (orphaned prompt) | On this ROM, process death kills the engine, so the prompt cannot survive as an engine-side orphan; it is lost. The app must show that loss. |

No decision in D1-D7 is finalized by this checkpoint.

## 7. Limits

- One device, API 29, one engine build (`v0.100.0`), one model.
- The republish test used a new client on a **live** engine; process-death
  survival was not producible (F5).
- Idle-timeout semantics were not measured (F6).
- Multi-choice and free-text read-back formats were not exercised.
- The experiment relied on `run-as` + the device's `curl 7.64.1`; the 404 noise
  seen once was a shell/argument artifact, and the clean `-i` runs show 200.

## 8. Verdict

**P0-E1: partially conclusive.**

- **Fully answered:** republish and identical ids for a new client on a live
  engine; cancel replay (200 + `resolved:false`).
- **Partially answered:** answer replay is *same-request replay safety*, not a
  fault-injected lost-ack recovery; read-back is proven for the tested `yes_no`
  prompt only.
- **Not performed:** engine survival after app death (no app-only kill exists on
  this device); idle-timeout suppression.
- **Not established:** exactly-once execution.

D3 and D4 remain open. The gaps are reported, not inferred.
