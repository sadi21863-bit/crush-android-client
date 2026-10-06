# UI/UX Specification & Roadmap

Written 2026-10-03 from real sources, not taste. Reference implementations
reviewed: `lovechat` (`:chatui` library), `MaterialChat`, Androidify (Google's
M3 Expressive reference app), plus Material 3 Expressive research.

## Research findings that change our decisions

1. **M3 Expressive measurably works.** Google's eye-tracking study (46 studies,
   18,000 participants) found users located key UI elements **up to 4× faster**
   in Expressive designs, and tap times dropped by seconds.
2. **But it is opt-in and risky for us.** It lives in
   `androidx.compose.material3:material3:1.4.0-alpha10`. We are otherwise on
   stable BOM `2026.09.00`. Adding an alpha to chase animation is a bad trade
   while the send path is unverified. **Decision: stay on stable M3. Revisit only
   after the app is functionally complete.**
3. **Expressive is not universally right.** The same research warns that
   breaking familiar interaction paradigms *reduced* usability (removing text
   labels from email actions scored worse). Chat has strong conventions; we
   should not reinvent them.
4. **Streaming markdown is the hard part, and everyone solves it the same way.**
   `lovechat` hand-rolls a character-scanning parser (no regex) so that **unclosed
   code fences and half-built tables render gracefully mid-stream.** That is
   exactly our problem: Crush republishes the *whole* message each frame, so we
   re-parse on every frame and must never flash a broken layout.

## P0 — Blocking gaps

### 1. Markdown rendering (biggest gap)
Right now assistant replies render as **plain `Text`**. An LLM reply contains
code fences, bold, lists and tables. Showing those unstyled looks broken.

- Hand-rolled incremental parser. Must be safe on a *partial* buffer.
- Required: fenced code (+ language label), bold/italic, inline code, H1–H3,
  bullet lists, blockquote, links, tables.
- Unclosed fence mid-stream → render as plain monospace until it closes.
- Perf: re-parse on every SSE frame is fine **only if** the parser is
  allocation-light. Cache by message length to skip redundant work.
- Do **not** use `AnnotatedString` with a regex parser; regex on partial input
  is how you get flickering.

### 2. Send path unverified
`ChatViewModel.send()` was root-caused (an exception inside `launch{}` escaping
its own `try/catch`, plus a stale workspace id) and fixed, but **never confirmed
end-to-end**. This is P0 and blocks any UI work on top of it.

### 3. Unlock flow missing
App correctly routes to Chat with a stored key, then says *"no API key - add one
in Settings"*. That message is **wrong**: the key exists, it needs a fingerprint.
- ChatViewModel must prompt for unlock, not report "missing".
- Copy: *"Unlock to use your saved key"*, not *"add one in Settings"*.

## P1 — Core chat UX

### 4. Streaming feel
We get **real deltas**, so a typewriter effect would be wrong (it would lag the
truth). What matters:
- Render the newest text immediately; no artificial delay.
- Keep the composer enabled-looking but show a **Stop** button while streaming
  (already built, untested).
- Autoscroll only when already near the bottom, so reading history is not yanked
  away. **Already implemented correctly.**

### 5. Pinned user message
From `lovechat`, and it is the single best idea in that repo: on send, the user
message **snaps to the top of the viewport** and stays while the reply unfolds
below. The conversation reads as *turns*, not an infinite scroll. Strongly
recommended.

### 6. Follow mode
Opt-in auto-scroll, **off by default**. Turns on if the user scrolls to the
bottom mid-stream; any scroll-up turns it off. Needs a jump-to-bottom FAB.

### 7. Edge-to-edge IME
Composer must sit above the keyboard. Use
`WindowInsets.imeAnimationTarget` with a critically-damped spring, and
`windowSoftInputMode="adjustNothing"` so Compose owns inset handling instead of
fighting the system.

### 8. Translucent bars
Top bar and composer paint a fading backdrop so messages dissolve behind them
rather than colliding with the status/nav bar.

## P2 — Navigation & polish

### 9. Session list
A ChatGPT-style app needs conversation history. We have `listSessions` in the API
but no UI. Drawer (`ModalNavigationDrawer`) on phones.

### 10. Model picker redesign
Current top-bar label reads `FREE space-bunny` — cryptic. Instead:
- Tappable row showing model name + a `Free` badge.
- Bottom sheet (already built, untested) grouped: Free first, then paid.
- Show context window and cost per 1M so the choice is informed.

### 11. States
Every screen needs explicit states. We currently show a bare word centred
("ready", "no API key"), which reads as broken.
- **Empty:** friendly prompt + suggested starters.
- **Loading:** skeleton or the expressive loading indicator.
- **Error:** inline in the bubble, actionable, never a silent failure.
- **Engine down:** distinct from "no key".

### 12. Accessibility
Per M3 research: exceed minimum tap targets; verify contrast; content
descriptions on icon buttons (several of ours already lack them).

## Visual system

- Keep M3 stable. Spring motion: damping `0.6`, stiffness `500`
  (values from MaterialChat, a good starting point).
- Role styling: user = `primaryContainer`, assistant = `surfaceVariant`
  (already implemented).
- Rounded 16dp bubbles with a 4dp "tail" corner on the sender's side
  (already implemented).
- Dynamic colour on Android 12+ with a branded fallback.

## Roadmap order

| # | Item | Why first |
|---|---|---|
| 1 | Verify `send()` end-to-end | Blocks everything |
| 2 | Unlock prompt in chat | App is unusable without it today |
| 3 | Markdown streaming renderer | Biggest perceived-quality jump |
| 4 | Pinned user message | Cheap, high impact |
| 5 | Real empty/error states | Removes "looks broken" feel |
| 6 | Model picker redesign | Model choice is central |
| 7 | Session list + drawer | Chat app completeness |
| 8 | Follow mode + jump FAB | Streaming ergonomics |
| 9 | Edge-to-edge IME polish | Feel |
| 10 | Consider M3 Expressive alpha | Only after 1–9 |

## Explicitly NOT doing

- **No M3 Expressive alpha.** Stable BOM until the app works.
- **No typewriter effect.** We have real deltas; faking them adds lag.
- **No plan/build agent toggle yet.** It is a *feature flag in Crush*, not a
  different app — revisit after chat is solid.