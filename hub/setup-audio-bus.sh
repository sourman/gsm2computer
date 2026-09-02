#!/usr/bin/env bash
set -euo pipefail
for i in $(seq 1 30); do
  pactl info >/dev/null 2>&1 && break
  sleep 1
done
pactl info >/dev/null || exit 1

create_sink() {
  local name="$1" desc="$2"
  if pactl list sinks short | awk "{print \$2}" | grep -qx "$name"; then
    echo "sink exists: $name"
    return 0
  fi
  pactl load-module module-null-sink "sink_name=$name" "sink_properties=device.description=$desc"
  echo "created: $name"
}

create_sink gsm_bus GSM_Bus
create_sink openclaw_bus OpenClaw_Bus
create_sink whatsapp_bus WhatsApp_Bus
create_sink telegram_bus Telegram_Bus
