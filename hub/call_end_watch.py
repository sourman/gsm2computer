#!/usr/bin/env python3
"""Phone-side call-end watcher (ADB) → Grok Bot call_ended webhook.

Source of truth: Pixel telephony/telecom via adb (not OpenClaw Talk UI).
Detects OFFHOOK/ACTIVE → IDLE, debounces, fires once per call, persists state
across restarts. Read-only toward the live call path.

Usage:
  call_end_watch.py              # run forever (systemd)
  call_end_watch.py --dry-run    # POST one test:true call_ended payload and exit
"""
from __future__ import annotations

import argparse
import json
import logging
import os
import re
import subprocess
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional
from zoneinfo import ZoneInfo

from alert_webhook import post_alert_event

LOG = logging.getLogger("gsm2computer-call-end-watch")

ET = ZoneInfo("America/New_York")
ADB_SERIAL = os.environ.get("GSM2COMPUTER_CALL_END_ADB", "100.85.191.25:5555")
HUB_HTTP = os.environ.get("GSM2COMPUTER_E2E_HUB") or os.environ.get(
    "GSM2COMPUTER_HUB_HTTP", "http://100.119.126.42:8787"
)
POLL_S = float(os.environ.get("GSM2COMPUTER_CALL_END_POLL_S", "2.5"))
MIN_DURATION_S = float(os.environ.get("GSM2COMPUTER_CALL_END_MIN_S", "5"))
IDLE_CONFIRM_POLLS = int(os.environ.get("GSM2COMPUTER_CALL_END_IDLE_POLLS", "2"))
STATE_PATH = Path(
    os.environ.get(
        "GSM2COMPUTER_CALL_END_STATE",
        str(Path.home() / "gsm2computer-call-end-watch" / "state.json"),
    )
)
LOG_PATH = Path(
    os.environ.get(
        "GSM2COMPUTER_CALL_END_LOG",
        str(Path.home() / "gsm2computer-call-end-watch" / "watch.log"),
    )
)

# Android TelephonyManager
CALL_STATE_IDLE = 0
CALL_STATE_RINGING = 1
CALL_STATE_OFFHOOK = 2

_TELECOM_CALL_RE = re.compile(
    r"\[Call id=(TC@\d+),\s*state=([A-Z_]+).*?handle=(tel:[^,\]]+)",
    re.DOTALL,
)
_CALL_STATE_RE = re.compile(r"mCallState=(\d+)")
_INCOMING_RE = re.compile(r"mCallIncomingNumber=([^\s]*)")


@dataclass
class PhoneSnapshot:
    in_call: bool
    call_state: int  # max mCallState seen
    call_id: Optional[str] = None
    number: Optional[str] = None
    telecom_state: Optional[str] = None
    raw_ok: bool = True


@dataclass
class WatchState:
    in_call: bool = False
    call_state: int = CALL_STATE_IDLE
    call_id: Optional[str] = None
    number: Optional[str] = None
    offhook_at: Optional[float] = None  # monotonic
    offhook_wall: Optional[str] = None  # ISO UTC
    last_fired_call_id: Optional[str] = None
    last_fired_at: Optional[str] = None
    idle_streak: int = 0
    # Hub observations during the active call (for dropped_suspected).
    hub_saw_busy: bool = False
    hub_ended_while_phone_live: bool = False
    hub_ws_died: bool = False
    hub_uplink_died: bool = False
    last_hub: dict[str, Any] = field(default_factory=dict)

    def to_json(self) -> dict[str, Any]:
        return {
            "in_call": self.in_call,
            "call_state": self.call_state,
            "call_id": self.call_id,
            "number": self.number,
            "offhook_at": self.offhook_at,
            "offhook_wall": self.offhook_wall,
            "last_fired_call_id": self.last_fired_call_id,
            "last_fired_at": self.last_fired_at,
            "idle_streak": self.idle_streak,
            "hub_saw_busy": self.hub_saw_busy,
            "hub_ended_while_phone_live": self.hub_ended_while_phone_live,
            "hub_ws_died": self.hub_ws_died,
            "hub_uplink_died": self.hub_uplink_died,
        }

    @classmethod
    def from_json(cls, data: dict[str, Any]) -> "WatchState":
        st = cls()
        st.in_call = bool(data.get("in_call"))
        st.call_state = int(data.get("call_state") or CALL_STATE_IDLE)
        st.call_id = data.get("call_id")
        st.number = data.get("number")
        # Don't restore offhook_at monotonic across restarts — use wall if needed.
        st.offhook_at = None
        st.offhook_wall = data.get("offhook_wall")
        st.last_fired_call_id = data.get("last_fired_call_id")
        st.last_fired_at = data.get("last_fired_at")
        st.idle_streak = int(data.get("idle_streak") or 0)
        st.hub_saw_busy = bool(data.get("hub_saw_busy"))
        st.hub_ended_while_phone_live = bool(data.get("hub_ended_while_phone_live"))
        st.hub_ws_died = bool(data.get("hub_ws_died"))
        st.hub_uplink_died = bool(data.get("hub_uplink_died"))
        return st


def setup_logging() -> None:
    LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
    LOG.setLevel(logging.INFO)
    fmt = logging.Formatter("%(asctime)s %(levelname)s %(message)s")
    LOG.handlers.clear()
    sh = logging.StreamHandler()
    sh.setFormatter(fmt)
    LOG.addHandler(sh)
    from logging.handlers import RotatingFileHandler

    fh = RotatingFileHandler(LOG_PATH, maxBytes=1_000_000, backupCount=4)
    fh.setFormatter(fmt)
    LOG.addHandler(fh)


def _adb(args: list[str], timeout_s: float = 8.0) -> tuple[int, str]:
    cmd = ["adb", "-s", ADB_SERIAL, *args]
    try:
        proc = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=timeout_s,
            check=False,
        )
        out = (proc.stdout or "") + (proc.stderr or "")
        return proc.returncode, out
    except subprocess.TimeoutExpired:
        return 124, "adb timeout"
    except FileNotFoundError:
        return 127, "adb missing"


def ensure_adb() -> bool:
    rc, out = _adb(["get-state"], timeout_s=5.0)
    if rc == 0 and "device" in out:
        return True
    LOG.warning("adb not ready (%s); reconnecting", (out or "").strip()[:80])
    subprocess.run(
        ["adb", "connect", ADB_SERIAL],
        capture_output=True,
        text=True,
        timeout=8,
        check=False,
    )
    time.sleep(0.5)
    rc2, out2 = _adb(["get-state"], timeout_s=5.0)
    ok = rc2 == 0 and "device" in out2
    if not ok:
        LOG.warning("adb reconnect failed")
    return ok


def read_phone_snapshot() -> PhoneSnapshot:
    """Combine telephony.registry mCallState with dumpsys telecom Call rows."""
    if not ensure_adb():
        return PhoneSnapshot(in_call=False, call_state=CALL_STATE_IDLE, raw_ok=False)

    rc_t, tel = _adb(["shell", "dumpsys", "telephony.registry"], timeout_s=10.0)
    rc_c, telecom = _adb(["shell", "dumpsys", "telecom"], timeout_s=10.0)
    if rc_t != 0 and rc_c != 0:
        return PhoneSnapshot(in_call=False, call_state=CALL_STATE_IDLE, raw_ok=False)

    states = [int(x) for x in _CALL_STATE_RE.findall(tel or "")]
    call_state = max(states) if states else CALL_STATE_IDLE

    numbers = [n for n in _INCOMING_RE.findall(tel or "") if n]
    number = numbers[0] if numbers else None

    call_id = None
    telecom_state = None
    in_call_telecom = False
    for m in _TELECOM_CALL_RE.finditer(telecom or ""):
        cid, st, handle = m.group(1), m.group(2), m.group(3)
        if st in ("ACTIVE", "DIALING", "RINGING", "CONNECTING", "ON_HOLD"):
            in_call_telecom = True
            call_id = cid
            telecom_state = st
            if handle.startswith("tel:"):
                number = handle[4:] or number
            break

    in_call = in_call_telecom or call_state in (CALL_STATE_RINGING, CALL_STATE_OFFHOOK)
    if in_call and call_state == CALL_STATE_IDLE and in_call_telecom:
        call_state = CALL_STATE_OFFHOOK if telecom_state != "RINGING" else CALL_STATE_RINGING

    # Redact number in logs later; keep full for payload (Cup needs it).
    return PhoneSnapshot(
        in_call=in_call,
        call_state=call_state,
        call_id=call_id,
        number=number,
        telecom_state=telecom_state,
        raw_ok=True,
    )


def load_state() -> WatchState:
    try:
        if STATE_PATH.is_file():
            return WatchState.from_json(json.loads(STATE_PATH.read_text()))
    except Exception as exc:
        LOG.warning("state load failed: %s", exc)
    return WatchState()


def save_state(st: WatchState) -> None:
    STATE_PATH.parent.mkdir(parents=True, exist_ok=True)
    tmp = STATE_PATH.with_suffix(".tmp")
    tmp.write_text(json.dumps(st.to_json(), indent=2))
    tmp.replace(STATE_PATH)


def hub_health() -> dict[str, Any]:
    url = f"{HUB_HTTP.rstrip('/')}/health"
    try:
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=4.0) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception as exc:
        return {"ok": False, "error": type(exc).__name__}


def _ago(val: Any) -> Optional[float]:
    try:
        if val is None:
            return None
        return float(val)
    except (TypeError, ValueError):
        return None


def observe_hub_during_call(st: WatchState, health: dict[str, Any]) -> None:
    call = health.get("call") or {}
    busy = bool(call.get("busy")) and not bool(call.get("e2e"))
    if busy:
        st.hub_saw_busy = True
    elif st.hub_saw_busy and st.in_call:
        st.hub_ended_while_phone_live = True
    ws = _ago(call.get("last_ws_s"))
    up = _ago(call.get("last_uplink_s"))
    # If hub still busy but ws/uplink went quiet for a long time, note it.
    if busy and ws is not None and ws > 45:
        st.hub_ws_died = True
    if busy and up is not None and up > 45:
        st.hub_uplink_died = True
    st.last_hub = {
        "busy": call.get("busy"),
        "e2e": call.get("e2e"),
        "path": call.get("path"),
        "last_ws_s": call.get("last_ws_s"),
        "last_uplink_s": call.get("last_uplink_s"),
        "abort_reason": call.get("abort_reason"),
        "talk_active": (health.get("talk") or {}).get("talk_active"),
        "webrtc_connected": (health.get("talk") or {}).get("webrtc_connected"),
        "cdp": (health.get("talk") or {}).get("cdp"),
        "cdp_wedged": (health.get("talk") or {}).get("cdp_wedged"),
    }


def journal_summary(since_s: float = 600.0) -> dict[str, Any]:
    """Cheap hub journal + last_call_tap skim for the call window."""
    out: dict[str, Any] = {
        "openclaw_replied_suspected": False,
        "ws_drop": False,
        "half_open_or_release": False,
        "spk_peak": None,
        "downlink_peak": None,
        "mic_peak": None,
        "tap_id": None,
        "journal_hits": [],
    }
    try:
        proc = subprocess.run(
            [
                "journalctl",
                "--user",
                "-u",
                "gsm2computer-hub.service",
                "--since",
                f"{int(since_s)} seconds ago",
                "--no-pager",
            ],
            capture_output=True,
            text=True,
            timeout=8,
            check=False,
        )
        text = proc.stdout or ""
        patterns = [
            ("response.created", "openclaw_replied_suspected"),
            ("WebSocket disconnected", "ws_drop"),
            ("force-release", "half_open_or_release"),
            ("call/release", "half_open_or_release"),
            ("half-open", "half_open_or_release"),
            ("handshake failed", "ws_drop"),
            ("re-linked openclaw_phone_mic", None),
            ("talk ready", None),
        ]
        hits = []
        for needle, flag in patterns:
            if needle.lower() in text.lower():
                hits.append(needle)
                if flag == "openclaw_replied_suspected":
                    out["openclaw_replied_suspected"] = True
                elif flag == "ws_drop":
                    out["ws_drop"] = True
                elif flag == "half_open_or_release":
                    out["half_open_or_release"] = True
        out["journal_hits"] = hits[:20]
    except Exception as exc:
        out["journal_error"] = type(exc).__name__

    health = hub_health()
    tap = health.get("last_call_tap") or {}
    streams = tap.get("streams") or {}
    out["tap_id"] = tap.get("id")
    try:
        out["spk_peak"] = float((streams.get("openclaw-spk-48k-stereo") or {}).get("peak") or 0) or None
    except (TypeError, ValueError):
        pass
    try:
        out["downlink_peak"] = float((streams.get("gsm-downlink-8k-mono") or {}).get("peak") or 0) or None
    except (TypeError, ValueError):
        pass
    try:
        out["mic_peak"] = float((streams.get("openclaw-mic-48k-stereo") or {}).get("peak") or 0) or None
    except (TypeError, ValueError):
        pass
    if (out.get("spk_peak") or 0) > 0.02 or (out.get("downlink_peak") or 0) > 0.02:
        out["openclaw_replied_suspected"] = True
    return out


def build_payload(
    *,
    st: WatchState,
    duration_s: float,
    test: bool = False,
) -> dict[str, Any]:
    now = datetime.now(timezone.utc)
    now_et = now.astimezone(ET)
    health = hub_health()
    call = health.get("call") or {}
    talk = health.get("talk") or {}
    window = max(duration_s + 30.0, 120.0)
    summary = journal_summary(since_s=window)

    dropped = bool(
        st.hub_ended_while_phone_live
        or st.hub_ws_died
        or st.hub_uplink_died
        or (
            st.hub_saw_busy
            and not bool(call.get("busy"))
            and duration_s > MIN_DURATION_S
        )
    )

    return {
        "event": "call_ended",
        "test": bool(test),
        "call_id": st.call_id,
        "caller_number": st.number,
        "ended_at_utc": now.isoformat(),
        "ended_at_et": now_et.isoformat(),
        "started_at_utc": st.offhook_wall,
        "duration_s": round(duration_s, 1),
        "phone_call_state_final": CALL_STATE_IDLE,
        "source": "adb_telephony_telecom",
        "adb_serial": ADB_SERIAL,
        "dropped_suspected": dropped,
        "hub_health": {
            "ok": health.get("ok"),
            "busy": call.get("busy"),
            "e2e": call.get("e2e"),
            "path": call.get("path"),
            "last_ws_s": call.get("last_ws_s"),
            "last_uplink_s": call.get("last_uplink_s"),
            "abort_reason": call.get("abort_reason"),
            "talk_active": talk.get("talk_active"),
            "webrtc_connected": talk.get("webrtc_connected"),
            "cdp": talk.get("cdp"),
            "cdp_wedged": talk.get("cdp_wedged"),
            "talk_error": talk.get("error"),
        },
        "hub_during_call": {
            "saw_busy": st.hub_saw_busy,
            "ended_while_phone_live": st.hub_ended_while_phone_live,
            "ws_died": st.hub_ws_died,
            "uplink_died": st.hub_uplink_died,
            "last_sample": st.last_hub,
        },
        "call_summary": summary,
        "text": (
            f"call_ended duration_s={duration_s:.0f} "
            f"dropped_suspected={dropped} "
            f"call_id={st.call_id or '-'}"
            + (" TEST" if test else "")
        ),
    }


def fire_call_ended(st: WatchState, duration_s: float, *, test: bool = False) -> None:
    payload = build_payload(st=st, duration_s=duration_s, test=test)
    post_alert_event(payload)
    st.last_fired_call_id = st.call_id or f"anon-{payload['ended_at_utc']}"
    st.last_fired_at = payload["ended_at_utc"]
    save_state(st)
    LOG.info(
        "fired call_ended test=%s duration_s=%.1f call_id=%s dropped=%s",
        test,
        duration_s,
        (st.call_id or "")[:24],
        payload["dropped_suspected"],
    )


def on_snapshot(st: WatchState, snap: PhoneSnapshot) -> None:
    if not snap.raw_ok:
        # ADB blip — do not invent transitions.
        return

    health = hub_health()
    if snap.in_call:
        observe_hub_during_call(st, health)

    if snap.in_call and not st.in_call:
        # Entering a call (ringing or offhook).
        st.in_call = True
        st.call_state = snap.call_state
        st.call_id = snap.call_id or f"phone-{int(time.time())}"
        st.number = snap.number
        st.offhook_at = time.monotonic()
        st.offhook_wall = datetime.now(timezone.utc).isoformat()
        st.idle_streak = 0
        st.hub_saw_busy = False
        st.hub_ended_while_phone_live = False
        st.hub_ws_died = False
        st.hub_uplink_died = False
        st.last_hub = {}
        observe_hub_during_call(st, health)
        save_state(st)
        LOG.info(
            "call start call_id=%s state=%s telecom=%s",
            st.call_id,
            snap.call_state,
            snap.telecom_state,
        )
        return

    if snap.in_call and st.in_call:
        st.call_state = snap.call_state
        if snap.call_id:
            st.call_id = snap.call_id
        if snap.number:
            st.number = snap.number
        st.idle_streak = 0
        # If we restored after restart mid-call, start duration clock now.
        if st.offhook_at is None:
            st.offhook_at = time.monotonic()
            if not st.offhook_wall:
                st.offhook_wall = datetime.now(timezone.utc).isoformat()
            LOG.info("mid-call resume call_id=%s (no fire)", st.call_id)
        save_state(st)
        return

    if (not snap.in_call) and st.in_call:
        st.idle_streak += 1
        if st.idle_streak < IDLE_CONFIRM_POLLS:
            save_state(st)
            return
        # Confirmed idle after being in-call.
        duration = 0.0
        if st.offhook_at is not None:
            duration = max(0.0, time.monotonic() - st.offhook_at)
        elif st.offhook_wall:
            try:
                started = datetime.fromisoformat(st.offhook_wall)
                duration = max(0.0, (datetime.now(timezone.utc) - started).total_seconds())
            except Exception:
                duration = 0.0

        call_id = st.call_id
        already = call_id and call_id == st.last_fired_call_id
        LOG.info(
            "call end call_id=%s duration_s=%.1f already_fired=%s",
            call_id,
            duration,
            already,
        )
        if already:
            pass
        elif duration < MIN_DURATION_S:
            LOG.info("skip call_ended (duration %.1fs < %.1fs)", duration, MIN_DURATION_S)
        else:
            fire_call_ended(st, duration_s=duration, test=False)

        # Reset active call fields.
        st.in_call = False
        st.call_state = CALL_STATE_IDLE
        st.call_id = None
        st.number = None
        st.offhook_at = None
        st.offhook_wall = None
        st.idle_streak = 0
        st.hub_saw_busy = False
        st.hub_ended_while_phone_live = False
        st.hub_ws_died = False
        st.hub_uplink_died = False
        st.last_hub = {}
        save_state(st)
        return

    # Idle → idle
    st.in_call = False
    st.call_state = CALL_STATE_IDLE
    st.idle_streak = 0
    save_state(st)


def sync_on_startup(st: WatchState, snap: PhoneSnapshot) -> None:
    """Align persisted state with phone without firing (no observed transition)."""
    if not snap.raw_ok:
        LOG.warning("startup: adb unavailable; keeping persisted state")
        return
    if snap.in_call:
        st.in_call = True
        st.call_state = snap.call_state
        st.call_id = snap.call_id or st.call_id or f"phone-{int(time.time())}"
        st.number = snap.number or st.number
        if st.offhook_at is None:
            st.offhook_at = time.monotonic()
        if not st.offhook_wall:
            st.offhook_wall = datetime.now(timezone.utc).isoformat()
        LOG.info("startup: phone already in-call call_id=%s (watch only)", st.call_id)
    else:
        if st.in_call:
            LOG.info(
                "startup: phone idle but state had in_call; clearing without fire "
                "(no observed OFFHOOK→IDLE)"
            )
        st.in_call = False
        st.call_state = CALL_STATE_IDLE
        st.call_id = None
        st.number = None
        st.offhook_at = None
        st.offhook_wall = None
        st.idle_streak = 0
    save_state(st)


def dry_run() -> int:
    """POST one clearly marked test call_ended payload."""
    st = WatchState(
        call_id="TC@dry-run",
        number="+10000000000",
        offhook_wall=datetime.now(timezone.utc).isoformat(),
        hub_saw_busy=True,
    )
    fire_call_ended(st, duration_s=42.0, test=True)
    print(
        json.dumps(
            {
                "dry_run": True,
                "posted_event": "call_ended",
                "test": True,
                "schema_keys": sorted(build_payload(st=st, duration_s=42.0, test=True).keys()),
            },
            indent=2,
        )
    )
    return 0


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="POST one test:true call_ended payload and exit",
    )
    args = parser.parse_args(argv)
    setup_logging()
    if args.dry_run:
        return dry_run()

    st = load_state()
    snap = read_phone_snapshot()
    sync_on_startup(st, snap)
    LOG.info(
        "watching adb=%s poll=%.1fs min_duration=%.0fs hub=%s",
        ADB_SERIAL,
        POLL_S,
        MIN_DURATION_S,
        HUB_HTTP,
    )
    while True:
        try:
            snap = read_phone_snapshot()
            on_snapshot(st, snap)
        except Exception:
            LOG.exception("poll error")
        time.sleep(POLL_S)


if __name__ == "__main__":
    raise SystemExit(main())
