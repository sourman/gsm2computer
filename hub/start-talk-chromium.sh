#!/usr/bin/env bash
# Launch dedicated Chromium for OpenClaw Control UI Talk.
# Pulse: mic = openclaw_phone_mic (sink-capture of phone_uplink), speaker = openclaw_bus.
set -euo pipefail

PROFILE="${GSM2COMPUTER_TALK_USER_DATA_DIR:-$HOME/.config/chromium-openclaw-talk}"
CDP_PORT="${GSM2COMPUTER_TALK_CDP_PORT:-9222}"
URL="${GSM2COMPUTER_TALK_UI_URL:-https://hub-cup.mining-ling.ts.net/chat/main}"
CHROMIUM_BIN="${GSM2COMPUTER_CHROMIUM_BIN:-chromium-browser}"
PULSE_SOURCE="${GSM2COMPUTER_TALK_PULSE_SOURCE:-${GSM2COMPUTER_PHONE_UPLINK_MONITOR:-output.openclaw_phone_mic}}"
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

# Stage tokenized Control UI URL so first paint is authenticated (hub-cup only).
if command -v python3 >/dev/null; then
  TOKEN_URL="$(
    GSM2COMPUTER_TALK_UI_URL="$URL" PYTHONPATH="${GSM2COMPUTER_HUB_DIR:-$HOME/gsm2computer-hub}:$HOME/gsm2computer-hub" \
      python3 - <<'PY2'
import os, sys
sys.path.insert(0, os.environ.get("GSM2COMPUTER_HUB_DIR", os.path.expanduser("~/gsm2computer-hub")))
try:
    from talk_chromium import control_ui_url_with_token
    print(control_ui_url_with_token())
except Exception as exc:
    print(os.environ.get("GSM2COMPUTER_TALK_UI_URL", ""), end="")
    sys.stderr.write(f"token url stage failed: {exc}\n")
PY2
  )"
  if [[ -n "${TOKEN_URL}" ]]; then
    printf "%s" "$TOKEN_URL" > "$PROFILE/talk-ui-url.txt"
  fi
fi

# Drop crash/session restore so Chromium does not reopen ghost about:blank
# targets that report a chat URL in /json/list while evaluate sees about:blank.
rm -f "$PROFILE/SingletonLock" "$PROFILE/SingletonCookie" "$PROFILE/SingletonSocket" 2>/dev/null || true
rm -f "$PROFILE/Default/Sessions/"* "$PROFILE/Default/Session Storage/"* 2>/dev/null || true
rm -f "$PROFILE/Default/Current Session" "$PROFILE/Default/Current Tabs" \
      "$PROFILE/Default/Last Session" "$PROFILE/Default/Last Tabs" 2>/dev/null || true

# Pin uplink volumes before Chromium opens the capture (AGC drifts them down).
pactl set-sink-volume phone_uplink 100% 2>/dev/null || true
pactl set-source-volume "$PULSE_SOURCE" 100% 2>/dev/null || true

LAUNCH_URL="$URL"
if [[ -f "$PROFILE/talk-ui-url.txt" ]]; then
  STAGED="$(tr -d '\r\n' < "$PROFILE/talk-ui-url.txt" || true)"
  if [[ -n "$STAGED" && "$STAGED" == *hub-cup.mining-ling.ts.net* ]]; then
    LAUNCH_URL="$STAGED"
  fi
fi

echo "talk chromium DISPLAY=$DISPLAY PULSE_SOURCE=$PULSE_SOURCE PULSE_SINK=$PULSE_SINK profile=$PROFILE launch=${LAUNCH_URL%%#*}#token=…"

# VPS/XFCE: GpuControl.CreateCommandBuffer transient failures leave the CDP
# target listed as chat/main while Runtime.evaluate sees about:blank — clear
# Sessions above and always launch with the tokenized URL to reduce that.
exec "$CHROMIUM_BIN" \
  --user-data-dir="$PROFILE" \
  --remote-debugging-port="$CDP_PORT" \
  --remote-debugging-address=127.0.0.1 \
  --remote-allow-origins=* \
  --use-fake-ui-for-media-stream \
  --autoplay-policy=no-user-gesture-required \
  --no-first-run \
  --no-default-browser-check \
  --disable-session-crashed-bubble \
  --hide-crash-restore-bubble \
  --disable-features=InfiniteSessionRestore \
  --noerrdialogs \
  --disable-infobars \
  --ozone-platform=x11 \
  --disable-features=WebRtcAllowInputVolumeAdjustment,ChromeWideEchoCancellation,InfiniteSessionRestore \
  --disable-gpu \
  --disable-gpu-compositing \
  --use-gl=swiftshader \
  --ignore-gpu-blocklist \
  --disable-dev-shm-usage \
  --password-store=basic \
  "$LAUNCH_URL"
