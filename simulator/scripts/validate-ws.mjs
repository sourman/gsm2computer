#!/usr/bin/env node
/**
 * Layer 2 validation: token + WebSocket + session.updated + synthetic μ-law uplink.
 * Usage: node scripts/validate-ws.mjs [hub-http-url]
 */
import WebSocket from "ws";

const HUB = (process.argv[2] || "http://100.101.181.110:8787").replace(/\/$/, "");
const LOOPBACK = process.argv.includes("--loopback");
const WS_URL =
  HUB.replace(/^https:/, "wss:").replace(/^http:/, "ws:") + (LOOPBACK ? "/loopback" : "");
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

/** 440 Hz tone as μ-law frames */
function synthToneFrame() {
  const out = Buffer.alloc(FRAME);
  for (let i = 0; i < FRAME; i++) {
    const t = (Date.now() / 1000 + i / 8000) * 2 * Math.PI * 440;
    const pcm = Math.round(Math.sin(t) * 8000);
    out[i] = encodeSample(pcm);
  }
  return out;
}

async function main() {
  console.log("1) GET /health");
  const health = await fetch(`${HUB}/health`);
  console.log("   ", health.status, await health.text());

  console.log("2) POST /token");
  const tokenRes = await fetch(`${HUB}/token`, { method: "POST" });
  const tokenBody = await tokenRes.json();
  if (!tokenRes.ok || !tokenBody.value) {
    throw new Error(`token failed: ${JSON.stringify(tokenBody)}`);
  }
  console.log("   token model=", tokenBody.model);

  console.log("3) WebSocket connect", WS_URL, LOOPBACK ? "(loopback)" : "");
  const ws = new WebSocket(WS_URL, {
    headers: { Authorization: `Bearer ${tokenBody.value}` },
  });

  let sessionOk = false;
  let bytesRecv = 0;
  let deltas = 0;

  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("timeout waiting session.updated")), 15000);

    ws.on("open", () => console.log("   WS open"));

    ws.on("message", (data) => {
      const text = data.toString();
      let json;
      try {
        json = JSON.parse(text);
      } catch {
        return;
      }
      if (json.type === "session.updated") {
        sessionOk = true;
        console.log("   session.updated OK", JSON.stringify(json.session));
        clearTimeout(timer);

        console.log("4) Uplink synthetic tone (2s)");
        let sent = 0;
        const uplink = setInterval(() => {
          const frame = synthToneFrame();
          ws.send(
            JSON.stringify({
              type: "input_audio_buffer.append",
              audio: frame.toString("base64"),
            }),
          );
          sent += frame.length;
          if (sent >= FRAME * 50) {
            clearInterval(uplink);
            console.log("   sent", sent, "μ-law bytes");
            setTimeout(resolve, 2000);
          }
        }, 20);
      } else if (json.type === "response.output_audio.delta") {
        deltas++;
        bytesRecv += Buffer.from(json.delta || "", "base64").length;
      } else {
        console.log("   event", json.type);
      }
    });

    ws.on("error", reject);
    ws.on("close", (code, reason) => {
      if (!sessionOk) {
        clearTimeout(timer);
        reject(new Error(`closed early code=${code} ${reason}`));
      }
    });
  });

  console.log("5) Downlink:", deltas, "deltas,", bytesRecv, "μ-law bytes received");
  ws.close(1000, "validate done");
  console.log("OK — hub path validated");
}

main().catch((err) => {
  console.error("FAIL:", err.message || err);
  process.exit(1);
});
