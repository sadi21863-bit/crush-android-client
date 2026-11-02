# Session-switch transcript bleed (user report, fixed 2026-11-02)

## What the user saw

> "When we switch between existing chats, the other session's chats get
> transferred to the chat I am currently in — or I can see the other
> session's chat (the one I switched from) inside the session I am currently
> in."

## Defect family (code-verified; no device reproduction needed — the seams are
just wrong)

Two unguarded writers share one `messages` list across session boundaries:

1. `openSession()` — the history-replay write. The merge fed
   `mergeReplay(replayed, it.messages)` where `it.messages` is the PREVIOUS
   session's on-screen transcript; `mergeReplay` appends every live row the
   fetched history does not contain, so switching A→B rendered **A's whole
   transcript under B's**. The R1 race comment (2026-10-31) had justified
   that merge only for "a turn the user sent while the history was loading" —
   i.e., the reopen-the-same-session case — but the code applied it to every
   switch.
2. `streamSend()` — the row insert at turn start
   (`messages = it.messages + userRow + streamingBubble`), plus `isStreaming`
   and `input = ""`. It runs AFTER a two-network-round-trip preflight; if the
   user switched chats during that window, the turn's rows were inserted into
   whatever transcript the user later had on screen, `isStreaming` lied, and
   the other chat's composer was wiped.
3. Related: `openSession()`/`newThread()` hold NO stale-switch guard. Two
   rapid switches → the slower history fetch (or session creation) lands
   last and wins, writing the abandoned session's data over the live view.

## The fix — one view epoch, guarded writers

`sessionViewEpoch` (monotonic, bumped by every session switch):

- `openSession()`: snapshots the epoch before the history fetch; if it moved
  on, **drops the write entirely** (no transcript, no wsId/sessionId, no
  current-session report). Only the newest switch may write.
- `newThread()`: same contract around `newSession()`.
- `send()` preflight: if the view moved on while the prompt was being
  prepared, the turn is dropped **before anything is posted** — the same
  policy the app already applies to permission prompts raised by a
  switched-away chat (analysis §4 P1-7). Honest UX: nothing was sent.
- `streamSend()`: second fastener — the transcript insert (rows,
  `activeSessionId`, `isStreaming`, composer clear) is withheld if the epoch
  moved; the turn only ever lands its rows in the chat view it belongs to.
- `openSession()`'s merge is now scoped by
  `liveRowsForOpenSession(...)`: live rows carry across ONLY when the target
  session was already on screen AND a run is in flight in it (the R1 case).
  On a real switch the new transcript is clean; a completed turn reappears
  through the engine's own history replay when the user switches back.

## Known edge, on record

A turn streaming in session A while the user views B and switches BACK to A
mid-stream: the in-flight bubble is not re-inserted (the epoch says "stale"),
so that turn completes invisibly until the next openSession replay. Rare,
deterministic, recoverable by reopening the chat; a future refinement can
re-fetch that session's history when a run finalises while its own session is
the viewed one.

## Tests

`ChatSessionTranscriptGuardTest` pins the scoping decision: different-session
and fresh-view switches carry nothing; same-session + live run keeps the
in-flight rows (R1); same-session without a run keeps nothing (engine
history is the truth).
