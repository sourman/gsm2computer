"""Portal SQLite, SMS persist, outbox, and calls API tests."""
from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

HUB_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HUB_DIR))
os.environ.setdefault(
    "GSM2COMPUTER_PORTAL_DB",
    str(Path(tempfile.mkdtemp()) / "portal-test.sqlite"),
)

from portal_http import EventBus, PortalApp  # noqa: E402
from portal_store import PortalStore, normalize_e164, resolve_call_recording  # noqa: E402


class FakeWriter:
    def __init__(self) -> None:
        self.buf = bytearray()
        self.closed = False

    def write(self, data: bytes) -> None:
        self.buf.extend(data)

    async def drain(self) -> None:
        return None

    def close(self) -> None:
        self.closed = True

    async def wait_closed(self) -> None:
        return None

    def get_extra_info(self, name: str):
        return None


def _parse_http(raw: bytes) -> tuple[int, dict, bytes]:
    header, _, body = raw.partition(b"\r\n\r\n")
    lines = header.decode("iso-8859-1").split("\r\n")
    status = int(lines[0].split()[1])
    headers = {}
    for line in lines[1:]:
        if ":" in line:
            k, v = line.split(":", 1)
            headers[k.strip().lower()] = v.strip()
    return status, headers, body


class PortalStoreTests(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.store = PortalStore(Path(self._tmp.name) / "portal.sqlite")

    def tearDown(self) -> None:
        self.store.close()
        self._tmp.cleanup()

    def test_normalize_e164_us_and_plus(self) -> None:
        self.assertEqual(normalize_e164("5551234567"), "+15551234567")
        self.assertEqual(normalize_e164("1 (555) 123-4567"), "+15551234567")
        self.assertEqual(normalize_e164("+44 7700 900123"), "+447700900123")
        self.assertEqual(normalize_e164("00447700900123"), "+447700900123")
        self.assertEqual(normalize_e164(""), "")

    def test_inbound_sms_persists_and_threads(self) -> None:
        msg = self.store.add_message("in", "+15551212", "hello from pixel", ts="2026-09-09T12:00:00Z")
        self.assertEqual(msg["peer"], "+15551212")
        threads = self.store.list_threads()
        self.assertEqual(threads[0]["peer"], "+15551212")
        self.assertEqual(threads[0]["lastBody"], "hello from pixel")
        listed = self.store.list_messages("+15551212")
        self.assertEqual(len(listed), 1)
        self.assertEqual(listed[0]["direction"], "in")

    def test_send_queues_outbox_and_ack(self) -> None:
        queued = self.store.enqueue_outbound("5551234567", "on my way")
        self.assertEqual(queued["status"], "pending")
        self.assertEqual(queued["to"], "+15551234567")
        self.assertEqual(queued["message"]["status"], "queued")
        pending = self.store.list_outbox_pending()
        self.assertEqual(len(pending), 1)
        claimed = self.store.claim_outbox_for_send()
        self.assertEqual(len(claimed), 1)
        self.assertEqual(claimed[0]["status"], "sending")
        self.assertEqual(self.store.list_outbox_pending(), [])
        acked = self.store.ack_outbox(queued["id"], status="sent")
        self.assertEqual(acked["status"], "sent")
        again = self.store.ack_outbox(queued["id"], status="sent")
        self.assertEqual(again["status"], "sent")
        self.assertEqual(self.store.get_message(queued["id"])["status"], "sent")

    def test_resolve_call_recording_session_tap_then_started_at(self) -> None:
        root = Path(self._tmp.name) / "taps"
        session_dir = root / "tap-session"
        session_dir.mkdir(parents=True)
        (session_dir / "openclaw.mp3").write_bytes(b"ID3session")
        tap_dir = root / "tap-from-summary"
        tap_dir.mkdir()
        (tap_dir / "openclaw.mp3").write_bytes(b"ID3summary")
        started_dir = root / "20260919T151500Z-openclaw"
        started_dir.mkdir()
        (started_dir / "openclaw.mp3").write_bytes(b"ID3started")

        by_session = resolve_call_recording(
            {"session_id": "tap-session", "tap_summary": json.dumps({"id": "tap-from-summary"}), "started_at": "2026-09-19T15:15:00Z"},
            root,
        )
        self.assertEqual(by_session.read_bytes(), b"ID3session")

        by_tap = resolve_call_recording(
            {"session_id": None, "tap_summary": json.dumps({"id": "tap-from-summary"}), "started_at": "2026-09-19T15:15:00Z"},
            root,
        )
        self.assertEqual(by_tap.read_bytes(), b"ID3summary")

        by_time = resolve_call_recording(
            {"session_id": None, "tap_summary": None, "started_at": "2026-09-19T15:15:00Z"},
            root,
        )
        self.assertEqual(by_time.read_bytes(), b"ID3started")

        traversal = resolve_call_recording({"session_id": "../secret"}, root)
        self.assertIsNone(traversal)

    def test_get_call_by_id(self) -> None:
        item = self.store.add_call("in", "+15550001111", "2026-09-19T15:15:00Z", 12, session_id="tap-1")
        found = self.store.get_call(item["id"])
        self.assertEqual(found["session_id"], "tap-1")
        self.assertIsNone(self.store.get_call("missing"))


class PortalHttpTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        root = Path(self._tmp.name)
        self.store = PortalStore(root / "portal.sqlite")
        dist = root / "dist"
        dist.mkdir()
        (dist / "index.html").write_text("<!doctype html><title>portal</title>", encoding="utf-8")
        self.app = PortalApp(
            store=self.store,
            events=EventBus(),
            dist_dir=dist,
            mode_getter=lambda: "openclaw",
            tap_getter=lambda: {"id": "tap-1", "streams": {"gsm": {"seconds": 3}}},
            record_dir=root / "taps",
        )

    async def asyncTearDown(self) -> None:
        self.store.close()
        self._tmp.cleanup()

    async def test_http_sms_send_outbox_ack_and_calls(self) -> None:
        writer = FakeWriter()
        send_body = json.dumps({"to": "+15550001111", "body": "ping"}).encode()
        result = await self.app.dispatch("POST", "/portal/api/messages/send", {}, send_body, writer)
        self.assertEqual(result, "handled")
        status, _, body = _parse_http(bytes(writer.buf))
        self.assertEqual(status, 201)
        payload = json.loads(body)
        self.assertTrue(payload["ok"])
        outbox_id = payload["id"]

        writer = FakeWriter()
        await self.app.dispatch("GET", "/sms/outbox", {}, b"", writer)
        status, _, body = _parse_http(bytes(writer.buf))
        self.assertEqual(status, 200)
        items = json.loads(body)["items"]
        self.assertEqual(items[0]["id"], outbox_id)
        self.assertEqual(items[0]["to"], "+15550001111")
        self.assertEqual(items[0]["status"], "sending")

        writer = FakeWriter()
        await self.app.dispatch("GET", "/sms/outbox", {}, b"", writer)
        self.assertEqual(json.loads(_parse_http(bytes(writer.buf))[2])["items"], [])

        writer = FakeWriter()
        ack = json.dumps({"status": "sent"}).encode()
        await self.app.dispatch("POST", f"/sms/outbox/{outbox_id}/ack", {}, ack, writer)
        status, _, body = _parse_http(bytes(writer.buf))
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["item"]["status"], "sent")

        writer = FakeWriter()
        await self.app.dispatch("GET", "/portal/api/threads", {}, b"", writer)
        threads = json.loads(_parse_http(bytes(writer.buf))[2])
        self.assertEqual(threads[0]["peer"], "+15550001111")

        writer = FakeWriter()
        call_body = json.dumps(
            {
                "direction": "IN",
                "number": "5550001111",
                "started_at": "2026-09-09T12:01:00Z",
                "duration_sec": 42,
            }
        ).encode()
        await self.app.dispatch("POST", "/calls", {}, call_body, writer)
        status, _, body = _parse_http(bytes(writer.buf))
        self.assertEqual(status, 201)
        call = json.loads(body)["call"]
        self.assertEqual(call["direction"], "in")
        self.assertEqual(call["number"], "+15550001111")
        self.assertEqual(call["duration_sec"], 42)
        self.assertEqual(call["switchboard_mode"], "openclaw")
        self.assertEqual(call["session_id"], "tap-1")
        self.assertIn("tap-1", call["tap_summary"] or "")

        writer = FakeWriter()
        await self.app.dispatch("GET", "/portal/api/calls", {}, b"", writer)
        calls = json.loads(_parse_http(bytes(writer.buf))[2])
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0]["id"], call["id"])

        writer = FakeWriter()
        await self.app.dispatch("GET", "/portal/", {}, b"", writer)
        status, headers, body = _parse_http(bytes(writer.buf))
        self.assertEqual(status, 200)
        self.assertIn("text/html", headers["content-type"])
        self.assertIn(b"portal", body)

    async def test_messages_require_peer(self) -> None:
        writer = FakeWriter()
        await self.app.dispatch("GET", "/portal/api/messages", {}, b"", writer)
        status, _, body = _parse_http(bytes(writer.buf))
        self.assertEqual(status, 400)
        self.assertFalse(json.loads(body)["ok"])

    async def test_call_recording_bytes_and_range(self) -> None:
        taps = Path(self._tmp.name) / "taps"
        tap_id = "20260919T151500Z-openclaw"
        (taps / tap_id).mkdir(parents=True)
        payload = b"ID3" + bytes(range(32))
        (taps / tap_id / "openclaw.mp3").write_bytes(payload)

        writer = FakeWriter()
        call_body = json.dumps(
            {
                "direction": "in",
                "number": "5550001111",
                "started_at": "2026-09-19T15:15:00Z",
                "duration_sec": 9,
                "session_id": tap_id,
            }
        ).encode()
        await self.app.dispatch("POST", "/calls", {}, call_body, writer)
        call_id = json.loads(_parse_http(bytes(writer.buf))[2])["call"]["id"]

        writer = FakeWriter()
        await self.app.dispatch("GET", f"/portal/api/calls/{call_id}/recording", {}, b"", writer)
        status, headers, body = _parse_http(bytes(writer.buf))
        self.assertEqual(status, 200)
        self.assertEqual(headers["content-type"], "audio/mpeg")
        self.assertEqual(headers["accept-ranges"], "bytes")
        self.assertEqual(body, payload)

        writer = FakeWriter()
        await self.app.dispatch(
            "GET",
            f"/portal/api/calls/{call_id}/recording",
            {"range": "bytes=0-1"},
            b"",
            writer,
        )
        status, headers, body = _parse_http(bytes(writer.buf))
        self.assertEqual(status, 206)
        self.assertEqual(body, payload[:2])
        self.assertEqual(headers["content-range"], f"bytes 0-1/{len(payload)}")

        writer = FakeWriter()
        await self.app.dispatch("GET", "/portal/api/calls/missing/recording", {}, b"", writer)
        status, _, body = _parse_http(bytes(writer.buf))
        self.assertEqual(status, 404)
        self.assertFalse(json.loads(body)["ok"])


class HubIngestTests(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.store = PortalStore(Path(self._tmp.name) / "portal.sqlite")
        self.events = EventBus()

    def tearDown(self) -> None:
        self.store.close()
        self._tmp.cleanup()

    def test_hub_ingest_sms_persists_and_keeps_mode_command(self) -> None:
        from portal_sms import ingest_inbound_sms

        log_entry, command = ingest_inbound_sms(
            self.store,
            self.events,
            {"from": "+15559876", "body": "MODE openclaw", "receivedAt": "2026-09-09T12:00:00Z"},
        )
        self.assertEqual(command, "openclaw")
        self.assertEqual(log_entry["from"], "+15559876")
        threads = self.store.list_threads()
        self.assertEqual(threads[0]["peer"], "+15559876")
        self.assertEqual(threads[0]["lastBody"], "MODE openclaw")

        _, status_cmd = ingest_inbound_sms(self.store, self.events, {"from": "+15559876", "body": "STATUS"})
        self.assertEqual(status_cmd, "status")

        _, none_cmd = ingest_inbound_sms(
            self.store, self.events, {"from": "+15559876", "body": "hello operator"}
        )
        self.assertIsNone(none_cmd)
        self.assertEqual(len(self.store.list_messages("+15559876")), 3)


if __name__ == "__main__":
    unittest.main()
