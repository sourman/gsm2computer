# ADR 0006: Hub portal for SMS and call log

- **Status:** accepted
- **Date:** 2026-09-09
- **Tickets:** [SAF-29](https://linear.app/safwatly/issue/SAF-29/hub-sqlite-portal-restsse-api), [SAF-30](https://linear.app/safwatly/issue/SAF-30/pwa-messaging-portal-threads-compose-calls-tab), [SAF-31](https://linear.app/safwatly/issue/SAF-31/pixel-outbound-sms-call-log-sync-to-hub), [SAF-32](https://linear.app/safwatly/issue/SAF-32/tailscale-https-trusted-cert-for-hub-portal) (v1); [SAF-33](https://linear.app/safwatly/issue/SAF-33/system-calllog-backfill-portal-v11) (CallLog backfill); [SAF-34](https://linear.app/safwatly/issue/SAF-34/openclaw-mcp-calls-recent-sms-thread) (OpenClaw MCP)

## Context

The Pixel 7 gateway forwards inbound SMS to the hub and keeps a local call log (max 20 entries, bridge-handled calls only). The operator uses a Redmi as their daily phone and wants to read/reply to SMS and view call history from any device on the Tailscale mesh, without opening the gateway app on the Pixel.

## Decision

1. **Hub** stores messages and calls in SQLite, exposes REST + SSE, serves a PWA at `/portal/`.
2. **Pixel gateway** posts call records on hangup (with full session metadata), polls outbound SMS queue every 10s.
3. **Auth v1**: Tailscale mesh only (no portal bearer token).
4. **Retention**: forever.
5. **Call log v1**: sync bridge `CallLogStore` entries; system Android `CallLog` backfill is a fast follow-up.
6. **HTTPS**: portal v1 stays HTTP on `:8787` ([ADR 0007](0007-hub-machine-name-and-https.md)); trusted `.ts.net` HTTPS is for OpenClaw only. Web Push waits on a non-conflicting HTTPS path.
7. **Outbound SMS**: 10s HTTP poll from `GatewayService`; no separate control WebSocket in v1.

## Call record fields

- `direction`, `number`, `started_at`, `duration_sec`
- `switchboard_mode` at hangup
- `session_id` / call-tap summary when voice bridge was active

## Consequences

- OpenClaw can later query `/portal/calls` and `/portal/messages` or get MCP tools over the same data.
- Anyone on the tailnet can use the portal until per-device auth is added.
- Hub grows a static frontend build step (`hub/portal/` → served by `hub.py`).

## Out of scope (v1)

- MMS, group SMS, carrier delivery receipts
- Per-device portal tokens
- Control WebSocket for instant SMS send
