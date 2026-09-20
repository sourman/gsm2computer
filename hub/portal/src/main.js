import "./style.css";
import { connectEvents } from "./api.js";
import { renderCallSim, stopCallSim } from "./call-sim.js";
import { renderDashboard } from "./dashboard.js";
import { bumpUnread, paintUnreadBadge } from "./desk-state.js";
import {
  bindMessagingApp,
  getMessagingContext,
  handleMessagingEvent,
  openMessagingPeer,
  parseMessagingRest,
  renderMessaging,
  resetMessagingSuppress,
} from "./messaging.js";
import { bindNotifyControls, notifySmsEvent } from "./notifications.js";
import { href, onRouteChange, parseRoute } from "./router.js";

const app = document.getElementById("app");
const routingLoaders = import.meta.glob("./routing/index.{js,ts,tsx,jsx}");
let routingUnsub = null;
let deskEvents = null;

function notifyRoute() {
  const route = parseRoute();
  const ctx = getMessagingContext();
  if (route.page === "messaging" && ctx.tab === "messages") return "messaging";
  return route.page;
}

async function onLiveEvent(type, data) {
  const event = data && typeof data === "object" ? { ...data, type: data.type || type } : { type };
  if (type === "message") {
    const ctx = getMessagingContext();
    const notified = await notifySmsEvent(event, {
      selectedPeer: ctx.peer,
      documentHidden: document.hidden,
      route: notifyRoute(),
      onOpen: (peer) => {
        if (peer) openMessagingPeer(peer);
      },
    });
    const viewingThread =
      parseRoute().page === "messaging" &&
      ctx.tab === "messages" &&
      ctx.peer === event.peer &&
      !document.hidden;
    if (notified && event.peer && !viewingThread) {
      bumpUnread(event.peer);
      paintUnreadBadge();
    }
  }
  if (parseRoute().page === "messaging") {
    await handleMessagingEvent(app, type);
  } else {
    paintUnreadBadge();
  }
}

function startDeskEvents() {
  if (deskEvents) return;
  deskEvents = connectEvents((type, payload) => {
    onLiveEvent(type, payload).catch((err) => console.warn(err));
  });
}

function registerServiceWorker() {
  const swUrl = `${import.meta.env.BASE_URL}sw.js`.replace(/\/{2,}/g, "/");
  if (!("serviceWorker" in navigator)) return;
  navigator.serviceWorker.register(swUrl).catch((err) => console.warn("sw", err));
  navigator.serviceWorker.addEventListener("message", (event) => {
    if (event.data?.type === "open-peer" && event.data.peer) {
      openMessagingPeer(event.data.peer);
    } else if (event.data?.type === "open-peer") {
      resetMessagingSuppress();
      window.location.href = href("/messaging");
    }
  });
}

async function render() {
  routingUnsub?.();
  routingUnsub = null;
  const route = parseRoute();
  if (route.page !== "messaging") resetMessagingSuppress();
  if (route.page !== "simulator" && route.page !== "routing") stopCallSim();
  if (route.page === "messaging") {
    parseMessagingRest(route.rest);
    bindMessagingApp(app);
    await renderMessaging(app);
  } else if (route.page === "routing") {
    await renderRouting();
  } else if (route.page === "simulator") {
    await renderCallSim(app);
  } else {
    await renderDashboard(app);
  }
  bindNotifyControls();
  paintUnreadBadge();
}

async function renderRouting() {
  const loaders = Object.values(routingLoaders);
  if (loaders.length) {
    const mod = await loaders[0]();
    if (typeof mod.mount === "function") {
      routingUnsub = await mod.mount(app) || null;
      return;
    }
    if (typeof mod.render === "function") {
      await mod.render(app);
      return;
    }
    if (typeof mod.default === "function") {
      await mod.default(app);
      return;
    }
  }
  app.innerHTML = `
    <header class="app-bar">
      <a class="back" href="${href("/")}">‹ Home</a>
      <h1>Routing</h1>
      <span></span>
    </header>
    <main class="dash">
      <p class="muted">Routing UI is not in this build yet. The hub switchboard still lives on the phone SMS commands.</p>
    </main>
  `;
}

onRouteChange(() => {
  render();
});

registerServiceWorker();
startDeskEvents();
render();
