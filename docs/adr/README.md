# Architecture decision records

Read these before changing voice routing, PipeWire, or the OpenClaw Talk path.

| ADR | Decision |
|-----|----------|
| [0001](0001-control-ui-webrtc-for-gsm-calls.md) | GSM/simulator calls use Control UI Talk (WebRTC), not `talk.session.create` |
| [0002](0002-pipewire-buses-and-mix-minus.md) | Null-sink buses; Chromium mic is `phone_uplink.monitor`, speaker is `openclaw_bus` |
| [0003](0003-talk-chromium-lifecycle.md) | Dedicated Chromium + CDP; relaunch on demand; login lives in the profile |
| [0004](0004-one-live-call.md) | One live GSM/hub call; waiting legs are rejected, not conferenced |
| [0005](0005-native-pcm-hub-wire.md) | Phone sends native PCM; hub resamples to 48 kHz. Missing fields stay 8 kHz μ-law |

Operational runbook (commands, first login): [`hub/TALK_WEBRTC.md`](../../hub/TALK_WEBRTC.md).
