/** Cross-tab state: call simulator ↔ switchboard (read-only mirror while sim is live). */

const listeners = new Set();

let simActive = false;
let simPath = null;

/** Last switchboard roster written by the routing page (not overwritten during a sim). */
let routingSnapshot = {
  seated: ["gsm_bus", "openclaw_bus"],
  echoGsm: false,
  deafened: [],
};

export function subscribeSimBridge(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

function notify() {
  const state = getSimBridgeState();
  for (const fn of listeners) fn(state);
}

export function getSimBridgeState() {
  return { active: simActive, path: simPath, snapshot: routingSnapshot };
}

/** Routing page pushes roster changes here whenever the operator edits the bay. */
export function updateRoutingSnapshot({ seated, echoGsm, deafened }) {
  if (simActive) return;
  routingSnapshot = {
    seated: [...(seated || [])],
    echoGsm: Boolean(echoGsm),
    deafened: [...(deafened || [])],
  };
}

export function viewForSim(path) {
  if (path === "loopback") {
    return {
      seated: ["gsm_bus"],
      echoGsm: true,
      deafened: [],
      mode: "loopback",
      label: "GSM echo",
    };
  }
  return {
    seated: ["gsm_bus", "openclaw_bus"],
    echoGsm: false,
    deafened: [],
    mode: "openclaw",
    label: "Agent",
  };
}

export function beginSimCall(path) {
  simActive = true;
  simPath = path;
  notify();
}

export function endSimCall() {
  simActive = false;
  simPath = null;
  notify();
}
