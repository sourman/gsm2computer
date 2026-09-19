export function pickLatestThreadPeer(threads) {
  if (!Array.isArray(threads) || threads.length === 0) return null;
  const sorted = [...threads].sort((a, b) => String(b.lastAt || "").localeCompare(String(a.lastAt || "")));
  return sorted[0].peer;
}

export function parseHash() {
  const raw = (location.hash || "#/messaging").replace(/^#/, "");
  const [pathPart, queryPart] = raw.split("?");
  const path = pathPart || "/messaging";
  const params = new URLSearchParams(queryPart || "");
  if (path.startsWith("/calls")) {
    return { route: "calls", peer: null };
  }
  return { route: "messaging", peer: params.get("peer") };
}

export function hashFor({ route, peer }) {
  if (route === "calls") return "#/calls";
  if (peer) return `#/messaging?peer=${encodeURIComponent(peer)}`;
  return "#/messaging";
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

export function formatClock(ts) {
  if (!ts) return "";
  const date = new Date(ts);
  if (Number.isNaN(date.getTime())) return String(ts);
  return date.toLocaleString(undefined, {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

export function unreadTotal(unread) {
  return Object.values(unread || {}).reduce((sum, n) => sum + (Number(n) || 0), 0);
}
