#!/usr/bin/env python3
"""Single-call lock (ADR 0004) plus idle/half-open recovery.

`call_busy` is claimed before the WebSocket 101 and released only after the
GSM handler finishes. A hung Pixel socket that still looks connected would
otherwise 409 every later dial for hours. The watchdog does not clear the
lock itself: it aborts the live handler so the existing finally path stops
PipeWire helpers, Talk, and taps, then releases the slot.
"""
from __future__ import annotations

import asyncio
import os
import time
from dataclasses import dataclass
from typing import Any, Optional


def _env_float(name: str, default: float) -> float:
    raw = os.environ.get(name)
    if raw is None or not str(raw).strip():
        return default
    try:
        return float(raw)
    except ValueError:
        return default


def _env_enabled(name: str, default: bool = True) -> bool:
    raw = os.environ.get(name)
    if raw is None or not str(raw).strip():
        return default
    return str(raw).strip().lower() not in ("0", "false", "no", "off")


@dataclass(frozen=True)
class CallWatchdogConfig:
    """Timeouts in seconds. 0 disables that check."""

    ws_idle_s: float = 60.0
    uplink_idle_s: float = 120.0
    uplink_grace_s: float = 45.0
    max_s: float = 5400.0  # 90 min
    ping_s: float = 20.0
    relink_grace_s: float = 65.0
    enabled: bool = True

    @classmethod
    def from_env(cls) -> "CallWatchdogConfig":
        return cls(
            ws_idle_s=_env_float("GSM2COMPUTER_CALL_WS_IDLE_S", 60.0),
            uplink_idle_s=_env_float("GSM2COMPUTER_CALL_UPLINK_IDLE_S", 120.0),
            uplink_grace_s=_env_float("GSM2COMPUTER_CALL_UPLINK_GRACE_S", 45.0),
            max_s=_env_float("GSM2COMPUTER_CALL_MAX_S", 5400.0),
            ping_s=_env_float("GSM2COMPUTER_CALL_PING_S", 20.0),
            relink_grace_s=_env_float("GSM2COMPUTER_CALL_RELINK_GRACE_S", 65.0),
            enabled=_env_enabled("GSM2COMPUTER_CALL_WATCHDOG", True),
        )


class CallSlot:
    """One live GSM/simulator WebSocket (ADR 0004)."""

    def __init__(self, config: Optional[CallWatchdogConfig] = None, clock=time.monotonic) -> None:
        self.config = config if config is not None else CallWatchdogConfig()
        self._clock = clock
        self.busy = False
        self.claimed_at: Optional[float] = None
        self.established_at: Optional[float] = None
        self.last_ws_at: Optional[float] = None
        self.last_uplink_at: Optional[float] = None
        self.path: str = ""
        self.is_e2e: bool = False
        self.abort = asyncio.Event()
        self.abort_reason: Optional[str] = None
        self.gap_wake = asyncio.Event()
        self.gap_reason: Optional[str] = None
        self.session_id: str = ""
        self.link_gap_since: Optional[float] = None
        self.link_gap_max_s: float = 0.0
        self.link_gap_count: int = 0
        self.ping_sent_at: Optional[float] = None
        self.last_pong_rtt_ms: Optional[float] = None
        self.ts_path: str = ""
        self.ts_relay: str = ""
        self.ts_addr: str = ""
        self.last_close_code: Optional[int] = None
        self.last_close_initiator: str = ""
        self.last_close_reason: str = ""
        # Set by the WS handler: returns DownlinkSendGuard.snapshot() for /health.
        self.downlink_probe: Optional[Any] = None

    def _reset_link_fields(self) -> None:
        self.gap_wake = asyncio.Event()
        self.gap_reason = None
        self.session_id = ""
        self.link_gap_since = None
        self.link_gap_max_s = 0.0
        self.link_gap_count = 0
        self.ping_sent_at = None
        self.last_pong_rtt_ms = None
        self.ts_path = ""
        self.ts_relay = ""
        self.ts_addr = ""
        self.last_close_code = None
        self.last_close_initiator = ""
        self.last_close_reason = ""

    @staticmethod
    def path_is_e2e(path: str) -> bool:
        """Synthetic OpenClaw self-test paths (must yield to a real Pixel call)."""
        cleaned = (path or "").strip().strip("/")
        return cleaned == "e2e-test" or cleaned.startswith("e2e-test/")

    def claim(self, path: str = "") -> bool:
        if self.busy:
            return False
        self.busy = True
        self.claimed_at = self._clock()
        self.established_at = None
        self.last_ws_at = None
        self.last_uplink_at = None
        self.path = path
        self.is_e2e = self.path_is_e2e(path)
        self.abort = asyncio.Event()
        self.abort_reason = None
        self._reset_link_fields()
        return True

    def mark_established(self) -> None:
        now = self._clock()
        self.established_at = now
        if self.last_ws_at is None:
            self.last_ws_at = now

    def note_ws_activity(self) -> None:
        self.last_ws_at = self._clock()

    def note_uplink(self) -> None:
        now = self._clock()
        self.last_uplink_at = now
        self.last_ws_at = now

    def ask_link_gap(self, reason: str) -> None:
        """Wake the reader to hold the call. Grace 0 keeps the old immediate abort."""
        if not self.busy or self.link_gap_since is not None:
            return
        if self.config.relink_grace_s <= 0:
            self.abort_call(reason)
            return
        self.gap_reason = reason
        self.gap_wake.set()

    def take_gap_request(self) -> Optional[str]:
        if not self.gap_wake.is_set():
            return None
        reason = self.gap_reason or "link gap"
        self.gap_reason = None
        self.gap_wake = asyncio.Event()
        return reason

    def note_link_gap(self) -> None:
        if self.link_gap_since is None:
            self.link_gap_since = self._clock()
            self.link_gap_count += 1

    def clear_link_gap(self) -> None:
        if self.link_gap_since is not None:
            dur = self._clock() - self.link_gap_since
            if dur > self.link_gap_max_s:
                self.link_gap_max_s = dur
        self.link_gap_since = None

    def note_pong_rtt(self, rtt_ms: float) -> None:
        self.last_pong_rtt_ms = round(float(rtt_ms), 1)

    def note_tailscale(self, mode: str, relay: str = "", addr: str = "") -> None:
        self.ts_path = mode or ""
        self.ts_relay = relay or ""
        self.ts_addr = addr or ""

    def note_close(self, code: Optional[int], initiator: str, reason: str = "") -> None:
        self.last_close_code = code
        self.last_close_initiator = initiator or ""
        self.last_close_reason = reason or ""

    def abort_call(self, reason: str) -> None:
        if self.abort_reason is None:
            self.abort_reason = reason
        self.abort.set()

    def release(self) -> None:
        self.busy = False
        self.claimed_at = None
        self.established_at = None
        self.last_ws_at = None
        self.last_uplink_at = None
        self.path = ""
        self.is_e2e = False
        self.abort_reason = None
        self.downlink_probe = None
        self._reset_link_fields()
        self.abort.set()
        self.abort = asyncio.Event()

    def check(self, now: Optional[float] = None) -> Optional[str]:
        """Return an abort reason if the live call looks stuck. Does not clear the lock."""
        if not self.config.enabled or not self.busy:
            return None
        if now is None:
            now = self._clock()
        cfg = self.config
        if cfg.max_s > 0 and self.claimed_at is not None:
            age = now - self.claimed_at
            if age >= cfg.max_s:
                return (
                    f"max call duration {age:.0f}s "
                    f"(GSM2COMPUTER_CALL_MAX_S={cfg.max_s:.0f})"
                )
        if self.link_gap_since is not None:
            gap = now - self.link_gap_since
            grace = cfg.relink_grace_s
            limit = (grace + 5.0) if grace > 0 else 0.0
            if gap >= limit:
                return (
                    f"relink grace exceeded {gap:.0f}s "
                    f"(GSM2COMPUTER_CALL_RELINK_GRACE_S={grace:.0f})"
                )
            # The relink waiter owns ws-idle / uplink-idle until grace ends.
            return None
        if self.established_at is None:
            return None
        established_for = now - self.established_at
        if cfg.ws_idle_s > 0 and self.last_ws_at is not None:
            quiet = now - self.last_ws_at
            if quiet >= cfg.ws_idle_s:
                return (
                    f"websocket idle {quiet:.0f}s "
                    f"(GSM2COMPUTER_CALL_WS_IDLE_S={cfg.ws_idle_s:.0f})"
                )
        if cfg.uplink_idle_s > 0 and established_for >= cfg.uplink_grace_s:
            if self.last_uplink_at is None:
                return (
                    f"no gsm uplink audio for {established_for:.0f}s "
                    f"(GSM2COMPUTER_CALL_UPLINK_IDLE_S={cfg.uplink_idle_s:.0f}, "
                    f"grace={cfg.uplink_grace_s:.0f}s)"
                )
            uplink_quiet = now - self.last_uplink_at
            if uplink_quiet >= cfg.uplink_idle_s:
                return (
                    f"gsm uplink idle {uplink_quiet:.0f}s "
                    f"(GSM2COMPUTER_CALL_UPLINK_IDLE_S={cfg.uplink_idle_s:.0f})"
                )
        return None

    def snapshot(self, now: Optional[float] = None) -> dict[str, Any]:
        if now is None:
            now = self._clock()

        def _ago(ts: Optional[float]) -> Optional[float]:
            if ts is None:
                return None
            return round(now - ts, 3)

        out = {
            "busy": self.busy,
            "path": self.path or None,
            "e2e": self.is_e2e,
            "age_s": _ago(self.claimed_at),
            "established_s": _ago(self.established_at),
            "last_ws_s": _ago(self.last_ws_at),
            "last_uplink_s": _ago(self.last_uplink_at),
            "abort_reason": self.abort_reason,
            "session_id": self.session_id or None,
            "link_gap_open": self.link_gap_since is not None,
            "link_gap_s": _ago(self.link_gap_since),
            "link_gap_max_s": round(self.link_gap_max_s, 3),
            "link_gap_count": self.link_gap_count,
            "ws_rtt_ms": self.last_pong_rtt_ms,
            "ts_path": self.ts_path or None,
            "ts_relay": self.ts_relay or None,
            "ts_addr": self.ts_addr or None,
            "last_close_code": self.last_close_code,
            "last_close_initiator": self.last_close_initiator or None,
            "last_close_reason": self.last_close_reason or None,
        }
        probe = self.downlink_probe
        if self.busy and probe is not None:
            try:
                dl = probe() or {}
                out["downlink_stalled_s"] = dl.get("stalled_s")
                out["downlink_max_stall_s"] = dl.get("max_stall_s")
                out["downlink_dropped_frames"] = dl.get("dropped_frames")
            except Exception:
                pass
        return out




ADMIN_RELEASE_FRESH_S = 10.0
HEAL_FRESH_S = 15.0


def call_looks_live(
    snapshot: dict,
    *,
    fresh_s: float = ADMIN_RELEASE_FRESH_S,
) -> bool:
    """True when the slot is busy with recent websocket or uplink activity.

    Used by /admin/call/release to refuse cutting a healthy live call unless
    the caller passes force=1.
    """
    if not snapshot.get("busy"):
        return False
    for key in ("last_ws_s", "last_uplink_s"):
        age = snapshot.get(key)
        if isinstance(age, (int, float)) and age < fresh_s:
            return True
    return False


def disruptive_heal_blocked(health: dict) -> bool:
    """True when Control UI reload or talk-chromium/gateway restart would hit a live path.

    Blocks on talk_active, any busy CallSlot, or fresh last_ws_s / last_uplink_s
    (redial race after busy flipped false).
    """
    call = health.get("call") or {}
    talk = health.get("talk") or {}
    if talk.get("talk_active"):
        return True
    if call.get("busy"):
        return True
    for key in ("last_ws_s", "last_uplink_s"):
        age = call.get(key)
        if isinstance(age, (int, float)) and age < HEAL_FRESH_S:
            return True
    return False


def admin_release_allowed(
    snapshot: dict,
    *,
    force: bool = False,
    fresh_s: float = ADMIN_RELEASE_FRESH_S,
) -> tuple[bool, str]:
    """Return (ok, reason) for an admin CallSlot release request."""
    if force:
        return True, "forced"
    if call_looks_live(snapshot, fresh_s=fresh_s):
        return (
            False,
            "live call with fresh ws/uplink activity; pass force=1 to override",
        )
    return True, "ok"

async def ensure_released_after_abort(
    slot: "CallSlot",
    reason: str,
    *,
    wait_s: float = 5.0,
) -> bool:
    """Abort a live call, then force-release if the same claim is still held.

    Prefer the WebSocket handler's ``finally`` path so PipeWire/Talk cleanup
    runs. When that path is wedged (dead peer, stuck bridge stop, etc.),
    ``abort_call`` alone leaves ``busy=True``. After a short wait, release the
    *same* claim (matched by ``claimed_at``) so a newer dial is not clobbered.

    Returns True if this function called ``release()``.
    """
    if not slot.busy:
        return False
    claimed_at = slot.claimed_at
    slot.abort_call(reason)
    if wait_s > 0:
        await asyncio.sleep(wait_s)
    if slot.busy and slot.claimed_at is not None and slot.claimed_at == claimed_at:
        slot.release()
        return True
    return False



async def preempt_e2e_for_real_call(
    slot: CallSlot,
    *,
    wait_s: float = 5.0,
    poll_s: float = 0.1,
) -> bool:
    """Abort a synthetic e2e holder so a real Pixel call can claim the slot.

    Returns True when the slot is free (or was already free). Returns False if
    the e2e session did not release in time — caller should still 409.
    """
    if not slot.busy:
        return True
    if not slot.is_e2e:
        return False
    slot.abort_call("preempted by real call")
    deadline = time.monotonic() + wait_s
    while slot.busy and time.monotonic() < deadline:
        await asyncio.sleep(poll_s)
    return not slot.busy

async def wait_or_abort(awaitable, abort: asyncio.Event):
    """Return awaitable's result, or None if abort fires first."""
    read_task = asyncio.ensure_future(awaitable)
    abort_task = asyncio.create_task(abort.wait())
    try:
        _done, pending = await asyncio.wait(
            {read_task, abort_task},
            return_when=asyncio.FIRST_COMPLETED,
        )
        for task in pending:
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass
        if abort.is_set():
            return None
        return read_task.result()
    finally:
        if not read_task.done():
            read_task.cancel()
            try:
                await read_task
            except asyncio.CancelledError:
                pass
        if not abort_task.done():
            abort_task.cancel()
            try:
                await abort_task
            except asyncio.CancelledError:
                pass
