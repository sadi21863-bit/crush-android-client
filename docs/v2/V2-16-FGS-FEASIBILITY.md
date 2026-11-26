# V2-16 Phase 0 — P0-E6 foreground-service feasibility

Date: 2026-11-26. Status: **evidence report, Checkpoint 1.** Produced under the
authorized V2-16 Phase 0. No production code, no commit, no Play Console
submission.

## 1. Build facts

| Item | Value |
|---|---|
| `minSdk` | **29** |
| `targetSdk` | **36** |
| `compileSdk` | **37** |
| Application id | `com.crush.mobile` |
| FGS declarations today | **none** — the manifest declares no `<service>`, no `FOREGROUND_SERVICE*` permission and no `POST_NOTIFICATIONS` |
| Test device | Redmi Note 7 Pro, **API 29** only |

Because `targetSdk` is 36, the API 34+ foreground-service rules apply on any
device running Android 14 or later, and the API 35 data-sync limits apply on
Android 15+.

## 2. Sources (accessed 2026-11-26)

| Topic | URL |
|---|---|
| FGS types, permissions and `specialUse` review | https://developer.android.com/develop/background-work/services/fgs/service-types |
| Background-start restrictions and exemptions | https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start |
| Android 15 behavior changes (dataSync timeout, BOOT_COMPLETED, SAW) | https://developer.android.com/about/versions/15/behavior-changes-15 |
| Android 16 behavior changes (target 36) | https://developer.android.com/about/versions/16/behavior-changes-16 |
| Play FGS declaration requirements | https://support.google.com/googleplay/android-developer/answer/13392821 |
| Play Device and Network Abuse / FGS policy criteria | https://support.google.com/googleplay/android-developer/answer/13315670 |

## 3. Platform rules by API level

| API | What applies |
|---|---|
| **29-30** | FGS requires the `FOREGROUND_SERVICE` permission and an ongoing notification. A `foregroundServiceType` may be declared but type permissions are not enforced. The API 31 background-start restriction does not exist yet, so a background start is possible subject to ordinary background limits and Doze. |
| **31-32** | Apps targeting 31+ cannot start an FGS from the background except for enumerated exemptions (visible activity transition, user action on a UI element, exact alarm, FCM high-priority, boot/locale/time broadcasts, device/profile owner, battery-optimization opt-out, visible overlay on 15+, and others). Otherwise `ForegroundServiceStartNotAllowedException`. |
| **33** | `POST_NOTIFICATIONS` becomes a runtime permission. If denied, the FGS notification is not shown in the drawer; the service can still run and appears in the Task Manager. User perceptibility is affected. |
| **34** | **Type mandatory**: every FGS must declare a type and hold the matching `FOREGROUND_SERVICE_<TYPE>` permission. While-in-use runtime prerequisites are checked at service creation. Play Console declaration becomes mandatory for apps targeting 34+. |
| **35** | `dataSync` and `mediaProcessing` are limited to **6 hours in any 24-hour period**; `Service.onTimeout()` fires and the service must stop within seconds or the system throws. The timer is shared across the app's services of that type and **resets when the user brings the app to the foreground**. `BOOT_COMPLETED` cannot start `dataSync`, `camera`, `mediaPlayback`, `phoneCall`, `mediaProjection` or `microphone`. The `SYSTEM_ALERT_WINDOW` background-start exemption requires a visible overlay. |
| **36** | The target-16 behavior-changes page adds no new foreground-service type rule beyond the health-permission changes; the 34/35 rules stand. Re-check the page before release (access date above). |

## 4. Type-by-type viability for long-running agent execution

| Type | Permission | Runtime prerequisite | Platform limit | Play use case | Verdict |
|---|---|---|---|---|---|
| `dataSync` | `FOREGROUND_SERVICE_DATA_SYNC` | none | **6 h / 24 h** on 35+; timer resets only in foreground | user-initiated backup/restore, upload/download, local import/export/processing | **Not suitable for >6 h.** Semantically a stretch for autonomous coding; usable only as a bounded-session stopgap |
| `specialUse` | `FOREGROUND_SERVICE_SPECIAL_USE` | none | Play review; manifest `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE">` | free-form use case, reviewed with the app | **Only plausible general candidate; unverified** — approval is not guaranteed |
| `shortService` | none | none | **~3 minutes** | short critical work | **No** |
| `mediaProcessing` | `FOREGROUND_SERVICE_MEDIA_PROCESSING` | none | 6 h / 24 h | media transcoding | **No** (media only) |
| `connectedDevice` | `FOREGROUND_SERVICE_CONNECTED_DEVICE` | BT/NFC/USB/network permission or USB permission | — | external device interaction | **No** |
| `location`, `camera`, `microphone`, `health`, `phoneCall`, `mediaProjection`, `remoteMessaging` | type-specific | runtime permission / role | while-in-use rules | their own domains | **No** |
| `systemExempted` | `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` | system role (device owner, VPN, exact-alarm, etc.) | throws otherwise | system integrations | **No** |

## 5. Findings

### F1 — No foreground-service type is a guaranteed fit (observed from the docs)

The only general-purpose bucket is `specialUse`, and it is explicitly reviewed
in Play Console with a free-form justification and a demo video. "Plausible"
is not "approved". No type can be treated as an architectural guarantee before
that review.

### F2 — `dataSync` is capped and semantically narrow (observed)

On target 35+, `dataSync` is limited to 6 hours per 24 hours across the app.
The timer resets only when the user foregrounds the app. A long-horizon coding
session that exceeds six hours in the background would be stopped by the system.
The Play use cases describe user-initiated transfers and local processing, not
autonomous agent execution; using it would invite a policy mismatch.

### F3 — FGS is not a process-death recovery mechanism (observed/interpreted)

The API 31+ background-start restriction means a restarted app in the background
cannot start an FGS without an exemption. A notification keeps a **live** process
from being frozen; it does not resurrect a killed one, and it does not make the
engine child survive `am kill`/`am crash` (the same finding as P0-E1 F5).

### F4 — Type and permission enforcement starts at API 34 (observed)

`targetSdk 36` means every API 34+ device enforces a declared type plus the
type permission, and every while-in-use prerequisite is checked at service
creation. A manifest change is therefore required for any FGS option.

### F5 — The API 29-33 path is different (observed)

On API 29-30 there is no type enforcement and no background-start restriction;
on 31-33 the restriction exists but not the type permission. The device under
test is API 29, so none of the 34+ enforcement can be validated locally.

### F6 — Notification permission affects user perceptibility (observed)

From API 33, `POST_NOTIFICATIONS` is a runtime permission. If denied, the FGS
notification is not shown in the drawer (the Task Manager still shows the
service). Play requires the use to be user-perceptible; the denial path needs a
defined UX, not a silent service. (Platform behaviour; the exact denial UX was
not verified on a 33+ device — see §7.)

### F7 — Play policy criteria are qualitative (observed)

The FGS policy requires the use to be beneficial and core, user-initiated or
perceptible, stoppable by the user, not deferrable without a negative user
experience, and to run only as long as necessary. An autonomous coding agent
fits the first four credibly; "only as long as necessary" is a judgment the
reviewer applies, and `specialUse` is the type that asks them to make it.

## 6. D1 input (recommendation, not a decision)

**Do not select FGS as the foundation.** The evidence supports:

1. **Default to the foreground-only model** with a controlled, visible
   interruption (the plan's D1 option b), because it needs no Play approval and
   is honest on every API level.
2. Treat FGS `specialUse` as an **optional enhancement**, pursued only with a
   Play Console declaration, a demo video and an eligibility answer. It should
   not be a prerequisite for the execution layer.
3. Do **not** use `dataSync` for sessions that can exceed 6 hours; its cap and
   semantics make it the wrong type for this workload.
4. Regardless of D1, never rely on FGS to recover from process death; recovery
   is the execution-record + republish/read-back path (P0-E1).

This section is **evidence for D1, not the decision**. D1 remains open until the
required evidence and the owner's decision are recorded.

## 7. Not performed

| Item | Reason |
|---|---|
| Play Console declaration / `specialUse` review | requires the owner's Play account and a release candidate |
| API 34/35/36 device verification | no such device available; only API 29 |
| Debug-only FGS prototype | cannot observe API 34+ enforcement on API 29; a prototype would only demonstrate notification plumbing already documented |
| Policy eligibility answer | external (X4); this report supplies the inputs, not the answer |
| Exact `POST_NOTIFICATIONS`-denied FGS UX | not verified on a 33+ device; confirm the drawer/Task-Manager behaviour in A5 |

## 8. Verdict

**P0-E6: conclusive at the documentation level, unverified on device.**
`specialUse` is the only plausible FGS type and is subject to Play review;
`dataSync` is capped at 6 h/24 h and semantically wrong; `shortService` is three
minutes; the remaining types do not apply. FGS is not a process-death recovery
mechanism. The executable fallback is foreground-only execution with a visible
interruption, which is the recommendation for D1 until Play eligibility is
established.
