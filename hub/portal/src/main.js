import { connectEvents, fetchCalls, fetchMessages, fetchThreads, isDemoMode, sendMessage } from "./api.js";
import {
  formatClock,
  hashFor,
  parseHash,
  pickLatestThreadPeer,
  scrollThreadToLatest,
  unreadTotal,
} from "./messaging.js";
import { bindNotifyControls, notifySmsEvent } from "./notifications.js";
import { demoInjectInbound } from "./demo.js";

const state = {
  route: "messaging",
  peer: null,
  threads: [],
  messages: [],
  calls: [],
  unread: {},
  suppressAutoOpen: false,
};

const els = {
  viewMessaging: document.getElementById("view-messaging"),
  viewCalls: document.getElementById("view-calls"),
  navMessaging: document.getElementById("nav-messaging"),
  navCalls: document.getElementById("nav-calls"),
  threadList: document.getElementById("thread-list"),
  threadPane: document.getElementById("thread-pane"),
  threadTitle: document.getElementById("thread-title"),
  threadScroller: document.getElementById("thread-scroller"),
  messageList: document.getElementById("message-list"),
  composeForm: document.getElementById("compose-form"),
  composeBody: document.getElementById("compose-body"),
  backBtn: document.getElementById("back-to-threads"),
  unreadBadge: document.getElementById("unread-badge"),
  callList: document.getElementById("call-list"),
};

function applyRouteFromHash() {
  const parsed = parseHash();
  state.route = parsed.route;
  state.peer = parsed.peer;
  if (parsed.peer) state.suppressAutoOpen = false;
}

function setHash(next, replace = false) {
  const hash = hashFor(next);
  if (replace) {
    history.replaceState(null, "", `${location.pathname}${location.search}${hash}`);
  } else if (location.hash !== hash) {
    location.hash = hash;
  }
}

function paintNav() {
  els.navMessaging.classList.toggle("is-active", state.route === "messaging");
  els.navCalls.classList.toggle("is-active", state.route === "calls");
  els.viewMessaging.hidden = state.route !== "messaging";
  els.viewCalls.hidden = state.route !== "calls";
  const total = unreadTotal(state.unread);
  els.unreadBadge.hidden = total === 0;
  els.unreadBadge.textContent = String(total);
}

function renderThreads() {
  if (!state.threads.length) {
    els.threadList.innerHTML = `<p class="empty">No traffic on the desk yet.</p>`;
    return;
  }
  els.threadList.innerHTML = state.threads
    .map((thread) => {
      const active = thread.peer === state.peer ? " is-active" : "";
      const unread = state.unread[thread.peer] || 0;
      const mark = unread ? `<span class="dot">${unread}</span>` : "";
      return `<button type="button" class="thread-row${active}" data-peer="${encodeURIComponent(thread.peer)}">
        <span class="peer">${escapeHtml(thread.peer)}</span>
        ${mark}
        <span class="snippet">${escapeHtml(thread.lastBody || "")}</span>
        <span class="when">${escapeHtml(formatClock(thread.lastAt))}</span>
      </button>`;
    })
    .join("");
  els.threadList.querySelectorAll("[data-peer]").forEach((btn) => {
    btn.addEventListener("click", () => openPeer(decodeURIComponent(btn.getAttribute("data-peer"))));
  });
}

function renderMessages() {
  const open = Boolean(state.peer);
  els.composeForm.hidden = !open;
  els.backBtn.hidden = !open;
  document.getElementById("view-messaging").classList.toggle("thread-open", open);
  els.threadTitle.textContent = open ? state.peer : "Select a thread";
  if (!open) {
    els.messageList.innerHTML = `<p class="empty">Pick a number from the left, or wait for the latest thread to open.</p>`;
    return;
  }
  if (!state.messages.length) {
    els.messageList.innerHTML = `<p class="empty">No messages in this thread.</p>`;
    return;
  }
  els.messageList.innerHTML = state.messages
    .map((msg) => {
      const mine = msg.direction === "out";
      const status = msg.status && msg.status !== "received" ? `<em>${escapeHtml(msg.status)}</em>` : "";
      return `<article class="bubble ${mine ? "out" : "in"}">
        <p>${escapeHtml(msg.body || "")}</p>
        <time>${escapeHtml(formatClock(msg.ts))} ${status}</time>
      </article>`;
    })
    .join("");
  scrollThreadToLatest(els.threadScroller);
}

function renderCalls() {
  if (!state.calls.length) {
    els.callList.innerHTML = `<p class="empty">No call tickets yet. Hangups from the gateway will land here.</p>`;
    return;
  }
  els.callList.innerHTML = state.calls
    .map((call) => {
      return `<article class="call-row">
        <strong>${escapeHtml(call.number || "unknown")}</strong>
        <span>${escapeHtml(call.direction || "")} · ${escapeHtml(formatClock(call.startedAt))}</span>
        <span>${call.durationSec != null ? `${call.durationSec}s` : ""} ${escapeHtml(call.switchboardMode || "")}</span>
      </article>`;
    })
    .join("");
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

async function refreshThreads() {
  state.threads = await fetchThreads();
  maybeAutoOpen();
  renderThreads();
}

function maybeAutoOpen() {
  if (state.route !== "messaging") return;
  if (state.peer || state.suppressAutoOpen) return;
  const latest = pickLatestThreadPeer(state.threads);
  if (!latest) return;
  state.peer = latest;
  setHash({ route: "messaging", peer: latest }, true);
}

async function refreshMessages() {
  if (!state.peer) {
    state.messages = [];
    renderMessages();
    return;
  }
  state.messages = await fetchMessages(state.peer);
  renderMessages();
}

async function refreshCalls() {
  state.calls = await fetchCalls();
  renderCalls();
}

export async function openPeer(peer, { replace = false } = {}) {
  state.suppressAutoOpen = false;
  state.peer = peer;
  delete state.unread[peer];
  setHash({ route: "messaging", peer }, replace);
  paintNav();
  renderThreads();
  await refreshMessages();
}

function showThreadList() {
  state.suppressAutoOpen = true;
  state.peer = null;
  state.messages = [];
  setHash({ route: "messaging", peer: null });
  renderThreads();
  renderMessages();
}

async function onRoute() {
  applyRouteFromHash();
  paintNav();
  if (state.route === "calls") {
    await refreshCalls();
    return;
  }
  await refreshThreads();
  await refreshMessages();
}

async function onLiveEvent(event) {
  if (!event || event.type !== "message") {
    if (event && event.type === "call") {
      await refreshCalls();
    }
    return;
  }
  await refreshThreads();
  if (state.peer && event.peer === state.peer) {
    const exists = state.messages.some((msg) => msg.id === event.id);
    if (!exists) state.messages.push(event);
    else {
      state.messages = state.messages.map((msg) => (msg.id === event.id ? { ...msg, ...event } : msg));
    }
    renderMessages();
  }
  const notified = await notifySmsEvent(event, {
    selectedPeer: state.peer,
    documentHidden: document.hidden,
    route: state.route,
    onOpen: (peer) => {
      if (peer) openPeer(peer);
    },
  });
  const viewingThread = state.route === "messaging" && state.peer === event.peer && !document.hidden;
  if (notified && event.peer && !viewingThread) {
    state.unread[event.peer] = (state.unread[event.peer] || 0) + 1;
    paintNav();
    renderThreads();
  }
}

function registerServiceWorker() {
  if (!("serviceWorker" in navigator)) return;
  const swUrl = `${import.meta.env.BASE_URL}sw.js`;
  navigator.serviceWorker.register(swUrl).catch((err) => {
    console.warn("service worker not registered", err);
  });
  navigator.serviceWorker.addEventListener("message", (event) => {
    if (event.data?.type === "open-peer" && event.data.peer) {
      openPeer(event.data.peer);
    }
  });
}

function wireUi() {
  els.backBtn.addEventListener("click", showThreadList);
  els.composeForm.addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const body = els.composeBody.value.trim();
    if (!body || !state.peer) return;
    els.composeForm.querySelector("button").disabled = true;
    try {
      const rec = await sendMessage(state.peer, body);
      els.composeBody.value = "";
      if (rec && !state.messages.some((msg) => msg.id === rec.id)) {
        state.messages.push(rec);
        renderMessages();
      }
      await refreshThreads();
    } catch (err) {
      els.composeBody.setCustomValidity(err.message || "send failed");
      els.composeForm.reportValidity();
      els.composeBody.setCustomValidity("");
    } finally {
      els.composeForm.querySelector("button").disabled = false;
    }
  });
  els.navMessaging.addEventListener("click", () => {
    state.suppressAutoOpen = false;
  });
  window.addEventListener("hashchange", () => {
    onRoute().catch((err) => console.warn(err));
  });
  if (isDemoMode()) {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "ghost";
    btn.textContent = "Simulate inbound";
    btn.addEventListener("click", () => {
      demoInjectInbound("+15559876", `lamp ping ${new Date().toLocaleTimeString()}`);
    });
    document.querySelector(".mast-alerts").append(btn);
  }
}

async function boot() {
  registerServiceWorker();
  bindNotifyControls();
  wireUi();
  applyRouteFromHash();
  connectEvents((event) => {
    onLiveEvent(event).catch((err) => console.warn(err));
  });
  await onRoute();
}

boot().catch((err) => {
  console.error(err);
  els.messageList.innerHTML = `<p class="empty">Desk failed to load: ${escapeHtml(err.message || err)}</p>`;
});
