# ADR 0003: Talk Chromium CDP lifecycle

- **Status:** accepted
- **Date:** 2026-09-05
- **Ticket:** SAF-11

## Context

ADR 0001 requires a real Chromium Control UI (OAuth cookies, Talk button, WebRTC). That cannot be a throwaway headless profile per call: OpenClaw device pairing and ChatGPT login are one-time and painful.

The hub already speaks CDP on call start/stop. Chromium must survive hub restarts and come back after a crash or reboot without a human clicking Allow on every call.

## Decision

One **long-lived** Chromium:

- Profile: `~/.config/chromium-openclaw-talk` (login + cookies persist here)
- CDP: `127.0.0.1:9222` only
- Display: GNOME/DCV `DISPLAY=:1` (`--ozone-platform=x11`)
- Flags include `--use-fake-ui-for-media-stream` and `--autoplay-policy=no-user-gesture-required` so getUserMedia/Talk do not prompt
- Pulse env: `PULSE_SOURCE=phone_uplink.monitor`, `PULSE_SINK=openclaw_bus`

**Who starts it**

1. User systemd `talk-chromium.service` (`Restart=on-failure`) via `start-talk-chromium.sh`, and/or
2. Hub `OpenClawTalkUI.ensure_browser()` if CDP is down when a call arrives (`start_new_session=True` so the browser is not a hub child)

If CDP is already listening, the hub **reuses** that browser and does not spawn a second one.

**What a call does**

- Connect: `start_audio()` (ensure browser + Control UI + Talk button present) → PipeWire bridge → `start_talk()` (CDP click Talk, wait for RTCPeerConnection, bind Pulse ports).
- Disconnect: CDP click to **stop Talk only**. Chromium stays up.

First-time pairing still needs a human on DCV if the Talk button is missing (`hub/TALK_WEBRTC.md`). The hub fails the handshake in that case; it does not fall back to relay.

## Restart behavior

| Event | Chromium process | Login | Talk session |
|-------|------------------|-------|----------------|
| Call ends | Stays | Stays | Stopped via CDP |
| Hub process restart | Stays (not a hub child; systemd unit separate) | Stays | Stopped if a call was up |
| Chromium crash | systemd `Restart=on-failure`, or hub relaunches on next call | Stays (profile on disk) | Must start Talk again on next call |
| Machine reboot | Unit starts after `graphical-session.target` if lingering/user systemd is on | Stays if profile intact | Off until next call |
| DCV/GNOME `:1` down | Launch fails until DISPLAY exists | Profile still on disk | Handshake fails |

`ensure_browser()` does **not** kill a foreign process on `:9222`. If that port is a different Chrome, Talk will mis-drive it — keep 9222 exclusive to this profile.

## Consequences

- Production Talk depends on a graphical session, not a pure SSH box.
- Do not point this Chromium at the operator’s daily Chrome profile; it is a dedicated Talk appliance.
- CDP is localhost-only; do not expose `:9222` on Tailscale/public interfaces.
