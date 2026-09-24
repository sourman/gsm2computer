#!/usr/bin/env python3
"""Optional outbound POST of system alerts to a Grok Bot webhook routine.

Unset GSM2COMPUTER_ALERT_WEBHOOK_URL is a silent no-op. Failures never raise
into the call path. Do not log the webhook key or Authorization header.

Synthetic OpenClaw e2e / self-test alerts are skipped (see is_test_alert).
Structured call_ended events (including dry-run test:true) always post —
they must wake Cup after real phone hangups and must not be swallowed by
the e2e text filter.
"""
from __future__ import annotations

import json
import logging
import os
import re
import urllib.error
import urllib.request
from typing import Any, Mapping, Optional

LOG = logging.getLogger("gsm2computer-hub")

URL_ENV = "GSM2COMPUTER_ALERT_WEBHOOK_URL"
KEY_ENV = "GSM2COMPUTER_ALERT_WEBHOOK_KEY"
TIMEOUT_S = 5.0

# Match e2e / self-test wording so test cleanup never wakes Cup.
_TEST_ALERT_RE = re.compile(
    r"(e2e-test|/e2e-test|\be2e\b|openclaw e2e|self-test|synthetic test)",
    re.IGNORECASE,
)


def is_test_alert(text: str, *, e2e: bool = False) -> bool:
    """True when this alert is about a synthetic e2e session (must not wake Cup)."""
    if e2e:
        return True
    return bool(_TEST_ALERT_RE.search(text or ""))


def _authorization_value(key: str) -> str:
    if key.lower().startswith("bearer "):
        return key
    return f"Bearer {key}"


def _post_json(payload: Mapping[str, Any]) -> None:
    url = (os.environ.get(URL_ENV) or "").strip()
    if not url:
        return
    key = (os.environ.get(KEY_ENV) or "").strip()
    body = json.dumps(dict(payload), ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if key:
        headers["Authorization"] = _authorization_value(key)
    req = urllib.request.Request(url, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_S) as resp:
            status = getattr(resp, "status", None) or resp.getcode()
            if status >= 300:
                LOG.warning("alert webhook HTTP %s", status)
    except urllib.error.HTTPError as exc:
        LOG.warning("alert webhook HTTP %s", exc.code)
    except Exception:
        LOG.warning("alert webhook failed")


def post_system_alert(text: str, *, e2e: bool = False) -> None:
    """POST ``{"text": ...}`` to the configured webhook, or skip if unset/test."""
    if is_test_alert(text, e2e=e2e):
        LOG.info("alert webhook skipped (e2e/test): %s", (text or "")[:160])
        return
    _post_json({"text": text})


def post_alert_event(payload: Mapping[str, Any]) -> None:
    """POST a structured JSON event (e.g. call_ended).

    Always allowed for ``event == "call_ended"`` (including ``test: true`` dry-runs).
    Other events with ``e2e: true`` are skipped. Never logs secrets.
    """
    event = str(payload.get("event") or "")
    if event == "call_ended":
        LOG.info(
            "alert webhook call_ended test=%s duration_s=%s call_id=%s",
            bool(payload.get("test")),
            payload.get("duration_s"),
            str(payload.get("call_id") or "")[:32],
        )
        _post_json(payload)
        return
    if payload.get("e2e") is True:
        LOG.info("alert webhook skipped (e2e event)")
        return
    text = str(payload.get("text") or "")
    if is_test_alert(text, e2e=False):
        LOG.info("alert webhook skipped (e2e/test event text)")
        return
    _post_json(payload)
