# Storage strategy for imported project folders — 2026-11-14

Research note for Crush Mobile Android. Question: which storage strategy should imported project folders use now that a Play release is the blocker, while sideloading remains possible? No code or Gradle files were changed.
Device context: Android 10 / API 29 (`bbf8ca69`), app 1.0.0 (1), engine v0.100.0. Build: `minSdk 29`, `targetSdk 36`, `compileSdk 37` (`app/build.gradle.kts:44,58-59`). Sources read 2026-11-14; citations `[S1]`–`[S14]` are in §7.

## 0. Bottom line

- **Recommendation: option D, a two-flavor split.** Play flavor = B-MVP (narrow SAF grant + app-private mirror + explicit save/reload); sideload/dev flavor = A (All Files Access, in-place POSIX editing).
- **B-full is not functionally equivalent to A.** It is a sync engine that approximates a POSIX directory after the fact; it cannot give the native engine the source folder's namespace, locking, atomic rename, symlinks, Unix modes, executable bits, or inotify semantics.
- **A alone does not reliably unblock the store.** Play has no "developer tool" category, the declaration requires a demo video and extended review, and rejection blocks publication. The exception is discretionary and weak when a SAF-based alternative exists.
- **B-MVP is a bounded feature, not a product rewrite.** The engine already takes a POSIX cwd (`ProjectRef.kt:30-34`); pointing it at `filesDir/projects/<uuid>/repo` needs no engine change. The work is import/copy, grant persistence, baseline tracking, conflict reporting, and honest UI.
- **Do not build B-full on the critical path.** Revisit only if users show a concrete need for continuous two-way sync; treat it as a separate product bet.

## 1. What SAF provides, and what it does not

### 1.1 The grant model

`ACTION_OPEN_DOCUMENT_TREE` lets the user select a directory and grants read/write access to that directory and its descendants through a `content://` tree URI. No storage runtime permission is required, and the files remain after the app is uninstalled [S3].
The initial grant is temporary; `ContentResolver.takePersistableUriPermission()` keeps it across device reboots. Persistable grants require `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`, and access is lost if the document is moved or deleted [S3][S7]. On Android 11+, the picker cannot select the root of internal storage, the root of a "reliable" SD card, or `Download`, and cannot select `Android/data` or `Android/obb` [S3] — so even the picker is already narrower than "any folder".

### 1.2 Why a `content://` tree is not a cwd

The engine is a separate native AArch64 process. It needs a real directory path for its working directory, ordinary `open`/`read`/`write`/`rename`/`unlink` syscalls, shell commands, and path-based file watching. A `content://` URI is resolved by the app process through `ContentResolver`; the native child has no `ContentResolver` and no supported way to turn a document tree into a directory. SAF can hand the app a stream or file descriptor for one document, but not a directory tree with POSIX semantics [S3][S7].
This matches ADR-0001 §6(c): a document-tree URI is not a POSIX path and cannot be the engine's resolve target (`docs/adr-0001-project-workspace-identity.md`). `DocumentFile` is only a convenience wrapper over `DocumentsContract`; its own docs say it has "substantial overhead" and that documents "don't have a strong notion of path," with opaque document IDs that "must not be parsed" [S14]. The current import flow works only because it decodes the `primary:` document ID back to `/storage/emulated/0/...` (`FolderUriDecoder.kt:37-46`) and then uses All Files Access, not the SAF grant, for the engine's I/O.

### 1.3 Capability comparison

| Capability | A: direct POSIX path (AFA) | SAF tree grant |
|---|---|---|
| Directory usable as cwd | Yes | No; URI only |
| Read/write | Kernel syscalls on a real path | `ContentResolver` streams / per-document FDs |
| Directory enumeration | `readdir` | `DocumentsContract` child query per directory |
| Rename | `rename(2)`; atomic on one filesystem | `renameDocument`; may return a new URI and invalidate the old one; no atomicity promise [S8] |
| Delete | `unlink`/`rmdir` | `deleteDocument`; provider must support `FLAG_SUPPORTS_DELETE` [S3][S8] |
| Advisory locks | `flock`/`fcntl` (filesystem-dependent) | No SAF lock API; only provider read/write |
| Symlinks / hardlinks | Yes | Document model has no symlink/hardlink API |
| Unix mode / exec bit | Yes, subject to the underlying filesystem | Document model exposes no Unix mode; cannot round-trip it |
| Execute a file | Yes, if the filesystem/OS permits | No path to execute; `content://` is not executable |
| Change observation | `inotify` / path-based `FileObserver` [S9] | `ContentObserver` on a URI; provider-driven and coarse [S10] |
| Random access / `mmap` | Yes | Stream/FD for one document; no directory mapping |
| Performance | Kernel/FUSE path | Provider IPC per operation; docs warn about large-tree iteration [S3] |
| Offline / cloud source | Local path only | Provider may be remote, offline, or slow |

Two consequences: (1) the engine cannot consume the SAF tree in place; it can only consume a mirror the app materialises as a real directory; (2) the mirror is a copy, not the source — its path is `filesDir/projects/<uuid>/repo`, while the user's folder remains where they picked it.

### 1.4 Change observation, deletion, rename, conflicts

`FileObserver` is inotify-based and watches files/directories by path [S9]. That works on the mirror; it cannot watch a `content://` tree.
`ContentObserver` receives callbacks when a content URI changes; the provider decides when to call `notifyChange`, and `notifyForDescendants` only broadens URI scope [S10]. It is not an inotify stream: no per-syscall event, no rename pair, no close-after-write event, and no guarantee of timely or fine-grained notification. A live sync engine must poll or periodically rescan.
`renameDocument` may return a new document URI and invalidate the original [S8], so any baseline keyed by URI must be repaired. `deleteDocument` requires provider support; the API also has a separate `trashDocument`, so deletion semantics vary by provider, and some providers are read-only [S3][S8]. A persistable grant can disappear when the document is moved or deleted [S3]; the app must detect this and re-prompt, not silently fail. There is no compare-and-swap or version token in the SAF contract; conflict detection is heuristic (size, last-modified, and content hash against a baseline).

### 1.5 Is C functionally equivalent to A?

**No.** C can eventually make the mirror contain the same bytes, but it is not equivalent:

- **Namespace:** the engine sees the mirror path, not the user's path. Scripts that hardcode `/storage/emulated/0/...`, use `realpath`, or compare inode identity see different values.
- **Timing:** an external editor and the engine see different copies until a sync pass lands; A's edits are visible immediately.
- **Locking:** two writers can hold different copies; SAF provides no shared advisory lock. A gives normal kernel locking and last-write semantics.
- **Atomicity:** SAF has no atomic rename guarantee. A failed export can leave a partial document; a failed import can leave a partial mirror file.
- **Metadata:** Unix modes, executable bits, symlinks, hardlinks, and some timestamps are not representable in the SAF document model. A preserves what the filesystem preserves.
- **Execution:** the engine cannot execute a file through a `content://` URI.
- **Observation:** no inotify on the source tree; only coarse provider notifications or polling.
- **Performance:** every source operation crosses the provider boundary; a full-tree scan/copy is O(files) document reads plus O(directories) queries, and the docs warn about large-tree iteration [S3].

A live bidirectional sync would therefore be a new product with a new failure surface, not a permission workaround. It needs a journal, baseline manifest, rename detection, deletion tombstones, conflict resolution, retry/backoff, provider capability detection, and battery-aware scheduling — and still would not give the engine the source directory.

## 2. Google Play policy for All Files Access

### 2.1 Scope

The policy applies to apps targeting Android 11 (API 30) or higher that declare `MANAGE_EXTERNAL_STORAGE`; it does not affect `READ_EXTERNAL_STORAGE` [S2]. The app targets 36 and declares the permission (`app/src/main/AndroidManifest.xml:26-28`), so it applies. AFA is broad but not literally every file: it cannot access other apps' app-specific directories under `Android/data` [S1].

### 2.2 Eligible categories

| Permitted use | Fit for Crush Mobile |
|---|---|
| File management (core purpose is accessing/editing/managing files outside app-specific storage) | Weak: core purpose is an AI coding agent, not file management |
| Backup and restore | No |
| Anti-virus | No |
| Document management (locate/access/edit compatible file types; must justify why SAF is insufficient) | Arguable but weak: code files are documents, and SAF is a viable alternative |
| On-device file search | No |
| Disk/folder encryption and locking | No |
| Device-to-device migration | No |
| Developer tool / terminal / AI coding agent | **Not listed** |

"Developer tool", "terminal", and "AI coding agent" do not appear in the current list [S2]. Absence is not an explicit prohibition — the exception exists — but there is no category the app can select and expect to pass.

### 2.3 The exception is discretionary and weak here

Play may grant a temporary exception when the permission enables core functionality, there is no alternative, or privacy-friendly alternatives have a substantially detrimental impact, and the privacy impact is mitigated [S2]. The developer must justify why SAF or MediaStore is insufficient. Crush Mobile can argue that in-place editing of arbitrary project folders is its core workflow, but a B-MVP Play build would be a viable SAF-based alternative for the store build, weakening the "no alternative" branch. This is a policy judgement, not a technical entitlement; no outcome can be promised from the public text.

### 2.4 Declaration, video, and review

If AFA stays in a Play artifact, the developer must complete the Permissions Declaration Form: select core functionality, provide review instructions, provide a video demonstration (YouTube preferred, or a cloud MP4), and provide test credentials if functionality is behind sign-in [S11]. The form is required for active bundles on Open, Closed, **and Internal** test tracks; while an alert is outstanding, the app cannot publish changes, including store-listing changes [S11]. Review is extended and may take several weeks [S11].
The core functionality must be prominently documented and promoted in the app description [S2]. Changing how the app uses the permission requires a new declaration; deceptive or undeclared use can suspend the app and/or terminate the developer account [S2]. Apps that fail policy requirements or do not submit the form may be removed from Play [S2].

### 2.5 Realistic risk

- **First review:** material rejection risk. No listed category fits; the exception requires a strong "SAF is insufficient" story, and B-MVP is a credible alternative.
- **If approved:** ongoing compliance risk. Approval is tied to the declared core functionality; expanding the file-management surface can trigger re-review.
- **If rejected:** the Play release is blocked, but the sideload build still works. The platform permission is available to any app whose user grants it through Settings [S1]; the Play policy is a distribution rule, not an OS restriction.

## 3. Scoped storage and targetSdk 36

- Apps targeting Android 10 (API 29) or higher get scoped storage by default: access to their app-specific directory plus specific media they created [S4].
- For apps targeting Android 11 (API 30) or higher, `WRITE_EXTERNAL_STORAGE` has no effect on shared-storage access [S4][S6].
- `requestLegacyExternalStorage` is ignored on Android 11+ once the app targets Android 11+ [S5][S6]. The app still sets it (`AndroidManifest.xml:38`), but at `targetSdk 36` it is not a store-viable path on modern devices.
- On Android 11+ with target 30+, `READ_EXTERNAL_STORAGE` gives read-only "all files" access through Files & Media; reading **and writing** all shared files requires All Files Access [S6].
- On Android 13+ with target 33+, `READ_EXTERNAL_STORAGE` is replaced by granular media permissions (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`) for other apps' media [S13]. The manifest caps `READ_EXTERNAL_STORAGE` at API 32 (`AndroidManifest.xml:23-25`), so on API 33+ it is not even requested.
- Therefore at `targetSdk 36` there is no manifest flag or runtime permission combination that gives an arbitrary native process a writable POSIX path to an arbitrary existing project tree outside app-specific storage. The only supported choices are AFA (policy-restricted) or SAF plus an app-owned mirror with a real path.
- **API 29 nuance.** `MANAGE_EXTERNAL_STORAGE` was introduced in Android 11 (API 30) [S6]. On the API 29 test device the current flow uses `READ_EXTERNAL_STORAGE` + `WRITE_EXTERNAL_STORAGE` plus `requestLegacyExternalStorage` (`AndroidManifest.xml:20-25,38`; `ProjectsScreen.kt:117-172`). That works today, but the public docs only describe the legacy opt-out for apps targeting Android 10 or lower; they do not promise it for a `targetSdk 36` app on Android 10. It is a beta convenience, not a store foundation. The Play flavor should use the SAF mirror on API 29 too.

## 4. Options compared

| Criterion | A: AFA all builds | B-MVP: SAF + mirror + manual sync | B-full: live bidirectional | D: two-flavor split |
|---|---|---|---|---|
| Play eligibility | Low; declaration + discretionary exception; no listed category | High; no AFA | High; no AFA | High for Play; AFA only in sideload artifact |
| Engine compatibility | Exact in-place POSIX | Exact POSIX on mirror; no engine change | POSIX on mirror, but stale/conflicting | Exact in both modes |
| Data safety / conflicts | Best: one copy, no divergence | Medium: divergence until save; conflicts explicit | High: concurrent writers, heuristic conflicts, possible silent loss | Medium: B-MVP on Play, A on sideload |
| Implementation effort | S (already works) | M (copy + baseline + conflict UI + grant states) | L/XL (sync engine) | M + flavor plumbing |
| UX honesty | Honest but permission-scary | Honest if labelled "imported copy" | Hard to keep honest; "live sync" overpromises | Honest per mode |
| Maintenance burden | Low code, high policy risk | Medium | High | Medium-high: two access paths, one identity model |
| API 29 device | Works via legacy today | Works | Works | Works |
| Store risk | High | Low | Low policy risk, high product risk | Low |

**A — AFA all builds.** Pros: the engine gets the real folder; no copy, no divergence, no sync engine; current code and device behaviour already work; fastest path for sideload users. Cons: store eligibility depends on a discretionary exception; the declaration applies to internal/closed testing too; rejection blocks release; the permission is far broader than the selected project; the app must keep justifying its core functionality. **Verdict:** acceptable for a sideload-only product; not acceptable as the only store strategy while the store is the blocker.

**B-MVP — narrow SAF grant + mirror + explicit save/reload.** Pros: Play-eligible with no AFA; least-privilege (SAF needs no storage permission [S3]); engine compatibility is exact on the mirror; the path already fits the ADR's `filesDir/projects/<uuid>/repo` shape; import works with any readable provider, not just `primary:`; the source folder is never silently modified. Cons: the folder and the engine's copy diverge; the user must save/reload; uninstall/clear-data deletes the mirror [S12]; large repos consume internal storage; SAF fidelity is provider-dependent (dotfiles, symlinks, modes, cloud latency); conflict reporting is real work. **Effort:** M. **Verdict:** the right store MVP.

**B-full — live bidirectional sync.** Pros: closest to A's convenience; no manual save step. Cons: it is a sync engine, not a permission change. It needs source polling/observation, mirror watching, a journal, rename/deletion detection, conflict resolution, retry/backoff, provider capability detection, battery-aware scheduling, and conflict UI. It still cannot provide POSIX locks, atomic rename, symlinks, modes, or exec on the source; concurrent edits can diverge or lose data. **Effort:** L/XL; ongoing maintenance high. **Verdict:** do not build now; it does not remove the fundamental difference from A, it hides it until the first conflict.

**D — two-flavor split (recommended).** Play flavor: no AFA, no broad storage permissions, B-MVP mirror. Sideload/dev flavor: AFA (and legacy READ/WRITE on API 29) with direct in-place editing, exactly today's workflow. Pros: unblocks the store without betting on an exception; preserves the power-user workflow; one engine and one project identity model; least-privilege Play artifact. Cons: two access paths to maintain and test; applicationId/signing decisions; users cannot move a project between flavors without export/import. **Verdict:** best fit for the current constraints.

## 5. Recommendation and phased plan

**Adopt D.** Ship Play with B-MVP, keep AFA in a sideload/dev flavor, and defer B-full until there is evidence manual save/reload is insufficient. This is the only option that both unblocks the store and keeps in-place editing for users who sideload.

### 5.1 Phases

| Phase | Work | Effort | Exit criterion |
|---|---|---|---|
| 0. Decide and freeze | Owner approves D; no new AFA-dependent UX; add a storage-mode seam to the project model | S | Decision recorded in `decisions-open.md` §11 |
| 1. Play import (B-MVP-1) | Flavor split; Play manifest removes AFA/READ/WRITE/legacy; SAF tree picker + persistable grant; copy to `filesDir/projects/<uuid>/repo`; progress, cancel, free-space check; project row records source URI and mode; engine binds the mirror | M | Play artifact imports a folder and the engine edits the mirror; source untouched |
| 2. Manual save/reload (B-MVP-2) | Baseline manifest; "Save changes to folder"; "Reload from folder"; diff preview; conflict report; explicit deletion handling; grant/lost-folder states | M | Round trip saves and reloads; a both-changed file is reported, never silently overwritten |
| 3. Sideload AFA | Sideload flavor keeps AFA; API 29 legacy path; direct mode; access-state gate at project open | S/M | Existing device workflow still works; missing AFA gives an honest state |
| 4. Optional B-full | Only if demand is proven; separate design note and sync-engine scope | XL | Not on the critical path |
| 5. Store submission | No AFA declaration; data-safety form; internal track; merged-manifest check; API 29 + 36 device pass | S | Play artifact contains no `MANAGE_EXTERNAL_STORAGE`; review passes |

### 5.2 Flavor split, high level

```kotlin
// app/build.gradle.kts (sketch only; not applied by this note)
flavorDimensions += "distribution"
productFlavors {
    create("play")     { dimension = "distribution" }
    create("sideload") { dimension = "distribution"; applicationIdSuffix = ".sideload" }
}
buildFeatures { buildConfig = true }
```

- `src/play/AndroidManifest.xml`: remove `MANAGE_EXTERNAL_STORAGE`, `READ_EXTERNAL_STORAGE`, and `WRITE_EXTERNAL_STORAGE` with `tools:node="remove"`; set `android:requestLegacyExternalStorage="false"` with `tools:replace`. Keep only `INTERNET` and `ACCESS_NETWORK_STATE`.
- `src/sideload/AndroidManifest.xml`: keep the current storage permissions and legacy flag.
- Runtime: a `StorageMode` enum (`DIRECT`, `MIRROR`) selected by `BuildConfig`, or a `StorageAccess` interface with `DirectAccess` and `MirrorAccess` implementations. Do not let Play code call `Environment.isExternalStorageManager()` or the AFA settings intent.
- `ProjectEntity` gains `storageMode`, `sourceTreeUri`, `sourceGrantState`, `lastSyncAtMs` (Room migration v3→v4; the database is currently version 3). `ProjectRef.workspacePath` stays the engine cwd; the engine does not need to know the mode.
- With flavors, the existing `r8probe` build type needs `matchingFallbacks` for the new dimension; verify variant names before building.
- Verify the merged manifest, not the source file: `aapt dump permissions` or `bundletool dump manifest` on the Play artifact must show no `MANAGE_EXTERNAL_STORAGE`.

**Application identity:** a `.sideload` suffix allows Play and sideload builds to coexist but creates a separate app identity (separate Room DB, mirror, project rows). The same applicationId avoids that split but may collide with Play App Signing's re-signed artifact. This is an owner decision, not a build detail to guess.

### 5.3 Minimum honest UX for B-MVP

- **Import:** "Import a copy. Crush works on a private copy. Your folder is not changed until you tap **Save changes to folder**." Show source name, app-copy size, and free space; refuse read-only/insufficient-space imports with an explanation. This surface owns the F08 access states: Ready / NeedsPermission / Missing / UnsupportedProvider, rechecked at project open.
- **Project row:** a persistent badge: "Imported copy" plus "Last saved: <time>" or "Not yet saved". Never present the mirror as the user's folder.
- **While editing:** a non-blocking banner when the mirror has changes not yet saved. Do not claim the folder is up to date.
- **Save changes to folder:** dry-run preview (added/modified/deleted counts), then write. If the source changed since the baseline, stop and show conflicts. Never silently overwrite.
- **Reload from folder:** explicit confirmation that the app copy will be replaced; show which files differ. Never auto-run on start.
- **Conflicts:** per file: "Keep app version", "Keep folder version", "Skip". Record the choice; do not merge text.
- **Deletions:** never propagate automatically in MVP; ask explicitly and show what will be removed on each side.
- **Grant lost / folder missing:** "Access to <folder> was revoked. Your work is safe in the app copy. Re-select the folder to save changes." / "The original folder is missing. Export the app copy to a new folder."
- **Uninstall warning:** "Removing the app deletes the private copy. Save changes to your folder first." The app sets `allowBackup="false"` (`AndroidManifest.xml:32`), so there is no OS backup safety net [S12].
- **Wording:** call it "Save to folder" / "Reload from folder", not "Sync", until bidirectional conflict handling ships. "Sync" implies a guarantee the MVP does not make.

## 6. Claims not verified from a primary source

- **Google's actual decision for this app.** No Play submission was made; rejection/approval risk is an assessment of public policy text, not an observed outcome.
- **Whether an "AI coding agent" can qualify under the exception or "document management".** Play has discretion; the current page does not name the category.
- **`requestLegacyExternalStorage` at target 36 on Android 10.** Docs describe the opt-out for apps targeting 29 or lower and say it is ignored on Android 11+; they do not explicitly specify a target-36 app on Android 10. The API 29 device works today, but that is device evidence, not a documented guarantee.
- **Real SAF provider performance on the target device.** No benchmark was run; the docs' large-tree caution is general [S3].
- **ContentObserver reliability for the primary ExternalStorageProvider.** The contract is provider-driven; no device test measured latency or completeness.
- **Atomicity of `renameDocument` for specific providers.** The API promises none; provider implementations were not inspected.
- **Persisted-grant quota.** Public `ContentResolver` docs describe persistence but state no per-app limit; no AOSP source was audited for one.
- **SAF fidelity for dotfiles, symlinks, modes, and large files.** The document model has no mode/symlink API; provider behaviour was not tested. Treat fidelity as provider-dependent.
- **Whether project scripts/binaries can execute from the mirror on API 29 and 36.** Not tested. The current engine's main path is shell/interpreter commands, not project executables; verify before promising a build-tool workflow.
- **Long-term Play policy stability.** The AFA text can change; access date is 2026-11-14.

## 7. Sources

All accessed 2026-11-14.

- **[S1]** Android Developers — *Manage all files on a storage device* (AFA, permitted operations, Play notice): https://developer.android.com/training/data-storage/manage-all-files
- **[S2]** Google Play Console Help — *Use of All files access (MANAGE_EXTERNAL_STORAGE) permission*: https://support.google.com/googleplay/android-developer/answer/10467955
- **[S3]** Android Developers — *Access documents and other files from shared storage* (SAF, tree grants, persistable permissions, provider operations, large-tree caution): https://developer.android.com/training/data-storage/shared/documents-files
- **[S4]** Android Developers — *Data and file storage overview* (scoped storage, `WRITE_EXTERNAL_STORAGE` at target 30+): https://developer.android.com/training/data-storage
- **[S5]** Android Developers — *Storage use cases and best practices* (legacy opt-out, `requestLegacyExternalStorage`): https://developer.android.com/training/data-storage/use-cases
- **[S6]** Android Developers — *Android 11 storage updates* (scoped storage enforcement, legacy flag ignored, READ vs AFA, `MANAGE_EXTERNAL_STORAGE`): https://developer.android.com/about/versions/11/privacy/storage
- **[S7]** Android Developers — `ContentResolver` (`takePersistableUriPermission`, `getPersistedUriPermissions`): https://developer.android.com/reference/android/content/ContentResolver
- **[S8]** Android Developers — `DocumentsContract` (`createDocument`, `renameDocument`, `deleteDocument`, tree URIs): https://developer.android.com/reference/android/provider/DocumentsContract
- **[S9]** Android Developers — `FileObserver` (inotify-based path watching): https://developer.android.com/reference/android/os/FileObserver
- **[S10]** Android Developers — `ContentObserver` and `registerContentObserver` (URI-level, provider-driven notifications): https://developer.android.com/reference/android/database/ContentObserver
- **[S11]** Google Play Console Help — *Declare permissions for your app* (declaration form, video, review, test tracks): https://support.google.com/googleplay/android-developer/answer/9214102
- **[S12]** Android Developers — *Access app-specific files* (`filesDir`, deletion on uninstall): https://developer.android.com/training/data-storage/app-specific
- **[S13]** Android Developers — *Android 13 behavior changes* (granular media permissions replace `READ_EXTERNAL_STORAGE`): https://developer.android.com/about/versions/13/behavior-changes-13
- **[S14]** Android Developers — `DocumentFile` (convenience wrapper, substantial overhead, documents have no strong path notion, opaque IDs): https://developer.android.com/reference/androidx/documentfile/provider/DocumentFile

Repo evidence: `app/src/main/AndroidManifest.xml:20-28,32,38`; `app/src/main/java/com/crush/mobile/ui/screens/projects/ProjectsScreen.kt:94-172`; `app/src/main/java/com/crush/mobile/project/FolderUriDecoder.kt:37-46`; `app/src/main/java/com/crush/mobile/project/ProjectRef.kt:30-34`; `app/src/main/java/com/crush/mobile/data/project/ProjectEntity.kt:31-42`; `app/src/main/java/com/crush/mobile/data/project/ProjectDatabase.kt:22`; `app/build.gradle.kts:44,58-59`; `docs/adr-0001-project-workspace-identity.md` §6(c).

## 8. Decision needed

1. **Approve D** (Play = B-MVP, sideload = AFA) and record it in `docs/decisions-open.md` §11.
2. **Approve B-MVP semantics:** the private mirror is the working copy; the source is updated only by explicit "Save changes to folder"; "Reload from folder" replaces the mirror only after confirmation; deletions are never propagated automatically in the first release.
3. **Approve the store position:** "AI coding agent", not "file manager"; no AFA declaration for the Play artifact. Attempting AFA on Play anyway is a separate submission bet and should not block B-MVP.
4. **Choose the sideload applicationId strategy:** `.sideload` suffix for coexistence (separate app identity) vs same ID for upgrade continuity (Play App Signing collision risk).
5. **Decide the B-full trigger:** default no; revisit only with measured demand for continuous two-way sync.
6. **Accept the uninstall/data-loss risk** and the required UX warning: the mirror lives in `filesDir`, backups are disabled, and uninstall/clear-data removes it.
