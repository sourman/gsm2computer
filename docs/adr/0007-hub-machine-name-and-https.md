# ADR 0007: Hub machine name `hub` and HTTPS naming limits

- **Status:** accepted
- **Date:** 2026-09-09
- **Tickets:** [SAF-32](https://linear.app/safwatly/issue/SAF-32/tailscale-https-trusted-cert-for-hub-portal) — Tailscale HTTPS / trusted cert for the hub portal PWA

## Context

The hub host runs gsm2computer (`:8787`), OpenClaw Control UI Talk (Tailscale Serve on `:443`), and unrelated services (NICE DCV on `:8443`). Operators want **service URLs on hostnames**, not raw ports, and asked for `portal.hub.mining-ling.ts.net` nested under the hub machine.

Tailscale MagicDNS issues one FQDN per machine: `<machine>.<tailnet>.ts.net`. Nested labels under a machine name (`portal.hub.mining-ling.ts.net`) are **not** a first-class Tailscale feature ([FR #1543](https://github.com/tailscale/tailscale/issues/1543)). Tailscale/Let’s Encrypt will not mint a trusted cert for that name. Chrome’s red lock on `:8443` is DCV, not the hub.

OpenClaw already owns `https://…mining-ling.ts.net/` via `gateway.tailscale.mode=serve`. Pointing Tailscale Serve `/` at the gsm2computer hub steals `:443`, OpenClaw fails to start (`status=78`), and GSM Talk dies.

## Decision

1. **Rename** the Tailscale machine to `hub` → canonical MagicDNS: `hub.mining-ling.ts.net`.
2. **OpenClaw Control UI** (trusted HTTPS when [HTTPS Certificates](https://login.tailscale.com/admin/dns) are enabled): `https://hub.mining-ling.ts.net/chat/main`.
3. **gsm2computer portal (v1):** HTTP on the hub listener — `http://hub.mining-ling.ts.net:8787/portal/` (MagicDNS; hub binds `100.101.181.110:8787`). No Chrome-trusted cert on `:8787`.
4. **Do not** configure Tailscale Serve `/` (or any `:443` mapping) to the gsm2computer hub. Do not use `scripts/tailscale-serve-portal.sh` (it resets Serve and breaks OpenClaw).
5. **Do not** target `portal.hub.mining-ling.ts.net` — not supported; Chrome will not trust a Tailscale cert there.
6. Sibling **`portal.mining-ling.ts.net`** is a **second Tailscale identity** (userspace `tailscaled`, not a VM). It must never call `tailscale serve` on the default/`hub` socket. OpenClaw keeps `https://hub.mining-ling.ts.net`.

Pixel gateway and hub API clients default to `http://hub.mining-ling.ts.net:8787` (hub binds `100.101.181.110:8787`). Only human browsers care about trusted HTTPS for installable PWA / Web Push (deferred until a path that does not steal OpenClaw’s Serve exists).

## Consequences

- Talk Chromium and Control UI URLs use `https://hub.mining-ling.ts.net/…` after rename (replace `ip-172-31-21-244.mining-ling.ts.net`).
- Portal QA and PWA install stay on HTTP until ADR 0006 HTTPS follow-up finds a shared-443 or separate-node design.
- Operational detail: [`docs/TAILSCALE_PORTAL_HTTPS.md`](../TAILSCALE_PORTAL_HTTPS.md).
