#!/usr/bin/env python3
"""Resolve PipeWire playback/record targets and wait until a helper is linked.

pw-cat/pw-record --target wants a PipeWire object.serial (or node id). Pulse
names like openclaw_bus.monitor are not nodes, so a name attach can miss and
the stream lands on the default device — or nowhere — while Talk frame counts
still look healthy. pactl source/sink indices match object.serial for the
null-sink buses this hub uses.
"""
from __future__ import annotations

import asyncio
import json
import os
import time
from typing import Any, Optional

LINK_WAIT_TIMEOUT_S = float(os.environ.get("GSM2COMPUTER_PW_LINK_TIMEOUT", "3"))
LINK_POLL_S = float(os.environ.get("GSM2COMPUTER_PW_LINK_POLL", "0.05"))
TOOL_TIMEOUT_S = float(os.environ.get("GSM2COMPUTER_PW_TOOL_TIMEOUT", "2"))
MISMATCH_CONFIRM_S = float(os.environ.get("GSM2COMPUTER_PW_MISMATCH_CONFIRM", "0.2"))

_pw_cat_has_raw: Optional[bool] = None


class PipewireLinkError(RuntimeError):
    """Helper never linked to the intended bus (or died / linked elsewhere)."""


async def _kill_proc(proc: asyncio.subprocess.Process) -> None:
    if proc.returncode is not None:
        return
    try:
        proc.terminate()
    except ProcessLookupError:
        return
    try:
        await asyncio.wait_for(proc.wait(), timeout=0.5)
    except asyncio.TimeoutError:
        try:
            proc.kill()
        except ProcessLookupError:
            return
        await proc.wait()


async def _communicate_or_kill(
    proc: asyncio.subprocess.Process,
    timeout_s: float,
    label: str,
) -> tuple[bytes, bytes]:
    try:
        return await asyncio.wait_for(proc.communicate(), timeout=timeout_s)
    except asyncio.TimeoutError:
        await _kill_proc(proc)
        raise RuntimeError(f"{label} timed out after {timeout_s}s") from None
    except asyncio.CancelledError:
        await _kill_proc(proc)
        raise


async def _run_captured(args: list[str], timeout_s: float, label: str) -> tuple[int, str, str]:
    proc = await asyncio.create_subprocess_exec(
        *args,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    stdout_b, stderr_b = await _communicate_or_kill(proc, timeout_s, label)
    return (
        proc.returncode or 0,
        stdout_b.decode("utf-8", errors="replace"),
        stderr_b.decode("utf-8", errors="replace"),
    )


async def _pactl_short(kind: str) -> str:
    rc, out, err = await _run_captured(
        ["pactl", "list", kind, "short"],
        TOOL_TIMEOUT_S,
        f"pactl list {kind}",
    )
    if rc:
        raise RuntimeError(f"pactl list {kind} failed: {err.strip() or rc}")
    return out


def _pactl_index_for_name(listing: str, name: str) -> Optional[str]:
    for line in listing.splitlines():
        parts = line.split()
        if len(parts) >= 2 and parts[1] == name:
            return parts[0]
    return None


async def resolve_pipewire_playback_target(name: str) -> str:
    """pw-cat --playback --target wants the sink object's serial."""
    if name.isdigit():
        return name
    serial = _pactl_index_for_name(await _pactl_short("sinks"), name)
    if serial is None:
        raise RuntimeError(f"PipeWire playback target not found: {name}")
    return serial


async def resolve_pipewire_record_target(name: str) -> str:
    """pw-record --target wants the source/monitor object's serial.

    Do not substitute the matching sink serial: capture must bind the
    monitor source. If the source is missing, fail immediately.
    """
    if name.isdigit():
        return name
    serial = _pactl_index_for_name(await _pactl_short("sources"), name)
    if serial is None:
        raise RuntimeError(
            f"PipeWire record target not found: {name} "
            "(source/monitor missing from pactl list sources)"
        )
    return serial


def stdbuf_unbuffered() -> list[str]:
    """pw-record FILE* stdout is fully buffered (4 KiB) on a pipe.

    4096 / (8000 Hz * 4 bytes stereo) = 128 ms, which is the clippy stall.
    """
    return ["stdbuf", "-o0", "-i0"]


def pw_latency_args() -> list[str]:
    """Keep pw-cat/pw-record quantum at one 20 ms GSM frame.

    The default 100 ms latency makes pw-record stdout arrive as mixed 8 ms
    fragments and ~120 ms stalls, which the simulator then plays as dropouts.
    """
    return ["--latency", os.environ.get("GSM2COMPUTER_PW_LATENCY", "20ms")]


def pipewire_stream_env(rate: int) -> dict[str, str]:
    """Force 20 ms quanta in the helper process (PIPEWIRE_LATENCY=period/rate)."""
    env = os.environ.copy()
    period = max(1, int(rate) // 50)
    env["PIPEWIRE_LATENCY"] = f"{period}/{int(rate)}"
    return env


async def pw_cat_raw_args() -> list[str]:
    """Return ['--raw'] only if this pw-cat documents the flag.

    PipeWire 1.2.x (safwat-eu) has no --raw; stdin '-' with --format is
    already raw PCM. 1.4.x may try libsndfile on '-' unless --raw is set.
    """
    global _pw_cat_has_raw
    if _pw_cat_has_raw is None:
        try:
            _rc, out, err = await _run_captured(
                ["pw-cat", "--help"],
                TOOL_TIMEOUT_S,
                "pw-cat --help",
            )
            text = f"{out}\n{err}"
            _pw_cat_has_raw = "--raw" in text or "-a," in text
        except Exception:
            _pw_cat_has_raw = False
    return ["--raw"] if _pw_cat_has_raw else []


async def _pw_dump(timeout_s: float = TOOL_TIMEOUT_S) -> list[dict[str, Any]]:
    proc = await asyncio.create_subprocess_exec(
        "pw-dump",
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    stdout_b, stderr_b = await _communicate_or_kill(proc, timeout_s, "pw-dump")
    if proc.returncode:
        err = stderr_b.decode("utf-8", errors="replace").strip()
        raise RuntimeError(f"pw-dump failed: {err or proc.returncode}")
    data = json.loads(stdout_b.decode("utf-8"))
    if not isinstance(data, list):
        raise RuntimeError("pw-dump returned non-list JSON")
    return data


def _props(obj: dict[str, Any]) -> dict[str, Any]:
    info = obj.get("info") or {}
    props = info.get("props") if isinstance(info, dict) else None
    return props if isinstance(props, dict) else {}


def _as_int(value: Any) -> Optional[int]:
    if isinstance(value, int):
        return value
    if isinstance(value, str) and value.isdigit():
        return int(value)
    return None


def _nodes_by_id(dump: list[dict[str, Any]]) -> dict[int, dict[str, Any]]:
    out: dict[int, dict[str, Any]] = {}
    for obj in dump:
        if obj.get("type") != "PipeWire:Interface:Node":
            continue
        nid = obj.get("id")
        if isinstance(nid, int):
            out[nid] = _props(obj)
    return out


def _client_ids_for_pid(dump: list[dict[str, Any]], pid: int) -> set[int]:
    ids: set[int] = set()
    want = str(pid)
    for obj in dump:
        if obj.get("type") != "PipeWire:Interface:Client":
            continue
        props = _props(obj)
        if str(props.get("application.process.id") or "") != want:
            continue
        cid = obj.get("id")
        if isinstance(cid, int):
            ids.add(cid)
    return ids


def _stream_ids_for_pid(dump: list[dict[str, Any]], pid: int) -> list[int]:
    want = str(pid)
    client_ids = _client_ids_for_pid(dump, pid)
    stream_ids: list[int] = []
    for nid, props in _nodes_by_id(dump).items():
        media = str(props.get("media.class") or "")
        if "Stream" not in media:
            continue
        if str(props.get("application.process.id") or "") == want:
            stream_ids.append(nid)
            continue
        client_id = _as_int(props.get("client.id"))
        if client_id is not None and client_id in client_ids:
            stream_ids.append(nid)
    return stream_ids


def _node_ids_for_serial(dump: list[dict[str, Any]], serial: str) -> list[int]:
    want = str(serial)
    return [
        nid
        for nid, props in _nodes_by_id(dump).items()
        if str(props.get("object.serial") or "") == want
    ]


def _iter_links(dump: list[dict[str, Any]]) -> list[tuple[int, int, str]]:
    links: list[tuple[int, int, str]] = []
    for obj in dump:
        if obj.get("type") != "PipeWire:Interface:Link":
            continue
        info = obj.get("info") or {}
        if not isinstance(info, dict):
            continue
        props = info.get("props") if isinstance(info.get("props"), dict) else {}
        out_n = info.get("output-node-id", props.get("link.output.node"))
        in_n = info.get("input-node-id", props.get("link.input.node"))
        if not isinstance(out_n, int) or not isinstance(in_n, int):
            continue
        state = str(info.get("state") or props.get("link.state") or "")
        if state not in ("active", "negotiating"):
            continue
        links.append((out_n, in_n, state))
    return links


def describe_stream_link(
    dump: list[dict[str, Any]],
    pid: int,
    target_serial: str,
    direction: str,
) -> tuple[str, str]:
    """Return (status, detail) for a helper pid.

    status is one of: pending, linked, mismatched.
    """
    nodes = _nodes_by_id(dump)
    stream_ids = _stream_ids_for_pid(dump, pid)
    if not stream_ids:
        return "pending", f"pid={pid} has no PipeWire stream node yet"
    target_ids = set(_node_ids_for_serial(dump, target_serial))
    if not target_ids:
        return "pending", f"no node with object.serial={target_serial}"

    peers: list[tuple[int, int]] = []
    for out_n, in_n, _state in _iter_links(dump):
        if direction == "playback":
            if out_n in stream_ids:
                peers.append((out_n, in_n))
        elif direction == "record":
            if in_n in stream_ids:
                peers.append((out_n, in_n))
        else:
            raise ValueError(f"unknown PipeWire link direction: {direction}")

    def _name(nid: int) -> str:
        props = nodes.get(nid) or {}
        name = props.get("node.name") or f"id:{nid}"
        serial = props.get("object.serial")
        return f"{name} serial={serial}"

    if not peers:
        streams = ", ".join(_name(s) for s in stream_ids)
        return "pending", f"pid={pid} stream unlinked ({streams})"

    matched = [(src, dst) for src, dst in peers if (dst if direction == "playback" else src) in target_ids]
    if matched:
        src, dst = matched[0]
        return "linked", f"{_name(src)} -> {_name(dst)}"

    src, dst = peers[0]
    return "mismatched", f"{_name(src)} -> {_name(dst)} (want serial={target_serial})"


def _remaining(deadline: float) -> float:
    return deadline - time.monotonic()


async def wait_for_pipewire_link(
    proc: asyncio.subprocess.Process,
    target_serial: str,
    *,
    direction: str,
    timeout_s: float = LINK_WAIT_TIMEOUT_S,
    label: str = "pipewire",
) -> str:
    """Poll pw-dump until proc's stream is linked to target_serial.

    Each dump is bounded by TOOL_TIMEOUT_S and by the remaining deadline so
    a stuck pw-dump cannot freeze the handshake. A confirmed mismatch
    (default-device attach) fails after one extra poll instead of burning
    the full wait.
    """
    if proc.pid is None:
        raise PipewireLinkError(f"{label} has no pid")
    deadline = time.monotonic() + timeout_s
    last = f"pid={proc.pid} waiting for {direction} link to serial={target_serial}"
    seen_mismatch = False
    while True:
        remaining = _remaining(deadline)
        if remaining <= 0:
            break
        if proc.returncode is not None:
            raise PipewireLinkError(
                f"{label} exited rc={proc.returncode} before {direction} "
                f"link to serial={target_serial}"
            )
        dump_timeout = min(TOOL_TIMEOUT_S, remaining)
        try:
            dump = await _pw_dump(timeout_s=dump_timeout)
            status, detail = describe_stream_link(dump, proc.pid, target_serial, direction)
        except Exception as exc:
            status, detail = "pending", str(exc)
            seen_mismatch = False
        last = detail
        if status == "linked":
            return detail
        if status == "mismatched":
            if seen_mismatch:
                raise PipewireLinkError(
                    f"{label} linked to the wrong node: {detail}"
                )
            seen_mismatch = True
            confirm = min(MISMATCH_CONFIRM_S, _remaining(deadline))
            if confirm <= 0:
                raise PipewireLinkError(
                    f"{label} linked to the wrong node: {detail}"
                )
            await asyncio.sleep(confirm)
            continue
        seen_mismatch = False
        remaining = _remaining(deadline)
        if remaining <= 0:
            break
        await asyncio.sleep(min(LINK_POLL_S, remaining))
    raise PipewireLinkError(
        f"{label} not linked to serial={target_serial} for {direction} "
        f"within {timeout_s}s ({last})"
    )
