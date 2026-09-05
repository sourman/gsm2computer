#!/usr/bin/env node
/**
 * Soak the hub OpenClaw path (Control UI Talk / WebRTC).
 * Usage: node scripts/soak-openclaw-talk.mjs [hub-http-url] [--calls 5] [--seconds 8]
 */
import WebSocket from "ws";

const args = process.argv.slice(2).filter((a) => !a.startsWith("--"));
const HUB = (args[0] || "http://100.101.181.110:8787").replace(/\/$/, "");
const flag = (name, fallback) => {
  const idx = process.argv.indexOf(`--${name}`);
  if (idx >= 0 && process.argv[idx + 1]) return process.argv[idx + 1];
  return fallback;
};
const CALLS = Number(flag("calls", "5"));
const SECONDS = Number(flag("seconds", "8"));
const WS_URL = HUB.replace(/^https:/, "wss:").replace(/^http:/, "ws:");
const FRAME = 160;

function encodeSample(pcm) {
  let sample = pcm | 0;
  let sign = (sample >> 8) & 0x80;
  if (sign !== 0) sample = -sample;
  if (sample > 32635) sample = 32635;
  sample += 0x84;
  let exponent = 0;
  while (exponent < 7 && (sample >> (exponent + 8)) !== 0) exponent++;
  const mantissa = (sample >> (exponent + 3)) & 0x0f;
  return ((sign & 0x80) | (exponent << 4) | mantissa) ^ 0xff;
}

function synthToneFrame() {
  const out = Buffer.alloc(FRAME);
  for (let i = 0; i < FRAME; i++) {
    const t = (Date.now() / 1000 + i / 8000) * 2 * Math.PI * 440;
    const pcm = Math.round(Math.sin(t) * 8000);
    out[i] = encodeSample(pcm);
  }
  return out;
}

async function oneCall(n) {
  const health = await fetch(`${HUB}/health`);
  const healthBody = await health.json();
  console.log(`call ${n} health`, health.status, JSON.stringify(healthBody));

  const tokenRes = await fetch(`${HUB}/token`, { method: "POST" });
  const tokenBody = await tokenRes.json();
  if (!tokenRes.ok || !tokenBody.value) {
    throw new Error(`token failed: ${JSON.stringify(tokenBody)}`);
  }

  const ws = new WebSocket(WS_URL, {
    headers: { Authorization: `Bearer ${tokenBody.value}` },
  });

  let sessionOk = false;
  let bytesRecv = 0;
  let deltas = 0;
  let handshakeError = null;

  await new Promise((resolve, reject) => {
    const timer = setTimeout(
      () => reject(new Error("timeout waiting session.updated")),
      60000,
    );

    ws.on("open", () => console.log(`call ${n} WS open`));

    ws.on("message", (data) => {
      const text = data.toString();
      let json;
      try {
        json = JSON.parse(text);
      } catch {
        return;
      }
      if (json.type === "error") {
        handshakeError = json.error?.message || JSON.stringify(json.error);
        return;
      }
      if (json.type === "session.updated") {
        sessionOk = true;
        console.log(`call ${n} session.updated`, JSON.stringify(json.session));
        clearTimeout(timer);
        let sent = 0;
        const need = FRAME * 50 * SECONDS;
        const uplink = setInterval(() => {
          const frame = synthToneFrame();
          ws.send(
            JSON.stringify({
              type: "input_audio_buffer.append",
              audio: frame.toString("base64"),
            }),
          );
          sent += frame.length;
          if (sent >= need) {
            clearInterval(uplink);
            console.log(`call ${n} sent`, sent, "μ-law bytes");
            setTimeout(resolve, 3000);
          }
        }, 20);
      } else if (json.type === "response.output_audio.delta") {
        deltas++;
        bytesRecv += Buffer.from(json.delta || "", "base64").length;
      }
    });

    ws.on("error", reject);
    ws.on("close", (code, reason) => {
      if (!sessionOk) {
        clearTimeout(timer);
        reject(
          new Error(
            `closed early code=${code} ${reason} err=${handshakeError || ""}`,
          ),
        );
      }
    });
  });

  ws.close(1000, "soak done");
  console.log(`call ${n} downlink deltas=${deltas} bytes=${bytesRecv}`);
  if (!sessionOk) {
    throw new Error(`call ${n} missing session.updated (${handshakeError})`);
  }
  if (bytesRecv < 1600) {
    throw new Error(`call ${n} almost no downlink (${bytesRecv} bytes)`);
  }
  return { deltas, bytesRecv };
}

async function main() {
  const results = [];
  for (let i = 1; i <= CALLS; i++) {
    results.push(await oneCall(i));
    await new Promise((r) => setTimeout(r, 1500));
  }
  console.log("OK —", CALLS, "OpenClaw Talk calls", results);
}

main().catch((err) => {
  console.error("FAIL:", err.message || err);
  process.exit(1);
});
