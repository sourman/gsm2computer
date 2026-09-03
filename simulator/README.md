# GSM Hub Call Simulator

Local dev web app that simulates the Android app's Layer 3 μ-law WebSocket call to the gsm2computer hub (`HubStreamClient.kt`).

## Quick start

```bash
cd simulator
npm install
npm run dev
```

Open **http://localhost:5173** in Chrome or Firefox.

## Usage

1. Leave the default hub URL (`http://100.101.181.110:8787`) or point at another hub.
2. **Hub loopback** (default): connects to `ws://…/loopback` — hub echoes μ-law without OpenClaw. Use with test tone or mic to sanity-check the wire.
3. Uncheck loopback for the OpenClaw path (downlink is agent speech on `openclaw_bus`, not echo).
4. Click **Start call** — mic permission when not using test tone; fetches `/token`, opens WebSocket, waits for `session.updated`.
5. Level meters show mic, tone uplink, hub downlink, and local sidetone activity.
6. Click **End call** to close cleanly.

The event log shows WS status, `session.updated`, errors, and byte counters.

## Protocol (matches Android)

| Step | Action |
|------|--------|
| 1 | `POST {hub}/token` (empty body) → `{ value, model, ... }` |
| 2 | WebSocket to `ws://{host}:{port}` or `ws://{host}:{port}/loopback` |
| 3 | Hub sends `session.updated` |
| 4 | Uplink: `{"type":"input_audio_buffer.append","audio":"<base64 μ-law 8kHz mono>"}` every ~20 ms |
| 5 | Downlink: `{"type":"response.output_audio.delta","delta":"<base64 μ-law>"}` |
| 6 | Hangup: WebSocket close 1000 |

## Headless validation

```bash
npm run validate
node scripts/validate-ws.mjs http://100.101.181.110:8787 --loopback
```

Checks `/health`, `/token`, WebSocket + `session.updated`, and sends 2 s of synthetic 440 Hz μ-law tone.

## Notes

- μ-law is fine for speech; pure tones may show clipping/artifacts from G.711 encoding.
- Loopback uses hub WebSocket echo (not PipeWire monitor) for reliable round-trip testing.

## CORS / proxy notes

- **WebSocket** connects directly from the browser to the hub host (cross-origin WS is allowed).
- **POST /token** from `localhost` would hit CORS on the hub. In dev, Vite middleware proxies `POST /proxy/token?hub=...` → `{hub}/token`.
- The browser WebSocket API cannot set `Authorization`; the hub accepts connections without it (same as a bare WS upgrade).
- For a non-default hub in dev, pass the URL in the UI — the proxy uses the `hub` query param.

## Manual browser testing

Requires a real mic and speaker:

- Confirm mic permission prompt appears.
- Speak after `session.updated`; watch bytes sent increment.
- If OpenClaw/switchboard is routed on the hub, you should hear TTS downlink and see bytes received grow.

## Files

```
simulator/
  index.html          UI shell
  src/main.ts         App wiring
  src/hub-client.ts   Token + WebSocket protocol
  src/pcmu.ts         μ-law codec + resampling
  src/audio-capture.ts  Mic → 8 kHz μ-law
  src/audio-playback.ts Downlink player
  scripts/validate-ws.mjs  Node WS smoke test
```
