#!/usr/bin/env python3
"""OpenClaw gateway-relay talk session ↔ PipeWire openclaw_bus bridge."""
from __future__ import annotations

import argparse
import asyncio
import audioop
import base64
import json
import logging
import os
import uuid
from pathlib import Path
from typing import Any, Optional

LOG = logging.getLogger("openclaw-talk-bridge")

OPENCLAW_GATEWAY_URL = os.environ.get("OPENCLAW_GATEWAY_URL", "ws://127.0.0.1:18789")
OPENCLAW_CONFIG_PATH = Path(
    os.environ.get("OPENCLAW_CONFIG_PATH", Path.home() / ".openclaw" / "openclaw.json")
)
OPENCLAW_BUS = os.environ.get("GSM2COMPUTER_OPENCLAW_BUS", "openclaw_bus")
OPENCLAW_MONITOR = os.environ.get("GSM2COMPUTER_OPENCLAW_MONITOR", "gsm_bus.monitor")
OPENCLAW_SESSION_KEY = os.environ.get("GSM2COMPUTER_OPENCLAW_SESSION_KEY", "main")
OPENCLAW_BRAIN = os.environ.get("GSM2COMPUTER_OPENCLAW_BRAIN", "agent-consult")
OPENCLAW_TALK_MODEL = os.environ.get("GSM2COMPUTER_OPENCLAW_MODEL", "gpt-realtime-2.1-mini")
OPENCLAW_SAMPLE_RATE = int(os.environ.get("GSM2COMPUTER_OPENCLAW_SAMPLE_RATE", "24000"))
GSM_SAMPLE_RATE = int(os.environ.get("GSM2COMPUTER_AUDIO_RATE", "8000"))
# 20 ms PCM16 mono frames at 24 kHz.
AUDIO_CHUNK_BYTES = int(os.environ.get("GSM2COMPUTER_OPENCLAW_CHUNK_BYTES", str(OPENCLAW_SAMPLE_RATE * 2 // 50)))
# Match OpenClaw Control UI: at most 4 unanswered appendAudio RPCs, then drop.
APPEND_QUEUE_MAX = int(os.environ.get("GSM2COMPUTER_OPENCLAW_APPEND_QUEUE", "4"))


def load_gateway_token() -> str:
    token = os.environ.get("OPENCLAW_GATEWAY_TOKEN", "").strip()
    if token:
        return token
    if not OPENCLAW_CONFIG_PATH.is_file():
        raise FileNotFoundError(f"missing OpenClaw config: {OPENCLAW_CONFIG_PATH}")
    cfg = json.loads(OPENCLAW_CONFIG_PATH.read_text(encoding="utf-8"))
    gateway = cfg.get("gateway") or {}
    auth = gateway.get("auth") or {}
    token = auth.get("token") or gateway.get("token")
    if not token:
        raise ValueError(f"no gateway token in {OPENCLAW_CONFIG_PATH}")
    return str(token)


class OpenClawGatewayClient:
    """Minimal OpenClaw gateway WebSocket client for talk.session.* RPCs."""

    def __init__(self, url: str, token: str) -> None:
        self.url = url
        self.token = token
        self._ws: Any = None
        self._recv_task: Optional[asyncio.Task] = None
        self._pending: dict[str, asyncio.Future] = {}
        self._event_handlers: dict[str, list] = {"talk.event": []}
        self._closed = False

    async def connect(self) -> None:
        try:
            import websockets
        except ImportError as exc:
            raise RuntimeError("websockets package required: pip install websockets") from exc

        self._ws = await websockets.connect(self.url, max_size=32 * 1024 * 1024)
        challenge = json.loads(await self._ws.recv())
        if challenge.get("event") != "connect.challenge":
            raise RuntimeError(f"unexpected pre-connect frame: {challenge!r}")

        req_id = str(uuid.uuid4())
        connect = {
            "type": "req",
            "id": req_id,
            "method": "connect",
            "params": {
                "minProtocol": 4,
                "maxProtocol": 4,
                "client": {
                    "id": "cli",
                    "version": "gsm2computer",
                    "platform": "linux",
                    "mode": "cli",
                },
                "role": "operator",
                "scopes": ["operator.read", "operator.talk"],
                "caps": [],
                "commands": [],
                "permissions": {},
                "auth": {"token": self.token},
                "locale": "en-US",
                "userAgent": "gsm2computer-openclaw-bridge/0.1",
            },
        }
        await self._ws.send(json.dumps(connect))
        hello = await self._read_response(req_id)
        if not hello.get("ok"):
            raise RuntimeError(f"gateway connect failed: {hello.get('error')}")
        scopes = (hello.get("payload") or {}).get("auth", {}).get("scopes") or []
        if "operator.talk" not in scopes:
            raise RuntimeError(f"missing operator.talk scope (got {scopes!r}); use loopback cli client")
        self._recv_task = asyncio.create_task(self._recv_loop())
        LOG.info("connected to OpenClaw gateway %s scopes=%s", self.url, scopes)

    def on_event(self, event_name: str, handler) -> None:
        self._event_handlers.setdefault(event_name, []).append(handler)

    async def request(self, method: str, params: dict) -> dict:
        if not self._ws:
            raise RuntimeError("not connected")
        req_id = str(uuid.uuid4())
        fut: asyncio.Future = asyncio.get_running_loop().create_future()
        self._pending[req_id] = fut
        await self._ws.send(
            json.dumps({"type": "req", "id": req_id, "method": method, "params": params})
        )
        msg = await fut
        if not msg.get("ok"):
            raise RuntimeError(f"{method} failed: {msg.get('error')}")
        return msg.get("payload") or {}

    async def _read_response(self, req_id: str) -> dict:
        while True:
            raw = await self._ws.recv()
            msg = json.loads(raw)
            if msg.get("type") == "res" and msg.get("id") == req_id:
                return msg

    async def _recv_loop(self) -> None:
        assert self._ws
        try:
            while not self._closed:
                raw = await self._ws.recv()
                msg = json.loads(raw)
                if msg.get("type") == "res":
                    req_id = msg.get("id")
                    fut = self._pending.pop(req_id, None)
                    if fut and not fut.done():
                        fut.set_result(msg)
                    continue
                if msg.get("type") == "event":
                    event = msg.get("event")
                    for handler in self._event_handlers.get(event, []):
                        try:
                            result = handler(msg.get("payload") or {})
                            if asyncio.iscoroutine(result):
                                await result
                        except Exception:
                            LOG.exception("event handler failed event=%s", event)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            if not self._closed:
                LOG.warning("gateway recv loop ended: %s", exc)
        finally:
            for fut in self._pending.values():
                if not fut.done():
                    fut.set_exception(ConnectionError("gateway connection closed"))
            self._pending.clear()

    async def close(self) -> None:
        self._closed = True
        if self._recv_task:
            self._recv_task.cancel()
            try:
                await self._recv_task
            except asyncio.CancelledError:
                pass
        if self._ws:
            await self._ws.close()
            self._ws = None


class OpenClawTalkBridge:
    """PipeWire openclaw_bus ↔ OpenClaw talk.session gateway-relay."""

    def __init__(
        self,
        gateway_url: str = OPENCLAW_GATEWAY_URL,
        gateway_token: Optional[str] = None,
        bus: str = OPENCLAW_BUS,
        monitor: str = OPENCLAW_MONITOR,
        session_key: str = OPENCLAW_SESSION_KEY,
        brain: str = OPENCLAW_BRAIN,
        model: str = OPENCLAW_TALK_MODEL,
        sample_rate: int = OPENCLAW_SAMPLE_RATE,
        mic_source: str = "pipewire",
        gsm_rate: int = GSM_SAMPLE_RATE,
    ) -> None:
        self.gateway_url = gateway_url
        self.gateway_token = gateway_token or load_gateway_token()
        self.bus = bus
        self.monitor = monitor
        self.session_key = session_key
        self.brain = brain
        self.model = model
        self.sample_rate = sample_rate
        self.mic_source = mic_source
        self.gsm_rate = gsm_rate
        self._client: Optional[OpenClawGatewayClient] = None
        self._session_id: Optional[str] = None
        self._playback: Optional[asyncio.subprocess.Process] = None
        self._record: Optional[asyncio.subprocess.Process] = None
        self._record_task: Optional[asyncio.Task] = None
        self._playback_queue: Optional[asyncio.Queue[Optional[bytes]]] = None
        self._playback_task: Optional[asyncio.Task] = None
        self._append_queue: asyncio.Queue[Optional[bytes]] = asyncio.Queue(maxsize=APPEND_QUEUE_MAX)
        self._append_task: Optional[asyncio.Task] = None
        self._append_dropped = 0
        self._mic_buffer = bytearray()
        self._mic_resample_state: Optional[tuple] = None
        self._closed = False
        self._audio_frames = 0

    async def start(self) -> None:
        if self._client:
            return
        self._closed = False
        self._client = OpenClawGatewayClient(self.gateway_url, self.gateway_token)
        await self._client.connect()
        payload = await self._client.request(
            "talk.session.create",
            {
                "mode": "realtime",
                "transport": "gateway-relay",
                "brain": self.brain,
                "sessionKey": self.session_key,
                "model": self.model,
            },
        )
        self._session_id = payload.get("sessionId")
        audio = payload.get("audio") or {}
        LOG.info(
            "talk session started sessionId=%s model=%s in=%sHz out=%sHz",
            self._session_id,
            payload.get("model") or self.model,
            audio.get("inputSampleRateHz"),
            audio.get("outputSampleRateHz"),
        )

        self._client.on_event("talk.event", self._on_talk_event)
        self._append_queue = asyncio.Queue(maxsize=APPEND_QUEUE_MAX)
        self._append_dropped = 0
        self._append_task = asyncio.create_task(self._append_worker())
        await self._start_pipewire()
        if self.mic_source == "pipewire":
            self._record_task = asyncio.create_task(self._pump_mic_pipewire())

    def feed_gsm_ulaw(self, ulaw: bytes) -> None:
        """Mix-minus uplink: phone audio from the GSM WebSocket, not the patched bus."""
        if self._closed or self.mic_source != "hub" or not ulaw:
            return
        pcm8k = audioop.ulaw2lin(ulaw, 2)
        pcm24k, self._mic_resample_state = audioop.ratecv(
            pcm8k, 2, 1, self.gsm_rate, self.sample_rate, self._mic_resample_state
        )
        self._mic_buffer.extend(pcm24k)
        while len(self._mic_buffer) >= AUDIO_CHUNK_BYTES:
            chunk = bytes(self._mic_buffer[:AUDIO_CHUNK_BYTES])
            del self._mic_buffer[:AUDIO_CHUNK_BYTES]
            self._enqueue_append(chunk)

    def _enqueue_append(self, chunk: bytes) -> None:
        if self._closed:
            return
        q = self._append_queue
        if q.full():
            try:
                q.get_nowait()
            except asyncio.QueueEmpty:
                pass
            self._append_dropped += 1
            if self._append_dropped == 1 or self._append_dropped % 50 == 0:
                LOG.warning("appendAudio queue full; dropped %s oldest frames", self._append_dropped)
        try:
            q.put_nowait(chunk)
        except asyncio.QueueFull:
            pass

    async def _append_worker(self) -> None:
        while not self._closed:
            chunk = await self._append_queue.get()
            if chunk is None:
                return
            await self._append_audio(chunk)

    async def _append_audio(self, chunk: bytes) -> None:
        if self._closed or not self._client or not self._session_id:
            return
        try:
            await self._client.request(
                "talk.session.appendAudio",
                {
                    "sessionId": self._session_id,
                    "audioBase64": base64.b64encode(chunk).decode("ascii"),
                },
            )
        except Exception as exc:
            LOG.warning("appendAudio failed: %s", exc)

    async def _start_pipewire(self) -> None:
        self._playback_queue = asyncio.Queue()
        self._playback = await asyncio.create_subprocess_exec(
            "pw-cat",
            "--playback",
            "--target",
            self.bus,
            "--rate",
            str(self.sample_rate),
            "--channels",
            "1",
            "--format",
            "s16",
            "-",
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.DEVNULL,
        )
        self._playback_task = asyncio.create_task(self._drain_playback())
        if self.mic_source == "pipewire":
            self._record = await asyncio.create_subprocess_exec(
                "pw-record",
                "--target",
                self.monitor,
                "--rate",
                str(self.sample_rate),
                "--channels",
                "1",
                "--format",
                "s16",
                "-",
                stdin=asyncio.subprocess.DEVNULL,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.DEVNULL,
            )
        LOG.info(
            "pipewire playback bus=%s mic_source=%s monitor=%s",
            self.bus,
            self.mic_source,
            self.monitor if self.mic_source == "pipewire" else "hub-ulaw",
        )

    async def _drain_playback(self) -> None:
        assert self._playback and self._playback.stdin and self._playback_queue
        while not self._closed:
            chunk = await self._playback_queue.get()
            if chunk is None:
                break
            try:
                self._playback.stdin.write(chunk)
                await self._playback.stdin.drain()
            except (BrokenPipeError, ConnectionResetError):
                LOG.warning("playback pipe closed")
                break

    async def _restart_playback(self) -> None:
        if self._playback_task:
            self._playback_task.cancel()
            try:
                await self._playback_task
            except asyncio.CancelledError:
                pass
        if self._playback and self._playback.returncode is None:
            self._playback.terminate()
            try:
                await asyncio.wait_for(self._playback.wait(), timeout=1)
            except asyncio.TimeoutError:
                self._playback.kill()
                await self._playback.wait()
        self._playback = None
        self._playback_queue = asyncio.Queue()
        self._playback = await asyncio.create_subprocess_exec(
            "pw-cat",
            "--playback",
            "--target",
            self.bus,
            "--rate",
            str(self.sample_rate),
            "--channels",
            "1",
            "--format",
            "s16",
            "-",
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.DEVNULL,
        )
        self._playback_task = asyncio.create_task(self._drain_playback())

    async def _pump_mic_pipewire(self) -> None:
        assert self._record and self._record.stdout
        while not self._closed:
            try:
                chunk = await self._record.stdout.readexactly(AUDIO_CHUNK_BYTES)
            except asyncio.IncompleteReadError:
                break
            except Exception as exc:
                LOG.warning("mic read failed: %s", exc)
                break
            if not chunk:
                break
            await self._append_audio(chunk)

    async def _on_talk_event(self, payload: dict) -> None:
        if self._closed or not self._playback_queue:
            return
        # Gateway wraps relay frames: { voiceSessionId, talkEvent: { type, audioBase64 } }
        event = payload.get("talkEvent") if isinstance(payload.get("talkEvent"), dict) else payload
        etype = event.get("type")
        if etype == "clear":
            LOG.info("talk.event clear — restarting playback")
            await self._restart_playback()
            return
        audio_b64 = event.get("audioBase64") or payload.get("audioBase64")
        if not audio_b64:
            return
        try:
            pcm = base64.b64decode(audio_b64, validate=False)
        except Exception:
            return
        if pcm:
            self._audio_frames += 1
            if self._audio_frames == 1 or self._audio_frames % 50 == 0:
                LOG.info("talk.event audio frames=%s bytes=%s", self._audio_frames, len(pcm))
            self._playback_queue.put_nowait(pcm)

    async def stop(self) -> None:
        if self._closed and not self._client:
            return
        self._closed = True
        session_id = self._session_id
        client = self._client
        self._session_id = None
        self._client = None

        if self._record_task:
            self._record_task.cancel()
            try:
                await self._record_task
            except asyncio.CancelledError:
                pass
            self._record_task = None
        try:
            self._append_queue.put_nowait(None)
        except asyncio.QueueFull:
            pass
        if self._append_task:
            self._append_task.cancel()
            try:
                await self._append_task
            except asyncio.CancelledError:
                pass
            self._append_task = None
        if self._playback_queue:
            self._playback_queue.put_nowait(None)
        if self._playback_task:
            self._playback_task.cancel()
            try:
                await self._playback_task
            except asyncio.CancelledError:
                pass
            self._playback_task = None

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
        self._playback = None
        self._record = None

        if client and session_id:
            try:
                await client.request("talk.session.close", {"sessionId": session_id})
            except Exception as exc:
                LOG.debug("talk.session.close: %s", exc)
        if client:
            await client.close()
        LOG.info("openclaw talk bridge stopped")


async def run_standalone() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    bridge = OpenClawTalkBridge()
    await bridge.start()
    LOG.info("running; Ctrl+C to stop")
    try:
        while True:
            await asyncio.sleep(3600)
    finally:
        await bridge.stop()


def main() -> None:
    parser = argparse.ArgumentParser(description="OpenClaw talk ↔ openclaw_bus bridge")
    parser.parse_args()
    try:
        asyncio.run(run_standalone())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
