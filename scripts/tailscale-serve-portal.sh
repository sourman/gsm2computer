#!/usr/bin/env bash
# OBSOLETE — do not run. OpenClaw owns Tailscale Serve on https://hub.mining-ling.ts.net/
# (ADR 0007). Claiming :443 for the gsm2computer hub kills Control UI Talk.
# Portal: http://hub.mining-ling.ts.net:8787/portal/
set -euo pipefail
echo "scripts/tailscale-serve-portal.sh is disabled (ADR 0007)." >&2
echo "Do not tailscale serve reset or bind https://hub.mining-ling.ts.net to :8787." >&2
echo "See docs/adr/0007-hub-machine-name-and-https.md and docs/TAILSCALE_PORTAL_HTTPS.md" >&2
exit 1
