# Crush Mobile V2 — Builder AI Reference
**Snapshot:** 2026-11-12 | **Repo:** sadi21863-bit/crush-android-client

## Decision
Ready to begin V2 visual design and incremental UI implementation. This is a presentation and information-architecture redesign, not an architecture rewrite. The standalone HTML prototype is a visual/interaction reference only; its sample data is illustrative and it is not connected to the engine.

## Current app facts
Native Kotlin + Jetpack Compose + Material 3. Existing routes: Onboarding, Chat, Settings, Models, Diagnostics, Projects, Review.
Relevant source:
- app/src/main/java/com/crush/mobile/MainActivity.kt
- app/src/main/java/com/crush/mobile/ui/navigation/NavGraph.kt
- app/src/main/java/com/crush/mobile/ui/screens/chat/ChatScreen.kt
- app/src/main/java/com/crush/mobile/ui/screens/projects/ProjectsScreen.kt
- app/src/main/java/com/crush/mobile/ui/screens/settings/SettingsScreen.kt
- app/src/main/java/com/crush/mobile/ui/theme/Theme.kt and Color.kt

Current capabilities include streamed chat/session history, provider/model discovery, tool activity and permission asks, imported-folder projects, per-file review/revert/export, chat copy/save, app lock, encrypted provider keys, diagnostics and chat-to-code handoff.

## Product direction
Build a focused mobile coding workspace, not a generic chatbot. Chat is primary; Projects and Review are first-class; Settings and Diagnostics are secondary. On compact phones use bottom navigation for Chat / Projects / Review, with Settings and Diagnostics in a secondary surface. Larger screens may use a rail/drawer.

## Invariants — do not regress
1. Preserve native Android + Compose and reuse existing ViewModels/domain models where possible.
2. Engine binary stays in jniLibs, extracted to nativeLibraryDir; never copy it to filesDir and chmod.
3. Keep useLegacyPackaging = true.
4. Engine control transport stays app-private UDS; never expose engine over TCP. The app's loopback CONNECT egress bridge is separate and relay-only.
5. Preserve long-lived workspace SSE, subscribe-before-prompt ordering, and whole-message replacement semantics.
6. Preserve Android Keystore AES/GCM key storage and secret redaction.
7. Preserve project import-only semantics; no internal repo creation. Keep platform folder permissions and central ProjectTrustService consent for project-root crushrc on every attach/restore path.
8. Preserve fail-closed chat deny-all tool policy. Do not expose privileged/sysadmin tools.
9. Preserve version-aware permission protocol handling (v0.99 vs v0.100+) and fail-safe behavior.
10. Preserve handoff consent, approval stamps, destination binding, dispatch/resume/discard and cold-boot restore.
11. Preserve navigation back-stack semantics, engine instance budget (chat + one project), app lock, session lifecycle and persisted user data.

## Screen guidance
### Chat
Clear conversation title, project context and subtle engine state; readable transcript; anchored multiline composer; attach/project/model actions; truthful streaming and stop states; visible permission asks; collapsible tool activity; file-change summary links to Review; retain message/code copy and save.
### Projects
Explain import-existing-folder; show project identity/recent activity/active state; retain SAF/storage permission flow; explain trust/crushrc consent plainly; removal must not imply deleting the user's source folder.
### Review
Group changes by turn; show file counts and added/removed lines; readable light/dark diffs; preserve per-file revert eligibility/confirmation and byte-exact SAF export.
### Settings and Diagnostics
Group Provider & model, Appearance, Security, Engine, About. Distinguish saved key from verified provider connection. Redact secrets in logs/exports.
### Onboarding
Provider selection and key entry remain together; mask key by default; show saving/error states.

## Visual direction
Use the prototype as moodboard: restrained graphite surfaces, violet primary accent, cool-gray secondary text, green success, amber waiting/permission, red destructive/error. 8dp spacing rhythm, 12–18dp radii, clear type scale, monospaced code/diffs. Avoid decorative dashboards, excessive gradients, glass effects and desktop density on phones. Keep light and dark themes, dynamic font scaling, TalkBack, contrast, 48dp touch targets and edge-to-edge insets.

## Implementation order
1. Inventory existing Composables, UI state, navigation events, dialogs and edge cases; capture baseline screenshots.
2. Add design tokens and reusable primitives (scaffold, navigation, cards, chips, status, empty states, dialogs).
3. Redesign Chat, then Projects, Review, Settings/Diagnostics, Onboarding.
4. Keep business logic in existing ViewModels/services; presentation adapters first.
5. Add Compose UI tests for navigation, lock, permission prompts, trust dialogs, review/revert/export and handoff.
6. Run the full Gradle gate and physical ARM64 device rails. x86_64 emulator cannot run the ARM64 engine.
7. Validate light/dark, compact/large font, and permission/error/empty/loading/streaming states.

## Readiness snapshot
Main HEAD at audit: cd64272d5356b80fc4f59e49c7b7c0249f410ed4; engine v0.100.0 merge 6800c0e; fetch pin ratchet 7346e20. Repository records 689 JVM tests across 74 suites and 8 instrumentation tests, with device evidence for engine v0.100, project trust, handoff/cold boot, switching and rollback. These are repository-recorded results; rerun the gate before a redesign merge. Ready for incremental UI work, not a broad rewrite.

## Acceptance checklist
- [ ] Chat streaming/stop/session switch/model/tool/permission/copy/save
- [ ] Projects import, denied permission, trust allow/cancel, open/switch/remove
- [ ] Review multi-file diff, eligible/ineligible revert, byte-exact export
- [ ] Handoff consent, dispatch, failure/resume/discard, destination change, cold boot
- [ ] Provider/key, theme, lock, tuning, diagnostics redaction/export
- [ ] TalkBack, 48dp targets, font scaling, contrast
- [ ] Phone/tablet, light/dark
- [ ] No UDS/SSE/security/persistence/handoff regressions
