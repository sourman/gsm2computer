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
- Opens a μ-law WebSocket to the hub (Tailscale default `http://hub.mining-ling.ts.net:8787`)
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
- Hub stream URL configured (Settings → hub control URL, or `scripts/configure-bridge.sh`). Default: `http://hub.mining-ling.ts.net:8787` on Tailscale.

Tested device profiles from upstream: Pixel 7, Samsung S10e, Qualcomm generic, etc.

## Build & install

```bash
sudo ./setup.sh          # once: JDK + Android SDK
./build.sh release       # → gateway.apk + gateway-magisk.zip
./deploy.sh --reboot     # first install (Magisk module + priv-app)
```

Configure the hub URL on-device or via adb (defaults to the Tailscale hub):

```bash
HUB_CONTROL_URL=http://hub.mining-ling.ts.net:8787 \
  ./scripts/configure-bridge.sh --force -s <serial>
```

`STREAM_TOKEN_URL` is optional and defaults to `{HUB_CONTROL_URL}/token`. OpenAI `STREAM_MODEL` / `STREAM_VOICE` are unused in hub mode.

Then open the app once (or let boot autostart) so the foreground-service mic capability is established.

## SMS forwarder

Inbound SMS is posted as `{from, body, receivedAt}` to `{HUB_CONTROL_URL}/sms`. The phone does not parse commands; the hub does.

Manual test:

1. Hub control URL set (default `http://hub.mining-ling.ts.net:8787`). Magisk grants `RECEIVE_SMS` on boot.
2. Send an SMS to the gateway SIM (or emulator: `adb emu sms send +15551212 hello`).
3. App log should show `SMS forwarded from …` or `SMS forward failed: …`.
4. On the hub, confirm `POST /sms` received the JSON. Hub `/health` should already be up (SAF-15).

## Call-drop diagnostics

Mid-call Tailscale or Wi-Fi dips used to hang up the GSM call, because any websocket failure called `Call.disconnect()`. The phone now holds the GSM call and retries the hub for `hub_link_grace_ms` (default **55s**, `0` disables the hold). The hub keeps the same call slot, PipeWire bridge, and Talk session for `GSM2COMPUTER_CALL_RELINK_GRACE_S` (default **65s**) and accepts a reconnect that sends `X-Gsm-Call-Session` with the id from `session.updated`. A different client still gets HTTP 409. A websocket close `1000`/`1001` is a hangup and ends the call. The caller hears silence during the gap.

Clocks in these logs are **UTC**.

### Pixel log

Written only while a call is up, about once a second, plus an immediate line for every websocket and teardown event. Rotated at 2 MB, five files kept. Survives reboot. The sampler never runs on the audio threads; if it fails, the call continues.

```
/storage/emulated/0/Android/data/com.gsm2computer.bridge/files/call-trace/call-trace.jsonl
/storage/emulated/0/Android/data/com.gsm2computer.bridge/files/call-trace/call-trace.1.jsonl
… call-trace.4.jsonl
```

`adb pull` that path after `adb root` (the gateway Pixel is rooted). If pull is denied:

```bash
./scripts/pull-pixel-call-trace.sh ./pixel-call-trace
# or
adb shell su -c 'cat /storage/emulated/0/Android/data/com.gsm2computer.bridge/files/call-trace/call-trace.jsonl'
```

Every line is one JSON object. Common fields: `ts` (UTC, `...Z`), `kind`.

| kind | when | fields |
|---|---|---|
| `call_start` | call begins | `grace_ms`, `call_state` |
| `sample` | ~1 Hz | fields below |
| `net_event` | network callback | `callback` (`onAvailable` / `onLost` / `onCapabilitiesChanged`), `network`, `transports` |
| `ws_open` | socket up | `session_id`, `http` |
| `ws_failure` | connect or read failed | `code`, `message`, `exception` |
| `ws_closed` | peer close, or local `stop` | `code`, `reason`, `local` |
| `reconnect` | retry scheduled | `attempt`, `delay_ms`, `message`, `session_id` |
| `ws_give_up` | grace expired or clean close | `message`, `session_id` |
| `teardown` | GSM call is released | `reason`, `end` (`local` / `remote` / `unknown`), `disconnect_cause`, `telecom_state`, `call_state` |

`sample` fields (absent when that probe could not run):

- `call_state` — orchestrator (`BRIDGED`, `LINK_HOLD`, …)
- `telecom_state` — `RINGING`, `ACTIVE`, `DISCONNECTED`, …
- `disconnect_cause` — telecom cause name, plus reason when present (`REMOTE`, `LOCAL:…`)
- `ws_state` — `connecting`, `open`, `hold`, `closed`
- `ws_rtt_ms` — last `client.ping` / `client.pong` round trip
- `ws_session_id`
- `network_transports` — `WIFI`, `CELLULAR`, `VPN`, joined with `+`
- `vpn_active`, `network_id`
- `probe_iface`, `probe_gateway` — non-VPN interface and its default gateway
- `cell_rat` (`2G`/`3G`/`LTE`/`NR`), `cell_dbm`, `cell_level`
- `wifi_rssi`, `wifi_bssid`, `wifi_link_mbps`
- `wifi_tx_packets`, `wifi_rx_packets`, `wifi_tx_errors`, `wifi_rx_errors`, `wifi_tx_retries` (sysfs, retries only if the driver exposes them)
- `wifi_tx_delta`, `wifi_rx_delta`, `wifi_tx_err_delta` — since the previous sample
- `probe_gateway_ok`, `probe_gateway_ms`, `probe_gateway_error` — ICMP to the gateway, bound to `probe_iface`
- `probe_public_ok`, `probe_public_ms`, `probe_public_error`, `probe_public_target` — ICMP to `1.1.1.1`, bound to the same interface (does not go through Tailscale)
- `probe_hub_ok`, `probe_hub_ms`, `probe_hub_error`, `probe_hub_target` — TCP connect to the hub control URL on the default route (the tailnet path)

The three probes fail independently: gateway down means Wi-Fi/AP, public down means WAN, hub down means Tailscale or the hub.

Grace period on the phone (milliseconds, `0` = hang up on the first failure). The key is `hub_link_grace_ms` in the `gsm2computer` shared preferences. Default is 55000. Read it; do not replace the prefs file:

```bash
adb shell su -c 'grep hub_link_grace_ms /data/data/com.gsm2computer.bridge/shared_prefs/gsm2computer.xml'
```

### Hub log

Journal timestamps are UTC (`YYYY-MM-DDTHH:MM:SS.mmmZ`). A dropped call and a normal hangup are different lines:

- `ws close session=… code=1000 reason=call ended initiator=peer clean=True` — the phone hung up
- `ws close … initiator=reset` or `initiator=eof` with `clean=False`, then `link gap open` — the path died and the hub is holding the call
- `relinked session=…` — the same call attached again
- `link gap expired` — nobody came back; the slot is released
- `ws pong rtt_ms=…` — hub websocket ping/pong RTT
- `call path session=… mode=direct|relay|unknown relay=… addr=… ss=…` — every 5s while the call is up. `mode=direct` means Tailscale `CurAddr` is set; `relay` means DERP only. `ss` is `ss -tin` (`rtt_ms`, `rto_ms`, `bytes_retrans`, `unacked`) when `ss` is installed

`/health` `call` also carries `session_id`, `link_gap_open`, `link_gap_s`, `link_gap_max_s`, `link_gap_count`, `ws_rtt_ms`, `ts_path`, `ts_relay`, `ts_addr`, `last_close_code`, `last_close_initiator`, `last_close_reason`.

The same events are appended, restart-safe, to:

```
~/gsm2computer-call-end-watch/link-gaps.jsonl
```

Override with `GSM2COMPUTER_LINK_GAP_LOG`. Rotated at 2 MB, three files (`link-gaps.jsonl`, `.1`, `.2`). Each line: `ts`, `kind` (`gap_open`, `relink`, `gap_expired`, `close`, `drop`), `session_id`, `close_code`, `close_reason`, `initiator`, `detail`.

They are also inserted into the portal SQLite table `call_link_events` (same database as `calls`, default `portal.sqlite` next to the hub). `call_end_watch` copies the JSONL tail into the `call_ended` payload as `link_gaps`, and treats an open `link_gap_s` / `link_gap_max_s` on `/health` as a mid-call gap.

`GSM2COMPUTER_CALL_RELINK_GRACE_S=0` disables the hold (first dead socket ends the call, which is the old behavior).

## Development

```bash
./build.sh
./deploy.sh              # hot-swap APK on rooted device
```

Magisk module id: `gsm2computer-bridge`  
Package: `com.gsm2computer.bridge`
