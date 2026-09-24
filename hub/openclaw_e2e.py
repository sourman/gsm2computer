#!/usr/bin/env python3
"""OpenClaw end-to-end self-test + self-heal ladder (runs on safwat-cup).

Connects like the Pixel (Bearer token, /e2e-test, client.audio + PCM append),
injects a short known TTS clip, and requires OpenClaw to reply *without* a
forced ``response.create``. Pass = unforced trigger + non-silent openclaw-spk
and gsm-downlink + non-empty transcript.

On failure, a one-shot ladder runs while idle:
  (a) soft reload Control UI Talk page
  (b) restart openclaw-gateway
  (c) restart talk-chromium
  (d) setup-audio-bus.sh (+ sink-capture sanity)
then re-tests after each step. All steps failing → alert webhook (wakes Cup).

A live Pixel call always wins: hub preempts /e2e-test; this client aborts if
/health shows a non-e2e busy slot.
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import json
import logging
import os
import subprocess
import sys
import time
import wave
from dataclasses import dataclass, field, asdict
from datetime import datetime, timezone
from logging.handlers import RotatingFileHandler
from pathlib import Path
from typing import Any, Optional
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

import websockets
from websockets.exceptions import ConnectionClosed

from alert_webhook import post_system_alert

LOG = logging.getLogger("gsm2computer-e2e")

HUB_HTTP = os.environ.get("GSM2COMPUTER_E2E_HUB", "http://127.0.0.1:8787")
# Cup binds the hub on the Tailscale IP; prefer explicit env / health URL.
if HUB_HTTP.endswith("/"):
    HUB_HTTP = HUB_HTTP[:-1]
CDP_LIST = os.environ.get("GSM2COMPUTER_TALK_CDP_LIST", "http://127.0.0.1:9222/json/list")
PROMPT = os.environ.get(
    "GSM2COMPUTER_E2E_PROMPT",
    "Hi, this is a test. What is two plus two?",
)
PEAK_MIN = float(os.environ.get("GSM2COMPUTER_E2E_PEAK_MIN", "0.02"))
BUDGET_S = float(os.environ.get("GSM2COMPUTER_E2E_BUDGET_S", "28"))
PCM_RATE = int(os.environ.get("GSM2COMPUTER_E2E_PCM_RATE", "48000"))
GAIN = float(os.environ.get("GSM2COMPUTER_E2E_GAIN", "4.0"))
LOG_PATH = Path(
    os.environ.get(
        "GSM2COMPUTER_E2E_LOG",
        str(Path.home() / "gsm2computer-e2e" / "e2e.log"),
    )
)
STATE_PATH = Path(
    os.environ.get(
        "GSM2COMPUTER_E2E_STATE",
        str(Path.home() / "gsm2computer-e2e" / "last-run.json"),
    )
)
HUB_DIR = Path(os.environ.get("GSM2COMPUTER_HUB_DIR", Path(__file__).resolve().parent))


@dataclass
class E2EResult:
    ok: bool
    step: str
    latency_s: float
    spk_peak: float = 0.0
    downlink_peak: float = 0.0
    mic_peak: float = 0.0
    transcript: str = ""
    triggered_unforced: bool = False
    forced_used: bool = False
    aborted_for_real_call: bool = False
    tap_id: Optional[str] = None
    error: Optional[str] = None
    dc_types: list[str] = field(default_factory=list)

    def summary(self) -> str:
        status = "PASS" if self.ok else "FAIL"
        return (
            f"{status} step={self.step} lat={self.latency_s:.1f}s "
            f"spk={self.spk_peak:.3f} dl={self.downlink_peak:.3f} "
            f"unforced={self.triggered_unforced} transcript={self.transcript[:80]!r} "
            f"err={self.error!r}"
        )


def setup_logging() -> None:
    LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
    LOG.setLevel(logging.INFO)
    fmt = logging.Formatter("%(asctime)s %(levelname)s %(message)s")
    sh = logging.StreamHandler(sys.stdout)
    sh.setFormatter(fmt)
    LOG.handlers.clear()
    LOG.addHandler(sh)
    fh = RotatingFileHandler(LOG_PATH, maxBytes=2_000_000, backupCount=8)
    fh.setFormatter(fmt)
    LOG.addHandler(fh)


def _http_json(method: str, url: str, body: Optional[bytes] = None, timeout: float = 8.0) -> Any:
    req = Request(url, data=body, method=method)
    if body is not None:
        req.add_header("Content-Type", "application/json")
    with urlopen(req, timeout=timeout) as resp:
        raw = resp.read()
    if not raw:
        return None
    return json.loads(raw.decode("utf-8"))


def health() -> dict[str, Any]:
    return _http_json("GET", f"{HUB_HTTP}/health")


def line_idle(h: Optional[dict[str, Any]] = None) -> bool:
    h = h or health()
    call = h.get("call") or {}
    talk = h.get("talk") or {}
    if call.get("busy"):
        return False
    if talk.get("talk_active"):
        return False
    return True


def real_call_holding(h: Optional[dict[str, Any]] = None) -> bool:
    """True when a non-e2e call owns the slot (Pixel / production)."""
    h = h or health()
    call = h.get("call") or {}
    if not call.get("busy"):
        return False
    return not bool(call.get("e2e"))


def get_token() -> str:
    data = _http_json("POST", f"{HUB_HTTP}/token", body=b"")
    if not isinstance(data, dict) or not data.get("value"):
        raise RuntimeError(f"token failed: {data!r}")
    return str(data["value"])


def synthesize_prompt_pcm(text: str, rate: int = PCM_RATE) -> bytes:
    """espeak-ng → wav → mono s16le at ``rate``, with gain for VAD."""
    out_dir = Path(os.environ.get("TMPDIR", "/tmp")) / "gsm2-e2e"
    out_dir.mkdir(parents=True, exist_ok=True)
    wav_path = out_dir / "prompt.wav"
    pcm_path = out_dir / "prompt.s16"
    subprocess.run(
        [
            "espeak-ng",
            "-v",
            "en-us",
            "-s",
            "150",
            "-w",
            str(wav_path),
            text,
        ],
        check=True,
        capture_output=True,
    )
    subprocess.run(
        [
            "ffmpeg",
            "-nostdin",
            "-y",
            "-i",
            str(wav_path),
            "-ac",
            "1",
            "-ar",
            str(rate),
            "-f",
            "s16le",
            "-acodec",
            "pcm_s16le",
            str(pcm_path),
        ],
        check=True,
        capture_output=True,
    )
    raw = pcm_path.read_bytes()
    # Apply gain (clip).
    import array

    samples = array.array("h")
    samples.frombytes(raw)
    g = GAIN
    for i, s in enumerate(samples):
        v = int(s * g)
        if v > 32767:
            v = 32767
        elif v < -32768:
            v = -32768
        samples[i] = v
    return samples.tobytes()


def _pcm_chunks(pcm: bytes, rate: int, frame_ms: int = 20) -> list[bytes]:
    frame = rate * 2 * frame_ms // 1000
    if frame <= 0:
        frame = rate * 2 // 50
    return [pcm[i : i + frame] for i in range(0, len(pcm), frame) if pcm[i : i + frame]]


async def _cdp_call(ws, nid_box: list[int], method: str, params: Optional[dict] = None) -> dict:
    nid_box[0] += 1
    my = nid_box[0]
    await ws.send(json.dumps({"id": my, "method": method, "params": params or {}}))
    while True:
        msg = json.loads(await asyncio.wait_for(ws.recv(), timeout=30))
        if msg.get("id") == my:
            return msg


async def _cdp_page_ws() -> str:
    targets = _http_json("GET", CDP_LIST)
    if not isinstance(targets, list):
        raise RuntimeError("cdp list failed")
    page = next(
        (
            t
            for t in targets
            if t.get("type") == "page" and "chat" in (t.get("url") or "")
        ),
        None,
    )
    if not page or not page.get("webSocketDebuggerUrl"):
        raise RuntimeError("no Control UI chat page on CDP")
    return str(page["webSocketDebuggerUrl"])


DC_HOOK_JS = r"""
(() => {
  window.__gsm2E2E = {types: [], transcripts: [], responseCreated: 0, forced: false};
  const push = (j) => {
    try {
      const t = j.type || "?";
      window.__gsm2E2E.types.push(t);
      if (t === "response.created") window.__gsm2E2E.responseCreated += 1;
      if (t === "response.output_audio_transcript.delta" && j.delta)
        window.__gsm2E2E.transcripts.push(String(j.delta));
      if (t === "response.output_audio_transcript.done" && j.transcript)
        window.__gsm2E2E.transcripts.push(String(j.transcript));
    } catch (e) {}
  };
  const attach = (ch) => {
    if (!ch || ch.__gsm2E2EAttached) return;
    ch.__gsm2E2EAttached = true;
    window.__gsm2E2E.dc = ch;
    ch.addEventListener("message", (ev) => {
      if (typeof ev.data !== "string") return;
      try { push(JSON.parse(ev.data)); } catch (e) {}
    });
  };
  const Orig = window.RTCPeerConnection;
  if (!Orig || window.__gsm2E2EHooked) {
    // still attach to any existing DC
    if (window.__gsm2Dc) attach(window.__gsm2Dc);
    return "already";
  }
  function Wrapped(...args) {
    const pc = new Orig(...args);
    const cdc = pc.createDataChannel.bind(pc);
    pc.createDataChannel = (...x) => {
      const ch = cdc(...x);
      attach(ch);
      return ch;
    };
    pc.addEventListener("datachannel", (ev) => attach(ev.channel));
    return pc;
  }
  Wrapped.prototype = Orig.prototype;
  Object.setPrototypeOf(Wrapped, Orig);
  window.RTCPeerConnection = Wrapped;
  window.__gsm2E2EHooked = true;
  return "hooked";
})()
"""

DC_SNAP_JS = r"""
(() => {
  const e = window.__gsm2E2E || {};
  return {
    types: (e.types || []).slice(-80),
    transcript: (e.transcripts || []).join(""),
    responseCreated: e.responseCreated || 0,
    dcState: e.dc ? e.dc.readyState : null,
  };
})()
"""


async def install_dc_hook() -> None:
    url = await _cdp_page_ws()
    async with websockets.connect(url, max_size=50_000_000) as ws:
        nid = [0]
        await _cdp_call(ws, nid, "Runtime.enable")
        r = await _cdp_call(
            ws, nid, "Runtime.evaluate", {"expression": DC_HOOK_JS, "returnByValue": True}
        )
        LOG.info("cdp dc hook: %s", (r.get("result") or {}).get("result", {}).get("value"))


async def snap_dc() -> dict[str, Any]:
    url = await _cdp_page_ws()
    async with websockets.connect(url, max_size=50_000_000) as ws:
        nid = [0]
        await _cdp_call(ws, nid, "Runtime.enable")
        r = await _cdp_call(
            ws, nid, "Runtime.evaluate", {"expression": DC_SNAP_JS, "returnByValue": True}
        )
        val = ((r.get("result") or {}).get("result") or {}).get("value") or {}
        return val if isinstance(val, dict) else {}


def _ws_url() -> str:
    base = HUB_HTTP.replace("https://", "wss://").replace("http://", "ws://")
    return f"{base}/e2e-test"


async def run_once(step: str = "initial", *, allow_force: bool = False) -> E2EResult:
    """Run one e2e attempt. Never sends response.create unless allow_force (debug only)."""
    t0 = time.monotonic()
    result = E2EResult(ok=False, step=step, latency_s=0.0)
    try:
        h0 = health()
        if real_call_holding(h0):
            result.aborted_for_real_call = True
            result.error = "real call in progress"
            result.latency_s = time.monotonic() - t0
            return result
        if not line_idle(h0) and not (h0.get("call") or {}).get("e2e"):
            # busy with something else
            result.error = f"line not idle: {h0.get('call')}"
            result.latency_s = time.monotonic() - t0
            return result

        pcm = synthesize_prompt_pcm(PROMPT, PCM_RATE)
        chunks = _pcm_chunks(pcm, PCM_RATE)
        await install_dc_hook()
        token = get_token()
        headers = {"Authorization": f"Bearer {token}"}
        async with websockets.connect(
            _ws_url(), additional_headers=headers, max_size=8_000_000, open_timeout=10
        ) as ws:
            # session.updated
            while time.monotonic() - t0 < 12:
                if real_call_holding():
                    result.aborted_for_real_call = True
                    result.error = "preempted before session"
                    break
                raw = await asyncio.wait_for(ws.recv(), timeout=12)
                if isinstance(raw, (bytes, bytearray)):
                    continue
                msg = json.loads(raw)
                if msg.get("type") == "session.updated":
                    sess = msg.get("session") or {}
                    if not sess.get("e2e"):
                        result.error = "hub did not tag session e2e"
                    LOG.info("session.updated e2e=%s", sess.get("e2e"))
                    break
            if result.aborted_for_real_call:
                result.latency_s = time.monotonic() - t0
                return result

            # wait webrtc
            for _ in range(40):
                if real_call_holding():
                    result.aborted_for_real_call = True
                    result.error = "preempted waiting webrtc"
                    break
                h = health()
                if (h.get("talk") or {}).get("webrtc_connected"):
                    break
                await asyncio.sleep(0.25)
            if result.aborted_for_real_call:
                result.latency_s = time.monotonic() - t0
                return result

            await ws.send(
                json.dumps(
                    {
                        "type": "client.audio",
                        "input": {"format": "audio/pcm", "rate": PCM_RATE},
                        "output": {"format": "audio/pcm", "rate": PCM_RATE},
                    }
                )
            )
            # uplink TTS
            for chunk in chunks:
                if real_call_holding():
                    result.aborted_for_real_call = True
                    result.error = "preempted during uplink"
                    break
                await ws.send(
                    json.dumps(
                        {
                            "type": "input_audio_buffer.append",
                            "audio": base64.b64encode(chunk).decode("ascii"),
                            "format": "audio/pcm",
                            "rate": PCM_RATE,
                        }
                    )
                )
                await asyncio.sleep(0.02)
            if result.aborted_for_real_call:
                result.latency_s = time.monotonic() - t0
                return result

            # linger for VAD end-of-speech + reply (no force)
            deadline = min(t0 + BUDGET_S - 3.0, time.monotonic() + 16.0)
            triggered = False
            transcript = ""
            types: list[str] = []
            while time.monotonic() < deadline:
                if real_call_holding():
                    result.aborted_for_real_call = True
                    result.error = "preempted while waiting reply"
                    break
                snap = await snap_dc()
                types = list(snap.get("types") or [])
                transcript = str(snap.get("transcript") or "")
                if int(snap.get("responseCreated") or 0) > 0:
                    triggered = True
                    # give audio a moment to hit the bus
                    await asyncio.sleep(2.0)
                    break
                await asyncio.sleep(0.4)

            if allow_force and not triggered and not result.aborted_for_real_call:
                result.forced_used = True
                result.error = "unforced trigger failed (force not used in prod pass)"

            # close ws so tap finalizes
            await ws.close()

        await asyncio.sleep(1.2)
        h = health()
        tap = h.get("last_call_tap") or {}
        streams = tap.get("streams") or {}
        result.tap_id = tap.get("id")
        result.spk_peak = float((streams.get("openclaw-spk-48k-stereo") or {}).get("peak") or 0.0)
        result.downlink_peak = float((streams.get("gsm-downlink-8k-mono") or {}).get("peak") or 0.0)
        result.mic_peak = float((streams.get("openclaw-mic-48k-stereo") or {}).get("peak") or 0.0)
        result.transcript = transcript.strip()
        result.triggered_unforced = triggered and not result.forced_used
        result.dc_types = types[-40:]
        result.latency_s = time.monotonic() - t0

        if result.aborted_for_real_call:
            return result

        reasons = []
        if not result.triggered_unforced:
            reasons.append("no unforced OpenClaw response.created")
        if result.spk_peak < PEAK_MIN:
            reasons.append(f"openclaw-spk peak {result.spk_peak:.4f} < {PEAK_MIN}")
        if result.downlink_peak < PEAK_MIN:
            reasons.append(f"gsm-downlink peak {result.downlink_peak:.4f} < {PEAK_MIN}")
        if not result.transcript:
            reasons.append("empty transcript")
        if reasons:
            result.error = "; ".join(reasons)
            result.ok = False
        else:
            result.ok = True
            result.error = None
        return result
    except Exception as exc:
        result.error = f"{type(exc).__name__}: {exc}"
        result.latency_s = time.monotonic() - t0
        LOG.exception("e2e run failed")
        return result


async def soft_reload_talk() -> None:
    LOG.info("heal(a): soft reload Control UI")
    # Prefer hub's talk_chromium API when importable; else CDP reload.
    sys.path.insert(0, str(HUB_DIR))
    from talk_chromium import get_talk_ui  # type: ignore

    ui = get_talk_ui()
    await ui.reload_control_ui()


async def _systemctl_restart(unit: str) -> None:
    if not line_idle():
        raise RuntimeError(f"refusing to restart {unit}: line not idle")
    LOG.info("systemctl --user restart %s", unit)
    subprocess.run(
        ["systemctl", "--user", "restart", unit],
        check=True,
        capture_output=True,
        text=True,
    )
    # wait healthy
    deadline = time.monotonic() + 45.0
    while time.monotonic() < deadline:
        await asyncio.sleep(1.0)
        try:
            h = health()
        except Exception:
            continue
        talk = h.get("talk") or {}
        if unit.startswith("talk-chromium"):
            if talk.get("cdp") and line_idle(h):
                return
        elif unit.startswith("openclaw-gateway"):
            # gateway: just ensure hub still idle and process active
            st = subprocess.run(
                ["systemctl", "--user", "is-active", unit],
                capture_output=True,
                text=True,
            )
            if st.stdout.strip() == "active" and line_idle(h):
                await asyncio.sleep(2.0)
                return
        else:
            if line_idle(h):
                return
    raise RuntimeError(f"{unit} did not become healthy in time")


async def heal_restart_gateway() -> None:
    LOG.info("heal(b): restart openclaw-gateway")
    await _systemctl_restart("openclaw-gateway.service")


async def heal_restart_talk_chromium() -> None:
    LOG.info("heal(c): restart talk-chromium")
    await _systemctl_restart("talk-chromium.service")


async def heal_audio_bus() -> None:
    LOG.info("heal(d): setup-audio-bus + sink-capture check")
    if not line_idle():
        raise RuntimeError("refusing audio bus heal: line not idle")
    script = HUB_DIR / "setup-audio-bus.sh"
    subprocess.run(["bash", str(script)], check=True, capture_output=True, text=True)
    # quick sink-capture sanity on openclaw_bus
    tone = Path("/tmp/gsm2-e2e-tone.wav")
    subprocess.run(
        [
            "ffmpeg",
            "-nostdin",
            "-y",
            "-f",
            "lavfi",
            "-i",
            "sine=f=440:d=1",
            "-ar",
            "48000",
            "-ac",
            "2",
            str(tone),
        ],
        check=True,
        capture_output=True,
    )
    play = subprocess.Popen(["paplay", "--device=openclaw_bus", str(tone)])
    await asyncio.sleep(0.2)
    env = {**os.environ, "PIPEWIRE_PROPS": "stream.capture.sink=true"}
    rec = Path("/tmp/gsm2-e2e-sinkcap.wav")
    try:
        subprocess.run(
            ["timeout", "0.8", "pw-record", "--target", "openclaw_bus", str(rec)],
            env=env,
            check=False,
            capture_output=True,
        )
    finally:
        play.wait(timeout=5)
    if not rec.is_file() or rec.stat().st_size < 1000:
        raise RuntimeError("sink-capture record missing/empty")
    with wave.open(str(rec), "rb") as w:
        frames = w.readframes(w.getnframes())
    import array

    samples = array.array("h")
    samples.frombytes(frames)
    peak = max(abs(x) for x in samples) / 32768.0 if samples else 0.0
    LOG.info("sink-capture peak=%.4f", peak)
    if peak < 0.01:
        raise RuntimeError(f"openclaw_bus sink-capture still silent peak={peak}")


HEAL_STEPS = [
    ("soft_reload", soft_reload_talk),
    ("restart_gateway", heal_restart_gateway),
    ("restart_talk_chromium", heal_restart_talk_chromium),
    ("audio_bus", heal_audio_bus),
]


def persist(result: E2EResult, extra: Optional[dict] = None) -> None:
    STATE_PATH.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "ts": datetime.now(timezone.utc).isoformat(),
        "result": asdict(result),
        "extra": extra or {},
    }
    STATE_PATH.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    LOG.info("e2e %s", result.summary())


async def run_with_ladder(*, simulate_fail_check: bool = False) -> E2EResult:
    if real_call_holding():
        r = E2EResult(
            ok=False,
            step="skipped",
            latency_s=0.0,
            aborted_for_real_call=True,
            error="real call in progress",
        )
        persist(r)
        return r
    if not line_idle():
        r = E2EResult(ok=False, step="skipped", latency_s=0.0, error="line not idle")
        persist(r)
        return r

    result = await run_once("initial")
    if simulate_fail_check and result.ok:
        # Force the ladder to exercise by treating the first pass as fail once.
        LOG.warning("simulate_fail_check: flipping initial PASS to FAIL to exercise ladder")
        result.ok = False
        result.error = (result.error or "") + " simulated_fail_for_ladder"
    if result.ok or result.aborted_for_real_call:
        persist(result)
        return result

    used: set[str] = set()
    for name, fn in HEAL_STEPS:
        if name in used:
            continue
        if real_call_holding() or not line_idle():
            result.error = f"aborted ladder at {name}: line busy"
            result.aborted_for_real_call = real_call_holding()
            persist(result, {"ladder_stop": name})
            return result
        try:
            await fn()
            used.add(name)
        except Exception as exc:
            LOG.warning("heal step %s failed: %s", name, exc)
            used.add(name)
            continue
        await asyncio.sleep(1.0)
        if not line_idle():
            result.error = f"line busy after heal {name}"
            persist(result)
            return result
        result = await run_once(name)
        if result.ok or result.aborted_for_real_call:
            persist(result, {"healed_by": name})
            return result

    persist(result, {"ladder_exhausted": True})
    post_system_alert(
        "OpenClaw e2e FAILED after heal ladder: "
        f"spk={result.spk_peak:.3f} dl={result.downlink_peak:.3f} "
        f"unforced={result.triggered_unforced} "
        f"transcript={result.transcript[:60]!r} err={result.error}"
    )
    return result


async def verify_yield() -> dict[str, Any]:
    """Hold /e2e-test briefly, then connect a 'real' path and expect preempt."""
    if not line_idle():
        return {"ok": False, "error": "not idle"}
    token = get_token()
    headers = {"Authorization": f"Bearer {token}"}
    out: dict[str, Any] = {"ok": False}
    async with websockets.connect(
        _ws_url(), additional_headers=headers, max_size=8_000_000, open_timeout=10
    ) as e2e_ws:
        # drain session.updated
        await asyncio.wait_for(e2e_ws.recv(), timeout=15)
        h = health()
        if not (h.get("call") or {}).get("e2e"):
            out["error"] = f"expected e2e busy, got {h.get('call')}"
            return out
        out["e2e_held"] = True
        # second connection as real path /
        token2 = get_token()
        headers2 = {"Authorization": f"Bearer {token2}"}
        real_url = HUB_HTTP.replace("https://", "wss://").replace("http://", "ws://") + "/"
        try:
            async with websockets.connect(
                real_url, additional_headers=headers2, max_size=8_000_000, open_timeout=15
            ) as real_ws:
                msg = json.loads(await asyncio.wait_for(real_ws.recv(), timeout=20))
                out["real_session"] = msg.get("type")
                out["real_accepted"] = msg.get("type") == "session.updated"
                # stop talk quickly
                await real_ws.close()
        except Exception as exc:
            out["real_error"] = f"{type(exc).__name__}: {exc}"
        # e2e should have been aborted
        await asyncio.sleep(0.5)
        try:
            await e2e_ws.recv()
        except ConnectionClosed:
            out["e2e_closed"] = True
        except Exception:
            out["e2e_closed"] = False
    await asyncio.sleep(1.0)
    out["idle_after"] = line_idle()
    out["ok"] = bool(out.get("real_accepted")) and bool(out.get("idle_after"))
    return out


async def simulate_gateway_failure_ladder() -> E2EResult:
    """Stop gateway briefly while idle, then run ladder (should heal via restart_gateway)."""
    if not line_idle():
        return E2EResult(ok=False, step="sim", latency_s=0.0, error="not idle")
    LOG.warning("simulate: stopping openclaw-gateway to force ladder heal")
    subprocess.run(
        ["systemctl", "--user", "stop", "openclaw-gateway.service"],
        check=True,
        capture_output=True,
        text=True,
    )
    await asyncio.sleep(1.0)
    try:
        # initial run should fail (no OpenClaw); ladder should restart gateway
        return await run_with_ladder()
    finally:
        # ensure gateway is up even if ladder skipped
        st = subprocess.run(
            ["systemctl", "--user", "is-active", "openclaw-gateway.service"],
            capture_output=True,
            text=True,
        )
        if st.stdout.strip() != "active":
            LOG.warning("simulate cleanup: starting openclaw-gateway")
            subprocess.run(
                ["systemctl", "--user", "start", "openclaw-gateway.service"],
                check=False,
            )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--once", action="store_true", help="single attempt, no ladder")
    parser.add_argument("--ladder", action="store_true", help="run with self-heal ladder")
    parser.add_argument("--verify-yield", action="store_true", help="preempt e2e for real path")
    parser.add_argument(
        "--simulate-gateway-fail",
        action="store_true",
        help="stop gateway then run ladder (idle only)",
    )
    parser.add_argument(
        "--hub",
        default=None,
        help="hub http base (default env GSM2COMPUTER_E2E_HUB or 127.0.0.1:8787)",
    )
    args = parser.parse_args()
    global HUB_HTTP
    if args.hub:
        HUB_HTTP = args.hub.rstrip("/")
    # Cup default if localhost health fails
    setup_logging()
    try:
        health()
    except Exception:
        alt = os.environ.get("GSM2COMPUTER_HUB_URL", "http://100.119.126.42:8787")
        LOG.warning("hub %s unreachable, trying %s", HUB_HTTP, alt)
        HUB_HTTP = alt.rstrip("/")

    if args.verify_yield:
        out = asyncio.run(verify_yield())
        LOG.info("yield verify: %s", json.dumps(out))
        return 0 if out.get("ok") else 2
    if args.simulate_gateway_fail:
        r = asyncio.run(simulate_gateway_failure_ladder())
        return 0 if r.ok else 1
    if args.once:
        r = asyncio.run(run_once("once"))
        persist(r)
        return 0 if r.ok else 1
    # default: ladder
    r = asyncio.run(run_with_ladder())
    return 0 if r.ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
