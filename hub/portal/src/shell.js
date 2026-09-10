import { href } from "./router.js";

export function mockBadgeHtml(isMock) {
  if (!isMock) return "";
  return `<span class="badge-mock">Local mock</span>`;
}

export function shellHtml({ page, mock, extra = "" }) {
  const item = (id, path, label) => {
    const on = page === id ? "aria-current=\"page\"" : "";
    return `<a class="cockpit-link${page === id ? " on" : ""}" href="${href(path)}" ${on}>${label}</a>`;
  };
  return `
    <header class="cockpit-bar">
      <span class="cockpit-mark">GSM Hub</span>
      <nav class="cockpit-nav" aria-label="Operator">
        ${item("home", "/", "Overview")}
        ${item("messaging", "/messaging", "Messaging")}
        ${item("routing", "/routing", "Switchboard")}
        ${item("simulator", "/simulator", "Simulator")}
      </nav>
      <div class="cockpit-trail">
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
