import os
import sys
import tempfile
import unittest
from pathlib import Path

HUB_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HUB_DIR))

from portal_store import PortalStore, normalize_peer  # noqa: E402


class PortalStoreTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = PortalStore(Path(self.tmp.name) / "portal.sqlite")

    def tearDown(self):
        self.store.close()
        self.tmp.cleanup()

    def test_normalize_peer(self):
        self.assertEqual(normalize_peer("0015551212"), "+15551212")
        self.assertEqual(normalize_peer(" +1 (555) 987-6 "), "+15559876")

    def test_threads_order_latest_first(self):
        self.store.persist_inbound("+15551111", "older", "2026-01-01T00:00:00Z")
        self.store.persist_inbound("+15552222", "newest", "2026-09-19T12:00:00Z")
        self.store.persist_inbound("+15551111", "mid", "2026-03-01T00:00:00Z")
        threads = self.store.list_threads()
        self.assertEqual([t["peer"] for t in threads], ["+15552222", "+15551111"])
        self.assertEqual(threads[0]["lastBody"], "newest")
        self.assertEqual(threads[1]["lastBody"], "mid")

    def test_messages_sorted_oldest_first(self):
        self.store.persist_inbound("+1555", "a", "2026-01-01T00:00:00Z")
        self.store.persist_inbound("+1555", "b", "2026-01-02T00:00:00Z")
        bodies = [m["body"] for m in self.store.list_messages("+1555")]
        self.assertEqual(bodies, ["a", "b"])

    def test_outbound_queue_and_ack(self):
        queued = self.store.queue_outbound("+15553333", "ping")
        self.assertEqual(queued["status"], "queued")
        self.assertEqual(len(self.store.list_outbox()), 1)
        acked = self.store.ack_outbox(queued["outboxId"])
        self.assertIsNotNone(acked)
        self.assertEqual(acked["status"], "sent")
        self.assertEqual(self.store.list_outbox(), [])
        self.assertEqual(self.store.list_messages("+15553333")[0]["status"], "sent")

    def test_blank_peer_skipped(self):
        self.assertIsNone(self.store.persist_inbound("  ", "hi"))
        self.assertEqual(self.store.list_threads(), [])


if __name__ == "__main__":
    unittest.main()
