import { encodeSample } from "./pcmu";

const FRAME_BYTES = 160;

/** 440 Hz μ-law frame — same idea as scripts/validate-ws.mjs */
export function synthToneFrame(phaseOffset = 0): Uint8Array {
  const out = new Uint8Array(FRAME_BYTES);
  const t0 = Date.now() / 1000 + phaseOffset;
  for (let i = 0; i < FRAME_BYTES; i++) {
    const t = (t0 + i / 8000) * 2 * Math.PI * 440;
    const pcm = Math.round(Math.sin(t) * 8000);
    out[i] = encodeSample(pcm);
  }
  return out;
}
