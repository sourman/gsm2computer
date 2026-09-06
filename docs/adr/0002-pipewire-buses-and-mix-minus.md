# ADR 0002: PipeWire null-sink buses and mix-minus

- **Status:** accepted
- **Date:** 2026-09-05
- **Tickets:** SAF-8, SAF-11, SAF-28

## Context

The hub machine (safwat-eu) has no GSM codec. Call audio arrives on a WebSocket as **PCM s16le at the client rate**, or as **8 kHz μ-law** when `format`/`rate` are omitted (simulator). OpenClaw Control UI Talk (ADR 0001) is a Chromium process that captures a Pulse **source** and plays to a Pulse **sink** at the graph rate (48 kHz). The hub resamples onto that graph (ADR 0005).

If Chromium captured `openclaw_bus.monitor` or `gsm_bus.monitor`, TTS would loop back into the mic (howl / agent talking to itself). Capture must be a **mix-minus**: uplink only, never the Talk downlink.

`pw-cat` / `pw-record` `--target` must be a PipeWire `object.serial`. Pulse names like `openclaw_bus.monitor` are not nodes; a name miss lands on the default device (historically AWS-Virtual-Microphone) while Talk still looks healthy.

`pw-record` writing into a **pipe** uses libc’s 4 KiB FILE buffer. At 8 kHz stereo s16 that is **128 ms** per flush — clippy “packet drops” on the simulator/phone even when the sine through the bus is clean.

## Decision

Null sinks from `hub/setup-audio-bus.sh`:

| Sink | Role |
|------|------|
| `phone_uplink` | Hub plays resampled caller PCM here (webrtc-ui mode) |
| `openclaw_bus` | Chromium speaker; hub records `.monitor` for phone downlink |
| `gsm_bus` | Legacy / switchboard GSM patch; still linked `openclaw_bus → gsm_bus` for the openclaw preset |
| `whatsapp_bus`, `telegram_bus` | Reserved |

**webrtc-ui graph**

```
phone PCM or μ-law WS → hub resample → pw-cat s16 48 kHz → phone_uplink
phone_uplink.monitor → Chromium PULSE_SOURCE (mic)
Chromium PULSE_SINK → openclaw_bus
openclaw_bus.monitor → hub pw-record s16 48 kHz → resample → WS → phone
```

Chromium must **not** capture `gsm_bus.monitor`, `openclaw_bus.monitor`, or `AWS-Virtual-Microphone`. Bind playback/record by serial, wait until linked, fail the handshake on mismatch (`hub/pipewire_target.py`).

Hub helpers run under `stdbuf -o0 -i0`, `--latency 20ms`, and `PIPEWIRE_LATENCY=period/rate`. Downlink pump uses `readexactly(20 ms)`, not short `read()`s.

## Consequences

- Switchboard `openclaw` preset still patches `openclaw_bus` monitor into `gsm_bus` for hardware/GSM injection; WS downlink records `openclaw_bus.monitor` directly.
- Graph clock is 48 kHz-only on this host (ADR 0005). 8 kHz μ-law clients are resampled. Do not “fix” quality by adding a second Talk transport.
- Simulator playout should keep a short jitter buffer; it has no WebRTC jitter buffer of its own.
