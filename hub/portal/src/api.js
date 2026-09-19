import * as demo from "./demo.js";

export function portalBase() {
  const base = import.meta.env.BASE_URL || "/portal/";
  return base.endsWith("/") ? base : `${base}/`;
}

export function apiUrl(path) {
  return `${portalBase()}api/${String(path).replace(/^\//, "")}`;
}

export function isDemoMode() {
  return new URLSearchParams(location.search).has("demo");
}

async function readJson(resp) {
  const data = await resp.json();
  if (!resp.ok) {
    const err = new Error((data && data.error) || `HTTP ${resp.status}`);
    err.status = resp.status;
    throw err;
  }
  return data;
}

export async function fetchThreads() {
  if (isDemoMode()) {
    return demo.demoFetchThreads();
  }
  const data = await readJson(await fetch(apiUrl("threads")));
  return Array.isArray(data) ? data : data.threads || [];
}

export async function fetchMessages(peer) {
  if (isDemoMode()) {
    return demo.demoFetchMessages(peer);
  }
  const url = `${apiUrl("messages")}?peer=${encodeURIComponent(peer)}`;
  const data = await readJson(await fetch(url));
  return Array.isArray(data) ? data : data.messages || [];
}

export async function sendMessage(to, body) {
  if (isDemoMode()) {
    return demo.demoSendMessage(to, body);
  }
  const data = await readJson(
    await fetch(apiUrl("messages/send"), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ to, body }),
    }),
  );
  return data.message || data;
}

export async function fetchCalls() {
  if (isDemoMode()) {
    return demo.demoFetchCalls();
  }
  const data = await readJson(await fetch(apiUrl("calls")));
  return Array.isArray(data) ? data : data.calls || [];
}

/**
 * Subscribe to hub SSE (`GET /portal/api/events`).
 * Default SSE event name is `message`; call records use `call`.
 */
export function connectEvents(onEvent, { onError } = {}) {
  if (isDemoMode()) {
    return demo.demoConnectEvents(onEvent);
  }
  const source = new EventSource(apiUrl("events"));
  const handle = (ev) => {
    let payload;
    try {
      payload = JSON.parse(ev.data);
    } catch {
      return;
    }
    onEvent(payload, ev.type || payload.type || "message");
  };
  source.addEventListener("message", handle);
  source.addEventListener("call", handle);
  source.onerror = () => {
    if (onError) onError();
  };
  return () => source.close();
}
