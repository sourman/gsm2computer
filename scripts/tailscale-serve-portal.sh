#!/usr/bin/env bash
# Configure Tailscale Serve so https://<magicdns>/portal/ terminates with a
# trusted .ts.net cert and proxies to the local gsm2computer hub.
set -euo pipefail

HUB_HTTP="${GSM2COMPUTER_HUB_LOCAL:-http://127.0.0.1:8787}"
MAGIC="${GSM2COMPUTER_HUB_MAGICDNS:-ip-172-31-21-244.mining-ling.ts.net}"

if ! command -v tailscale >/dev/null 2>&1; then
  echo "tailscale CLI not found on PATH" >&2
  exit 1
fi

echo "current serve status:"
tailscale serve status || true

echo "resetting serve mappings (Tailscale HTTPS frontend only; hub on 8787 is untouched)"
tailscale serve reset

# Background HTTPS on 443 → local hub. Tailscale mints the .ts.net cert.
if tailscale serve --bg --https=443 "$HUB_HTTP"; then
  echo "serve: https://$MAGIC → $HUB_HTTP"
else
  echo "https=443 failed; trying default serve (still TLS on MagicDNS)" >&2
  tailscale serve --bg "$HUB_HTTP"
fi

echo
tailscale serve status || true
echo
echo "probe:"
curl -fsS "https://$MAGIC/health" && echo
code=$(curl -fsS -o /dev/null -w '%{http_code}' "https://$MAGIC/portal/" || true)
echo "GET /portal/ → HTTP ${code:-err}"
echo "If Chrome still shows a red lock, enable HTTPS Certificates in the Tailscale DNS admin console."
