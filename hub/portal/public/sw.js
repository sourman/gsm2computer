const BASE = self.registration.scope;
const CACHE = "gsm2portal-v3";
const SHELL = [BASE, `${BASE}index.html`, `${BASE}manifest.json`, `${BASE}icon-192.png`];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(CACHE).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (url.pathname.includes("/portal/api/") || url.pathname.includes("/sms") || url.pathname === "/health" || event.request.method !== "GET") {
    return;
  }
  event.respondWith(
    fetch(event.request)
      .then((resp) => {
        const copy = resp.clone();
        caches.open(CACHE).then((cache) => cache.put(event.request, copy));
        return resp;
      })
      .catch(() => caches.match(event.request).then((cached) => cached || caches.match(BASE)))
  );
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const peer = event.notification.data && event.notification.data.peer;
  const dest = peer
    ? `${self.registration.scope}messaging/thread/${encodeURIComponent(peer)}`
    : `${self.registration.scope}messaging`;
  event.waitUntil(
    (async () => {
      const windows = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
      for (const client of windows) {
        client.postMessage({ type: "open-peer", peer: peer || null });
        if ("focus" in client) {
          return client.focus();
        }
      }
      if (self.clients.openWindow) {
        return self.clients.openWindow(dest);
      }
      return undefined;
    })(),
  );
});
