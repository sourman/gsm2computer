#!/usr/bin/env python3
"""Optional outbound POST of system alerts to a Grok Bot webhook routine.

Unset GSM2COMPUTER_ALERT_WEBHOOK_URL is a silent no-op. Failures never raise
into the call path. Do not log the webhook key or Authorization header.

Synthetic OpenClaw e2e / self-test alerts are skipped (see is_test_alert).
"""
from __future__ import annotations

import json
import logging
import os
import re
import urllib.error
import urllib.request

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


def post_system_alert(text: str, *, e2e: bool = False) -> None:
    """POST ``{"text": ...}`` to the configured webhook, or skip if unset/test."""
    if is_test_alert(text, e2e=e2e):
        LOG.info("alert webhook skipped (e2e/test): %s", (text or "")[:160])
        return
    url = (os.environ.get(URL_ENV) or "").strip()
    if not url:
        return
    key = (os.environ.get(KEY_ENV) or "").strip()
    payload = json.dumps({"text": text}, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if key:
        headers["Authorization"] = _authorization_value(key)
    req = urllib.request.Request(url, data=payload, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_S) as resp:
            status = getattr(resp, "status", None) or resp.getcode()
            if status >= 300:
                LOG.warning("alert webhook HTTP %s", status)
    except urllib.error.HTTPError as exc:
        LOG.warning("alert webhook HTTP %s", exc.code)
    except Exception:
        LOG.warning("alert webhook failed")
