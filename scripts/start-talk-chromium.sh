#!/usr/bin/env bash
# Launch dedicated Chromium for OpenClaw Control UI Talk.
# Pulse: mic = phone_uplink.monitor, speaker = openclaw_bus (mix-minus).
set -euo pipefail

PROFILE="${GSM2COMPUTER_TALK_USER_DATA_DIR:-$HOME/.config/chromium-openclaw-talk}"
CDP_PORT="${GSM2COMPUTER_TALK_CDP_PORT:-9222}"
URL="${GSM2COMPUTER_TALK_UI_URL:-https://ip-172-31-21-244.mining-ling.ts.net/chat/main}"
CHROMIUM_BIN="${GSM2COMPUTER_CHROMIUM_BIN:-chromium-browser}"
PULSE_SOURCE="${GSM2COMPUTER_PHONE_UPLINK_MONITOR:-phone_uplink.monitor}"
PULSE_SINK="${GSM2COMPUTER_OPENCLAW_BUS:-openclaw_bus}"

detect_display() {
  if [[ -n "${DISPLAY:-}" && -n "${XAUTHORITY:-}" ]]; then
    return 0
  fi
  local pid
  pid="$(pgrep -u "$(id -u)" -n gnome-session-binary 2>/dev/null || true)"
  if [[ -z "$pid" ]]; then
    pid="$(pgrep -u "$(id -u)" -n gdm-x-session 2>/dev/null || true)"
  fi
  if [[ -n "$pid" && -r "/proc/$pid/environ" ]]; then
    local env
    env="$(tr '\0' '\n' < "/proc/$pid/environ")"
    DISPLAY="${DISPLAY:-$(printf '%s\n' "$env" | awk -F= '/^DISPLAY=/{print $2; exit}')}"
    XAUTHORITY="${XAUTHORITY:-$(printf '%s\n' "$env" | awk -F= '/^XAUTHORITY=/{print $2; exit}')}"
  fi
  DISPLAY="${DISPLAY:-:1}"
  XAUTHORITY="${XAUTHORITY:-/run/user/$(id -u)/gdm/Xauthority}"
  export DISPLAY XAUTHORITY
}

if ! pactl list sinks short 2>/dev/null | awk '{print $2}' | grep -qx phone_uplink; then
  echo "phone_uplink sink missing — run hub/setup-audio-bus.sh" >&2
  exit 1
fi

detect_display
export DISPLAY XAUTHORITY
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"
export PULSE_SOURCE PULSE_SINK

mkdir -p "$PROFILE"

echo "talk chromium DISPLAY=$DISPLAY PULSE_SOURCE=$PULSE_SOURCE PULSE_SINK=$PULSE_SINK profile=$PROFILE"

exec "$CHROMIUM_BIN" \
  --user-data-dir="$PROFILE" \
  --remote-debugging-port="$CDP_PORT" \
  --remote-debugging-address=127.0.0.1 \
  --use-fake-ui-for-media-stream \
  --autoplay-policy=no-user-gesture-required \
  --no-first-run \
  --no-default-browser-check \
  --disable-session-crashed-bubble \
  --hide-crash-restore-bubble \
  --disable-infobars \
  --ozone-platform=x11 \
  "$URL"
