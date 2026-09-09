#!/usr/bin/env python3
"""Inbound SMS persist + STATUS/MODE parse (ADR 0006)."""
from __future__ import annotations

import re
from datetime import datetime, timezone
from typing import Any, Optional

from portal_http import EventBus
from portal_store import PortalStore


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


def ingest_inbound_sms(
    store: PortalStore,
    events: EventBus,
    payload: dict[str, Any],
) -> tuple[dict[str, Any], Optional[str]]:
    """Persist inbound SMS and return (log_entry, switchboard command)."""
    received = payload.get("receivedAt") or datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    log_entry = {
        "from": payload.get("from"),
        "body": payload.get("body"),
        "receivedAt": received,
    }
    from_raw = str(payload.get("from") or "")
    body_text = str(payload.get("body") or "")
    if from_raw.strip():
        msg = store.add_message("in", from_raw, body_text, ts=str(received), status="received")
        events.publish("message", msg)
    return log_entry, parse_sms_command(body_text)
