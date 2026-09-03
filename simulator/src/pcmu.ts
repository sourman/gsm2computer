/** ITU-T G.711 μ-law (PCMU) — mirrors app PcmuCodec.kt */

export function encodeSample(pcm: number): number {
  let sample = pcm | 0;
  let sign = (sample >> 8) & 0x80;
  if (sign !== 0) sample = -sample;
  if (sample > 32635) sample = 32635;
  sample += 0x84;
  if (sample > 32635) sample = 32635;

  let exponent = 0;
  while (exponent < 7 && (sample >> (exponent + 8)) !== 0) {
    exponent++;
  }
  const mantissa = (sample >> (exponent + 3)) & 0x0f;
  const raw = (sign & 0x80) | (exponent << 4) | mantissa;
  return raw ^ 0xff;
}

export function decodeSample(pcmu: number): number {
  const b = (pcmu & 0xff) ^ 0xff;
  const sign = b & 0x80;
  const exponent = (b >> 4) & 0x07;
  const mantissa = b & 0x0f;
  let sample = ((mantissa << 3) + 0x84) << exponent;
  sample -= 0x84;
  return sign !== 0 ? -sample : sample;
}

export function encodePcm16ToMulaw(pcm16: Int16Array): Uint8Array {
  const out = new Uint8Array(pcm16.length);
  for (let i = 0; i < pcm16.length; i++) {
    out[i] = encodeSample(pcm16[i]!);
  }
  return out;
}

export function decodeMulawToPcm16(mulaw: Uint8Array): Int16Array {
  const out = new Int16Array(mulaw.length);
  for (let i = 0; i < mulaw.length; i++) {
    out[i] = decodeSample(mulaw[i]!);
  }
  return out;
}

/** Downsample float32 mono (any rate) to 8 kHz Int16 via averaging. */
export function downsampleTo8k(
  input: Float32Array,
  inputRate: number,
): Int16Array {
  const ratio = inputRate / 8000;
  const outLen = Math.floor(input.length / ratio);
  const out = new Int16Array(outLen);
  for (let i = 0; i < outLen; i++) {
    const start = Math.floor(i * ratio);
    const end = Math.min(input.length, Math.floor((i + 1) * ratio));
    let sum = 0;
    for (let j = start; j < end; j++) {
      sum += input[j]!;
    }
    const avg = sum / (end - start || 1);
    const clamped = Math.max(-1, Math.min(1, avg));
    out[i] = (clamped * 0x7fff) | 0;
  }
  return out;
}

/** Upsample 8 kHz Int16 PCM to float32 at targetRate (linear interpolation). */
export function upsample8kToFloat(
  pcm8k: Int16Array,
  targetRate: number,
): Float32Array {
  const ratio = targetRate / 8000;
  const outLen = Math.floor(pcm8k.length * ratio);
  const out = new Float32Array(outLen);
  for (let i = 0; i < outLen; i++) {
    const srcPos = i / ratio;
    const idx = Math.floor(srcPos);
    const frac = srcPos - idx;
    const s0 = (pcm8k[idx] ?? 0) / 0x8000;
    const s1 = (pcm8k[Math.min(idx + 1, pcm8k.length - 1)] ?? s0) / 0x8000;
    out[i] = s0 + (s1 - s0) * frac;
  }
  return out;
}
