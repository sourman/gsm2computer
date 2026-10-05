#!/usr/bin/env python3
"""Append-only UTC JSONL of hub link-gap and websocket-close events.

The file survives a hub restart. call_end_watch reads the tail when a call ends.
Default path is next to the call-end watcher state, override with
GSM2COMPUTER_LINK_GAP_LOG.
"""
from __future__ import annotations

import json
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional


def default_path() -> Path:
    raw = os.environ.get("GSM2COMPUTER_LINK_GAP_LOG", "").strip()
    if raw:
        return Path(raw)
    return Path.home() / "gsm2computer-call-end-watch" / "link-gaps.jsonl"


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def append_link_gap(
    event: dict[str, Any],
    path: Optional[Path] = None,
    *,
    max_bytes: int = 2_000_000,
    keep: int = 3,
) -> None:
    target = Path(path) if path is not None else default_path()
    target.parent.mkdir(parents=True, exist_ok=True)
    record = dict(event)
    record.setdefault("ts", utc_now())
    line = json.dumps(record, default=str, sort_keys=True)
    if target.exists() and target.stat().st_size + len(line) + 1 > max_bytes:
        _rotate(target, keep)
    with target.open("a", encoding="utf-8") as fh:
        fh.write(line + "\n")


def read_recent(limit: int = 30, path: Optional[Path] = None) -> list[dict[str, Any]]:
    target = Path(path) if path is not None else default_path()
    if limit <= 0 or not target.is_file():
        return []
    try:
        lines = target.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return []
    out: list[dict[str, Any]] = []
    for line in lines[-limit:]:
        line = line.strip()
        if not line:
            continue
        try:
            item = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(item, dict):
            out.append(item)
    return out


def _rotate(target: Path, keep: int) -> None:
    slots = max(1, keep - 1)
    oldest = target.with_name(target.name + f".{slots}")
    if oldest.exists():
        oldest.unlink()
    for i in range(slots - 1, 0, -1):
        src = target.with_name(target.name + f".{i}")
        if src.exists():
            src.rename(target.with_name(target.name + f".{i + 1}"))
    if target.exists():
        target.rename(target.with_name(target.name + ".1"))
