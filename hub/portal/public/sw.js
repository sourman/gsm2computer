const CACHE = "gsm2-portal-v1";

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches
      .open(CACHE)
      .then((cache) => cache.addAll(["./", "./index.html", "./manifest.json", "./icon.svg"]))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(self.clients.claim());
});

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (url.pathname.includes("/portal/api/") || url.pathname.includes("/sms")) {
    return;
  }
  if (event.request.method !== "GET") {
    return;
  }
  event.respondWith(
    caches.match(event.request).then((cached) => {
      const fetched = fetch(event.request)
        .then((resp) => {
          if (resp && resp.ok && resp.type === "basic") {
            const copy = resp.clone();
            caches.open(CACHE).then((cache) => cache.put(event.request, copy));
          }
          return resp;
        })
        .catch(() => cached);
      return cached || fetched;
    }),
  );
});

self.addEventListener("message", (event) => {
  const data = event.data || {};
  if (data.type === "notify-sms") {
    event.waitUntil(
      self.registration.showNotification(data.title, {
        body: data.body || "",
        tag: data.tag || "sms",
        data: { peer: data.peer || "" },
        icon: "./icon-192.png",
      }),
    );
  }
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const peer = event.notification.data && event.notification.data.peer;
  const dest = peer
    ? `${self.registration.scope}#/messaging?peer=${encodeURIComponent(peer)}`
    : `${self.registration.scope}#/messaging`;
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
