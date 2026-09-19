export function osNotificationsAvailable(isSecure = globalThis.isSecureContext, hasNotification = typeof Notification !== "undefined") {
  return Boolean(isSecure && hasNotification);
}

export function notificationDelivery({
  isSecure = globalThis.isSecureContext,
  permission = typeof Notification !== "undefined" ? Notification.permission : "denied",
} = {}) {
  if (isSecure && permission === "granted") return "os";
  return "in-app";
}

export function shouldNotifySms(event, { selectedPeer, documentHidden, route } = {}) {
  if (!event || event.type !== "message") return false;
  if (event.direction === "out" && event.status === "queued") return false;
  const viewingThread =
    route === "messaging" &&
    selectedPeer &&
    selectedPeer === event.peer &&
    !documentHidden;
  return !viewingThread;
}

export function previewBody(body, max = 140) {
  const text = String(body || "").replace(/\s+/g, " ").trim();
  if (text.length <= max) return text;
  return `${text.slice(0, max - 1)}…`;
}

export function notificationCopy(event) {
  const peer = event.peer || "unknown";
  if (event.direction === "out") {
    return {
      title: `SMS sent to ${peer}`,
      body: previewBody(event.body),
    };
  }
  return {
    title: `SMS from ${peer}`,
    body: previewBody(event.body),
  };
}

export async function requestOsPermission() {
  if (!osNotificationsAvailable()) {
    throw new Error("OS notifications need a secure context (HTTPS)");
  }
  return Notification.requestPermission();
}

export async function showOsNotification({ title, body, peer, tag }) {
  const options = {
    body,
    tag: tag || `sms-${peer || "desk"}`,
    data: { peer: peer || "" },
  };
  const reg = await navigator.serviceWorker?.ready.catch(() => null);
  if (reg && typeof reg.showNotification === "function") {
    await reg.showNotification(title, { ...options, icon: "./icon-192.png" });
    return "sw";
  }
  // eslint-disable-next-line no-new
  new Notification(title, options);
  return "page";
}

export function showInAppToast({ title, body, peer, onOpen }) {
  const stack = document.getElementById("toast-stack");
  if (!stack) return;
  const toast = document.createElement("button");
  toast.type = "button";
  toast.className = "toast";
  toast.innerHTML = `<strong>${escapeHtml(title)}</strong><span>${escapeHtml(body || "")}</span>`;
  const remove = () => toast.remove();
  toast.addEventListener("click", () => {
    if (onOpen) onOpen(peer);
    remove();
  });
  stack.prepend(toast);
  window.setTimeout(remove, 8000);
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

export function bindNotifyControls({ onPermission } = {}) {
  const btn = document.getElementById("notify-btn");
  const hint = document.getElementById("notify-hint");
  const paint = () => {
    const secure = osNotificationsAvailable();
    const permission = secure ? Notification.permission : "denied";
    if (!secure) {
      btn.hidden = true;
      hint.hidden = false;
      hint.textContent = "HTTP desk — in-app toasts only. OS alerts need HTTPS.";
      return;
    }
    if (permission === "granted") {
      btn.hidden = true;
      hint.hidden = true;
      return;
    }
    if (permission === "denied") {
      btn.hidden = true;
      hint.hidden = false;
      hint.textContent = "Alerts blocked in this browser. In-app toasts still fire.";
      return;
    }
    btn.hidden = false;
    hint.hidden = true;
    btn.textContent = "Enable desk alerts";
  };
  paint();
  if (btn) {
    btn.addEventListener("click", async () => {
      try {
        const permission = await requestOsPermission();
        paint();
        if (onPermission) onPermission(permission);
      } catch (err) {
        hint.hidden = false;
        hint.textContent = err.message || String(err);
      }
    });
  }
  return paint;
}

export async function notifySmsEvent(event, ctx) {
  if (!shouldNotifySms(event, ctx)) return false;
  const copy = notificationCopy(event);
  const delivery = notificationDelivery();
  if (delivery === "os") {
    try {
      await showOsNotification({ ...copy, peer: event.peer, tag: event.id });
      return "os";
    } catch {
      showInAppToast({ ...copy, peer: event.peer, onOpen: ctx.onOpen });
      return "in-app";
    }
  }
  showInAppToast({ ...copy, peer: event.peer, onOpen: ctx.onOpen });
  return "in-app";
}
