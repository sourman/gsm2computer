const HUB_ORIGIN = (import.meta.env.VITE_HUB_ORIGIN || "").replace(/\/$/, "");
const API = `${HUB_ORIGIN}/portal/api`;

async function jsonGet(path) {
  const resp = await fetch(path);
  if (!resp.ok) {
    const text = await resp.text();
    throw new Error(text || `HTTP ${resp.status}`);
  }
  return resp.json();
}

async function fetchOrMock(url, mockLoader) {
  try {
    const data = await jsonGet(url);
    return { data, mock: false };
  } catch {
    const mod = await mockLoader();
    return { data: mod.default ?? mod, mock: true };
  }
}

export async function getHealth() {
  return fetchOrMock(`${HUB_ORIGIN}/health`, () => import("./mocks/health.json"));
}

export async function getUsage(health) {
  const fromHealth = usageFromHealth(health);
  if (fromHealth) return { data: fromHealth, mock: false };
  const loaded = await import("./mocks/usage.json");
  return { data: loaded.default, mock: true };
}

function usageFromHealth(health) {
  if (!health || typeof health !== "object") return null;
  if (health.usage && typeof health.usage === "object") return health.usage;
  if (health.tokens && typeof health.tokens === "object") return health.tokens;
  if (health.openclaw_tokens && typeof health.openclaw_tokens === "object") {
    return health.openclaw_tokens;
  }
  return null;
}

export async function getThreads() {
  const { data } = await fetchOrMock(`${API}/threads`, () => import("./mocks/threads.json"));
  return data;
}

export async function getMessages(peer) {
  try {
    return await jsonGet(`${API}/messages?peer=${encodeURIComponent(peer)}`);
  } catch {
    const loaded = await import("./mocks/messages.json");
    const all = loaded.default;
    return all[peer] || [];
  }
}

export async function getCalls() {
  const { data, mock } = await fetchOrMock(`${API}/calls`, () => import("./mocks/calls.json"));
  return { data, mock };
}

export async function sendMessage(to, body) {
  try {
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
  } catch (err) {
    if (HUB_ORIGIN) throw err;
    return { ok: true, mock: true, to, body };
  }
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
    /* browser reconnects when hub is up; silent when using mocks */
  };
  return source;
}
