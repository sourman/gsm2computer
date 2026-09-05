#!/usr/bin/env python3
"""OpenClaw gateway-relay talk session ↔ PipeWire openclaw_bus bridge.

Legacy path. Default hub Talk is Control UI WebRTC (`talk_chromium.py`,
GSM2COMPUTER_OPENCLAW_TALK=webrtc-ui). Set that env to `relay` to use this
module. The hub must not fall back here automatically.
"""
from __future__ import annotations

import argparse
import asyncio
import audioop
import base64
import json
import logging
import os
import time
import uuid
from pathlib import Path
from typing import Any, Optional

from pipewire_target import (
    PipewireLinkError,
    pipewire_stream_env,
    pw_cat_raw_args,
    pw_latency_args,
    stdbuf_unbuffered,
    resolve_pipewire_playback_target,
    resolve_pipewire_record_target,
    wait_for_pipewire_link,
)

LOG = logging.getLogger("openclaw-talk-bridge")

OPENCLAW_GATEWAY_URL = os.environ.get("OPENCLAW_GATEWAY_URL", "ws://127.0.0.1:18789")
OPENCLAW_CONFIG_PATH = Path(
    os.environ.get("OPENCLAW_CONFIG_PATH", Path.home() / ".openclaw" / "openclaw.json")
)
OPENCLAW_BUS = os.environ.get("GSM2COMPUTER_OPENCLAW_BUS", "openclaw_bus")
OPENCLAW_MONITOR = os.environ.get("GSM2COMPUTER_OPENCLAW_MONITOR", "gsm_bus.monitor")
OPENCLAW_SESSION_KEY = os.environ.get("GSM2COMPUTER_OPENCLAW_SESSION_KEY", "main")
OPENCLAW_BRAIN = os.environ.get("GSM2COMPUTER_OPENCLAW_BRAIN", "agent-consult")
OPENCLAW_TALK_PROVIDER = os.environ.get("GSM2COMPUTER_OPENCLAW_PROVIDER", "openai")
# Call path (simulator/GSM) uses the same GA realtime model as Control UI Talk.
# Needs a Platform API key on the gateway; GPT-Live is OAuth-only fallback.
OPENCLAW_TALK_MODEL = os.environ.get("GSM2COMPUTER_OPENCLAW_MODEL", "gpt-realtime-2.1-mini")
OPENCLAW_TALK_VOICE = os.environ.get("GSM2COMPUTER_OPENCLAW_VOICE", "").strip()
OPENCLAW_SAMPLE_RATE = int(os.environ.get("GSM2COMPUTER_OPENCLAW_SAMPLE_RATE", "24000"))
GSM_SAMPLE_RATE = int(os.environ.get("GSM2COMPUTER_AUDIO_RATE", "8000"))
# 20 ms PCM16 mono frames at 24 kHz.
AUDIO_CHUNK_BYTES = int(os.environ.get("GSM2COMPUTER_OPENCLAW_CHUNK_BYTES", str(OPENCLAW_SAMPLE_RATE * 2 // 50)))
# Match OpenClaw Control UI: at most 4 unanswered appendAudio RPCs, then drop.
APPEND_QUEUE_MAX = int(os.environ.get("GSM2COMPUTER_OPENCLAW_APPEND_QUEUE", "4"))
# Official Talk clients retry a dropped gateway-relay on ~0.5s then 2s.
RECONNECT_DELAYS_S = (0.5, 2.0, 2.0, 2.0)
SESSION_END_EVENT_TYPES = frozenset({"close", "error", "session.closed", "session.error"})
CLEAR_EVENT_TYPES = frozenset({"clear"})
# Prefer the requested model, then GA realtime, then OAuth GPT-Live.
FALLBACK_TALK_SESSIONS = (
    {"provider": "openai", "model": "gpt-realtime-2.1-mini", "voice": "marin"},
    {"provider": "openai", "model": "gpt-live-1-codex", "voice": "cove"},
)


def _default_voice(model: str) -> str:
    if OPENCLAW_TALK_VOICE:
        return OPENCLAW_TALK_VOICE
    if str(model).startswith("gpt-live"):
        return "cove"
    return "marin"


def _is_unconfigured_talk_error(reason: str) -> bool:
    return "is not configured" in reason.lower()


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
        provider: str = OPENCLAW_TALK_PROVIDER,
        model: str = OPENCLAW_TALK_MODEL,
        voice: Optional[str] = None,
        sample_rate: int = OPENCLAW_SAMPLE_RATE,
        mic_source: str = "pipewire",
        gsm_rate: int = GSM_SAMPLE_RATE,
        tap: Optional[Any] = None,
    ) -> None:
        self.gateway_url = gateway_url
        self.gateway_token = gateway_token or load_gateway_token()
        self.bus = bus
        self.monitor = monitor
        self.session_key = session_key
        self.brain = brain
        self.provider = provider
        self.model = model
        self.voice = voice if voice is not None else _default_voice(model)
        self.sample_rate = sample_rate
        self.mic_source = mic_source
        self.gsm_rate = gsm_rate
        self.tap = tap
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
        self._loud_audio_frames = 0
        self._first_audio_frame_at: Optional[float] = None
        self._first_loud_audio_frame_at: Optional[float] = None
        self._playback_linked = False
        self._record_linked = False
        self._helper_tasks: list[asyncio.Task] = []
        self._reconnect_task: Optional[asyncio.Task] = None
        self._logged_event_types: set[str] = set()

    @property
    def audio_frames(self) -> int:
        return self._audio_frames

    @property
    def first_audio_frame_at(self) -> Optional[float]:
        return self._first_audio_frame_at

    @property
    def loud_audio_frames(self) -> int:
        return self._loud_audio_frames

    @property
    def first_loud_audio_frame_at(self) -> Optional[float]:
        return self._first_loud_audio_frame_at

    @property
    def playback_linked(self) -> bool:
        return self._playback_linked

    def _session_create_params(
        self,
        provider: Optional[str] = None,
        model: Optional[str] = None,
        voice: Optional[str] = None,
    ) -> dict:
        params = {
            "mode": "realtime",
            "transport": "gateway-relay",
            "brain": self.brain,
            "sessionKey": self.session_key,
            "provider": provider or self.provider,
            "model": model or self.model,
        }
        chosen_voice = voice if voice is not None else self.voice
        if chosen_voice:
            params["voice"] = chosen_voice
        return params

    def _talk_session_attempts(self) -> list[dict]:
        attempts: list[dict] = []
        seen: set[tuple[str, str]] = set()

        def add(provider: str, model: str, voice: str) -> None:
            key = (provider, model)
            if key in seen:
                return
            seen.add(key)
            attempts.append({"provider": provider, "model": model, "voice": voice})

        add(self.provider, self.model, self.voice or _default_voice(self.model))
        for fallback in FALLBACK_TALK_SESSIONS:
            add(fallback["provider"], fallback["model"], fallback["voice"])
        return attempts

    async def _create_talk_session(self) -> dict:
        if not self._client:
            raise RuntimeError("not connected")
        attempts = self._talk_session_attempts()
        last_exc: Optional[Exception] = None
        for attempt in attempts:
            params = self._session_create_params(**attempt)
            try:
                payload = await self._client.request("talk.session.create", params)
            except Exception as exc:
                last_exc = exc
                if not _is_unconfigured_talk_error(str(exc)):
                    raise
                LOG.warning(
                    "talk.session.create not configured provider=%s model=%s: %s",
                    attempt["provider"],
                    attempt["model"],
                    exc,
                )
                continue
            self.provider = attempt["provider"]
            self.model = payload.get("model") or attempt["model"]
            self.voice = payload.get("voice") or attempt["voice"]
            self._session_id = payload.get("sessionId")
            audio = payload.get("audio") or {}
            LOG.info(
                "talk session started sessionId=%s provider=%s model=%s voice=%s in=%sHz out=%sHz",
                self._session_id,
                payload.get("provider") or self.provider,
                self.model,
                self.voice,
                audio.get("inputSampleRateHz"),
                audio.get("outputSampleRateHz"),
            )
            return payload
        raise last_exc or RuntimeError("talk.session.create failed: no configured realtime provider")

    async def start_audio(self) -> None:
        """Connect gateway, create Talk, then link PipeWire. Phone is not answered yet.

        GPT-Live attaches a response owner at talk.session.create. The working
        pre-SAF-28 path created Talk before pw-cat. We keep that order, but do
        not return until playback is linked so the hub can still withhold
        session.updated.
        """
        if self._client:
            return
        self._closed = False
        self._client = OpenClawGatewayClient(self.gateway_url, self.gateway_token)
        await self._client.connect()
        # Queue + handler before create so greeting PCM is not dropped while
        # we wait for the player link (old code registered the handler after).
        self._playback_queue = asyncio.Queue()
        self._client.on_event("talk.event", self._on_talk_event)
        self._append_queue = asyncio.Queue(maxsize=APPEND_QUEUE_MAX)
        self._append_dropped = 0
        self._append_task = asyncio.create_task(self._append_worker())
        await self._create_talk_session()
        await self._start_pipewire()
        if self.mic_source == "pipewire":
            self._record_task = asyncio.create_task(self._pump_mic_pipewire())

    async def start_talk(self) -> None:
        """Fail loud if Talk or the player is dead. Does not create a second session."""
        if not self._client:
            raise RuntimeError("start_audio() must run before start_talk()")
        if not self._session_id:
            raise RuntimeError("talk session was not created")
        if not self._playback_linked:
            raise PipewireLinkError("openclaw playback is not linked to the bus")
        if self.mic_source == "pipewire" and not self._record_linked:
            raise PipewireLinkError("openclaw record is not linked to the monitor")
        proc = self._playback
        if proc is None or proc.returncode is not None:
            raise PipewireLinkError(
                f"openclaw pw-cat dead rc={None if proc is None else proc.returncode}"
            )

    async def start(self) -> None:
        await self.start_audio()
        await self.start_talk()

    def feed_gsm_ulaw(self, ulaw: bytes) -> None:
        """Mix-minus uplink: phone audio from the GSM WebSocket, not the patched bus."""
        if self._closed or self.mic_source != "hub" or not ulaw:
            return
        if not self._session_id:
            self._mic_buffer.clear()
            self._mic_resample_state = None
            return
        pcm8k = audioop.ulaw2lin(ulaw, 2)
        pcm24k, self._mic_resample_state = audioop.ratecv(
            pcm8k, 2, 1, self.gsm_rate, self.sample_rate, self._mic_resample_state
        )
        self._mic_buffer.extend(pcm24k)
        while len(self._mic_buffer) >= AUDIO_CHUNK_BYTES:
            chunk = bytes(self._mic_buffer[:AUDIO_CHUNK_BYTES])
            del self._mic_buffer[:AUDIO_CHUNK_BYTES]
            if self.tap:
                self.tap.write_s16("openclaw-append-24k-mono", chunk, self.sample_rate, 1)
            self._enqueue_append(chunk)

    def _enqueue_append(self, chunk: bytes) -> None:
        if self._closed or not self._session_id:
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
            msg = str(exc)
            if "Unknown Talk session" in msg or "realtime_unavailable" in msg:
                self._note_session_dead(msg)
                return
            LOG.warning("appendAudio failed: %s", exc)

    def _drain_append_queue(self) -> None:
        q = self._append_queue
        while True:
            try:
                q.get_nowait()
            except asyncio.QueueEmpty:
                break

    def _is_fatal_talk_error(self, reason: str) -> bool:
        text = reason.lower()
        return any(
            needle in text
            for needle in (
                "no credits remaining",
                "insufficient_quota",
                "billing",
                "usage_limit_reached",
                "usage limit has been reached",
                "invalid_api_key",
                "incorrect api key",
                "is not configured",
            )
        )

    def _note_session_dead(self, reason: str) -> None:
        if self._closed:
            return
        self._session_id = None
        self._drain_append_queue()
        if self._is_fatal_talk_error(reason):
            LOG.error("talk session dead; not recreating (%s)", reason)
            return
        if self._reconnect_task and not self._reconnect_task.done():
            return
        LOG.warning("talk session dead (%s); recreating", reason)
        self._reconnect_task = asyncio.create_task(self._reconnect_session(reason))

    async def _reconnect_session(self, reason: str) -> None:
        try:
            for attempt, delay in enumerate(RECONNECT_DELAYS_S, start=1):
                if self._closed:
                    return
                await asyncio.sleep(delay)
                if self._closed:
                    return
                try:
                    await self._create_talk_session()
                    self._audio_frames = 0
                    self._loud_audio_frames = 0
                    self._first_audio_frame_at = None
                    self._first_loud_audio_frame_at = None
                    self._append_dropped = 0
                    LOG.info(
                        "talk session recreated attempt=%s sessionId=%s after %s",
                        attempt,
                        self._session_id,
                        reason,
                    )
                    return
                except Exception as exc:
                    LOG.warning("talk session recreate attempt=%s failed: %s", attempt, exc)
                    if self._is_fatal_talk_error(str(exc)):
                        LOG.error("talk session recreate aborted (%s)", exc)
                        return
            LOG.error("talk session recreate exhausted after %s", reason)
        finally:
            self._reconnect_task = None

    def _track_helper(self, proc: asyncio.subprocess.Process, label: str) -> None:
        self._helper_tasks.append(asyncio.create_task(self._log_helper_stderr(proc, label)))
        self._helper_tasks.append(asyncio.create_task(self._watch_helper_exit(proc, label)))

    async def _log_helper_stderr(self, proc: asyncio.subprocess.Process, label: str) -> None:
        if not proc.stderr:
            return
        try:
            while True:
                line = await proc.stderr.readline()
                if not line:
                    return
                text = line.decode("utf-8", errors="replace").rstrip()
                if text:
                    LOG.warning("%s stderr: %s", label, text)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            if not self._closed:
                LOG.warning("%s stderr reader ended: %s", label, exc)

    async def _watch_helper_exit(self, proc: asyncio.subprocess.Process, label: str) -> None:
        rc = await proc.wait()
        if self._closed:
            return
        LOG.error("%s exited early rc=%s", label, rc)

    async def _start_pipewire(self) -> None:
        playback_serial = await resolve_pipewire_playback_target(self.bus)
        LOG.info("pw-cat playback target %s -> serial %s", self.bus, playback_serial)
        raw = await pw_cat_raw_args()
        latency = pw_latency_args()
        pw_env = pipewire_stream_env(self.sample_rate)
        if self._playback_queue is None:
            self._playback_queue = asyncio.Queue()
        self._playback = await asyncio.create_subprocess_exec(
            *stdbuf_unbuffered(),
            "pw-cat",
            "--playback",
            *raw,
            *latency,
            "--target",
            playback_serial,
            "--rate",
            str(self.sample_rate),
            "--channels",
            "1",
            "--format",
            "s16",
            "-",
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.PIPE,
            env=pw_env,
        )
        self._track_helper(self._playback, "pw-cat playback")
        try:
            detail = await wait_for_pipewire_link(
                self._playback,
                playback_serial,
                direction="playback",
                label="openclaw pw-cat",
            )
        except PipewireLinkError:
            self._playback_linked = False
            raise
        self._playback_linked = True
        LOG.info("openclaw playback linked: %s", detail)
        self._playback_task = asyncio.create_task(self._drain_playback())

        if self.mic_source == "pipewire":
            record_serial = await resolve_pipewire_record_target(self.monitor)
            LOG.info("pw-record target %s -> serial %s", self.monitor, record_serial)
            self._record = await asyncio.create_subprocess_exec(
                *stdbuf_unbuffered(),
                "pw-record",
                *raw,
                *latency,
                "--media-category",
                "Capture",
                "--target",
                record_serial,
                "--rate",
                str(self.sample_rate),
                "--channels",
                "1",
                "--format",
                "s16",
                "-",
                stdin=asyncio.subprocess.DEVNULL,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                env=pw_env,
            )
            self._track_helper(self._record, "pw-record")
            try:
                rec_detail = await wait_for_pipewire_link(
                    self._record,
                    record_serial,
                    direction="record",
                    label="openclaw pw-record",
                )
            except PipewireLinkError:
                self._record_linked = False
                raise
            self._record_linked = True
            LOG.info("openclaw record linked: %s", rec_detail)

        LOG.info(
            "pipewire playback bus=%s serial=%s mic_source=%s monitor=%s",
            self.bus,
            playback_serial,
            self.mic_source,
            self.monitor if self.mic_source == "pipewire" else "hub-ulaw",
        )

    async def _drain_playback(self) -> None:
        assert self._playback and self._playback.stdin and self._playback_queue
        while not self._closed:
            chunk = await self._playback_queue.get()
            if chunk is None:
                break
            proc = self._playback
            if not proc or not proc.stdin:
                LOG.error("playback write skipped: pw-cat stdin missing")
                break
            if proc.returncode is not None:
                LOG.error("playback write skipped: pw-cat already exited rc=%s", proc.returncode)
                break
            try:
                proc.stdin.write(chunk)
                await proc.stdin.drain()
            except (BrokenPipeError, ConnectionResetError, OSError) as exc:
                LOG.error(
                    "playback write failed: %s (pw-cat rc=%s)",
                    exc,
                    proc.returncode,
                )
                break

    def _flush_playback_queue(self) -> None:
        """talk.event clear: drop queued PCM, keep the linked pw-cat for the call."""
        q = self._playback_queue
        if not q:
            return
        flushed = 0
        while True:
            try:
                item = q.get_nowait()
            except asyncio.QueueEmpty:
                break
            if item is not None:
                flushed += 1
        LOG.info("talk.event clear — flushed %s queued playback frames (pw-cat kept)", flushed)

    async def _pump_mic_pipewire(self) -> None:
        assert self._record and self._record.stdout
        while not self._closed:
            try:
                chunk = await self._record.stdout.readexactly(AUDIO_CHUNK_BYTES)
            except asyncio.IncompleteReadError:
                if not self._closed:
                    LOG.error(
                        "pw-record stdout closed early rc=%s",
                        None if not self._record else self._record.returncode,
                    )
                break
            except Exception as exc:
                LOG.warning("mic read failed: %s", exc)
                break
            if not chunk:
                break
            await self._append_audio(chunk)

    async def _on_talk_event(self, payload: dict) -> None:
        if self._closed:
            return
        # Gateway wraps relay frames: { type, reason, talkEvent: { type, audioBase64 } }
        nested = payload.get("talkEvent") if isinstance(payload.get("talkEvent"), dict) else {}
        event = nested if nested else payload
        outer = str(payload.get("type") or "")
        etype = str(event.get("type") or outer)
        type_key = f"{outer}/{etype}"
        if type_key not in self._logged_event_types and etype not in ("audio",):
            self._logged_event_types.add(type_key)
            LOG.info(
                "talk.event type outer=%s nested=%s keys=%s",
                outer,
                etype,
                sorted(payload)[:12],
            )
        if outer in SESSION_END_EVENT_TYPES or etype in SESSION_END_EVENT_TYPES:
            reason = (
                payload.get("reason")
                or (event.get("payload") or {}).get("reason")
                or event.get("message")
                or payload.get("message")
                or etype
                or outer
            )
            LOG.warning("talk session ended outer=%s type=%s reason=%s", outer, etype, reason)
            self._note_session_dead(f"talk.event {outer or etype}: {reason}")
            return
        if outer in CLEAR_EVENT_TYPES or etype in CLEAR_EVENT_TYPES:
            self._flush_playback_queue()
            return
        if not self._playback_queue:
            return
        audio_b64 = event.get("audioBase64") or payload.get("audioBase64")
        if not audio_b64:
            return
        try:
            pcm = base64.b64decode(audio_b64, validate=False)
        except Exception:
            return
        if not pcm:
            return
        if self.tap:
            self.tap.write_s16("openclaw-tts-24k-mono", pcm, self.sample_rate, 1)
        rms = audioop.rms(pcm, 2) / 32768.0
        self._audio_frames += 1
        if self._first_audio_frame_at is None:
            self._first_audio_frame_at = time.monotonic()
        if rms > 0.02:
            self._loud_audio_frames += 1
            if self._first_loud_audio_frame_at is None:
                self._first_loud_audio_frame_at = time.monotonic()
        if self._audio_frames == 1 or self._audio_frames % 50 == 0:
            LOG.info(
                "talk.event audio frames=%s bytes=%s rms=%.4f loud=%s (gateway PCM, not GSM downlink)",
                self._audio_frames,
                len(pcm),
                rms,
                self._loud_audio_frames,
            )
        proc = self._playback
        if not proc or proc.returncode is not None:
            LOG.error(
                "talk.event audio dropped: pw-cat not running rc=%s frames=%s",
                None if not proc else proc.returncode,
                self._audio_frames,
            )
            return
        qsize = self._playback_queue.qsize()
        if qsize >= 200 and (qsize == 200 or qsize % 50 == 0):
            LOG.warning(
                "playback queue depth=%s; pw-cat may not be consuming (frames=%s)",
                qsize,
                self._audio_frames,
            )
        self._playback_queue.put_nowait(pcm)

    async def stop(self) -> None:
        if self._closed and not self._client:
            return
        self._closed = True
        self._playback_linked = False
        self._record_linked = False
        session_id = self._session_id
        client = self._client
        self._session_id = None
        self._client = None
        reconnect = self._reconnect_task
        self._reconnect_task = None
        if reconnect:
            reconnect.cancel()
            try:
                await reconnect
            except asyncio.CancelledError:
                pass

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
        helper_tasks = self._helper_tasks
        self._helper_tasks = []
        for task in helper_tasks:
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass

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
