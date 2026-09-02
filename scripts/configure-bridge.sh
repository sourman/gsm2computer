#!/usr/bin/env bash
# Push hub-stream settings into the phone app's SharedPreferences.
#
# Usage:
#   HUB_CONTROL_URL=http://100.101.181.110:8787 ./scripts/configure-bridge.sh --force -s <serial>
#
# Environment:
#   HUB_CONTROL_URL             hub origin (default: Tailscale http://100.101.181.110:8787)
#   STREAM_TOKEN_URL            token endpoint (default: {HUB_CONTROL_URL}/token)
#   STREAM_ENABLED=true|false   (default: true when hub or token URL set)
#   STREAM_MODEL                OpenAI only (default: gpt-realtime)
#   STREAM_VOICE                OpenAI only (default: marin)
#   HUB_OWNED_SESSION           skip response.create / session.update (default: true when hub set)
#   AUTOCONNECT                 (default: true)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

PKG="com.gsm2computer.bridge"
PREFS="gsm2computer"
FORCE=false
SERIAL=""
DEFAULT_HUB="http://100.101.181.110:8787"

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

HUB_CONTROL_URL="${HUB_CONTROL_URL:-$DEFAULT_HUB}"
STREAM_MODEL="${STREAM_MODEL:-gpt-realtime}"
STREAM_VOICE="${STREAM_VOICE:-marin}"
AUTOCONNECT="${AUTOCONNECT:-true}"
STREAM_ENABLED="${STREAM_ENABLED:-true}"

if [[ -z "${STREAM_TOKEN_URL:-}" && -n "$HUB_CONTROL_URL" ]]; then
  STREAM_TOKEN_URL="${HUB_CONTROL_URL%/}/token"
fi
STREAM_TOKEN_URL="${STREAM_TOKEN_URL:-}"

if [[ -n "$HUB_CONTROL_URL" ]]; then
  HUB_OWNED_SESSION="${HUB_OWNED_SESSION:-true}"
else
  HUB_OWNED_SESSION="${HUB_OWNED_SESSION:-false}"
fi

if [[ -z "$HUB_CONTROL_URL" && -z "$STREAM_TOKEN_URL" ]]; then
  echo "HUB_CONTROL_URL or STREAM_TOKEN_URL is required" >&2
  exit 1
fi

XML="/data/data/$PKG/shared_prefs/${PREFS}.xml"
TMP="$(mktemp)"
cat >"$TMP" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
  <boolean name="autoconnect" value="$AUTOCONNECT" />
  <boolean name="stream_enabled" value="$STREAM_ENABLED" />
  <boolean name="hub_owned_session" value="$HUB_OWNED_SESSION" />
  <string name="hub_control_url">$HUB_CONTROL_URL</string>
  <string name="stream_token_url">$STREAM_TOKEN_URL</string>
  <string name="stream_model">$STREAM_MODEL</string>
  <string name="stream_voice">$STREAM_VOICE</string>
</map>
EOF

prefs_readable() {
  "${ADB[@]}" shell "run-as $PKG cat '$XML'" >/dev/null 2>&1 && return 0
  "${ADB[@]}" shell "su -c 'test -f $XML'" >/dev/null 2>&1
}

if prefs_readable && [[ "$FORCE" != true ]]; then
  echo "Prefs exist — use --force to overwrite"
  rm -f "$TMP"
  exit 0
fi

"${ADB[@]}" push "$TMP" "/data/local/tmp/${PREFS}.xml"
if "${ADB[@]}" shell "run-as $PKG true" >/dev/null 2>&1; then
  "${ADB[@]}" shell "run-as $PKG mkdir -p shared_prefs && run-as $PKG cp /data/local/tmp/${PREFS}.xml shared_prefs/${PREFS}.xml"
else
  DATA="/data/data/$PKG"
  UID_GID=$("${ADB[@]}" shell "su -c 'stat -c %u:%g $DATA'" | tr -d '\r')
  "${ADB[@]}" shell "su -c 'mkdir -p $DATA/shared_prefs && cp /data/local/tmp/${PREFS}.xml $XML && chmod 770 $DATA/shared_prefs && chmod 660 $XML && chown $UID_GID $DATA/shared_prefs $XML'"
fi
rm -f "$TMP"

echo "Configured $PKG:"
echo "  hub_control_url=$HUB_CONTROL_URL"
echo "  stream_token_url=$STREAM_TOKEN_URL"
echo "  hub_owned_session=$HUB_OWNED_SESSION"
echo "  stream_model=$STREAM_MODEL"
echo "  stream_voice=$STREAM_VOICE"
echo "  autoconnect=$AUTOCONNECT"
