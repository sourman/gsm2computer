#!/usr/bin/env bash
# Pull the Pixel call-trace JSONL after a dropped GSM call.
#
# The bridge writes UTC JSONL only while a call is up:
#   /storage/emulated/0/Android/data/com.gsm2computer.bridge/files/call-trace/
#     call-trace.jsonl          current
#     call-trace.1.jsonl … .4   rotated (2 MB each, five files kept)
#
# Usage:
#   ./scripts/pull-pixel-call-trace.sh [dest-dir]
#   ANDROID_SERIAL=<adb-serial> ./scripts/pull-pixel-call-trace.sh ./pixel-call-trace
#
# The gateway phone is rooted. `adb pull` of the app external-files dir needs
# `adb root` (or the su fallback below).
set -euo pipefail

DEST="${1:-./pixel-call-trace}"
PKG=com.gsm2computer.bridge
REMOTE="/storage/emulated/0/Android/data/${PKG}/files/call-trace"

ADB=(adb)
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  ADB+=(-s "$ANDROID_SERIAL")
fi

mkdir -p "$DEST"
"${ADB[@]}" root >/dev/null 2>&1 || true
if "${ADB[@]}" pull "$REMOTE" "$DEST"; then
  echo "pulled ${REMOTE} -> ${DEST}/call-trace"
  exit 0
fi

echo "adb pull failed; trying su tar" >&2
mkdir -p "$DEST"
"${ADB[@]}" exec-out su -c "tar -C /storage/emulated/0/Android/data/${PKG}/files -cf - call-trace" \
  | tar -C "$DEST" -xf -
echo "extracted via su -> ${DEST}/call-trace"
