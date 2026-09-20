import { unreadTotal } from "./desk-state.js";
import { osNotificationsAvailable } from "./notifications.js";
import { href } from "./router.js";

export function mockBadgeHtml(isMock) {
  if (!isMock) return "";
  return `<span class="badge-mock">Local mock</span>`;
}

function notifyControlsHtml() {
  const secure = osNotificationsAvailable();
  const permission = secure && typeof Notification !== "undefined" ? Notification.permission : "denied";
  if (!secure) {
    return `
      <button id="notify-btn" type="button" class="ghost" hidden>Enable desk alerts</button>
      <p id="notify-hint" class="hint">HTTP desk — in-app toasts only. OS alerts need HTTPS.</p>
    `;
  }
  if (permission === "granted") {
    return `
      <button id="notify-btn" type="button" class="ghost" hidden>Enable desk alerts</button>
      <p id="notify-hint" class="hint" hidden></p>
    `;
  }
  if (permission === "denied") {
    return `
      <button id="notify-btn" type="button" class="ghost" hidden>Enable desk alerts</button>
      <p id="notify-hint" class="hint">Alerts blocked in this browser. In-app toasts still fire.</p>
    `;
  }
  return `
    <button id="notify-btn" type="button" class="ghost">Enable desk alerts</button>
    <p id="notify-hint" class="hint" hidden></p>
  `;
}

export function shellHtml({ page, mock, extra = "" }) {
  const total = unreadTotal();
  const badge = `<span id="unread-badge" class="nav-badge"${total ? "" : " hidden"}>${total}</span>`;
  const item = (id, path, label) => {
    const on = page === id ? "aria-current=\"page\"" : "";
    const mark = id === "messaging" ? badge : "";
    return `<a class="cockpit-link${page === id ? " on" : ""}" href="${href(path)}" ${on}>${label}${mark}</a>`;
  };
  return `
    <header class="cockpit-bar">
      <span class="cockpit-mark">GSM Hub</span>
      <nav class="cockpit-nav" aria-label="Operator">
        ${item("home", "/", "Overview")}
        ${item("messaging", "/messaging", "Messaging")}
        ${item("calls", "/calls", "Calls")}
        ${item("routing", "/routing", "Switchboard")}
        ${item("simulator", "/simulator", "Simulator")}
      </nav>
      <div class="cockpit-trail">
        ${notifyControlsHtml()}
        ${mockBadgeHtml(mock)}
        ${extra}
      </div>
    </header>
  `;
}

export function formatFixturePeer(value) {
  if (!value) return "";
  const raw = String(value).trim();
  const digits = raw.replace(/\D/g, "");
  if (digits === "15550100" || digits === "15555550100" || digits === "5550100") {
    return "Mock +1 555 0100";
  }
  if (digits === "15555550123") {
    return "Mock +1 555 0100";
  }
  if (/555/.test(digits) && digits.length >= 7) {
    return `Mock ${raw}`;
  }
  return raw;
}
