# OpenClaw Control UI Talk (WebRTC) — gsm2computer Option C

Phone PCM (or simulator μ-law) is spliced into the **same Chromium Control UI Talk
path** that already sounds right (OAuth + `gpt-realtime-2.1-mini` + WebRTC).
The hub does **not** call `talk.session.create` (gateway-relay) unless
`GSM2COMPUTER_OPENCLAW_TALK=relay`.

## Audio graph

```
phone PCM or μ-law WS → hub resample → pw-cat s16 48 kHz → phone_uplink sink
phone_uplink.monitor → Chromium PULSE_SOURCE (mic)
Chromium PULSE_SINK → openclaw_bus
openclaw_bus.monitor → hub pw-record s16 48 kHz → resample → WS → phone
```

Mix-minus: Chromium must **not** capture `gsm_bus.monitor`, `openclaw_bus.monitor`,
or `AWS-Virtual-Microphone`. TTS would otherwise loop back into the mic.

## Chromium

- Profile: `~/.config/chromium-openclaw-talk`
- Display: GNOME/DCV `DISPLAY=:1`
- CDP: `http://127.0.0.1:9222`
- Env: `PULSE_SOURCE=phone_uplink.monitor PULSE_SINK=openclaw_bus`
- URL: `https://hub.mining-ling.ts.net/chat/main` ([ADR 0007](../docs/adr/0007-hub-machine-name-and-https.md))

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

### Stuck-call watchdog

The GSM WebSocket claims the one-live-call slot (`live_call`, ADR 0004) before
the 101 upgrade and releases it in that handler's `finally`. If the Pixel socket
stays half-open, later dials get HTTP 409 until the hub process restarts.

After `session.updated`, a watchdog aborts the live handler (close WS → existing
cleanup of PipeWire helpers, Talk, taps → then release the lock). It does not
clear the lock while orphans are still running.

| Env | Default | 0 means |
|---|---|---|
| `GSM2COMPUTER_CALL_WATCHDOG` | `1` | `0`/`off` disables all checks |
| `GSM2COMPUTER_CALL_WS_IDLE_S` | `60` | no inbound WS frames (incl. ping/pong) |
| `GSM2COMPUTER_CALL_UPLINK_IDLE_S` | `120` | no `input_audio_buffer.append` |
| `GSM2COMPUTER_CALL_UPLINK_GRACE_S` | `45` | wait this long after `session.updated` before requiring uplink |
| `GSM2COMPUTER_CALL_MAX_S` | `2700` (45 min) | hard cap from slot claim; disable with `0` |
| `GSM2COMPUTER_CALL_PING_S` | `20` | hub-initiated WS ping after the call is established |

`GET /health` includes `call` (`busy`, `age_s`, `last_ws_s`, `last_uplink_s`).

### Outbound alert webhook (optional)

Stuck-call watchdog aborts POST a Grok Bot webhook when both env vars are set.
Unset `GSM2COMPUTER_ALERT_WEBHOOK_URL` is a silent skip. This is **not** the
Pixel SMS outbox (`/sms/outbox`).

| Env | Default | Notes |
|---|---|---|
| `GSM2COMPUTER_ALERT_WEBHOOK_URL` | unset | Public HTTPS webhook URL. Empty = no-op |
| `GSM2COMPUTER_ALERT_WEBHOOK_KEY` | unset | Sender key; sent as `Authorization: Bearer <key>` |

Cup (file-copy deploy) — systemd user drop-in, then reload:

```
~/.config/systemd/user/gsm2computer-hub.service.d/alert-webhook.conf
```

Example: [docs/alert-webhook.conf.example](../docs/alert-webhook.conf.example).
`systemctl --user daemon-reload && systemctl --user restart gsm2computer-hub`

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

## OpenClaw e2e self-test

`hub/openclaw_e2e.py` injects known TTS over `/e2e-test` (Pixel-shaped PCM
WebSocket). Pass requires an *unforced* OpenClaw reply (no `response.create`),
non-silent `openclaw-spk` + `gsm-downlink`, and a non-empty transcript.

- systemd: `gsm2computer-openclaw-e2e.timer` (every 30 min) + oneshot service
- Hub also starts the oneshot after a real call &gt;10s with silent reply peaks
- `/e2e-test` is preempted by a real Pixel path so Safwat never sees line-busy
  from a self-test (exception to ADR 0004 for synthetic holders only)
- Logs: `~/gsm2computer-e2e/e2e.log` (rotated); taps under `~/gsm2computer-e2e-calls/`
