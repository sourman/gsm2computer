"""Offline regression tests: never attach to production Chromium."""
import importlib.util
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch

from talk_chromium import OpenClawTalkUI, TalkUiError, on_chat_main, page_ready

ORIGIN = "https://hub-cup.mining-ling.ts.net"
GOOD = {"url": ORIGIN + "/chat/main", "hasTalkButton": True}
BAD = {"url": ORIGIN + "/dashboard/main", "hasTalkButton": True}
spec = importlib.util.spec_from_file_location("watchdog", Path(__file__).resolve().parents[1] / "ops/watchdog/cup_watchdog.py")
watchdog = importlib.util.module_from_spec(spec)
spec.loader.exec_module(watchdog)


class PageURLTests(unittest.TestCase):
    def test_exact_origin_and_path(self):
        for suffix in ("", "?x=1", "#fragment"):
            self.assertTrue(on_chat_main(GOOD["url"] + suffix))
        for url in (BAD["url"], ORIGIN + "/chat/other", ORIGIN + "/chat/main/",
                    ORIGIN + "/dashboard/main?next=/chat/main", "https://evil.test/chat/main",
                    "http://hub-cup.mining-ling.ts.net/chat/main", ORIGIN + ":444/chat/main",
                    "https://hub-cup.mining-ling.ts.net.evil.test/chat/main", "", None,
                    "https://[invalid/chat/main", "https://user@hub-cup.mining-ling.ts.net/chat/main"):
            with self.subTest(url=url):
                self.assertFalse(on_chat_main(url))
                self.assertFalse(page_ready({"url": url, "hasTalkButton": True}))
                ctx = watchdog.Ctx({}, None)
                ctx.health = {"talk": {"cdp": True, "page": {"url": url, "hasTalkButton": True}}}
                self.assertFalse(watchdog.check_talk_page(ctx)[0])
        self.assertFalse(page_ready({"url": GOOD["url"]}))

    def test_watchdog_good_and_busy(self):
        ctx = watchdog.Ctx({}, None)
        ctx.health = {"talk": {"cdp": True, "page": GOOD}}
        self.assertTrue(watchdog.check_talk_page(ctx)[0])
        ctx.busy = True
        self.assertIsNone(watchdog.check_talk_page(ctx)[0])


class HealTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.ui = OpenClawTalkUI()
        self.ui.idle_check = lambda: True
        self.session = AsyncMock()
        self.session.evaluate.return_value = dict(BAD)

    async def test_idle_navigate_and_backoff(self):
        self.assertEqual(await self.ui._heal_page_url(self.session, BAD), "navigation requested")
        self.session.call.assert_awaited_once_with("Page.navigate", {"url": GOOD["url"]})
        self.assertEqual(await self.ui._heal_page_url(self.session, BAD), "backoff")
        self.assertFalse(self.ui.page_heal_in_progress)

    async def test_busy_active_live_and_unknown_block(self):
        for guard, active, page in ((lambda: False, False, BAD), (None, False, BAD),
                                   (lambda: True, True, BAD),
                                   (lambda: True, False, {**BAD, "live": True}),
                                   (lambda: True, False, {**BAD, "pcs": [{"connection": "connecting"}]})):
            self.ui.idle_check, self.ui.talk_active = guard, active
            self.assertIn("blocked", await self.ui._heal_page_url(self.session, page))
        self.session.call.assert_not_awaited()

    async def test_call_arrives_during_probe(self):
        async def evaluate(*args):
            self.ui.idle_check = lambda: False
            return BAD
        self.session.evaluate.side_effect = evaluate
        self.assertIn("blocked", await self.ui._heal_page_url(self.session, BAD))
        self.session.call.assert_not_awaited()
        # Aborted before navigate must not burn the 120s cooldown.
        self.ui.idle_check = lambda: True
        self.session.evaluate.side_effect = None
        self.session.evaluate.return_value = dict(BAD)
        self.assertEqual(await self.ui._heal_page_url(self.session, BAD), "navigation requested")

    async def test_failed_navigation_keeps_backoff(self):
        self.session.call.side_effect = RuntimeError("CDP failed")
        with self.assertRaises(RuntimeError):
            await self.ui._heal_page_url(self.session, BAD)
        self.assertFalse(self.ui.page_heal_in_progress)
        self.assertEqual(await self.ui._heal_page_url(self.session, BAD), "backoff")

    async def test_ensure_does_not_accept_dashboard_button(self):
        self.ui.idle_check = lambda: False
        with self.assertRaises(TalkUiError):
            await self.ui._ensure_control_ui(self.session)
        self.session.call.assert_not_awaited()

    async def test_ensure_navigates_then_waits_for_exact_url(self):
        self.session.evaluate.side_effect = [BAD, BAD, GOOD]
        with patch("talk_chromium.asyncio.sleep", new_callable=AsyncMock):
            await self.ui._ensure_control_ui(self.session)
        self.session.call.assert_awaited_once_with("Page.navigate", {"url": GOOD["url"]})

    async def test_health_exposes_reason_and_readonly_probe(self):
        self.ui.idle_check = lambda: False
        self.ui._connect_page = AsyncMock(return_value=self.session)
        result = await self.ui._health_page_probe()
        self.assertFalse(result["onChatMain"])
        self.assertIn("wrong origin or path", result["reason"])
        self.ui._connect_page.assert_awaited_once_with(read_only=True)
        self.session.close.assert_awaited_once()

    async def test_reload_unknown_idle_is_blocked(self):
        self.ui.idle_check = None
        with self.assertRaises(TalkUiError):
            await self.ui.reload_control_ui()


if __name__ == "__main__":
    unittest.main()
