#!/usr/bin/env python3
"""Call-slot lock and watchdog policy (no PipeWire / Chromium)."""
from __future__ import annotations

import asyncio
import os
import unittest

from call_slot import CallSlot, CallWatchdogConfig, ensure_released_after_abort


class FakeClock:
    def __init__(self, start: float = 1000.0) -> None:
        self.now = start

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


class CallSlotLockTests(unittest.TestCase):
    def test_claim_rejects_second_call_until_release(self) -> None:
        slot = CallSlot(CallWatchdogConfig(enabled=False), clock=FakeClock())
        self.assertTrue(slot.claim("/"))
        self.assertTrue(slot.busy)
        self.assertFalse(slot.claim("/loopback"))
        slot.release()
        self.assertFalse(slot.busy)
        self.assertTrue(slot.claim("/"))

    def test_release_resets_abort_event_for_next_call(self) -> None:
        slot = CallSlot(CallWatchdogConfig(enabled=False), clock=FakeClock())
        self.assertTrue(slot.claim("/"))
        slot.abort_call("test")
        self.assertTrue(slot.abort.is_set())
        slot.release()
        self.assertFalse(slot.abort.is_set())
        self.assertIsNone(slot.abort_reason)


class CallWatchdogPolicyTests(unittest.TestCase):
    def setUp(self) -> None:
        self.clock = FakeClock()
        self.cfg = CallWatchdogConfig(
            ws_idle_s=60.0,
            uplink_idle_s=120.0,
            uplink_grace_s=45.0,
            max_s=2700.0,
            enabled=True,
        )
        self.slot = CallSlot(self.cfg, clock=self.clock)

    def _establish(self) -> None:
        self.assertTrue(self.slot.claim("/"))
        self.clock.advance(2.0)
        self.slot.mark_established()

    def test_healthy_audio_does_not_abort(self) -> None:
        self._establish()
        for _ in range(40):
            self.clock.advance(30.0)
            self.slot.note_uplink()
            self.assertIsNone(self.slot.check())
        self.assertTrue(self.slot.busy)

    def test_ws_silence_after_establish_aborts(self) -> None:
        slot = CallSlot(
            CallWatchdogConfig(
                ws_idle_s=60.0,
                uplink_idle_s=0,
                uplink_grace_s=45.0,
                max_s=0,
                enabled=True,
            ),
            clock=self.clock,
        )
        self.assertTrue(slot.claim("/"))
        self.clock.advance(2.0)
        slot.mark_established()
        self.clock.advance(59.0)
        self.assertIsNone(slot.check())
        self.clock.advance(2.0)
        reason = slot.check()
        self.assertIsNotNone(reason)
        assert reason is not None
        self.assertIn("websocket idle", reason)
        self.assertTrue(slot.busy)
        self.assertIsNone(slot.abort_reason)

    def test_ping_or_control_frame_resets_ws_idle(self) -> None:
        slot = CallSlot(
            CallWatchdogConfig(
                ws_idle_s=60.0,
                uplink_idle_s=0,
                max_s=0,
                enabled=True,
            ),
            clock=self.clock,
        )
        self.assertTrue(slot.claim("/"))
        slot.mark_established()
        self.clock.advance(50.0)
        slot.note_ws_activity()
        self.clock.advance(50.0)
        self.assertIsNone(slot.check())
        self.clock.advance(20.0)
        reason = slot.check()
        self.assertIsNotNone(reason)
        assert reason is not None
        self.assertIn("websocket idle", reason)

    def test_no_activity_hits_uplink_grace_before_ws_idle(self) -> None:
        self._establish()
        self.clock.advance(44.0)
        self.assertIsNone(self.slot.check())
        self.clock.advance(2.0)
        reason = self.slot.check()
        self.assertIsNotNone(reason)
        assert reason is not None
        self.assertIn("no gsm uplink audio", reason)

    def test_max_duration_aborts_even_with_audio(self) -> None:
        self._establish()
        self.slot.note_uplink()
        self.clock.advance(2697.0)
        self.slot.note_uplink()
        self.assertIsNone(self.slot.check())
        self.clock.advance(2.0)
        self.slot.note_uplink()
        reason = self.slot.check()
        self.assertIsNotNone(reason)
        assert reason is not None
        self.assertIn("max call duration", reason)
        self.assertTrue(self.slot.busy)

    def test_no_uplink_after_grace_aborts(self) -> None:
        self._establish()
        self.slot.note_ws_activity()
        self.clock.advance(44.0)
        self.slot.note_ws_activity()
        self.assertIsNone(self.slot.check())
        self.clock.advance(2.0)
        self.slot.note_ws_activity()
        reason = self.slot.check()
        self.assertIsNotNone(reason)
        assert reason is not None
        self.assertIn("no gsm uplink audio", reason)

    def test_uplink_then_idle_aborts(self) -> None:
        self._establish()
        self.slot.note_uplink()
        self.clock.advance(119.0)
        self.slot.note_ws_activity()
        self.assertIsNone(self.slot.check())
        self.clock.advance(2.0)
        self.slot.note_ws_activity()
        reason = self.slot.check()
        self.assertIsNotNone(reason)
        assert reason is not None
        self.assertIn("gsm uplink idle", reason)

    def test_handshake_not_idle_checked(self) -> None:
        self.assertTrue(self.slot.claim("/"))
        self.clock.advance(90.0)
        self.assertIsNone(self.slot.check())

    def test_zero_timeouts_disable_checks(self) -> None:
        slot = CallSlot(
            CallWatchdogConfig(
                ws_idle_s=0,
                uplink_idle_s=0,
                max_s=0,
                enabled=True,
            ),
            clock=self.clock,
        )
        self.assertTrue(slot.claim("/"))
        slot.mark_established()
        self.clock.advance(10_000)
        self.assertIsNone(slot.check())

    def test_disabled_watchdog_never_aborts(self) -> None:
        slot = CallSlot(
            CallWatchdogConfig(enabled=False, ws_idle_s=1, uplink_idle_s=1, max_s=1),
            clock=self.clock,
        )
        self.assertTrue(slot.claim("/"))
        slot.mark_established()
        self.clock.advance(10)
        self.assertIsNone(slot.check())

    def test_abort_call_does_not_release_lock(self) -> None:
        self._establish()
        self.slot.abort_call("websocket idle 60s")
        self.assertTrue(self.slot.busy)
        self.assertTrue(self.slot.abort.is_set())
        self.assertEqual(self.slot.abort_reason, "websocket idle 60s")
        self.slot.release()
        self.assertFalse(self.slot.busy)


class EnsureReleasedAfterAbortTests(unittest.IsolatedAsyncioTestCase):
    async def test_abort_with_bridge_still_referenced_clears_busy(self) -> None:
        """Mirrors /admin/call/release when active_bridge is set and WS finally never runs."""
        slot = CallSlot(CallWatchdogConfig(enabled=False), clock=FakeClock())
        self.assertTrue(slot.claim("/"))
        slot.mark_established()
        # Stand-in for hub.active_bridge still pointing at a live PipewireBridge:
        # abort alone must not clear the lock; ensure_released_after_abort must.
        bridge_still_referenced = object()
        self.assertIsNotNone(bridge_still_referenced)
        slot.abort_call("admin force-release")
        self.assertTrue(slot.busy)
        self.assertTrue(slot.abort.is_set())
        forced = await ensure_released_after_abort(
            slot, "admin force-release", wait_s=0.05
        )
        # already aborted; second abort is a no-op for reason, but wait+release runs
        self.assertTrue(forced)
        self.assertFalse(slot.busy)
        self.assertIsNone(slot.abort_reason)

    async def test_ensure_released_force_clears_when_handler_wedged(self) -> None:
        slot = CallSlot(CallWatchdogConfig(enabled=False), clock=FakeClock())
        self.assertTrue(slot.claim("/"))
        forced = await ensure_released_after_abort(
            slot, "websocket ping send failed: Connection reset by peer", wait_s=0.05
        )
        self.assertTrue(forced)
        self.assertFalse(slot.busy)

    async def test_ensure_released_noop_if_finally_already_released(self) -> None:
        slot = CallSlot(CallWatchdogConfig(enabled=False), clock=FakeClock())
        self.assertTrue(slot.claim("/"))

        async def handler_finally() -> None:
            await asyncio.sleep(0.02)
            slot.release()

        task = asyncio.create_task(handler_finally())
        forced = await ensure_released_after_abort(slot, "watchdog", wait_s=0.1)
        await task
        self.assertFalse(forced)
        self.assertFalse(slot.busy)

    async def test_ensure_released_does_not_clobber_newer_claim(self) -> None:
        clock = FakeClock()
        slot = CallSlot(CallWatchdogConfig(enabled=False), clock=clock)
        self.assertTrue(slot.claim("/old"))
        ensure = asyncio.create_task(
            ensure_released_after_abort(slot, "stale", wait_s=0.15)
        )
        await asyncio.sleep(0.05)
        slot.release()
        clock.advance(1.0)  # new claim must get a distinct claimed_at
        self.assertTrue(slot.claim("/new"))
        forced = await ensure
        self.assertFalse(forced)
        self.assertTrue(slot.busy)
        self.assertEqual(slot.path, "/new")


class CallWatchdogConfigTests(unittest.TestCase):
    def test_from_env_zero_disables_max(self) -> None:
        prev = os.environ.get("GSM2COMPUTER_CALL_MAX_S")
        os.environ["GSM2COMPUTER_CALL_MAX_S"] = "0"
        try:
            cfg = CallWatchdogConfig.from_env()
            self.assertEqual(cfg.max_s, 0.0)
        finally:
            if prev is None:
                os.environ.pop("GSM2COMPUTER_CALL_MAX_S", None)
            else:
                os.environ["GSM2COMPUTER_CALL_MAX_S"] = prev


class WaitFrameOrAbortTests(unittest.IsolatedAsyncioTestCase):
    async def test_abort_unblocks_wait_without_clearing_lock(self) -> None:
        from call_slot import wait_or_abort

        slot = CallSlot(CallWatchdogConfig(enabled=False), clock=FakeClock())
        self.assertTrue(slot.claim("/"))

        async def never() -> str:
            await asyncio.sleep(30)
            return "frame"

        wait = asyncio.create_task(wait_or_abort(never(), slot.abort))
        await asyncio.sleep(0.05)
        self.assertFalse(wait.done())
        slot.abort_call("test abort")
        msg = await asyncio.wait_for(wait, timeout=1.0)
        self.assertIsNone(msg)
        self.assertTrue(slot.busy)
        slot.release()
        self.assertFalse(slot.busy)

    async def test_wait_or_abort_returns_result(self) -> None:
        from call_slot import wait_or_abort

        abort = asyncio.Event()
        result = await wait_or_abort(asyncio.sleep(0, result="frame"), abort)
        self.assertEqual(result, "frame")
        self.assertFalse(abort.is_set())


if __name__ == "__main__":
    unittest.main()
