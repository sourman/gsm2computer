import "./style.css";
import { connectEvents, getCalls, getMessages, getThreads, sendMessage } from "./api.js";

const app = document.getElementById("app");
const state = {
  tab: "messages",
  peer: null,
  threads: [],
  messages: [],
  calls: [],
  error: "",
  sending: false,
};

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

function parseHash() {
  const raw = (location.hash || "").replace(/^#/, "");
  if (raw.startsWith("/thread/")) {
    state.tab = "messages";
    state.peer = decodeURIComponent(raw.slice("/thread/".length));
    return;
  }
  if (raw === "/calls") {
    state.tab = "calls";
    state.peer = null;
    return;
  }
  state.tab = "messages";
  state.peer = null;
}

function setHash() {
  if (state.peer) {
    location.hash = `/thread/${encodeURIComponent(state.peer)}`;
  } else if (state.tab === "calls") {
    location.hash = "/calls";
  } else {
    location.hash = "/";
  }
}

async function refresh() {
  try {
    state.error = "";
    if (state.tab === "calls") {
      state.calls = await getCalls();
    } else {
      state.threads = await getThreads();
      if (state.peer) {
        state.messages = await getMessages(state.peer);
      }
    }
  } catch (err) {
    state.error = err.message || String(err);
  }
  render();
}

function render() {
  if (state.peer) {
    renderThread();
    return;
  }
  app.innerHTML = `
    <header class="app-bar">
      <h1>GSM Hub</h1>
    </header>
    <nav class="tabs">
      <button class="${state.tab === "messages" ? "active" : ""}" data-tab="messages">Messages</button>
      <button class="${state.tab === "calls" ? "active" : ""}" data-tab="calls">Calls</button>
    </nav>
    ${state.error ? `<div class="error">${escapeHtml(state.error)}</div>` : ""}
    <main>${state.tab === "calls" ? renderCalls() : renderThreads()}</main>
  `;
  app.querySelectorAll("[data-tab]").forEach((btn) => {
    btn.addEventListener("click", () => {
      state.tab = btn.getAttribute("data-tab");
      state.peer = null;
      setHash();
      refresh();
    });
  });
  app.querySelectorAll("[data-peer]").forEach((btn) => {
    btn.addEventListener("click", () => {
      state.peer = btn.getAttribute("data-peer");
      setHash();
      refresh();
    });
  });
}

function renderThreads() {
  if (!state.threads.length) {
    return `<div class="empty">No conversations yet.<br/>Inbound SMS from the Pixel will show up here.</div>`;
  }
  return `<ul class="list">${state.threads
    .map(
      (t) => `
      <li>
        <button class="row" data-peer="${escapeAttr(t.peer)}">
          <strong>${escapeHtml(t.peer)}</strong>
          <span>${escapeHtml(t.lastBody || "")}</span>
          <span class="meta">${escapeHtml(fmtTime(t.lastAt))}</span>
        </button>
      </li>`
    )
    .join("")}</ul>`;
}

function renderCalls() {
  if (!state.calls.length) {
    return `<div class="empty">No bridged calls yet.</div>`;
  }
  return state.calls
    .map((c) => {
      const dir = c.direction === "out" ? "Outgoing" : "Incoming";
      const mode = c.switchboard_mode ? ` · ${c.switchboard_mode}` : "";
      const session = c.session_id ? `<div class="meta">session ${escapeHtml(c.session_id)}</div>` : "";
      return `<article class="call-card">
        <h2>${escapeHtml(dir)} ${escapeHtml(c.number || "")}</h2>
        <div class="meta">${escapeHtml(fmtTime(c.started_at))} · ${escapeHtml(fmtDuration(c.duration_sec))}${escapeHtml(mode)}</div>
        ${session}
      </article>`;
    })
    .join("");
}

function renderThread() {
  const msgs = state.messages
    .map((m) => {
      const side = m.direction === "out" ? "out" : "in";
      const st = m.status && m.status !== "received" && m.status !== "sent"
        ? `<span class="status-pill">${escapeHtml(m.status)}</span>`
        : "";
      return `<div class="bubble ${side}">${escapeHtml(m.body || "")}${st}<time>${escapeHtml(fmtTime(m.ts))}</time></div>`;
    })
    .join("");
  app.innerHTML = `
    <div class="thread">
      <header class="app-bar">
        <button class="back" id="back">‹ Threads</button>
        <h1>${escapeHtml(state.peer)}</h1>
        <span></span>
      </header>
      ${state.error ? `<div class="error">${escapeHtml(state.error)}</div>` : ""}
      <div class="messages" id="messages">${msgs || `<div class="empty">No messages in this thread.</div>`}</div>
      <form class="compose" id="compose">
        <textarea name="body" rows="1" placeholder="Text message" required></textarea>
        <button type="submit" ${state.sending ? "disabled" : ""}>Send</button>
      </form>
    </div>
  `;
  const scroller = app.querySelector("#messages");
  if (scroller) scroller.scrollTop = scroller.scrollHeight;
  app.querySelector("#back").addEventListener("click", () => {
    state.peer = null;
    setHash();
    refresh();
  });
  app.querySelector("#compose").addEventListener("submit", async (ev) => {
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
      await refresh();
    } catch (err) {
      state.sending = false;
      state.error = err.message || String(err);
      render();
    }
  });
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

window.addEventListener("hashchange", () => {
  parseHash();
  refresh();
});

if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("/portal/sw.js").catch((err) => console.warn("sw", err));
}

parseHash();
connectEvents((type) => {
  if (type === "call" && state.tab === "calls") refresh();
  if (type === "message" || type === "outbox") refresh();
});
refresh();
