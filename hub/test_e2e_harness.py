#!/usr/bin/env python3
"""e2e harness: 1x uplink pacing, non-blocking real-call check, transcript de-dup."""
from __future__ import annotations

import asyncio
import time
import unittest

try:  # CI has no websockets wheel; the harness only needs the names at import
    import websockets  # noqa: F401
except ModuleNotFoundError:  # pragma: no cover
    import sys
    import types

    _ws = types.ModuleType("websockets")
    _exc = types.ModuleType("websockets.exceptions")

    class ConnectionClosed(Exception):
        pass

    _exc.ConnectionClosed = ConnectionClosed
    _ws.exceptions = _exc
    sys.modules["websockets"] = _ws
    sys.modules["websockets.exceptions"] = _exc

import openclaw_e2e as e2e  # noqa: E402


class FakeWS:
    def __init__(self) -> None:
        self.sent: list[float] = []

    async def send(self, _msg: str) -> None:
        self.sent.append(time.monotonic())


class PacingTests(unittest.IsolatedAsyncioTestCase):
    async def test_paced_at_1x_even_with_slow_health_check(self) -> None:
        calls: list[float] = []

        async def slow_holding() -> bool:
            calls.append(time.monotonic())
            await asyncio.sleep(0.3)  # /health taking 300 ms must not slow audio
            return False

        ws = FakeWS()
        chunks = [b"\x00\x00" * 960] * 100  # 2.0 s of audio
        t0 = time.monotonic()
        pre = await e2e.paced_uplink(ws, chunks, holding=slow_holding)
        dur = time.monotonic() - t0
        self.assertFalse(pre)
        self.assertEqual(len(ws.sent), 100)
        self.assertGreater(dur, 1.9)
        self.assertLess(dur, 2.3, f"uplink took {dur:.2f}s for 2.0s of audio")
        # checks every >=0.5 s, not per chunk
        self.assertLessEqual(len(calls), 5)
        gaps = [b - a for a, b in zip(ws.sent, ws.sent[1:])]
        self.assertLess(max(gaps), 0.12)

    async def test_real_call_preempts_uplink(self) -> None:
        async def holding() -> bool:
            return True

        ws = FakeWS()
        pre = await e2e.paced_uplink(ws, [b"\x00\x00" * 960] * 200, holding=holding)
        self.assertTrue(pre)
        self.assertLess(len(ws.sent), 10)

    def test_trailing_silence_is_300ms(self) -> None:
        tail = e2e.trailing_silence(48000)
        self.assertEqual(len(tail), 15)
        self.assertTrue(all(f == b"\x00\x00" * 960 for f in tail))

    def test_send_loop_has_no_blocking_health_call(self) -> None:
        import inspect

        src = inspect.getsource(e2e.run_once)
        self.assertIn("paced_uplink", src)
        self.assertNotIn("await asyncio.sleep(0.02)", src)


class TranscriptDedupTests(unittest.TestCase):
    def test_hooks_replace_deltas_with_done(self) -> None:
        import inspect

        for js in (e2e.DC_HOOK_JS, inspect.getsource(e2e._attach_existing_dc)):
            self.assertIn("E.byResp[key] = String(j.transcript)", js)
            self.assertNotIn("transcripts.push(String(j.transcript))", js)
        self.assertIn("responses:", e2e.DC_SNAP_JS)


if __name__ == "__main__":
    unittest.main()
