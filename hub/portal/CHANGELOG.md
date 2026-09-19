# Hub portal changelog

## 0.1.0 — SMS desk alerts + open latest

Operator PWA at `/portal/` (Messaging + Calls). Live updates use EventSource `GET /portal/api/events` (`connectEvents` in `src/api.js`).

### Desk alerts

- **Secure context (HTTPS / localhost):** `Enable desk alerts` asks for Notification permission once. New inbound SMS (and outbound-ack / `sent`) shows an OS notification via the service worker when possible, else `new Notification`.
- **Insecure context (typical Tailscale MagicDNS `http://…:8787/portal/`):** the Notification API is unavailable. The desk shows an in-app toast and an unread badge on Messaging. It does not pretend Web Push works over HTTP.
- Background PWA alerts need a **trusted HTTPS** origin (Tailscale Serve / `.ts.net` cert). Service workers and Web Push are secure-context only; HTTP install will not receive OS notifications after the tab is gone.

### Open latest

Navigating to Messaging with no `peer` selected opens the thread with the newest `lastAt`. The message scroller sets `scrollTop = scrollHeight` after render and after live events.
