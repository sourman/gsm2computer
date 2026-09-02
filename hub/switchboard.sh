#!/usr/bin/env bash
# gsm2computer switchboard presets (SAF-8 prototype)
set -euo pipefail

unlink_all() {
  pw-link -l 2>/dev/null | grep -E "gsm_bus|openclaw_bus|whatsapp_bus|telegram_bus" | grep "|->" | while read -r line; do
    src=$(echo "$line" | sed "s/ *|->.*//")
    dst=$(echo "$line" | sed "s/.*|-> //")
    pw-link -d "$src" "$dst" 2>/dev/null || true
  done
}

link_stereo() {
  local from="$1" to="$2"
  pw-link "${from}:monitor_FL" "${to}:playback_FL" 2>/dev/null || true
  pw-link "${from}:monitor_FR" "${to}:playback_FR" 2>/dev/null || true
}

case "${1:-status}" in
  status)
    echo "=== sinks ==="
    pactl list sinks short | grep -E "gsm|openclaw|whatsapp|telegram" || true
    echo "=== links ==="
    pw-link -l 2>/dev/null | grep -E "gsm_bus|openclaw_bus|whatsapp_bus|telegram_bus" || true
    ;;
  clear) unlink_all ;;
  openclaw)
    unlink_all
    link_stereo openclaw_bus gsm_bus
    echo "mode: gsm uplink (hub) + openclaw downlink -> gsm"
    ;;
  conference)
    unlink_all
    link_stereo gsm_bus openclaw_bus
    link_stereo gsm_bus whatsapp_bus
    link_stereo gsm_bus telegram_bus
    link_stereo openclaw_bus gsm_bus
    echo "mode: gsm + openclaw + whatsapp + telegram (partial mesh)"
    ;;
  *) echo "usage: $0 {status|clear|openclaw|conference}"; exit 1 ;;
esac
