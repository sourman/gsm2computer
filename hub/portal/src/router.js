const BASE = (import.meta.env.BASE_URL || "/").replace(/\/?$/, "/");

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

export function parseRoute() {
  const path = currentPath();
  if (path === "/messaging" || path.startsWith("/messaging/")) {
    const rest = path === "/messaging" ? "/" : path.slice("/messaging".length);
    return { page: "messaging", path, rest: normalizePath(rest) };
  }
  if (path === "/routing" || path.startsWith("/routing/")) {
    return { page: "routing", path };
  }
  if (path === "/simulator" || path.startsWith("/simulator/")) {
    return { page: "simulator", path };
  }
  return { page: "home", path: "/" };
}

export function href(path) {
  const p = normalizePath(path);
  if (usingHash()) return `#${p}`;
  const tail = p === "/" ? "" : p.replace(/^\//, "");
  return `${BASE}${tail}`;
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
