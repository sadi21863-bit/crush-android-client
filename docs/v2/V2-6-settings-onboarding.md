# V2-6 - Settings, onboarding and preferences (started 2026-11-21)

Status: **COMPLETE on the phone (2026-11-21).** The redesign, Finding 13, the
provider round-trip and the preservation rows are implemented and verified on
device. No new preference set was added (V2-A4 trim); Onboarding is
Robolectric-pinned only, for the reason recorded in the manifest.

Authority:

- Revision 4.0 §V2-6 (goal, tasks 1-7, exit).
- Owner trims (V2-A4, 2026-11-19): task 2 (theme/density/navigation/reduced
  motion/response-rendering preference set) and its task 3 persistence work are
  **backlog**, not V2-6. O5 stays closed. Theme and Enter-send continue to use
  the existing keys; nothing new is persisted.
- V2-A5: offer tested providers only, plus any provider with a stored key;
  hidden ids stay resolvable.
- V2-A6: evidence labels.
- V2-A8: neutral graphite accent - interactive/selected state never relies on
  hue alone; focus/selection carries a filled icon, weight, fill or border.
- Rev 3.1 brief §4.7.4 (Settings), §4.7.6 (Diagnostics), §4.7.7 (Onboarding),
  §5.6 (content/host split), §6.5 (coverage intent).
- O12: Finding 13 copy, copy only.

## 1. Scope

| # | Task | V2-6 disposition |
|---|---|---|
| 1 | Redesign `SettingsContent` and `OnboardingContent` using the existing host seams | **IN SCOPE**: V2-2 components/tokens, section order per brief §4.7.4, no host/state-contract change |
| 2 | Add a preference set (theme, density, navigation, reduced motion, response rendering) | **TRIMMED** by V2-A4/O5; no new keys |
| 3 | Persist the new preferences with migration tests | **N/A** (no new preferences); existing writes untouched |
| 4 | Keep credential writes/reads in `SecureKeyStore`/host | **PRESERVE**: content still sees `hasApiKey`/`vaultIds`/hint only; never plaintext |
| 5 | Preserve tested-provider offer policy and stored-key exception | **PRESERVE**: `Providers.offerable`/`vaultIds` rendering unchanged |
| 6 | Fix Finding 13 copy | **IN SCOPE**: "Add a key for <provider> to start chatting." |
| 7 | Provider selection cannot reset navigation or strand a stored key | **PRESERVE + RE-VERIFY** on device (Finding 12 fix, `LaunchRouteState`) |
| - | Diagnose/repair surface | **PRESERVE**: entry stays in About; Diagnostics keeps its V2-2 restyle and function (copy/share, redaction) |

Out of scope, explicitly: a new preference batch (O5), a density control, a
navigation-preference control, reduced-motion setting, response-rendering
setting, app-lock toggle (brief: none), engine pinned version/contract level in
About (no host-exposed value exists; inventing one is out of bounds), key
validation (CORR-001 stays deferred).

## 2. Decisions

### V2-6-D1 - Redesign, not a state-contract change

`SettingsContent`/`OnboardingContent` keep their signatures and stay pure of
Context/ViewModel/container/Keystore/coroutine scope. All writes still cross the
seam as callbacks. No host, repository, DataStore key, engine or credential
path changes. The only copy edits are the Finding 13 line and an explicit
"not checked before your first message" line on onboarding (the brief forbids
implying verification, and CORR-001 is still open).

### V2-6-D2 - Section order follows the brief

The visible order becomes **Provider (credential) -> Permissions -> Appearance
(theme, keyboard) -> Performance -> About/Diagnostics**. The current screen puts
Appearance above Permissions; the brief's order is authoritative for the
redesign. The API key stays inside the Provider block, where it belongs (a key
belongs to a provider).

### V2-6-D3 - Keys are write-only in this surface

The key field exists only while adding/replacing. A stored key is reported as
"API key saved" plus the existing masked hint; plaintext never enters
`SettingsViewState` or the content. "key stored" remains per-provider slot
presence. Delete stays a real write and still goes through the host.

### V2-6-D4 - Permissions stays a switch, not a chip group

One boolean whose safe state is OFF. A switch cannot be knocked into
AUTO_APPROVE by a mis-tap on a chip row. The unsafe state keeps the error tone
and the blast-radius warning. The label stays written ("AUTO-APPROVE" /
"ASK EACH TIME"), not colour-only.

### V2-6-D5 - Performance keeps AUTO/MANUAL truth

Each tuning row keeps its resolved value, its source label and its option chips.
"Reset all to Auto" remains a text action. No new knob is added; a value the app
cannot resolve is not shown.

### V2-6-D6 - Onboarding is honest and unchanged in behavior

Welcome -> provider -> key -> save -> first chat. The provider menu still offers
the tested set; the key field keeps the reveal toggle and the per-provider
placeholder; submit still reports the host write's outcome and never claims the
key was validated. The primary action stays "Start chatting", and a blank key
keeps it disabled.

### V2-6-D7 - Diagnostics is preserved

Its entry remains in About ("Report a problem"). Its V2-2 restyle, redaction,
copy/share and log tail are unchanged by this package.

## 3. Code map

| Area | File | Change |
|---|---|---|
| Settings content | `ui/screens/settings/SettingsContent.kt` | `CrushSectionHeader`/`CrushCard`/`CrushButton`/tokens; brief section order; Finding 13 copy |
| Settings host | `ui/screens/settings/SettingsScreen.kt`, `SettingsViewModel.kt` | unchanged (verify no diff) |
| Settings state | `SettingsViewState.kt` | unchanged |
| Onboarding content | `ui/screens/onboarding/OnboardingContent.kt` | token hero + card, `CrushButton`, honest not-verified line; behavior unchanged |
| Onboarding host/state | `OnboardingScreen.kt`, `OnboardingViewState.kt` | unchanged |
| Tests | `SettingsContentTest`, `OnboardingContentTest` | Finding 13 pin, honest-copy pin; all existing pins stay |

## 4. Test plan

| Pin | File |
|---|---|
| Missing-key line names the provider with a correct article and the old "Add a ..." line is gone | `SettingsContentTest` |
| Provider rows, stored badge, replace/delete, blank-key save gate, theme/keyboard dispatch, permission switch, AUTO/MANUAL tuning, Diagnostics entry, error snackbar, no back control | existing `SettingsContentTest` |
| Onboarding provider slot/menu, per-provider placeholder, blank-key gate, trimmed submit, saving state, failure/success, reveal toggle | existing `OnboardingContentTest` |
| The not-checked-yet line is present and the screen never says the key is verified | `OnboardingContentTest` |
| No new DataStore key is written | code review of the diff (no settings repository change) |

## 5. Evidence

| Claim | Evidence | Status |
|---|---|---|
| Content classes (new pins included) | `SettingsContentTest` 11/11, `OnboardingContentTest` 11/11, 0 failures (local `v2-6-tests-2.log`, 2026-11-21) | PASS |
| Full gate | `v2-6-gate-4.log` (post-2.0-fix): 919 tests / 96 classes / 0 failures / 0 errors / 0 skipped; lint 0 errors / 1 pre-existing `NotShrinkingResources` warning; `BUILD SUCCESSFUL in 12m 29s`. First pass `v2-6-gate-1.log` was also green before the 2.0 fix | PASS |
| Settings redesign on device | before `00`-`01`; after `12`-`14` (sections, cards, type, order, buttons) | PASS on device |
| Permissions control | `15` AUTO-APPROVE state + `16` ASK restored; the host write and recomposition ran (keyboard activation) | PASS on device |
| Finding 13 | `20`: Zen selected, "Add a key for OpenCode Zen to start chatting."; unit pin fails the old wording | PASS on device |
| Provider round-trip | `17` OpenRouter selected without leaving Settings; `18` relaunch lands on Chat (`ciphertext=true`, ChatViewModel composed), not Onboarding; `19` provider persisted; `21` OpenCode Go restored and persisted; `22` the next Crush Chat bootstrap is `ready` on Go with the model re-resolved | PASS on device |
| No silent provider substitution | Log excerpt: selecting key-less Zen reports `provider=opencode-zen storedCiphertext=false providersWithKeys=hyper,openrouter,opencode-go` instead of falling back to a keyed provider | PASS on device |
| Onboarding redesign | unit pins (11/11); device frame not reachable without costing the stored key | NOT PERFORMED (device), content PASS |
| 2.0x font scale on Settings | BEFORE `24` (the "key stored" badge squeezed the hint column); fixed to a status line; AFTER `26`-`27`; font scale restored `28` | PASS after fix |
| Credential path | `SecureKeyStore`/host untouched; content sees `hasApiKey`/`vaultIds`/hint only; no key in any capture or log | PASS (code review + unit tests) |

Harness note: injected `adb input tap` was intermittently unreliable on this
Settings screen during the pass (real-finger selection worked; drag, keyboard
and hover did not fail). The manifest records the observation; it is not an
app claim.

The capture manifest (`docs/parity/2026-11-21-v2-6-settings/MANIFEST.md`)
records what each frame shows and the rows above plus the three deliberate
gaps: onboarding device, tablet/wide and font scale, and key save/replace/
delete on device (needs credential entry, which the agent never performs).

## 6. Honest limits

- No screenshot-test harness; before/after captures are manual.
- Onboarding cannot be reached on this device without a fresh install or
  selecting a key-less provider and then being unable to return without a key,
  so its device row is NOT PERFORMED with that reason. The content is
  Robolectric-pinned (11/11).
- Tablet/wide layouts remain unobservable on this phone (O3).
- 2.0x font scale was driven on Settings and fixed (see §7); Onboarding at 2.0
  is not reachable for the same reason as its device row.
- The API action buttons wrap their labels at 2.0x instead of shrinking. They
  stay fully legible with no clipping; the wrap is accepted rather than adding
  an auto-sizing dependency.
- The key save/replace/delete paths were not driven on device because they need
  credential entry, which the agent never performs. Save/failure/clear are
  unit-pinned and the host is unchanged.
- `adb input tap` was intermittently unreliable on Settings during this pass
  (real-finger taps worked; drags, hover and keyboard did not fail). The
  manifest records it as a harness observation with no root cause claimed.
- No new preference exists to migrate (task 2 trimmed), so no migration test
  exists to write; this is a trim, not a gap.

## 7. Post-pass 2.0x font-scale check (2026-11-21)

The first 2.0x pass (`font_scale=2.0`) found one real layout failure on
Settings: the per-provider "key stored" badge shared the provider `Row` with
the name/hint column, so at 2.0 the hint column lost about half its width and
wrapped word-by-word (`24-font2x-providers-before.png`).

Fix: the badge is now a `CrushStatusLabel` on its own line under the hint -
the same written-status pattern the project rows use. Recheck at 2.0: names
and hints keep the full width (`26-font2x-providers-fixed.png`). The API
action buttons wrap "Replace key" onto two lines inside the pill, which is
legible and not clipped (`27-font2x-api-actions.png`); Permissions, Appearance
and the bottom bar stay legible (`25-font2x-permissions-appearance.png`).
Font scale was restored to 1.0 and Settings re-captured
(`28-font-restored-1x.png`).

This adds one finding (V2-6 F-P) and one fix to the package. The first re-gate
run was lost when the local build was killed mid-write and left a truncated
KSP cache (`StreamCorruptedException`); after clearing `kspCaches/release`,
`v2-6-gate-4.log` is green (919 tests / 0 failures; lint 0 / 1).
