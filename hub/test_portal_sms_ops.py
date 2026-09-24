#!/usr/bin/env python3
import os
import unittest

from portal_sms import (
    format_ops_sms_alert,
    is_ops_sender,
    looks_like_system_down,
    normalize_msisdn,
    should_wake_cup,
)


class OpsSmsWakeTests(unittest.TestCase):
    def test_normalize(self):
        self.assertEqual(normalize_msisdn("+201280043725"), "+201280043725")
        self.assertEqual(normalize_msisdn("201280043725"), "+201280043725")
        self.assertEqual(normalize_msisdn("+20 128 004 3725"), "+201280043725")

    def test_ops_sender_default(self):
        self.assertTrue(is_ops_sender("+201280043725"))
        self.assertTrue(is_ops_sender("01280043725"))
        self.assertFalse(is_ops_sender("+201281498069"))

    def test_ops_sender_env_override(self):
        os.environ["GSM2COMPUTER_OPS_SMS_NUMBERS"] = "+15551212,+201299999999"
        try:
            self.assertTrue(is_ops_sender("+15551212"))
            self.assertFalse(is_ops_sender("+201280043725"))
        finally:
            del os.environ["GSM2COMPUTER_OPS_SMS_NUMBERS"]

    def test_system_down_parse(self):
        self.assertTrue(looks_like_system_down("system down"))
        self.assertTrue(looks_like_system_down("SYS DOWN"))
        self.assertTrue(looks_like_system_down("Hey mr. jumper cup .. still down "))
        self.assertTrue(looks_like_system_down("STATUS"))
        self.assertFalse(looks_like_system_down("CAN U CALL ME AFTER 2 HOURS"))

    def test_should_wake(self):
        self.assertTrue(should_wake_cup("+201280043725", "anything"))
        self.assertTrue(should_wake_cup("+1999", "system down"))
        self.assertFalse(should_wake_cup("+1999", "thanks"))

    def test_format(self):
        text = format_ops_sms_alert("+201280043725", "still down", "t")
        self.assertIn("ops SMS wake-up", text)
        self.assertIn("+201280043725", text)
        self.assertIn("still down", text)


if __name__ == "__main__":
    unittest.main()
