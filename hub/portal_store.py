#!/usr/bin/env python3
"""SQLite persistence for the hub messaging portal (ADR 0006)."""
from __future__ import annotations

import re
import sqlite3
import threading
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional


SCHEMA = """
CREATE TABLE IF NOT EXISTS messages (
    id TEXT PRIMARY KEY,
    direction TEXT NOT NULL,
    peer TEXT NOT NULL,
    body TEXT NOT NULL,
    ts TEXT NOT NULL,
    status TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS messages_peer_ts ON messages (peer, ts);

CREATE TABLE IF NOT EXISTS calls (
    id TEXT PRIMARY KEY,
    direction TEXT NOT NULL,
    number TEXT NOT NULL,
    started_at TEXT NOT NULL,
    duration_sec INTEGER NOT NULL,
    switchboard_mode TEXT,
    session_id TEXT,
    tap_summary TEXT
);
CREATE INDEX IF NOT EXISTS calls_started ON calls (started_at);

CREATE TABLE IF NOT EXISTS outbox (
    id TEXT PRIMARY KEY,
    to_number TEXT NOT NULL,
    body TEXT NOT NULL,
    created_at TEXT NOT NULL,
    status TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS outbox_status ON outbox (status, created_at);
"""


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def normalize_e164(raw: str) -> str:
    """Normalize to E.164 where possible; empty input stays empty."""
    s = (raw or "").strip()
    if not s:
        return ""
    s = re.sub(r"[\s().\-]", "", s)
    if s.startswith("00"):
        s = "+" + s[2:]
    if s.startswith("+"):
        digits = re.sub(r"\D", "", s)
        return "+" + digits if digits else ""
    digits = re.sub(r"\D", "", s)
    if not digits:
        return raw.strip()
    if len(digits) == 10:
        return "+1" + digits
    if len(digits) == 11 and digits.startswith("1"):
        return "+" + digits
    return "+" + digits


def _row_message(row: sqlite3.Row) -> dict[str, Any]:
    return {
        "id": row["id"],
        "direction": row["direction"],
        "peer": row["peer"],
        "body": row["body"],
        "ts": row["ts"],
        "status": row["status"],
    }


def _row_call(row: sqlite3.Row) -> dict[str, Any]:
    return {
        "id": row["id"],
        "direction": row["direction"],
        "number": row["number"],
        "started_at": row["started_at"],
        "duration_sec": int(row["duration_sec"] or 0),
        "switchboard_mode": row["switchboard_mode"],
        "session_id": row["session_id"],
        "tap_summary": row["tap_summary"],
    }


def _row_outbox(row: sqlite3.Row) -> dict[str, Any]:
    return {
        "id": row["id"],
        "to": row["to_number"],
        "body": row["body"],
        "created_at": row["created_at"],
        "status": row["status"],
    }


class PortalStore:
    def __init__(self, db_path: Path) -> None:
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self._conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.execute("PRAGMA foreign_keys=ON")
        with self._lock:
            self._conn.executescript(SCHEMA)
            self._conn.commit()

    def close(self) -> None:
        with self._lock:
            self._conn.close()

    def add_message(
        self,
        direction: str,
        peer: str,
        body: str,
        ts: Optional[str] = None,
        status: str = "received",
        msg_id: Optional[str] = None,
    ) -> dict[str, Any]:
        peer_n = normalize_e164(peer) or peer.strip()
        item = {
            "id": msg_id or str(uuid.uuid4()),
            "direction": direction,
            "peer": peer_n,
            "body": body if body is not None else "",
            "ts": ts or utc_now(),
            "status": status,
        }
        with self._lock:
            self._conn.execute(
                "INSERT INTO messages (id, direction, peer, body, ts, status) VALUES (?, ?, ?, ?, ?, ?)",
                (item["id"], item["direction"], item["peer"], item["body"], item["ts"], item["status"]),
            )
            self._conn.commit()
        return item

    def list_threads(self) -> list[dict[str, Any]]:
        sql = """
            SELECT m.peer, m.body, m.ts, m.direction, m.status
            FROM messages m
            INNER JOIN (
                SELECT peer, MAX(ts) AS last_ts
                FROM messages
                GROUP BY peer
            ) latest ON latest.peer = m.peer AND latest.last_ts = m.ts
            ORDER BY m.ts DESC
        """
        with self._lock:
            rows = self._conn.execute(sql).fetchall()
        threads: list[dict[str, Any]] = []
        seen: set[str] = set()
        for row in rows:
            peer = row["peer"]
            if peer in seen:
                continue
            seen.add(peer)
            threads.append(
                {
                    "peer": peer,
                    "lastBody": row["body"],
                    "lastAt": row["ts"],
                    "unread": 0,
                }
            )
        return threads

    def list_messages(self, peer: str) -> list[dict[str, Any]]:
        peer_n = normalize_e164(peer) or peer.strip()
        with self._lock:
            rows = self._conn.execute(
                "SELECT * FROM messages WHERE peer = ? ORDER BY ts ASC, id ASC",
                (peer_n,),
            ).fetchall()
        return [_row_message(r) for r in rows]

    def enqueue_outbound(self, to_number: str, body: str) -> dict[str, Any]:
        peer = normalize_e164(to_number) or to_number.strip()
        if not peer:
            raise ValueError("to required")
        if body is None or not str(body).strip():
            raise ValueError("body required")
        now = utc_now()
        msg_id = str(uuid.uuid4())
        msg = self.add_message("out", peer, str(body), ts=now, status="queued", msg_id=msg_id)
        with self._lock:
            self._conn.execute(
                "INSERT INTO outbox (id, to_number, body, created_at, status) VALUES (?, ?, ?, ?, ?)",
                (msg_id, peer, str(body), now, "pending"),
            )
            self._conn.commit()
        return {
            "id": msg_id,
            "to": peer,
            "body": str(body),
            "created_at": now,
            "status": "pending",
            "message": msg,
        }

    def list_outbox_pending(self) -> list[dict[str, Any]]:
        """Rows still in pending (tests / diagnostics only)."""
        with self._lock:
            rows = self._conn.execute(
                "SELECT * FROM outbox WHERE status = 'pending' ORDER BY created_at ASC"
            ).fetchall()
        return [_row_outbox(r) for r in rows]

    def claim_outbox_for_send(self, limit: int = 20) -> list[dict[str, Any]]:
        """Atomically lease pending rows as sending so polls cannot double-send."""
        with self._lock:
            rows = self._conn.execute(
                """SELECT id FROM outbox WHERE status = 'pending'
                   ORDER BY created_at ASC LIMIT ?""",
                (max(1, int(limit)),),
            ).fetchall()
            ids = [r["id"] for r in rows]
            if not ids:
                self._conn.commit()
                return []
            placeholders = ",".join("?" * len(ids))
            self._conn.execute(
                f"UPDATE outbox SET status = 'sending' WHERE id IN ({placeholders})",
                ids,
            )
            self._conn.commit()
            claimed = self._conn.execute(
                f"SELECT * FROM outbox WHERE id IN ({placeholders})",
                ids,
            ).fetchall()
        return [_row_outbox(r) for r in claimed]

    def ack_outbox(self, item_id: str, status: str = "sent", error: Optional[str] = None) -> Optional[dict[str, Any]]:
        status_n = (status or "sent").strip().lower()
        if status_n not in {"sent", "failed"}:
            status_n = "sent"
        msg_status = "sent" if status_n == "sent" else "failed"
        with self._lock:
            row = self._conn.execute("SELECT * FROM outbox WHERE id = ?", (item_id,)).fetchone()
            if row is None:
                return None
            current = row["status"]
            if current in {"sent", "failed"}:
                result = _row_outbox(row)
                if error:
                    result["error"] = error
                return result
            self._conn.execute("UPDATE outbox SET status = ? WHERE id = ?", (status_n, item_id))
            self._conn.execute("UPDATE messages SET status = ? WHERE id = ?", (msg_status, item_id))
            self._conn.commit()
            updated = self._conn.execute("SELECT * FROM outbox WHERE id = ?", (item_id,)).fetchone()
        result = _row_outbox(updated)
        if error:
            result["error"] = error
        return result

    def add_call(
        self,
        direction: str,
        number: str,
        started_at: str,
        duration_sec: int,
        switchboard_mode: Optional[str] = None,
        session_id: Optional[str] = None,
        tap_summary: Optional[str] = None,
        call_id: Optional[str] = None,
    ) -> dict[str, Any]:
        dir_n = (direction or "").strip().lower()
        if dir_n in {"in", "incoming", "inbound"}:
            dir_n = "in"
        elif dir_n in {"out", "outgoing", "outbound"}:
            dir_n = "out"
        number_n = normalize_e164(number) or (number or "").strip()
        item = {
            "id": call_id or str(uuid.uuid4()),
            "direction": dir_n or "in",
            "number": number_n,
            "started_at": started_at or utc_now(),
            "duration_sec": int(duration_sec or 0),
            "switchboard_mode": switchboard_mode or None,
            "session_id": session_id or None,
            "tap_summary": tap_summary or None,
        }
        with self._lock:
            self._conn.execute(
                """INSERT INTO calls
                   (id, direction, number, started_at, duration_sec, switchboard_mode, session_id, tap_summary)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                (
                    item["id"],
                    item["direction"],
                    item["number"],
                    item["started_at"],
                    item["duration_sec"],
                    item["switchboard_mode"],
                    item["session_id"],
                    item["tap_summary"],
                ),
            )
            self._conn.commit()
        return item

    def list_calls(self) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._conn.execute("SELECT * FROM calls ORDER BY started_at DESC").fetchall()
        return [_row_call(r) for r in rows]

    def get_message(self, msg_id: str) -> Optional[dict[str, Any]]:
        with self._lock:
            row = self._conn.execute("SELECT * FROM messages WHERE id = ?", (msg_id,)).fetchone()
        return _row_message(row) if row else None
