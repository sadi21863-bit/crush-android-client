# Batch C observations — mechanism resolution (2026-11-09)

The P6 Batch C device verification (capabilities"P6 BATCH C DEVICE
VERIFICATION", docs commit `d0d3b45`) left six open observations. The
owner's directive for this gate: resolve what CAN be resolved, so no
ambiguous runtime behavior carries into Batch G or the v0.100.0 ladder.
Evidence base: the pulled device `app.log` (`files/app.log`, 30,185 B,
session started 18:35:20; windows 18:35-18:41 and 19:14-19:19 read as
raw lines; the C session's on-device screenshots), plus `ChatViewModel`
line anchors as of `20e8347`/Batch F work in flight.

What follows is per observation: MECHANISM (decoded with evidence),
then resolution class. Two of the six turn out to be the DESIGNED
absorption sequence doing its job; one becomes a concrete trigger we
can now name; the rest are honest documented classes.

 ---------------------------------------------------------------

## (1) The first verify turn's run canceled after ~20 s

Evidence line: `18:40:58.748 W/ChatViewModel: stream idle > 20s for run
c2e97e48-...; requesting engine cancel` — then `18:40:58.870` "+0 -0
across 0 file(s)" (the turn's bookkeeping closing; the bash cards had
finished; the agent's opening reply was on the screen).

Mechanism: the DESIGNED stream-idle watchdog (the prompt-wait machinery
reports a run idle when no stream frames arrive for 20 s and cancels the
app-side stream). During a long TOOL phase the engine posts no stream
frames — an ask-gated bash card plus the model's tool turns can exceed
20 s in wall time with no frames — so the watchdog saw "idle" and
canceled. The engine honored the cancel; the agent's turn ended early;
the opening reply remained; the owner's "continue" nudge re-ran the
protocol as a fresh turn (which is the SAME separate-turns rule the
template preaches).

Resolution class: RESOLVED-BY-MECHANISM, no defect. The threshold is
deliberate state (the same family as the 60 s socket budget and the
300 ms settle beat — constant, not a bug). What the drive taught is the
COST of a false idle: a mid-report run dies and the user must nudge.
That cost is recorded here; if the v0.100 ladder re-proves the stream's
tool-phase frame flow (schema messages land per tool event), a longer
tool-aware idle is a candidate change, deliberately NOT taken now.

## (2) The "resuming session 4161054d (110 msgs)" flip-flop

Evidence lines (18:40:26-29):

    18:40:26.184 engine Running after 3162ms (instance p536fa26e)
    18:40:28.741 resuming session 4161054d-... (110 msgs)
    18:40:28.744 event transport identity change -> crush-p536fa26e.sock
    18:40:29.171 current-session reported: 4161054d
    18:40:29.466 current-session reported: f3e78747
    18:40:29.503 current-session reported: 4161054d

Mechanism (decoded): `4161054d` is a REAL session of the code
instance's engine (its 110-message longest thread from earlier drives)
- NOT the handoff target. The "resuming session" grab is the designed
BOOTSTRAP-TAIL behavior the settle-beat comment in
`bindCodeForHandoff`/`dispatchHandoff` itself documents word-for-word:
"bootstrap's tail claims the last-resumed session the moment engineReady
flipped true". Its `findSessionToResume` + `openSession` (the resume
route at `ChatViewModel.kt:1334/:1372/:1535`) picked the newest
with-content session the engine offered (4161054d) a few hundred
milliseconds BEFORE the dispatch minted its own f3e78747; the dispatch's
own ownership transfer (`sessionId = target.id`, the epoch bump, the
note-row view reset) then WON, and every earlier grab write was dropped
by the same stale-view epoch guard the machinery carries (the grab's
replay fetch lands seconds later and its epoch check fails - the write
dies without stomping anything). The three "current-session reported"
lines are the report loop's ticks interleaving across the window; the
row stamp recorded exactly the dispatch's write (the pulled Room row:
`targetCodeSessionId=f3e78747…`).

Net behavior: a self-correcting race whose every write is already
graduated by design. Residue on screen: a transient report-line
flip-flop and the 4161054d label appearing in the stream for <3 s.

Resolution class: RESOLVED-BY-MECHANISM. The dispatch's designed
absorbents (settle beat -> epoch bump -> note-row reset) ran and won.
No defect, no change taken. For Batch G this region matters: the
cold-boot resume route shares the grab machinery, so G's design must
route through the SAME ownership discipline (it already plans to - g2
is the route, openSession is the executor).

## (3) The header title settled "Handoff: Tool" (minted "Handoff HHmmss")

Mechanism: the mint title is app-carried (WorkspaceManager.newSession
title, "Handoff <HHmmss>"); the header renders the engine's session
record, whose audience-facing title is ENGINE-OWNED. "Handoff: Tool" is
the engine's auto-title pass (the same family the capabilities doc
records as "live auto-titles": "New chat" became "Ping"). The app's
mint is a REQUEST; the engine's title pipeline wins the display.

Resolution class: RESOLVED-BY-MECHANISM (display contract), with an
honest open edge: the engine is free to retitle, and "Tool" is a poor
auto-title for a handoff thread. Not surfaced to the user as an error;
the sheet and the row keep the deterministic mint ("Handoff HHmmss"
stays source-of-record in the provenance line). Candidate (not taken):
upstream's auto-title behavior on freshly-minted sessions rides the
v0.100 ladder only if it breaks a real user flow; nothing here does.

## (4) The transcript merges old code content and handoff turns

Evidence: the drive's L1-L4 screenshots (the merged scroll), and the
same "resuming session" family above. Given (2): the swapped grab's
replay of 4161054d's 110-message transcript has NO path into the
persisted view (its epoch check fails on the late fetch); what the user
saw as "merged" is the ENGINE's own session history for f3e78747: the
minted id reaching the app is a full engine-side UUID
(`f3e78747-4baa-…`), NOT the app's `ses_`-prefixed mint, which says the
engine created its own session record (with its own lineage) and the
history the app replays when the drag returns is the engine's - which
per the drive visibly carried the pre-handoff context.

Resolution class: RESOLVED-BY-MECHANISM at the app layer (nothing the
app wrote made the merge; provenance honesty stays intact - the row and
the note are exact), with ONE decisive probe left to the D+F device
drive: read the code workspace's sessions listing after a fresh dispatch
and inspect the minted session's parentage/message set. If the engine
parented the handoff session onto the newest code session (evidence
says yes: "4 msgs"_DIRECT at 19:18 vs the visible merged scroll), the
drive records that as an upstream engine fact (v0.100 ladder surface)
instead of remaining an app-side ambiguity.

## (5) The `.crushrc-ran.marker` writer (the caught tree mutation)

NEW EVIDENCE - the smoking gun window (19:18):

    19:18:07.927 start MainActivity          (owner returned)
    19:18:07.984 resume gate=PROMPT away=1128s
    19:18:08.138 lock engaged; refusing to bootstrap
    19:18:20.794 unlocked
    19:18:20.835 lock released; resuming
    19:18:20.837 bootstrap instance=p536fa26e workspacePath=...
    19:18:23.503 resuming session f3e78747-... (4 msgs)
    19:18:23.518 connected to workspace 06f08e48-...

The marker's mtime is 1794232103 = **19:18:23** - the SECOND of the S1b
mechanism's crushrc executions, firing inside this exact unlock-re-attach
window (the S1b probe measured: the engine executes the project-root
crushrc as bash on every workspace attach). The 18:40:26 dispatch attach
executed it too - the dispatch's stat-walk runs AFTER that attach, so
the captured digest contains the marker AS OF the attach (that is the
tree as-dispatched, honestly). The verify turn's re-walk caught the
marker because the UNLOCK re-attach moved it AFTER the dispatch digest.

Mechanism: `crushrc` (the 72 B, 2026-11-06 probe residue in the target
folder) IS the writer - the engine runs it as bash on every workspace
(re)attach, and its single line writes the marker (S1b: the executed
surface is `crushrc` only; `.crush.json`/`.crush/crush.json` did not
execute in the paths probed). There was never a second unknown writer.
The ask-card stream's permission-requests at 18:40:28-33 and 19:24:2x
are the SAME executions surfacing as bash permission prompts under
permissionMode=ASK.

Resolution class: RESOLVED-BY-MECHANISM - the writer is identified
(the consented crushrc execution on attach), not a mystery anymore.
The design consequence stays honest: the verify-first protocol CANNOT
distinguish "consented crushrc execution moved a runtime artifact" from
"a malicious edit" by STAT alone - the mismatch report flags anything
and waits for the owner's decision; the owner's call resolves it. The
residue is owner property in
a user folder; removing `/storage/emulated/0/Test project
folder/crushrc` (+ the marker) removes the recurring drift class for
future drives - owner-approved operation, deliberately not performed
ad hoc NOW (it also serves as the standing drift generator for the
drive). Batch F's digest captures the marker both times BY DESIGN
(consistency over exclusion); exclusion of runtime artifacts is an
explicit non-goal of this digest.

## (6) The model tag dance deepseek -> haiku -> deepseek

NEW EVIDENCE - it happens on EVERY unlock/bootstrap, not once:

    18:40:26.644 ensureAgentReady ... model[large] = deepseek-v4.1-flash
    18:40:28.517 probed 1 candidate(s), using claude-haiku-5-5 (of 27 listed)
    18:40:28.700 ensureAgentReady ... claude-haiku-5-5 (agent/init ok)
    18:40:38.670 probe deepseek-v4.1-flash -> WORKS
    19:18:21.346 ensureAgentReady ... deepseek-v4.1-flash
    19:18:23.342 probed 1 candidate(s), using claude-haiku-5-5 (of 27 listed)
    19:18:23.417 ensureAgentReady ... claude-haiku-5-5
    19:18:30.215 probe deepseek-v4.1-flash -> WORKS

Mechanism: the instance's session-store holds the user's selection;
the BOOTSTRAP's model-store probe path runs on every (re)bind
(dispatch attach, unlock resume - both windows show the same flip) and
re-picks the SAME pool default (claude-haiku-5-5) before a wider probe
confirming the user's chosen model still WORKS re-asserts it.
ensureAgentReady toggles agent/init with each flap. End state: the
user's model. The cosmetic family the capabilities doc already carries
("the session's model tag resets on relaunch") - the dispatch window
merely exposes it twice in one minute.

The REAL open edge (about which the D+F drive must decide): WHICH model
actually served the verify-first first turn? The ensureAgentReady at
18:40:28.700 landed BEFORE the first turn's send (~29.5+) and deepseek's
re-assertion printed only at 18:40:38 - so the first turn plausibly ran
on the default (haiku), not the stamped deepseek. The row's
`sourceProvider=deepseek-v4.1-flash` is the CHAT-side stamp (correct,
intentional), but the drive should capture the AGENT-side model id the
engine reports when the first turn RUNS. If it differs from the stamp,
the fix candidate is: dispatch carries the stamped model id to the
target instance's model selection (a setModel call after agentReady)
before the first turn - a small, honest Batch F tail that makes the
handoff deliver the model the user approved with (decision pending
drive evidence, recorded as the gate's open question, deferring to the
D+F drive).

 ---------------------------------------------------------------

## Summary state (why nothing rises to a Batch G blocker)

| # | Observation | Class | Action taken here |
|---|---|---|---|
| 1 | idle watchdog canceled a tool-heavy run | designed guard, cost recorded | none (documented) |
| 2 | resume-grab flip-flop at dispatch | designed tail + epoch guard absorbed it | none (G told to share the discipline) |
| 3 | header retitle "Handoff: Tool" | engine auto-title display contract | none |
| 4 | merged transcript scroll | engine-side session lineage; one probe left | probe rides the D+F drive |
| 5 | marker writer | the consented crushrc execution on attach (S1b) | mechanism named; residue removal = owner call |
| 6 | model tag dance | unlock/bootstrap probe flapping; end state correct | first-turn model id capture rides the D+F drive; stamp-to-target fix is the decided follow-up IF the drive shows a mismatch |

No batch below G is blocked by any of these. The v0.100.0 ladder is the
right place to re-prove the underlying engine behaviors (attach-
execution, session lineage, auto-titles, probe flap) on the new .so,
per the owner's "no ambiguous runtime behavior" gate.
