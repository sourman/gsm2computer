# GSM2Computer

Turn a rooted Android phone with a SIM into a **GSM audio bridge**. When someone calls the phone, the app captures the call audio and streams it to a **hub machine on the internet** over WebSocket. The hub (not implemented here yet) can route that audio to WhatsApp Web, AI agents, or anything else you run on a desktop/server.

No SSH required for callers — they just dial the phone number.

## Architecture (v0.1)

```
Caller ──GSM──► Bridge phone (this app) ──WebSocket/μ-law──► Hub computer
                     ▲                           │
                     └──── agent audio ──────────┘
```

The Android side:

- Answers GSM calls as the default phone app
- Captures caller audio via privileged `AudioRecord` (Magisk system priv-app)
- Opens a WebSocket to your hub's token endpoint
- Injects hub audio back into the GSM uplink

The optional `hub-token-worker/` Cloudflare Worker mints short-lived stream tokens so the phone never holds your real API key. Replace it with your own hub server when ready.

## Lineage

Forked from [rmeehub/gsm-sip-gateway](https://github.com/rmeehub/gsm-sip-gateway). SIP signalling, SignalWire tooling, and Twilio test rig were removed. See `NOTICE`.

Licensed under MIT — see `LICENSE`.

## Requirements

- Rooted Android phone (Magisk) with an unlockable bootloader
- SIM with voice service
- Set as **default phone app**
- Hub stream URL configured (Settings → hub token URL, or `scripts/configure-bridge.sh`)

Tested device profiles from upstream: Pixel 7, Samsung S10e, Qualcomm generic, etc.

## Build & install

```bash
sudo ./setup.sh          # once: JDK + Android SDK
./build.sh release       # → gateway.apk + gateway-magisk.zip
./deploy.sh --reboot     # first install (Magisk module + priv-app)
```

Configure the hub URL on-device or via adb:

```bash
STREAM_TOKEN_URL=https://your-hub.example/token \
  ./scripts/configure-bridge.sh --force -s <serial>
```

Then open the app once (or let boot autostart) so the foreground-service mic capability is established.

## Hub server (future)

This repo ships only the **phone bridge**. The computer-side hub — accepting the stream, bridging to WhatsApp Web, mixing AI — is planned separately. Protocol choice for v0.1 is **WebSocket with μ-law audio** (same path proven on Pixel 7 in upstream). RTP/UDP may follow for resilience in restrictive networks.

## Development

```bash
./build.sh
./deploy.sh              # hot-swap APK on rooted device
```

Magisk module id: `gsm2computer-bridge`  
Package: `com.gsm2computer.bridge`
