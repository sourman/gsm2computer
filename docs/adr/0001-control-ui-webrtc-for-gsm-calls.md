# ADR 0001: GSM OpenClaw voice uses Control UI WebRTC

- **Status:** accepted
- **Date:** 2026-09-05
- **Ticket:** SAF-11

## Context

Two OpenClaw Talk transports exist:

1. **Control UI Talk** — browser WebRTC + ChatGPT OAuth + `gpt-realtime-2.1-mini`. This is the path that already sounded right in a human browser.
2. **Gateway-relay** — `talk.session.create` / `appendAudio` over the OpenClaw gateway WebSocket (`openclaw_talk_bridge.py`). PCM at 24 kHz, then PipeWire → 8 kHz μ-law for the phone.

The phone and call simulator speak **8 kHz G.711 μ-law** on a WebSocket to `hub.py` (Layer 3, same protocol as the Android bridge). They cannot do WebRTC themselves.

Gateway-relay **rejects** `gpt-realtime-2.1-mini` when OpenAI on the gateway is OAuth-only:

`Realtime voice provider "openai" is not configured`

GA realtime on that RPC wants a Platform API key. OAuth only unlocks **GPT-Live** (`gpt-live-1-codex`) on gateway-relay. GPT-Live then hit Plus `usage_limit_reached` (429) and the hub used to recreate sessions in a tight loop.

Flipping the relay default model to 2.1-mini does **not** fix the rejection. The hub still gets `is not configured` and must not silently pretend otherwise.

## Decision

Default `GSM2COMPUTER_OPENCLAW_TALK=webrtc-ui`.

On each GSM/simulator call the hub drives the **same Control UI Talk button** in a dedicated Chromium via CDP (`hub/talk_chromium.py`). The phone is spliced in as Chromium’s Pulse mic/speaker through PipeWire (ADR 0002). The model in that session is whatever Control UI Talk already uses (`gpt-realtime-2.1-mini`).

`GSM2COMPUTER_OPENCLAW_TALK=relay` is an **explicit** escape hatch to `OpenClawTalkBridge`. There is **no automatic fallback** from webrtc-ui to relay. If Chromium/Talk/WebRTC is not up, the hub **fails the call handshake**.

## Consequences

- Phone/simulator audio quality is bounded by 8 kHz μ-law plus the WebRTC path, not by gateway-relay PCM framing.
- A logged-in Chromium profile and a graphical session (`DISPLAY=:1`) are now part of the production Talk stack (ADR 0003).
- Control UI Talk and GSM Talk share one OpenClaw session surface; do not “fix” GSM by calling `talk.session.create` for 2.1-mini unless a Platform API key is actually configured.
- Loopback (`/loopback`) stays μ-law echo with no OpenClaw.
