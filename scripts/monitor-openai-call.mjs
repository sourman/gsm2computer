#!/usr/bin/env node
/**
 * Sideband OpenAI Realtime monitor (Bearer auth — works outside Workers).
 * Usage: node scripts/monitor-openai-call.mjs rtc_u1_...
 */
import { readFileSync } from "fs";
import { createRequire } from "module";
import { dirname, join } from "path";
import { fileURLToPath } from "url";

const require = createRequire(import.meta.url);
const WebSocket = require(join(dirname(fileURLToPath(import.meta.url)), "../bridge-worker/node_modules/ws"));

const callId = process.argv[2];
if (!callId) {
  console.error("Usage: monitor-openai-call.mjs <call_id>");
  process.exit(1);
}

function loadKey() {
  if (process.env.OPENAI_API_KEY) return process.env.OPENAI_API_KEY.trim();
  const env = readFileSync(join(dirname(fileURLToPath(import.meta.url)), "..", ".env"), "utf8");
  const m = env.match(/^OPENAI_API_KEY=(.+)$/m);
  return m ? m[1].trim() : "";
}

const apiKey = loadKey();
if (!apiKey) {
  console.error("OPENAI_API_KEY missing");
  process.exit(1);
}

const url = `wss://api.openai.com/v1/realtime?call_id=${encodeURIComponent(callId)}`;
console.log(`Connecting ${callId}...`);

const ws = new WebSocket(url, {
  headers: { Authorization: `Bearer ${apiKey}` },
});

ws.on("open", () => {
  console.log("WS open");
  const prompt = () => {
    ws.send(JSON.stringify({
      type: "response.create",
      response: {
        instructions:
          "Greet the caller, then ask them to say something. Keep the call alive with short responses. Do not hang up.",
      },
    }));
  };
  prompt();
  const keepalive = setInterval(() => {
    if (ws.readyState !== WebSocket.OPEN) { clearInterval(keepalive); return; }
    prompt();
  }, 12_000);
  ws.on("close", () => clearInterval(keepalive));
});

ws.on("message", (data) => {
  try {
    const msg = JSON.parse(data.toString());
    const t = msg.type || "";
    if (
      t.startsWith("response.audio") ||
      t.includes("transcription") ||
      t.startsWith("input_audio") ||
      t === "session.updated"
    ) {
      console.log(`EVT ${t}: ${JSON.stringify(msg).slice(0, 400)}`);
    }
  } catch (_) {}
});

ws.on("error", (e) => console.error("WS error:", e.message));
ws.on("close", (code, reason) => console.log(`WS closed ${code} ${reason}`));

setTimeout(() => {
  console.log("Monitor timeout 90s");
  ws.close();
  process.exit(0);
}, 90_000);
