import { mulawEnergy } from "./audio-level";
import { startMicCapture } from "./audio-capture";
import { UlawPlayer } from "./audio-playback";
import { HubClient } from "./hub-client";
import { StereoMeter } from "./level-meter";
import { synthToneFrame } from "./synth-tone";

const hubUrlInput = document.getElementById("hub-url") as HTMLInputElement;
const callPathInputs = document.querySelectorAll<HTMLInputElement>('input[name="call-path"]');
const toneModeInput = document.getElementById("tone-mode") as HTMLInputElement;
const startBtn = document.getElementById("start-btn") as HTMLButtonElement;
const endBtn = document.getElementById("end-btn") as HTMLButtonElement;
const logEl = document.getElementById("log") as HTMLPreElement;
const statusEl = document.getElementById("status") as HTMLElement;
const micStatusEl = document.getElementById("mic-status") as HTMLElement;
const bytesSentEl = document.getElementById("bytes-sent") as HTMLElement;
const bytesRecvEl = document.getElementById("bytes-recv") as HTMLElement;

const micMeter = new StereoMeter(document.getElementById("meter-mic")!);
const toneMeter = new StereoMeter(document.getElementById("meter-tone")!);
const downlinkMeter = new StereoMeter(document.getElementById("meter-downlink")!);
const sidetoneMeter = new StereoMeter(document.getElementById("meter-sidetone")!);

let client: HubClient | null = null;
let mic: Awaited<ReturnType<typeof startMicCapture>> | null = null;
let player: UlawPlayer | null = null;
let sidetonePlayer: UlawPlayer | null = null;
let tonePhase = 0;
let bytesSent = 0;
let bytesRecv = 0;

function getCallPath(): "loopback" | "openclaw" {
  const selected = document.querySelector<HTMLInputElement>('input[name="call-path"]:checked');
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
  setUiInCall(true);

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
            ? `Hub session ready (${mode}) — streaming test tone`
            : `Hub session ready (${mode}) — streaming mic`,
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

  try {
    await client.start();

    if (useTone) {
      micStatusEl.textContent = "tone";
      log(
        loopback
          ? "Test tone → hub loopback — you should hear 440 Hz echoed back"
          : "Test tone → OpenClaw path (downlink is OpenClaw speech, not echo)",
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

startBtn.addEventListener("click", () => void startCall());
endBtn.addEventListener("click", () => void endCall());

log("Ready — Loopback + test tone = hear 440 Hz echoed (no OpenClaw needed)", "info");
log("OpenClaw mode: select OpenClaw call path; downlink is agent speech only", "info");
