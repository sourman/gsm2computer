#!/usr/bin/env bash
# CLI against the userspace Tailscale sibling (portal.mining-ling.ts.net).
# NEVER omit --socket — default socket is OpenClaw's hub node.
set -euo pipefail
SOCK="${TAILSCALE_PORTAL_SOCKET:-$HOME/.local/share/tailscale-portal/tailscaled.sock}"
exec tailscale --socket="$SOCK" "$@"
