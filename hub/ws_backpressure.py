#!/usr/bin/env python3
"""Downlink WebSocket backpressure: survive a transient stall, detect a dead socket.

A short Tailscale hiccup stops TCP ACKs from the Pixel. Awaiting
``writer.drain()`` on the downlink then blocks for the whole outage; the old
code aborted the call after 5 s (TC@352, 2026-09-24 4:35 PM ET: 49 s gap).

Policy here:
- never block the downlink pump on drain; write frames into the transport
  buffer only while the backlog is under ~backlog_s of audio, otherwise drop
  the frame (stale call audio is useless once the link is back);
- the socket is only "dead" when buffered bytes make no forward progress for
  ``dead_s`` (default 45 s). Inbound silence is handled by the CallSlot
  ws-idle watchdog (60 s), which pongs/uplink frames keep fresh.
"""
from __future__ import annotations

import os
import time
from typing import Any, Callable, Optional


def _env_float(name: str, default: float) -> float:
    raw = os.environ.get(name)
    try:
        return float(raw) if raw not in (None, "") else default
    except ValueError:
        return default


DOWNLINK_BACKLOG_S = _env_float("GSM2COMPUTER_WS_DOWNLINK_BACKLOG_S", 0.3)
DOWNLINK_DEAD_S = _env_float("GSM2COMPUTER_WS_DOWNLINK_DEAD_S", 45.0)
FRAME_S = 0.02
MIN_BACKLOG_BYTES = 4096


def transport_buffered(writer: Any) -> int:
    """Bytes queued in the asyncio transport (not yet handed to the kernel)."""
    transport = getattr(writer, "transport", None)
    if transport is None:
        return 0
    try:
        return int(transport.get_write_buffer_size())
    except Exception:
        return 0


def writer_closing(writer: Any) -> bool:
    try:
        return bool(writer.is_closing())
    except Exception:
        return False


class DownlinkSendGuard:
    """Admission + liveness for downlink frames on one WebSocket."""

    def __init__(
        self,
        *,
        backlog_s: float = DOWNLINK_BACKLOG_S,
        dead_s: float = DOWNLINK_DEAD_S,
        frame_s: float = FRAME_S,
        min_backlog_bytes: int = MIN_BACKLOG_BYTES,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.backlog_s = backlog_s
        self.dead_s = dead_s
        self.frame_s = frame_s
        self.min_backlog_bytes = min_backlog_bytes
        self._clock = clock
        self.written_bytes = 0
        self._sent_bytes = 0
        self.buffered = 0
        self.last_progress_at = clock()
        self.stall_started_at: Optional[float] = None
        self.max_stall_s = 0.0
        self.dropped_frames = 0
        self.drop_run = 0
        self.stalls = 0

    def observe(self, buffered: int) -> None:
        """Record transport backlog; progress = bytes actually left the buffer."""
        now = self._clock()
        buffered = max(0, int(buffered))
        sent = self.written_bytes - buffered
        if buffered == 0 or sent > self._sent_bytes:
            if self.stall_started_at is not None:
                self.max_stall_s = max(self.max_stall_s, now - self.stall_started_at)
            self.stall_started_at = None
            self.last_progress_at = now
            self.drop_run = 0
        elif self.stall_started_at is None:
            self.stall_started_at = now
            self.stalls += 1
        if self.stall_started_at is not None:
            self.max_stall_s = max(self.max_stall_s, now - self.stall_started_at)
        self._sent_bytes = max(self._sent_bytes, sent)
        self.buffered = buffered

    def cap_bytes(self, frame_bytes: int) -> int:
        frames = max(1, int(round(self.backlog_s / self.frame_s)))
        return max(self.min_backlog_bytes, frames * max(1, int(frame_bytes)))

    def admit(self, frame_bytes: int) -> bool:
        """True → write this frame now. False → drop it (backlog over cap)."""
        if self.buffered + frame_bytes > self.cap_bytes(frame_bytes):
            self.dropped_frames += 1
            self.drop_run += 1
            return False
        self.written_bytes += frame_bytes
        self.buffered += frame_bytes
        return True

    def note_control_write(self, nbytes: int) -> None:
        """Pings/pongs/text written outside admit() still count toward progress."""
        self.written_bytes += max(0, int(nbytes))
        self.buffered += max(0, int(nbytes))

    def stalled_for(self) -> float:
        if self.stall_started_at is None:
            return 0.0
        return self._clock() - self.stall_started_at

    def dead_reason(self) -> Optional[str]:
        if self.dead_s <= 0 or self.buffered <= 0:
            return None
        quiet = self._clock() - self.last_progress_at
        if quiet >= self.dead_s:
            return (
                f"websocket downlink dead: {self.buffered} bytes unsent for {quiet:.0f}s "
                f"(GSM2COMPUTER_WS_DOWNLINK_DEAD_S={self.dead_s:.0f})"
            )
        return None

    def snapshot(self) -> dict[str, Any]:
        return {
            "buffered": self.buffered,
            "stalled_s": round(self.stalled_for(), 1),
            "max_stall_s": round(self.max_stall_s, 1),
            "stalls": self.stalls,
            "dropped_frames": self.dropped_frames,
        }


def reconnect_should_supersede(snapshot: dict, *, stale_s: float = 1.5) -> bool:
    """A new real (non-e2e) Pixel socket may replace the live one only if it looks stale.

    The Pixel opens a second socket only after it thinks the first died. A healthy
    call (inbound frames < stale_s ago, downlink not stalled) keeps its slot so a
    stray client cannot hijack it.
    """
    if not snapshot.get("busy") or snapshot.get("e2e"):
        return False
    last_ws = snapshot.get("last_ws_s")
    stalled = float(snapshot.get("downlink_stalled_s") or 0.0)
    if stalled >= stale_s:
        return True
    if last_ws is None:
        return False  # still handshaking: never kick a session mid-setup
    return float(last_ws) >= stale_s
