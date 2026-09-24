#!/usr/bin/env python3
"""alert_webhook.post_system_alert — env gate, Grok Bot POST, never raises."""
from __future__ import annotations

import io
import json
import os
import unittest
import urllib.error
from unittest.mock import patch

from alert_webhook import KEY_ENV, URL_ENV, post_alert_event, post_system_alert


class _FakeResponse:
    def __init__(self, status: int = 200) -> None:
        self.status = status

    def getcode(self) -> int:
        return self.status

    def __enter__(self) -> "_FakeResponse":
        return self

    def __exit__(self, *args: object) -> None:
        return None


class AlertWebhookTests(unittest.TestCase):
    def setUp(self) -> None:
        self._env = {URL_ENV: os.environ.get(URL_ENV), KEY_ENV: os.environ.get(KEY_ENV)}
        os.environ.pop(URL_ENV, None)
        os.environ.pop(KEY_ENV, None)

    def tearDown(self) -> None:
        for name, value in self._env.items():
            if value is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = value

    def test_unset_url_is_noop(self) -> None:
        with patch("alert_webhook.urllib.request.urlopen") as urlopen:
            post_system_alert("call watchdog abort: idle")
        urlopen.assert_not_called()

    def test_empty_url_is_noop(self) -> None:
        os.environ[URL_ENV] = "   "
        with patch("alert_webhook.urllib.request.urlopen") as urlopen:
            post_system_alert("call watchdog abort: idle")
        urlopen.assert_not_called()

    def test_posts_json_text_with_bearer_key(self) -> None:
        os.environ[URL_ENV] = "https://example.test/webhook"
        os.environ[KEY_ENV] = "crsr_test_key"
        with patch("alert_webhook.urllib.request.urlopen", return_value=_FakeResponse()) as urlopen:
            post_system_alert("call watchdog abort: websocket idle 60s")
        urlopen.assert_called_once()
        req = urlopen.call_args[0][0]
        self.assertEqual(req.full_url, "https://example.test/webhook")
        self.assertEqual(req.get_method(), "POST")
        self.assertEqual(req.get_header("Content-type"), "application/json")
        self.assertEqual(req.get_header("Authorization"), "Bearer crsr_test_key")
        self.assertEqual(json.loads(req.data.decode("utf-8")), {"text": "call watchdog abort: websocket idle 60s"})
        self.assertEqual(urlopen.call_args.kwargs.get("timeout") or urlopen.call_args[1].get("timeout"), 5.0)

    def test_key_already_has_bearer_prefix(self) -> None:
        os.environ[URL_ENV] = "https://example.test/webhook"
        os.environ[KEY_ENV] = "Bearer already_prefixed"
        with patch("alert_webhook.urllib.request.urlopen", return_value=_FakeResponse()) as urlopen:
            post_system_alert("hub down")
        req = urlopen.call_args[0][0]
        self.assertEqual(req.get_header("Authorization"), "Bearer already_prefixed")

    def test_http_error_is_swallowed(self) -> None:
        os.environ[URL_ENV] = "https://example.test/webhook"
        os.environ[KEY_ENV] = "crsr_test_key"
        err = urllib.error.HTTPError(
            "https://example.test/webhook",
            401,
            "Unauthorized",
            hdrs=None,
            fp=io.BytesIO(),
        )
        with self.assertLogs("gsm2computer-hub", level="WARNING"):
            with patch("alert_webhook.urllib.request.urlopen", side_effect=err):
                post_system_alert("call watchdog abort: ping failed")

    def test_transport_error_is_swallowed(self) -> None:
        os.environ[URL_ENV] = "https://example.test/webhook"
        with self.assertLogs("gsm2computer-hub", level="WARNING"):
            with patch("alert_webhook.urllib.request.urlopen", side_effect=TimeoutError("slow")):
                post_system_alert("call watchdog abort: max duration")

    def test_logs_do_not_include_key_or_authorization(self) -> None:
        os.environ[URL_ENV] = "https://example.test/webhook"
        os.environ[KEY_ENV] = "crsr_secret_must_not_appear"
        with self.assertLogs("gsm2computer-hub", level="WARNING") as captured:
            with patch(
                "alert_webhook.urllib.request.urlopen",
                side_effect=urllib.error.HTTPError(
                    "https://example.test/webhook",
                    500,
                    "Error",
                    hdrs=None,
                    fp=io.BytesIO(),
                ),
            ):
                post_system_alert("boom")
        joined = "\n".join(captured.output)
        self.assertNotIn("crsr_secret_must_not_appear", joined)
        self.assertNotIn("Authorization", joined)
        self.assertIn("alert webhook HTTP 500", joined)


if __name__ == "__main__":
    unittest.main()


class TestE2EAlertSkip(unittest.TestCase):
    def test_is_test_alert_paths(self):
        from alert_webhook import is_test_alert

        self.assertTrue(is_test_alert("admin force-release path=/e2e-test"))
        self.assertTrue(is_test_alert("OpenClaw e2e FAILED after heal ladder"))
        self.assertTrue(is_test_alert("x", e2e=True))
        self.assertFalse(is_test_alert("admin force-release path=/ age_s=40"))



class TestAlertEvent(unittest.TestCase):
    def setUp(self) -> None:
        self._env = {URL_ENV: os.environ.get(URL_ENV), KEY_ENV: os.environ.get(KEY_ENV)}
        os.environ.pop(URL_ENV, None)
        os.environ.pop(KEY_ENV, None)

    def tearDown(self) -> None:
        for name, value in self._env.items():
            if value is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = value

    def test_call_ended_posts_even_with_test_true(self) -> None:
        os.environ[URL_ENV] = "https://example.test/webhook"
        os.environ[KEY_ENV] = "crsr_test_key"
        payload = {"event": "call_ended", "test": True, "duration_s": 42, "text": "call_ended TEST"}
        with patch("alert_webhook.urllib.request.urlopen", return_value=_FakeResponse()) as urlopen:
            post_alert_event(payload)
        urlopen.assert_called_once()
        body = json.loads(urlopen.call_args[0][0].data.decode("utf-8"))
        self.assertEqual(body["event"], "call_ended")
        self.assertTrue(body["test"])
        self.assertEqual(body["duration_s"], 42)

    def test_call_ended_not_swallowed_by_e2e_words_in_text(self) -> None:
        """call_ended must wake Cup even if text accidentally mentions e2e."""
        os.environ[URL_ENV] = "https://example.test/webhook"
        payload = {"event": "call_ended", "test": False, "text": "call_ended (not an e2e-test path)"}
        with patch("alert_webhook.urllib.request.urlopen", return_value=_FakeResponse()) as urlopen:
            post_alert_event(payload)
        urlopen.assert_called_once()

    def test_other_e2e_event_skipped(self) -> None:
        os.environ[URL_ENV] = "https://example.test/webhook"
        with patch("alert_webhook.urllib.request.urlopen") as urlopen:
            post_alert_event({"event": "other", "e2e": True, "text": "x"})
        urlopen.assert_not_called()
