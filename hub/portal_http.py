"""HTTP routes for /portal and the SMS outbox. Does not touch the Talk WebSocket path."""
from __future__ import annotations

import asyncio
import json
import logging
import mimetypes
from pathlib import Path
from typing import Any, Optional
from urllib.parse import unquote

from portal_store import HUB_DIR, get_bus, get_store

LOG = logging.getLogger("gsm2computer-hub")

PORTAL_DIST = HUB_DIR / "portal" / "dist"
API_PREFIX = "/portal/api/"

_STATUS = {
    200: "OK",
    201: "Created",
    301: "Moved Permanently",
    400: "Bad Request",
    404: "Not Found",
    405: "Method Not Allowed",
    500: "Internal Server Error",
    503: "Service Unavailable",
}


def _http(status: int, headers: dict[str, str], payload: bytes) -> bytes:
    lines = [f"HTTP/1.1 {status} {_STATUS.get(status, '')}".rstrip()]
    for key, value in headers.items():
        lines.append(f"{key}: {value}")
    lines.extend(["", ""])
    return "\r\n".join(lines).encode("ascii") + payload


def json_bytes(status: int, body: Any, extra: Optional[dict[str, str]] = None) -> bytes:
    payload = json.dumps(body).encode("utf-8")
    headers = {
        "Content-Type": "application/json; charset=utf-8",
        "Content-Length": str(len(payload)),
        "Connection": "close",
        "Cache-Control": "no-store",
    }
    if extra:
        headers.update(extra)
    return _http(status, headers, payload)


async def _write_close(writer: asyncio.StreamWriter, data: bytes) -> None:
    try:
        writer.write(data)
        await writer.drain()
    finally:
        writer.close()
        try:
            await writer.wait_closed()
        except Exception:
            pass


def _json_body(body: bytes) -> dict[str, Any]:
    if not body:
        return {}
    try:
        payload = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError("invalid json") from exc
    if not isinstance(payload, dict):
        raise ValueError("invalid json")
    return payload


async def dispatch_portal(
    method: str,
    path: str,
    query: dict[str, str],
    headers: dict[str, str],
    body: bytes,
    reader: asyncio.StreamReader,
    writer: asyncio.StreamWriter,
) -> bool:
    """Handle portal/outbox routes. True = response already written and writer closed."""
    if path in ("/sms/outbox", "/sms/outbox/"):
        await _handle_outbox(method, writer)
        return True
    if path in ("/sms/outbox/ack", "/sms/outbox/ack/"):
        await _handle_outbox_ack(method, body, writer)
        return True
    if path.rstrip("/") == "/portal" and path != "/portal/":
        payload = b""
        await _write_close(
            writer,
            _http(
                301,
                {
                    "Location": "/portal/",
                    "Content-Length": "0",
                    "Connection": "close",
                },
                payload,
            ),
        )
        return True
    if not path.startswith("/portal/") and path != "/portal/":
        return False
    if path.startswith(API_PREFIX) or path.rstrip("/") == "/portal/api":
        await _handle_api(method, path, query, body, reader, writer)
        return True
    if method not in ("GET", "HEAD"):
        await _write_close(writer, json_bytes(405, {"ok": False, "error": "method not allowed"}))
        return True
    await _serve_static(path, method == "HEAD", writer)
    return True


async def _handle_outbox(method: str, writer: asyncio.StreamWriter) -> None:
    if method != "GET":
        await _write_close(writer, json_bytes(405, {"ok": False, "error": "method not allowed"}))
        return
    await _write_close(writer, json_bytes(200, {"ok": True, "outbox": get_store().list_outbox()}))


async def _handle_outbox_ack(method: str, body: bytes, writer: asyncio.StreamWriter) -> None:
    if method != "POST":
        await _write_close(writer, json_bytes(405, {"ok": False, "error": "method not allowed"}))
        return
    try:
        payload = _json_body(body)
    except ValueError:
        await _write_close(writer, json_bytes(400, {"ok": False, "error": "invalid json"}))
        return
    token = str(payload.get("id") or payload.get("outboxId") or "").strip()
    rec = get_store().ack_outbox(token)
    if rec is None:
        await _write_close(writer, json_bytes(404, {"ok": False, "error": "outbox item not found"}))
        return
    get_bus().publish(rec)
    await _write_close(writer, json_bytes(200, {"ok": True, "message": rec}))


async def _handle_api(
    method: str,
    path: str,
    query: dict[str, str],
    body: bytes,
    reader: asyncio.StreamReader,
    writer: asyncio.StreamWriter,
) -> None:
    route = path[len(API_PREFIX) :].strip("/") if path.startswith(API_PREFIX) else ""
    if route == "events":
        if method != "GET":
            await _write_close(writer, json_bytes(405, {"ok": False, "error": "method not allowed"}))
            return
        await _sse_events(reader, writer)
        return
    if route == "threads":
        if method != "GET":
            await _write_close(writer, json_bytes(405, {"ok": False, "error": "method not allowed"}))
            return
        await _write_close(writer, json_bytes(200, get_store().list_threads()))
        return
    if route == "messages":
        if method == "GET":
            peer = unquote(query.get("peer") or "")
            if not peer.strip():
                await _write_close(writer, json_bytes(400, {"ok": False, "error": "peer required"}))
                return
            await _write_close(writer, json_bytes(200, get_store().list_messages(peer)))
            return
        await _write_close(writer, json_bytes(405, {"ok": False, "error": "method not allowed"}))
        return
    if route == "messages/send":
        if method != "POST":
            await _write_close(writer, json_bytes(405, {"ok": False, "error": "method not allowed"}))
            return
        try:
            payload = _json_body(body)
            rec = get_store().queue_outbound(str(payload.get("to") or ""), str(payload.get("body") or ""))
        except ValueError as exc:
            await _write_close(writer, json_bytes(400, {"ok": False, "error": str(exc)}))
            return
        get_bus().publish(rec)
        await _write_close(writer, json_bytes(200, {"ok": True, "message": rec}))
        return
    if route == "calls":
        if method != "GET":
            await _write_close(writer, json_bytes(405, {"ok": False, "error": "method not allowed"}))
            return
        await _write_close(writer, json_bytes(200, get_store().list_calls()))
        return
    await _write_close(writer, json_bytes(404, {"ok": False, "error": "not found"}))


async def _sse_events(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    headers = (
        "HTTP/1.1 200 OK\r\n"
        "Content-Type: text/event-stream; charset=utf-8\r\n"
        "Cache-Control: no-cache, no-store\r\n"
        "Connection: keep-alive\r\n"
        "X-Accel-Buffering: no\r\n"
        "\r\n"
    )
    writer.write(headers.encode("ascii"))
    await writer.drain()
    bus = get_bus()
    queue = bus.subscribe()
    try:
        writer.write(b": connected\n\n")
        await writer.drain()
        while True:
            try:
                event = await asyncio.wait_for(queue.get(), timeout=15.0)
            except asyncio.TimeoutError:
                writer.write(b": ping\n\n")
                await writer.drain()
                continue
            name = str(event.get("type") or "message")
            payload = json.dumps(event)
            frame = f"event: {name}\ndata: {payload}\n\n".encode("utf-8")
            writer.write(frame)
            await writer.drain()
    except (ConnectionError, asyncio.IncompleteReadError, BrokenPipeError, ConnectionResetError):
        LOG.debug("portal sse client disconnected")
    except Exception:
        LOG.exception("portal sse error")
    finally:
        bus.unsubscribe(queue)
        writer.close()
        try:
            await writer.wait_closed()
        except Exception:
            pass
        # reader is unused except to keep the request task associated with the socket
        _ = reader


def _safe_dist_file(rel: str) -> Optional[Path]:
    if not PORTAL_DIST.is_dir():
        return None
    cleaned = rel.replace("\\", "/").lstrip("/")
    if ".." in Path(cleaned).parts:
        return None
    candidate = (PORTAL_DIST / cleaned).resolve()
    try:
        candidate.relative_to(PORTAL_DIST.resolve())
    except ValueError:
        return None
    if candidate.is_file():
        return candidate
    return None


async def _serve_static(path: str, head_only: bool, writer: asyncio.StreamWriter) -> None:
    rel = path[len("/portal") :].lstrip("/")
    if not rel or rel.endswith("/"):
        rel = f"{rel}index.html" if rel else "index.html"
    target = _safe_dist_file(rel)
    if target is None and not rel.startswith("api/"):
        target = _safe_dist_file("index.html")
    if target is None:
        if not PORTAL_DIST.is_dir():
            html = (
                b"<!doctype html><meta charset=utf-8><title>Portal not built</title>"
                b"<body style='font-family:sans-serif;background:#1a1410;color:#f4ead8'>"
                b"<p>Hub portal dist is missing. From <code>hub/portal</code> run "
                b"<code>npm install && npm run build</code>.</p>"
            )
            await _write_close(
                writer,
                _http(
                    503,
                    {
                        "Content-Type": "text/html; charset=utf-8",
                        "Content-Length": str(len(html)),
                        "Connection": "close",
                    },
                    html,
                ),
            )
            return
        await _write_close(writer, json_bytes(404, {"ok": False, "error": "not found"}))
        return
    data = target.read_bytes()
    mime, _ = mimetypes.guess_type(str(target))
    if target.suffix == ".js":
        mime = "text/javascript"
    elif target.suffix == ".webmanifest":
        mime = "application/manifest+json"
    elif not mime:
        mime = "application/octet-stream"
    cache = "no-cache" if target.name in {"index.html", "sw.js", "manifest.json"} else "public, max-age=31536000, immutable"
    headers = {
        "Content-Type": mime,
        "Content-Length": str(len(data)),
        "Connection": "close",
        "Cache-Control": cache,
    }
    payload = b"" if head_only else data
    if head_only:
        headers["Content-Length"] = str(len(data))
    await _write_close(writer, _http(200, headers, payload))
