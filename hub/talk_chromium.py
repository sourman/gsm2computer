#!/usr/bin/env python3
"""OpenClaw Control UI Talk supervisor (Chromium + CDP + WebRTC).

Dedicated Chromium profile runs the same Control UI Talk path that already
sounds right in a human browser. The hub splices phone μ-law through
phone_uplink → Chromium mic and Chromium sink → openclaw_bus.

This is *not* gateway-relay talk.session.create.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import signal
import socket
import time
from pathlib import Path
from typing import Any, Optional
from urllib.error import URLError
from urllib.request import Request, urlopen

from pipewire_target import TOOL_TIMEOUT_S, _run_captured

LOG = logging.getLogger("openclaw-talk-ui")

# Cup is the live Talk host. Re-read env on every call so a process that
# imported this module without GSM2COMPUTER_TALK_UI_URL cannot navigate to the
# stale hub.mining-ling.ts.net default and wedge CDP against the wrong gateway.
_DEFAULT_CONTROL_UI_URL = "https://hub-cup.mining-ling.ts.net/chat/main"


def control_ui_url() -> str:
    return os.environ.get("GSM2COMPUTER_TALK_UI_URL", _DEFAULT_CONTROL_UI_URL) or _DEFAULT_CONTROL_UI_URL


# Back-compat alias for callers that still read the module constant.
CONTROL_UI_URL = control_ui_url()
CDP_PORT = int(os.environ.get("GSM2COMPUTER_TALK_CDP_PORT", "9222"))
CDP_HOST = os.environ.get("GSM2COMPUTER_TALK_CDP_HOST", "127.0.0.1")
USER_DATA_DIR = Path(
    os.environ.get(
        "GSM2COMPUTER_TALK_USER_DATA_DIR",
        str(Path.home() / ".config" / "chromium-openclaw-talk"),
    )
)
PHONE_UPLINK_SINK = os.environ.get("GSM2COMPUTER_PHONE_UPLINK_SINK", "phone_uplink")
PHONE_UPLINK_MONITOR = os.environ.get(
    "GSM2COMPUTER_PHONE_UPLINK_MONITOR", f"{PHONE_UPLINK_SINK}.monitor"
)
OPENCLAW_BUS = os.environ.get("GSM2COMPUTER_OPENCLAW_BUS", "openclaw_bus")
CHROMIUM_BIN = os.environ.get("GSM2COMPUTER_CHROMIUM_BIN", "chromium-browser")
DISPLAY = os.environ.get("GSM2COMPUTER_TALK_DISPLAY", "")
START_TIMEOUT_S = float(os.environ.get("GSM2COMPUTER_TALK_START_TIMEOUT", "45"))
WEBRTC_TIMEOUT_S = float(os.environ.get("GSM2COMPUTER_TALK_WEBRTC_TIMEOUT", "25"))
PAGE_TIMEOUT_S = float(os.environ.get("GSM2COMPUTER_TALK_PAGE_TIMEOUT", "30"))
TALK_ROLLOVER_S = float(os.environ.get("GSM2COMPUTER_TALK_ROLLOVER_S", "1500"))

HOOK_JS = r"""
(() => {
  const HOOK_VER = 3;
  // Always (re)apply mic/APM patches when version advances; only wrap PC once.
  if (!window.__gsm2TalkPcs) window.__gsm2TalkPcs = [];
  if (!window.__gsm2PcWrapped) {
    const Orig = window.RTCPeerConnection;
    if (!Orig) return "no-rtc";
    function Wrapped(...args) {
      const pc = new Orig(...args);
      window.__gsm2TalkPcs.push(pc);
      return pc;
    }
    Wrapped.prototype = Orig.prototype;
    Object.setPrototypeOf(Wrapped, Orig);
    window.RTCPeerConnection = Wrapped;
    window.__gsm2PcWrapped = true;
  }
  // Virtual null-sink mic (phone_uplink.monitor): WebRTC APM must stay OFF.
  // AGC drifts Pulse monitor volumes; NS/EC on telephony audio creates the
  // "stuck in a bottle" / underwater sound and attenuates speech (~8 dB seen
  // on openclaw-mic vs gsm-uplink taps). Mix-minus is PipeWire topology, not AEC.
  try {
    const md = navigator.mediaDevices;
    const apmOff = {
      autoGainControl: false,
      echoCancellation: false,
      noiseSuppression: false,
      googAutoGainControl: false,
      googEchoCancellation: false,
      googNoiseSuppression: false,
      googHighpassFilter: false,
      googTypingNoiseDetection: false,
      googAudioMirroring: false,
    };
    // Prefer sink-capture virtual mic (openclaw_phone_mic). phone_uplink.monitor
    // is silent on current PipeWire (monitor.passthrough); default deviceId also
    // yields media-source energy 0 so OpenClaw never VAD-triggers.
    const MIC_LABEL_RE = /OpenClaw_Phone_Mic|openclaw_phone_mic/i;
    const MIC_FALLBACK_RE = /OpenClaw_Mic|phone_uplink/i;
    const pickMicId = async () => {
      try {
        const devs = await md.enumerateDevices();
        const inputs = devs.filter((d) => d.kind === "audioinput");
        const hit =
          inputs.find((d) => MIC_LABEL_RE.test(d.label || "")) ||
          inputs.find((d) => MIC_FALLBACK_RE.test(d.label || ""));
        return hit ? hit.deviceId : null;
      } catch (e) {
        return null;
      }
    };
    const forceAudio = async (audio) => {
      const micId = await pickMicId();
      const extra = micId ? {deviceId: {exact: micId}} : {};
      if (audio === undefined || audio === true) return {...apmOff, ...extra};
      if (audio && typeof audio === "object") return {...audio, ...apmOff, ...extra};
      return audio;
    };
    // Versioned re-patch so hot hub deploys replace a stale gUM wrapper.
    const GUM_VER = 3;
    if (md && typeof md.getUserMedia === "function" && window.__gsm2GumPatchVer !== GUM_VER) {
      const origGum = (window.__gsm2OrigGum || md.getUserMedia).bind(md);
      window.__gsm2OrigGum = window.__gsm2OrigGum || md.getUserMedia.bind(md);
      md.getUserMedia = async (constraints) => {
        const c = constraints ? {...constraints} : {};
        c.audio = await forceAudio(c.audio);
        return origGum(c);
      };
      const prev = window.__gsm2GumPatchVer;
      window.__gsm2GumPatchVer = GUM_VER;
      window.__gsm2GumPatched = true;
      // Prior Talk sessions may still hold a silent deviceId=default track.
      if (typeof prev === "number" && prev !== GUM_VER) window.__gsm2NeedsFreshGum = true;
    }
    // OpenClaw may tighten constraints after gUM; keep APM off.
    if (window.MediaStreamTrack && MediaStreamTrack.prototype.applyConstraints && !window.__gsm2ApplyPatched) {
      const origApply = MediaStreamTrack.prototype.applyConstraints;
      MediaStreamTrack.prototype.applyConstraints = function(constraints) {
        const c = constraints ? {...constraints} : {};
        if (this.kind === "audio") {
          // applyConstraints uses flat keys, not {audio: {...}}
          Object.assign(c, apmOff);
        }
        return origApply.call(this, c);
      };
      window.__gsm2ApplyPatched = true;
    }
  } catch (e) {}
  window.__gsm2TalkHook = true;
  window.__gsm2TalkHookVer = HOOK_VER;
  return "hooked-ver-" + HOOK_VER + "-gum-" + (window.__gsm2GumPatchVer || 0);
})()
"""

PAGE_STATE_JS = r"""
(() => {
  const pcs = window.__gsm2TalkPcs || [];
  const talkBtn = document.querySelector(
    "button.chat-send-btn--talk-mode, .chat-talk-control button.chat-send-btn--voice, button.chat-send-btn--voice"
  );
  const live = document.querySelector(
    ".chat-talk-control--active, button.chat-send-btn--voice-live"
  );
  const tokenInput = document.querySelector(
    'input[type="password"], input[name="token"], input[autocomplete="off"][type="text"]'
  );
  const status = document.querySelector(
    ".agent-chat__voice-status, .agent-chat__talk-status-text, .agent-chat__talk-status"
  );
  const body = (document.body && document.body.innerText || "").slice(0, 800);
  const composer = document.querySelector(".agent-chat__input textarea, textarea");
  const composerVal = composer && "value" in composer ? String(composer.value || "") : "";
  return {
    url: location.href,
    title: document.title,
    hooked: !!window.__gsm2TalkHook,
    hasTalkButton: !!talkBtn,
    talkDisabled: !!(talkBtn && talkBtn.disabled),
    talkLabel: talkBtn ? (talkBtn.getAttribute("aria-label") || talkBtn.className) : null,
    live: !!live,
    hasTokenInput: !!tokenInput,
    statusText: status ? status.textContent.trim() : null,
    composerLen: composerVal.length,
    pcs: pcs.map((pc) => ({
      connection: pc.connectionState,
      ice: pc.iceConnectionState,
      signaling: pc.signalingState,
    })),
    snippet: body,
  };
})()
"""

CLICK_START_JS = r"""
(() => {
  const live = document.querySelector(
    ".chat-talk-control--active button, button.chat-send-btn--voice-live"
  );
  if (live) return {state: "already_active", className: live.className};
  const talkMode = document.querySelector("button.chat-send-btn--talk-mode");
  const voice = document.querySelector(
    ".chat-talk-control button.chat-send-btn--voice, button.chat-send-btn--voice"
  );
  const btn = talkMode || voice;
  if (!btn) return {state: "missing", snippet: (document.body.innerText || "").slice(0, 400)};
  if (btn.disabled) {
    return {state: "disabled", label: btn.getAttribute("aria-label"), className: btn.className};
  }
  // OpenClaw's Tap-to-talk handler sends the composer draft if it is
  // nonempty, and only starts Talk when the box is empty. Leftover
  // transcript from the previous call would otherwise steal the click.
  const composer = document.querySelector(".agent-chat__input textarea, textarea");
  const composerLen = composer && "value" in composer ? String(composer.value || "").length : 0;
  if (composer && composerLen) {
    composer.value = "";
    composer.dispatchEvent(new Event("input", {bubbles: true}));
    composer.dispatchEvent(new Event("change", {bubbles: true}));
  }
  btn.click();
  return {
    state: "clicked",
    label: btn.getAttribute("aria-label"),
    className: btn.className,
    composerLen,
    clearedComposer: composerLen > 0,
  };
})()
"""

CLICK_STOP_JS = r"""
(() => {
  const live = document.querySelector(
    ".chat-talk-control--active button, button.chat-send-btn--voice-live"
  );
  if (!live) return {state: "idle"};
  live.click();
  return {state: "clicked", label: live.getAttribute("aria-label")};
})()
"""


class TalkUiError(RuntimeError):
    """Control UI Talk is not up; hub must fail the call handshake."""


def _cdp_http(path: str, timeout_s: float = 2.0) -> Any:
    url = f"http://{CDP_HOST}:{CDP_PORT}{path}"
    req = Request(url, headers={"Host": f"{CDP_HOST}:{CDP_PORT}"})
    with urlopen(req, timeout=timeout_s) as resp:
        return json.loads(resp.read().decode("utf-8"))


def cdp_available(timeout_s: float = 0.8) -> bool:
    try:
        _cdp_http("/json/version", timeout_s=timeout_s)
        return True
    except (URLError, TimeoutError, json.JSONDecodeError, OSError, socket.timeout):
        return False


def _read_proc_environ(pid: int) -> dict[str, str]:
    env: dict[str, str] = {}
    try:
        raw = Path(f"/proc/{pid}/environ").read_bytes()
    except OSError:
        return env
    for item in raw.split(b"\0"):
        if not item or b"=" not in item:
            continue
        key, _, value = item.partition(b"=")
        try:
            env[key.decode("utf-8", "replace")] = value.decode("utf-8", "replace")
        except Exception:
            continue
    return env


def detect_display() -> tuple[str, str]:
    """Return (DISPLAY, XAUTHORITY) from the GNOME/DCV session if needed."""
    display = DISPLAY or os.environ.get("DISPLAY") or ""
    xauth = os.environ.get("XAUTHORITY") or ""
    if display and xauth:
        return display, xauth
    uid = os.getuid()
    try:
        proc = Path("/proc")
        for entry in proc.iterdir():
            if not entry.name.isdigit():
                continue
            try:
                stat = entry.joinpath("status").read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            if f"Uid:\t{uid}" not in stat:
                continue
            try:
                cmd = entry.joinpath("cmdline").read_bytes().replace(b"\0", b" ").decode("utf-8", "replace")
            except OSError:
                continue
            if "gnome-session-binary" not in cmd and "gdm-x-session" not in cmd:
                continue
            env = _read_proc_environ(int(entry.name))
            display = display or env.get("DISPLAY") or ""
            xauth = xauth or env.get("XAUTHORITY") or ""
            if display:
                break
    except OSError:
        pass
    return display or ":1", xauth or f"/run/user/{uid}/gdm/Xauthority"


def load_gateway_token() -> str:
    try:
        from openclaw_talk_bridge import load_gateway_token as _load
    except ImportError:
        _load = None
    if _load is not None:
        return _load()
    token = os.environ.get("OPENCLAW_GATEWAY_TOKEN", "").strip()
    if token:
        return token
    cfg_path = Path(os.environ.get("OPENCLAW_CONFIG_PATH", Path.home() / ".openclaw" / "openclaw.json"))
    cfg = json.loads(cfg_path.read_text(encoding="utf-8"))
    gateway = cfg.get("gateway") or {}
    auth = gateway.get("auth") or {}
    token = auth.get("token") or gateway.get("token")
    if not token:
        raise ValueError(f"no gateway token in {cfg_path}")
    return str(token)


def control_ui_url_with_token() -> str:
    base = control_ui_url()
    token = load_gateway_token()
    if "#token=" in base:
        return base
    return f"{base}#token={token}"


def chromium_launch_env() -> dict[str, str]:
    env = os.environ.copy()
    display, xauth = detect_display()
    env["DISPLAY"] = display
    if xauth:
        env["XAUTHORITY"] = xauth
    env.setdefault("XDG_RUNTIME_DIR", f"/run/user/{os.getuid()}")
    # Sink-capture virtual mic (see setup-audio-bus.sh); fall back to configured monitor.
    env["PULSE_SOURCE"] = os.environ.get(
        "GSM2COMPUTER_TALK_PULSE_SOURCE",
        os.environ.get("GSM2COMPUTER_PHONE_UPLINK_MONITOR", "openclaw_phone_mic"),
    )
    env["PULSE_SINK"] = OPENCLAW_BUS
    # Never let Chromium inherit the AWS virtual mic or gsm_bus as capture.
    env.pop("PULSE_LATENCY_MSEC", None)
    return env


def chromium_args(url: str) -> list[str]:
    USER_DATA_DIR.mkdir(parents=True, exist_ok=True)
    return [
        CHROMIUM_BIN,
        f"--user-data-dir={USER_DATA_DIR}",
        f"--remote-debugging-port={CDP_PORT}",
        "--remote-debugging-address=127.0.0.1",
        "--use-fake-ui-for-media-stream",
        "--autoplay-policy=no-user-gesture-required",
        "--no-first-run",
        "--no-default-browser-check",
        "--disable-session-crashed-bubble",
        "--hide-crash-restore-bubble",
        "--disable-infobars",
        "--ozone-platform=x11",
        # Reduce Chromium adjusting Pulse capture/source volumes via WebRTC APM.
        "--disable-features=WebRtcAllowInputVolumeAdjustment,ChromeWideEchoCancellation",
        url,
    ]


def _cmdline_is_talk_chromium(cmdline: str) -> bool:
    return str(USER_DATA_DIR) in cmdline and f"--remote-debugging-port={CDP_PORT}" in cmdline


def talk_chromium_pids() -> list[int]:
    pids: list[int] = []
    marker = str(USER_DATA_DIR)
    try:
        for entry in Path("/proc").iterdir():
            if not entry.name.isdigit():
                continue
            try:
                cmd = entry.joinpath("cmdline").read_bytes().replace(b"\0", b" ").decode("utf-8", "replace")
            except OSError:
                continue
            if marker in cmd and "chromium" in cmd.lower():
                pids.append(int(entry.name))
    except OSError:
        pass
    return pids


def browser_pid() -> Optional[int]:
    for pid in talk_chromium_pids():
        try:
            cmd = Path(f"/proc/{pid}/cmdline").read_bytes().replace(b"\0", b" ").decode("utf-8", "replace")
        except OSError:
            continue
        if "--type=" in cmd:
            continue
        if _cmdline_is_talk_chromium(cmd) or str(USER_DATA_DIR) in cmd:
            return pid
    return None


class CdpSession:
    def __init__(self, ws: Any) -> None:
        self._ws = ws
        self._next_id = 1
        self._pending: dict[int, asyncio.Future] = {}
        self._recv_task = asyncio.create_task(self._recv_loop())

    async def _recv_loop(self) -> None:
        try:
            async for raw in self._ws:
                msg = json.loads(raw)
                req_id = msg.get("id")
                if isinstance(req_id, int):
                    fut = self._pending.pop(req_id, None)
                    if fut and not fut.done():
                        fut.set_result(msg)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            for fut in self._pending.values():
                if not fut.done():
                    fut.set_exception(exc)
            self._pending.clear()

    async def call(self, method: str, params: Optional[dict] = None, timeout_s: float = 10.0) -> dict:
        req_id = self._next_id
        self._next_id += 1
        fut: asyncio.Future = asyncio.get_running_loop().create_future()
        self._pending[req_id] = fut
        payload = {"id": req_id, "method": method}
        if params is not None:
            payload["params"] = params
        await self._ws.send(json.dumps(payload))
        msg = await asyncio.wait_for(fut, timeout=timeout_s)
        if "error" in msg:
            raise TalkUiError(f"CDP {method} failed: {msg['error']}")
        return msg.get("result") or {}

    async def evaluate(self, expression: str, timeout_s: float = 10.0, await_promise: bool = False) -> Any:
        result = await self.call(
            "Runtime.evaluate",
            {
                "expression": expression,
                "returnByValue": True,
                "awaitPromise": await_promise,
            },
            timeout_s=timeout_s,
        )
        inner = result.get("result") or {}
        if result.get("exceptionDetails"):
            text = json.dumps(result["exceptionDetails"])[:400]
            raise TalkUiError(f"CDP evaluate exception: {text}")
        return inner.get("value")

    async def close(self) -> None:
        self._recv_task.cancel()
        try:
            await self._recv_task
        except asyncio.CancelledError:
            pass
        try:
            await self._ws.close()
        except Exception:
            pass



async def pin_phone_uplink_volume() -> None:
    """Force phone_uplink (+ monitor) to 100% — Chromium AGC drifts it down."""
    for kind, target in (
        ("sink", PHONE_UPLINK_SINK),
        ("source", PHONE_UPLINK_MONITOR),
    ):
        cmd = ["pactl", f"set-{kind}-volume", target, "100%"]
        rc, out, err = await _run_captured(
            cmd,
            TOOL_TIMEOUT_S,
            " ".join(cmd),
        )
        if rc != 0:
            LOG.warning(
                "pin %s volume %s failed rc=%s err=%s",
                kind,
                target,
                rc,
                (err or out)[:160],
            )
        else:
            LOG.info("pinned %s volume %s to 100%%", kind, target)


class OpenClawTalkUI:
    """Start/stop Control UI Talk in a dedicated Chromium via CDP."""

    def __init__(self) -> None:
        self._proc: Optional[asyncio.subprocess.Process] = None
        self._closed = False
        self.talk_active = False
        self.webrtc_connected = False
        self.talk_started_at: Optional[float] = None
        self.last_state: dict[str, Any] = {}

    @property
    def audio_frames(self) -> int:
        return 0

    @property
    def loud_audio_frames(self) -> int:
        return 0

    @property
    def first_loud_audio_frame_at(self) -> Optional[float]:
        return None

    def feed_gsm_ulaw(self, ulaw: bytes) -> None:
        """Uplink is PipeWire phone_uplink, not gateway appendAudio."""
        return

    async def start_audio(self) -> None:
        """Bring Chromium + Control UI up before the phone is answered."""
        self._closed = False
        await self.ensure_browser()
        session = await self._connect_page()
        try:
            await self._ensure_control_ui(session)
            hook = await session.evaluate(HOOK_JS)
            LOG.info("webrtc hook: %s", hook)
            state = await session.evaluate(PAGE_STATE_JS)
            self.last_state = state if isinstance(state, dict) else {}
            LOG.info(
                "control ui ready hasTalk=%s live=%s tokenInput=%s",
                self.last_state.get("hasTalkButton"),
                self.last_state.get("live"),
                self.last_state.get("hasTokenInput"),
            )
            if not self.last_state.get("hasTalkButton"):
                snippet = (self.last_state.get("snippet") or "")[:240]
                raise TalkUiError(
                    "Control UI Talk button not found — profile may need a one-time "
                    f"DCV login ({snippet!r})"
                )
        finally:
            await session.close()

    async def start_talk(self, *, allow_already_active: bool = True) -> None:
        if self._closed:
            raise TalkUiError("talk ui is closed")
        session = await self._connect_page()
        try:
            await session.evaluate(HOOK_JS)
            hook_state = await session.evaluate(
                "({gum:window.__gsm2GumPatchVer||0, fresh:!!window.__gsm2NeedsFreshGum})"
            )
            LOG.info("webrtc hook state: %s", hook_state)
            if isinstance(hook_state, dict) and hook_state.get("fresh"):
                LOG.warning(
                    "Talk mic patch upgraded; reloading Control UI for openclaw_phone_mic"
                )
                await session.call("Page.reload", {"ignoreCache": True})
                deadline = time.monotonic() + PAGE_TIMEOUT_S
                last = None
                while time.monotonic() < deadline:
                    last = await session.evaluate(PAGE_STATE_JS)
                    if isinstance(last, dict) and last.get("hasTalkButton"):
                        break
                    await asyncio.sleep(0.35)
                else:
                    raise TalkUiError(f"Control UI reload missing talk button: {last!r}")
                await session.evaluate(
                    "window.__gsm2NeedsFreshGum=false;window.__gsm2TalkHook=false;"
                    "window.__gsm2PcWrapped=false;window.__gsm2GumPatchVer=0;"
                    "window.__gsm2OrigGum=null;1"
                )
                await session.evaluate(HOOK_JS)
            clicked = await session.evaluate(CLICK_START_JS)
            LOG.info("talk click: %s", clicked)
            if not isinstance(clicked, dict):
                raise TalkUiError(f"talk click returned {clicked!r}")
            if clicked.get("state") == "already_active" and not allow_already_active:
                raise TalkUiError("Control UI Talk still active after stop")
            if clicked.get("state") == "missing":
                raise TalkUiError(
                    "Control UI Talk button missing; cannot start WebRTC Talk"
                )
            if clicked.get("state") == "disabled":
                raise TalkUiError(
                    f"Control UI Talk button disabled ({clicked.get('label')})"
                )
            if clicked.get("clearedComposer"):
                LOG.warning(
                    "cleared leftover composer draft (%s chars) so Tap-to-talk "
                    "starts Talk instead of sending",
                    clicked.get("composerLen"),
                )
            if clicked.get("state") == "clicked" and not await self._webrtc_in_flight_soon(
                session, timeout_s=2.5
            ):
                LOG.warning("talk click produced no live PeerConnection; retrying")
                retry = await session.evaluate(CLICK_START_JS)
                LOG.info("talk click retry: %s", retry)
            state = await self._wait_webrtc(session)
            self.last_state = state
            self.talk_active = True
            self.webrtc_connected = True
            self.talk_started_at = time.monotonic()
            await self._bind_chromium_audio()
            LOG.info("control ui talk webrtc connected: %s", state.get("pcs"))
        finally:
            await session.close()

    async def talk_session_healthy(self) -> Optional[bool]:
        """Return whether Talk UI is live and has a connected WebRTC peer (None if CDP unavailable)."""
        if not cdp_available():
            return None
        try:
            session = await self._connect_page()
        except Exception:
            return None
        try:
            state = await session.evaluate(PAGE_STATE_JS)
            if not isinstance(state, dict):
                return None
            if not state.get("live"):
                return False
            pcs = state.get("pcs") or []
            if not pcs:
                return False
            for pc in pcs:
                if not isinstance(pc, dict):
                    continue
                conn = pc.get("connection")
                ice = pc.get("ice")
                if conn == "connected" and ice in ("connected", "completed"):
                    return True
            return False
        except Exception:
            return None
        finally:
            await session.close()

    async def stop_talk(self) -> None:
        """Stop the live Talk session without tearing down Chromium."""
        self.talk_active = False
        self.webrtc_connected = False
        self.talk_started_at = None
        if not cdp_available():
            return
        try:
            session = await self._connect_page()
        except Exception as exc:
            LOG.warning("talk stop: cdp unavailable: %s", exc)
            return
        try:
            result = await session.evaluate(CLICK_STOP_JS)
            LOG.info("talk stop click: %s", result)
        except Exception as exc:
            LOG.warning("talk stop click failed: %s", exc)
        finally:
            await session.close()


    async def reload_control_ui(self) -> None:
        """Soft heal: stop Talk, reload Control UI page, ready for next start_talk."""
        await self.stop_talk()
        if not cdp_available():
            raise TalkUiError("cdp unavailable for control ui reload")
        session = await self._connect_page()
        try:
            await self._ensure_control_ui(session)
            # Force a fresh document even when already on the chat URL.
            await session.call("Page.reload", {"ignoreCache": True})
            deadline = time.monotonic() + PAGE_TIMEOUT_S
            last = None
            while time.monotonic() < deadline:
                last = await session.evaluate(PAGE_STATE_JS)
                if isinstance(last, dict) and last.get("hasTalkButton"):
                    break
                await asyncio.sleep(0.35)
            else:
                raise TalkUiError(f"control ui reload missing talk button: {last!r}")
            await session.evaluate(HOOK_JS)
            LOG.info("control ui reloaded hasTalk=%s", True)
        finally:
            await session.close()

    async def restart_talk(self) -> None:
        """Refresh Control UI Talk before provider session limits (~30 min)."""
        if self._closed:
            raise TalkUiError("talk ui is closed")
        age_s = 0.0
        if self.talk_started_at is not None:
            age_s = time.monotonic() - self.talk_started_at
        LOG.info("talk rollover after %.0fs (limit=%.0fs)", age_s, TALK_ROLLOVER_S)
        await self.stop_talk()
        deadline = time.monotonic() + 12.0
        still_live = True
        while time.monotonic() < deadline:
            if not cdp_available():
                await asyncio.sleep(0.35)
                continue
            try:
                session = await self._connect_page()
            except Exception:
                await asyncio.sleep(0.3)
                continue
            try:
                state = await session.evaluate(PAGE_STATE_JS)
            finally:
                await session.close()
            if isinstance(state, dict) and not state.get("live"):
                still_live = False
                break
            await asyncio.sleep(0.35)
        if still_live:
            raise TalkUiError("talk rollover stop failed: Control UI still live")
        await self.start_talk(allow_already_active=False)

    async def start(self) -> None:
        await self.start_audio()
        await self.start_talk()

    async def stop(self) -> None:
        self._closed = True
        await self.stop_talk()

    async def health(self) -> dict[str, Any]:
        pid = browser_pid()
        info: dict[str, Any] = {
            "cdp": cdp_available(),
            "browser_pid": pid,
            "user_data_dir": str(USER_DATA_DIR),
            "pulse_source": PHONE_UPLINK_MONITOR,
            "pulse_sink": OPENCLAW_BUS,
            "talk_active": self.talk_active,
            "webrtc_connected": self.webrtc_connected,
            "url": control_ui_url(),
        }
        if not info["cdp"]:
            return info
        # Never let a wedged CDP block /health (and thus e2e idle checks).
        try:
            info["page"] = await asyncio.wait_for(self._health_page_probe(), timeout=2.5)
        except asyncio.TimeoutError:
            info["error"] = "cdp probe timed out"
            info["cdp_wedged"] = True
            LOG.warning("talk health CDP probe timed out")
        except Exception as exc:
            info["error"] = str(exc)
        return info

    async def _health_page_probe(self) -> dict[str, Any]:
        session = await self._connect_page()
        try:
            page = await session.evaluate(PAGE_STATE_JS)
            return page if isinstance(page, dict) else {"raw": page}
        finally:
            await session.close()

    async def ensure_browser(self) -> None:
        if cdp_available() and browser_pid() is not None:
            return
        if cdp_available():
            LOG.warning("cdp port %s is up but talk chromium pid was not found", CDP_PORT)
            return
        env = chromium_launch_env()
        url = control_ui_url()
        args = chromium_args(url)
        LOG.info(
            "launching talk chromium display=%s pulse_source=%s pulse_sink=%s profile=%s",
            env.get("DISPLAY"),
            env.get("PULSE_SOURCE"),
            env.get("PULSE_SINK"),
            USER_DATA_DIR,
        )
        self._proc = await asyncio.create_subprocess_exec(
            *args,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.PIPE,
            env=env,
            start_new_session=True,
        )
        asyncio.create_task(self._log_chromium_stderr())
        deadline = time.monotonic() + START_TIMEOUT_S
        while time.monotonic() < deadline:
            if self._proc.returncode is not None:
                err = ""
                if self._proc.stderr:
                    err = (await self._proc.stderr.read())[-500:].decode("utf-8", "replace")
                raise TalkUiError(f"talk chromium exited rc={self._proc.returncode} {err}")
            if cdp_available():
                LOG.info("talk chromium cdp ready pid=%s", self._proc.pid)
                return
            await asyncio.sleep(0.2)
        raise TalkUiError(
            f"talk chromium CDP did not listen on {CDP_HOST}:{CDP_PORT} within {START_TIMEOUT_S}s"
        )

    async def _log_chromium_stderr(self) -> None:
        proc = self._proc
        if not proc or not proc.stderr:
            return
        try:
            while True:
                line = await proc.stderr.readline()
                if not line:
                    return
                text = line.decode("utf-8", errors="replace").rstrip()
                if text and any(k in text.lower() for k in ("error", "fail", "denied", "pulse")):
                    LOG.warning("chromium: %s", text)
        except asyncio.CancelledError:
            raise
        except Exception:
            return

    async def _connect_page(self) -> CdpSession:
        try:
            import websockets
        except ImportError as exc:
            raise TalkUiError("websockets package required for Control UI CDP") from exc
        try:
            targets = _cdp_http("/json/list", timeout_s=2.0)
        except Exception as exc:
            raise TalkUiError(f"cdp list failed: {exc}") from exc
        ws_url = None
        for target in targets:
            if target.get("type") != "page":
                continue
            url = str(target.get("url") or "")
            if "devtools://" in url or url.startswith("chrome://"):
                continue
            ws_url = target.get("webSocketDebuggerUrl")
            if "openclaw" in url or "/chat/" in url or "mining-ling.ts.net" in url:
                break
        if not ws_url and targets:
            ws_url = next(
                (t.get("webSocketDebuggerUrl") for t in targets if t.get("type") == "page"),
                None,
            )
        if not ws_url:
            version = _cdp_http("/json/version", timeout_s=2.0)
            ws_url = version.get("webSocketDebuggerUrl")
        if not ws_url:
            raise TalkUiError("no CDP page websocket")
        ws = await websockets.connect(ws_url, max_size=16 * 1024 * 1024)
        session = CdpSession(ws)
        await session.call("Runtime.enable")
        await session.call("Page.enable")
        return session

    async def _ensure_control_ui(self, session: CdpSession) -> None:
        state = await session.evaluate(PAGE_STATE_JS)
        if isinstance(state, dict) and state.get("hasTalkButton"):
            return
        url = control_ui_url_with_token()
        LOG.info("navigating talk chromium to Control UI %s", control_ui_url())
        await session.call("Page.navigate", {"url": url})
        deadline = time.monotonic() + PAGE_TIMEOUT_S
        last = state if isinstance(state, dict) else {}
        while time.monotonic() < deadline:
            await asyncio.sleep(0.4)
            last = await session.evaluate(PAGE_STATE_JS)
            if isinstance(last, dict) and last.get("hasTalkButton"):
                return
            snippet = str((last or {}).get("snippet") or "").lower()
            if "device pairing required" in snippet or "approve this browser" in snippet:
                raise TalkUiError(
                    "Control UI needs device pairing. On safwat-eu run "
                    "`openclaw devices` (or log in once via DCV in the "
                    "chromium-openclaw-talk profile) then retry."
                )
        raise TalkUiError(
            "Control UI did not show the Talk button. Log in once via DCV: "
            f"profile {USER_DATA_DIR} url={control_ui_url()} snippet={((last or {}).get('snippet') or '')[:180]!r}"
        )

    @staticmethod
    def _webrtc_connected(state: dict[str, Any]) -> bool:
        pcs = state.get("pcs") or []
        return any(
            pc.get("connection") in ("connected", "completed")
            or pc.get("ice") in ("connected", "completed")
            for pc in pcs
        )

    @staticmethod
    def _webrtc_in_flight(state: dict[str, Any]) -> bool:
        if state.get("live"):
            return True
        for pc in state.get("pcs") or []:
            if pc.get("connection") in ("new", "connecting", "connected", "completed"):
                return True
            if pc.get("ice") in ("new", "checking", "connected", "completed"):
                return True
        return False

    async def _webrtc_in_flight_soon(self, session: CdpSession, timeout_s: float) -> bool:
        deadline = time.monotonic() + timeout_s
        while time.monotonic() < deadline:
            state = await session.evaluate(PAGE_STATE_JS)
            if isinstance(state, dict) and self._webrtc_in_flight(state):
                return True
            await asyncio.sleep(0.25)
        return False

    async def _wait_webrtc(self, session: CdpSession) -> dict[str, Any]:
        deadline = time.monotonic() + WEBRTC_TIMEOUT_S
        last: dict[str, Any] = {}
        while time.monotonic() < deadline:
            last = await session.evaluate(PAGE_STATE_JS)
            if not isinstance(last, dict):
                last = {}
            snippet = str(last.get("snippet") or "")
            status = str(last.get("statusText") or "")
            fatal = ("auth" in status.lower() and "fail" in status.lower()) or "is not configured" in snippet.lower()
            if fatal:
                raise TalkUiError(f"Control UI Talk failed: {status or snippet[:200]}")
            if self._webrtc_connected(last):
                return last
            await asyncio.sleep(0.35)
        raise TalkUiError(
            "Control UI Talk WebRTC did not connect "
            f"(live={last.get('live')} composerLen={last.get('composerLen')} "
            f"pcs={last.get('pcs')} status={last.get('statusText')!r} "
            f"snippet={(last.get('snippet') or '')[:180]!r})"
        )

    async def _bind_chromium_audio(self) -> None:
        """Fail if Chromium capture/playback is on the wrong PipeWire node."""
        pids = talk_chromium_pids()
        if not pids:
            raise TalkUiError("talk chromium pid not found after Talk start")
        deadline = time.monotonic() + 5.0
        last = ""
        while time.monotonic() < deadline:
            rc, out, err = await _run_captured(
                ["pactl", "list", "source-outputs"],
                TOOL_TIMEOUT_S,
                "pactl list source-outputs",
            )
            sources = out if rc == 0 else ""
            rc2, out2, err2 = await _run_captured(
                ["pactl", "list", "sink-inputs"],
                TOOL_TIMEOUT_S,
                "pactl list sink-inputs",
            )
            sinks = out2 if rc2 == 0 else ""
            capture_ok = (
                PHONE_UPLINK_MONITOR in sources
                or PHONE_UPLINK_SINK in sources
                or "openclaw_phone_mic" in sources
                or "output.openclaw_phone_mic" in sources
                or "openclaw_mic" in sources
            )
            playback_ok = OPENCLAW_BUS in sinks
            bad_capture = any(
                name in sources
                for name in ("AWS-Virtual-Microphone", "gsm_bus.monitor", "openclaw_bus.monitor")
            ) and "openclaw_phone_mic" not in sources and PHONE_UPLINK_MONITOR not in sources
            last = (
                f"capture_ok={capture_ok} playback_ok={playback_ok} "
                f"bad_capture={bad_capture} source-outputs={len(sources)} sink-inputs={len(sinks)}"
            )
            if capture_ok and playback_ok and not bad_capture:
                LOG.info("chromium pulse bind ok: %s", last)
                await pin_phone_uplink_volume()
                return
            if bad_capture:
                LOG.warning("chromium capture on the wrong source; relinking (%s)", last)
            await self._pw_link_chromium_ports()
            await asyncio.sleep(0.3)
        raise TalkUiError(
            f"Chromium audio not bound to {PHONE_UPLINK_MONITOR} / {OPENCLAW_BUS} ({last}). "
            "Mix-minus requires phone_uplink.monitor as the mic, not "
            "AWS-Virtual-Microphone or gsm_bus.monitor."
        )

    async def _pw_link_chromium_ports(self) -> None:
        rc_o, outputs, _ = await _run_captured(["pw-link", "-o"], TOOL_TIMEOUT_S, "pw-link -o")
        rc_i, inputs, _ = await _run_captured(["pw-link", "-i"], TOOL_TIMEOUT_S, "pw-link -i")
        if rc_o or rc_i:
            return
        out_ports = [ln.strip() for ln in outputs.splitlines() if ln.strip()]
        in_ports = [ln.strip() for ln in inputs.splitlines() if ln.strip()]

        def _match(ports: list[str], *needles: str) -> list[str]:
            lowered = [(p, p.lower()) for p in ports]
            return [p for p, low in lowered if all(n.lower() in low for n in needles)]

        uplink_outs = _match(out_ports, PHONE_UPLINK_SINK, "monitor")
        chromium_ins = [
            p
            for p in in_ports
            if "chromium" in p.lower() and ("input" in p.lower() or "capture" in p.lower() or "playback" in p.lower())
        ]
        chromium_outs = [
            p
            for p in out_ports
            if "chromium" in p.lower() and ("output" in p.lower() or "playback" in p.lower() or "monitor" in p.lower())
        ]
        bus_ins = _match(in_ports, OPENCLAW_BUS, "playback") or _match(in_ports, OPENCLAW_BUS)

        async def _link(src: str, dst: str) -> None:
            await _run_captured(["pw-link", src, dst], TOOL_TIMEOUT_S, f"pw-link {src} {dst}")

        for src, dst in _zip_stereo(uplink_outs, chromium_ins):
            await _link(src, dst)
        for src, dst in _zip_stereo(chromium_outs, bus_ins):
            await _link(src, dst)


def _zip_stereo(left: list[str], right: list[str]) -> list[tuple[str, str]]:
    if not left or not right:
        return []
    n = min(len(left), len(right), 2)
    return list(zip(left[:n], right[:n]))


_ui: Optional[OpenClawTalkUI] = None


def get_talk_ui() -> OpenClawTalkUI:
    global _ui
    if _ui is None:
        _ui = OpenClawTalkUI()
    return _ui


async def run_standalone() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    parser = argparse.ArgumentParser(description="OpenClaw Control UI Talk Chromium supervisor")
    parser.add_argument("command", nargs="?", default="run", choices=("run", "start", "stop", "health"))
    args = parser.parse_args()
    ui = get_talk_ui()
    if args.command == "health":
        print(json.dumps(await ui.health(), indent=2))
        return
    if args.command == "stop":
        await ui.stop()
        return
    await ui.ensure_browser()
    if args.command in ("run", "start"):
        print(json.dumps(await ui.health(), indent=2))
    if args.command == "run":
        LOG.info("talk chromium running; Ctrl+C to stop browser supervisor (Talk stays until stop)")
        stop = asyncio.get_running_loop().create_future()

        def _stop() -> None:
            if not stop.done():
                stop.set_result(True)

        for sig in (signal.SIGTERM, signal.SIGINT):
            asyncio.get_running_loop().add_signal_handler(sig, _stop)
        await stop


def main() -> None:
    try:
        asyncio.run(run_standalone())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()