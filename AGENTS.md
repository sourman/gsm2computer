# Agent notes (gsm2computer)

Architecture decisions live in **[docs/adr/](docs/adr/)**. Read those before changing voice routing, PipeWire, the hub Talk path, or Chromium/CDP.

| Read first | When |
|------------|------|
| [docs/adr/0001-control-ui-webrtc-for-gsm-calls.md](docs/adr/0001-control-ui-webrtc-for-gsm-calls.md) | GSM / simulator / OpenClaw Talk — **not** `talk.session.create` for 2.1-mini |
| [docs/adr/0002-pipewire-buses-and-mix-minus.md](docs/adr/0002-pipewire-buses-and-mix-minus.md) | `pw-cat` / `pw-record`, buses, mix-minus, serial targets |
| [docs/adr/0003-talk-chromium-lifecycle.md](docs/adr/0003-talk-chromium-lifecycle.md) | Talk Chromium restart, CDP, profile, DISPLAY |
| [docs/adr/0004-one-live-call.md](docs/adr/0004-one-live-call.md) | Second GSM/simulator inbound is reject/409; must not tear down the live call |
| [docs/adr/0005-native-pcm-hub-wire.md](docs/adr/0005-native-pcm-hub-wire.md) | Phone PCM at HAL rate; hub 48 kHz. Missing fields = 8 kHz μ-law |
| [hub/TALK_WEBRTC.md](hub/TALK_WEBRTC.md) | Commands, first DCV login, env knobs |

## Hard rules for this repo

- Default Talk mode is **`webrtc-ui`**. Do not auto-fallback to gateway-relay (`relay`) when Chromium or WebRTC fails — fail the handshake.
- Gateway-relay `talk.session.create` with `gpt-realtime-2.1-mini` is **rejected** on OAuth-only OpenAI. That is not a bug to paper over with a model-name swap.
- Bind PipeWire helpers by **object.serial** and wait until linked. Name `--target` can attach the default device while Talk looks healthy.
- `pw-record` stdout on a pipe must stay **unbuffered** (`stdbuf -o0`); 4 KiB stdio is a 128 ms dropout at 8 kHz stereo.
- Dedicated Talk Chromium profile only (`chromium-openclaw-talk`). Do not attach the operator’s daily Chrome for production Talk.
- One live call (ADR 0004). A waiting GSM leg or second hub WebSocket is rejected; it must not hang up the established call or steal the mixer.
- Phone WS audio is native PCM at the HAL rate (ADR 0005). The hub resamples to 48 kHz. Deploy hub and APK together. Simulator omits `format`/`rate` and stays 8 kHz μ-law.
- Do not commit gateway tokens, `#token=` URLs, or CDP ports bound to non-localhost.

Android GSM silencing / priv-app notes: [docs/VOICE_CALL_SILENCING_INVESTIGATION.md](docs/VOICE_CALL_SILENCING_INVESTIGATION.md).
