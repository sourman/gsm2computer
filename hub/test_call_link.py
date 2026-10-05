#!/usr/bin/env python3
"""Relink classification, Tailscale path parsing, and the same-session gate."""
from __future__ import annotations

import asyncio
import unittest

from call_link import (
    RelinkGate,
    WsRead,
    is_clean_hangup,
    parse_ss_ti,
    parse_tailscale_status,
    wait_for_ws,
)
from call_slot import CallSlot, CallWatchdogConfig


class CloseClassificationTests(unittest.TestCase):
    def test_peer_normal_close_is_a_hangup(self) -> None:
        self.assertTrue(is_clean_hangup(1000, "peer"))
        self.assertTrue(is_clean_hangup(1001, "peer"))

    def test_reset_and_other_codes_are_drops(self) -> None:
        self.assertFalse(is_clean_hangup(None, "reset"))
        self.assertFalse(is_clean_hangup(None, "eof"))
        self.assertFalse(is_clean_hangup(1006, "peer"))
        self.assertFalse(is_clean_hangup(1011, "hub"))
        self.assertFalse(is_clean_hangup(1000, "hub"))


class TailscalePathTests(unittest.TestCase):
    def test_curaddr_is_direct_even_when_relay_is_named(self) -> None:
        payload = {
            "Peer": {
                "n1": {
                    "TailscaleIPs": ["100.85.191.25"],
                    "CurAddr": "154.176.92.230:51421",
                    "Relay": "par",
                    "Online": True,
                }
            }
        }
        info = parse_tailscale_status(payload, "100.85.191.25")
        self.assertEqual(info["mode"], "direct")
        self.assertEqual(info["relay"], "par")
        self.assertEqual(info["addr"], "154.176.92.230:51421")

    def test_relay_without_curaddr(self) -> None:
        payload = {
            "Peer": {
                "n1": {
                    "TailscaleIPs": ["100.85.191.25"],
                    "CurAddr": "",
                    "Relay": "par",
                }
            }
        }
        info = parse_tailscale_status(payload, "100.85.191.25")
        self.assertEqual(info["mode"], "relay")

    def test_unknown_peer(self) -> None:
        info = parse_tailscale_status({"Peer": {}}, "100.1.1.1")
        self.assertEqual(info["mode"], "unknown")


class SocketStatTests(unittest.TestCase):
    def test_parse_ss_ti(self) -> None:
        text = (
            "ESTAB 0 0 100.119.126.42:8787 100.85.191.25:51234\n"
            " cubic rto:201 rtt:89.2/12.1 bytes_retrans:5 unacked:2\n"
        )
        parsed = parse_ss_ti(text)
        self.assertEqual(parsed["rtt_ms"], 89.2)
        self.assertEqual(parsed["rto_ms"], 201.0)
        self.assertEqual(parsed["bytes_retrans"], 5)
        self.assertEqual(parsed["unacked"], 2)


class RelinkGateTests(unittest.IsolatedAsyncioTestCase):
    async def test_only_the_matching_session_is_delivered(self) -> None:
        gate = RelinkGate()
        waiter = gate.begin("hub-abc")
        self.assertFalse(gate.matches("hub-other"))
        self.assertIsNone(gate.deliver("hub-other", "r", "w"))
        release = gate.deliver("hub-abc", "reader", "writer")
        self.assertIsNotNone(release)
        reader, writer, rel = await waiter
        self.assertEqual((reader, writer), ("reader", "writer"))
        assert release is not None
        self.assertIs(rel, release)
        gate.release_holder()
        self.assertTrue(release.done())

    async def test_close_unblocks_a_parked_socket(self) -> None:
        gate = RelinkGate()
        waiter = gate.begin("hub-abc")
        release = gate.deliver("hub-abc", "r", "w")
        await waiter
        gate.close()
        assert release is not None
        self.assertTrue(release.done())
        self.assertFalse(gate.matches("hub-abc"))


class WaitForWsTests(unittest.IsolatedAsyncioTestCase):
    async def test_gap_wakes_without_dropping_a_finished_frame(self) -> None:
        abort = asyncio.Event()
        gap = asyncio.Event()

        async def _frame():
            return WsRead(text="hi")

        kind, frame = await wait_for_ws(_frame(), abort, gap)
        self.assertEqual(kind, "frame")
        assert frame is not None
        self.assertEqual(frame.text, "hi")

        gap.set()

        async def _block():
            await asyncio.Event().wait()

        kind, frame = await wait_for_ws(_block(), abort, gap)
        self.assertEqual(kind, "gap")
        self.assertIsNone(frame)


class LinkGapSlotTests(unittest.TestCase):
    def test_ws_idle_is_suppressed_until_the_grace_backstop(self) -> None:
        clock = _Clock()
        slot = CallSlot(
            CallWatchdogConfig(
                ws_idle_s=60.0,
                uplink_idle_s=120.0,
                uplink_grace_s=45.0,
                max_s=0,
                relink_grace_s=65.0,
                enabled=True,
            ),
            clock=clock,
        )
        self.assertTrue(slot.claim("/"))
        slot.session_id = "hub-1"
        slot.mark_established()
        slot.note_link_gap()
        clock.advance(60.0)
        self.assertIsNone(slot.check())
        clock.advance(10.0)
        reason = slot.check()
        self.assertIsNotNone(reason)
        assert reason is not None
        self.assertIn("relink grace exceeded", reason)

    def test_ask_link_gap_does_not_abort_when_grace_is_enabled(self) -> None:
        slot = CallSlot(CallWatchdogConfig(relink_grace_s=65.0, enabled=False))
        self.assertTrue(slot.claim("/"))
        slot.ask_link_gap("websocket ping send failed")
        self.assertFalse(slot.abort.is_set())
        self.assertTrue(slot.gap_wake.is_set())
        self.assertEqual(slot.take_gap_request(), "websocket ping send failed")

    def test_zero_grace_still_aborts_immediately(self) -> None:
        slot = CallSlot(CallWatchdogConfig(relink_grace_s=0, enabled=False))
        self.assertTrue(slot.claim("/"))
        slot.ask_link_gap("websocket ping send failed")
        self.assertTrue(slot.abort.is_set())
        self.assertIn("ping send failed", slot.abort_reason or "")

    def test_release_clears_the_gap_so_the_next_call_is_not_blocked(self) -> None:
        slot = CallSlot(CallWatchdogConfig(enabled=False))
        self.assertTrue(slot.claim("/"))
        slot.note_link_gap()
        slot.session_id = "hub-1"
        slot.release()
        self.assertFalse(slot.busy)
        self.assertIsNone(slot.snapshot()["session_id"])
        self.assertFalse(slot.snapshot()["link_gap_open"])
        self.assertTrue(slot.claim("/"))


class _Clock:
    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


if __name__ == "__main__":
    unittest.main()
