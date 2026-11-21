# V2-6 device evidence manifest (Settings / Onboarding)

Device: Redmi Note 7 Pro `bbf8ca69`, API 29, 393dp portrait, app
`com.crush.mobile`. Build: gate `v2-6-gate-1.log` (919 tests, 0 failures).

The pass records a **before/after** pair for the redesign: the "before" frames
are the gate-9 (V2-5) build that was installed when the pass started; the
"after" frames are this V2-6 build.

| File | What it actually shows | Status |
|---|---|---|
| `00-before-settings-top.png` | Gate-9 build, Settings top: old M3 cards, mixed type, sections ordered Provider -> API -> Appearance; OpenCode Go selected with stored badges on Hyper/OpenRouter/Go | BEFORE (V2-6 redesign) |
| `01-before-settings-bottom.png` | Gate-9 build, Settings bottom: tuning rows, About, "Report a problem" | BEFORE (V2-6 redesign) |
| `12-after-settings-top.png` | V2-6 build: `Provider` and `API key` section headers in the token scale, hairline `CrushCard`s, provider rows with stored badges, graphite primary/secondary buttons; OpenCode Go still selected | PASS on device |
| `13-after-settings-middle.png` | V2-6 build: Permissions before Appearance (brief §4.7.4 order); ASK EACH TIME; theme and keyboard chip cards with the filled-check selected state; Performance starts with the resolved device line | PASS on device |
| `14-after-settings-bottom.png` | V2-6 build: Performance AUTO/MANUAL rows and "Reset all to Auto"; About (version, engine attribution, report entry) | PASS on device |
| `15-permission-auto-state.png` | The switch's unsafe state renders as written status (`AUTO-APPROVE`, error tone) plus the blast-radius warning; toggled by keyboard activation on the focused switch | PASS on device (state/write) |
| `16-permission-ask-restored.png` | The same switch restored to `ASK EACH TIME`; the host write and recomposition both ran | PASS on device |
| `17-provider-openrouter.png` | Provider switched in Settings: OpenRouter selected, `key stored`, API card reads "API key saved" with the OpenRouter hint; the screen did not navigate away | PASS on device |
| `18-restart-chat-route.png` | After force-stop + relaunch with OpenRouter selected: the app lands on the project's Code sessions (Chat route), not Onboarding. `app.log` same launch: `Main: resume gate=PROMPT coldStart=true ... ciphertext=true` and a composed `ChatViewModel` | PASS on device |
| `19-restart-provider-persisted.png` | Settings after that restart: OpenRouter still selected, stored key still reported | PASS on device |
| `20-finding13-zen-no-key.png` | OpenCode Zen selected (no stored key): "No API key stored" and **"Add a key for OpenCode Zen to start chatting."** - the Finding 13 wording; still on Settings | PASS on device |
| `21-restored-opencode-go.png` | Owner's provider restored: OpenCode Go selected with `key stored`; prefs write at 15:44 | PASS on device |
| `22-chat-ready-after-roundtrip.png` | Crush Chat after the round-trip: `Crush Chat · ready`, DeepSeek V4.1, Q&A-only copy. Log same minute: `ensureAgentReady: key set for provider 'opencode-go' ... is_ready=true` | PASS on device (no stranding) |

`2026-11-21-device-log-excerpts.txt` holds the three log lines this pass cites:
the cold start (`ciphertext=true`), the key-less Zen bootstrap
(`provider=opencode-zen`, `storedCiphertext=false`,
`providersWithKeys=hyper,openrouter,opencode-go`) proving **no silent
substitution**, and the restored Go bootstrap (`is_ready=true`, model
re-resolved to `deepseek-v4.1-flash`). No key, hint or ciphertext is quoted.

## Environment notes (not app claims)

- **Injected taps were unreliable on this Settings screen during the pass.**
  `adb shell input tap` intermittently failed to complete a press on Settings
  content while drags scrolled, keyboard activation toggled the switch, mouse
  hover highlighted the correct row, and the same injection worked on the Chat,
  Code and bottom-bar targets. A real finger tap worked on both attempts (the
  provider-row selections in `20` and `21` were owner-performed). This is
  recorded as a harness/ROM observation, not a V2-6 defect; the pass did not
  reproduce it after a process restart at first, then saw it return, so no
  root cause is claimed.
- One heads-up SMS landed over the tap point during the pass and swallowed an
  injected tap; it was dismissed and excluded from the evidence.
- The API-key hint line is blank after some provider switches (the hint is
  remembered for the last saved key, not per provider). This is pre-existing
  data behaviour, unchanged by V2-6; the screen still says only "API key
  saved", which is true.

## Not captured on device (and not claimed)

- Onboarding: reaching it requires a fresh install or selecting a key-less
  provider and then being unable to return without entering a key. The content
  is Robolectric-pinned (11/11); a device frame would cost the owner's stored
  provider state, so it is NOT PERFORMED by decision.
- Tablet/wide panes (O3); 2.0x font scale on Settings/Onboarding.
- A key save, replace or delete on device: that needs credential entry, which
  the agent never performs. The save/failure paths are unit-pinned.
