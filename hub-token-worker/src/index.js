/**
 * GSM2Computer hub token minter (optional helper for development).
 *
 * The Android bridge phone fetches a short-lived stream token from POST /token
 * before opening its WebSocket to the hub. Deploy this worker (or run your own
 * server) and point the phone at https://<host>/token.
 */

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
};

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: CORS });
    }

    if (url.pathname === "/health") {
      return json({ ok: true, service: "gsm2computer-hub-token" });
    }

    if (url.pathname === "/token" && (request.method === "POST" || request.method === "GET")) {
      if (!env.OPENAI_API_KEY) {
        return new Response("OPENAI_API_KEY not configured", { status: 500, headers: CORS });
      }
      const model = env.STREAM_MODEL || env.REALTIME_MODEL || "gpt-realtime";
      const mintResp = await fetch("https://api.openai.com/v1/realtime/client_secrets", {
        method: "POST",
        headers: {
          Authorization: `Bearer ${env.OPENAI_API_KEY.trim()}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ session: { type: "realtime", model } }),
      });
      const text = await mintResp.text();
      if (!mintResp.ok) {
        console.error(`Token mint failed ${mintResp.status}: ${text.slice(0, 300)}`);
        return new Response(`mint failed ${mintResp.status}`, { status: 502, headers: CORS });
      }
      let value;
      let expiresAt;
      try {
        const j = JSON.parse(text);
        value = j.value || j.client_secret?.value;
        expiresAt = j.expires_at;
      } catch {
        return new Response("mint parse failed", { status: 502, headers: CORS });
      }
      if (!value) return new Response("no token in mint response", { status: 502, headers: CORS });
      return json({ value, expires_at: expiresAt, model });
    }

    return new Response("Not found", { status: 404, headers: CORS });
  },
};

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
}
