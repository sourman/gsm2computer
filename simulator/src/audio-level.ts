import { decodeMulawToPcm16 } from "./pcmu";

/** RMS of μ-law chunk (0 = silence / 0xff padding). */
export function mulawEnergy(mulaw: Uint8Array): number {
  if (mulaw.length === 0) return 0;
  const pcm = decodeMulawToPcm16(mulaw);
  let sum = 0;
  for (let i = 0; i < pcm.length; i++) {
    const s = pcm[i]! / 32768;
    sum += s * s;
  }
  return Math.sqrt(sum / pcm.length);
}

/** RMS of float32 PCM samples (0 = silence). */
export function pcmEnergy(samples: Float32Array): number {
  if (samples.length === 0) return 0;
  let sum = 0;
  for (let i = 0; i < samples.length; i++) {
    const s = samples[i]!;
    sum += s * s;
  }
  return Math.sqrt(sum / samples.length);
}

/** Map RMS energy to a 0–1 meter fill (speech/tone visible, silence near zero). */
export function energyToMeterLevel(energy: number): number {
  if (energy <= 0.001) return 0;
  return Math.min(1, Math.sqrt(energy / 0.22));
}
