#!/usr/bin/env python3
"""Per-call taps between the phone, PipeWire buses, and OpenClaw.

Capture still writes WAV stems during the call. close() mixes each room into
one stereo MP3 (left = that room's uplink/mic, right = downlink/speaker) and
deletes the WAVs on success:

  gsm.mp3            phone room (16 kHz): uplink L, downlink R
  openclaw.mp3       OpenClaw/Chromium room (48 kHz): mic L, speaker R
  openclaw-relay.mp3 relay append L + tts R (24 kHz), if those stems exist

Internal stem names are unchanged:

  phone μ-law WS  →  gsm-uplink-8k-mono.wav
  hub 8 kHz capture before μ-law  →  gsm-downlink-8k-mono.wav
  phone_uplink.monitor (Chromium mic)  →  openclaw-mic-48k-stereo.wav
  openclaw_bus.monitor (Chromium speaker)  →  openclaw-spk-48k-stereo.wav

relay graph also writes openclaw-append-24k-mono.wav and openclaw-tts-24k-mono.wav.

Disable with GSM2COMPUTER_CALL_RECORD=0.
"""
from __future__ import annotations

import audioop
import asyncio
import json
import logging
import os
import wave
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

from pipewire_target import (
    pipewire_stream_env,
    pw_cat_raw_args,
    pw_latency_args,
    resolve_pipewire_record_target,
    stdbuf_unbuffered,
)

LOG = logging.getLogger("gsm2computer-hub")

RECORD_ENABLED = os.environ.get("GSM2COMPUTER_CALL_RECORD", "1").lower() not in (
    "0",
    "false",
    "no",
    "off",
)
RECORD_DIR = Path(
    os.environ.get(
        "GSM2COMPUTER_CALL_RECORD_DIR",
        str(Path.home() / "gsm2computer-calls"),
    )
)
KEEP_CALLS = int(os.environ.get("GSM2COMPUTER_CALL_RECORD_KEEP", "20"))
MIX_TIMEOUT_S = 30.0

# out name, left stem, right stem, mix sample rate
_ROOM_MIXES: tuple[tuple[str, str, str, int], ...] = (
    ("gsm.mp3", "gsm-uplink-8k-mono", "gsm-downlink-8k-mono", 16000),
    ("openclaw.mp3", "openclaw-mic-48k-stereo", "openclaw-spk-48k-stereo", 48000),
    ("openclaw-relay.mp3", "openclaw-append-24k-mono", "openclaw-tts-24k-mono", 24000),
)


def _enabled() -> bool:
    return RECORD_ENABLED


def _wav_seconds(path: Path) -> float:
    with wave.open(str(path), "rb") as wav:
        rate = wav.getframerate()
        if rate <= 0:
            raise ValueError(f"invalid sample rate in {path.name}")
        return wav.getnframes() / rate


def ffmpeg_mix_args(left: Path, right: Path, out: Path, rate: int) -> list[str]:
    if rate <= 0:
        raise ValueError(f"invalid mix rate {rate}")
    whole_dur = max(_wav_seconds(left), _wav_seconds(right))
    if whole_dur <= 0:
        raise ValueError(f"empty stems: {left.name}, {right.name}")
    filt = (
        f"[0:a]aformat=sample_fmts=s16:channel_layouts=mono,aresample={rate}:async=1,apad=whole_dur={whole_dur:.6f}[l];"
        f"[1:a]aformat=sample_fmts=s16:channel_layouts=mono,aresample={rate}:async=1,apad=whole_dur={whole_dur:.6f}[r];"
        f"[l][r]join=inputs=2:channel_layout=stereo[a]"
    )
    return [
        "ffmpeg",
        "-y",
        "-hide_banner",
        "-nostdin",
        "-i",
        str(left),
        "-i",
        str(right),
        "-filter_complex",
        filt,
        "-map",
        "[a]",
        "-c:a",
        "libmp3lame",
        "-q:a",
        "4",
        str(out),
    ]


async def mix_stereo_mp3(
    left: Path,
    right: Path,
    out: Path,
    rate: int,
    timeout: float = MIX_TIMEOUT_S,
) -> dict[str, Any]:
    """Mix left/right WAVs into a stereo MP3. Raises on failure."""
    if not left.is_file():
        raise FileNotFoundError(left)
    if not right.is_file():
        raise FileNotFoundError(right)
    args = ffmpeg_mix_args(left, right, out, rate)
    try:
        proc = await asyncio.create_subprocess_exec(
            *args,
            stdin=asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
    except FileNotFoundError as exc:
        raise FileNotFoundError("ffmpeg not found") from exc
    try:
        _stdout, stderr = await asyncio.wait_for(proc.communicate(), timeout=timeout)
    except asyncio.TimeoutError:
        if proc.returncode is None:
            proc.kill()
            await proc.wait()
        _unlink_quiet(out)
        raise TimeoutError(f"ffmpeg mix timed out after {timeout}s: {out.name}")
    if proc.returncode != 0:
        _unlink_quiet(out)
        err = stderr.decode("utf-8", errors="replace").strip()[:500]
        raise RuntimeError(f"ffmpeg mix {out.name} exited {proc.returncode}: {err}")
    if not out.is_file() or out.stat().st_size == 0:
        _unlink_quiet(out)
        raise RuntimeError(f"ffmpeg produced no output {out.name}")
    return {
        "file": out.name,
        "rate": rate,
        "channels": 2,
        "bytes": out.stat().st_size,
    }


def _unlink_quiet(path: Path) -> None:
    try:
        path.unlink(missing_ok=True)
    except OSError:
        pass


async def _ffmpeg_ok() -> bool:
    try:
        proc = await asyncio.create_subprocess_exec(
            "ffmpeg",
            "-version",
            stdin=asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
    except FileNotFoundError:
        return False
    try:
        await asyncio.wait_for(proc.communicate(), timeout=5)
    except asyncio.TimeoutError:
        if proc.returncode is None:
            proc.kill()
            await proc.wait()
        return False
    return proc.returncode == 0


class _Stream:
    def __init__(self, path: Path, rate: int, channels: int) -> None:
        self.path = path
        self.rate = rate
        self.channels = channels
        self.wav = wave.open(str(path), "wb")
        self.wav.setnchannels(channels)
        self.wav.setsampwidth(2)
        self.wav.setframerate(rate)
        self.nbytes = 0
        self.peak = 0
        self.sum_sq = 0.0
        self.loud_windows = 0
        self.windows = 0

    def write(self, pcm: bytes) -> None:
        if not pcm:
            return
        extra = len(pcm) % (2 * self.channels)
        if extra:
            pcm = pcm[: len(pcm) - extra]
        if not pcm:
            return
        self.wav.writeframes(pcm)
        self.nbytes += len(pcm)
        width = 2
        rms = audioop.rms(pcm, width)
        if rms > self.peak:
            self.peak = rms
        # One window per write is good enough for 20 ms frames.
        self.windows += 1
        self.sum_sq += float(rms) * float(rms)
        if rms / 32768.0 > 0.02:
            self.loud_windows += 1

    def stats(self) -> dict[str, Any]:
        samples = self.nbytes // (2 * self.channels)
        seconds = samples / self.rate if self.rate else 0.0
        mean_rms = (self.sum_sq / self.windows) ** 0.5 / 32768.0 if self.windows else 0.0
        return {
            "file": self.path.name,
            "rate": self.rate,
            "channels": self.channels,
            "seconds": round(seconds, 3),
            "peak": round(self.peak / 32768.0, 4),
            "rms": round(mean_rms, 4),
            "loud_frac": round(self.loud_windows / self.windows, 3) if self.windows else 0.0,
        }

    def close(self) -> None:
        self.wav.close()


class CallTap:
    def __init__(self, call_id: str, meta: dict[str, Any]) -> None:
        self.call_id = call_id
        self.dir = RECORD_DIR / call_id
        self.dir.mkdir(parents=True, exist_ok=True)
        self.meta = meta
        self._streams: dict[str, _Stream] = {}
        self._procs: list[asyncio.subprocess.Process] = []
        self._tasks: list[asyncio.Task] = []
        self._closed = False
        LOG.info("call tap dir %s", self.dir)

    @classmethod
    def maybe_open(cls, *, loopback: bool, mode: str) -> Optional["CallTap"]:
        if not _enabled():
            return None
        RECORD_DIR.mkdir(parents=True, exist_ok=True)
        _prune(RECORD_DIR, KEEP_CALLS)
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        kind = "loopback" if loopback else mode
        call_id = f"{stamp}-{kind}"
        return cls(call_id, {"loopback": loopback, "mode": mode, "id": call_id})

    def write_s16(self, name: str, pcm: bytes, rate: int, channels: int = 1) -> None:
        if self._closed or not pcm:
            return
        stream = self._streams.get(name)
        if stream is None:
            path = self.dir / f"{name}.wav"
            stream = _Stream(path, rate, channels)
            self._streams[name] = stream
        stream.write(pcm)

    def write_ulaw(self, name: str, ulaw: bytes, rate: int = 8000) -> None:
        if self._closed or not ulaw:
            return
        self.write_s16(name, audioop.ulaw2lin(ulaw, 2), rate, 1)

    async def start_source(self, name: str, source: str, rate: int, channels: int) -> None:
        """pw-record a PipeWire monitor into a WAV (OpenClaw's ears/mouth)."""
        if self._closed:
            return
        try:
            serial = await resolve_pipewire_record_target(source)
            raw = await pw_cat_raw_args()
            latency = pw_latency_args()
            env = pipewire_stream_env(rate)
            proc = await asyncio.create_subprocess_exec(
                *stdbuf_unbuffered(),
                "pw-record",
                *raw,
                *latency,
                "--media-category",
                "Capture",
                "--target",
                serial,
                "--rate",
                str(rate),
                "--channels",
                str(channels),
                "--format",
                "s16",
                "-",
                stdin=asyncio.subprocess.DEVNULL,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                env=env,
            )
        except Exception as exc:
            LOG.error("call tap %s failed to start on %s: %s", name, source, exc)
            return
        self._procs.append(proc)
        self._tasks.append(asyncio.create_task(self._pump(proc, name, rate, channels, source)))
        LOG.info("call tap %s recording %s serial=%s %s/%sch", name, source, serial, rate, channels)

    async def _pump(
        self,
        proc: asyncio.subprocess.Process,
        name: str,
        rate: int,
        channels: int,
        source: str,
    ) -> None:
        assert proc.stdout
        read_size = max(channels * 2, (rate * channels * 2) // 50)
        try:
            while not self._closed:
                try:
                    chunk = await proc.stdout.readexactly(read_size)
                except asyncio.IncompleteReadError as exc:
                    if exc.partial:
                        self.write_s16(name, exc.partial, rate, channels)
                    return
                self.write_s16(name, chunk, rate, channels)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            if not self._closed:
                LOG.warning("call tap %s (%s) ended: %s", name, source, exc)
        finally:
            if proc.stderr:
                try:
                    err = await asyncio.wait_for(proc.stderr.read(), timeout=0.2)
                except (asyncio.TimeoutError, asyncio.CancelledError):
                    err = b""
                if err and not self._closed:
                    LOG.warning(
                        "call tap %s stderr: %s",
                        name,
                        err.decode("utf-8", errors="replace")[:300],
                    )

    def summary(self) -> dict[str, Any]:
        return {
            "id": self.call_id,
            "dir": str(self.dir),
            "streams": {name: stream.stats() for name, stream in self._streams.items()},
        }

    async def close(self) -> dict[str, Any]:
        if self._closed:
            return self.summary()
        self._closed = True
        for proc in self._procs:
            if proc.returncode is None:
                try:
                    proc.terminate()
                except ProcessLookupError:
                    pass
        for proc in self._procs:
            if proc.returncode is None:
                try:
                    await asyncio.wait_for(proc.wait(), timeout=1)
                except asyncio.TimeoutError:
                    try:
                        proc.kill()
                    except ProcessLookupError:
                        pass
                    await proc.wait()
        for task in self._tasks:
            if not task.done():
                task.cancel()
            try:
                await asyncio.wait_for(task, timeout=0.5)
            except (asyncio.TimeoutError, asyncio.CancelledError):
                pass
        self._tasks = []
        self._procs = []
        for stream in self._streams.values():
            stream.close()
        mixes: dict[str, Any] = {}
        try:
            mixes = await self._mix_rooms()
        except Exception as exc:
            LOG.error("call tap mix failed: %s; leaving WAV stems", exc)
        summary = self.summary()
        summary["meta"] = self.meta
        summary["mixes"] = mixes
        (self.dir / "levels.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
        LOG.info("call tap closed %s mixes=%s", json.dumps(summary["streams"]), json.dumps(mixes))
        return summary

    async def _mix_rooms(self) -> dict[str, Any]:
        mixes: dict[str, Any] = {}
        if not await _ffmpeg_ok():
            LOG.error(
                "call tap mix: ffmpeg not found (`ffmpeg -version` failed); leaving WAV stems in %s",
                self.dir,
            )
            return mixes
        for out_name, left_name, right_name, rate in _ROOM_MIXES:
            left = self.dir / f"{left_name}.wav"
            right = self.dir / f"{right_name}.wav"
            if not left.is_file() or not right.is_file():
                continue
            out = self.dir / out_name
            try:
                stats = await mix_stereo_mp3(left, right, out, rate)
            except Exception as exc:
                LOG.error("call tap mix %s failed: %s; leaving WAV stems", out_name, exc)
                continue
            left_stats = self._streams[left_name].stats() if left_name in self._streams else {}
            right_stats = self._streams[right_name].stats() if right_name in self._streams else {}
            stats["seconds"] = max(left_stats.get("seconds", 0.0), right_stats.get("seconds", 0.0))
            stats["left"] = left_name
            stats["right"] = right_name
            left.unlink()
            right.unlink()
            mixes[out_name] = stats
            LOG.info("call tap mixed %s", out_name)
        return mixes


def _prune(root: Path, keep: int) -> None:
    dirs = [p for p in root.iterdir() if p.is_dir()]
    dirs.sort(key=lambda p: p.name, reverse=True)
    for old in dirs[keep:]:
        for child in old.iterdir():
            child.unlink()
        old.rmdir()
        LOG.info("call tap pruned %s", old)
