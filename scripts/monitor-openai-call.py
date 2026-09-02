#!/usr/bin/env python3
"""Sideband WebSocket monitor for an active OpenAI Realtime SIP call (Bearer auth)."""
import json
import os
import sys
import time

try:
    import websocket
except ImportError:
    print("pip install websocket-client", file=sys.stderr)
    sys.exit(1)

def load_key():
    key = os.environ.get("OPENAI_API_KEY", "")
    if key:
        return key.strip()
    env_path = os.path.join(os.path.dirname(__file__), "..", ".env")
    if os.path.isfile(env_path):
        for line in open(env_path):
            if line.startswith("OPENAI_API_KEY="):
                return line.split("=", 1)[1].strip()
    return ""

def main():
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} rtc_u1_...", file=sys.stderr)
        sys.exit(1)
    call_id = sys.argv[1]
    api_key = load_key()
    if not api_key:
        print("OPENAI_API_KEY not set", file=sys.stderr)
        sys.exit(1)

    url = f"wss://api.openai.com/v1/realtime?call_id={call_id}"
    print(f"Connecting sideband WS for {call_id}...")

    events = []

    def on_open(ws):
        print("WS open")
        ws.send(json.dumps({
            "type": "response.create",
            "response": {
                "instructions": "Greet briefly, then keep the conversation going. Ask the caller a question."
            },
        }))

    def on_message(ws, message):
        try:
            msg = json.loads(message)
            t = msg.get("type", "")
            if t.startswith("response.") or "transcription" in t or t.startswith("input_audio"):
                print(f"EVT {t}: {json.dumps(msg)[:300]}")
            events.append(t)
        except Exception:
            print(message[:200])

    def on_error(ws, error):
        print(f"WS error: {error}")

    def on_close(ws, code, reason):
        print(f"WS closed code={code} reason={reason}")

    ws = websocket.WebSocketApp(
        url,
        header=[f"Authorization: Bearer {api_key}"],
        on_open=on_open,
        on_message=on_message,
        on_error=on_error,
        on_close=on_close,
    )
    # Block for up to 90s
    end = time.time() + 90
    import threading
    t = threading.Thread(target=ws.run_forever, kwargs={"ping_interval": 20, "ping_timeout": 10})
    t.daemon = True
    t.start()
    while t.is_alive() and time.time() < end:
        time.sleep(1)
    try:
        ws.close()
    except Exception:
        pass
    print(f"Done. event_types={len(events)}")

if __name__ == "__main__":
    main()
