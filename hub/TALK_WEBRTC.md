# OpenClaw Control UI Talk (WebRTC) — gsm2computer Option C

Phone/simulator μ-law is spliced into the **same Chromium Control UI Talk
path** that already sounds right (OAuth + `gpt-realtime-2.1-mini` + WebRTC).
The hub does **not** call `talk.session.create` (gateway-relay) unless
`GSM2COMPUTER_OPENCLAW_TALK=relay`.

## Audio graph

```
phone μ-law WS → hub pw-cat → phone_uplink sink
phone_uplink.monitor → Chromium PULSE_SOURCE (mic)
Chromium PULSE_SINK → openclaw_bus
openclaw_bus.monitor → hub pw-record → μ-law WS → phone
```

Mix-minus: Chromium must **not** capture `gsm_bus.monitor`, `openclaw_bus.monitor`,
or `AWS-Virtual-Microphone`. TTS would otherwise loop back into the mic.

## Chromium

- Profile: `~/.config/chromium-openclaw-talk`
- Display: GNOME/DCV `DISPLAY=:1`
- CDP: `http://127.0.0.1:9222`
- Env: `PULSE_SOURCE=phone_uplink.monitor PULSE_SINK=openclaw_bus`
- URL: `https://ip-172-31-21-244.mining-ling.ts.net/chat/main`

On GSM/simulator WebSocket connect the hub starts Talk; on disconnect it
stops Talk. If Talk/WebRTC is not up, the hub **fails the call handshake**.
There is no silent fallback to gateway-relay.

## First login (once per talk profile)

If `/health` or hub logs say the Talk button is missing, or OpenClaw asks
to pair the browser:

1. Connect to safwat-eu with DCV (DISPLAY `:1`).
2. Start the talk browser: `scripts/start-talk-chromium.sh` (tmux session
   `gsm2computer-option-c` is fine).
3. In that window, complete Control UI login / device pairing
   (`openclaw devices` on the host if prompted).
4. Confirm the chat composer shows the Talk (mic) button.
5. Leave Chromium running. The hub drives Talk via CDP after that.

The supervisor also appends `#token=…` from `~/.openclaw/openclaw.json`
when navigating. Do not put that token in git or systemd unit files.

## Hub env

| Value | Behavior |
|---|---|
| `webrtc-ui` (default, also legacy `1`/`true`) | Control UI Talk |
| `relay` | Legacy `OpenClawTalkBridge` / `talk.session.create` |
| `off` / `0` | No OpenClaw; PipeWire gsm_bus only |

## Commands

```bash
# sinks (includes phone_uplink)
./setup-audio-bus.sh
pactl list sinks short | grep phone_uplink
pactl list sources short | grep phone_uplink

# long-running Chromium (tmux gsm2computer-option-c)
./scripts/start-talk-chromium.sh

# CDP health
python3 hub/talk_chromium.py health
```
