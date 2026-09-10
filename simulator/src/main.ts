import { mulawEnergy } from "./audio-level";
import { startMicCapture } from "./audio-capture";
import { UlawPlayer } from "./audio-playback";
import { HubClient } from "./hub-client";
import { StereoMeter } from "./level-meter";
import { synthToneFrame } from "./synth-tone";

export type SimLifecycle = {
  onCallStart?: (path: "loopback" | "openclaw") => void;
  onCallEnd?: () => void;
};

/** Mount the call simulator into `root` (standalone page or portal pane). Returns hangup. */
export function mountCallSimulator(root: HTMLElement, lifecycle: SimLifecycle = {}): () => void {
  const $ = <T extends HTMLElement>(id: string) => {
    const el = root.querySelector<T>(`#${id}`);
    if (!el) throw new Error(`missing #${id}`);
    return el;
  };

  const hubUrlInput = $<HTMLInputElement>("hub-url");
  const callPathInputs = root.querySelectorAll<HTMLInputElement>('input[name="call-path"]');
  const toneModeInput = $<HTMLInputElement>("tone-mode");
  const startBtn = $<HTMLButtonElement>("start-btn");
  const endBtn = $<HTMLButtonElement>("end-btn");
  const logEl = $<HTMLPreElement>("log");
  const statusEl = $("status");
  const micStatusEl = $("mic-status");
  const bytesSentEl = $("bytes-sent");
  const bytesRecvEl = $("bytes-recv");

  const micMeter = new StereoMeter($("meter-mic"));
  const toneMeter = new StereoMeter($("meter-tone"));
  const downlinkMeter = new StereoMeter($("meter-downlink"));
  const sidetoneMeter = new StereoMeter($("meter-sidetone"));

  let client: HubClient | null = null;
  let mic: Awaited<ReturnType<typeof startMicCapture>> | null = null;
  let player: UlawPlayer | null = null;
  let sidetonePlayer: UlawPlayer | null = null;
  let tonePhase = 0;
  let bytesSent = 0;
  let bytesRecv = 0;
  let disposed = false;
  let callLive = false;

  function getCallPath(): "loopback" | "openclaw" {
    const selected = root.querySelector<HTMLInputElement>('input[name="call-path"]:checked');
    return selected?.value === "openclaw" ? "openclaw" : "loopback";
  }

  function log(message: string, level: "info" | "ok" | "warn" | "err" = "info"): void {
    const ts = new Date().toISOString().slice(11, 23);
    const line = document.createElement("div");
    line.className = `log-${level}`;
    line.textContent = `[${ts}] ${message}`;
    logEl.appendChild(line);
    logEl.scrollTop = logEl.scrollHeight;
  }

  function setUiInCall(inCall: boolean): void {
    startBtn.disabled = inCall;
    endBtn.disabled = !inCall;
    hubUrlInput.disabled = inCall;
    callPathInputs.forEach((input) => {
      input.disabled = inCall;
    });
    toneModeInput.disabled = inCall;
  }

  async function startCall(): Promise<void> {
    const hubUrl = hubUrlInput.value.trim();
    if (!hubUrl) {
      log("Hub URL required", "err");
      return;
    }

    const useTone = toneModeInput.checked;
    const loopback = getCallPath() === "loopback";
    bytesSent = 0;
    bytesRecv = 0;
    tonePhase = 0;
    bytesSentEl.textContent = "0";
    bytesRecvEl.textContent = "0";
    micMeter.reset();
    toneMeter.reset();
    downlinkMeter.reset();
    sidetoneMeter.reset();
    micMeter.setActive(!useTone);
    toneMeter.setActive(useTone);
    downlinkMeter.setActive(true);
    sidetoneMeter.setActive(useTone && !loopback);
    startBtn.disabled = true;
    statusEl.textContent = "connecting";

    try {
      player = new UlawPlayer();
      await player.resume();
      if (useTone && !loopback) {
        sidetonePlayer = new UlawPlayer();
        await sidetonePlayer.resume();
      }

      client = new HubClient(
      hubUrl,
      {
        onLog: log,
        onStatus: (s) => {
          statusEl.textContent = s;
        },
        onSessionReady: () => {
          const mode = loopback ? "loopback" : "OpenClaw";
          log(
            useTone
              ? `Hub session ready (${mode}). Streaming test tone`
              : `Hub session ready (${mode}). Streaming mic`,
            "ok",
          );
        },
        onAudioDelta: (mulaw, channels) => {
          if (channels) {
            downlinkMeter.setStereoLevel(channels.l, channels.r);
          } else {
            downlinkMeter.setEnergy(mulawEnergy(mulaw));
          }
          player?.playMulaw(mulaw);
        },
        onBytesSent: (n) => {
          bytesSent += n;
          bytesSentEl.textContent = String(bytesSent);
        },
        onBytesReceived: (n) => {
          bytesRecv += n;
          bytesRecvEl.textContent = String(bytesRecv);
        },
      },
      loopback,
    );

      await client.start();
      callLive = true;
      setUiInCall(true);
      lifecycle.onCallStart?.(getCallPath());

      if (useTone) {
        micStatusEl.textContent = "tone";
        log(
          loopback
            ? "Test tone to hub loopback. You should hear 440 Hz come back"
            : "Test tone to OpenClaw path (earpiece is agent speech, not echo)",
          "ok",
        );
        client.startUplink(() => {
          tonePhase += 0.02;
          const frame = synthToneFrame(tonePhase);
          const energy = mulawEnergy(frame);
          toneMeter.setEnergy(energy);
          if (!loopback) {
            sidetoneMeter.setEnergy(energy);
            sidetonePlayer?.playMulaw(frame);
          }
          return frame;
        });
      } else {
        mic = await startMicCapture(
          (chunk, levels) => {
            client?.enqueueMulaw(chunk);
            micMeter.setStereoEnergy(levels.l, levels.r);
          },
          { disableEchoCancellation: loopback },
        );
        micStatusEl.textContent = "yes";
        log("Microphone capture started (echo cancel off for loopback)", "ok");
        client.startUplink(() => client!.drainFrame());
      }
    } catch (e) {
      log(e instanceof Error ? e.message : String(e), "err");
      await endCall();
    }
  }

  async function endCall(): Promise<void> {
    if (callLive) {
      callLive = false;
      lifecycle.onCallEnd?.();
    }
    mic?.stop();
    mic = null;
    micStatusEl.textContent = "no";
    micMeter.reset();
    toneMeter.reset();
    downlinkMeter.reset();
    sidetoneMeter.reset();
    micMeter.setActive(false);
    toneMeter.setActive(false);
    downlinkMeter.setActive(false);
    sidetoneMeter.setActive(false);

    client?.stop();
    client = null;

    player?.stop();
    player = null;
    sidetonePlayer?.stop();
    sidetonePlayer = null;

    setUiInCall(false);
    statusEl.textContent = "idle";
  }

  const onStart = () => void startCall();
  const onEnd = () => void endCall();
  startBtn.addEventListener("click", onStart);
  endBtn.addEventListener("click", onEnd);

  log("Ready. Loopback + test tone: hear 440 Hz echoed (no OpenClaw needed)", "info");
  log("OpenClaw path: earpiece is agent speech only", "info");

  return () => {
    if (disposed) return;
    disposed = true;
    startBtn.removeEventListener("click", onStart);
    endBtn.removeEventListener("click", onEnd);
    void endCall();
  };
}
