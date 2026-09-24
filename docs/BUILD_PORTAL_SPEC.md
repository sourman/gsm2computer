# Build spec: Hub messaging portal (PWA)

Handoff doc for build / review / smoke / QA agents.

Linear (ADR 0006):

| Ticket | What |
|--------|------|
| [SAF-29](https://linear.app/safwatly/issue/SAF-29/hub-sqlite-portal-restsse-api) | Hub SQLite + portal REST/SSE API |
| [SAF-30](https://linear.app/safwatly/issue/SAF-30/pwa-messaging-portal-threads-compose-calls-tab) | PWA (threads, compose, calls tab) |
| [SAF-31](https://linear.app/safwatly/issue/SAF-31/pixel-outbound-sms-call-log-sync-to-hub) | Pixel outbound SMS + call log sync |
| [SAF-32](https://linear.app/safwatly/issue/SAF-32/tailscale-https-trusted-cert-for-hub-portal) | Tailscale HTTPS / trusted cert |
| [SAF-33](https://linear.app/safwatly/issue/SAF-33/system-calllog-backfill-portal-v11) | System CallLog backfill (v1.1) |
| [SAF-34](https://linear.app/safwatly/issue/SAF-34/openclaw-mcp-calls-recent-sms-thread) | OpenClaw MCP `calls_recent` / `sms_thread` (future) |

Inbound SMS forwarder is already shipped: [SAF-18](https://linear.app/safwatly/issue/SAF-18/android-sms-forwarder-to-hub-control-api). Routing commands stay [SAF-12](https://linear.app/safwatly/issue/SAF-12/sms-routing-control-plane-deterministic).

## Locked decisions

| Topic | Choice |
|-------|--------|
| Call log v1 | Bridge `CallLogStore` → hub; system `CallLog` backfill deferred |
| Outbound SMS | 10s HTTP poll from `GatewayService` |
| Auth | Tailscale only (no portal token v1) |
| Retention | Forever (SQLite) |
| Call metadata | Full: direction, number, time, duration, switchboard_mode, session/tap summary |
| HTTPS | Portal v1: HTTP `http://hub.mining-ling.ts.net:8787/portal/` ([ADR 0007](adr/0007-hub-machine-name-and-https.md)); trusted `.ts.net` is OpenClaw only |
| PWA | Installable; SSE while open; Web Push after HTTPS works |

## Hub machine

- Tailscale bind IP: `100.101.181.110:8787` (hub process listens here)
- MagicDNS: `hub.mining-ling.ts.net` (HTTPS via OpenClaw Serve — Talk/Control UI only, not `:8787`)
- Portal / Pixel default: `http://hub.mining-ling.ts.net:8787` (no nested `portal.hub…`; see [TAILSCALE_PORTAL_HTTPS.md](TAILSCALE_PORTAL_HTTPS.md))
- Service: `hub/gsm2computer-hub.service`, working dir `~/gsm2computer-hub`
- Repo hub code: `hub/hub.py`

## API surface (implement)

```
GET  /portal/              → PWA static files
GET  /portal/api/threads           → [{peer, lastBody, lastAt, unread?}]
GET  /portal/api/messages?peer=+1  → thread messages
POST /portal/api/messages/send     → {to, body} queue outbound
GET  /portal/api/calls             → call history
GET  /portal/api/events            → SSE stream (new message, new call)
```

Existing `POST /sms` from Pixel: **persist** to SQLite in addition to current logging/routing.

## SQLite schema (suggested)

```sql
messages(id TEXT PK, direction TEXT, peer TEXT, body TEXT, ts TEXT, status TEXT)
calls(id TEXT PK, direction TEXT, number TEXT, started_at TEXT, duration_sec INT,
      switchboard_mode TEXT, session_id TEXT, tap_summary TEXT)
outbox(id TEXT PK, to_number TEXT, body TEXT, created_at TEXT, status TEXT)
```

Normalize phone numbers to E.164 where possible.

## Pixel gateway changes

1. `SEND_SMS` in manifest + Magisk `service.sh` grant
2. On call end in `GatewayService` / `CallOrchestrator`: `POST /calls` with full metadata
3. Background poll every 10s: `GET /sms/outbox` (or agreed path), send via `SmsManager`, `POST` ack
4. Do not break existing `POST /sms` inbound forward or `STATUS`/`MODE` routing

## PWA (`hub/portal/`)

- Vite + vanilla or minimal framework
- Tabs or nav: **Messages** (thread list + conversation), **Calls** (history)
- Compose bar in thread view
- `manifest.json`, service worker (installable)
- Subscribe to SSE for live updates when tab open
- Mobile-friendly layout

## Tailscale HTTPS

See [ADR 0007](adr/0007-hub-machine-name-and-https.md) and [TAILSCALE_PORTAL_HTTPS.md](TAILSCALE_PORTAL_HTTPS.md).

- **Do not** `tailscale serve` `/` to the gsm2computer hub (breaks OpenClaw Talk).
- **Do not** use `portal.hub.mining-ling.ts.net` (not MagicDNS; no trusted cert).
- Portal v1: HTTP on `:8787`. `:8443` red lock is NICE DCV, unrelated.
- Future trusted portal HTTPS needs a separate design (e.g. second Tailscale node), not Serve on OpenClaw’s `:443`.

## Tests

### Smoke (automated, no browser)

- Hub unit/integration: POST /sms persists, GET threads, POST send queues outbox
- Python tests in `hub/tests/` if pattern exists, else add minimal pytest
- `HubEndpointsTest` style tests for any new Kotlin URL helpers

### QA (headed chad-browser)

```bash
chad-browser up --name gsm2portal-qa http://hub.mining-ling.ts.net:8787/portal/
# or https://hub.mining-ling.ts.net/portal/ once portal HTTPS exists (OpenClaw owns :443 today)
```

Drive: load portal, verify empty state or seed data, simulate message list UI, compose send (may need hub seed or mock outbox).

Use `chad-browser down gsm2portal-qa` only if you launched it.

## Hard rules (repo)

- Do not commit gateway tokens or `#token=` URLs
- Default Talk mode stays `webrtc-ui`; do not change voice routing ADRs
- One live call (ADR 0004) — portal work must not break call handshake
- Deploy hub and APK together when changing wire protocol

## Notify human

If blocked on hub SSH, Tailscale serve config, Pixel ADB, or credentials:

```bash
/home/ahmed/.agents/skills/qol/qol.py "Blocked on portal build — need your input."
```

## Review checklist

- [ ] SQLite migrations/idempotent init
- [ ] POST /sms still routes STATUS/MODE
- [ ] Outbox poll does not block audio WebSocket thread
- [ ] PWA works on mobile viewport
- [ ] No secrets in git
- [ ] ADR 0006 matches implementation
