import "./routing.css";
import { shellHtml } from "../shell.js";
import {
  getSimBridgeState,
  subscribeSimBridge,
  updateRoutingSnapshot,
  viewForSim,
} from "../sim-bridge.js";
import {
  INITIAL_MOCK,
  PLAYERS,
  PRESETS,
  mockStateForMode,
  onlyGsm,
  roomFromSeated,
  seatedForPreset,
  statusForSeatedPublic,
} from "./mock-state.js";

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

export function isRoutingPath(hash) {
  const raw = String(hash || "").replace(/^#/, "");
  return raw === "/routing" || raw.startsWith("/routing?");
}

function hubUrl(path) {
  const origin = String(import.meta.env.VITE_HUB_ORIGIN || "").replace(/\/$/, "");
  return `${origin}${path}`;
}

function rosterRows(seats, flashId, readOnly) {
  return seats
    .map((s) => {
      const flash = s.id === flashId ? " flash" : "";
      let rowClass = "out";
      let action = "Join";
      let actionKind = "join";
      if (s.seated && s.deafened) {
        rowClass = "deafened";
        action = "Join";
        actionKind = "join";
      } else if (s.seated) {
        rowClass = "in";
        action = "Deafen";
        actionKind = "deafen";
      }
      const disabled = readOnly ? " disabled" : "";
      return `<tr class="${rowClass}${flash}" data-seat="${escapeHtml(s.id)}" data-action="${actionKind}">
        <td><span class="seat-dot" style="background:${s.color}"></span>${escapeHtml(s.label)}</td>
        <td><button type="button" class="seat-toggle" data-seat="${escapeHtml(s.id)}" data-action="${actionKind}"${disabled}>${action}</button></td>
      </tr>`;
    })
    .join("");
}

export function renderRouting(rootEl) {
  if (!rootEl) return;
  const session = {
    state: INITIAL_MOCK,
    live: false,
    error: "",
    busy: false,
    pulseMode: null,
    seated: [...(INITIAL_MOCK.seated || ["gsm_bus", "openclaw_bus"])],
    deafened: [],
    echoGsm: false,
    flashId: null,
  };

  function snapshot() {
    return {
      seated: [...session.seated],
      echoGsm: session.echoGsm,
      deafened: [...session.deafened],
    };
  }

  function persistSnapshot() {
    updateRoutingSnapshot(snapshot());
  }

  function applyView(view) {
    session.seated = [...view.seated];
    session.echoGsm = view.echoGsm;
    session.deafened = [...(view.deafened || [])];
  }

  function simState() {
    const sim = getSimBridgeState();
    if (!sim.active) return null;
    return viewForSim(sim.path);
  }

  async function load() {
    try {
      const resp = await fetch(hubUrl("/switchboard/state"));
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
      const data = await resp.json();
      if (!("mode" in data) && !Array.isArray(data.links)) {
        throw new Error("not switchboard state");
      }
      session.state = data;
      session.live = true;
      session.error = "";
      if (!simState()) {
        const mode = data.applied === "clear" || !data.mode ? "clear" : data.mode;
        session.seated = seatedForPreset(mode);
        session.echoGsm = false;
        session.deafened = [];
      }
    } catch {
      session.state = INITIAL_MOCK;
      session.live = false;
      if (!simState()) {
        session.seated = [...(INITIAL_MOCK.seated || ["gsm_bus", "openclaw_bus"])];
        session.echoGsm = false;
        session.deafened = [];
      }
      session.error = "";
    }
    paint(true);
  }

  async function applyMode(mode) {
    if (session.busy || simState()) return;
    session.busy = true;
    session.seated = seatedForPreset(mode);
    session.echoGsm = false;
    session.deafened = [];
    session.pulseMode = mode;
    paint(false);
    try {
      const resp = await fetch(hubUrl("/switchboard/mode"), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ mode }),
      });
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
      const data = await resp.json();
      if (data.ok === false && data.error) {
        session.error = data.error;
        session.live = true;
      } else {
        session.state = data;
        session.live = true;
        session.error = "";
      }
    } catch {
      session.state = mockStateForMode(mode);
      session.live = false;
      session.error = "";
    }
    session.busy = false;
    persistSnapshot();
    paint(false);
  }

  function seatAction(id, kind) {
    const seated = new Set(session.seated);
    const deaf = new Set(session.deafened);

    if (kind === "deafen" && seated.has(id)) {
      deaf.add(id);
    } else if (kind === "join") {
      if (seated.has(id) && deaf.has(id)) {
        deaf.delete(id);
      } else {
        seated.add(id);
        deaf.delete(id);
      }
    }

    session.seated = PLAYERS.map((p) => p.id).filter((pid) => seated.has(pid));
    session.deafened = PLAYERS.map((p) => p.id).filter((pid) => deaf.has(pid));
    if (!onlyGsm(session.seated)) session.echoGsm = false;

    session.flashId = id;
    session.state = {
      ...session.state,
      mock: true,
      status_text: statusForSeatedPublic(session.seated, session.echoGsm, session.deafened),
      message: `${kind}: ${id}`,
    };
    persistSnapshot();
    paint(false);
    window.setTimeout(() => {
      session.flashId = null;
      const row = rootEl.querySelector(`tr[data-seat="${id}"]`);
      row?.classList.remove("flash");
    }, 400);
  }

  function paint(full) {
    const sim = simState();
    const readOnly = Boolean(sim);
    const seated = sim ? sim.seated : session.seated;
    const echoGsm = sim ? sim.echoGsm : session.echoGsm;
    const deafened = sim ? sim.deafened : session.deafened;
    const room = roomFromSeated(seated, echoGsm, deafened);
    const extraBits = [];
    if (session.live) extraBits.push(`<span class="routing-badge live">Hub live</span>`);
    extraBits.push(`<span class="routing-badge mock">Roster local</span>`);
    const extra = extraBits.join("");
    const modeHint = sim ? sim.mode : room.id;
    const presets = PRESETS.map((p) => {
      const on = p.id === modeHint ? "on" : "";
      const pulse = p.id === modeHint && session.pulseMode === p.id ? "pulse" : "";
      const disabled = session.busy || readOnly ? "disabled" : "";
      return `<button type="button" class="preset ${on}" data-mode="${p.id}" ${disabled}>
        <span class="lamp ${pulse}" style="background:${p.lamp};color:${p.lamp}"></span>
        <strong>${escapeHtml(p.id === "loopback" ? "GSM ONLY" : p.id.toUpperCase())}</strong>
        <span>${escapeHtml(p.blurb)}</span>
      </button>`;
    }).join("");
    const mixClass = room.mixMinus ? "on" : room.well === "GSM echo" ? "echo" : "idle";
    const gsmSolo = onlyGsm(seated);
    const echoBox =
      gsmSolo && !readOnly
        ? `<label class="echo-toggle"><input type="checkbox" id="echo-gsm" ${echoGsm ? "checked" : ""} /> Play my voice back (GSM wire test)</label>`
        : "";
    const simBanner = sim
      ? `<p class="sim-live-banner">Simulator call active (${escapeHtml(sim.label)}). GSM is the fake handset, not the Pixel. Bay is read-only until the sim ends.</p>`
      : "";
    const roster = `
      <p class="room-kicker">${escapeHtml(room.title)}</p>
      <div class="mix-well ${mixClass}">
        <strong>${escapeHtml(room.well)}</strong>
        <span>${escapeHtml(room.caption)}</span>
      </div>
      <table class="roster">
        <thead>
          <tr><th>Party</th><th></th></tr>
        </thead>
        <tbody>${rosterRows(room.seats, session.flashId, readOnly)}</tbody>
      </table>
      ${echoBox}
    `;
    const statusText = sim
      ? `=== sim ===\n${sim.label}\n${statusForSeatedPublic(seated, echoGsm, deafened)}`
      : session.state.status_text || "";

    if (full || !rootEl.querySelector(".routing-root")) {
      rootEl.innerHTML = `
        ${shellHtml({ page: "routing", mock: !session.live, extra })}
        <div class="routing-root">
          <header class="routing-head">
            <div>
              <h1>Switchboard</h1>
              <div class="sub">Join puts a party on the line. Deafen cuts audio both ways: they cannot hear you and you cannot hear them.</div>
            </div>
          </header>
          ${simBanner}
          ${session.error ? `<p class="routing-error">${escapeHtml(session.error)}</p>` : ""}
          <div class="preset-row">${presets}</div>
          <div class="room-bay" id="room-bay">${roster}</div>
          <p class="room-hub-note">Deafen is not Leave. The party stays in the bay with no audio path. Use a preset to reset the whole line. Wire tests live on the Simulator tab.</p>
          <pre class="teletype">${escapeHtml(statusText)}</pre>
        </div>
      `;
      bind(rootEl, readOnly);
      return;
    }

    const bay = rootEl.querySelector("#room-bay");
    if (bay) bay.innerHTML = roster;
    const tty = rootEl.querySelector(".teletype");
    if (tty) tty.textContent = statusText;
    const banner = rootEl.querySelector(".sim-live-banner");
    if (sim && !banner) {
      const head = rootEl.querySelector(".routing-head");
      head?.insertAdjacentHTML("afterend", simBanner);
    } else if (!sim && banner) {
      banner.remove();
    } else if (sim && banner) {
      banner.outerHTML = simBanner;
    }
    const presetRow = rootEl.querySelector(".preset-row");
    if (presetRow) presetRow.innerHTML = presets;
    bind(rootEl, readOnly);
  }

  function bind(root, readOnly) {
    if (!readOnly) {
      root.querySelectorAll("[data-mode]").forEach((btn) => {
        btn.onclick = () => applyMode(btn.getAttribute("data-mode"));
      });
    }
    root.querySelectorAll(".seat-toggle").forEach((btn) => {
      btn.onclick = (ev) => {
        ev.stopPropagation();
        if (readOnly) return;
        seatAction(btn.getAttribute("data-seat"), btn.getAttribute("data-action"));
      };
    });
    root.querySelectorAll("tr[data-seat]").forEach((row) => {
      row.onclick = () => {
        if (readOnly) return;
        seatAction(row.getAttribute("data-seat"), row.getAttribute("data-action"));
      };
    });
    const echo = root.querySelector("#echo-gsm");
    if (echo && !readOnly) {
      echo.onchange = () => {
        session.echoGsm = echo.checked;
        session.state = {
          ...session.state,
          status_text: statusForSeatedPublic(session.seated, session.echoGsm, session.deafened),
        };
        persistSnapshot();
        paint(false);
      };
    }
  }

  const unsubSim = subscribeSimBridge((sim) => {
    if (!sim.active) {
      applyView(sim.snapshot);
      session.state = {
        ...session.state,
        status_text: statusForSeatedPublic(session.seated, session.echoGsm, session.deafened),
        message: "sim ended. bay restored",
      };
    }
    paint(false);
  });

  paint(true);
  persistSnapshot();
  load();

  return () => unsubSim();
}

export const mount = renderRouting;
export { renderRouting as render };
export default renderRouting;
