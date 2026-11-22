# V2 ADR register (opened 2026-11-19)

Purpose: one place that says which V2 decisions are settled, which are
owner-gated, and what evidence backs each. It complements
`../adr-0001-project-workspace-identity.md`; nothing here overrides ADR-0001
or the Rev 3.1 state contract.

Rules:

- A V2 package may implement only ACCEPTED decisions.
- An OPEN item is not a design choice to be settled in code; it needs an
  owner answer, then an ADR entry here before implementation.
- PARKED items are out of V2.0. Reopening one needs a new approval.
- "Accepted" records direction, not shipped behavior.

## Accepted

| ID | Decision | Date | Evidence |
|---|---|---|---|
| V2-A1 | Hybrid authority: Revision 4.0 is the forward roadmap/design direction; the November 14 Rev 3.1 brief stays authoritative for the UI state contract, test registry, composer gates and parity standard | 2026-11-19 | status doc §5.10 |
| V2-A2 | Shell: three primary destinations - Chat, Projects, Settings. Review and Diagnostics are pushed routes. Phone bottom bar below 600dp, rail 600-839dp, drawer/extended rail at 840dp+ (brief §4.6). No fourth "Code" destination | 2026-11-19 | §5.10; `NavGraph.kt`; Android adaptive navigation guide (retrieved 2026-11-19) |
| V2-A3 | Code is project-bound: no global Code workspace, no cross-project session namespace; project identity stays visible | 2026-11-19 | §5.10; ADR-0001 |
| V2-A4 | No brief deferral is reopened: pinned message/follow, cost/context-window display, new density/navigation/response-rendering preferences, structured model UI and release packaging stay backlog | 2026-11-19 | §5.10; package trims |
| V2-A5 | Provider offer rule: offer tested providers only (`isKnown`), plus any provider whose key is already stored; hidden ids remain resolvable | 2026-11-18 | `Providers.offerable`, `1c902eb`, after-f12 manifest |
| V2-A6 | Evidence labels (CODE/TEST/CI/DEVICE-RECORDED/DEVICE-VERIFIED/PARTIAL/PLANNED/NOT-PERFORMED/OWNER-DECISION) are mandatory in V2 reports | 2026-11-19 | Revision 4.0 §1.2; §5.10 accepted rules |
| V2-A7 | V2.0 active scope is V2-0 to V2-6 only; V2-7, V2-8 and V2-9 are parked | 2026-11-19 | §5.10 package table |
| V2-A8 | V2-1 visual direction approved (owner, 2026-11-19): neutral graphite accent (`#3B3B36` light / `#D3D0C8` dark, measured 10.62/12.17 on paper/bg), body text 15sp, platform monospace, V2-2 includes call-site reclassification. Because the accent is only 1.53:1 (light) / 1.29:1 (dark) from ink, interactive/selected state must never rely on hue alone: underline, fill, weight, filled icons and a 2dp focus outline carry it | 2026-11-19 | `V2-1-design-foundation.md` §4.1, §9; §5.11 |
| V2-A9 | V2-5 ships **Build (project chat) + Review** as the phone workflow. **Explore is deferred** until a real read-only file browser exists; no fake Explore tab and no fourth primary destination (V2-A2). Build/Review navigation must not grant permission or change a run's binding/policy | 2026-11-20 | owner answer 2026-11-20; `V2-5-code-projects-review.md` |
| V2-A10 | The legacy workspace is **Chat-only** and is not listed in Projects. Projects lists imported/app-managed Code projects; a row opens a **project home** (identity, path, trust, last measured changes, Open Code chat, Review) rather than jumping straight into a chat. This supersedes brief §4.7.2's "legacy row" clause | 2026-11-20 | owner answer 2026-11-20; `V2-5-code-projects-review.md` |
| V2-A11 | **Crush Chat uses the legacy engine instance** (owner sketch). The legacy instance receives the same readback-verified deny-all tool policy as the former ChatPool, so Chat stays Q&A-only at the engine boundary. ChatPool remains a compatibility/archival binding and is no longer the Chat tab's engine | 2026-11-20 | owner notebook 2026-11-20; `V2-5-chat-code-ia-plan.md` |
| V2-A12 | The three primary destinations are labeled **Crush Chat, Crush Code, Settings**; Projects is the project list inside Crush Code, not a peer tab. Chat and Code are list-first: Chat shows chats, Code shows projects, a project shows its own sessions. Each project's sessions stay on that project's engine | 2026-11-20 | owner notebook 2026-11-20; `V2-5-chat-code-ia-plan.md` |

## Open (owner gate)

| ID | Question | Blocks | Current disposition |
|---|---|---|---|
| O3 | Tablet/foldable list-detail panes | any wide-window claim | after phone acceptance; not in V2.0 |
| O4 | Tool-activity visibility level | Chat transcript design | backlog until usability evidence |
| O5 | Any preference beyond existing Settings | V2-6 | backlog; no new preference batch |
| O8 | AFA/import-only policy change | project import | NO CHANGE; needs ADR + store review |
| O9 | Release hardening/rollout | shipping | separate roadmap; V2-9 parked |
| O10 | Deny-all relaxation for Chat | Chat tool policy | DEFERRED; deny-all stands |
| O11 | **RESOLVED 2026-11-22** - loading row captured on device (`Loading sessions...` / `Loading chats...`) via a debug-only stall harness plus a debug-only preview activity; see `docs/parity/2026-11-22-o11-session-loading/` | parity closure | done; live window evidenced by the stall log and unit pins |
| O12 | **RESOLVED 2026-11-21** - Finding 13 copy fixed in V2-6 ("Add a key for \<provider\> to start chatting.") | - | resolved; see §5.15 and the V2-6 record |
| O14 | CI SDK auto-download flake: `platforms;android-37.0` failed with a zip-read error on run `44549231349` attempt 1; attempt 2 passed | gate reliability | record only; hardening (pre-install the platform and/or retry the download in `.github/workflows/android-ci.yml`) edits a build file and needs its own authorization |

Resolved during V2-0: **O13** - the November 14 brief is 1,319 lines
(74,654 bytes) on disk. An earlier 996-line count was
`Measure-Object -Line`, which counts non-blank lines only (996 non-blank +
323 blank). Revision 4.0's 1,319-line reference is correct; no owner
action remains.

Resolved 2026-11-19: **O2** - the owner answered Q1-Q4 (graphite accent,
15sp body, platform monospace, include call-site reclassification) and
approved sections 4-8 of `V2-1-design-foundation.md`. Recorded as **V2-A8**.
This unblocks V2-2 only; V2-3 still needs section 7's navigation decision
and an explicit go.

Interpretation recorded with V2-1 (not a reopened decision): brief 4.2
principle 1 budgets "permanent chrome <= 56dp on a 360dp phone" while
brief 4.6 and V2-A2 require an 80dp phone bottom bar. The 56dp figure is
read as the top-bar/transcript budget, with the bottom bar separate.

**Resolved 2026-11-20:** the owner authorized V2-3 with the option text
"80dp bottom bar below 600dp, rail 600-839dp, extended rail/drawer 840dp+",
so the 80dp bar is the accepted reading and V2-3 implements it. The 56dp
"permanent chrome" budget therefore does not include the bottom bar.

## Parked

| ID | Item | Note |
|---|---|---|
| V2-7 | Deterministic intelligent UI foundation | separate approval; A2UI is alpha (`1.0.0-alpha02`, 2026-10-21) |
| V2-8 | Structured model UI experiment | same |
| V2-9 | Release hardening and rollout | Gate 7 remains deferred |

## Standing constraints (not decisions to revisit)

- ADR-0001 identity chain and run-freeze invariants.
- Chat deny-all policy is an engine boundary, not a visual toggle.
- Host-owned trust, secrets, file grants, diffs and revert decisions.
- No provider fallback that can surprise the user with cost.
- Preserve user data: no silent history wipe or project migration.
- Bytecode target 17; CI Temurin 21; minSdk 29.
