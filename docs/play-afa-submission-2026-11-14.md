# Play submission with All Files Access — draft 2026-11-14

**Status: prepared, NOT submitted. The owner deferred the Play release on
2026-11-14; this package is parked until a release date is set.** The AFA
decision and the two-flavor fallback remain recorded in
[decisions-open.md](decisions-open.md) §11. The app-side hardening is landed
in `af178f8` (rationale dialog, just-in-time request, volume-root refusal).

Decision of record: [decisions-open.md](decisions-open.md) §11 — attempt
`MANAGE_EXTERNAL_STORAGE` (AFA) on the first store submission, with the
two-flavor SAF-mirror fallback pre-scoped in
[storage-strategy-research-2026-11-14.md](storage-strategy-research-2026-11-14.md).
This note is the submission package: declaration answers, listing copy, demo
script, reviewer access, and the honest limits. Nothing here is submitted yet.

## 1. What is being submitted

- App: Crush Mobile 1.0.0 (1), `targetSdk 36`, `minSdk 29`.
- Permission: `MANAGE_EXTERNAL_STORAGE`, requested **only** when the user
  imports a project folder on Android 11+; never at launch.
- Category selected in the declaration: **Document management** (closest
  permitted use). Not "file management": the core purpose is not managing the
  device's files; it is editing one code project in place with an AI agent.

## 2. Core functionality statement (must match the listing)

> Crush Mobile is an on-device AI coding agent for a project folder the user
> chooses. The engine is a native process that edits, runs, and verifies code
> in that folder in place. Without direct filesystem access to the chosen
> project, the engine cannot run its tools, so the app is unusable for its
> core purpose.

This is the "without this core functionality, the app is broken" test in the
Play policy. The listing must lead with this, not with generic chat.

## 3. Declaration answers

### 3.1 Why AFA is directly tied to core functionality

- The engine is an embedded native AArch64 process. It needs a real POSIX
  working directory for `open`/`read`/`write`/`rename`/`unlink`, shell tools,
  and file watching.
- The user's project is an existing folder on shared storage. Editing it in
  place is the product: changes are immediately visible to the user's other
  tools (git, editors, file manager).
- The app does not browse, index, or scan other directories; the only bound
  workspace is the folder the user imports. This is an app-level boundary,
  not an OS sandbox (see §6).

### 3.2 Why SAF is not sufficient

- SAF grants the **app** access through `content://` URIs. The native engine
  has no `ContentResolver`; a tree URI is not a directory path and cannot be
  its cwd.
- A SAF mirror is not equivalent: external edits and engine edits diverge
  until a sync pass; there are no POSIX locks, no atomic rename, no symlinks,
  modes, or executable bits; `ContentObserver` is provider-driven, not
  inotify; every operation crosses provider IPC. The result is a sync engine
  with conflict and data-loss risk — substantially detrimental to the core
  workflow, not a cosmetic difference.
- One-off file import is not the use case: the user selects a folder once,
  and the engine keeps working in it.

### 3.3 Privacy impact mitigation

- **Just-in-time**: AFA is requested only from the import action; there is a
  rationale dialog before the system screen, and "Not now" leaves the app
  usable.
- **Project-scoped binding**: the importer refuses the volume root and
  non-primary providers; the app binds the engine only to the imported folder
  and never exposes a file browser or a device-wide search surface. The engine
  can still be instructed by the user to touch another path; this is an
  app-level boundary, not an OS sandbox.
- **No background collection**: no indexing, scanning, or uploading of
  arbitrary files on the app's own initiative.
- **Honest copy**: the rationale says Android grants the permission
  device-wide and that Crush binds the engine to the imported folder; it does
  not claim the OS enforces that scope.
- **Model context**: as part of an agent turn, files the engine reads can be
  sent to the user's configured model provider. The privacy policy and Data
  Safety form must disclose this; do not claim the app is fully offline.

### 3.4 What the declaration must not say

- Do not claim a technical sandbox that the OS does not enforce.
- Do not claim "no data leaves the device".
- Do not label the app a file manager.
- Do not describe AFA as needed for "selecting files"; the invalid-use list
  names individual-file selection, and our story is ongoing project editing.

## 4. Store listing copy (draft)

**Short description (<= 80 chars):**

> AI coding agent that edits your project folder in place on your phone.

**Full description (opening paragraphs):**

> Crush Mobile is an AI coding agent that works in a real project folder on
> your phone. Import a folder, and the engine edits files in place, runs
> commands, and verifies changes — the same files your file manager and git
> see.
>
> Crush needs All files access for one reason: Android cannot give a native
> coding engine a scoped folder. The permission is requested only when you
> import a project, and Crush uses it only for the folder you chose. It does
> not browse, index, or scan your other files.

Then the feature list (chat, tool approvals, project switching, diagnostics).
Keep the in-place editing claim in the first screen of the listing.

## 5. Demo video script (90–120 s)

1. Fresh install → onboarding → provider key (cut the key itself).
2. Projects → **Import folder**.
3. Show the rationale dialog; tap **Continue**.
4. System **All files access** screen → toggle Crush on → back.
5. SAF picker → choose `DemoProject`.
6. Chat: "Add a line to README.md, then run `git diff`."
7. Approve the bash request (ASK mode) so the tool path is visible.
8. Show the tool row completing, then open the system Files app and show the
   changed `README.md` in the same folder.
9. End card: "Edits the folder you choose, in place."

The video must show the system permission screen and the same folder being
changed; a chat-only demo does not evidence the core functionality.

## 6. Honest limits

- AFA is device-wide at the OS level. The import/binding boundary is app
  logic; a determined engine command can still reach other paths. The
  declaration must not imply otherwise.
- Approval is discretionary. The permitted-use table does not name developer
  tools or AI coding agents; the exception requires "no alternative" or
  "substantially detrimental" and is reviewed case by case.
- A rejection is not a code failure. The fallback is the two-flavor split:
  Play = narrow SAF + mirror, sideload = AFA.

## 7. Reviewer access (owner action)

The app is unusable without a model-provider key, so reviewers cannot reach
the core flow with a stock install. One of these is required before
submission:

- a dedicated, tightly-quota'd review key supplied through Play Console
  review instructions (never committed, rotated after review); or
- a review build with a deterministic local demo provider that still
  exercises import → tool call → in-place edit.

Decide which; do not ship a key in the APK.

## 8. Pre-submission checklist

- [x] Rationale dialog + JIT request shipped (`ProjectsScreen`, `af178f8`);
      compile/unit-level only on the API 29 device.
- [x] Volume root refused at import
      (`FolderUriDecoder.primaryProjectPathFromTreeDocumentId`, `af178f8`;
      unit-pinned).
- [ ] Merged manifest: AFA present; READ/WRITE capped at 32/29; no other
      broad storage permissions.
- [ ] Listing leads with in-place project editing.
- [ ] Declaration category: Document management; answers from §3.
- [ ] Demo video from §5.
- [ ] Reviewer key/demo mode from §7.
- [ ] Privacy policy + Data Safety form disclose model-provider transmission.
- [ ] Internal-track submission triggers the declaration; do not change the
      permission use without resubmitting the form.

## 9. Sources

- Play policy — Use of All files access: https://support.google.com/googleplay/android-developer/answer/10467955 (accessed 2026-11-14).
- Android — Manage all files: https://developer.android.com/training/data-storage/manage-all-files (accessed 2026-11-14).
- Android — Access documents and other files (SAF): https://developer.android.com/training/data-storage/shared/documents-files (accessed 2026-11-14).
- Full option analysis: [storage-strategy-research-2026-11-14.md](storage-strategy-research-2026-11-14.md).
