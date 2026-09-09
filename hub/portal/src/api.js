const API = "/portal/api";

async function jsonGet(path) {
  const resp = await fetch(path);
  if (!resp.ok) {
    const text = await resp.text();
    throw new Error(text || `HTTP ${resp.status}`);
  }
  return resp.json();
}

export function getThreads() {
  return jsonGet(`${API}/threads`);
}

export function getMessages(peer) {
  return jsonGet(`${API}/messages?peer=${encodeURIComponent(peer)}`);
}

export function getCalls() {
  return jsonGet(`${API}/calls`);
}

export async function sendMessage(to, body) {
  const resp = await fetch(`${API}/messages/send`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ to, body }),
  });
  if (!resp.ok) {
    const text = await resp.text();
    throw new Error(text || `HTTP ${resp.status}`);
  }
  return resp.json();
}

export function connectEvents(onEvent) {
  const source = new EventSource(`${API}/events`);
  for (const type of ["message", "call", "outbox"]) {
    source.addEventListener(type, (ev) => {
      try {
        onEvent(type, JSON.parse(ev.data));
      } catch (err) {
        console.warn("sse parse", err);
      }
    });
  }
  source.onerror = () => {
    /* browser reconnects */
  };
  return source;
}
