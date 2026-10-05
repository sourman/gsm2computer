#!/usr/bin/env python3
"""Same-call websocket relink and close classification.

A Pixel reconnect for the live session must attach to the call that is
already holding Talk and PipeWire. A different client still gets 409.
The slot is released when the grace window ends, by the same finally path
as any other call end, so a stuck holder cannot outlive the grace backstop.
"""
from __future__ import annotations

import asyncio
import json
import subprocess
from dataclasses import dataclass
from typing import Any, Optional


@dataclass
class WsRead:
    text: str = ""
    ended: bool = False
    close_code: Optional[int] = None
    close_reason: str = ""
    initiator: str = ""
    pong: bool = False


def is_clean_hangup(close_code: Optional[int], initiator: str) -> bool:
    """Peer close 1000/1001 is a hangup. Resets and other codes are drops."""
    if initiator != "peer":
        return False
    return close_code in (1000, 1001)


def parse_tailscale_status(payload: dict, peer_ip: str) -> dict[str, Any]:
    """Map `tailscale status --json` to direct vs DERP for one peer.

    CurAddr set means a direct path even when Relay names the fallback region.
    """
    if not peer_ip or not isinstance(payload, dict):
        return {"mode": "unknown", "relay": "", "addr": "", "error": "no peer"}
    peers = payload.get("Peer") or {}
    if not isinstance(peers, dict):
        return {"mode": "unknown", "relay": "", "addr": "", "error": "no peers"}
    for peer in peers.values():
        if not isinstance(peer, dict):
            continue
        ips = peer.get("TailscaleIPs") or []
        if peer_ip not in ips:
            continue
        cur = str(peer.get("CurAddr") or "")
        relay = str(peer.get("Relay") or "")
        if cur:
            mode = "direct"
        elif relay:
            mode = "relay"
        else:
            mode = "unknown"
        return {
            "mode": mode,
            "relay": relay,
            "addr": cur,
            "online": peer.get("Online"),
            "active": peer.get("Active"),
        }
    return {"mode": "unknown", "relay": "", "addr": "", "error": "peer not in status"}


def parse_ss_ti(text: str) -> dict[str, Any]:
    """Pull rtt / retrans from `ss -tin` output. Missing fields stay absent."""
    out: dict[str, Any] = {}
    if not text:
        return out
    for raw in text.split():
        if raw.startswith("rtt:"):
            # rtt:89.2/12.1
            body = raw.split(":", 1)[1]
            rtt = body.split("/", 1)[0]
            try:
                out["rtt_ms"] = float(rtt)
            except ValueError:
                pass
        elif raw.startswith("rto:"):
            try:
                out["rto_ms"] = float(raw.split(":", 1)[1])
            except ValueError:
                pass
        elif raw.startswith("bytes_retrans:"):
            try:
                out["bytes_retrans"] = int(raw.split(":", 1)[1])
            except ValueError:
                pass
        elif raw.startswith("retrans:"):
            out["retrans"] = raw.split(":", 1)[1]
        elif raw.startswith("unacked:"):
            try:
                out["unacked"] = int(raw.split(":", 1)[1])
            except ValueError:
                pass
    return out


def load_tailscale_status() -> dict:
    """Local tailscale status. Empty dict if the CLI is missing or unhappy."""
    try:
        proc = subprocess.run(
            ["tailscale", "status", "--json"],
            capture_output=True,
            text=True,
            timeout=3,
            check=False,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired, OSError):
        return {}
    if proc.returncode != 0 or not (proc.stdout or "").strip():
        return {}
    try:
        data = json.loads(proc.stdout)
    except json.JSONDecodeError:
        return {}
    return data if isinstance(data, dict) else {}


def read_peer_socket(peer_ip: str) -> dict[str, Any]:
    if not peer_ip:
        return {}
    try:
        proc = subprocess.run(
            ["ss", "-tin", "dst", peer_ip],
            capture_output=True,
            text=True,
            timeout=2,
            check=False,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired, OSError) as exc:
        return {"error": type(exc).__name__}
    parsed = parse_ss_ti(proc.stdout or "")
    if proc.returncode != 0 and not parsed:
        err = (proc.stderr or "").strip()[:120]
        if err:
            parsed["error"] = err
    return parsed


class RelinkGate:
    """Hand a new websocket to the live call without a second CallSlot claim."""

    def __init__(self) -> None:
        self.session_id: Optional[str] = None
        self._waiter: Optional[asyncio.Future] = None
        self._holder_release: Optional[asyncio.Future] = None

    def matches(self, session_id: str) -> bool:
        if not session_id or session_id != self.session_id:
            return False
        waiter = self._waiter
        return waiter is not None and not waiter.done()

    def begin(self, session_id: str) -> "asyncio.Future":
        self.cancel_wait()
        loop = asyncio.get_running_loop()
        self.session_id = session_id
        self._waiter = loop.create_future()
        return self._waiter

    def deliver(self, session_id: str, reader: Any, writer: Any) -> Optional["asyncio.Future"]:
        if not self.matches(session_id):
            return None
        loop = asyncio.get_running_loop()
        release: asyncio.Future = loop.create_future()
        waiter = self._waiter
        self._waiter = None
        self._holder_release = release
        assert waiter is not None
        waiter.set_result((reader, writer, release))
        return release

    def release_holder(self) -> None:
        rel = self._holder_release
        self._holder_release = None
        if rel is not None and not rel.done():
            rel.set_result(True)

    def cancel_wait(self) -> None:
        waiter = self._waiter
        self._waiter = None
        if waiter is not None and not waiter.done():
            waiter.cancel()

    def close(self) -> None:
        self.cancel_wait()
        self.session_id = None
        self.release_holder()


async def wait_for_ws(awaitable, abort: asyncio.Event, gap: asyncio.Event):
    """Return ('frame', result), ('abort', None), or ('gap', None).

    A finished read wins over the gap flag so a close code is not discarded.
    Abort wins over a still-pending read.
    """
    read_task = asyncio.ensure_future(awaitable)
    abort_task = asyncio.create_task(abort.wait())
    gap_task = asyncio.create_task(gap.wait())
    try:
        _done, pending = await asyncio.wait(
            {read_task, abort_task, gap_task},
            return_when=asyncio.FIRST_COMPLETED,
        )
        for task in pending:
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass
        if read_task.done() and not read_task.cancelled():
            return "frame", read_task.result()
        if not read_task.done():
            read_task.cancel()
            try:
                await read_task
            except asyncio.CancelledError:
                pass
        if abort.is_set():
            return "abort", None
        return "gap", None
    finally:
        if not read_task.done():
            read_task.cancel()
            try:
                await read_task
            except asyncio.CancelledError:
                pass
        for task in (abort_task, gap_task):
            if not task.done():
                task.cancel()
                try:
                    await task
                except asyncio.CancelledError:
                    pass
