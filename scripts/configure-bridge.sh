#!/usr/bin/env bash
# Push hub-stream settings into the phone app's SharedPreferences.
#
# Usage:
#   STREAM_TOKEN_URL=https://your-hub.example/token ./scripts/configure-bridge.sh --force -s <serial>
#
# Environment:
#   STREAM_ENABLED=true|false   (default: true when URL set)
#   STREAM_TOKEN_URL            hub token endpoint (required)
#   STREAM_MODEL                (default: gpt-realtime)
#   STREAM_VOICE                (default: marin)
#   AUTOCONNECT                 (default: true)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

PKG="com.gsm2computer.bridge"
PREFS="gsm2computer"
FORCE=false
SERIAL=""

usage() {
  echo "Usage: $0 [--force] [-s SERIAL]" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --force) FORCE=true; shift ;;
    -s) SERIAL="$2"; shift 2 ;;
    -h|--help) usage ;;
    *) echo "Unknown arg: $1" >&2; usage ;;
  esac
done

ADB=(adb)
[[ -n "$SERIAL" ]] && ADB=(adb -s "$SERIAL")

STREAM_TOKEN_URL="${STREAM_TOKEN_URL:-}"
STREAM_MODEL="${STREAM_MODEL:-gpt-realtime}"
STREAM_VOICE="${STREAM_VOICE:-marin}"
AUTOCONNECT="${AUTOCONNECT:-true}"
STREAM_ENABLED="${STREAM_ENABLED:-true}"

if [[ -z "$STREAM_TOKEN_URL" ]]; then
  echo "STREAM_TOKEN_URL is required" >&2
  exit 1
fi

XML="/data/data/$PKG/shared_prefs/${PREFS}.xml"
TMP="$(mktemp)"
cat >"$TMP" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
  <boolean name="autoconnect" value="$AUTOCONNECT" />
  <boolean name="stream_enabled" value="$STREAM_ENABLED" />
  <string name="stream_token_url">$STREAM_TOKEN_URL</string>
  <string name="stream_model">$STREAM_MODEL</string>
  <string name="stream_voice">$STREAM_VOICE</string>
</map>
EOF

"${ADB[@]}" shell "run-as $PKG cat '$XML' 2>/dev/null" >/dev/null && HAS=true || HAS=false
if [[ "$HAS" == true && "$FORCE" != true ]]; then
  echo "Prefs exist — use --force to overwrite"
  rm -f "$TMP"
  exit 0
fi

"${ADB[@]}" push "$TMP" "/data/local/tmp/${PREFS}.xml"
"${ADB[@]}" shell "run-as $PKG mkdir -p shared_prefs && run-as $PKG cp /data/local/tmp/${PREFS}.xml shared_prefs/${PREFS}.xml"
rm -f "$TMP"

echo "Configured $PKG:"
echo "  stream_token_url=$STREAM_TOKEN_URL"
echo "  stream_model=$STREAM_MODEL"
echo "  stream_voice=$STREAM_VOICE"
echo "  autoconnect=$AUTOCONNECT"
