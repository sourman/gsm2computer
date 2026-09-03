#!/usr/bin/env python3
"""gsm2computer hub: μ-law WebSocket ↔ PipeWire gsm_bus + switchboard control."""
from __future__ import annotations

import asyncio
import audioop
import base64
import hashlib
import json
import logging
import os
import re
import signal
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Optional, Tuple

HOST = os.environ.get("GSM2COMPUTER_HUB_HOST", "100.101.181.110")
PORT = int(os.environ.get("GSM2COMPUTER_HUB_PORT", "8787"))
HUB_DIR = Path(os.environ.get("GSM2COMPUTER_HUB_DIR", Path(__file__).resolve().parent))
SWITCHBOARD = HUB_DIR / "switchboard.sh"
GSM_SINK = os.environ.get("GSM2COMPUTER_GSM_SINK", "gsm_bus")
GSM_MONITOR = os.environ.get("GSM2COMPUTER_GSM_MONITOR", "gsm_bus.monitor")
OPENCLAW_DOWNLINK_MONITOR = os.environ.get(
    "GSM2COMPUTER_OPENCLAW_DOWNLINK_MONITOR", "openclaw_bus.monitor"
)
AUDIO_RATE = int(os.environ.get("GSM2COMPUTER_AUDIO_RATE", "8000"))
AUTO_MODE_ON_CALL = os.environ.get("GSM2COMPUTER_AUTO_MODE", "openclaw")
OPENCLAW_TALK_ON_CALL = os.environ.get("GSM2COMPUTER_OPENCLAW_TALK", "1").lower() not in (
    "0",
    "false",
    "no",
    "off",
)
RECORD_CHUNK = int(os.environ.get("GSM2COMPUTER_RECORD_CHUNK", "160"))
ULAW_BIAS = 0x84

LOG = logging.getLogger("gsm2computer-hub")
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

current_mode: Optional[str] = None
active_bridge: Optional["PipewireBridge"] = None
active_openclaw: Optional["OpenClawTalkBridge"] = None

try:
    from openclaw_talk_bridge import OpenClawTalkBridge
except ImportError:
    OpenClawTalkBridge = None  # type: ignore[misc, assignment]


def json_response(status: int, body: dict, extra_headers: Optional[dict] = None) -> bytes:
    payload = json.dumps(body).encode("utf-8")
    status_text = {
        200: "OK",
        400: "Bad Request",
        404: "Not Found",
        409: "Conflict",
        500: "Internal Server Error",
    }.get(status, "")
    lines = [
        f"HTTP/1.1 {status} {status_text}".rstrip(),
        "Content-Type: application/json",
        f"Content-Length: {len(payload)}",
        "Connection: close",
    ]
    if extra_headers:
        for k, v in extra_headers.items():
            lines.append(f"{k}: {v}")
    lines.extend(["", ""])
    return "\r\n".join(lines).encode("ascii") + payload


async def read_http_request(reader: asyncio.StreamReader) -> Tuple[str, str, dict, bytes]:
    header_lines: list[bytes] = []
    while True:
        line = await reader.readline()
        if not line:
            raise ConnectionError("client closed")
        if line in (b"\r\n", b"\n"):
            break
        header_lines.append(line.rstrip(b"\r\n"))

    if not header_lines:
        raise ValueError("empty request")

    request_line = header_lines[0].decode("iso-8859-1")
    parts = request_line.split()
    if len(parts) < 2:
        raise ValueError(f"bad request line: {request_line!r}")
    method, path = parts[0], parts[1]

    headers: dict[str, str] = {}
    for raw in header_lines[1:]:
        if b":" not in raw:
            continue
        name, value = raw.split(b":", 1)
        headers[name.decode("iso-8859-1").strip().lower()] = value.decode("iso-8859-1").strip()

    body = b""
    if method in ("POST", "PUT", "PATCH"):
        length = int(headers.get("content-length", "0") or "0")
        if length:
            body = await reader.readexactly(length)

    return method.upper(), path, headers, body


def ws_accept_key(sec_key: str) -> str:
    digest = hashlib.sha1((sec_key + WS_GUID).encode("ascii")).digest()
    return base64.b64encode(digest).decode("ascii")


async def ws_send_text(writer: asyncio.StreamWriter, text: str) -> None:
    data = text.encode("utf-8")
    length = len(data)
    if length < 126:
        header = bytes([0x81, length])
    elif length < 65536:
        header = bytes([0x81, 126]) + length.to_bytes(2, "big")
    else:
        header = bytes([0x81, 127]) + length.to_bytes(8, "big")
    writer.write(header + data)
    await writer.drain()


async def ws_send_pong(writer: asyncio.StreamWriter, payload: bytes = b"") -> None:
    length = len(payload)
    header = bytes([0x8A, length])
    writer.write(header + payload)
    await writer.drain()


async def ws_read_frame(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> Optional[str]:
    try:
        hdr = await reader.readexactly(2)
    except asyncio.IncompleteReadError:
        return None
    opcode = hdr[0] & 0x0F
    masked = bool(hdr[1] & 0x80)
    length = hdr[1] & 0x7F
    if length == 126:
        length = int.from_bytes(await reader.readexactly(2), "big")
    elif length == 127:
        length = int.from_bytes(await reader.readexactly(8), "big")

    mask = await reader.readexactly(4) if masked else None
    payload = await reader.readexactly(length)
    if masked and mask:
        payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))

    if opcode == 0x8:
        return None
    if opcode == 0x9:
        await ws_send_pong(writer, payload)
        return ""
    if opcode == 0x1:
        return payload.decode("utf-8", errors="replace")
    return ""


async def run_switchboard(*args: str) -> tuple[int, str, str]:
    if not SWITCHBOARD.is_file():
        return 1, "", f"missing switchboard script: {SWITCHBOARD}"
    proc = await asyncio.create_subprocess_exec(
        str(SWITCHBOARD),
        *args,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
        cwd=str(HUB_DIR),
    )
    stdout_b, stderr_b = await proc.communicate()
    return proc.returncode or 0, stdout_b.decode("utf-8", errors="replace"), stderr_b.decode("utf-8", errors="replace")


async def resolve_pipewire_record_target(name: str) -> str:
    """pw-record --target wants a PipeWire object.serial.

    Pulse names like openclaw_bus.monitor are not nodes, so --target falls
    through to the default source (AWS-Virtual-Microphone on safwat-eu).
    pactl source/sink indices match object.serial for these null sinks.
    """
    if name.isdigit():
        return name

    async def _pactl_short(kind: str) -> str:
        proc = await asyncio.create_subprocess_exec(
            "pactl",
            "list",
            kind,
            "short",
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        stdout_b, stderr_b = await proc.communicate()
        if proc.returncode:
            err = stderr_b.decode("utf-8", errors="replace").strip()
            raise RuntimeError(f"pactl list {kind} failed: {err or proc.returncode}")
        return stdout_b.decode("utf-8", errors="replace")

    for line in (await _pactl_short("sources")).splitlines():
        parts = line.split()
        if len(parts) >= 2 and parts[1] == name:
            return parts[0]
    sink = name[: -len(".monitor")] if name.endswith(".monitor") else name
    for line in (await _pactl_short("sinks")).splitlines():
        parts = line.split()
        if len(parts) >= 2 and parts[1] == sink:
            return parts[0]
    raise RuntimeError(f"PipeWire record target not found: {name}")


async def parse_switchboard_links() -> list[dict[str, str]]:
    proc = await asyncio.create_subprocess_exec(
        "pw-link",
        "-l",
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.DEVNULL,
    )
    stdout_b, _ = await proc.communicate()
    links: list[dict[str, str]] = []
    bus_re = re.compile(r"(gsm_bus|openclaw_bus|whatsapp_bus|telegram_bus)")
    current_src: Optional[str] = None
    for raw in stdout_b.decode("utf-8", errors="replace").splitlines():
        line = raw.strip()
        if not line:
            continue
        if "|->" in line:
            if not bus_re.search(line):
                continue
            _, dst = [part.strip() for part in line.split("|->", 1)]
            if current_src:
                links.append({"from": current_src, "to": dst})
            continue
        if bus_re.search(line):
            current_src = line
    return links


async def switchboard_state() -> dict:
    rc, status_out, status_err = await run_switchboard("status")
    links = await parse_switchboard_links()
    return {
        "ok": rc == 0,
        "mode": current_mode,
        "links": links,
        "status_text": status_out.strip(),
        "stderr": status_err.strip() or None,
    }


async def set_switchboard_mode(mode: str) -> dict:
    global current_mode
    allowed = {"clear", "openclaw", "loopback", "conference", "status"}
    if mode not in allowed:
        return {"ok": False, "error": f"unknown mode {mode!r}", "allowed": sorted(allowed - {"status"})}
    rc, out, err = await run_switchboard(mode)
    if rc != 0:
        return {"ok": False, "error": err or out or f"switchboard {mode} failed"}
    if mode != "status":
        current_mode = None if mode == "clear" else mode
    state = await switchboard_state()
    state["applied"] = mode
    state["message"] = out.strip()
    return state


def _ulaw_decode_sample(pcmu: int) -> int:
    b = (pcmu & 0xff) ^ 0xff
    sign = b & 0x80
    exponent = (b >> 4) & 0x07
    mantissa = b & 0x0f
    sample = ((mantissa << 3) + ULAW_BIAS) << exponent
    sample -= ULAW_BIAS
    return -sample if sign else sample


def _ulaw_encode_sample(pcm: int) -> int:
    sample = pcm
    sign = (sample >> 8) & 0x80
    if sign:
        sample = -sample
    if sample > 32635:
        sample = 32635
    sample += ULAW_BIAS
    if sample > 32635:
        sample = 32635
    exponent = 0
    while exponent < 7 and (sample >> (exponent + 8)) != 0:
        exponent += 1
    mantissa = (sample >> (exponent + 3)) & 0x0f
    raw = (sign & 0x80) | (exponent << 4) | mantissa
    return raw ^ 0xff


def _mulaw_rms_energy(ulaw: bytes) -> float:
    """RMS of decoded PCM / 32768 (~0–1; matches simulator mulawEnergy)."""
    if not ulaw:
        return 0.0
    sum_sq = 0.0
    for b in ulaw:
        s = _ulaw_decode_sample(b) / 32768.0
        sum_sq += s * s
    return (sum_sq / len(ulaw)) ** 0.5


def parse_sms_command(body: str) -> Optional[str]:
    text = (body or "").strip()
    if not text:
        return None
    upper = text.upper()
    if upper == "STATUS":
        return "status"
    match = re.match(r"^MODE\s+(\S+)", upper)
    if match:
        return match.group(1).lower()
    return None


class PipewireBridge:
    def __init__(self, sink: str, monitor: str, rate: int) -> None:
        self.sink = sink
        self.monitor = monitor
        self.rate = rate
        self._playback: Optional[asyncio.subprocess.Process] = None
        self._record: Optional[asyncio.subprocess.Process] = None
        self._record_task: Optional[asyncio.Task] = None
        self._closed = False

    async def start(
        self,
        ws_writer: asyncio.StreamWriter,
        monitor: Optional[str] = None,
        record_downlink: bool = True,
    ) -> None:
        record_target = monitor or self.monitor
        record_serial: Optional[str] = None
        if record_downlink:
            record_serial = await resolve_pipewire_record_target(record_target)
            LOG.info("pw-record target %s -> serial %s", record_target, record_serial)
        self._playback = await asyncio.create_subprocess_exec(
            "pw-cat",
            "--playback",
            "--target",
            self.sink,
            "--rate",
            str(self.rate),
            "--channels",
            "1",
            "--format",
            "ulaw",
            "-",
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.PIPE,
        )
        if record_serial is not None:
            self._record = await asyncio.create_subprocess_exec(
                "pw-record",
                "--media-category",
                "Capture",
                "--target",
                record_serial,
                "--rate",
                str(self.rate),
                "--channels",
                "2",
                "--format",
                "s16",
                "-",
                stdin=asyncio.subprocess.DEVNULL,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
            await asyncio.sleep(0.05)
            if self._record.returncode is not None:
                err = await self._record.stderr.read()
                LOG.error(
                    "pw-record 2ch s16 failed: %s",
                    err.decode("utf-8", errors="replace").strip() or self._record.returncode,
                )
            else:
                self._record_task = asyncio.create_task(self._pump_out(ws_writer))
        LOG.info(
            "pipewire bridge started sink=%s monitor=%s record=%s",
            self.sink,
            record_target if record_downlink else "off",
            record_downlink,
        )

    async def _pump_out(self, ws_writer: asyncio.StreamWriter) -> None:
        assert self._record and self._record.stdout
        # 20 ms of stereo s16le at AUDIO_RATE.
        read_size = RECORD_CHUNK * 2 * 2
        nonzero = 0
        while not self._closed:
            try:
                chunk = await self._record.stdout.read(read_size)
            except Exception as exc:
                LOG.warning("record read failed: %s", exc)
                break
            if not chunk:
                break
            extra = len(chunk) % 4
            if extra:
                chunk = chunk[: len(chunk) - extra]
            if not chunk:
                continue
            left = audioop.tomono(chunk, 2, 1, 0)
            right = audioop.tomono(chunk, 2, 0, 1)
            mono_pcm = audioop.tomono(chunk, 2, 0.5, 0.5)
            energy_l = audioop.rms(left, 2) / 32768.0
            energy_r = audioop.rms(right, 2) / 32768.0
            if energy_l > 0.02 or energy_r > 0.02:
                nonzero += 1
                if nonzero == 1 or nonzero % 50 == 0:
                    LOG.info("downlink energy l=%.3f r=%.3f", energy_l, energy_r)
            event = {
                "type": "response.output_audio.delta",
                "delta": base64.b64encode(audioop.lin2ulaw(mono_pcm, 2)).decode("ascii"),
                "channels": {"l": energy_l, "r": energy_r},
            }
            try:
                await ws_send_text(ws_writer, json.dumps(event))
            except (ConnectionError, BrokenPipeError, asyncio.IncompleteReadError):
                break
        if self._record.returncode is None:
            await self._record.wait()
        if self._record.stderr:
            err = await self._record.stderr.read()
            if err:
                LOG.error("pw-record stderr: %s", err.decode("utf-8", errors="replace").strip())

    async def write_in(self, ulaw: bytes) -> None:
        if self._closed or not ulaw:
            return
        proc = self._playback
        if not proc or not proc.stdin:
            return
        proc.stdin.write(ulaw)
        await proc.stdin.drain()

    async def stop(self) -> None:
        if self._closed:
            return
        self._closed = True
        if self._record_task:
            self._record_task.cancel()
            try:
                await self._record_task
            except asyncio.CancelledError:
                pass
        for proc in (self._playback, self._record):
            if not proc:
                continue
            if proc.stdin:
                proc.stdin.close()
            if proc.returncode is None:
                proc.terminate()
                try:
                    await asyncio.wait_for(proc.wait(), timeout=2)
                except asyncio.TimeoutError:
                    proc.kill()
                    await proc.wait()
            if proc.stderr:
                err = await proc.stderr.read()
                if err:
                    LOG.debug("pipewire stderr: %s", err.decode("utf-8", errors="replace").strip())


async def handle_websocket(
    reader: asyncio.StreamReader,
    writer: asyncio.StreamWriter,
    headers: dict,
    path: str,
) -> None:
    global active_bridge, active_openclaw

    key = headers.get("sec-websocket-key")
    if not key:
        writer.write(b"HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
        await writer.drain()
        return

    if active_bridge is not None:
        writer.write(b"HTTP/1.1 409 Conflict\r\nConnection: close\r\n\r\n")
        await writer.drain()
        return

    accept = ws_accept_key(key)
    response = (
        "HTTP/1.1 101 Switching Protocols\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Accept: {accept}\r\n"
        "\r\n"
    )
    writer.write(response.encode("ascii"))
    await writer.drain()

    loopback = path.strip("/") == "loopback" or path.startswith("/loopback")
    LOG.info("WebSocket connected path=%s loopback=%s", path, loopback)
    if loopback:
        result = await set_switchboard_mode("loopback")
        LOG.info("loopback call: %s", result.get("applied") or result.get("error"))
    elif AUTO_MODE_ON_CALL:
        result = await set_switchboard_mode(AUTO_MODE_ON_CALL)
        LOG.info("auto mode on call: %s", result.get("applied") or result.get("error"))

    bridge = PipewireBridge(GSM_SINK, GSM_MONITOR, AUDIO_RATE)
    active_bridge = bridge
    openclaw_bridge: Optional[OpenClawTalkBridge] = None
    if loopback:
        downlink_monitor = GSM_MONITOR
    elif OPENCLAW_TALK_ON_CALL:
        downlink_monitor = OPENCLAW_DOWNLINK_MONITOR
    else:
        downlink_monitor = GSM_MONITOR
    if not loopback and OPENCLAW_TALK_ON_CALL and OpenClawTalkBridge is not None:
        try:
            openclaw_bridge = OpenClawTalkBridge(mic_source="hub")
            await openclaw_bridge.start()
            active_openclaw = openclaw_bridge
            LOG.info("openclaw talk bridge started for call")
        except Exception as exc:
            LOG.error("openclaw talk bridge failed to start: %s", exc)
    try:
        await bridge.start(writer, monitor=downlink_monitor, record_downlink=not loopback)
        session_ack = {
            "type": "session.updated",
            "session": {
                "id": f"hub-{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')}",
                "model": "gsm2computer-hub",
                "audio": {"format": "audio/pcmu", "rate": AUDIO_RATE},
            },
        }
        await ws_send_text(writer, json.dumps(session_ack))

        while True:
            msg = await ws_read_frame(reader, writer)
            if msg is None:
                break
            if not msg:
                continue
            try:
                event = json.loads(msg)
            except json.JSONDecodeError:
                LOG.debug("non-json ws frame: %r", msg[:200])
                continue

            etype = event.get("type")
            if etype == "input_audio_buffer.append":
                audio_b64 = event.get("audio") or event.get("data") or ""
                if audio_b64:
                    ulaw = base64.b64decode(audio_b64, validate=False)
                    await bridge.write_in(ulaw)
                    if loopback:
                        energy = _mulaw_rms_energy(ulaw)
                        echo = {
                            "type": "response.output_audio.delta",
                            "delta": base64.b64encode(ulaw).decode("ascii"),
                            "channels": {"l": energy, "r": energy},
                        }
                        await ws_send_text(writer, json.dumps(echo))
                    elif openclaw_bridge is not None:
                        openclaw_bridge.feed_gsm_ulaw(ulaw)
            else:
                LOG.debug("ws event type=%s", etype)
    finally:
        await bridge.stop()
        active_bridge = None
        if openclaw_bridge is not None:
            await openclaw_bridge.stop()
            active_openclaw = None
            LOG.info("openclaw talk bridge stopped")
        LOG.info("WebSocket disconnected")


async def handle_client(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    peer = writer.get_extra_info("peername")
    try:
        method, path, headers, body = await read_http_request(reader)
    except (ConnectionError, ValueError, asyncio.IncompleteReadError) as exc:
        LOG.debug("bad request from %s: %s", peer, exc)
        writer.close()
        await writer.wait_closed()
        return

    LOG.debug("%s %s from %s", method, path, peer)

    if headers.get("upgrade", "").lower() == "websocket" or "sec-websocket-key" in headers:
        await handle_websocket(reader, writer, headers, path)
        writer.close()
        await writer.wait_closed()
        return

    try:
        if method == "GET" and path == "/health":
            writer.write(json_response(200, {"ok": True, "mode": current_mode}))
        elif method == "POST" and path == "/token":
            expires = (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat().replace("+00:00", "Z")
            writer.write(
                json_response(
                    200,
                    {"value": "hub-token", "model": "hub", "expires_at": expires},
                )
            )
        elif method == "GET" and path == "/switchboard/state":
            writer.write(json_response(200, await switchboard_state()))
        elif method == "POST" and path == "/switchboard/mode":
            try:
                payload = json.loads(body.decode("utf-8") if body else "{}")
            except json.JSONDecodeError:
                writer.write(json_response(400, {"ok": False, "error": "invalid json"}))
                await writer.drain()
                writer.close()
                await writer.wait_closed()
                return
            mode = str(payload.get("mode", "")).strip().lower()
            if not mode:
                writer.write(json_response(400, {"ok": False, "error": "mode required"}))
            else:
                writer.write(json_response(200, await set_switchboard_mode(mode)))
        elif method == "POST" and path == "/sms":
            try:
                payload = json.loads(body.decode("utf-8") if body else "{}")
            except json.JSONDecodeError:
                payload = {"raw": body.decode("utf-8", errors="replace")}
            log_entry = {
                "from": payload.get("from"),
                "body": payload.get("body"),
                "receivedAt": payload.get("receivedAt")
                or datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            }
            LOG.info("sms %s", json.dumps(log_entry, ensure_ascii=False))
            command = parse_sms_command(str(payload.get("body") or ""))
            if command:
                result = await set_switchboard_mode(command)
                writer.write(json_response(200, {"ok": True, "sms": log_entry, "routing": result}))
            else:
                writer.write(json_response(200, {"ok": True, "sms": log_entry}))
        else:
            writer.write(json_response(404, {"ok": False, "error": "not found"}))
    except Exception:
        LOG.exception("handler error for %s %s", method, path)
        writer.write(json_response(500, {"ok": False, "error": "internal error"}))

    await writer.drain()
    writer.close()
    await writer.wait_closed()


async def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, lambda: asyncio.create_task(shutdown()))

    server = await asyncio.start_server(handle_client, HOST, PORT)
    addrs = ", ".join(str(sock.getsockname()) for sock in server.sockets or [])
    LOG.info(
        "listening on %s (sink=%s auto_mode=%s openclaw_talk=%s)",
        addrs,
        GSM_SINK,
        AUTO_MODE_ON_CALL or "off",
        "on" if OPENCLAW_TALK_ON_CALL else "off",
    )
    async with server:
        await server.serve_forever()


_server: Optional[asyncio.AbstractServer] = None


async def shutdown() -> None:
    global active_bridge, active_openclaw
    LOG.info("shutting down")
    if active_openclaw:
        await active_openclaw.stop()
        active_openclaw = None
    if active_bridge:
        await active_bridge.stop()
        active_bridge = None
    raise SystemExit(0)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except (KeyboardInterrupt, SystemExit):
        pass
