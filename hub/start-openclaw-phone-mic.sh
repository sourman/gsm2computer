#!/usr/bin/env bash
# Sink-capture phone_uplink → Audio/Source for Talk Chromium.
#
# PipeWire exposes this as Pulse source "output.openclaw_phone_mic".
# -i MUST be exactly stream.capture.sink=true (key=value). Do NOT put
# node.name / node.passive in -i or set node.name via -o — that renames the
# source and yields a silent capture on this PipeWire.
set -euo pipefail
exec /usr/bin/pw-loopback \
  -n openclaw_phone_mic \
  -C phone_uplink \
  -i stream.capture.sink=true \
  -o media.class=Audio/Source
