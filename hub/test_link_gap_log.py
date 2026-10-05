#!/usr/bin/env python3
"""Link-gap JSONL and the calls-table companion rows."""
from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from link_gap_log import append_link_gap, read_recent
from portal_store import PortalStore


class LinkGapLogTests(unittest.TestCase):
    def test_append_read_and_rotate(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "link-gaps.jsonl"
            append_link_gap(
                {
                    "kind": "gap_open",
                    "session_id": "hub-1",
                    "close_code": None,
                    "initiator": "reset",
                    "close_reason": "connection reset",
                    "ts": "2026-10-04T20:22:51.700Z",
                },
                path=path,
                max_bytes=10_000,
            )
            rows = read_recent(path=path)
            self.assertEqual(len(rows), 1)
            self.assertEqual(rows[0]["kind"], "gap_open")
            self.assertTrue(rows[0]["ts"].endswith("Z"))
            append_link_gap(
                {"kind": "relink", "session_id": "hub-1", "ts": "2026-10-04T20:23:10.000Z"},
                path=path,
                max_bytes=80,
                keep=2,
            )
            self.assertTrue(path.with_name(path.name + ".1").exists() or path.exists())
            text = path.read_text(encoding="utf-8")
            self.assertIn("relink", text)
            for line in path.read_text(encoding="utf-8").splitlines():
                json.loads(line)


class PortalLinkEventTests(unittest.TestCase):
    def test_link_events_survive_reopen(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            db = Path(tmp) / "portal.sqlite"
            store = PortalStore(db)
            store.add_link_event(
                kind="gap_open",
                session_id="hub-abc",
                close_code=None,
                close_reason="incomplete read",
                initiator="eof",
                detail="socket ended",
                ts="2026-10-04T20:22:51.700Z",
            )
            store.close()
            again = PortalStore(db)
            rows = again.list_link_events()
            again.close()
            self.assertEqual(len(rows), 1)
            self.assertEqual(rows[0]["kind"], "gap_open")
            self.assertEqual(rows[0]["session_id"], "hub-abc")
            self.assertEqual(rows[0]["initiator"], "eof")
            self.assertIsNone(rows[0]["close_code"])


if __name__ == "__main__":
    unittest.main()
