# MANIFEST — V2-15 device capture

Date: 2026-11-24. Device: Redmi Note 7 Pro (`violet`), API 29, arm64-v8a,
serial `bbf8ca69`. Build: debug `com.crush.mobile` 1.0.0 from this working tree
(uncommitted V2-15 L1/L2/L3 + Phase C tests), bundled engine `v0.100.0`.

| Capture | Shows |
|---|---|
| `v215-02-unlocked.png` | App unlocked, project `Test project folder 2` session list; legacy + project `pd871eee8` children alive (PSS state A). |
| `v215-04-tab.png` | Crush Code projects list; `Test project folder 2` marked Current. |
| `v215-05-two-projects.png` | `TestProject3` sheet before opening its chat. |
| `v215-06-after-stop.png` | Projects list after switching to Crush Chat: no Current row; the project engine stopped, legacy only. |

Raw measurements, engine command lines, PSS samples and the post-stop
process/socket census: `V215-DEVICE-EVIDENCE.md`.

Covered: one real spawned project child + legacy supervisor; stop on a binding
switch; child death; socket-file removal; no listener in `/proc/net/unix`;
per-process PSS in four UI states.

Not covered: a 3-child sample (normal switching stops the outgoing non-legacy
engine); a per-process thread census; `shutdown()` on process termination;
instrumented tests; TalkBack.
