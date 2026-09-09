# Tailscale naming and HTTPS on the hub

Auth v1 is Tailscale mesh only ([ADR 0006](../adr/0006-portal-messaging.md)). Machine naming and cert limits: [ADR 0007](../adr/0007-hub-machine-name-and-https.md).

## URLs (after machine rename to `hub`)

| Service | URL | Chrome-trusted HTTPS |
|---------|-----|----------------------|
| OpenClaw Control UI / Talk | `https://hub.mining-ling.ts.net/chat/main` | Yes (Tailscale Serve + HTTPS Certificates in admin) |
| gsm2computer portal (PWA) | `http://hub.mining-ling.ts.net:8787/portal/` | No — HTTP on `:8787` |
| Hub health / Pixel API | `http://hub.mining-ling.ts.net:8787` (hub binds `100.101.181.110:8787`) | No |
| NICE DCV | `https://…:8443` | No — DCV cert, unrelated to Tailscale |

**Legacy hostname** (before rename): `ip-172-31-21-244.mining-ling.ts.net` → replace with `hub.mining-ling.ts.net` everywhere after `tailscale set --hostname=hub` (or admin rename).

## What we cannot do

### `portal.hub.mining-ling.ts.net` (nested under hub)

Tailscale MagicDNS only does `<machine>.<tailnet>.ts.net`. Labels like `portal.hub.mining-ling.ts.net` are **not** issued by MagicDNS ([FR #1543](https://github.com/tailscale/tailscale/issues/1543)). Tailscale/Let’s Encrypt will **not** mint a trusted cert for that name. Do not plan DNS or Chrome around it.

### Tailscale Serve `/` → gsm2computer hub

**Do not** `tailscale serve` the hub on `:443` or map `/` to `http://…:8787`. OpenClaw owns MagicDNS HTTPS (`gateway.tailscale.mode=serve`). Stealing Serve makes OpenClaw **refuse to start** (`status=78`) and GSM Talk dies (`Talk button disabled` / handshake fail).

`scripts/tailscale-serve-portal.sh` is **obsolete** — it runs `tailscale serve reset` and claims `/` for the hub. Do not run it on safwat-eu.

### Sibling `portal.mining-ling.ts.net`

That name would be a **second Tailscale machine** (or tagged device identity), not a subdomain of `hub`. Out of scope unless we add a dedicated portal node.

## Why Chrome showed a red lock

| Symptom | Cause |
|---------|--------|
| Red lock on **`:8443`** | **NICE DCV** (`dcvserver`), not Tailscale or the hub. Ignore for portal/Talk. |
| Red lock on **`http://100.x:8787`** | Plain HTTP — no certificate on the hub listener. Expected for portal v1. |
| Red lock on **`portal.hub…`** | Name not in MagicDNS; no valid Tailscale cert. |
| Trusted Talk / Control UI | Visit **`https://hub.mining-ling.ts.net/…`** after rename, with HTTPS Certificates enabled in [Tailscale DNS admin](https://login.tailscale.com/admin/dns). |

A prior Serve experiment may have pointed the OpenClaw hostname at the hub briefly; trusted cert for Talk is always the OpenClaw Serve target on the machine FQDN, not `:8787`.

## If Talk is down after a Serve experiment

```bash
ssh safwat-eu 'tailscale serve status; systemctl --user is-active openclaw-gateway.service'
# If OpenClaw failed because Serve was stolen:
ssh safwat-eu 'tailscale serve reset; systemctl --user start openclaw-gateway.service'
# Reload Control UI in Talk Chromium; Talk button should show "Start voice input".
```

Only reset Serve to **restore OpenClaw** — not to expose the gsm2computer portal on HTTPS.

## Rename checklist (hub machine → `hub`)

On the hub (as the user that owns Tailscale):

```bash
sudo tailscale set --hostname=hub
tailscale status --json | jq -r '.Self.DNSName'   # expect hub.mining-ling.ts.net.
```

Then update bookmarks and docs: `ip-172-31-21-244` → `hub`. Talk Chromium profile URL: `https://hub.mining-ling.ts.net/chat/main`.

Pixel outbound SMS / call upload: default `http://hub.mining-ling.ts.net:8787` (hub binds `100.101.181.110:8787`).

## Portal HTTPS (future)

Trusted PWA install and Web Push need HTTPS without stealing OpenClaw’s `:443`. Options not chosen in v1: separate Tailscale node for `portal.…`, or an OpenClaw-supported shared Serve layout. Until then, portal stays HTTP on `:8787`.
