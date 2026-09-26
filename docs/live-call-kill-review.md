# Live-call kill review

Review-only. **No runtime behavior was changed.** Scope is the production path: Pixel app `com.gsm2computer.bridge` → Tailscale WebSocket → `hub.py` PipeWire splice → Chromium Control UI Talk (CDP) → OpenClaw / OpenAI Realtime.

The reported symptom is: **calls sometimes work for about 10 minutes, then suddenly go dead.** We do not have a confirmed recurrence or logs from an incident. Rank below is “most likely to produce that shape,” not proven cause.

Severity:

| Level | Meaning |
|-------|---------|
| **High** | Can tear down or silently mute an in-progress healthy call |
| **Medium** | Can do the same under a race, operator action, or overlapping timer |
| **Low** | Real hazard, but the default timer does not match ~10 minutes |

“10-minute fit” is whether the trigger itself, or the time-to-fail after a slow leak, lands near 10 minutes.

---

## Ranked findings

### 1. Talk health watchdog ends the GSM call when WebRTC *looks* dead — and rollover is too late to save a ~10 min session

- **Severity:** High
- **Where:** `hub/hub.py` `_watch_webrtc_ui_talk`, `_talk_rollover_watch`; `hub/talk_chromium.py` `OpenClawTalkUI.talk_session_healthy`, `restart_talk`
- **Trigger:** After 60 s grace, every 15 s the hub attaches CDP and requires:
  - Control UI DOM “live” class
  - at least one `RTCPeerConnection` with **`connectionState === "connected"` AND `iceConnectionState` in (`connected`, `completed`)**
  - 45 s of consecutive `False` → `bridge._signal_abort("webrtc-ui talk unhealthy…")` → GSM WebSocket close
  - 90 s of CDP `None` → abort `"webrtc-ui CDP unavailable…"`
  
  Handshake uses a **looser** check (`_webrtc_connected`: connection **or** ICE). A peer that ICE-restarts (`checking` / `disconnected`) or whose `connectionState`/`iceConnectionState` disagree fails the live probe even if media is still flowing.

  Separately, `_talk_rollover_watch` restarts Talk at **`GSM2COMPUTER_TALK_ROLLOVER_S` default 1500 s (25 min)** to beat “OpenAI/WebRTC session limits (~30 min)”. `restart_talk` clicks Stop, waits up to 12 s, then `start_talk(allow_already_active=False)`. Any exception **aborts the whole GSM call**.
- **10-minute fit:** **Yes, strongest match if the provider/UI session actually dies around 10 minutes.** Rollover cannot preempt that — it only runs at 25 min. After ICE/DOM go stale, the GSM leg is killed ~45–90 s later. If the session stays “connected” but media is silent, this watchdog does **not** fire (see finding 3).
- **Fix:**
  1. Confirm OpenClaw / `gpt-realtime-2.1-mini` WebRTC max duration on this account (network log on the Talk peer, `response.*` errors on the data channel). If it is ~10–15 min, set rollover to **well under** that (e.g. 8 min) and make rollover failure **not** hang up GSM — keep the phone path, retry Talk, or fail loud without `abort_call`.
  2. Align `talk_session_healthy` with `_webrtc_connected` (OR, not AND). Treat brief `disconnected`/`checking` as unknown, not unhealthy. Do not abort on CDP `None` unless Talk is also missing from Pulse (`pactl list source-outputs` / sink-inputs).
  3. Never call `restart_talk` unless `/health.call.busy` is true **and** the operator wants mid-call refresh; consider skipping rollover entirely until the 10-minute death is logged.

### 2. CDP harden can close or replace the live Talk tab; `/health` and the Talk watchdog share one DevTools socket

- **Severity:** High
- **Where:** `hub/talk_chromium.py` `CdpSession.call`, `OpenClawTalkUI._connect_page`, `_health_page_probe`, `health`; `hub/hub.py` `GET /health`
- **Trigger (any of these during a live call):**
  1. **5 s hard timeout** on every CDP method: on expiry `CdpSession.close()` force-aborts the DevTools WebSocket. Chromium allows one inspector per target; a kicked peer looks like a wedge to the next caller.
  2. **`PAGE_STATE_JS` reads `document.body.innerText` then slices 800 chars.** `innerText` walks the whole chat DOM. After many minutes of transcript this can exceed 5 s, which trips (1).
  3. If `location.href` is `about:` (the GpuControl / swiftshader ghost the launch script already documents), `_connect_page` **`GET /json/close/{id}`** that target. A live Talk renderer that briefly reports `about:blank` is destroyed mid-call.
  4. If every listed page fails, `_connect_page` **`PUT /json/new`** with the tokenized Control UI URL. Later probes prefer “chat” pages and may attach to the **empty new tab**. `talk_session_healthy` then returns `False` (no live PC) and finding 1 hangs up the GSM call. Auto-accept of JS dialogs (`Page.javascriptDialogOpening` → `accept: true`) can also confirm a reload / leave-page prompt and tear WebRTC down.
  5. `GET /health` always runs `_health_page_probe` (2.5 s wrapper). E2e `wait_until_idle` hits `/health` every 250 ms. Portal dashboard also fetches `/health` on render. Each fetch attaches CDP **while** `_watch_webrtc_ui_talk` attaches every 15 s.
- **10-minute fit:** **Plausible.** Not a fixed 10-minute timer, but transcript growth + repeated attach/timeout/close can start failing on that timescale. Ghost `about:blank` + `/json/close` is sudden death at a random time, including ~10 min.
- **Fix:**
  - Do **not** call `_connect_page` / `Page.reload` / `/json/close` / `/json/new` from `/health` or from the in-call watchdog. Health should be Pulse + `talk_active` + last successful probe cache.
  - Drop `innerText` from the hot probe (use the talk-button/`live` selectors only).
  - On CDP timeout: detach without `/json/close`. Never close a target whose list URL is the live chat.
  - Serialize one CDP session for the Talk page; do not overlap e2e hooks with a real call.
  - Stop auto-accepting dialogs during `talk_active` (log them).

### 3. Audio can die while the phone WebSocket and GSM call stay up (no webrtc-ui energy watchdog)

- **Severity:** High
- **Where:** `hub/hub.py` `_watch_talk_frames_without_energy` (relay **only**); `OpenClawTalkUI.start_talk` / `ensure_openclaw_phone_mic`; `hub/gsm2computer-openclaw-phone-mic.service`; `hub/talk-chromium.service` vs `hub/start-talk-chromium.sh`
- **Trigger:** In `webrtc-ui` the hub never aborts because OpenClaw mic/speaker energy is zero. `_watch_talk_frames_without_energy` is only started for `OPENCLAW_TALK_MODE == "relay"`. Silent media with ICE still `connected` is a live, “healthy” call.

  Known mute mechanisms:
  - **Post-Talk mic re-link:** after WebRTC bind, `ensure_openclaw_phone_mic(force=True)` **systemctl-restarts** `pw-loopback`. Comment says getUserMedia unlinks capture ports and the restart must happen *while* Chromium holds the source. If PipeWire hands Chromium a **new** node and the existing `MediaStreamTrack` keeps the old deviceId, OpenClaw VAD sees energy 0 for the rest of the call. There is **no** re-`getUserMedia` after this forced restart.
  - **Loopback death later:** unit `Restart=on-failure`. A glitch mid-call recreates `openclaw_phone_mic` without refreshing Chromium’s track.
  - **`PULSE_SOURCE` split brain:** `talk-chromium.service` sets `PULSE_SOURCE=phone_uplink.monitor` (documented silent on this PipeWire). The launch script defaults to `output.openclaw_phone_mic`. systemd env wins. The gUM hook prefers the virtual mic **until the page reloads without the hook** (finding 2, or OpenClaw SPA navigation).
  - Chrome page-lifecycle freeze on an unfocused `:1` window (no `--disable-renderer-backgrounding` / `--disable-backgrounding-occluded-windows`) can freeze JS/WebRTC on the order of 5–10 minutes while the hub WS still pumps `openclaw_bus` silence.
- **10-minute fit:** **Yes for the human symptom** (“suddenly go dead”) even when the watchdog never fires. Chrome freeze and a delayed loopback unlink are both in the 5–10 minute band. A call that was loud at t=0 then silent at t=10 with `busy=true` is this class.
- **Fix:**
  - Run a **webrtc-ui** energy watchdog on `openclaw-mic` / `openclaw-spk` taps (already recorded). If both stay ~0 for N seconds while GSM uplink is live, log a distinct reason; do not confuse it with WS idle.
  - After `force` loopback restart, wait until `pactl list source-outputs` shows Chromium on `openclaw_phone_mic`, or fail the handshake — do not continue with a dead mic.
  - Unify `PULSE_SOURCE` on the systemd unit with the script (`openclaw_phone_mic`). Never restart the loopback unit while `live_call.busy` unless Chromium re-gUM is proven.
  - Add Chromium flags: `--disable-renderer-backgrounding --disable-backgrounding-occluded-windows --disable-background-timer-throttling`.

### 4. Downlink drain abort vs ping vs WS-idle: three watchdogs on one writer

- **Severity:** High (false kill of a slow-but-alive path)
- **Where:** `hub/hub.py` `PipewireBridge._pump_out`, `_watch_live_call`, `ws_send_ping` / `ws_send_text`; `hub/call_slot.py` `CallSlot.check` (`ws_idle_s=60`, `uplink_idle_s=120`, `ping_s=20`); `app/.../realtime/HubStreamClient.kt`
- **Trigger:** Downlink is **continuous** 20 ms 48 kHz PCM as JSON+base64 (~150 KB/s). `_pump_out` `drain()` times out at **`GSM2COMPUTER_WS_SEND_TIMEOUT_S` default 5 s** and aborts `"websocket downlink send stalled"`. That path exists specifically because a stuck drain also blocks ping/pong on the same `StreamWriter`, which used to freeze `last_ws` and look like `"websocket idle 60s"` while the GSM call lived.

  `_watch_live_call` still pings every 20 s on that same writer. `asyncio.TimeoutError` subclasses `OSError`, so a ping `drain()` timeout is `"websocket ping send failed"` — a **second** abort reason for the same stall.

  Pixel `HubStreamClient.send()` **ignores** OkHttp `WebSocket.send`’s boolean. A full outgoing queue drops uplink frames with no log. Hub `note_uplink()` stops; after 120 s `"gsm uplink idle"` aborts. OkHttp default **writeTimeout is 10 s** (not set to 0); a stalled send can `onFailure` → `RtpSession` `onError` → `onRtpTimeout` → `CallOrchestrator.tearDown("Hub stream timeout")` and **hang up GSM**.
- **10-minute fit:** **Indirect.** Not a 10-minute constant. A growing send buffer, Tailscale half-open, or Wi‑Fi doze (see finding 8) can look like “fine, then dead.” The 5 s stall abort is faster than 10 min once the path actually wedges.
- **Fix:** One outbound WS lock; classify stall vs idle as a single reason. Pause ping while `_pump_out` is draining. Do not abort on 5 s drain if TCP is still progressing (bytes written). On Android: check `send()`; `writeTimeout(0)` / `pingInterval` already 20 s; log queue failures. Prefer aborting only the downlink pump, not GSM, if uplink is still arriving.

### 5. E2e 30-minute timer + heal ladder is idle-checked but not idle-locked

- **Severity:** Medium (High if a call overlaps the timer)
- **Where:** `hub/openclaw_e2e.py` `run_with_ladder`, `run_once`, `ensure_phone_mic_preflight`, `soft_reload_talk`, `_systemctl_restart`, `heal_audio_bus`, `wait_until_idle`, `install_dc_hook` / `snap_dc`; `hub/gsm2computer-openclaw-e2e.timer` (`OnUnitActiveSec=30min`); `hub/hub.py` `_maybe_schedule_e2e_after_silent_call`
- **Trigger:** Ladder steps check `line_idle()` / `wait_until_idle` then **act** (Control UI `Page.reload`, `systemctl restart openclaw-gateway`, `restart talk-chromium`, `setup-audio-bus.sh`). There is no lock: a Pixel claim between check and restart is a TOCTOU. `run_once` calls `ensure_phone_mic_preflight()` **before** `real_call_holding()` — a restart only if the unit is inactive, but it still races.

  While a test is winding down, `wait_until_idle` polls `/health` at 4 Hz (finding 2). `install_dc_hook` / `snap_dc` open extra DevTools sockets on the **same** chat page. A real call that preempts `/e2e-test` (ADR 0004 exception) can inherit those sockets and a page just reloaded for `NeedsFreshGum`.

  Silent-call auto-e2e (`GSM2COMPUTER_E2E_AFTER_SILENT` default on) starts the same oneshot **2 s after** a ≥10 s call with quiet OpenClaw taps — i.e. immediately after the incident we care about, while the operator may redial.
- **10-minute fit:** **No as a 10-minute clock** (30 min + `Persistent=false`). **Yes as a coincidental overlap** if people call ~10 min after the last e2e, or if silent-call e2e runs under them.
- **Fix:** Hold a hub-side `line_busy` lock the e2e process must take; refuse all CDP and `systemctl` if `call.busy && !e2e`. Move `ensure_phone_mic_preflight` after the idle check. Disable `Page.reload` / unit restarts unless `talk_active` is false **and** `busy` is false at the moment of exec. Do not attach e2e DC hooks unless `session.e2e` is true. Gate silent-call e2e on “no busy for 60 s.”

### 6. Stuck-slot reaper can stop the Talk **singleton** after a new call has started

- **Severity:** Medium
- **Where:** `hub/hub.py` `_reap_stuck_call_slot`, `_ensure_call_slot_freed`, `_clear_stale_active_bridge`; `hub/call_slot.py` `ensure_released_after_abort`; `talk_chromium.get_talk_ui` singleton
- **Trigger:** Watchdog aborts, then schedules `_ensure_call_slot_freed(..., wait_s=5)`. The reaper **also** runs `live_call.check()` every 30 s and, on the same reason, waits 5 s, `release()`, `_clear_stale_active_bridge`, and `openclaw_ref.stop()` if `active_openclaw is openclaw_ref`. `OpenClawTalkUI` is process-global. After the old handler `finally` releases, a new dial can `start_talk()` on that same object during the 5 s wait. The reaper then `stop()`s Talk for the **new** live call. `_clear_stale_active_bridge` matches the captured bridge pointer (good); Talk stop does not.
- **10-minute fit:** No. Follow-on-call killer, not a 10-minute timer.
- **Fix:** Stop Talk only if `talk_started_at` / a generation counter still matches the aborted claim. Reaper should be last-resort for `max_s` + wedged handler, not a second copy of the 60 s idle watchdog.

### 7. Admin `/admin/call/release` “fresh” window is 10 s, not “line idle”

- **Severity:** Medium (operator / automation)
- **Where:** `hub/hub.py` `POST /admin/call/release`; `hub/call_slot.py` `call_looks_live` (`ADMIN_RELEASE_FRESH_S = 10`)
- **Trigger:** Without `force=1`, release is allowed when **both** `last_ws_s` and `last_uplink_s` are ≥ 10 s (or null). A 10 s Tailscale hitch looks “not live.” Comment in hub: a triage probe already cut a healthy call. `force=1` always aborts.
- **10-minute fit:** No, unless someone polls this on a timer.
- **Fix:** Refuse unless `force=1` **or** `check()` would already abort (same thresholds as the watchdog). Log caller identity.

### 8. Android: any WS error hangs up GSM; wakelock/Wi‑Fi lock are weak; no audio focus

- **Severity:** Medium
- **Where:** `CallOrchestrator.onRtpTimeout` → `tearDown`; `RtpSession.transportSink.onError`; `HubStreamClient` (`readTimeout(0)`, `pingInterval(20s)`, default writeTimeout 10 s, `send` unchecked); `GatewayService.acquireLocks`; `MicCapabilityGuard` (in-call skip is correct)
- **Trigger:** Hub close/abort surfaces as `onClosed`/`onFailure` → `onError` → **immediate GSM disconnect**. That is intended for a dead hub, but it means every hub false-abort (findings 1–4) also drops the cellular caller.

  `PARTIAL_WAKE_LOCK.acquire()` with **no timeout** is the right call-bridge behavior (the lint suggestion of a timeout would *create* a mid-call kill). `WIFI_MODE_FULL_HIGH_PERF` is deprecated and often a no-op on modern Pixel builds; Wi‑Fi can sleep, Tailscale stalls, finding 4 fires.

  There is **no** `AudioManager.requestAudioFocus`. Another app can duck `USAGE_MEDIA` incall injection. `MicCapabilityGuard` periodic relaunch is skipped while `bridgeState != IDLE` (good). WS mode correctly disables the RTP inactivity timer and the “silent AudioRecord” fail-the-call path.
- **10-minute fit:** OEM Wi‑Fi/Doze around several minutes is plausible, not proven. Not an app-level 10-minute timer.
- **Fix:** Keep the un-timeouted wakelock. Use `WifiManager.WIFI_MODE_FULL_HIGH_PERF` replacement (`WifiLock` + `ConnectivityManager` / request a high-perf network). Hold audio focus for the call. Log `send()==false`. Distinguish “hub ended the session” (do not redial) vs “transport blip” (do not hang GSM for 3 s).

### 9. Hard cap 45 minutes, hub `/token` 1 hour — not the 10-minute clock

- **Severity:** Low
- **Where:** `CallWatchdogConfig.max_s` default **2700**; `POST /token` `expires_at` +1 h (hub never validates the token)
- **Trigger:** A call that actually lasts 45 min is aborted with `"max call duration"`. Control UI `#token=` is the OpenClaw gateway token from disk, not this stub.
- **10-minute fit:** **No.**
- **Fix:** Leave max as a safety cap; log it distinctly. Do not treat `/token` expiry as a live-call signal.

### 10. Call-tap disk / WS jitter buffer growth — bounded, not a 10-minute bomb

- **Severity:** Low
- **Where:** `hub/call_tap.py` (`KEEP_CALLS=20`, WAV stems during the call); `RtpSession` WS jitter buffer cap 500 frames (~10 s at 20 ms)
- **Trigger:** 48 kHz stereo taps are large (~110 MB / 10 min / stream) but rotate after the call. A full disk could fail `pw-record` / ffmpeg and abort helpers (`_watch_helper_exit` → abort). WS buffer drops oldest; it glitches rather than kills.
- **10-minute fit:** Only if the hub disk is already almost full.
- **Fix:** Monitor tap dir + inode; fail recording, not the call, on ENOSPC.

---

## Overlapping or contradicting patches

Patches from 2026-09-23–24 that step on each other:

| Cluster | What fights |
|---------|-------------|
| **Idle vs stall vs ping** | `6fd5d13` abort on downlink `drain` stall was added because ping/idle mis-detected half-open sockets. `_watch_live_call` still pings on the same writer; stall now reports **two** reasons (`downlink send stalled` vs `ping send failed` vs old `websocket idle`). |
| **Abort vs force-release vs reaper vs admin** | `dcd9f4e` force-release after abort; `ff8a6a9` clear stale `active_bridge` + protect live admin release; `0d495cc` abort stuck slot on bridge close; `_reap_stuck_call_slot` still duplicates the idle/max checks and can `stop()` Talk. Three wait-5 s paths. |
| **Talk mic** | `bbe0c2d` / `4f7ca72` / `99971e0` / `d7e0a4c`: monitor is silent → sink-capture loopback → **force restart loopback after gUM**. systemd unit still exports `PULSE_SOURCE=phone_uplink.monitor`. ADR 0002 and `TALK_WEBRTC.md` still draw `phone_uplink.monitor → Chromium`. `setup-audio-bus.sh` can spawn a **second** `pw-loopback` if the unit is not enabled (`>/tmp/openclaw-phone-mic-loopback.log` and a stray extra `&` redirect). |
| **CDP “help” vs live Talk** | `d7e0a4c` 5 s timeout + force-close + ghost `/json/close` + `/json/new` + dialog accept. Same helpers are used by in-call `talk_session_healthy`, `/health` (made CDP-safe in `99971e0` so e2e idle checks cannot hang), and e2e DC hooks. Hardening the probe made it **more willing to destroy the page**. |
| **E2e heal vs live call** | `39a819b` ladder reloads/restarts; `4f7ca72` / `dd9bb42` add idle checks and no timer catch-up. Checks are TOCTOU, not a lock. Silent-call auto-start (`_maybe_schedule_e2e_after_silent_call`) can run the ladder just as the human redials. |
| **Health vs Talk liveness** | `/health` CDP probe exists so e2e can see `talk_active`. The same probe is what wedges or ghost-closes the live tab. |

---

## What is already guarded (so we do not “fix” it)

- E2e and heal **intend** to refuse when `call.busy` or `talk_active` (`line_idle`, `real_call_holding`). Preempt `/e2e-test` for a real Pixel path (ADR 0004 exception).
- Admin release refuses a call with WS/uplink younger than 10 s unless `force=1`.
- Android waiting GSM leg is rejected; teardown of the waiting leg does not restore mixer / hang the live call (ADR 0004).
- `MicCapabilityGuard` will not foreground-relaunch during a call.
- WS mode does not apply the 30 s RTP inactivity hangup or the “silent capture source” fail-closed path.
- Hub TCP keepalive 30/10/3; Pixel OkHttp ping 20 s; hub ping 20 s; inbound ping/pong count as `note_ws_activity`.
- `ensure_released_after_abort` will not `release()` a **newer** `claimed_at` (tested). Talk singleton stop is the hole, not the slot.

---

## Logging / metrics to pin the next 10-minute death

Add **one structured line per second** (or on change) while `live_call.busy`, without extra CDP:

```text
call_tick busy age_s= last_ws_s= last_uplink_s= abort= \
  dl_energy= ul_energy= talk_active= webrtc= \
  pulse_mic= pulse_spk= loopback_active= \
  ws_send_q= pw_cat_rc= pw_record_rc=
```

Concrete signals:

| If we see… | Then it was… |
|------------|----------------|
| `call watchdog abort: webrtc-ui talk unhealthy` ~45 s after ICE/`live` flap, age_s≈600 | Finding 1 (session or probe) |
| `webrtc-ui CDP unavailable` / `CDP … timed out` / `cdp ghost target` / `/json/close` | Finding 2 |
| `age_s≈600`, `busy=true`, `last_ws_s<2`, `last_uplink_s<2`, `dl_energy≈0` and `openclaw-spk peak≈0` | Finding 3 (silent Talk, WS alive) |
| `websocket downlink send stalled` or `ping send failed` then Pixel `Hub WS failure` / `Hub stream timeout` | Finding 4 |
| e2e.log `heal(a/b/c)` or `systemctl restart talk-chromium` with `call.busy=true` | Finding 5 |
| `force-release CallSlot` / `reaper` / `admin force-release` | Findings 6–7 |
| Pixel `AUDIO-FLOW` REC/TRX going IDLE while hub `last_uplink_s` climbs | Phone send queue / capture |

Keep last 20 min of: hub journal (`gsm2computer-hub`, `talk-chromium`, `openclaw-gateway`, `gsm2computer-openclaw-phone-mic`), `~/gsm2computer-e2e/e2e.log`, call-tap `openclaw.mp3` + `meta.json` peaks, Chromium `chrome://webrtc-internals` dump if DCV is up, `pactl list source-outputs/sink-inputs` snapshot every 30 s during a call.

Alert webhook already fires on watchdog abort — include `age_s`, ICE/DOM snapshot **cached**, and **do not** CDP-attach from the alert path.

Until those ticks exist, treat “~10 min then dead” as **undifferentiated** among findings 1–4.

---

## Suggested order of work (when we are allowed to change runtime)

1. Stop in-call CDP from closing/reloading/creating tabs; slim the health probe.
2. Log `call_tick` + tap energy; do not hang up on energy yet.
3. Measure real Talk session lifetime; set rollover from data, and never abort GSM on rollover failure.
4. Single WS send lock; demote 5 s drain from “kill call” to “log + metric.”
5. E2e mutual exclusion lock; unify `PULSE_SOURCE` / loopback ownership.
