const KEY = "gsm2-portal-demo";

function load() {
  try {
    return JSON.parse(sessionStorage.getItem(KEY) || "null");
  } catch {
    return null;
  }
}

function seed() {
  const now = Date.now();
  const iso = (ms) => new Date(ms).toISOString();
  return {
    messages: [
      {
        id: "d1",
        direction: "in",
        peer: "+15551212",
        body: "STATUS",
        ts: iso(now - 120000),
        status: "received",
      },
      {
        id: "d2",
        direction: "out",
        peer: "+15551212",
        body: "desk is up",
        ts: iso(now - 90000),
        status: "sent",
      },
      {
        id: "d3",
        direction: "in",
        peer: "+15559876",
        body: "call me when the lamp is on",
        ts: iso(now - 30000),
        status: "received",
      },
    ],
  };
}

function save(state) {
  sessionStorage.setItem(KEY, JSON.stringify(state));
}

function getState() {
  let state = load();
  if (!state || !Array.isArray(state.messages)) {
    state = seed();
    save(state);
  }
  return state;
}

const listeners = new Set();

export function demoFetchThreads() {
  const byPeer = new Map();
  for (const msg of getState().messages) {
    const prev = byPeer.get(msg.peer);
    if (!prev || String(msg.ts) > String(prev.ts)) {
      byPeer.set(msg.peer, msg);
    }
  }
  return [...byPeer.values()]
    .sort((a, b) => String(b.ts).localeCompare(String(a.ts)))
    .map((msg) => ({ peer: msg.peer, lastBody: msg.body, lastAt: msg.ts }));
}

export function demoFetchMessages(peer) {
  return getState()
    .messages.filter((msg) => msg.peer === peer)
    .sort((a, b) => String(a.ts).localeCompare(String(b.ts)));
}

export function demoSendMessage(to, body) {
  const rec = {
    type: "message",
    id: `d${Date.now()}`,
    direction: "out",
    peer: to,
    body,
    ts: new Date().toISOString(),
    status: "sent",
  };
  const state = getState();
  state.messages.push(rec);
  save(state);
  emit(rec);
  return rec;
}

export function demoFetchCalls() {
  return [];
}

export function demoConnectEvents(onEvent) {
  listeners.add(onEvent);
  return () => listeners.delete(onEvent);
}

export function demoInjectInbound(peer, body) {
  const rec = {
    type: "message",
    id: `d${Date.now()}`,
    direction: "in",
    peer,
    body,
    ts: new Date().toISOString(),
    status: "received",
  };
  const state = getState();
  state.messages.push(rec);
  save(state);
  emit(rec);
  return rec;
}

function emit(rec) {
  for (const fn of listeners) {
    fn(rec, "message");
  }
}
