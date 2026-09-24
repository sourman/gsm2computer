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
    max_s: float = 2700.0
    ping_s: float = 20.0
    enabled: bool = True

    @classmethod
    def from_env(cls) -> "CallWatchdogConfig":
        return cls(
            ws_idle_s=_env_float("GSM2COMPUTER_CALL_WS_IDLE_S", 60.0),
            uplink_idle_s=_env_float("GSM2COMPUTER_CALL_UPLINK_IDLE_S", 120.0),
            uplink_grace_s=_env_float("GSM2COMPUTER_CALL_UPLINK_GRACE_S", 45.0),
            max_s=_env_float("GSM2COMPUTER_CALL_MAX_S", 2700.0),
            ping_s=_env_float("GSM2COMPUTER_CALL_PING_S", 20.0),
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
        self.abort = asyncio.Event()
        self.abort_reason: Optional[str] = None

    def claim(self, path: str = "") -> bool:
        if self.busy:
            return False
        self.busy = True
        self.claimed_at = self._clock()
        self.established_at = None
        self.last_ws_at = None
        self.last_uplink_at = None
        self.path = path
        self.abort = asyncio.Event()
        self.abort_reason = None
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
        self.abort_reason = None
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

        return {
            "busy": self.busy,
            "path": self.path or None,
            "age_s": _ago(self.claimed_at),
            "established_s": _ago(self.established_at),
            "last_ws_s": _ago(self.last_ws_at),
            "last_uplink_s": _ago(self.last_uplink_at),
            "abort_reason": self.abort_reason,
        }




ADMIN_RELEASE_FRESH_S = 10.0


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
