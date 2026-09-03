import { pcmEnergy } from "./audio-level";
import { downsampleTo8k, encodePcm16ToMulaw } from "./pcmu";

export type MicLevels = { l: number; r: number };

export type MicCapture = {
  stop: () => void;
  /** Feed pending μ-law into hub client enqueue. */
  readMulaw: () => Uint8Array;
};

export async function startMicCapture(
  onMulaw: (chunk: Uint8Array, levels: MicLevels) => void,
  options?: { disableEchoCancellation?: boolean },
): Promise<MicCapture> {
  const noAec = options?.disableEchoCancellation ?? false;
  const stream = await navigator.mediaDevices.getUserMedia({
    audio: {
      channelCount: 2,
      echoCancellation: !noAec,
      noiseSuppression: !noAec,
      autoGainControl: !noAec,
    },
    video: false,
  });

  const ctx = new AudioContext();
  if (ctx.state === "suspended") {
    await ctx.resume();
  }
  const source = ctx.createMediaStreamSource(stream);
  const processor = ctx.createScriptProcessor(2048, 2, 1);
  const gain = ctx.createGain();
  gain.gain.value = 0; // monitor muted — uplink only

  source.connect(processor);
  processor.connect(gain);
  gain.connect(ctx.destination);

  let pending = new Uint8Array(0);

  processor.onaudioprocess = (ev) => {
    const input = ev.inputBuffer;
    const channels = input.numberOfChannels;
    const left = input.getChannelData(0);
    const right = channels >= 2 ? input.getChannelData(1) : left;

    const energyL = pcmEnergy(left);
    const energyR = pcmEnergy(right);

    const mono = new Float32Array(left.length);
    if (channels >= 2) {
      for (let i = 0; i < left.length; i++) {
        mono[i] = (left[i]! + right[i]!) * 0.5;
      }
    } else {
      mono.set(left);
    }

    const pcm8k = downsampleTo8k(mono, ctx.sampleRate);
    const mulaw = encodePcm16ToMulaw(pcm8k);
    if (mulaw.length === 0) return;

    const merged = new Uint8Array(pending.length + mulaw.length);
    merged.set(pending);
    merged.set(mulaw, pending.length);
    pending = merged;
    onMulaw(mulaw, { l: energyL, r: energyR });
  };

  return {
    stop: () => {
      processor.disconnect();
      source.disconnect();
      gain.disconnect();
      stream.getTracks().forEach((t) => t.stop());
      void ctx.close();
      pending = new Uint8Array(0);
    },
    readMulaw: () => {
      const out = pending;
      pending = new Uint8Array(0);
      return out;
    },
  };
}
