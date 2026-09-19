"""SQLite message store + SSE fan-out for the hub portal (ADR 0006)."""
from __future__ import annotations

import os
import re
import sqlite3
import threading
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

HUB_DIR = Path(os.environ.get("GSM2COMPUTER_HUB_DIR", Path(__file__).resolve().parent))


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def normalize_peer(raw: str) -> str:
    text = re.sub(r"[\s().-]", "", (raw or "").strip())
    if text.startswith("00"):
        text = "+" + text[2:]
    return text


def new_id() -> str:
    return uuid.uuid4().hex


class EventBus:
    """In-process fan-out for portal SSE subscribers."""

    def __init__(self) -> None:
        self._subs: set[Any] = set()
        self._lock = threading.Lock()

    def subscribe(self) -> Any:
        import asyncio

        queue: asyncio.Queue = asyncio.Queue(maxsize=64)
        with self._lock:
            self._subs.add(queue)
        return queue

    def unsubscribe(self, queue: Any) -> None:
        with self._lock:
            self._subs.discard(queue)

    def publish(self, event: dict[str, Any]) -> None:
        with self._lock:
            subs = list(self._subs)
        for queue in subs:
            try:
                queue.put_nowait(event)
            except Exception:
                self.unsubscribe(queue)

    def clear(self) -> None:
        with self._lock:
            self._subs.clear()

    def subscriber_count(self) -> int:
        with self._lock:
            return len(self._subs)


class PortalStore:
    def __init__(self, db_path: Path) -> None:
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self._conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._init()

    def close(self) -> None:
        with self._lock:
            self._conn.close()

    def _init(self) -> None:
        with self._lock:
            self._conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS messages (
                    id TEXT PRIMARY KEY,
                    direction TEXT NOT NULL,
                    peer TEXT NOT NULL,
                    body TEXT NOT NULL,
                    ts TEXT NOT NULL,
                    status TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS messages_peer_ts ON messages(peer, ts);
                CREATE TABLE IF NOT EXISTS outbox (
                    id TEXT PRIMARY KEY,
                    to_number TEXT NOT NULL,
                    body TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    status TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS calls (
                    id TEXT PRIMARY KEY,
                    direction TEXT,
                    number TEXT,
                    started_at TEXT,
                    duration_sec INTEGER,
                    switchboard_mode TEXT,
                    session_id TEXT,
                    tap_summary TEXT
                );
                """
            )
            self._conn.commit()

    def persist_inbound(self, peer: str, body: str, ts: Optional[str] = None) -> Optional[dict[str, Any]]:
        peer_n = normalize_peer(peer)
        if not peer_n:
            return None
        rec = {
            "type": "message",
            "id": new_id(),
            "direction": "in",
            "peer": peer_n,
            "body": body or "",
            "ts": ts or utc_now(),
            "status": "received",
        }
        self._insert_message(rec)
        return rec

    def queue_outbound(self, to_number: str, body: str) -> dict[str, Any]:
        peer_n = normalize_peer(to_number)
        if not peer_n:
            raise ValueError("to required")
        if not (body or "").strip():
            raise ValueError("body required")
        ts = utc_now()
        outbox_id = new_id()
        rec = {
            "type": "message",
            "id": new_id(),
            "direction": "out",
            "peer": peer_n,
            "body": body.strip(),
            "ts": ts,
            "status": "queued",
            "outboxId": outbox_id,
        }
        with self._lock:
            self._conn.execute(
                "INSERT INTO messages(id, direction, peer, body, ts, status) VALUES (?, ?, ?, ?, ?, ?)",
                (rec["id"], rec["direction"], rec["peer"], rec["body"], rec["ts"], rec["status"]),
            )
            self._conn.execute(
                "INSERT INTO outbox(id, to_number, body, created_at, status) VALUES (?, ?, ?, ?, ?)",
                (outbox_id, peer_n, rec["body"], ts, "queued"),
            )
            self._conn.commit()
        return rec

    def list_outbox(self) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT id, to_number, body, created_at, status FROM outbox WHERE status = 'queued' ORDER BY created_at ASC"
            ).fetchall()
        return [
            {
                "id": r["id"],
                "to": r["to_number"],
                "body": r["body"],
                "createdAt": r["created_at"],
                "status": r["status"],
            }
            for r in rows
        ]

    def ack_outbox(self, outbox_id: str) -> Optional[dict[str, Any]]:
        token = (outbox_id or "").strip()
        if not token:
            return None
        ts = utc_now()
        with self._lock:
            row = self._conn.execute(
                "SELECT id, to_number, body FROM outbox WHERE id = ? AND status = 'queued'",
                (token,),
            ).fetchone()
            if row is None:
                return None
            self._conn.execute("UPDATE outbox SET status = 'sent' WHERE id = ?", (token,))
            self._conn.execute(
                "UPDATE messages SET status = 'sent' WHERE direction = 'out' AND peer = ? AND body = ? AND status = 'queued'",
                (row["to_number"], row["body"]),
            )
            msg = self._conn.execute(
                "SELECT id, direction, peer, body, ts, status FROM messages "
                "WHERE direction = 'out' AND peer = ? AND body = ? ORDER BY ts DESC LIMIT 1",
                (row["to_number"], row["body"]),
            ).fetchone()
            self._conn.commit()
        rec = {
            "type": "message",
            "id": msg["id"] if msg else new_id(),
            "direction": "out",
            "peer": row["to_number"],
            "body": row["body"],
            "ts": msg["ts"] if msg else ts,
            "status": "sent",
            "outboxId": token,
        }
        return rec

    def list_threads(self) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._conn.execute(
                """
                SELECT m.peer AS peer, m.body AS lastBody, m.ts AS lastAt
                FROM messages m
                JOIN (
                    SELECT peer, MAX(ts) AS lastAt FROM messages GROUP BY peer
                ) t ON m.peer = t.peer AND m.ts = t.lastAt
                GROUP BY m.peer
                ORDER BY m.ts DESC
                """
            ).fetchall()
        return [{"peer": r["peer"], "lastBody": r["lastBody"], "lastAt": r["lastAt"]} for r in rows]

    def list_messages(self, peer: str) -> list[dict[str, Any]]:
        peer_n = normalize_peer(peer)
        with self._lock:
            rows = self._conn.execute(
                "SELECT id, direction, peer, body, ts, status FROM messages WHERE peer = ? ORDER BY ts ASC, id ASC",
                (peer_n,),
            ).fetchall()
        return [self._row_message(r) for r in rows]

    def list_calls(self) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT id, direction, number, started_at, duration_sec, switchboard_mode, session_id, tap_summary "
                "FROM calls ORDER BY started_at DESC"
            ).fetchall()
        return [
            {
                "id": r["id"],
                "direction": r["direction"],
                "number": r["number"],
                "startedAt": r["started_at"],
                "durationSec": r["duration_sec"],
                "switchboardMode": r["switchboard_mode"],
                "sessionId": r["session_id"],
                "tapSummary": r["tap_summary"],
            }
            for r in rows
        ]

    def _insert_message(self, rec: dict[str, Any]) -> None:
        with self._lock:
            self._conn.execute(
                "INSERT INTO messages(id, direction, peer, body, ts, status) VALUES (?, ?, ?, ?, ?, ?)",
                (rec["id"], rec["direction"], rec["peer"], rec["body"], rec["ts"], rec["status"]),
            )
            self._conn.commit()

    @staticmethod
    def _row_message(row: sqlite3.Row) -> dict[str, Any]:
        return {
            "id": row["id"],
            "direction": row["direction"],
            "peer": row["peer"],
            "body": row["body"],
            "ts": row["ts"],
            "status": row["status"],
        }


_bus = EventBus()
_store: Optional[PortalStore] = None
_store_lock = threading.Lock()


def get_bus() -> EventBus:
    return _bus


def db_path_from_env() -> Path:
    raw = os.environ.get("GSM2COMPUTER_PORTAL_DB")
    if raw:
        return Path(raw)
    return HUB_DIR / "portal.sqlite"


def get_store() -> PortalStore:
    global _store
    with _store_lock:
        if _store is None:
            _store = PortalStore(db_path_from_env())
        return _store


def reset_for_tests(path: Optional[Path] = None) -> PortalStore:
    global _store
    with _store_lock:
        if _store is not None:
            try:
                _store.close()
            except Exception:
                pass
            _store = None
        _bus.clear()
        target = Path(path) if path is not None else db_path_from_env()
        _store = PortalStore(target)
        return _store
