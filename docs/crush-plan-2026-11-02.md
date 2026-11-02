  
  


RESEARCH\-BACKED IMPLEMENTATION BLUEPRINT

Crush Mobile

Chat \+ Code \+ Projects \+ Optional Termux Environments \+ Local & Cloud AI

Master architecture, incremental migration, security controls, build order, release gates, and upstream\-collaboration strategy\.

2 November 2026  |  Current repository: sadi21863\-bit/crush\-android\-client  |  Observed main: 0741e77

  


__Primary recommendation  __Retain the working embedded Crush v0\.99\.0 engine\. Build two product experiences on a shared foundation\. Make the Project System the canonical Code context\. Offer Termux levels 0–3 as capability choices per project, not a single forced migration\.

__Decision__

__Direction__

Product

Crush Chat / Crush Code with explicit plan\-to\-project handoff

Source of truth

Project ≠ workspace ≠ session ≠ run; one canonical writable project per execution

Built\-in environment

Default, retains app\-private Unix\-domain socket and current on\-device agent

Termux

Optional levelled add\-on; feasibility and security gates before L2/L3

AI

Charm Hyper recommended cloud option; local inference optional on capable hardware; no claims of guaranteed free credits

Release

Ship a reproducible multi\-file coding demonstration before expanding to advanced backends

Document status: design and delivery plan\. Not an assertion that the new features are already implemented or that the current live repository was cloned and executed in this environment\.

# Executive decision brief

Crush Mobile has already solved several unusually hard Android\-embedding problems: Crush child\-process packaging, app\-private Unix\-domain socket transport, long\-lived SSE, session continuity, provider discovery, approvals, and single\-file diff rendering\. The next product leap is a durable project model and a clear separation between free\-form conversation and project\-grounded code execution\. \[R1\]\[R2\]

A single gigantic Termux migration would risk undoing those gains\. The more defensible approach is an opt\-in Development Environment layer that begins with a minimal Termux tool connector, later supports coherent project execution, and treats Termux\-hosted Crush as an explicit experimental backend\.

__Keep and harden__

__Build next__

__Defer until gated__

Embedded engine v0\.99\.0 and private socket

ProjectRegistry; per\-project workspace mapping

Separate engine inside Termux

Streaming, session, tool events, approval UI

Chat/Code capability policy and mode navigation

Bidirectional external\-folder auto\-sync

OpenRouter/OpenCode Go and provider abstraction

Multi\-file edit/test/review workflow

Local 7B\+ model as universal default

CI and initial instrumentation

Signed R8 release, smoke tests, onboarding

Auto\-upgrade to new Crush nightly

__Nonnegotiable invariant  __Never reintroduce an unauthenticated cross\-UID TCP endpoint to bridge Termux\. The existing private Unix\-domain socket mitigates the previous cross\-app access problem; any cross\-app full\-agent path needs a separately authenticated protocol\.

# 1\. Source review, freshness and confidence

The public main branch displayed 117 commits, latest visible CI run for commit 0741e77 \(2 Nov 2026\) and recent successful CI checks\. The README reports Crush v0\.99\.0, long\-lived SSE, ask\-mode approval and tested file diffs\. The repository contains MIT app licensing and third\-party NOTICE\. \[R1\]\[R2\]\[R3\]

I directly inspected the accessible GitHub README, workflow, documentation, provider notes, release pages and official platform references\. A fresh complete git clone from the container failed because github\.com could not resolve\. Older uploaded ZIPs are useful historical architecture references but MUST NOT be mistaken for the latest source tree\. This plan therefore separates source\-documented facts, upstream\-confirmed behavior and recommendations needing new experiments\.

__Evidence grade__

__Meaning__

__Example__

A — publicly confirmed

Live visible file/workflow/release evidence

Latest workflow results; MIT LICENSE/NOTICE

B — repository\-reported

Documented device outcome not independently rerun

Eight androidTest cases; 455 local JVM tests; ASK / diff device pass

C — architecture proposal

Recommended design, not yet present

ProjectRegistry, Chat/Code handoff, Termux level UI

D — experimental

Needs physical\-device proof before promising users

Termux L2/L3, offline model tool reliability

Documentation drift to fix first: README still contains older sections saying “no androidTest,” “no root LICENSE,” and “first green CI pending,” while newer project files and workflow results contradict them\. This should be corrected by a source\-driven documentation pass rather than simply replacing old text with optimistic claims\. \[R1\]\[R2\]\[R3\]

# 2\. Current baseline: do not rebuild solved foundations

__Subsystem__

__Current source\-level/documented state__

__Plan action__

Engine / IPC

Crush v0\.99\.0 child process; app\-private UDS, not TCP

Preserve; build adapter around it

Session / SSE

Long\-lived workspace SSE; reconnection and event routing present

Add per\-project affinity and race tests

Permissions

ASK/allow\-once device flow and question handling reported

Add role\-mode policy and failure/cancel tests

File diffs

Real edit shown on Android 10; multi\-file code case unproven

Prove 2\+ source file edits first

Providers

Multiple providers; Hyper authenticated, but usable funded chat not recorded

Explicit eligibility / credit UX

Tests / CI

455 @Test methods reported; 8 Android instrumentation tests documented; CI green on main

Repro release/artifact pipeline with bundled engine

Distribution

MIT app license and FSL notice created

Audit packaging, signed release, copyright

# 3\. Product thesis, users and scope

Crush Chat is conversation\-first: asking, learning, brainstorming, planning, analysis and drafting\. Crush Code is project\-first: selecting an actual repository, inspecting relevant files, planning modifications, approving tools, editing multiple files, reviewing diffs and verifying tests\. A user\-approved handoff bridges the two without silently increasing privileges\.

__User journey__

__Core value__

__Success demonstration__

Everyday user

Ask questions without shell access or project setup

New chat streams and persists with zero dev\-tool permissions

Student / hobbyist

Import a small project and ask the agent to change code

Two\-file patch, clear diff, export result

Experienced mobile developer

Use Git/toolchain via optional Termux add\-on

Test command runs on matching project revision

Privacy\-focused user

On\-device model when hardware fits

No cloud request; visible offline badge

Charm contributor / tester

Reproducible Android embedding proof

Signed APK, public test report and clear licensing

Non\-goals for initial launch: a complete Android Studio competitor, unbounded background work, automatic untrusted repository configuration execution, automatic silent cloud fallback, and perfect POSIX toolchain support on every handset\.

# 4\. Proposed structural architecture

Native Compose UI  
  |\-\- Chat experience  \(ConversationPolicy: no code\-execution tools\)  
  |\-\- Code experience  \(ProjectRegistry \-> Explore/Build/Review\)  
  |\-\- Handoff composer \(user\-reviewed context snapshot\)  
  \+\-\- Settings / Providers / Environment setup  
             |  
     Session \+ Run Coordinator  
       \- identity, run cancellation, stream routing, lock gating  
       \- explicit capability/permission policy  
             |  
     Project Environment Manager  
       |\-\- L0 Embedded Android  \[current Crush / app\-private UDS\]  
       |\-\- L1 Termux tools     \[RUN\_COMMAND connector\]  
       |\-\- L2 Termux project   \[consistent project exchange/ownership\]  
       \+\-\- L3 Termux agent     \[separate, authenticated full\-engine backend\]  
             |  
     Model Provider Router  \-> Charm Hyper / other cloud / local inference  


An environment is not an AI provider\. The former determines where tools/files run; the latter determines where model inference runs\. A user may combine Termux L1 with a cloud provider, or L0 with an on\-device model\. Keeping these axes orthogonal avoids hidden privilege escalation and unnecessary special cases\.

# 5\. Canonical identity and persistence

__Entity__

__Must contain__

__Invariant__

Project

projectId, label, location type, canonical path, VCS metadata, environmentLevel

Survives engine restart; no ID derived from transient workspace

WorkspaceBinding

projectId, engine instance ID, Crush workspace ID, cwd, attachedAt, generation

Must match intended canonical path; discard stale bindings

Session

sessionId, projectId nullable, mode, transcript anchor, provider/model

One project owns many sessions; Chat can have no project

Run

runId, sessionId, workspace generation, status, capability grant, cancel token

One immutable identity from send to terminal state

EnvironmentProfile

provider/backend, capabilities, setup state, permissions, tool versions

Capability flags not inferred from integration level alone

FileSnapshot

path, hash, revision, base content ref, provenance, sync state

No silent overwrite or diff against wrong revision

filesDir/  
  conversations/                 \# Chat\-specific state \(metadata, not secrets\)  
  projects/<project\-uuid>/  
      repo/                      \# canonical POSIX project directory  
      \.crush\-mobile/              \# app\-owned manifests; exclude from commits  
          project\.json  
          snapshots/  
          sync\-journal/  
  sockets/crush\.sock              \# app\-private engine transport  


Persist metadata using Room/SQLite with versioned migrations\. Preserve existing session history and provider credentials via one\-time migration\. Do not move or rename the current workspace until migration has backup, rollback and on\-device upgrade tests\.

# 6\. Chat experience specification

Chat must remain usable with no project and no Termux\. Its tools policy is enforced at the agent or service boundary, not just by hiding buttons in Compose\. Default no shell, file write, package installation or repository modification; choose allowed safe read\-only tools deliberately\. The user must explicitly promote content into Code\.

- A Chat tab with conversation list, model selection, cost/availability information, attachments according to provider capability, and regenerate/stop behavior\.
- Chat mode remains project\-agnostic; do not bind it to whichever Code project was most recently opened\.
- A visible “Use this plan in Code” action creates a proposed context handoff with user confirmation; it does not silently grant folder access\.
- Follow existing lock and provider credential rules; do not decrypt/start privileged engine activity merely because a locked screen remains composed\.

# 7\. Code experience specification

Code starts at a Projects screen, not at an empty chat\. Each project has recent sessions, branch/status, selected environment, provider/model, and latest changes\. Once entered, separate three workflows: Explore \(read\), Build \(write/run after approval\) and Review \(diff/verify\)\.

__Screen__

__Core information__

__Primary action__

Projects

Imported/local projects, repo identity, environment badge, last activity

New / import / clone / open

Project home

Current branch, capability status, active runs, change count

Explore / Build

Code chat

Agent conversation, tools, permissions, run status

Send / stop / approve

Changed files

Grouped diffs, rename/delete indicators, hunk navigation

Review / revert with safeguards

Tests and terminal

Exit code, stdout/stderr, duration, toolchain origin

Rerun / copy log

Project settings

Canonical path, sync policy, environment, model/provider

Change only at safe boundary

A “project” is the persistent real folder; Crush workspace is an attached runtime for that folder\. If Crush internally attaches by \-\-cwd or its workspace API, the adapter must set the cwd to the canonical repository directory and verify server\-reported identity before using it\. \[R1\]\[R11\]

# 8\. Project creation, import, Git and external folders

__1\. __Create a new project inside app\-managed storage with a generated stable UUID, empty source tree and optional Git init\.

__2\. __Import a source archive or user\-selected folder into app\-managed storage using streaming copies and a signed/hashed manifest where possible; filter symlinks, traversal and unsafe filenames\.

__3\. __Clone a Git repository with explicit URL, authentication method and destination\. Display source trust warning; never run repository\-provided hooks/config until user approval\.

__4\. __On import/clone completion, record fingerprint, project root and initial base revision, then attach a Crush workspace to that actual root\.

__5\. __Later offer a linked external folder using Android Storage Access Framework; retain URI permissions and implement two\-way conflict detection without assuming URI == Linux filesystem path\.

__Android rule  __ACTION\_OPEN\_DOCUMENT\_TREE gives document\-provider URI access, not arbitrary POSIX cwd access\. Android 11\+ also restricts selecting storage roots and specific sensitive directories\. Import into an app\-managed canonical directory for the first release\. \[R7\]

__Operation__

__v1 behavior__

__Later behavior__

Import from SAF

Copy into app project, show source and destination

Linked folder with conflict\-aware sync

Git clone

Managed project directory; source authentication explicit

SSH keys / agents / richer credential modes

Export

ZIP or Git push on user request

Selective snapshot or commit transport

Delete project

Separate remove\-from\-list vs delete\-local\-copy actions

Linked\-source revocation and stale grant repair

Project transfer to Termux

Not implicit

Explicit staged files or Termux\-owned project backend

# 9\. Chat\-to\-Code handoff: product differentiator

The handoff transforms a conversation into an approved, versioned context package: user goal, decisions, open questions, constraints, selected snippets and desired test criteria\. It creates a new Code session in a selected project with a provenance marker; it must not copy secrets or the full transcript automatically\.

handoffId, sourceChatId, targetProjectId, targetCodeSessionId  
summaryMarkdown, userApprovedAt, selectedReferences\[\]  
projectTreeFingerprintAtHandoff, expiryPolicy, sourceProvider  


- User reviews and edits the summary before dispatch; show which private Code project it will access\.
- Code agent first verifies the summary against repository reality and lists mismatches; do not blindly execute an out\-of\-date plan\.
- If the target project changes, require new confirmation; no dynamic target alias\.
- Provide “Back to Chat” with a sanitized implementation summary, not raw project secrets\.

# 10\. Termux integration levels: user\-facing contract

__Level__

__User promise__

__Engine location__

__Project\-file truth__

__Release policy__

L0 — Built\-in

No Termux needed; native embedded coding basics

Crush in app sandbox

App\-managed project repo

Default/stable

L1 — Tools

Selected one\-off tools in Termux with explicit consent

Crush remains embedded

App repo; staged input only as needed

First add\-on milestone

L2 — Project

A coherent project toolchain through Termux

Crush remains embedded initially

Explicit staging/sync contract OR Termux\-owned project bridge

Beta after conflict tests

L3 — Full agent

Crush and developer tools run inside Termux

Crush in Termux

Termux\-owned repo; remote file\+tool protocol

Experimental until secured

Levels represent capabilities, not trust or price\. A L3 project should not silently inherit unrestricted permission to install packages or read any Termux file\. “Termux detected” does not mean “Termux authorized\.”

# 11\. Termux Level 1: technical connector

Use Termux’s supported RUN\_COMMAND service, rather than executing Termux\-private binaries directly from the Android app\. Required user consent includes the declared com\.termux\.permission\.RUN\_COMMAND, a granted Android permission, package visibility, and allow\-external\-apps=true inside Termux\. Termux documents results via a PendingIntent \(version dependent\) and bounded output\. \[R8\]

TermuxConnector\.probe\(\) \-> NotInstalled | PermissionMissing | PolicyMissing | Ready  
TermuxConnector\.run\(request: ToolRequest\) \-> Started\(externalCommandId\)  
TermuxConnector\.observe\(externalCommandId\) \-> Result\(exitCode, stdout, stderr\)  
TermuxConnector\.cancel\(externalCommandId\) \-> CancelOutcome  


- Probe tool availability \(git, bash, python, node\) using trusted absolute paths or resolved executable identities; return path/version and permission status\.
- Run from allowlisted, structured commands in initial release\. Avoid converting arbitrary model text into an unrestricted shell string\.
- Use unique PendingIntent identities, bind callbacks to the exact runId and projectId, scrub secrets from command output, and limit payloads\.
- Treat command output truncation, lost callback and app process death as distinct results\. A timed\-out callback does NOT mean the process was killed\.
- Level 1 cannot promise “run tests against the current project” until file staging or a shared project strategy exists\.

# 12\. Termux Level 2: project\-aware execution

The core challenge is two Android UIDs and two private filesystems\. App\-managed Crush file tools cannot read Termux\-private source paths; Termux commands cannot automatically open the app\-private repo\. Simple shell integration therefore risks “edit A, test B” errors\.

__Strategy__

__Benefits__

__Risks / recommended use__

Staging to Termux

Quickly runs build commands on a transferred snapshot

Copy cost, stale results, output reconciliation; suitable controlled beta

Shared SAF provider

User\-consented access to some files

URI semantics, performance, no seamless POSIX CWD

Termux\-owned project \+ full bridge

Single authoritative repo in Termux

Requires authenticated file/diff operations; advances toward L3

Recommended L2 first slice: one\-way staged snapshot with immutable revision hash, approved command, captured output, and a strict “results are for revision X” label\. Only later introduce bidirectional diff\-based sync with three\-way conflict detection, atomic writes, crash journal and user resolution\. Never automatically sync generated secrets or ignored files\.

# 13\. Termux Level 3: full\-hosted engine \(experimental\)

L3 would host Crush and the repository together inside Termux, while Compose acts as the remote client\. This solves code/tool filesystem divergence but introduces the greatest authentication and lifecycle burden\. Do not inherit the old Corvid plan’s unauthenticated loopback example; that design contradicts the resolved sibling\-app security issue\. \[R1\]\[R5\]

- Verify a compatible Crush/Go binary in the actual Termux environment on target Android versions; do not assume that an Android release artifact and a Termux Linux package are interchangeable\.
- Define an authenticated cross\-app transport first\. Prefer Android permission\-checked Binder/service or another carefully authenticated bridge\. If TCP is used, require a random secret, peer authentication, scoped sessions, rotation and no unprotected privileged endpoints\.
- Introduce remote file APIs for listing/reading/writing/diffing, run APIs, permission prompts, cancellation and recovery\. A shell\-only API is insufficient\.
- Make Termux service setup, uninstall, stopped\-state, package update, key handling and dependency management explicit in user onboarding\.
- Keep L3 behind a feature flag and security review; never replace L0 until equivalent reliability is demonstrated on physical devices\.

# 14\. Capability system and permissions

Separate the environment from permissions\. Model capability discovery at runtime \(canRead, canWrite, canShell, hasGit, hasPython, supportsPTY, supportsCancellation, supportsNetwork, canInstallPackages, supportsSnapshots, canAccessProjectRoot\)\. Each command carries a declared capability requirement and project scope\.

data class CapabilityReport\(  
  val backend: EnvironmentBackend,  
  val rootId: String,  
  val availableTools: Map<String, VersionInfo>,  
  val supportsStreaming: Boolean,  
  val supportsCancellation: Boolean,  
  val filesystemModel: FileSystemModel,  
  val permittedActions: Set<ActionCapability>  
\)  


Approval policy matrix is separate: read\-only inspection may be allowed, file writes usually require explicit project\-scoped grant, network/package installation require extra approval, and destructive operations should never be silently enabled by changing Termux level\. The agent model may request an action; only the policy engine authorizes execution\.

# 15\. Provider strategy: Hyper, others and local inference

Charm Hyper is the natural recommended cloud onboarding option for a Crush\-centric project, but it should be marketed as a supported external account/service, not as guaranteed free AI\. Repository notes record an approved Hyper account without free credits, and a later real\-key test encountered HTTP 402 with model discovery working\. Availability and pricing must be determined from the current user account\. \[R6\]

__Provider type__

__Default UX__

__Guardrail__

Charm Hyper

Recommended cloud provider, user signs in or supplies own key

No embedded shared key; show credits/402 access distinction

OpenRouter / cloud others

User chooses own key and approved model

Model availability, billing and limits probed; no assumptions

OpenCode Zen / Go

Separate provider/access policies

Free\-tier restrictions not bypassed by masquerading as official client

Local llama\.cpp

Optional offline model after verified download

No automatic cloud fallback; size/performance shown

The upstream Crush v0\.99\.0 release uses Catwalk v3 provider API types \(completions/responses/messages\) and adds editor\-host integration features\. Preserve version\-aware provider adapters and schema migrations\. \[R4\]

# 16\. On\-device model path

llama\.cpp has documented Android/arm64 build and Android GUI examples, including GGUF metadata reading and hardware\-dependent kernels\. It provides an inference runtime, not a ready\-made coding\-agent guarantee\. Model quality must be judged on tool calling, edit reliability, prompt length and speed on actual devices\. \[R9\]

__1\. __Prototype local generation completely outside the agent first: model download to app\-owned storage, SHA\-256 verify, disk quota, abort/resume, delete and offline generation\.

__2\. __Build an app\-owned inference service with resource budgeting; do not run it as an unrestricted exposed localhost API\. Determine how Crush can reach it through a safe compatible provider bridge\.

__3\. __Run a hardware matrix: RAM headroom, prompt prefill, decode tokens/sec, thermal throttling, battery, tool\-call JSON validity, 4K/8K context\. Record results on an older API 29 phone and newer devices\.

__4\. __Ship model catalogue by capabilities, not speculative “recommended 7B for all” policy\. Let low\-memory users select cloud without failure or surprise downloads\.

__5\. __Prohibit silent fallback to paid cloud from offline mode\. Ask explicitly before sending code or prompts outside the phone\.

__Selection rule  __Local AI can be the default privacy preference, but it cannot be a guaranteed capable model on every supported device\. Present an honest first\-run device readiness check and let the user select cloud or local\.

# 17\. Security and privacy architecture

__Threat / failure__

__Severity__

__Required mitigation / acceptance__

Cross\-UID access to Crush privileged API

Critical

Maintain app\-private UDS; authenticated channel required for Termux\-hosted backend

Chat accidentally executing Code tools

High

Enforced mode\-specific authorization in engine adapter, not UI\-only

Malicious imported repo config/hooks

High

Untrusted\-repo policy, never auto\-execute config / Git hooks

SAF sync overwriting external changes

High

Revision compare, three\-way conflict, atomic staged writes

Termux command injection / escalation

Critical

Structured args, approvals, allowlisted commands at L1, audit identity

API key leaked via logs/exports/config

High

Keystore, redaction, no credential export, clear engine plaintext caveat

Model download tamper / license

High

Hash/signature when available, model license and provenance report

Run continues after Stop / lock

High

Server\-side cancellation and no further privileged actions, verify terminal state

Background OS kill corrupts workspace

High

Durable run journal, safe recovery, Android foreground\-service compliance

Project deletion or migration mistake

High

Backup/export, explicit confirmation, rollback and idempotent migration

External add\-ons must not be able to bypass the app lock by accessing a privileged backend with cached credentials\. A locked UI and a stopped unauthorized operation are separate security conditions\. Distinguish stopping a local collector from cancelling the engine’s real run\.

# 18\. Android lifecycle, performance and observability

- Treat runs as finite, journaled state machines\. Distinguish UI background, app process death, engine exit, environment disconnect and provider timeout\.
- Use an Android foreground service only for appropriate user\-visible ongoing work and obey service type, start and timeout requirements; do not promise indefinite background execution\. \[R10\]
- Budget thermal load separately for agent, Termux compiler and local inference; throttle local model under device pressure and surface capability downgrade\.
- Use bounded event routing and capped diagnostic logs, with privacy redaction and opt\-in export; preserve a run ID from client → engine → tool → result\.
- Provide “paused”, “waiting for approval”, “interrupted”, “unknown external process state” and “cancelled” as distinct user statuses\.

# 19\. Builder\-ready phased roadmap

Each phase is a mergeable, reversible vertical slice\. Exit tests must be saved as evidence \(CI links, test logs, screen recordings, real\-device assertions\), not replaced by a checkbox\. Avoid implementing L2/L3 until the matching earlier gates pass\.

## P0  |  Reconcile current tree and lock baseline

Outcome: Create an honest repo\-wide source of truth at latest commit\.

Depends on: None

__1\. __Clone repo at exact SHA, record tree hash; inventory current classes, tests, docs and build files\. Compare to public source/older ZIP only as historical deltas\.

__2\. __Correct README drift on license, first green CI, androidTest and engine version; clearly separate proved device behavior from planned features\.

__3\. __Document run/session/workspace lifecycles and all current single\-writer configuration paths; establish feature\-flag locations\.

__4\. __Run full JVM suite, lint, debug/release assembly; gather signed\-release prerequisites, then pin a baseline tag\.

### Proof required

- repo revision, test count and build commands are verifiable from a clean checkout\.
- No previously shipped chat/session/security capability regresses\.

Artifacts: BASELINE\-NOV\.md, evidence matrix, CI links, baseline git tag

## P1  |  Prove release baseline and multi\-file coding

Outcome: Protect current engineering investment before architectural expansion\.

Depends on: P0

__1\. __Reproduce a real two\-file code edit on arm64 phone; capture permission prompt, changes and test command\.

__2\. __Finish R8 signed\-release smoke test with real embedded engine; test app lock, UDS, streaming and diff\.

__3\. __Add missing device cases: permission never/always, cancel, diagnostics export, crash recovery and cross\-app socket deny\.

__4\. __Prove that CI can produce an engine\-included, release\-testable artifact through a controlled supply\-chain job, while still keeping secrets off CI\.

### Proof required

- A clean signed APK runs a real coding task on device\.
- Both edited files produce readable, correct diffs and verified results\.
- CI and test report identify what was actually executed versus merely assembled\.

Artifacts: signed internal\-test APK, video, CI artifact provenance, acceptance log

## P2  |  ProjectRegistry and safe migration

Outcome: Introduce persistent projects without losing existing conversations\.

Depends on: P1

__1\. __Add Room entities Project, WorkspaceBinding, SessionBinding, EnvironmentProfile and migration journal\.

__2\. __Create one canonical app\-private project root per new project; support create, list, rename, archive and explicit delete\.

__3\. __Map legacy workspace to one generated legacy project without changing existing Crush IDs or transcripts unexpectedly\.

__4\. __Test app upgrade, rollback using backups, duplicate projects and out\-of\-space handling\.

### Proof required

- Two projects remain independent after process kill and reboot\.
- Existing users retain chats, provider keys and previous files\.

Artifacts: versioned DB schema, migration test fixtures, rollback protocol

## P3  |  Workspace and run coordinator

Outcome: Route per\-project Crush workspaces safely and deterministically\.

Depends on: P2

__1\. __Create project\-to\-cwd mapping and verify the server workspace really binds that path\.

__2\. __Add immutable RunHandle identity \(project/session/workspace generation\) and session reconnection policy\.

__3\. __Gate provider switches, environment changes and project deletion until active run completes/cancels\.

__4\. __Add race tests for late SSE, lost reader, duplicated callbacks, stale workspace and engine restart\.

### Proof required

- Project A cannot receive Project B events, edits or permissions\.
- Stop cancels the server run before a new run can reuse its identity\.

Artifacts: coordinator adapter, state diagrams, concurrency tests

## P4  |  Crush Code v1: Explore–Build–Review

Outcome: Deliver the first project\-first coding experience\.

Depends on: P3

__1\. __Build Projects list, project home, file browser, per\-project sessions, read\-only Explore and gated Build\.

__2\. __Integrate existing tool cards and diffs into a project\-oriented Changed Files/Review screen\.

__3\. __Expose command/test results with exit codes, revision labels and output truncation warnings\.

__4\. __Provide Git status/branch surface and safe export, avoiding unsupported tool claims\.

### Proof required

- Phone user edits two files, reviews each hunk and exports/preserves the result\.
- Unsafe tool calls require project\-scoped approval\.

Artifacts: Code experience UX, instrumentation, multi\-file demo

## P5  |  Crush Chat mode with hard tool boundary

Outcome: Offer a simple conversational surface without repository rights\.

Depends on: P3; can develop alongside P4

__1\. __Implement mode\-specific tool/capability policy enforced below Compose\.

__2\. __Separate Chat sessions and project\-linked Code sessions in navigation and storage\.

__3\. __Keep model selection/provider keys shared while tool grants and project selection are separate\.

__4\. __Regression\-test Chat prompt injection requests to modify arbitrary files\.

### Proof required

- Chat cannot execute shell or write source under default mode\.
- Chat works when Termux is absent and no Code projects exist\.

Artifacts: Chat screen, policy layer, security tests

## P6  |  User\-approved Chat → Code handoff

Outcome: Make “discuss → build” the product\-defining workflow\.

Depends on: P4, P5

__1\. __Generate editable summary with source provenance and selected attachments\.

__2\. __User chooses destination project and approves exactly what context is copied\.

__3\. __Code agent verifies plan versus current repo before first write\.

__4\. __Offer sanitized Code → Chat completion note; block secret transcript copying\.

### Proof required

- No private project is touched until the user explicitly selects and confirms it\.
- Handoff remains traceable and resumable after app restart\.

Artifacts: handoff models, consent UX, end\-to\-end flow

## P7  |  Termux Level 1 connector

Outcome: Provide useful optional commands without assuming shared source files\.

Depends on: P3, P5

__1\. __Build setup wizard for Termux installed/version, RUN\_COMMAND permission, allow\-external\-apps property and package visibility\.

__2\. __Implement probe, structured run, one\-shot callback, output cap, per\-project approval and disconnect\.

__3\. __Ship safe capability probes \(pwd, git \-\-version, python \-\-version\) and explicit toolchain checks\.

__4\. __Test missing install, revoked permission, stopped Termux, timeout and lost PendingIntent results\.

### Proof required

- L0 works unchanged with no Termux installed\.
- L1 reports accurate stdout, stderr, exit status and error states on device\.

Artifacts: TermuxConnector, setup UI, test harness, compatibility table

## P8  |  Termux Level 2 staged project execution

Outcome: Make tools run against a known project revision without overwriting files\.

Depends on: P7, P4

__1\. __Choose and document canonical authority for a single project\. Start with immutable one\-way staging, not transparent sync\.

__2\. __Transfer approved source snapshot to Termux\-accessible staging with hashes and ignore rules\.

__3\. __Execute tests against the transferred revision; label outputs with source revision and age\.

__4\. __Add optional sync\-back only with conflict detection, journal, revert and explicit confirmation\.

### Proof required

- Edited Code files and tested Termux snapshot hash\-match\.
- External change and concurrent change never silently overwrite each other\.

Artifacts: snapshot manifest, sync journal, conflict UI, on\-device verification

## P9  |  Termux Level 3 feasibility gate

Outcome: Decide whether a full Termux\-hosted Crush agent is justified\.

Depends on: P8 or independent gated prototype

__1\. __Prove a compatible Crush binary and reliable toolchain on selected devices\.

__2\. __Build security prototype for authenticated cross\-app control and remote file APIs; no anonymous TCP\.

__3\. __Run permission asks, stream reconnect, cancel and file diffs with Termux\-owned source files\.

__4\. __Compare latency, reliability and maintenance costs with L0/L2 before choosing to ship\.

### Proof required

- Sibling apps cannot invoke privileged endpoints or retrieve provider credentials\.
- All Code run/file operations refer to the same Termux\-owned repository\.

Artifacts: experiment report, threat model, Go/No\-Go ADR

## P10  |  Provider onboarding and cost clarity

Outcome: Offer reliable multi\-provider selection without free\-tier promises\.

Depends on: P1; independent of Termux

__1\. __Make Hyper the recommended cloud option with usable key/device\-code path if supported, and honest funding/402 messaging\.

__2\. __Keep OpenRouter and others accessible; probe model eligibility and show input/output price provenance\.

__3\. __Add explicit per\-session provider selection, budget warnings and known\-unverified labels\.

__4\. __Guard provider switching while a run is active; never silently migrate the active run\.

### Proof required

- Real Hyper funded turn is validated before marketing it as working\.
- No unavailable model is chosen solely because its name contains “free”\.

Artifacts: provider verification matrix, account onboarding tests

## P11  |  Offline inference integration

Outcome: Add on\-device AI for capable hardware without compromising device stability\.

Depends on: P3, P10; can spike earlier

__1\. __Run llama\.cpp Android binding standalone; verify GGUF import, model size, hashing and streaming\.

__2\. __Bridge chosen local inference protocol to Crush, with app\-private access control\.

__3\. __Measure old and recent devices for RAM, battery, throughput, context and agent tool\-call quality\.

__4\. __Ship offline mode with clear resource limits and opt\-in cloud fallback\.

### Proof required

- Airplane\-mode coding turn works on a supported device with no cloud calls\.
- Low\-memory devices fail gracefully and offer cloud setup without silent upload\.

Artifacts: model manager, device benchmarks, provenance manifest

## P12  |  Public beta and upstream recognition

Outcome: Make the result installable, supportable and easy to evaluate\.

Depends on: P1, P4, P5; enhanced after later phases

__1\. __Publish signed, reproducible beta with one\-click install steps and privacy/security disclosure\.

__2\. __Add screenshots, short demo video, GitHub About description/topics, contribution guide and issue templates\.

__3\. __Create a compatibility page with real devices and capabilities, list unsupported Termux levels honestly\.

__4\. __Share a concise technical post with Charmbracelet highlighting upstreamable Android/Termux fixes, not demanding official adoption\.

### Proof required

- External tester can install and complete a documented task without developer assistance\.
- License notices are included in distribution and dependencies are auditable\.

Artifacts: release notes, signed APK/attestations, showcase, upstream proposal

# 20\. Dependency order and parallelism

P0 \-> P1 \-> P2 \-> P3 \-> P4 \-> P6  
                  |     \\\-> P5 \-\-/  
                  |       \\\-> P7 \-> P8 \-> P9 \(experimental\)  
                  \\\-> P10 \-\-\-\-\-\-\-\-> P11 \(local model\)  
P12 can begin outreach/docs once P1 \+ P4 \+ P5 are proved\.  


Do not let the AI builder implement the entire tree as one PR\. Each phase must ship a small vertical slice, with no migration from current Chat until P2/P3 gates pass\. P9 is conditional, not an obligation to build a full Termux client\.

# 21\. Cross\-mode release acceptance matrix

__ID__

__Scenario__

__Expected evidence__

MODE\-01

Chat no tools

Prompt injection asking for rm/git write is denied at engine policy

MODE\-02

Project read isolation

A cannot see B files or permissions

MODE\-03

Project restore

Two projects survive cold start and app update

MODE\-04

Workspace reattach

No accidental new session when workspace restarts

MODE\-05

Run cancellation

Stop prevents later tool execution and reports truthfully

MODE\-06

ASK approval

Allow\-once resumes; reject cannot write

MODE\-07

Multi\-file edit

Two code files modified; diffs show exact hunks

MODE\-08

Review/revert

Revert restores the right base revision

MODE\-09

Test execution

Exit code/log associated with exact revision

MODE\-10

SAF import

Tree URI imported safely; traversal/symlink rejected

MODE\-11

External conflict

Concurrent external edit triggers conflict, never overwrite

MODE\-12

Handoff privacy

Chat plan summary does not contain blocked secrets

MODE\-13

Termux absent

Level 0 remains functional

MODE\-14

Termux consent

Level 1 refuses without required grants

MODE\-15

Termux callback lost

Honest unknown status; no false success

MODE\-16

Termux project sync

Revision hash matches tested content

MODE\-17

Termux cross\-app attacker

L3 denies unknown app / unauthorized request

MODE\-18

Local offline

Airplane mode produces true local generation

MODE\-19

No silent cloud

User receives explicit choice on local model failure

MODE\-20

Hyper unfunded

402 shows funding explanation, not invalid API\-key warning

MODE\-21

App lock

Lock denies privileged background run actions

MODE\-22

Process death

Recovery displays interrupted/unknown state correctly

MODE\-23

R8 signed

Release build passes engine streaming/ASK/diff

MODE\-24

Third\-party terms

FSL notice and app license present in binary distribution

MODE\-25

CI fidelity

CI reports engine\-less compile gate vs engine\-included device gate

# 22\. Minimum engineering contracts for implementation

interface ProjectEnvironment \{  
  suspend fun probe\(\): CapabilityReport  
  suspend fun open\(project: ProjectRef\): ProjectHandle  
  suspend fun listFiles\(handle: ProjectHandle, path: RelPath\): List<FileMeta>  
  suspend fun read\(handle: ProjectHandle, path: RelPath\): ByteArray  
  suspend fun execute\(handle: ProjectHandle, request: ExecutionRequest\): RunRef  
  suspend fun cancel\(run: RunRef\): CancelOutcome  
  fun events\(run: RunRef\): Flow<ExecutionEvent>  
\}  
  
interface ProjectStore \{  
  suspend fun get\(projectId: ProjectId\): ProjectRecord  
  suspend fun create\(request: CreateProject\): ProjectRecord  
  suspend fun checkpoint\(projectId: ProjectId\): SnapshotRef  
  suspend fun resolveConflict\(op: SyncOperationId, decision: ConflictDecision\)  
\}  


These contracts are intentionally illustrative: adapt to real current Kotlin modules and Crush API rather than blindly pasting\. Filesystem operation results need explicit errors \(permission denied, revoked URI, stale revision, unsupported tool, cancelled, output truncated, engine disconnected\)\.

# 23\. First implementation sprint: exact AI\-builder instructions

START HERE\. Deliver a narrow baseline plus project proof, not a new home\-screen redesign\. Do not claim success from README statements alone\.

__1\. __Inspect current main at 0741e77 or a newer pinned commit; record the exact SHA, app ID, engine version, current classes, migrations and GitHub workflow state\.

__2\. __Run the existing JVM, lint and instrumented build gates\. Do not change Gradle/Kotlin versions or engine pin casually\.

__3\. __Create a feature branch and write an ADR for Project vs Workspace vs Session vs Run vs Environment identity, including migration plan\.

__4\. __Introduce one in\-memory ProjectRef prototype pointing to a temp app\-private project directory\. Start Crush on that cwd without changing existing Chat\.

__5\. __Demonstrate Project A and Project B attachments, each with separate marker files and sessions; prove no cross\-project contamination\.

__6\. __Build one integration test that edits two files in Project A, verifies diffs, and asserts Project B unchanged\.

__7\. __Only then introduce Room persistence and Code\-mode UI\. Include screenshot/video evidence and update capability docs with VERIFIED vs IMPLEMENTED vs EXPERIMENTAL\.

__Hard stop  __If the embedded engine cannot consistently attach workspaces to two distinct project directories without session bleed, do not build Chat/Code navigation, Termux levels or local inference on top\. Resolve identity and lifetime first\.

# 24\. Decision log and questions to resolve

__Decision__

__Recommended default__

__Who decides / when__

Product naming

Crush Mobile / Chat / Code pending upstream branding check

Owner before beta

First project source

Managed local folder / import / clone

Builder \+ owner in P2

Chat tool grants

No shell, write or package tools by default

Owner/security review in P5

Termux add\-on distribution

Built\-in optional connector first; separate APK only if needed

P7 evidence

Termux L2 authority

One\-way revision\-pinned snapshot first

P8 ADR

Termux L3 networking

No unauthenticated localhost; choose verifiable secure bridge

P9 security gate

Local model catalogue

Benchmark\-supported models only; user download

P11 device matrix

Cloud defaults

Hyper recommended but not guaranteed free; others selectable

P10 confirmed account behavior

Engine upgrade

Pin stable and upgrade only in dedicated branch

Compatibility gate

Official Charm adoption

Seek feedback/listing, no assumption of ownership

After credible public demo

# 25\. Official collaboration and public recognition

Build an upstream contribution story around reusable engineering value: Android private Unix\-socket operation, permission event delivery, child process lifecycle, environment capability description and optional Termux integration guidance\. Keep your independent APK clearly marked as unofficial until Charm explicitly agrees otherwise\.

Public project discoverability is currently weaker than the code: the GitHub About section has no description or topics and the repository shows no stars\. This is a presentation gap you can fix independently of upstream approval\. Lead outreach with a 90–120 second physical\-device coding demo, link an exact release, explain what works, and ask maintainers for API and compatibility feedback rather than immediate official adoption\. \[R1\]

# 26\. Research and evidence references

__\[R1\]  Crush Android live repository README, source tree and documented device results  
__https://github\.com/sadi21863\-bit/crush\-android\-client

__\[R2\]  GitHub Actions: latest public workflow run statuses  
__https://github\.com/sadi21863\-bit/crush\-android\-client/actions

__\[R3\]  Android CI workflow file, artifact caveat and gate commands  
__https://github\.com/sadi21863\-bit/crush\-android\-client/blob/main/\.github/workflows/android\-ci\.yml

__\[R4\]  Crush release v0\.99\.0: Catwalk v3, hooks, editor integration  
__https://github\.com/charmbracelet/crush/releases/tag/v0\.99\.0

__\[R5\]  Original Corvid\-Termux independent\-project plan and caveats  
__https://github\.com/sadi21863\-bit/crush\-android\-client/blob/main/docs/termux\-project\-plan\.md

__\[R6\]  Repository provider investigation, including Hyper account eligibility  
__https://github\.com/sadi21863\-bit/crush\-android\-client/blob/main/docs/providers\.md

__\[R7\]  Android Storage Access Framework folder grant and restrictions  
__https://developer\.android\.com/training/data\-storage/shared/documents\-files

__\[R8\]  Termux RUN\_COMMAND official integration, required permission and result limitations  
__https://github\.com/termux/termux\-app/wiki/RUN\_COMMAND\-Intent

__\[R9\]  llama\.cpp Android build and native inference guide  
__https://github\.com/ggml\-org/llama\.cpp/blob/master/docs/android\.md

__\[R10\]  Android foreground service use and background work constraints  
__https://developer\.android\.com/develop/background\-work/services/fgs

__\[R11\]  Crush project / capability analysis in client repository  
__https://github\.com/sadi21863\-bit/crush\-android\-client/blob/main/docs/project\-analysis\.md

__\[R12\]  Current capabilities matrix and source of device validation claims  
__https://github\.com/sadi21863\-bit/crush\-android\-client/blob/main/docs/capabilities\.md

__\[R13\]  Open user decisions and release\-signing/lint progression  
__https://github\.com/sadi21863\-bit/crush\-android\-client/blob/main/docs/decisions\-open\.md

__\[R14\]  App and third\-party licence obligations  
__https://github\.com/sadi21863\-bit/crush\-android\-client/blob/main/NOTICE\.md

__\[R15\]  UI spec and historic design rationale  
__https://github\.com/sadi21863\-bit/crush\-android\-client/blob/main/docs/ui\-spec\.md

