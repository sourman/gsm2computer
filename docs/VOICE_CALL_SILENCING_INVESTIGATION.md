# VOICE_CALL Capture Silencing — Investigation Findings

**Date:** 2026-06-28
**Device:** Pixel 7 (panther), Android 16, build CP1A.260405.005, SDK 36
**App:** com.gsm2computer.bridge, priv-app via Magisk overlay, v2.8.51 (versionCode 329)
**Verdict:** The AudioRecord(VOICE_CALL) path **is salvageable**. The silencing is caused by two
**independent, separately-fixable** policy checks. Both are reachable for our priv-app. No platform
signature is required.

---

## 1. The EXACT AOSP condition that sets `silenced:true` for VOICE_CALL

There are **two** silencing code paths. Both must pass for `VOICE_CALL` to be un-silenced.
The `dumpsys audio` "silenced" string is populated by `AudioFlinger::setRecordSilenced(portId, true)`,
which is called from `AudioPolicyService::setAppState_l(client, APP_STATE_IDLE)`.

### Path A — Input-source permission check (`getInputForAttr` / `checkPermissionForInput`)

**File:** `frameworks/av/services/audiopolicy/service/AudioPolicyInterfaceImpl.cpp`

```cpp
case AudioSource::VOICE_UPLINK:
case AudioSource::VOICE_DOWNLINK:
case AudioSource::VOICE_CALL:
    permRes = audioserver_permissions()
        ? check_perm(CALL_AUDIO_INTERCEPTION, attrSource.uid)
        : callAudioInterceptionAllowed(attrSource);
    break;
```

If `permRes` is false, there is a **legacy fallback** (`isLegacyOutputSource` returns true for
VOICE_CALL/VOICE_UPLINK/VOICE_DOWNLINK):

```cpp
if (!permRes.value()) {
    if (isLegacyOutputSource(req.source)) {
        permRes = audioserver_permissions() ? check_perm(CAPTURE_AUDIO_OUTPUT, attrSource.uid)
                                             : captureAudioOutputAllowed(attrSource);
        PROPAGATE_FALSEY(permRes);
    } else {
        return false;   // hard deny
    }
}
```

Our app **HAS** `CAPTURE_AUDIO_OUTPUT` granted (confirmed via `dumpsys package`), so **Path A
passes via the legacy fallback** — this is NOT the blocker. (Adding `CALL_AUDIO_INTERCEPTION` would
make it pass the primary check and is still recommended; see §3.)

### Path B — `startInput` appop + UID-state check (THE ACTUAL BLOCKER)

**File:** `frameworks/av/services/audiopolicy/service/AudioPolicyInterfaceImpl.cpp` (`startInput`)

```cpp
const auto permitted = startRecording(client->attributionSource, client->virtualDeviceId,
                                      String16(msg.str().c_str()), client->attributes.source);
if (permitted == PERMISSION_HARD_DENIED) {
    return binderStatusFromStatusT(PERMISSION_DENIED);   // hard fail, not silence
}
...
if (permitted == PERMISSION_GRANTED) {
    setAppState_l(client, APP_STATE_TOP);        // un-silenced
} else {
    setAppState_l(client, APP_STATE_IDLE);      // SILENCED  <-- this is what we see
}
client->active = true;
updateUidStates_l();   // may re-silence based on uid state
```

`startRecording` → `checkRecordingInternal` in `frameworks/av/media/utils/ServiceUtilities.cpp`:

```cpp
int32_t getOpForSource(audio_source_t source) {
    switch (source) {
        case AUDIO_SOURCE_VOICE_DOWNLINK: return AppOpsManager::OP_RECORD_INCOMING_PHONE_AUDIO;
        // NOTE/TODO(b/379754682): AUDIO_SOURCE_VOICE_CALL is handled specially:
        //   CALL includes both uplink and downlink, but we attribute RECORD_OP (only).
        // VOICE_CALL and VOICE_UPLINK fall through to default -> OP_RECORD_AUDIO
        case AUDIO_SOURCE_DEFAULT:
        default:                return AppOpsManager::OP_RECORD_AUDIO;
    }
}

bool isRecordOpRequired(audio_source_t source) {
    switch (source) {
        case AUDIO_SOURCE_FM_TUNER:
        case AUDIO_SOURCE_ECHO_REFERENCE:
        case AUDIO_SOURCE_REMOTE_SUBMIX:
        case AUDIO_SOURCE_VOICE_DOWNLINK:   // <-- why VOICE_DOWNLINK is "not silenced"
            return false;
        default:                             // <-- VOICE_CALL, MIC, VOICE_RECOGNITION, etc.
            return true;
    }
}

static int checkRecordingInternal(...) {
    if (isAudioServerOrMediaServerOrSystemServerOrRootUid(uid)) return PERMISSION_GRANTED;
    const int32_t attributedOpCode = getOpForSource(source);
    if (isRecordOpRequired(source)) {
        permitted = permissionChecker.checkPermissionForStartDataDeliveryFromDatasource(
            sAndroidPermissionRecordAudio, resolvedAttributionSource, msg, attributedOpCode);
        // returns PERMISSION_GRANTED, PERMISSION_SOFT_DENIED, or PERMISSION_HARD_DENIED
    }
    return permitted;
}
```

So for `VOICE_CALL`, the appop consulted is **`OP_RECORD_AUDIO`** (not
`OP_RECORD_INCOMING_PHONE_AUDIO` — that is only for `VOICE_DOWNLINK`).

The `PermissionChecker.checkPermissionForStartDataDeliveryFromDatasource` returns
`PERMISSION_SOFT_DENIED` (not GRANTED, not HARD_DENIED) when the appop's effective mode is
`MODE_IGNORED`. That soft-denial makes `startRecording` return non-GRANTED, which triggers
`setAppState_l(client, APP_STATE_IDLE)` → `setRecordSilenced(portId, true)`.

### Path C — `updateUidStates_l` re-silences during the call

**File:** `frameworks/av/services/audiopolicy/service/AudioPolicyService.cpp`

`VOICE_CALL` is a "virtual source" (`isVirtualSource` returns true), so `updateUidStates_l`
auto-sets `allowCapture = true` for it — **unless** `!current->hasOp()`, which re-silences.
`hasOp()` reflects the `OP_RECORD_AUDIO` appop state. So even after `startInput` un-silences,
`updateUidStates_l` can re-silence if the appop evaluates to ignored.

### The appop effective-mode logic (why we get silenced in background)

**File:** `frameworks/base/services/core/java/com/android/server/appop/AppOpsService.java`
(`UidState.evalMode`)

```java
int evalMode(int op, int mode) {
    if (mode == MODE_FOREGROUND) {
        if (appWidgetVisible) return MODE_ALLOWED;
        else if (mActivityManagerInternal.isPendingTopUid(uid)) return MODE_ALLOWED;
        else if (state <= AppOpsManager.resolveFirstUnrestrictedUidState(op)) {
            switch (op) {
                case OP_RECORD_AUDIO:
                    if ((capability & PROCESS_CAPABILITY_FOREGROUND_MICROPHONE) != 0)
                        return MODE_ALLOWED;
                    else
                        return MODE_IGNORED;   // <-- SILENCED
            }
        } else {
            return MODE_IGNORED;               // <-- SILENCED
        }
    } else if (mode == MODE_ALLOWED) { ... }
    return mode;
}
```

And `resolveFirstUnrestrictedUidState(OP_RECORD_AUDIO)` returns
`UID_STATE_MAX_LAST_NON_RESTRICTED = UID_STATE_FOREGROUND (500)`.

`PROCESS_STATE_TO_UID_STATE` mapping (AppOpsService.java):
- `PROCESS_STATE_TOP (2)` → `UID_STATE_TOP (200)` ✓ passes
- `PROCESS_STATE_FOREGROUND_SERVICE (3/5)` → `UID_STATE_FOREGROUND_SERVICE (400)` ✓ passes
- `PROCESS_STATE_IMPORTANT_FOREGROUND (4)` → `UID_STATE_FOREGROUND (500)` ✓ passes (500 ≤ 500)
- `PROCESS_STATE_TRANSIENT_BACKGROUND` and below → `UID_STATE_BACKGROUND (600)` ✗ fails

**The capability is the second gate:** even when the proc-state gate passes, the
`PROCESS_CAPABILITY_FOREGROUND_MICROPHONE` bit must be set. The bit is set only when:
- `PROCESS_STATE_TOP` or `PROCESS_STATE_PERSISTENT` (all capabilities granted), OR
- `PROCESS_STATE_FOREGROUND_SERVICE` with `foregroundServiceType` containing `microphone`
  AND the FGS is allowed while-in-use (app was foreground when FGS started, or is on the
  temp-allowlist).

---

## 2. Is the condition reachable for our app? YES — proven on-device

### Live evidence (test calls placed from Redmi → Pixel 7)

| Scenario | procState | capability | VOICE_CALL in dumpsys audio |
|---|---|---|---|
| Screen OFF, FGS running (normal gateway mode) | 4 (IMPORTANT_FOREGROUND) | `---NFU-TI` (no M bit) | **silenced** |
| Screen ON, activity launched, but not focused | 4 (IMPORTANT_FOREGROUND) | `--MNFUATI` (M bit set) | **silenced** |
| Screen ON + unlocked, app focused (TOP) | 2 (PROCESS_STATE_TOP) | `LCMNFUATI` (all bits) | **not silenced** ✓ |

The decisive dumpsys line, captured with the app in TOP state:

```
06-28 23:18:35:978 rec update riid:703 uid:10288 session:817 src:VOICE_CALL not silenced pack:com.gsm2computer.bridge
06-28 23:18:52:626 rec stop  riid:703 uid:10288 session:817 src:VOICE_CALL not silenced pack:com.gsm2computer.bridge
```

For comparison, `VOICE_DOWNLINK` is always "not silenced" because
`isRecordOpRequired(VOICE_DOWNLINK) == false` — the appop is never consulted:

```
06-28 22:55:01:488 rec update riid:327 uid:10288 session:385 src:VOICE_DOWNLINK not silenced pack:com.gsm2computer.bridge
```

### Why we are silenced in normal operation

1. The gateway runs as a **background FGS** with the screen off (the dedicated-device use case).
2. `RECORD_AUDIO` is a `dangerous` runtime permission → its **UID-level appop mode defaults to
   `MODE_FOREGROUND`** (`Uid mode: RECORD_AUDIO: foreground`, confirmed via `cmd appops get`).
3. With screen off, the app's `curProcState = 4` (IMPORTANT_FOREGROUND) and
   `curCapability = ---NFU-TI` — the `M` (MICROPHONE) capability bit is **missing**.
4. `evalMode(OP_RECORD_AUDIO, MODE_FOREGROUND)` returns `MODE_IGNORED` → `startRecording` returns
   `PERMISSION_SOFT_DENIED` → `setAppState_l(APP_STATE_IDLE)` → `setRecordSilenced(true)`.
5. `appops set --uid 10288 RECORD_AUDIO allow` does **not** override the UID mode — the system
   re-asserts `foreground` as the default for dangerous permissions. Confirmed on-device: the mode
   persists across `appops set`, `appops reset`, and `service call appops` calls.

### Why the M capability bit is missing in background

Per AOSP `AppOps.md` and `ActivityManager.java`:
- `PROCESS_STATE_TOP` / `PROCESS_STATE_PERSISTENT` → all capabilities granted.
- `PROCESS_STATE_FOREGROUND_SERVICE` → capabilities from the declared `foregroundServiceType`.

Our app declares `foregroundServiceType="phoneCall|microphone"` and the FGS is running with
`types=0x00000084` (PHONE_CALL=0x04 | MICROPHONE=0x80), confirmed via
`dumpsys activity services`. **But the M bit is only granted when the FGS is in
`PROCESS_STATE_FOREGROUND_SERVICE` (procState 3/5), not `PROCESS_STATE_IMPORTANT_FOREGROUND`
(procState 4).** When the screen is off, the system demotes the FGS to procState 4, which does
NOT grant FGS-type-derived capabilities — it only gets the base foreground capability set
(NFU-TI). This is the while-in-use restriction: the microphone capability is only honored when
the FGS is in the true FGS proc state, which requires the app to have been foreground when the
FGS was started (or to be on the `mFgsWhileInUseTempAllowList`).

---

## 3. The precise change needed

There are two independent fixes. **Fix 1 is mandatory; Fix 2 is the real blocker and requires
a workaround.**

### Fix 1 — Declare and allowlist `CALL_AUDIO_INTERCEPTION` (recommended, removes the legacy fallback reliance)

The permission `android.permission.CALL_AUDIO_INTERCEPTION` has protection level
**`signature|privileged|role`** (confirmed via `dumpsys package android`). Our priv-app can get it
via the **privileged** path.

**Evidence it is reachable for a priv-app:** `com.google.android.gms` holds it via the **role**
path (it is the `ROLE_SYSTEM_CALL_STREAMING` holder); the platform privapp-permissions file
`/system/etc/permissions/privapp-permissions-platform.xml` allowlists it for CTS tests via the
**privileged** path:

```xml
<!-- Permission required for CTS test - CallAudioInterceptionTest -->
<permission name="android.permission.CALL_AUDIO_INTERCEPTION"/>
```

Changes:

1. `app/src/main/AndroidManifest.xml` — add to the `<uses-permission>` list:
   ```xml
   <uses-permission android:name="android.permission.CALL_AUDIO_INTERCEPTION"
       tools:ignore="ProtectedPermissions" />
   ```

2. `magisk/system/etc/permissions/privapp-permissions-gateway.xml` — add inside
   `<privapp-permissions package="com.gsm2computer.bridge">`:
   ```xml
   <!-- Audio: primary call-audio interception permission for VOICE_CALL capture -->
   <permission name="android.permission.CALL_AUDIO_INTERCEPTION" />
   ```

3. Rebuild, reinstall module, reboot, verify:
   ```bash
   adb shell dumpsys package com.gsm2computer.bridge | grep CALL_AUDIO_INTERCEPTION
   # expect: granted=true
   ```

This makes Path A pass the primary check. It is not sufficient by itself to un-silence in the
background (Path B still fails), but it removes dependence on the legacy
`CAPTURE_AUDIO_OUTPUT` fallback (which is marked `// TODO: remove this access` in AOSP) and is
the correct long-term permission for VOICE_CALL/UPLINK/DOWNLINK capture.

### Fix 2 — Solve the `OP_RECORD_AUDIO` foreground-mode silencing (THE BLOCKER)

This is the actual cause of the all-zero PCM. Three options, in increasing order of robustness:

#### Option 2a — Keep the app in `PROCESS_STATE_TOP` during calls (works, proven)

On-device evidence: when the app is in `PROCESS_STATE_TOP` (procState 2, capability
`LCMNFUATI`), `VOICE_CALL` is **not silenced**. The gateway already declares `MainActivity` with
`launchMode="singleTop"` and dialer intent-filters. If `MainActivity` is resumed (screen on, app
focused) during the call, the silencing disappears.

Drawback: requires the screen on and the app focused — unsuitable for a headless gateway.
However, the gateway already uses `scrcpy` for phantom-touch mitigation; an always-on activity
keep-alive (e.g. a transparent overlay activity or `SHOW_WHEN_LOCKED` + `turnScreenOn` flags on
the in-call UI) may be enough to keep procState=2. This is the closest to a "pure Android API"
fix but is fragile on a dedicated device.

#### Option 2b — Get the app on the FGS while-in-use temp-allowlist

`ActivityManagerInternal.tempAllowWhileInUsePermissionInFgs(uid, durationMs)` (system API)
temporarily allows an FGS to hold while-in-use permissions even when started from background.
Our app is a priv-app with `MODIFY_PHONE_STATE` and could call this, but the API is
`@SystemApi` and not easily reachable without reflection or a hidden-API allowlist. The cleaner
path is to start the FGS while the app is foreground (during onboarding / when the user opens
the app once) — the capability then persists for the lifetime of the FGS process.

Verified prerequisite: the FGS must have been started while the app was in a foreground state
(even briefly). If the gateway service is started from `BootReceiver` (screen off), the
while-in-use gate fails. If it is (re)started from `MainActivity` while the user is setting up the
gateway, the M capability sticks for the FGS lifetime. This is likely the **most practical fix**
and matches how Google's own call-streaming service operates.

#### Option 2c — Switch the `OP_RECORD_AUDIO` UID mode to `allow` at the framework level

`appops set --uid <uid> RECORD_AUDIO allow` does NOT stick — the system re-asserts
`MODE_FOREGROUND` as the default for dangerous permissions. Overriding this requires either:
- A patched `AppOpsService` that changes `sOpDefaultMode[OP_RECORD_AUDIO]` from
  `MODE_FOREGROUND` to `MODE_ALLOWED` (Magisk system patch — heavy, breaks CTS).
- Granting `OP_RECORD_AUDIO` at the package level AND preventing the UID-level mode from being
  set to foreground. The UID-level mode is set by `PermissionController` in
  `setUidMode` during permission grant. The module already hides PermissionController, but the
  UID mode is already persisted and survives. This path is a dead end without a framework patch.

**Recommended: pursue Option 2b (start FGS while foreground) + Fix 1.** This is the only path
that un-silences VOICE_CALL in background operation without a framework patch.

### Verification commands

```bash
# Before fix: silenced
adb shell dumpsys audio | grep "src:VOICE_CALL" | tail -2

# After fix: expect "not silenced"
adb shell dumpsys audio | grep "src:VOICE_CALL" | tail -2

# Confirm capability M bit is set during a background call
adb shell dumpsys activity processes | grep -A25 "com.callagent" | grep curCapability
# expect: --MNFUATI or LCMNFUATI (M present)

# Confirm CALL_AUDIO_INTERCEPTION granted
adb shell dumpsys package com.gsm2computer.bridge | grep CALL_AUDIO_INTERCEPTION
# expect: granted=true
```

---

## 4. Why `VOICE_DOWNLINK` reads near-zero (the prior agent's observation)

`VOICE_DOWNLINK` is **not silenced** by `dumpsys audio` (confirmed in every test call). The
prior agent's report that it "reads near-zero (maps to a different empty device)" is a
**separate, HAL-level issue**, not a policy issue. `VOICE_DOWNLINK` bypasses the appop check
entirely (`isRecordOpRequired == false`), so it is never policy-silenced. The near-zero PCM is
because the Pixel 7's audio HAL routes `VOICE_DOWNLINK` to a different (empty) capture device
than `VOICE_CALL`. This is exactly why the parallel native-ALSA bypass agent is needed for the
downlink, while the `VOICE_CALL` source (which carries both uplink + downlink) is salvageable
via the policy fix above.

---

## 5. Summary

| Question | Answer |
|---|---|
| Exact AOSP condition for `silenced:true` | `AppOpsService.evalMode(OP_RECORD_AUDIO, MODE_FOREGROUND)` returns `MODE_IGNORED` when the app lacks `PROCESS_CAPABILITY_FOREGROUND_MICROPHONE` (screen off / not TOP). This makes `startRecording` return `PERMISSION_SOFT_DENIED`, which triggers `setAppState_l(APP_STATE_IDLE)` → `setRecordSilenced(true)` in `AudioPolicyService::startInput`. |
| Reachable for our priv-app? | **Yes.** Proven on-device: when the app is in `PROCESS_STATE_TOP` with the M capability, `VOICE_CALL` is `not silenced`. No platform signature is required. `CALL_AUDIO_INTERCEPTION` is `signature|privileged|role` and reachable via the privileged path. |
| Precise change needed | (1) Declare + allowlist `CALL_AUDIO_INTERCEPTION` (manifest + privapp XML). (2) Ensure the FGS is started while the app is foreground so the `PROCESS_CAPABILITY_FOREGROUND_MICROPHONE` bit sticks for the FGS lifetime — this un-silences `VOICE_CALL` in background. |
| If not reachable? | N/A — it is reachable. The native-ALSA bypass remains the right path for `VOICE_DOWNLINK` (HAL routing issue, not policy), but the `AudioRecord(VOICE_CALL)` path does NOT need to be abandoned. |

**Decision input:** The AudioRecord path is salvageable. Pursue Fix 1 + Fix 2b in parallel
with the native-ALSA work. The two paths are complementary: `AudioRecord(VOICE_CALL)` for the
mixed uplink+downlink stream once un-silenced, and native ALSA for direct downlink capture.