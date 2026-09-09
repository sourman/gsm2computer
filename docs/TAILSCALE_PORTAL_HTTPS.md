# Tailscale HTTPS for the hub portal

Chrome must trust `https://ip-172-31-21-244.mining-ling.ts.net` so the PWA can install (and later use Web Push). Auth v1 is Tailscale mesh only (ADR 0006).

Hub HTTP origin stays `http://100.101.181.110:8787` for the Pixel gateway. Tailscale Serve is a TLS frontend on the MagicDNS name.

## Why Chrome shows a red lock

Typical causes on this host:

1. Something is listening on `:8443` with a **non-Tailscale** certificate (self-signed or hostname mismatch).
2. `tailscale serve` / `tailscale funnel` is not actually bound to the hub, so the browser hits the wrong process.
3. Tailscale HTTPS certificates are disabled in the admin console (MagicDNS HTTPS must be on).

`.ts.net` certs are issued by Let’s Encrypt via the Tailscale client. Chrome trusts those. It will **not** trust a random file on `:8443`.

## Fix (run on the hub, as the user that owns `tailscale`)

```bash
# 1. See what is claimed today
tailscale serve status
sudo ss -lptn | grep -E '8787|443|8443'

# 2. Drop a stale :8443 / path mapping if it is not the hub
tailscale serve reset

# 3. Proxy the MagicDNS HTTPS name to the hub
# Hub listens on the Tailscale IP (100.101.181.110), not 127.0.0.1 — use that origin:
tailscale serve --bg --https=443 http://100.101.181.110:8787
# If the daemon cannot bind 443, use:
# tailscale serve --bg http://100.101.181.110:8787

# 4. Confirm
tailscale serve status
curl -fsS https://ip-172-31-21-244.mining-ling.ts.net/health
curl -fsS -o /dev/null -w '%{http_code}\n' https://ip-172-31-21-244.mining-ling.ts.net/portal/
```

The portal is served by the hub at `/portal/` (static files from `hub/portal/dist`). Do not point Serve at a different port unless that port reverse-proxies the same hub.

## Admin console

On https://login.tailscale.com/admin/dns :

- MagicDNS enabled
- HTTPS Certificates enabled

Then `tailscale cert ip-172-31-21-244.mining-ling.ts.net` can mint/renew the cert (Serve does this itself when HTTPS is on).

## Script

`scripts/tailscale-serve-portal.sh` wraps the serve command. Copy it to the hub or run it over SSH:

```bash
ssh <hub-user>@100.101.181.110 'bash -s' < scripts/tailscale-serve-portal.sh
```

Pixel outbound SMS / call upload keep using `http://100.101.181.110:8787`. Only browsers need the trusted `.ts.net` URL.
