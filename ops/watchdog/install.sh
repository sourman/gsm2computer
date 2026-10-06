#!/bin/bash
# Install / update cup-watchdog on safwat-cup (run as ahmed on cup, from a checkout of this repo).
#   bash ops/watchdog/install.sh            # install + enable timer
#   HOLD_POSTS=1 bash ops/watchdog/install.sh   # install with webhook POSTs held (CUP_WATCHDOG_POST=0)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
dest="$HOME/gsm2computer-hub/ops/watchdog"
units="$HOME/.config/systemd/user"
mkdir -p "$dest" "$units" "$units/cup-watchdog.service.d"
install -m 0755 "$here/cup_watchdog.py" "$dest/cup_watchdog.py"
install -m 0644 "$here/cup-watchdog.service" "$units/cup-watchdog.service"
install -m 0644 "$here/cup-watchdog.timer" "$units/cup-watchdog.timer"
if [ "${HOLD_POSTS:-0}" = "1" ]; then
  printf '[Service]\nEnvironment=CUP_WATCHDOG_POST=0\n' > "$units/cup-watchdog.service.d/hold-posts.conf"
  echo "POSTs HELD (remove $units/cup-watchdog.service.d/hold-posts.conf + daemon-reload to enable)"
fi
systemctl --user daemon-reload
systemctl --user enable --now cup-watchdog.timer
systemctl --user list-timers cup-watchdog.timer --no-pager
