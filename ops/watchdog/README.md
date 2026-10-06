# cup-watchdog

Free, on-host replacement for the two 15-minute Grok Bot routines
("Cup production health" + "Pixel Tailscale liveness"). Runs on **safwat-cup**
from a `systemd --user` timer every **150 s**, read-only, and POSTs to the
existing alert webhook **only on state changes**.

It never remediates (no restarts, no `/admin/call/release`, no Talk/PipeWire/CDP
writes). The receiving Grok Bot routine decides what to do and must respect the
live-call rule (`busy` / `real_call_busy` are included in every payload).

## Checks

| check | fails when |
|---|---|
| `hub_health` | `GET http://100.119.126.42:8787/health` not 200 / `ok != true` (1 quick retry) |
| `talk_page` | (idle only) Talk Chromium CDP down, no Talk button, or page not on `/chat` |
| `portal_cup` / `hub_cup_portal` | `https://portal-cup…/`, `https://hub-cup…/portal` final status (after redirects) not 200 |
| `unit:gsm2computer-hub` / `unit:talk-chromium` / `unit:openclaw-gateway` | user unit not `active` (NRestarts tracked; increases logged as `unit_restart` events in the daily summary) |
| `pixel_ping` | `tailscale ping --tsmp --c 1 --timeout 5s 100.85.191.25` fails 3 tries in a row |
| `webhook_env` | hub unit Environment lacks `GSM2COMPUTER_ALERT_WEBHOOK_URL`/`_KEY` (presence only) |
| `uplink_volume` | `phone_uplink.monitor` not 100% |
| `stale_bridge` | any `rejecting websocket: call already in progress` in the hub journal since the previous run |
| `e2e_selftest` | `~/gsm2computer-e2e/last-run.json` older than 45 min, or FAIL; `skipped` (line busy/real call) is OK unless no PASS for 3 h; skipped entirely while a real call is up |
| `reply_audio` | a newly ended real call (`~/gsm2computer-calls/*`, non-e2e) has silent `openclaw-spk` (max ≤ -80 dB over ≥ 10 s). Reuses/appends `~/cup-health-checked-calls` |

A check flips to **down** after failing **2 consecutive runs** (`reply_audio`: 1).
Line busy (real call or the e2e self-test) is never itself a failure.

## Events / payload

All POSTs: `Content-Type: application/json`, `Authorization: Bearer <GSM2COMPUTER_ALERT_WEBHOOK_KEY>`
(same as `hub/alert_webhook.py`). URL/key are read from env or the
`gsm2computer-hub` unit Environment; never logged.

```json
{
  "source": "cup-watchdog",
  "event": "down" | "recovered" | "daily_summary",
  "host": "safwat-cup",
  "check": "pixel_ping,uplink_volume",          // comma list of bundled checks
  "detail": "pixel_ping: …; uplink_volume: …",
  "since": "2026-10-06T11:31:59-04:00",          // ET, earliest first-fail
  "checks": [{"check": "...", "detail": "...", "since": "...", "reminder": true?, "down_for_min": 12?, "test": true?}],
  "busy": false, "real_call_busy": false,
  "ts": "2026-10-06T11:32:00-04:00",
  "test": false,
  "reminder": false,                              // down only: true = 60-min still-down reminder
  "text": "cup-watchdog DOWN on safwat-cup: …"
}
```

* `down`: one POST per run bundling every check that newly went down (+ a single
  reminder after 60 min still down). Unsent downs (webhook error / held) retry next run.
* `recovered`: one POST bundling checks that cleared (only if their down was delivered).
* `daily_summary`: once a day in the 08:45–10:00 ET window; adds `counts_24h`,
  `events_24h`, `current`, `down_now`, `e2e_last`, `nrestarts`.

## Files on cup

* script: `~/gsm2computer-hub/ops/watchdog/cup_watchdog.py`
* units: `~/.config/systemd/user/cup-watchdog.{service,timer}`
* state dir `~/.local/state/cup-watchdog/`: `heartbeat.json` (last run, overall,
  per-check ok/FAIL), `state.json`, `last-results.json`, `watchdog.log`

Heartbeat from the box: `ssh safwat-cup cat .local/state/cup-watchdog/heartbeat.json`

## Install / update

```bash
# on cup, from a copy of this directory
bash install.sh                 # install + enable timer (POSTs live)
HOLD_POSTS=1 bash install.sh    # same, but drop-in CUP_WATCHDOG_POST=0 holds POSTs
# release the hold:
rm ~/.config/systemd/user/cup-watchdog.service.d/hold-posts.conf && systemctl --user daemon-reload
```

## Testing

```bash
W=~/gsm2computer-hub/ops/watchdog/cup_watchdog.py
python3 $W --dry-run -v --state-dir /tmp/cupwd-dry          # all checks, print payloads only
# end-to-end alert path with a throwaway state dir (does not touch production state):
python3 $W --state-dir /tmp/cupwd-test --only pixel_ping --confirm-runs 1 --force-fail pixel_ping   # -> TEST down POST
python3 $W --state-dir /tmp/cupwd-test --only pixel_ping                                          # -> TEST recovered POST
```

`--webhook-url http://127.0.0.1:PORT/` points at a local sink (real key not sent).
