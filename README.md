# GSM2Computer

A **phone-only computer** setup: you call your machine and talk to it. No SSH, no remote desktop, no browser UI — the phone call is the only interface.

The computer runs headless on the internet. A rooted Android phone with a SIM acts as the GSM audio bridge: when you dial it, call audio streams to the hub over WebSocket and hub audio streams back. Everything you do with the computer — agents, automation, messaging, whatever runs on the box — happens through that voice link.

## Vision

```
You ──GSM call──► Bridge phone ──WebSocket/μ-law──► Your computer (hub)
                      ▲                                    │
                      └──────── voice responses ───────────┘
```

- **Phone-only access** — the owner has zero direct access to the computer except via phone calls.
- **Headless hub** — the machine does the work; you never log in remotely.
- **Dial to interact** — no apps, VPNs, or terminals on your daily driver; just call the number.

This repo is the bridge half of that stack today. The computer-side hub (voice agent, routing, session handling) is planned here as the other half.

## What exists today (v0.1)

The Android bridge:

- Answers GSM calls as the default phone app
- Captures caller audio via privileged `AudioRecord` (Magisk system priv-app)
- Opens a μ-law WebSocket to the hub (Tailscale default `http://100.101.181.110:8787`)
- Injects hub audio back into the GSM uplink
- Forwards inbound SMS as JSON to `{hub}/sms` (phone does not parse commands)

The optional `hub-token-worker/` Cloudflare Worker mints short-lived stream tokens so the phone never holds your real API key. The full hub server — the brain of the phone-only computer — is not implemented yet. Protocol for v0.1 is **WebSocket with μ-law audio** (proven on Pixel 7 in upstream). RTP/UDP may follow for restrictive networks.

## Lineage

Derived from [pulpoff/gsm2sip](https://github.com/pulpoff/gsm2sip), the original Android GSM-SIP gateway. SIP signalling, SignalWire tooling, and Twilio test rig were removed in this fork. See `NOTICE`.

Licensed under MIT — see `LICENSE`.

## Requirements

- Rooted Android phone (Magisk) with an unlockable bootloader
- SIM with voice service
- Set as **default phone app**
- Hub stream URL configured (Settings → hub control URL, or `scripts/configure-bridge.sh`). Default: `http://100.101.181.110:8787` on Tailscale.

Tested device profiles from upstream: Pixel 7, Samsung S10e, Qualcomm generic, etc.

## Build & install

```bash
sudo ./setup.sh          # once: JDK + Android SDK
./build.sh release       # → gateway.apk + gateway-magisk.zip
./deploy.sh --reboot     # first install (Magisk module + priv-app)
```

Configure the hub URL on-device or via adb (defaults to the Tailscale hub):

```bash
HUB_CONTROL_URL=http://100.101.181.110:8787 \
  ./scripts/configure-bridge.sh --force -s <serial>
```

`STREAM_TOKEN_URL` is optional and defaults to `{HUB_CONTROL_URL}/token`. OpenAI `STREAM_MODEL` / `STREAM_VOICE` are unused in hub mode.

Then open the app once (or let boot autostart) so the foreground-service mic capability is established.

## SMS forwarder

Inbound SMS is posted as `{from, body, receivedAt}` to `{HUB_CONTROL_URL}/sms`. The phone does not parse commands; the hub does.

Manual test:

1. Hub control URL set (default `http://100.101.181.110:8787`). Magisk grants `RECEIVE_SMS` on boot.
2. Send an SMS to the gateway SIM (or emulator: `adb emu sms send +15551212 hello`).
3. App log should show `SMS forwarded from …` or `SMS forward failed: …`.
4. On the hub, confirm `POST /sms` received the JSON. Hub `/health` should already be up (SAF-15).

## Development

```bash
./build.sh
./deploy.sh              # hot-swap APK on rooted device
```

Magisk module id: `gsm2computer-bridge`  
Package: `com.gsm2computer.bridge`
