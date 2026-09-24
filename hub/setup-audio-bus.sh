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
create_sink phone_uplink Phone_Uplink
create_sink whatsapp_bus WhatsApp_Bus
create_sink telegram_bus Telegram_Bus

# Keep phone_uplink at unity gain. Chromium/WebRTC AGC otherwise drifts
# phone_uplink.monitor down (seen at 71% / -9 dB) via monitor.channel-volumes.
pactl set-sink-volume phone_uplink 100% 2>/dev/null || true
pactl set-source-volume phone_uplink.monitor 100% 2>/dev/null || true

# Virtual mic for Talk Chromium: phone_uplink.monitor is silent on PipeWire
# 1.4.x null sinks here (monitor.passthrough). Sink-capture loopback exposes
# openclaw_phone_mic as a real Audio/Source Chromium can select by label.
ensure_openclaw_phone_mic() {
  if pactl list sources short 2>/dev/null | awk '{print $2}' | grep -qx openclaw_phone_mic; then
    echo "source exists: openclaw_phone_mic"
    return 0
  fi
  if systemctl --user is-enabled gsm2computer-openclaw-phone-mic.service >/dev/null 2>&1; then
    systemctl --user start gsm2computer-openclaw-phone-mic.service || true
  fi
  # Fallback if unit not installed yet
  if ! pactl list sources short 2>/dev/null | awk '{print $2}' | grep -qx openclaw_phone_mic; then
    pw-loopback -n openclaw_phone_mic -C phone_uplink \
      -i stream.capture.sink=true \
      -o media.class=Audio/Source \
      >/tmp/openclaw-phone-mic-loopback.log 2>&1 &
      >/tmp/gsm2-openclaw-phone-mic.log 2>&1 &
    sleep 0.5
  fi
  if pactl list sources short 2>/dev/null | awk '{print $2}' | grep -qx openclaw_phone_mic; then
    echo "created: openclaw_phone_mic"
  else
    echo "WARN: openclaw_phone_mic not available" >&2
  fi
}
ensure_openclaw_phone_mic
