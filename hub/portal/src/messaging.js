import { getCalls, getHealth, getMessages, getThreads, sendMessage } from "./api.js";
import { clearUnread, deskState, paintUnreadBadge, unreadTotal } from "./desk-state.js";
import { href, navigate, replaceLocation } from "./router.js";
import { formatFixturePeer, shellHtml } from "./shell.js";

export { unreadTotal };

const state = {
  tab: "messages",
  peer: null,
  threads: [],
  messages: [],
  calls: [],
  error: "",
  sending: false,
  mock: false,
  suppressAutoOpen: false,
};

let boundApp = null;

export function pickLatestThreadPeer(threads) {
  if (!Array.isArray(threads) || threads.length === 0) return null;
  const sorted = [...threads].sort((a, b) => String(b.lastAt || "").localeCompare(String(a.lastAt || "")));
  return sorted[0].peer;
}

export function scrollThreadToLatest(scroller) {
  if (!scroller) return;
  const run = () => {
    scroller.scrollTop = scroller.scrollHeight;
  };
  run();
  if (typeof requestAnimationFrame === "function") {
    requestAnimationFrame(run);
  }
}

export function parseMessagingRest(rest) {
  if (rest.startsWith("/thread/")) {
    state.tab = "messages";
    state.peer = decodeURIComponent(rest.slice("/thread/".length));
    state.suppressAutoOpen = false;
    return;
  }
  if (rest === "/calls") {
    state.tab = "calls";
    state.peer = null;
    return;
  }
  state.tab = "messages";
  state.peer = null;
}

export function getMessagingContext() {
  return { peer: state.peer, tab: state.tab };
}

export function resetMessagingSuppress() {
  state.suppressAutoOpen = false;
}

export function openMessagingPeer(peer) {
  if (!peer) return;
  state.suppressAutoOpen = false;
  state.tab = "messages";
  state.peer = peer;
  clearUnread(peer);
  paintUnreadBadge();
  navigate(`/messaging/thread/${encodeURIComponent(peer)}`);
}

function setMessagingPath() {
  if (state.peer) {
    navigate(`/messaging/thread/${encodeURIComponent(state.peer)}`);
  } else if (state.tab === "calls") {
    navigate("/messaging/calls");
  } else {
    navigate("/messaging");
  }
}

export async function renderMessaging(app) {
  boundApp = app;
  try {
    state.error = "";
    const healthRes = await getHealth();
    state.mock = Boolean(healthRes.mock);
    if (state.tab === "calls") {
      const res = await getCalls();
      state.calls = Array.isArray(res.data) ? res.data : [];
      state.mock = state.mock || Boolean(res.mock);
    } else {
      state.threads = await getThreads();
      if (!state.peer && !state.suppressAutoOpen) {
        const latest = pickLatestThreadPeer(state.threads);
        if (latest) {
          state.peer = latest;
          replaceLocation(`/messaging/thread/${encodeURIComponent(latest)}`);
        }
      }
      if (state.peer) {
        clearUnread(state.peer);
        state.messages = await getMessages(state.peer);
      } else {
        state.messages = [];
      }
    }
  } catch (err) {
    state.error = err.message || String(err);
  }
  paint(app);
}

function paint(app) {
  app.innerHTML = `
    ${shellHtml({ page: "messaging", mock: state.mock })}
    <nav class="tabs">
      <a class="${state.tab === "messages" ? "active" : ""}" data-tab="messages" href="${href("/messaging")}">Messages</a>
      <a class="${state.tab === "calls" ? "active" : ""}" data-tab="calls" href="${href("/messaging/calls")}">Calls</a>
    </nav>
    ${state.error ? `<div class="error">${escapeHtml(state.error)}</div>` : ""}
    <main>${state.tab === "calls" ? renderCalls() : renderMessagesLayout()}</main>
  `;
  paintUnreadBadge();
  app.querySelectorAll("[data-tab]").forEach((el) => {
    el.addEventListener("click", (ev) => {
      ev.preventDefault();
      state.tab = el.getAttribute("data-tab");
      state.peer = null;
      state.suppressAutoOpen = state.tab !== "messages";
      setMessagingPath();
    });
  });
  app.querySelectorAll("[data-peer]").forEach((btn) => {
    btn.addEventListener("click", () => {
      state.suppressAutoOpen = false;
      state.peer = btn.getAttribute("data-peer");
      clearUnread(state.peer);
      setMessagingPath();
    });
  });
  app.querySelector("[data-threads-back]")?.addEventListener("click", (ev) => {
    ev.preventDefault();
    state.suppressAutoOpen = true;
    state.peer = null;
    setMessagingPath();
  });
  bindThread(app);
}

function renderMessagesLayout() {
  const hasPeer = Boolean(state.peer);
  return `<div class="msg-layout${hasPeer ? " has-peer" : ""}">
    <div class="msg-list-pane">${renderThreads()}</div>
    <div class="msg-detail-pane">${hasPeer ? renderThreadInner() : `<div class="empty">Select a thread. Pixel forwarder idle until a peer is chosen.</div>`}</div>
  </div>`;
}

function renderThreads() {
  if (!state.threads.length) {
    return `<div class="empty">No SMS on the wire. Pixel forwarder idle.</div>`;
  }
  return `<ul class="list">${state.threads
    .map((t) => {
      const on = t.peer === state.peer ? " on" : "";
      const unread = deskState.unread[t.peer] || 0;
      const mark = unread ? `<span class="thread-unread">${unread}</span>` : "";
      return `
      <li>
        <button class="row${on}" data-peer="${escapeAttr(t.peer)}">
          <span class="row-head"><strong>${escapeHtml(formatFixturePeer(t.peer) || t.peer)}</strong>${mark}</span>
          <span>${escapeHtml(t.lastBody || "")}</span>
          <span class="meta">${escapeHtml(fmtTime(t.lastAt))}</span>
        </button>
      </li>`;
    })
    .join("")}</ul>`;
}

function renderCalls() {
  if (!state.calls.length) {
    return `<div class="empty">Mixer idle. No GSM or simulator legs bridged.</div>`;
  }
  return `<div class="call-list">${state.calls
    .map((c) => {
      const incoming = c.direction !== "out";
      const dir = incoming ? "Incoming" : "Outgoing";
      const mode = c.switchboard_mode || "idle";
      const session = c.session_id
        ? `<div class="meta session-id">${escapeHtml(c.session_id)}</div>`
        : "";
      return `<article class="call-card">
        <div class="call-card-head">
          <span class="lamp ${incoming ? "good" : "warn"}"></span>
          <h2>${escapeHtml(dir)}</h2>
          <span class="preset-chip">${escapeHtml(mode)}</span>
        </div>
        <p class="fixture-num">${escapeHtml(formatFixturePeer(c.number) || c.number || "")}</p>
        <div class="call-dur">${escapeHtml(fmtDuration(c.duration_sec))}</div>
        <div class="meta">${escapeHtml(fmtTime(c.started_at))}</div>
        ${session}
      </article>`;
    })
    .join("")}</div>`;
}

function renderThreadInner() {
  const msgs = state.messages
    .map((m) => {
      const side = m.direction === "out" ? "out" : "in";
      const st =
        m.status && m.status !== "received" && m.status !== "sent"
          ? `<span class="status-pill">${escapeHtml(m.status)}</span>`
          : "";
      return `<div class="bubble ${side}"><span class="bubble-body">${escapeHtml(m.body || "")}</span>${st}<time>${escapeHtml(fmtTime(m.ts))}</time></div>`;
    })
    .join("");
  const peerLabel = formatFixturePeer(state.peer) || state.peer;
  return `
    <div class="thread">
      <div class="thread-head">
        <a class="back" data-threads-back href="${href("/messaging")}">‹ Threads</a>
        <span class="thread-peer">${escapeHtml(peerLabel)}</span>
      </div>
      ${state.error ? `<div class="error">${escapeHtml(state.error)}</div>` : ""}
      <div class="messages" id="messages">${msgs || `<div class="empty">No messages in this thread.</div>`}</div>
      <form class="compose" id="compose">
        <textarea name="body" rows="1" placeholder="SMS to mock peer" required></textarea>
        <button type="submit" ${state.sending ? "disabled" : ""}>Send</button>
      </form>
    </div>
  `;
}

function bindThread(app) {
  scrollThreadToLatest(app.querySelector("#messages"));
  const form = app.querySelector("#compose");
  if (!form) return;
  form.addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const area = ev.target.querySelector("textarea");
    const btn = ev.target.querySelector("button");
    const body = area.value.trim();
    if (!body || state.sending) return;
    state.sending = true;
    if (btn) btn.disabled = true;
    try {
      await sendMessage(state.peer, body);
      state.sending = false;
      await renderMessaging(app);
    } catch (err) {
      state.sending = false;
      state.error = err.message || String(err);
      paint(app);
    }
  });
}

export async function handleMessagingEvent(app, type) {
  const target = app || boundApp;
  if (!target) return;
  if (type === "call" && state.tab === "calls") await renderMessaging(target);
  if (type === "message" || type === "outbox") await renderMessaging(target);
}

export function bindMessagingApp(app) {
  boundApp = app;
}

function fmtTime(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString([], { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
}

function fmtDuration(sec) {
  const n = Number(sec) || 0;
  const m = Math.floor(n / 60);
  const s = n % 60;
  return m ? `${m}m ${s}s` : `${s}s`;
}

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function escapeAttr(value) {
  return escapeHtml(value).replace(/'/g, "&#39;");
}
