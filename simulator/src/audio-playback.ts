import { decodeMulawToPcm16, upsample8kToFloat } from "./pcmu";

/** Schedules decoded μ-law chunks for speaker playback. */
export class UlawPlayer {
  private ctx: AudioContext;
  private gain: GainNode;
  private nextTime = 0;

  constructor() {
    this.ctx = new AudioContext();
    this.gain = this.ctx.createGain();
    this.gain.gain.value = 1.0;
    this.gain.connect(this.ctx.destination);
  }

  async resume(): Promise<void> {
    if (this.ctx.state === "suspended") {
      await this.ctx.resume();
    }
  }

  playMulaw(mulaw: Uint8Array): void {
    if (mulaw.length === 0) return;
    const pcm8k = decodeMulawToPcm16(mulaw);
    const floats = upsample8kToFloat(pcm8k, this.ctx.sampleRate);
    const buffer = this.ctx.createBuffer(1, floats.length, this.ctx.sampleRate);
    buffer.copyToChannel(new Float32Array(floats), 0);

    const src = this.ctx.createBufferSource();
    src.buffer = buffer;
    src.connect(this.gain);

    const now = this.ctx.currentTime;
    if (this.nextTime < now) this.nextTime = now;
    src.start(this.nextTime);
    this.nextTime += buffer.duration;
  }

  flush(): void {
    this.nextTime = this.ctx.currentTime;
  }

  stop(): void {
    void this.ctx.close();
  }
}
