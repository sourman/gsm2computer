import "./style.css";
import { renderCallSim, stopCallSim } from "./call-sim.js";
import { renderDashboard } from "./dashboard.js";
import { parseMessagingRest, renderMessaging, startMessagingEvents } from "./messaging.js";
import { href, onRouteChange, parseRoute } from "./router.js";

const app = document.getElementById("app");
const routingLoaders = import.meta.glob("./routing/index.{js,ts,tsx,jsx}");
let routingUnsub = null;

async function render() {
  routingUnsub?.();
  routingUnsub = null;
  const route = parseRoute();
  if (route.page !== "simulator" && route.page !== "routing") stopCallSim();
  if (route.page === "messaging") {
    parseMessagingRest(route.rest);
    startMessagingEvents(app);
    await renderMessaging(app);
    return;
  }
  if (route.page === "routing") {
    await renderRouting();
    return;
  }
  if (route.page === "simulator") {
    await renderCallSim(app);
    return;
  }
  await renderDashboard(app);
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

const swUrl = `${import.meta.env.BASE_URL}sw.js`.replace(/\/{2,}/g, "/");
if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register(swUrl).catch((err) => console.warn("sw", err));
}

render();
