#!/usr/bin/env python3
"""dropped_suspected: teardown inside the margin is not a drop."""
from __future__ import annotations

import os
import tempfile
import unittest
from unittest.mock import patch

os.environ.setdefault("GSM2COMPUTER_CALL_END_STATE", os.path.join(tempfile.gettempdir(), "cew-test-state.json"))
os.environ.setdefault("GSM2COMPUTER_CALL_END_LOG", os.path.join(tempfile.gettempdir(), "cew-test.log"))

import call_end_watch as cew  # noqa: E402


def H(busy: bool, ws: float | None, up: float | None = None, e2e: bool = False) -> dict:
    return {
        "ok": True,
        "call": {"busy": busy, "e2e": e2e, "last_ws_s": ws, "last_uplink_s": ws if up is None else up},
        "talk": {"talk_active": busy},
    }


class AssessDropTests(unittest.TestCase):
    def _call(self) -> cew.WatchState:
        return cew.WatchState(in_call=True, call_id="TC@1")

    def test_normal_hangup_hub_already_stopped_is_not_drop(self) -> None:
        st = self._call()
        for t in range(0, 100, 3):
            cew.observe_hub_during_call(st, H(True, 0.2), now=1000.0 + t)
        # Phone IDLE seen at 1100; hub WS closed at 1098 (between polls).
        st.phone_idle_at = 1100.0
        out = cew.assess_drop(st, H(False, 4.0), now=1102.0)
        self.assertFalse(out["dropped_suspected"])
        self.assertEqual(out["hub_end_lead_s"], 2.0)
        self.assertEqual(out["dropped_reasons"], [])

    def test_hub_seen_idle_just_before_phone_is_teardown(self) -> None:
        st = self._call()
        cew.observe_hub_during_call(st, H(True, 0.2), now=1000.0)
        cew.observe_hub_during_call(st, H(False, 1.5), now=1097.0)  # ended 1095.5
        st.phone_idle_at = 1100.0
        out = cew.assess_drop(st, H(False, 6.0), now=1101.5)
        self.assertFalse(out["dropped_suspected"])
        self.assertEqual(out["hub_end_lead_s"], 4.5)

    def test_ws_drop_in_last_10s_is_teardown(self) -> None:
        st = self._call()
        cew.observe_hub_during_call(st, H(True, 0.2), now=1000.0)
        cew.observe_hub_during_call(st, H(True, 10.5), now=1099.0)  # dead_at 1088.5
        st.phone_idle_at = 1097.0 + 10.0  # 1107: dead_at 1088.5 -> lead 18.5
        out = cew.assess_drop(st, H(False, 20.0), now=1108.0)
        self.assertTrue(out["dropped_suspected"])
        st2 = self._call()
        cew.observe_hub_during_call(st2, H(True, 0.2), now=1000.0)
        cew.observe_hub_during_call(st2, H(True, 10.0), now=1099.0)  # dead_at 1089
        st2.phone_idle_at = 1095.0  # dead 6s before idle -> teardown
        out2 = cew.assess_drop(st2, H(False, 12.0), now=1101.0)
        self.assertFalse(out2["dropped_suspected"])

    def test_hub_ended_well_before_phone_is_drop(self) -> None:
        st = self._call()
        cew.observe_hub_during_call(st, H(True, 0.2), now=1000.0)
        cew.observe_hub_during_call(st, H(False, 1.0), now=1050.0)  # ended 1049
        st.phone_idle_at = 1100.0
        out = cew.assess_drop(st, H(False, 52.0), now=1101.0)
        self.assertTrue(out["dropped_suspected"])
        self.assertIn("hub_ended_before_phone", out["dropped_reasons"])
        self.assertEqual(out["hub_end_lead_s"], 51.0)
        self.assertTrue(st.hub_ended_while_phone_live)

    def test_ws_dead_while_busy_mid_call_is_drop(self) -> None:
        st = self._call()
        cew.observe_hub_during_call(st, H(True, 0.2), now=1000.0)
        cew.observe_hub_during_call(st, H(True, 15.0, 15.0), now=1050.0)  # dead 1035
        cew.observe_hub_during_call(st, H(True, 60.0, 60.0), now=1095.0)  # keeps earliest
        st.phone_idle_at = 1100.0
        out = cew.assess_drop(st, H(True, 65.0, 65.0), now=1101.0)
        self.assertTrue(out["dropped_suspected"])
        self.assertEqual(out["ws_dead_lead_s"], 65.0)
        self.assertEqual(out["uplink_dead_lead_s"], 65.0)
        self.assertIn("ws_died_mid_call", out["dropped_reasons"])
        self.assertTrue(out["hub_still_busy_at_end"])

    def test_short_stall_and_quick_reconnect_are_not_drop(self) -> None:
        st = self._call()
        cew.observe_hub_during_call(st, H(True, 8.0), now=1010.0)  # < STREAM_DEAD_S
        cew.observe_hub_during_call(st, H(True, 0.3), now=1013.0)  # recovered
        self.assertIsNone(st.ws_dead_at)
        cew.observe_hub_during_call(st, H(False, 1.0), now=1020.0)  # ended 1019
        self.assertIsNotNone(st.hub_end_at)
        cew.observe_hub_during_call(st, H(True, 0.2), now=1025.0)  # reconnect, 6s gap
        self.assertIsNone(st.hub_end_at)
        st.phone_idle_at = 1100.0
        out = cew.assess_drop(st, H(False, 3.0), now=1101.0)
        self.assertFalse(out["dropped_suspected"])
        self.assertEqual(out["hub_gap_max_s"], 6.0)

    def test_recovered_ws_stall_over_10s_is_mid_call_gap(self) -> None:
        st = self._call()
        cew.observe_hub_during_call(st, H(True, 0.2), now=1000.0)
        cew.observe_hub_during_call(st, H(True, 12.0), now=1012.0)  # silent since 1000
        cew.observe_hub_during_call(st, H(True, 0.5), now=1014.5)  # resumed 1014.0
        self.assertIsNone(st.ws_dead_at)
        st.phone_idle_at = 1100.0
        out = cew.assess_drop(st, H(False, 3.0), now=1101.0)
        self.assertTrue(out["dropped_suspected"])
        self.assertEqual(out["dropped_reasons"], ["mid_call_gap"])
        self.assertEqual(out["hub_gap_max_s"], 14.0)

    def test_hub_gap_then_pixel_reconnect_is_mid_call_gap(self) -> None:
        # TC@352: hub aborted, Pixel re-attached ~49s later, call went on.
        st = self._call()
        cew.observe_hub_during_call(st, H(True, 0.2), now=1000.0)
        cew.observe_hub_during_call(st, H(False, 1.0), now=1010.0)  # ended 1009
        cew.observe_hub_during_call(st, H(False, 21.0), now=1030.0)
        cew.observe_hub_during_call(st, H(True, 0.2), now=1058.0)  # reconnected
        self.assertIsNone(st.hub_end_at)
        cew.observe_hub_during_call(st, H(True, 0.2), now=1090.0)
        st.phone_idle_at = 1100.0
        out = cew.assess_drop(st, H(False, 2.0), now=1101.0)
        self.assertTrue(out["dropped_suspected"])
        self.assertEqual(out["dropped_reasons"], ["mid_call_gap"])
        self.assertEqual(out["hub_gap_max_s"], 49.0)
        self.assertLess(out["hub_end_lead_s"], cew.DROP_MARGIN_S)  # teardown was clean

    def test_downlink_stall_counts_as_gap(self) -> None:
        st = self._call()
        h = H(True, 0.2)
        h["call"]["downlink_stalled_s"] = 14.0  # uplink fine, hub->phone stuck
        cew.observe_hub_during_call(st, h, now=1000.0)
        cew.observe_hub_during_call(st, H(True, 0.2), now=1003.0)  # sends resumed
        st.phone_idle_at = 1100.0
        out = cew.assess_drop(st, H(False, 2.0), now=1101.0)
        self.assertIn("mid_call_gap", out["dropped_reasons"])
        self.assertEqual(out["hub_gap_max_s"], 14.0)

    def test_gap_not_counted_outside_call_and_reset(self) -> None:
        st = cew.WatchState(in_call=False)
        cew.observe_hub_during_call(st, H(True, 30.0), now=1000.0)
        cew.observe_hub_during_call(st, H(True, 0.2), now=1001.0)
        self.assertEqual(st.hub_gap_max_s, 0.0)
        st.in_call = True
        cew.observe_hub_during_call(st, H(True, 30.0), now=1031.0)
        self.assertEqual(st.hub_gap_max_s, 0.0)  # still open: judged at IDLE
        cew.observe_hub_during_call(st, H(True, 0.2), now=1032.0)
        self.assertEqual(st.hub_gap_max_s, 30.8)
        st.reset_call_observations()
        self.assertEqual(st.hub_gap_max_s, 0.0)

    def test_e2e_busy_and_hub_unreachable_are_not_evidence(self) -> None:
        st = self._call()
        cew.observe_hub_during_call(st, H(True, 0.2, e2e=True), now=1000.0)
        cew.observe_hub_during_call(st, {"ok": False, "error": "URLError"}, now=1010.0)
        self.assertFalse(st.hub_saw_busy)
        st.phone_idle_at = 1100.0
        out = cew.assess_drop(st, {"ok": False}, now=1101.0)
        self.assertFalse(out["dropped_suspected"])
        self.assertIsNone(out["hub_end_lead_s"])

    def test_state_roundtrip_keeps_timings(self) -> None:
        st = self._call()
        st.hub_end_at, st.ws_dead_at, st.uplink_dead_at, st.phone_idle_at = 1.0, 2.0, 3.0, 4.0
        st.hub_gap_max_s = 31.5
        back = cew.WatchState.from_json(st.to_json())
        self.assertEqual(back.hub_gap_max_s, 31.5)
        self.assertEqual(
            (back.hub_end_at, back.ws_dead_at, back.uplink_dead_at, back.phone_idle_at),
            (1.0, 2.0, 3.0, 4.0),
        )


class ScenarioTests(unittest.TestCase):
    def test_scenarios(self) -> None:
        with patch.object(cew, "journal_summary", return_value={}):
            st, final, now = cew.simulate_scenario("normal", now=10_000.0)
            p = cew.build_payload(st=st, duration_s=120.0, test=True, health=final, now_ts=now)
            self.assertFalse(p["dropped_suspected"])
            self.assertLess(p["hub_end_lead_s"], cew.DROP_MARGIN_S)
            self.assertIn("hub_end_lead_s", p["hub_during_call"])
            st, final, now = cew.simulate_scenario("midcall", now=10_000.0)
            p = cew.build_payload(st=st, duration_s=120.0, test=True, health=final, now_ts=now)
            self.assertTrue(p["dropped_suspected"])
            self.assertGreaterEqual(p["hub_end_lead_s"], cew.DROP_MARGIN_S)
            self.assertTrue(p["test"])
            st, final, now = cew.simulate_scenario("gap30", now=10_000.0)
            p = cew.build_payload(st=st, duration_s=120.0, test=True, health=final, now_ts=now)
            self.assertTrue(p["dropped_suspected"])
            self.assertEqual(p["dropped_reasons"], ["mid_call_gap"])
            self.assertGreaterEqual(p["hub_gap_max_s"], 29.0)
            self.assertEqual(p["hub_during_call"]["hub_gap_max_s"], p["hub_gap_max_s"])
            self.assertLess(p["hub_end_lead_s"], cew.DROP_MARGIN_S)

    def test_dry_run_does_not_post_or_save_with_no_post(self) -> None:
        with patch.object(cew, "journal_summary", return_value={}), patch.object(
            cew, "post_alert_event"
        ) as post, patch.object(cew, "save_state") as save:
            self.assertEqual(cew.dry_run("midcall", post=False), 0)
            post.assert_not_called()
            save.assert_not_called()
            cew.dry_run("normal", post=True)
            post.assert_called_once()
            self.assertTrue(post.call_args[0][0]["test"])
            save.assert_not_called()


if __name__ == "__main__":
    unittest.main()
