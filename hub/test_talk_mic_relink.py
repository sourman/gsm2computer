#!/usr/bin/env python3
"""Talk mic re-link: no mid-call Page.reload / pw-loopback unit restart."""
from __future__ import annotations

import unittest
from unittest.mock import AsyncMock, patch

from call_slot import disruptive_heal_blocked
from talk_chromium import (
    PHONE_MIC_UNIT,
    ensure_openclaw_phone_mic,
    live_talk_blocks_reload,
    loopback_capture_linked,
    relink_openclaw_phone_mic,
)


# Shape observed on safwat-cup (PipeWire 1.4) during a live Talk call.
LINKED_LISTING = """
phone_uplink:monitor_FL
  |-> input.openclaw_phone_mic:input_FL
phone_uplink:monitor_FR
  |-> input.openclaw_phone_mic:input_FR
phone_uplink:playback_FL
  |<- pw-cat:output_FL
input.openclaw_phone_mic:input_FL
  |<- phone_uplink:monitor_FL
output.openclaw_phone_mic:capture_FL
  |-> Chromium:input_FL
"""

UNLINKED_LISTING = """
output.openclaw_phone_mic:capture_FL
  |-> Chromium:input_FL
phone_uplink:monitor_FL
phone_uplink:monitor_FR
"""


class LoopbackLinkParseTests(unittest.TestCase):
    def test_linked_sink_capture_is_true(self) -> None:
        self.assertTrue(loopback_capture_linked(LINKED_LISTING))

    def test_chromium_on_output_only_is_not_capture_link(self) -> None:
        self.assertFalse(loopback_capture_linked(UNLINKED_LISTING))

    def test_monitor_to_output_source_does_not_count(self) -> None:
        listing = (
            "phone_uplink:monitor_FL\n"
            "  |-> output.openclaw_phone_mic:input_FL\n"
        )
        self.assertFalse(loopback_capture_linked(listing))

    def test_empty_listing_is_false(self) -> None:
        self.assertFalse(loopback_capture_linked(""))


class LiveTalkReloadGuardTests(unittest.TestCase):
    def test_dom_live_blocks(self) -> None:
        self.assertTrue(live_talk_blocks_reload({"live": True}))

    def test_connected_pc_blocks(self) -> None:
        self.assertTrue(
            live_talk_blocks_reload(
                {"live": False, "pcs": [{"connection": "connected", "ice": "connected"}]}
            )
        )

    def test_idle_page_allows(self) -> None:
        self.assertFalse(live_talk_blocks_reload({"live": False, "pcs": [], "hasTalkButton": True}))

    def test_talk_active_flag_blocks_even_without_page(self) -> None:
        self.assertTrue(live_talk_blocks_reload(None, talk_active=True))


class DisruptiveHealGuardTests(unittest.TestCase):
    def test_busy_blocks(self) -> None:
        h = {"call": {"busy": True, "last_ws_s": 0.2, "last_uplink_s": 0.2}, "talk": {}}
        self.assertTrue(disruptive_heal_blocked(h))

    def test_talk_active_blocks(self) -> None:
        h = {"call": {"busy": False}, "talk": {"talk_active": True}}
        self.assertTrue(disruptive_heal_blocked(h))

    def test_fresh_ws_after_busy_cleared_blocks(self) -> None:
        h = {"call": {"busy": False, "last_ws_s": 1.0, "last_uplink_s": None}, "talk": {}}
        self.assertTrue(disruptive_heal_blocked(h))

    def test_idle_allows(self) -> None:
        h = {
            "call": {"busy": False, "last_ws_s": None, "last_uplink_s": None},
            "talk": {"talk_active": False},
        }
        self.assertFalse(disruptive_heal_blocked(h))


class EnsureMicRestartTests(unittest.IsolatedAsyncioTestCase):
    async def test_active_unit_does_not_restart(self) -> None:
        calls: list[list[str]] = []

        async def fake_run(args, timeout_s, label):  # noqa: ANN001
            calls.append(list(args))
            if args[:3] == ["systemctl", "--user", "is-active"]:
                return 0, "active\n", ""
            if args[:3] == ["pactl", "list", "sources"]:
                return 0, "42\topenclaw_phone_mic\tmodule\n", ""
            raise AssertionError(args)

        with patch("talk_chromium._run_captured", new=fake_run):
            restarted = await ensure_openclaw_phone_mic()
        self.assertFalse(restarted)
        self.assertFalse(any("restart" in cmd for cmd in calls))
        self.assertTrue(any(a[:3] == ["systemctl", "--user", "is-active"] for a in calls))

    async def test_force_restarts_unit(self) -> None:
        calls: list[list[str]] = []

        async def fake_run(args, timeout_s, label):  # noqa: ANN001
            calls.append(list(args))
            return 0, "active\n", ""

        with patch("talk_chromium._run_captured", new=fake_run), patch(
            "talk_chromium.asyncio.sleep", new=AsyncMock()
        ):
            restarted = await ensure_openclaw_phone_mic(force=True)
        self.assertTrue(restarted)
        self.assertIn(["systemctl", "--user", "restart", PHONE_MIC_UNIT], calls)


class RelinkDoesNotRestartUnitTests(unittest.IsolatedAsyncioTestCase):
    async def test_already_linked_is_noop(self) -> None:
        calls: list[list[str]] = []

        async def fake_run(args, timeout_s, label):  # noqa: ANN001
            calls.append(list(args))
            if args == ["pw-link", "-l"]:
                return 0, LINKED_LISTING, ""
            raise AssertionError(f"unexpected {args}")

        with patch("talk_chromium._run_captured", new=fake_run):
            ok = await relink_openclaw_phone_mic()
        self.assertTrue(ok)
        self.assertTrue(all(cmd[0] == "pw-link" for cmd in calls))
        self.assertFalse(any("systemctl" in cmd for cmd in calls))

    async def test_unlinked_uses_pw_link_not_systemctl(self) -> None:
        listing = {"n": 0}

        async def fake_run(args, timeout_s, label):  # noqa: ANN001
            if args[:2] == ["systemctl", "--user"]:
                raise AssertionError("must not restart loopback unit")
            if args == ["pw-link", "-l"]:
                listing["n"] += 1
                if listing["n"] == 1:
                    return 0, UNLINKED_LISTING, ""
                return 0, LINKED_LISTING, ""
            if args == ["pw-link", "-o"]:
                return 0, "phone_uplink:monitor_FL\nphone_uplink:monitor_FR\n", ""
            if args == ["pw-link", "-i"]:
                return (
                    0,
                    "input.openclaw_phone_mic:input_FL\ninput.openclaw_phone_mic:input_FR\n",
                    "",
                )
            if args[0] == "pw-link" and len(args) == 3:
                linked.append((args[1], args[2]))
                return 0, "", ""
            raise AssertionError(args)

        linked: list[tuple[str, str]] = []
        with patch("talk_chromium._run_captured", new=fake_run):
            ok = await relink_openclaw_phone_mic()
        self.assertTrue(ok)
        # Real null-sink output ports are monitor_*; those must be linked.
        self.assertIn(
            ("phone_uplink:monitor_FL", "input.openclaw_phone_mic:input_FL"), linked
        )
        self.assertIn(
            ("phone_uplink:monitor_FR", "input.openclaw_phone_mic:input_FR"), linked
        )


class StartTalkDoesNotReloadTests(unittest.TestCase):
    def test_start_talk_source_has_no_page_reload(self) -> None:
        import inspect
        import talk_chromium

        src = inspect.getsource(talk_chromium.OpenClawTalkUI.start_talk)
        self.assertNotIn("Page.reload", src)
        self.assertNotIn("force=True", src)
        self.assertNotIn("__gsm2NeedsFreshGum = true", src)
        self.assertIn("relink_openclaw_phone_mic", src)

    def test_webrtc_ui_starts_talk_before_phone_bridge(self) -> None:
        from pathlib import Path

        src = Path(__file__).with_name("hub.py").read_text(encoding="utf-8")
        talk_at = src.find("openclaw talk ready before phone uplink")
        bridge_at = src.find("await bridge.start(")
        self.assertGreater(talk_at, 0)
        self.assertGreater(bridge_at, 0)
        self.assertLess(talk_at, bridge_at)

    def test_gum_upgrade_reload_does_not_rearm_fresh_flag(self) -> None:
        import inspect
        import talk_chromium

        src = inspect.getsource(talk_chromium.OpenClawTalkUI._maybe_reload_for_gum_upgrade)
        self.assertNotIn("__gsm2GumPatchVer=0", src)

    def test_start_audio_may_reload_only_on_gum_upgrade(self) -> None:
        import inspect
        import talk_chromium

        src = inspect.getsource(talk_chromium.OpenClawTalkUI.start_audio)
        self.assertIn("_maybe_reload_for_gum_upgrade", src)
        self.assertNotIn("Page.reload", src)


if __name__ == "__main__":
    unittest.main()
