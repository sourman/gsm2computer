#!/usr/bin/env python3
"""cup-watchdog: free on-host health watchdog for safwat-cup.

Runs every ~2.5 min from a systemd --user timer. Checks the production stack
read-only and POSTs to the existing Grok Bot alert webhook ONLY on state
changes:

  * ``down``       a check failed on N consecutive runs (default 2), one POST
                   bundling every check that flipped in the same run
                   (+ one reminder after 60 min if still down)
  * ``recovered``  a previously-notified check passes again
  * ``daily_summary`` once a day ~08:45 ET

It NEVER remediates (no restarts, no /admin/call/release). The receiving
routine decides what to do, respecting the live-call rule.

Webhook URL/key are read from env (GSM2COMPUTER_ALERT_WEBHOOK_URL/_KEY) or,
if unset, from the gsm2computer-hub user unit's Environment. They are never
printed or logged. Auth matches hub/alert_webhook.py (Bearer key, JSON body).

Stdlib only; every subprocess / HTTP call has a hard timeout.
"""
from __future__ import annotations

import argparse
import fcntl
import json
import os
import re
import shlex
import socket
import subprocess
import sys
import time
import urllib.parse
import urllib.error
import urllib.request
import wave
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable, Optional
from zoneinfo import ZoneInfo

ET = ZoneInfo("America/New_York")
HOME = Path.home()
HOST = socket.gethostname() or "safwat-cup"

HUB = os.environ.get("CUP_WATCHDOG_HUB", "http://100.119.126.42:8787").rstrip("/")
PIXEL_IP = os.environ.get("CUP_WATCHDOG_PIXEL_IP", "100.85.191.25")
PORTALS = {
    "portal_cup": "https://portal-cup.mining-ling.ts.net/",
    "hub_cup_portal": "https://hub-cup.mining-ling.ts.net/portal",
}
UNITS = ["gsm2computer-hub", "talk-chromium", "openclaw-gateway"]
E2E_LAST = Path(os.environ.get("CUP_WATCHDOG_E2E_LAST", str(HOME / "gsm2computer-e2e" / "last-run.json")))
E2E_MAX_AGE_S = int(os.environ.get("CUP_WATCHDOG_E2E_MAX_AGE_S", str(45 * 60)))
E2E_MAX_NO_PASS_S = int(os.environ.get("CUP_WATCHDOG_E2E_MAX_NO_PASS_S", str(3 * 3600)))
CALLS_DIR = Path(os.environ.get("CUP_WATCHDOG_CALLS_DIR", str(HOME / "gsm2computer-calls")))
CHECKED_CALLS = Path(os.environ.get("CUP_WATCHDOG_CHECKED_CALLS", str(HOME / "cup-health-checked-calls")))
EXTRA_CHECKED_FILES = [
    HOME / "gsm2computer-hub" / ".cup-health-reply-audio-state.json",
    HOME / "gsm2computer-hub" / ".cup-health-checked-calls",
    HOME / "gsm2computer-e2e" / "postcall-reply-checked.json",
]
URL_ENV = "GSM2COMPUTER_ALERT_WEBHOOK_URL"
KEY_ENV = "GSM2COMPUTER_ALERT_WEBHOOK_KEY"
STALE_BRIDGE_PAT = "rejecting websocket: call already in progress"
SUMMARY_AT = os.environ.get("CUP_WATCHDOG_SUMMARY_AT", "08:45")  # ET, window until 10:00
RENOTIFY_MIN = int(os.environ.get("CUP_WATCHDOG_RENOTIFY_MIN", "60"))  # 0 = never
EVENT_KEEP_DAYS = 7

# Checks that flip on the first failing run (deterministic, one-shot facts).
CONFIRM_OVERRIDE = {"reply_audio": 1}


# ---------------------------------------------------------------- helpers

def now_utc() -> datetime:
    return datetime.now(timezone.utc)


def iso_et(dt: Optional[datetime] = None) -> str:
    return (dt or now_utc()).astimezone(ET).isoformat(timespec="seconds")


def parse_iso(s: Any) -> Optional[datetime]:
    if not s:
        return None
    try:
        dt = datetime.fromisoformat(str(s).replace("Z", "+00:00"))
        return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)
    except Exception:
        return None


def run(cmd: list[str], timeout: float = 10.0) -> tuple[int, str]:
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, errors="replace",
                           timeout=timeout, stdin=subprocess.DEVNULL)
        return p.returncode, (p.stdout or "") + (p.stderr or "")
    except subprocess.TimeoutExpired:
        return 124, f"timeout after {timeout:.0f}s"
    except FileNotFoundError as e:
        return 127, str(e)


def http_get(url: str, timeout: float = 10.0) -> tuple[int, bytes, str]:
    req = urllib.request.Request(url, headers={"User-Agent": "cup-watchdog/1"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:  # follows redirects
            return r.status, r.read(2_000_000), r.geturl()
    except urllib.error.HTTPError as e:
        return e.code, b"", url
    except Exception as e:
        return 0, str(e).encode(), url


def atomic_write(path: Path, text: str) -> None:
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(text, encoding="utf-8")
    os.replace(tmp, path)


# ---------------------------------------------------------------- webhook

def webhook_config(override_url: Optional[str] = None) -> tuple[str, str]:
    """Return (url, key). Never log these."""
    if override_url is not None:
        return override_url, (os.environ.get("CUP_WATCHDOG_TEST_KEY") or "test-key")
    url = (os.environ.get(URL_ENV) or "").strip()
    key = (os.environ.get(KEY_ENV) or "").strip()
    if url:
        return url, key
    rc, out = run(["systemctl", "--user", "show", "gsm2computer-hub", "-p", "Environment", "--value"], 8)
    if rc != 0:
        return "", ""
    try:
        parts = shlex.split(out)
    except ValueError:
        parts = out.split()
    env = dict(p.split("=", 1) for p in parts if "=" in p)
    return env.get(URL_ENV, "").strip(), env.get(KEY_ENV, "").strip()


def post_webhook(payload: dict, url: str, key: str) -> tuple[bool, str]:
    """POST like hub/alert_webhook.py. Returns (ok, short status) without secrets."""
    if not url:
        return False, "no webhook url"
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json", "User-Agent": "cup-watchdog/1"}
    if key:
        headers["Authorization"] = key if key.lower().startswith("bearer ") else f"Bearer {key}"
    req = urllib.request.Request(url, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            st = getattr(resp, "status", None) or resp.getcode()
            return 200 <= st < 300, f"HTTP {st}"
    except urllib.error.HTTPError as e:
        return False, f"HTTP {e.code}"
    except Exception as e:
        return False, f"error {type(e).__name__}"


# ---------------------------------------------------------------- checks
# Each check returns (ok, detail). ok=None means "skip this run" (no state change).

class Ctx:
    def __init__(self, state: dict, last_run: Optional[datetime]):
        self.state = state
        self.last_run = last_run
        self.health: Optional[dict] = None
        self.busy = False          # any busy slot (incl. e2e)
        self.real_busy = False     # busy with a real (non-e2e) call
        self.info: dict[str, Any] = {}


def check_hub_health(ctx: Ctx):
    last = ""
    for attempt in range(2):
        st, body, _ = http_get(HUB + "/health", 8)
        if st == 200:
            try:
                h = json.loads(body)
            except Exception:
                last = "health not JSON"
            else:
                ctx.health = h
                call = h.get("call") or {}
                ctx.busy = bool(call.get("busy"))
                ctx.real_busy = ctx.busy and not call.get("e2e")
                talk = h.get("talk") or {}
                ctx.info["busy"] = ctx.busy
                ctx.info["e2e_busy"] = bool(call.get("e2e"))
                ctx.info["talk_active"] = talk.get("talk_active")
                ctx.info["webrtc_connected"] = talk.get("webrtc_connected")
                if h.get("ok") is True:
                    return True, f"ok busy={ctx.busy}"
                last = f"/health ok={h.get('ok')!r}"
        else:
            last = f"/health HTTP {st or 'unreachable'}"
        if attempt == 0:
            time.sleep(3)
    return False, last


def check_talk_page(ctx: Ctx):
    if ctx.health is None or ctx.busy:
        return None, "skipped (hub down or line busy)"
    talk = ctx.health.get("talk") or {}
    page = talk.get("page") or {}
    problems = []
    if not talk.get("cdp"):
        problems.append("cdp=false")
    if not page.get("hasTalkButton"):
        problems.append("no Talk button")
    try:
        actual = urllib.parse.urlsplit(str(page.get("url") or ""))
        expected = urllib.parse.urlsplit(os.environ.get(
            "GSM2COMPUTER_TALK_UI_URL", "https://hub-cup.mining-ling.ts.net/chat/main"))
        correct = (actual.scheme, actual.hostname, actual.port or (443 if actual.scheme == "https" else 80), actual.path) == (
            expected.scheme, expected.hostname, expected.port or (443 if expected.scheme == "https" else 80), "/chat/main")
        correct = correct and actual.username is None and actual.password is None
    except ValueError:
        correct = False
    if not correct:
        problems.append("wrong origin or path (expected /chat/main)")
    if problems:
        return False, "Talk page: " + ", ".join(problems)
    return True, "ok"


def make_portal_check(url: str):
    def _check(ctx: Ctx):
        st, _, final = http_get(url, 10)
        if st == 200:
            return True, "200"
        st2, _, final = http_get(url, 10)  # one quick retry
        if st2 == 200:
            return True, "200 (retry)"
        return False, f"{url} -> {st2 or 'unreachable'} (final {final})"
    return _check


def make_unit_check(unit: str):
    def _check(ctx: Ctx):
        rc, out = run(["systemctl", "--user", "show", unit, "-p", "ActiveState",
                       "-p", "SubState", "-p", "NRestarts"], 8)
        props = dict(l.split("=", 1) for l in out.splitlines() if "=" in l)
        nr = props.get("NRestarts", "?")
        ctx.info.setdefault("nrestarts", {})[unit] = nr
        if props.get("ActiveState") == "active":
            return True, f"active NRestarts={nr}"
        return False, f"{unit} {props.get('ActiveState', '?')}/{props.get('SubState', '?')} NRestarts={nr}"
    return _check


def check_pixel_ping(ctx: Ctx):
    last = ""
    for i in range(3):
        rc, out = run(["tailscale", "ping", "--tsmp", "--c", "1", "--timeout", "5s", PIXEL_IP], 12)
        if rc == 0 and "pong" in out:
            m = re.search(r"in (\d+(?:\.\d+)?m?s)", out)
            return True, f"pong{(' ' + m.group(1)) if m else ''} try={i + 1}"
        last = out.strip().splitlines()[-1][:160] if out.strip() else f"rc={rc}"
        if i < 2:
            time.sleep(2)
    return False, f"Pixel {PIXEL_IP} no TSMP pong after 3 tries ({last})"


def check_webhook_env(ctx: Ctx):
    rc, out = run(["systemctl", "--user", "show", "gsm2computer-hub", "-p", "Environment", "--value"], 8)
    if rc != 0:
        return False, "cannot read hub unit environment"
    try:
        parts = shlex.split(out)
    except ValueError:
        parts = out.split()
    env = dict(p.split("=", 1) for p in parts if "=" in p)
    missing = [k for k in (URL_ENV, KEY_ENV) if not env.get(k, "").strip()]
    if missing:
        return False, "hub unit missing " + ",".join(missing)
    return True, "present"


def check_uplink_volume(ctx: Ctx):
    rc, out = run(["pactl", "get-source-volume", "phone_uplink.monitor"], 6)
    pcts = [int(x) for x in re.findall(r"(\d+)%", out)]
    if rc != 0 or not pcts:
        return False, f"phone_uplink.monitor volume unreadable ({out.strip()[:120]})"
    if all(p == 100 for p in pcts):
        return True, "100%"
    return False, f"phone_uplink.monitor volume {'/'.join(map(str, pcts))}% (want 100%)"


def check_stale_bridge(ctx: Ctx):
    since = ctx.last_run or (now_utc() - timedelta(minutes=5))
    # Bound the window: never look back more than 15 min.
    since = max(since, now_utc() - timedelta(minutes=15))
    since_s = since.astimezone().strftime("%Y-%m-%d %H:%M:%S")
    rc, out = run(["journalctl", "--user", "-u", "gsm2computer-hub", "--since", since_s,
                   "--no-pager", "-o", "cat"], 15)
    if rc == 124:
        return None, "journalctl timeout"
    n = out.count(STALE_BRIDGE_PAT)
    ctx.info["stale_bridge_rejects"] = n
    if n:
        return False, f"{n} stale-bridge websocket reject(s) since {iso_et(since)}"
    return True, "0 rejects"


def check_e2e(ctx: Ctx):
    st = ctx.state.setdefault("e2e", {})
    try:
        d = json.loads(E2E_LAST.read_text())
    except Exception as e:
        return False, f"e2e last-run unreadable ({type(e).__name__})"
    ts = parse_iso(d.get("ts"))
    r = d.get("result") or {}
    if ts is None:
        return False, "e2e last-run has no ts"
    age = (now_utc() - ts).total_seconds()
    ok = bool(r.get("ok"))
    skipped = r.get("step") == "skipped" or bool(r.get("aborted_for_real_call"))
    if ok:
        st["last_pass"] = ts.isoformat()
    last_pass = parse_iso(st.get("last_pass")) or parse_iso(st.setdefault("baseline", now_utc().isoformat()))
    ctx.info["e2e_last"] = f"{'PASS' if ok else ('skipped' if skipped else 'FAIL')} {iso_et(ts)}"
    if ctx.real_busy:
        return None, "skipped (real call in progress)"
    if age > E2E_MAX_AGE_S:
        return False, f"e2e self-test stale: last run {int(age // 60)} min ago ({iso_et(ts)})"
    if ok:
        return True, f"PASS {int(age // 60)} min ago latency={r.get('latency_s', 0):.1f}s"
    if skipped:
        no_pass = (now_utc() - last_pass).total_seconds() if last_pass else 0
        if no_pass > E2E_MAX_NO_PASS_S:
            return False, f"e2e skipped ({r.get('error')}) and no PASS for {int(no_pass // 60)} min"
        return True, f"skipped ({r.get('error')}), last PASS {iso_et(last_pass) if last_pass else '?'}"
    return False, f"e2e FAIL at {iso_et(ts)} step={r.get('step')} error={str(r.get('error'))[:160]}"


def _wav_peak_db(path: Path, timeout: float = 180) -> tuple[Optional[float], Optional[float]]:
    """Return (max_db, duration_s) via ffmpeg volumedetect (niced)."""
    dur = None
    try:
        with wave.open(str(path)) as w:
            dur = w.getnframes() / float(w.getframerate() or 1)
    except Exception:
        pass
    rc, out = run(["nice", "-n", "15", "ffmpeg", "-hide_banner", "-nostats", "-i", str(path),
                   "-af", "volumedetect", "-f", "null", "-"], timeout)
    m = re.search(r"max_volume:\s*([-0-9.]+)\s*dB", out)
    if dur is None:
        md = re.search(r"Duration:\s*(\d+):(\d+):(\d+\.\d+)", out)
        if md:
            dur = int(md.group(1)) * 3600 + int(md.group(2)) * 60 + float(md.group(3))
    return (float(m.group(1)) if m else None), dur


def _load_checked() -> set[str]:
    ids: set[str] = set()
    try:
        for line in CHECKED_CALLS.read_text(errors="replace").splitlines():
            tok = line.strip().split()[0] if line.strip() else ""
            if tok.startswith("tap:"):
                tok = tok[4:]
            if tok:
                ids.add(tok)
    except FileNotFoundError:
        pass
    # Also honour IDs recorded by the old Grok Bot health routine's JSON state.
    for extra in EXTRA_CHECKED_FILES:
        try:
            ids.update(re.findall(r"\d{8}T\d{6}Z-[A-Za-z0-9-]+", Path(extra).read_text(errors="replace")))
        except OSError:
            pass
    return ids


def check_reply_audio(ctx: Ctx):
    """Newly ended real calls must have non-silent OpenClaw reply audio."""
    st = ctx.state.setdefault("reply_audio", {})
    checked = _load_checked() | set(st.get("checked") or [])
    if not CALLS_DIR.is_dir():
        return None, "no calls dir"
    first_run = not st.get("baseline_done")
    now = time.time()
    dirs = sorted(p for p in CALLS_DIR.iterdir() if p.is_dir() and "e2e" not in p.name)
    newly: list[str] = []
    silent: list[str] = []
    measured = 0
    notes = []
    for d in dirs:
        if d.name in checked:
            continue
        try:
            mtimes = [f.stat().st_mtime for f in d.iterdir()] or [d.stat().st_mtime]
        except OSError:
            continue
        newest = max(mtimes)
        if first_run and now - newest > 24 * 3600:
            newly.append(d.name)  # baseline: old calls the 15-min routine already covered
            continue
        if ctx.real_busy and d == dirs[-1]:
            continue  # current call still recording
        if now - newest < 90:
            continue  # still being written / just ended
        if measured >= 3:
            break  # bound runtime; rest next run
        wav = next((d / n for n in ("openclaw-spk-48k-stereo.wav", "gsm-downlink-8k-mono.wav")
                    if (d / n).exists()), None)
        newly.append(d.name)
        if wav is None:
            notes.append(f"{d.name}: no reply wav")
            continue
        measured += 1
        max_db, dur = _wav_peak_db(wav)
        rec = {"id": d.name, "max_db": max_db, "dur_s": round(dur or 0, 1), "at": iso_et()}
        st.setdefault("recent", []).append(rec)
        st["recent"] = st["recent"][-20:]
        if max_db is not None and max_db <= -80.0 and (dur or 0) >= 10.0:
            silent.append(f"{d.name} ({wav.name} max {max_db} dB over {dur:.0f}s)")
        else:
            notes.append(f"{d.name}: max {max_db} dB")
    st["baseline_done"] = True
    if newly:
        st["checked"] = sorted(set(st.get("checked") or []) | set(newly))[-2000:]
        try:
            with CHECKED_CALLS.open("a") as f:
                for cid in newly:
                    f.write(f"{cid}\n")
        except OSError:
            pass
    if silent:
        return False, "silent OpenClaw reply on ended real call(s): " + "; ".join(silent)
    if measured:
        return True, "; ".join(notes)[:300]
    return None, ("; ".join(notes) or "no newly ended real calls")[:300]


def build_checks() -> list[tuple[str, Callable[[Ctx], tuple]]]:
    checks: list[tuple[str, Callable]] = [("hub_health", check_hub_health)]
    checks.append(("talk_page", check_talk_page))
    for name, url in PORTALS.items():
        checks.append((name, make_portal_check(url)))
    for u in UNITS:
        checks.append((f"unit:{u}", make_unit_check(u)))
    checks += [
        ("pixel_ping", check_pixel_ping),
        ("webhook_env", check_webhook_env),
        ("uplink_volume", check_uplink_volume),
        ("stale_bridge", check_stale_bridge),
        ("e2e_selftest", check_e2e),
        ("reply_audio", check_reply_audio),
    ]
    return checks


# ---------------------------------------------------------------- main

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--state-dir", default=os.environ.get(
        "CUP_WATCHDOG_STATE_DIR", str(HOME / ".local" / "state" / "cup-watchdog")))
    ap.add_argument("--dry-run", action="store_true",
                    help="print payloads instead of POSTing (treated as delivered in this state dir)")
    ap.add_argument("--force-fail", default="",
                    help="comma list of checks to force-fail (detail marked TEST, payload test=true)")
    ap.add_argument("--only", default="", help="comma list: run only these checks")
    ap.add_argument("--confirm-runs", type=int, default=int(os.environ.get("CUP_WATCHDOG_CONFIRM_RUNS", "2")))
    ap.add_argument("--webhook-url", default=None,
                    help="override webhook URL (for local sink tests; real key is NOT sent)")
    ap.add_argument("--no-summary", action="store_true")
    ap.add_argument("--summary-now", action="store_true", help="emit daily_summary this run")
    ap.add_argument("--verbose", "-v", action="store_true")
    args = ap.parse_args()

    post_enabled = os.environ.get("CUP_WATCHDOG_POST", "1") not in ("0", "false", "no", "")
    forced = {c.strip() for c in args.force_fail.split(",") if c.strip()}
    only = {c.strip() for c in args.only.split(",") if c.strip()}
    test_mode = bool(forced)

    sd = Path(args.state_dir).expanduser()
    sd.mkdir(parents=True, exist_ok=True)
    lock = open(sd / ".lock", "w")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        print("another cup-watchdog run holds the lock; exiting", file=sys.stderr)
        return 0

    state_path = sd / "state.json"
    try:
        state = json.loads(state_path.read_text())
    except Exception:
        state = {}
    state.setdefault("checks", {})
    state.setdefault("events", [])
    t0 = time.time()
    now = now_utc()
    last_run = parse_iso(state.get("last_run"))
    ctx = Ctx(state, last_run)

    results: dict[str, dict] = {}
    for name, fn in build_checks():
        if only and name not in only:
            continue
        try:
            ok, detail = fn(ctx)
        except Exception as e:  # a broken check is itself a failure, never a crash
            ok, detail = False, f"check crashed: {type(e).__name__}: {e}"[:200]
        if name in forced:
            ok, detail = False, f"TEST forced failure via --force-fail (real result: {detail})"
        results[name] = {"ok": ok, "detail": detail}

    # Unit restart deltas (informational, go into events log + summary).
    prev_nr = state.get("nrestarts") or {}
    for u, nr in (ctx.info.get("nrestarts") or {}).items():
        try:
            if u in prev_nr and int(nr) > int(prev_nr[u]):
                state["events"].append({"ts": now.isoformat(), "event": "unit_restart",
                                        "check": f"unit:{u}", "detail": f"NRestarts {prev_nr[u]}->{nr}"})
        except ValueError:
            pass
    if ctx.info.get("nrestarts"):
        state["nrestarts"] = {**prev_nr, **ctx.info["nrestarts"]}

    # State machine.
    downs, reminders, recovers = [], [], []
    for name, r in results.items():
        cs = state["checks"].setdefault(name, {"status": "up", "fail_streak": 0})
        cs["last_detail"] = r["detail"]
        cs["last_ok"] = r["ok"]
        need = CONFIRM_OVERRIDE.get(name, args.confirm_runs)
        if r["ok"] is None:
            continue
        if r["ok"]:
            cs["fail_streak"] = 0
            cs.pop("first_fail", None)
            if cs.get("status") == "down":
                cs["status"] = "up"
                if cs.get("notified"):
                    recovers.append({"check": name, "detail": r["detail"], "since": cs.get("since"),
                                     "test": bool(cs.get("test")),
                                     "down_for_min": int((now - (parse_iso(cs.get("since")) or now)).total_seconds() // 60)})
                cs["notified"] = False
                cs["reminded"] = False
                cs["recovered_at"] = now.isoformat()
            continue
        cs["fail_streak"] = int(cs.get("fail_streak") or 0) + 1
        cs.setdefault("first_fail", now.isoformat())
        if cs.get("status") != "down" and cs["fail_streak"] >= need:
            cs["status"] = "down"
            cs["since"] = cs["first_fail"]
            cs["notified"] = False
            cs["reminded"] = False
            cs["test"] = test_mode
        if cs.get("status") == "down":
            entry = {"check": name, "detail": r["detail"], "since": iso_et(parse_iso(cs["since"]))}
            if not cs.get("notified"):
                downs.append(entry)
            elif (RENOTIFY_MIN and not cs.get("reminded")
                  and (now - (parse_iso(cs.get("notified_at")) or now)).total_seconds() >= RENOTIFY_MIN * 60):
                reminders.append({**entry, "reminder": True})

    url, key = ("", "") if args.dry_run else webhook_config(args.webhook_url)
    post_log: list[str] = []

    def deliver(payload: dict) -> bool:
        if args.dry_run:
            print("DRY-RUN would POST:", json.dumps(payload, indent=2, ensure_ascii=False))
            post_log.append(f"{payload['event']}:dry-run")
            return True
        if not post_enabled:
            post_log.append(f"{payload['event']}:held(CUP_WATCHDOG_POST=0)")
            return False
        ok, st = post_webhook(payload, url, key)
        post_log.append(f"{payload['event']}:{st}")
        return ok

    def base(event: str, items: list[dict]) -> dict:
        p = {
            "source": "cup-watchdog",
            "event": event,
            "host": HOST,
            "check": ",".join(i["check"] for i in items),
            "detail": "; ".join(f"{i['check']}: {i['detail']}" for i in items)[:1500],
            "since": min((i.get("since") or iso_et(now)) for i in items) if items else None,
            "checks": items,
            "busy": ctx.busy,
            "real_call_busy": ctx.real_busy,
            "ts": iso_et(now),
            "test": test_mode or any(i.get("test") for i in items),
        }
        if p["test"] and "TEST" not in p["detail"]:
            p["detail"] = "TEST " + p["detail"]
        return p

    alert_items = downs + reminders
    if alert_items:
        p = base("down", alert_items)
        p["reminder"] = bool(reminders) and not downs
        p["text"] = (f"cup-watchdog{' TEST' if test_mode else ''} DOWN on {HOST}: {p['detail']}"
                     f" (line {'busy' if ctx.busy else 'idle'}; watchdog does no remediation)")[:1800]
        if deliver(p):
            for i in alert_items:
                cs = state["checks"][i["check"]]
                if i.get("reminder"):
                    cs["reminded"] = True
                else:
                    cs["notified"] = True
                    cs["notified_at"] = now.isoformat()
            for i in downs:
                state["events"].append({"ts": now.isoformat(), "event": "down", "check": i["check"],
                                        "detail": i["detail"][:300], "test": test_mode})

    # Retry recovered notifications that failed previously.
    pending_rec = state.get("pending_recovered") or []
    recovers = pending_rec + recovers
    state["pending_recovered"] = []
    if recovers:
        for i in recovers:
            i["since"] = iso_et(parse_iso(i.get("since"))) if parse_iso(i.get("since")) else i.get("since")
        p = base("recovered", recovers)
        p["text"] = f"cup-watchdog{' TEST' if p['test'] else ''} RECOVERED on {HOST}: {p['detail']}"[:1800]
        if deliver(p):
            for i in recovers:
                state["events"].append({"ts": now.isoformat(), "event": "recovered", "check": i["check"],
                                        "test": bool(i.get("test"))})
        else:
            state["pending_recovered"] = recovers

    # Daily summary ~08:45 ET (window until 10:00 so a missed run still sends).
    now_et = now.astimezone(ET)
    hh, mm = (int(x) for x in SUMMARY_AT.split(":"))
    today = now_et.date().isoformat()
    in_window = (now_et.hour, now_et.minute) >= (hh, mm) and now_et.hour < 10
    if not args.no_summary and not only and not test_mode and (
            args.summary_now or (in_window and state.get("last_summary_date") != today)):
        cutoff = now - timedelta(hours=24)
        ev24 = [e for e in state["events"] if (parse_iso(e.get("ts")) or now) >= cutoff and not e.get("test")]
        down_now = sorted(n for n, c in state["checks"].items() if c.get("status") == "down")
        runs = [parse_iso(x) for x in state.get("run_times", [])]
        p = {
            "source": "cup-watchdog",
            "event": "daily_summary",
            "host": HOST,
            "check": "all",
            "detail": (f"last 24h: {sum(e['event'] == 'down' for e in ev24)} down, "
                       f"{sum(e['event'] == 'recovered' for e in ev24)} recovered, "
                       f"{sum(e['event'] == 'unit_restart' for e in ev24)} unit restarts; "
                       f"currently {'DOWN: ' + ', '.join(down_now) if down_now else 'all OK'}"),
            "since": iso_et(cutoff),
            "counts_24h": {
                "down": sum(e["event"] == "down" for e in ev24),
                "recovered": sum(e["event"] == "recovered" for e in ev24),
                "unit_restart": sum(e["event"] == "unit_restart" for e in ev24),
                "watchdog_runs": sum(1 for r in runs if r and r >= cutoff),
            },
            "events_24h": ev24[-30:],
            "current": {n: {"status": c.get("status"), "detail": c.get("last_detail")}
                        for n, c in sorted(state["checks"].items())},
            "down_now": down_now,
            "e2e_last": ctx.info.get("e2e_last"),
            "nrestarts": state.get("nrestarts"),
            "busy": ctx.busy,
            "ts": iso_et(now),
            "test": False,
        }
        p["text"] = f"cup-watchdog daily summary {HOST}: {p['detail']}"
        if deliver(p):
            state["last_summary_date"] = today

    # Bookkeeping.
    keep = now - timedelta(days=EVENT_KEEP_DAYS)
    state["events"] = [e for e in state["events"] if (parse_iso(e.get("ts")) or now) >= keep][-500:]
    rt = [x for x in state.get("run_times", []) if (parse_iso(x) or now) >= now - timedelta(hours=25)]
    rt.append(now.isoformat())
    state["run_times"] = rt[-800:]
    if not only and not test_mode:
        state["last_run"] = now.isoformat()
    dur = round(time.time() - t0, 1)
    down_now = sorted(n for n, c in state["checks"].items() if c.get("status") == "down")
    atomic_write(state_path, json.dumps(state, indent=1, default=str))

    hb = {
        "last_run": iso_et(now),
        "last_run_epoch": int(now.timestamp()),
        "duration_s": dur,
        "overall": "down" if down_now else "ok",
        "down": down_now,
        "busy": ctx.busy,
        "post_enabled": post_enabled,
        "posts_this_run": post_log,
        "runs_24h": sum(1 for x in state["run_times"] if (parse_iso(x) or now) >= now - timedelta(hours=24)),
        "results": {n: ("ok" if r["ok"] else ("skip" if r["ok"] is None else "FAIL")) for n, r in results.items()},
    }
    atomic_write(sd / "heartbeat.json", json.dumps(hb, indent=1))
    atomic_write(sd / "last-results.json", json.dumps({"ts": iso_et(now), "results": results,
                                                        "info": ctx.info}, indent=1, default=str))

    fails = [n for n, r in results.items() if r["ok"] is False]
    line = (f"{iso_et(now)} dur={dur}s busy={ctx.busy} fails={','.join(fails) or '-'} "
            f"down={','.join(down_now) or '-'} posts={','.join(post_log) or '-'}")
    log = sd / "watchdog.log"
    try:
        if log.exists() and log.stat().st_size > 1_000_000:
            os.replace(log, sd / "watchdog.log.1")
        with log.open("a") as f:
            f.write(line + "\n")
    except OSError:
        pass
    print(line)
    if args.verbose:
        for n, r in results.items():
            print(f"  {n:28s} {'ok  ' if r['ok'] else ('skip' if r['ok'] is None else 'FAIL')} {r['detail']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
