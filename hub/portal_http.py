#!/usr/bin/env python3
"""Portal REST, SSE, static files, SMS outbox, and call ingest (ADR 0006)."""
from __future__ import annotations

import asyncio
import json
import logging
import mimetypes
import re
from pathlib import Path
from typing import Any, Callable, Optional
from urllib.parse import parse_qs, urlsplit

from portal_store import PortalStore, utc_now

LOG = logging.getLogger("gsm2computer-hub")

OUTBOX_ACK_RE = re.compile(r"^/sms/outbox/([^/]+)/ack$")

ModeGetter = Callable[[], Optional[str]]
TapGetter = Callable[[], Optional[dict[str, Any]]]


class EventBus:
    def __init__(self) -> None:
        self._subs: set[asyncio.Queue] = set()

    def subscribe(self) -> asyncio.Queue:
        q: asyncio.Queue = asyncio.Queue(maxsize=64)
        self._subs.add(q)
        return q

    def unsubscribe(self, q: asyncio.Queue) -> None:
        self._subs.discard(q)

    def publish(self, event_type: str, data: dict[str, Any]) -> None:
        payload = (event_type, data)
        for q in list(self._subs):
            try:
                q.put_nowait(payload)
            except asyncio.QueueFull:
                LOG.warning("portal sse drop event=%s (subscriber queue full)", event_type)


def json_response(status: int, body: Any, extra_headers: Optional[dict] = None) -> bytes:
    payload = json.dumps(body).encode("utf-8")
    status_text = {
        200: "OK",
        201: "Created",
        400: "Bad Request",
        404: "Not Found",
        405: "Method Not Allowed",
        409: "Conflict",
        500: "Internal Server Error",
        503: "Service Unavailable",
    }.get(status, "")
    lines = [
        f"HTTP/1.1 {status} {status_text}".rstrip(),
        "Content-Type: application/json; charset=utf-8",
        f"Content-Length: {len(payload)}",
        "Connection: close",
    ]
    if extra_headers:
        for k, v in extra_headers.items():
            lines.append(f"{k}: {v}")
    lines.extend(["", ""])
    return "\r\n".join(lines).encode("ascii") + payload


def raw_response(
    status: int,
    payload: bytes,
    content_type: str,
    extra_headers: Optional[dict] = None,
    extra_status: str = "",
) -> bytes:
    status_text = extra_status or {
        200: "OK",
        302: "Found",
        404: "Not Found",
        503: "Service Unavailable",
    }.get(status, "")
    lines = [
        f"HTTP/1.1 {status} {status_text}".rstrip(),
        f"Content-Type: {content_type}",
        f"Content-Length: {len(payload)}",
        "Connection: close",
    ]
    if extra_headers:
        for k, v in extra_headers.items():
            lines.append(f"{k}: {v}")
    lines.extend(["", ""])
    return "\r\n".join(lines).encode("ascii") + payload


def _tap_summary_text(tap: Optional[dict[str, Any]]) -> Optional[str]:
    if not tap:
        return None
    return json.dumps(tap, ensure_ascii=False)


class PortalApp:
    def __init__(
        self,
        store: PortalStore,
        events: EventBus,
        dist_dir: Path,
        mode_getter: Optional[ModeGetter] = None,
        tap_getter: Optional[TapGetter] = None,
    ) -> None:
        self.store = store
        self.events = events
        self.dist_dir = Path(dist_dir)
        self.mode_getter = mode_getter or (lambda: None)
        self.tap_getter = tap_getter or (lambda: None)

    async def dispatch(
        self,
        method: str,
        path: str,
        headers: dict[str, str],
        body: bytes,
        writer: asyncio.StreamWriter,
    ) -> str:
        """Handle portal / outbox / calls routes.

        Returns 'handled', 'stream', or 'unhandled'.
        """
        split = urlsplit(path)
        route = split.path
        query = parse_qs(split.query)

        if method == "GET" and route == "/portal/api/events":
            await self._sse(writer)
            return "stream"

        if method == "GET" and route == "/portal/api/threads":
            writer.write(json_response(200, self.store.list_threads()))
            return "handled"

        if method == "GET" and route == "/portal/api/messages":
            peer = (query.get("peer") or [""])[0]
            if not peer.strip():
                writer.write(json_response(400, {"ok": False, "error": "peer required"}))
                return "handled"
            writer.write(json_response(200, self.store.list_messages(peer)))
            return "handled"

        if method == "POST" and route == "/portal/api/messages/send":
            writer.write(self._send_message(body))
            return "handled"

        if method == "GET" and route == "/portal/api/calls":
            writer.write(json_response(200, self.store.list_calls()))
            return "handled"

        if method == "GET" and route == "/sms/outbox":
            writer.write(json_response(200, {"ok": True, "items": self.store.claim_outbox_for_send()}))
            return "handled"

        ack = OUTBOX_ACK_RE.match(route)
        if ack and method == "POST":
            writer.write(self._ack_outbox(ack.group(1), body))
            return "handled"

        if method == "POST" and route == "/calls":
            writer.write(self._add_call(body))
            return "handled"

        if route == "/portal" and method == "GET":
            writer.write(
                raw_response(
                    302,
                    b"",
                    "text/plain",
                    extra_headers={"Location": "/portal/"},
                    extra_status="Found",
                )
            )
            return "handled"

        if route == "/" and method == "GET":
            writer.write(
                raw_response(
                    302,
                    b"",
                    "text/plain",
                    extra_headers={"Location": "/portal/"},
                    extra_status="Found",
                )
            )
            return "handled"

        if route.startswith("/portal/") and method == "GET":
            writer.write(self._static(route))
            return "handled"

        return "unhandled"

    def _send_message(self, body: bytes) -> bytes:
        try:
            payload = json.loads(body.decode("utf-8") if body else "{}")
        except json.JSONDecodeError:
            return json_response(400, {"ok": False, "error": "invalid json"})
        to_number = str(payload.get("to") or "")
        text = str(payload.get("body") or "")
        try:
            queued = self.store.enqueue_outbound(to_number, text)
        except ValueError as exc:
            return json_response(400, {"ok": False, "error": str(exc)})
        self.events.publish("message", queued["message"])
        self.events.publish("outbox", {k: v for k, v in queued.items() if k != "message"})
        return json_response(201, {"ok": True, **queued})

    def _ack_outbox(self, item_id: str, body: bytes) -> bytes:
        status = "sent"
        error = None
        if body:
            try:
                payload = json.loads(body.decode("utf-8"))
            except json.JSONDecodeError:
                payload = {}
            raw_status = str(payload.get("status") or "").strip().lower()
            if raw_status in {"sent", "failed"}:
                status = raw_status
            elif payload.get("ok") is False:
                status = "failed"
            error = payload.get("error")
            if error is not None:
                error = str(error)
        result = self.store.ack_outbox(item_id, status=status, error=error)
        if result is None:
            return json_response(404, {"ok": False, "error": "outbox item not found"})
        msg = self.store.get_message(item_id)
        if msg:
            self.events.publish("message", msg)
        self.events.publish("outbox", result)
        return json_response(200, {"ok": True, "item": result})

    def _add_call(self, body: bytes) -> bytes:
        try:
            payload = json.loads(body.decode("utf-8") if body else "{}")
        except json.JSONDecodeError:
            return json_response(400, {"ok": False, "error": "invalid json"})
        number = str(payload.get("number") or "")
        if not number.strip():
            return json_response(400, {"ok": False, "error": "number required"})
        direction = str(payload.get("direction") or "in")
        started_at = str(payload.get("started_at") or utc_now())
        try:
            duration_sec = int(payload.get("duration_sec") or 0)
        except (TypeError, ValueError):
            return json_response(400, {"ok": False, "error": "duration_sec must be int"})
        mode = str(payload.get("switchboard_mode") or "").strip() or None
        session_id = str(payload.get("session_id") or "").strip() or None
        tap_summary = payload.get("tap_summary")
        if isinstance(tap_summary, dict):
            tap_summary = json.dumps(tap_summary, ensure_ascii=False)
        elif tap_summary is not None:
            tap_summary = str(tap_summary) or None

        tap = self.tap_getter()
        if not mode:
            mode = self.mode_getter()
        if not session_id and tap:
            session_id = str(tap.get("id") or "") or None
        if not tap_summary:
            tap_summary = _tap_summary_text(tap)

        item = self.store.add_call(
            direction=direction,
            number=number,
            started_at=started_at,
            duration_sec=duration_sec,
            switchboard_mode=mode,
            session_id=session_id,
            tap_summary=tap_summary,
        )
        self.events.publish("call", item)
        return json_response(201, {"ok": True, "call": item})

    def _static(self, route: str) -> bytes:
        dist = self.dist_dir.resolve()
        if not dist.is_dir():
            return json_response(
                503,
                {"ok": False, "error": "portal dist missing; run npm run build in hub/portal"},
            )
        rel = route[len("/portal/") :]
        if not rel or rel.endswith("/"):
            rel = (rel or "") + "index.html"
        candidate = (dist / rel).resolve()
        try:
            candidate.relative_to(dist)
        except ValueError:
            return json_response(404, {"ok": False, "error": "not found"})
        if candidate.is_dir():
            candidate = candidate / "index.html"
        if not candidate.is_file():
            index = dist / "index.html"
            if rel.endswith(".html") is False and "." not in Path(rel).name and index.is_file():
                candidate = index
            else:
                return json_response(404, {"ok": False, "error": "not found"})
        data = candidate.read_bytes()
        ctype, _ = mimetypes.guess_type(str(candidate))
        if candidate.suffix == ".webmanifest" or candidate.name == "manifest.json":
            ctype = "application/manifest+json"
        elif candidate.suffix == ".js":
            ctype = "application/javascript; charset=utf-8"
        elif candidate.suffix == ".css":
            ctype = "text/css; charset=utf-8"
        elif candidate.suffix == ".html":
            ctype = "text/html; charset=utf-8"
        elif not ctype:
            ctype = "application/octet-stream"
        headers = {}
        if candidate.name == "index.html":
            headers["Cache-Control"] = "no-cache"
        elif candidate.suffix in {".js", ".css"} and "index-" in candidate.name:
            headers["Cache-Control"] = "public, max-age=31536000, immutable"
        return raw_response(200, data, ctype, extra_headers=headers or None)

    async def _sse(self, writer: asyncio.StreamWriter) -> None:
        headers = (
            "HTTP/1.1 200 OK\r\n"
            "Content-Type: text/event-stream; charset=utf-8\r\n"
            "Cache-Control: no-cache\r\n"
            "Connection: keep-alive\r\n"
            "X-Accel-Buffering: no\r\n"
            "\r\n"
        )
        writer.write(headers.encode("ascii"))
        await writer.drain()
        q = self.events.subscribe()
        try:
            writer.write(b": connected\n\n")
            await writer.drain()
            while True:
                try:
                    event_type, data = await asyncio.wait_for(q.get(), timeout=15)
                except asyncio.TimeoutError:
                    writer.write(b": ping\n\n")
                    await writer.drain()
                    continue
                payload = json.dumps(data, ensure_ascii=False)
                writer.write(f"event: {event_type}\ndata: {payload}\n\n".encode("utf-8"))
                await writer.drain()
        except (ConnectionError, BrokenPipeError, asyncio.CancelledError):
            pass
        finally:
            self.events.unsubscribe(q)
