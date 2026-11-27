# A2 slice 1 — authorized scope (2026-11-27)

Owner decision: **A2 slice 1 only**. Full A2 was NOT authorized. A1 remains
accepted; this record adds no A1 changes.

## In scope

- Additive Room **v3→v4** `executions` table on the existing `projects.db`,
  registered in the existing migration chain. No destructive fallback.
- An `ExecutionStore` for run-phase records: execution id, run id, engine key
  token, workspace path, session id, engine generation / lease where observable,
  phase, timestamps, named terminal reason and acknowledgement state.
- Migration test from the current schema: preserve existing rows byte-for-byte
  and prove a guarded phase transition.
- Durable **intent before POST**, acknowledgement record, and honest ambiguity
  at the four send-path boundaries of plan §16.2 / design §6:
  before persist, after intent before send, after send before ack, after ack
  before running. No automatic re-send of an ambiguous send.
- Startup **reconciliation** for run cells **C1–C3** only:
  - C1 idle — no phantom run, no empty session minted;
  - C2 mid-turn silent — reattach + reconcile, reconstruct, or mark lost;
  - C3 mid-tool — same plus reconcile the tool row from history; tool state may
    be unknown but must be shown as such.
- Stale-generation protection (§16.3 / §17.1) where the existing seams allow a
  deterministic test; a mismatch must never rebind a recovered execution to a
  new engine as if it were the old one.

## Explicitly out of scope

- **C4 parked permission, C5 parked question, C6 after Submit before ack** —
  deferred to D3/X1 and A3. No prompt policy is invented here.
- Prompt text, transcripts, provider keys or any secret in a durable record.
  Only ids, a raw prompt *reference*, timestamps and named reasons.
- D1 (foreground service), D2 (capacity policy), D4 (long-run contract) and D7
  final resolutions. They stay open.
- Device fault-bridge work (R37) and any claim that C1–C3 were observed on a
  device. Verification is JVM/Robolectric unless separately authorized.
- New libraries, plan or frozen risk-register edits, `main` changes, PR/merge.

## Gates carried forward

- Full JVM gate floor is 1087 tests; current baseline is 121 suites / 1129
  tests / 0 failures. A lower count fails A-G7.
- No GitHub Actions run exists for this branch; the gate evidence stays local.

## Evidence expectation

A separate A2 slice-1 record will list which cells and crash boundaries were
actually exercised, by what artifact, and which remain source-wired only.
