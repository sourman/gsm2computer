#!/usr/bin/env python3
"""Inbound SMS persist + STATUS/MODE parse (ADR 0006) + Cup ops wake-up."""
from __future__ import annotations

import os
import re
from datetime import datetime, timezone
from typing import Any, Optional

from portal_http import EventBus
from portal_store import PortalStore

# Default Safwat ops MSISDN; override/extend via GSM2COMPUTER_OPS_SMS_NUMBERS
# (comma-separated; any common formatting accepted).
_DEFAULT_OPS_NUMBERS = ("+201280043725",)

_SYSTEM_DOWN_RE = re.compile(
    r"(?i)(\bsys(?:tem)?\s*down\b|\bdown\s*again\b|\bstill\s*down\b|"
    r"\bstuck\b|\bbusy\b|\bportal\b|\bvoip\b|"
    r"\bvoice\s*call\b|\bcutte?d\b|\bwake\s*cup\b|\balert\b)"
)


def normalize_msisdn(raw: str) -> str:
    """Normalize to +E.164-ish. Egyptian national 0XXXXXXXXXX -> +20XXXXXXXXXX."""
    text = (raw or "").strip()
    if not text:
        return ""
    digits = re.sub(r"\D+", "", text)
    if not digits:
        return ""
    # Egypt mobile national format: 01xxxxxxxxx (11 digits) -> +201xxxxxxxxx
    if digits.startswith("0") and len(digits) == 11:
        digits = "20" + digits[1:]
    return f"+{digits}"


def ops_sms_numbers() -> set[str]:
    raw = (os.environ.get("GSM2COMPUTER_OPS_SMS_NUMBERS") or "").strip()
    parts = [p.strip() for p in raw.split(",")] if raw else list(_DEFAULT_OPS_NUMBERS)
    out: set[str] = set()
    for part in parts:
        if not part:
            continue
        norm = normalize_msisdn(part)
        if not norm:
            continue
        out.add(norm)
        out.add(re.sub(r"\D+", "", norm))
    return out


def is_ops_sender(from_raw: str) -> bool:
    norm = normalize_msisdn(from_raw)
    if not norm:
        return False
    ops = ops_sms_numbers()
    digits = re.sub(r"\D+", "", norm)
    return norm in ops or digits in ops or f"+{digits}" in ops


def looks_like_system_down(body: str) -> bool:
    text = (body or "").strip()
    if not text:
        return False
    if text.upper() == "STATUS":
        return True
    return bool(_SYSTEM_DOWN_RE.search(text))


def should_wake_cup(from_raw: str, body: str) -> bool:
    """Wake Cup on any SMS from an ops number, or STATUS/system-down from anyone."""
    if is_ops_sender(from_raw):
        return True
    return looks_like_system_down(body)


def format_ops_sms_alert(from_raw: str, body: str, received_at: str) -> str:
    preview = (body or "").strip().replace("\n", " ")
    if len(preview) > 280:
        preview = preview[:277] + "..."
    return (
        f"ops SMS wake-up from {from_raw or 'unknown'}: {preview or '(empty)'} "
        f"(receivedAt={received_at})"
    )


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
