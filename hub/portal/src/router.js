const env = import.meta.env || {};
const BASE = (env.BASE_URL || "/").replace(/\/?$/, "/");

export function usingHash() {
  return (location.hash || "").startsWith("#/");
}

export function currentPath() {
  if (usingHash()) {
    const raw = location.hash.slice(1);
    return normalizePath(raw.split("?")[0]);
  }
  let path = location.pathname || "/";
  const prefix = BASE.replace(/\/$/, "") || "";
  if (prefix && (path === prefix || path.startsWith(`${prefix}/`))) {
    path = path.slice(prefix.length) || "/";
  }
  return normalizePath(path);
}

export function normalizePath(path) {
  let p = path || "/";
  if (!p.startsWith("/")) p = `/${p}`;
  if (p.length > 1 && p.endsWith("/")) p = p.slice(0, -1);
  return p || "/";
}

export function parseRouteFromPath(path) {
  const p = normalizePath(path);
  if (p === "/calls" || p.startsWith("/calls/")) {
    return { page: "calls", path: p };
  }
  if (p === "/messaging" || p.startsWith("/messaging/")) {
    const rest = p === "/messaging" ? "/" : p.slice("/messaging".length);
    return { page: "messaging", path: p, rest: normalizePath(rest) };
  }
  if (p === "/routing" || p.startsWith("/routing/")) {
    return { page: "routing", path: p };
  }
  if (p === "/simulator" || p.startsWith("/simulator/")) {
    return { page: "simulator", path: p };
  }
  return { page: "home", path: "/" };
}

export function parseRoute() {
  return parseRouteFromPath(currentPath());
}

export function href(path) {
  const p = normalizePath(path);
  if (usingHash()) return `#${p}`;
  const tail = p === "/" ? "" : p.replace(/^\//, "");
  return `${BASE}${tail}`;
}

export function replaceLocation(path) {
  const p = normalizePath(path);
  if (usingHash()) {
    history.replaceState(null, "", `${location.pathname}${location.search}#${p}`);
    return;
  }
  history.replaceState({}, "", href(p));
}

export function navigate(path) {
  const p = normalizePath(path);
  if (usingHash()) {
    location.hash = p;
    return;
  }
  const url = href(p);
  if (`${location.pathname}${location.search}` === url && !location.hash) {
    window.dispatchEvent(new PopStateEvent("popstate"));
    return;
  }
  history.pushState({}, "", url);
  window.dispatchEvent(new PopStateEvent("popstate"));
}

export function onRouteChange(fn) {
  window.addEventListener("hashchange", fn);
  window.addEventListener("popstate", fn);
}
