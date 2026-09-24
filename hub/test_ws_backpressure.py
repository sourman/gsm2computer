#!/usr/bin/env python3
"""Downlink stall survival (TC@352: 5 s drain abort → 49 s gap)."""
from __future__ import annotations

import asyncio
import inspect
import socket
import sys
import time
import types
import unittest

sys.modules.setdefault("audioop", types.ModuleType("audioop"))  # py3.13 CI; unused here

from call_slot import CallSlot  # noqa: E402
from ws_backpressure import (  # noqa: E402
    DownlinkSendGuard,
    reconnect_should_supersede,
    transport_buffered,
)


class Clock:
    def __init__(self) -> None:
        self.t = 1000.0

    def __call__(self) -> float:
        return self.t


class GuardTests(unittest.TestCase):
    def test_admits_until_backlog_cap_then_drops(self) -> None:
        g = DownlinkSendGuard(backlog_s=0.3, dead_s=45, clock=Clock())
        frame = 1000  # cap = max(4096, 15 * 1000) = 15000
        admitted = 0
        for _ in range(40):
            g.observe(g.buffered)  # link stalled: nothing leaves the buffer
            if g.admit(frame):
                admitted += 1
        self.assertEqual(admitted, 15)
        self.assertEqual(g.dropped_frames, 25)

    def test_transient_stall_is_not_dead_and_recovers(self) -> None:
        c = Clock()
        g = DownlinkSendGuard(dead_s=45, clock=c)
        g.admit(2000)
        g.observe(2000)
        for _ in range(30):  # 30 s outage, nothing drains
            c.t += 1.0
            g.observe(2000)
            self.assertIsNone(g.dead_reason())
        self.assertGreaterEqual(g.stalled_for(), 29.0)
        c.t += 0.5
        g.observe(0)  # link back, buffer flushed
        self.assertIsNone(g.dead_reason())
        self.assertIsNone(g.stall_started_at)
        self.assertGreaterEqual(g.max_stall_s, 30.0)
        self.assertTrue(g.admit(2000))

    def test_no_progress_for_dead_window_is_dead(self) -> None:
        c = Clock()
        g = DownlinkSendGuard(dead_s=45, clock=c)
        g.admit(3000)
        g.observe(3000)
        c.t += 44.0
        g.observe(3000)
        self.assertIsNone(g.dead_reason())
        c.t += 2.0
        g.observe(3000)
        self.assertIn("downlink dead", g.dead_reason() or "")

    def test_partial_progress_resets_dead_clock(self) -> None:
        c = Clock()
        g = DownlinkSendGuard(dead_s=45, clock=c)
        g.admit(4000)
        g.observe(4000)
        c.t += 40
        g.observe(3000)  # 1000 bytes left the buffer
        c.t += 40
        g.observe(3000)
        self.assertIsNone(g.dead_reason())

    def test_empty_buffer_never_dead(self) -> None:
        c = Clock()
        g = DownlinkSendGuard(dead_s=45, clock=c)
        c.t += 999
        g.observe(0)
        self.assertIsNone(g.dead_reason())


class SupersedePolicyTests(unittest.TestCase):
    def test_stale_real_call_is_superseded(self) -> None:
        self.assertTrue(
            reconnect_should_supersede({"busy": True, "e2e": False, "last_ws_s": 8.0, "established_s": 300})
        )
        self.assertTrue(
            reconnect_should_supersede(
                {"busy": True, "e2e": False, "last_ws_s": 0.2, "downlink_stalled_s": 6.0}
            )
        )

    def test_healthy_or_setup_or_e2e_is_not_superseded(self) -> None:
        self.assertFalse(reconnect_should_supersede({"busy": True, "e2e": False, "last_ws_s": 0.1}))
        self.assertFalse(reconnect_should_supersede({"busy": True, "e2e": False, "last_ws_s": None}))
        self.assertFalse(reconnect_should_supersede({"busy": True, "e2e": True, "last_ws_s": 30}))
        self.assertFalse(reconnect_should_supersede({"busy": False}))


class SnapshotTests(unittest.TestCase):
    def test_health_exposes_downlink_stall(self) -> None:
        slot = CallSlot()
        slot.claim("/")
        g = DownlinkSendGuard(clock=Clock())
        slot.downlink_probe = g.snapshot
        snap = slot.snapshot()
        self.assertIn("downlink_stalled_s", snap)
        self.assertIn("downlink_dropped_frames", snap)
        slot.release()
        self.assertIsNone(slot.downlink_probe)
        self.assertNotIn("downlink_stalled_s", slot.snapshot())


class RealSocketStallTests(unittest.IsolatedAsyncioTestCase):
    """Integration: a peer that stops reading (Tailscale hiccup) on a real TCP socket."""

    async def _pair(self):
        accepted: asyncio.Future = asyncio.get_running_loop().create_future()

        async def on_conn(r, w):
            accepted.set_result((r, w))

        server = await asyncio.start_server(on_conn, "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        sock = socket.socket()
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
        sock.setblocking(False)
        await asyncio.get_running_loop().sock_connect(sock, ("127.0.0.1", port))
        cr, cw = await asyncio.open_connection(sock=sock)
        sr, sw = await asyncio.wait_for(accepted, 5)
        s = sw.get_extra_info("socket")
        s.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4096)
        self.addAsyncCleanup(self._close, server, cw, sw)
        return (sr, sw), (cr, cw)

    async def _close(self, server, cw, sw) -> None:
        for w in (cw, sw):
            w.close()
        server.close()

    async def test_pump_survives_stall_drops_stale_and_recovers(self) -> None:
        import hub

        (_sr, sw), (cr, _cw) = await self._pair()
        g = DownlinkSendGuard(backlog_s=0.3, dead_s=45)
        frame = hub.ws_encode_text("x" * 2000)
        t0 = time.monotonic()
        # 3 s of 20 ms frames (sped up 4x) while the peer reads nothing.
        for i in range(150):
            g.observe(transport_buffered(sw))
            if g.admit(len(frame)):
                sw.write(frame)
            if i % 50 == 0:
                await hub.ws_send_ping(sw)  # must not block or raise
                await hub.ws_send_pong(sw, b"p")
            await asyncio.sleep(0.005)
        elapsed = time.monotonic() - t0
        self.assertLess(elapsed, 3.0, "pump blocked on a stalled socket")
        self.assertFalse(sw.is_closing())
        self.assertGreater(g.dropped_frames, 50)
        self.assertIsNone(g.dead_reason())
        self.assertLessEqual(transport_buffered(sw), g.cap_bytes(len(frame)) + 4096)
        # Peer comes back and drains: guard sees progress, admits again.
        total = 0
        while True:
            try:
                chunk = await asyncio.wait_for(cr.read(65536), 0.3)
            except asyncio.TimeoutError:
                break
            if not chunk:
                break
            total += len(chunk)
        g.observe(transport_buffered(sw))
        self.assertGreater(total, 0)
        self.assertIsNone(g.stall_started_at)
        self.assertTrue(g.admit(len(frame)))

    async def test_soft_drain_times_out_without_raising(self) -> None:
        import hub

        (_sr, sw), _c = await self._pair()
        sw.write(b"y" * 2_000_000)
        ok = await hub._soft_drain(sw, timeout=0.3)
        self.assertFalse(ok)
        self.assertFalse(sw.is_closing())

    async def test_dead_socket_detected_with_short_window(self) -> None:
        import hub

        (_sr, sw), _c = await self._pair()
        g = DownlinkSendGuard(dead_s=0.5)
        frame = hub.ws_encode_text("z" * 4000)
        reason = None
        deadline = time.monotonic() + 3.0
        while time.monotonic() < deadline and reason is None:
            g.observe(transport_buffered(sw))
            reason = g.dead_reason()
            if g.admit(len(frame)):
                sw.write(frame)
            await asyncio.sleep(0.02)
        self.assertIsNotNone(reason)


class HubSourceTests(unittest.TestCase):
    def test_pump_out_no_longer_aborts_on_drain_timeout(self) -> None:
        import hub

        src = inspect.getsource(hub.PipewireBridge._pump_out)
        self.assertNotIn("ws_send_text(ws_writer", src)
        self.assertNotIn("send stalled", src)
        self.assertIn("downlink_guard", src)

    def test_pong_never_awaits_drain(self) -> None:
        import hub

        for fn in (hub.ws_send_pong, hub.ws_send_ping):
            self.assertNotIn(".drain(", inspect.getsource(fn))

    def test_supersede_does_not_wake_cup(self) -> None:
        import hub

        src = inspect.getsource(hub._watch_live_call)
        self.assertIn("SUPERSEDED_REASON", src)


if __name__ == "__main__":
    unittest.main()
