import asyncio
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

os.environ["GSM2COMPUTER_OPENCLAW_TALK"] = "off"

HUB_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HUB_DIR))

from portal_store import reset_for_tests  # noqa: E402
from hub import handle_client, split_request_target  # noqa: E402


def _encode_request(method: str, target: str, body: bytes = b"", extra_headers: list[str] | None = None) -> bytes:
    headers = [
        f"{method} {target} HTTP/1.1",
        "Host: 127.0.0.1",
        f"Content-Length: {len(body)}",
        "Connection: close",
    ]
    if body:
        headers.append("Content-Type: application/json")
    if extra_headers:
        headers.extend(extra_headers)
    return ("\r\n".join(headers) + "\r\n\r\n").encode("ascii") + body


def _parse_http(blob: bytes) -> tuple[int, dict[str, str], bytes]:
    header_blob, _, payload = blob.partition(b"\r\n\r\n")
    lines = header_blob.decode("iso-8859-1").split("\r\n")
    status = int(lines[0].split()[1])
    headers: dict[str, str] = {}
    for line in lines[1:]:
        if ":" not in line:
            continue
        name, value = line.split(":", 1)
        headers[name.strip().lower()] = value.strip()
    return status, headers, payload


class SplitTargetTest(unittest.TestCase):
    def test_query(self):
        path, query = split_request_target("/portal/api/messages?peer=%2B1555")
        self.assertEqual(path, "/portal/api/messages")
        self.assertEqual(query.get("peer"), "+1555")


class PortalHttpTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        db = Path(self.tmp.name) / "portal.sqlite"
        os.environ["GSM2COMPUTER_PORTAL_DB"] = str(db)
        reset_for_tests(db)
        self.server = await asyncio.start_server(handle_client, "127.0.0.1", 0)
        self.port = self.server.sockets[0].getsockname()[1]

    async def asyncTearDown(self):
        self.server.close()
        await self.server.wait_closed()
        self.tmp.cleanup()

    async def _exchange(self, request: bytes) -> tuple[int, dict[str, str], bytes]:
        reader, writer = await asyncio.open_connection("127.0.0.1", self.port)
        writer.write(request)
        await writer.drain()
        blob = await asyncio.wait_for(reader.read(), timeout=5)
        writer.close()
        await writer.wait_closed()
        return _parse_http(blob)

    async def test_sms_persists_and_lists_latest_thread_first(self):
        first = json.dumps({"from": "+15551111", "body": "older", "receivedAt": "2026-01-01T00:00:00Z"}).encode()
        second = json.dumps({"from": "+15552222", "body": "newest", "receivedAt": "2026-09-19T12:00:00Z"}).encode()
        status, _, body = await self._exchange(_encode_request("POST", "/sms", first))
        self.assertEqual(status, 200)
        self.assertTrue(json.loads(body)["ok"])
        await self._exchange(_encode_request("POST", "/sms", second))
        status, _, body = await self._exchange(_encode_request("GET", "/portal/api/threads"))
        self.assertEqual(status, 200)
        threads = json.loads(body)
        self.assertEqual(threads[0]["peer"], "+15552222")
        self.assertEqual(threads[0]["lastBody"], "newest")
        status, _, body = await self._exchange(_encode_request("GET", "/portal/api/messages?peer=%2B15552222"))
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)[0]["body"], "newest")

    async def test_send_queues_outbox_ack_updates_status(self):
        payload = json.dumps({"to": "+15553333", "body": "desk ping"}).encode()
        status, _, body = await self._exchange(_encode_request("POST", "/portal/api/messages/send", payload))
        self.assertEqual(status, 200)
        rec = json.loads(body)["message"]
        self.assertEqual(rec["status"], "queued")
        status, _, body = await self._exchange(_encode_request("GET", "/sms/outbox"))
        self.assertEqual(status, 200)
        item = json.loads(body)["outbox"][0]
        ack = json.dumps({"id": item["id"]}).encode()
        status, _, body = await self._exchange(_encode_request("POST", "/sms/outbox/ack", ack))
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["message"]["status"], "sent")

    async def test_sse_emits_inbound_message(self):
        reader, writer = await asyncio.open_connection("127.0.0.1", self.port)
        writer.write(_encode_request("GET", "/portal/api/events", extra_headers=["Accept: text/event-stream"]))
        await writer.drain()
        header = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), timeout=5)
        self.assertIn(b"200", header.split(b"\r\n", 1)[0])
        # connected comment
        await asyncio.wait_for(reader.readline(), timeout=5)
        await asyncio.wait_for(reader.readline(), timeout=5)
        sms = json.dumps({"from": "+15554444", "body": "sse-hi", "receivedAt": "2026-09-19T12:00:00Z"}).encode()
        await self._exchange(_encode_request("POST", "/sms", sms))
        event_line = await asyncio.wait_for(reader.readline(), timeout=5)
        data_line = await asyncio.wait_for(reader.readline(), timeout=5)
        self.assertTrue(event_line.startswith(b"event: message"))
        payload = json.loads(data_line.decode("utf-8").split("data:", 1)[1].strip())
        self.assertEqual(payload["body"], "sse-hi")
        self.assertEqual(payload["peer"], "+15554444")
        writer.close()
        await writer.wait_closed()

    async def test_health_still_ok(self):
        status, _, body = await self._exchange(_encode_request("GET", "/health"))
        self.assertEqual(status, 200)
        self.assertTrue(json.loads(body)["ok"])


if __name__ == "__main__":
    unittest.main()
