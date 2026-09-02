#!/usr/bin/env node
/**
 * Local sideband monitor daemon. OpenAI call_id sideband WS requires Bearer
 * auth headers — unavailable in Cloudflare Workers fetch/WebSocket. The
 * bridge-worker POSTs call_id here after accept; we attach the monitor WS.
 *
 *   node scripts/monitor-daemon.mjs
 *   MONITOR_PORT=8787 (default)
 */
import { createServer } from "http";
import { readFileSync } from "fs";
import { dirname, join } from "path";
import { fileURLToPath } from "url";
import { createRequire } from "module";

const require = createRequire(import.meta.url);
const WebSocket = require(join(dirname(fileURLToPath(import.meta.url)), "../bridge-worker/node_modules/ws"));

const PORT = Number(process.env.MONITOR_PORT || 8787);
const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");

function loadKey() {
  if (process.env.OPENAI_API_KEY) return process.env.OPENAI_API_KEY.trim();
  const env = readFileSync(join(ROOT, ".env"), "utf8");
  const m = env.match(/^OPENAI_API_KEY=(.+)$/m);
  return m ? m[1].trim() : "";
}

const apiKey = loadKey();
const active = new Map();

function attach(callId) {
  if (active.has(callId)) return;
  const url = `wss://api.openai.com/v1/realtime?call_id=${encodeURIComponent(callId)}`;
  console.log(`[monitor] attaching ${callId}`);
  const ws = new WebSocket(url, { headers: { Authorization: `Bearer ${apiKey}` } });
  active.set(callId, ws);

  ws.on("open", () => {
    console.log(`[monitor] WS open ${callId}`);
    const greet = () => {
      ws.send(JSON.stringify({
        type: "response.create",
        response: {
          instructions:
            "You are a test endpoint for a GSM-SIP gateway. Greet briefly, then converse. Keep responses short. Do not hang up.",
        },
      }));
    };
    greet();
    const keepalive = setInterval(() => {
      if (ws.readyState !== WebSocket.OPEN) {
        clearInterval(keepalive);
        return;
      }
      greet();
    }, 12_000);
    ws.on("close", () => clearInterval(keepalive));
  });

  ws.on("message", (data) => {
    try {
      const msg = JSON.parse(data.toString());
      const t = msg.type || "";
      if (t.startsWith("input_audio") || t.includes("transcription") || t.startsWith("response.audio")) {
        console.log(`[monitor] ${callId} ${t}`);
      }
    } catch (_) {}
  });

  ws.on("error", (e) => console.error(`[monitor] error ${callId}:`, e.message));
  ws.on("close", (code, reason) => {
    console.log(`[monitor] closed ${callId} ${code} ${reason}`);
    active.delete(callId);
  });
}

createServer((req, res) => {
  if (req.method === "GET" && req.url === "/health") {
    res.writeHead(200, { "Content-Type": "text/plain" });
    res.end(`ok active=${active.size}\n`);
    return;
  }
  if (req.method !== "POST" || req.url !== "/attach") {
    res.writeHead(404);
    res.end();
    return;
  }
  let body = "";
  req.on("data", (c) => { body += c; });
  req.on("end", () => {
    try {
      const { call_id: callId } = JSON.parse(body);
      if (!callId) throw new Error("missing call_id");
      attach(callId);
      res.writeHead(204);
      res.end();
    } catch (e) {
      res.writeHead(400, { "Content-Type": "text/plain" });
      res.end(String(e.message));
    }
  });
}).listen(PORT, () => {
  console.log(`[monitor] daemon listening on :${PORT}`);
});
