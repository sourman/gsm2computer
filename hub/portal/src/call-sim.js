import "./call-sim.css";
import { getHealth } from "./api.js";
import { shellHtml } from "./shell.js";
import { beginSimCall, endSimCall } from "./sim-bridge.js";

function defaultHub() {
  const env = String(import.meta.env.VITE_HUB_ORIGIN || "").replace(/\/$/, "");
  if (env) return env;
  if (import.meta.env.DEV) return "http://127.0.0.1:8787";
  if (typeof location !== "undefined") return location.origin;
  return "http://hub.mining-ling.ts.net:8787";
}

let hangup = null;

function meterHtml(id, label) {
  return `<div class="stereo-meter" id="${id}">
    <span class="meter-label">${label}</span>
    <div class="meter-channels">
      <div class="meter-channel" data-channel="l">
        <span class="channel-label">L</span>
        <div class="meter-track" role="meter" aria-valuemin="0" aria-valuemax="100" aria-valuenow="0">
          <div class="meter-fill"></div>
        </div>
      </div>
      <div class="meter-channel" data-channel="r">
        <span class="channel-label">R</span>
        <div class="meter-track" role="meter" aria-valuemin="0" aria-valuemax="100" aria-valuenow="0">
          <div class="meter-fill"></div>
        </div>
      </div>
    </div>
  </div>`;
}

export function simPanelHtml({ agentOn = true } = {}) {
  const echoOn = agentOn ? "" : "checked";
  const agent = agentOn ? "checked" : "";
  return `
    <div class="call-sim">
      <header class="routing-head">
        <div>
          <h1>Call simulator</h1>
          <div class="sub">This is the fake handset. Same WebSocket the Pixel uses.</div>
        </div>
      </header>
      <section class="controls card">
        <label>
          Hub URL
          <input id="hub-url" type="url" value="${defaultHub()}" />
        </label>
        <fieldset class="call-path" id="call-path">
          <legend>What the earpiece plays</legend>
          <div class="segmented" role="radiogroup" aria-label="Call path">
            <label class="segment">
              <input type="radio" name="call-path" value="loopback" ${echoOn} />
              <span>GSM echo</span>
            </label>
            <label class="segment">
              <input type="radio" name="call-path" value="openclaw" ${agent} />
              <span>Agent</span>
            </label>
          </div>
          <p class="call-path-hint">
            GSM echo is a wire test with nobody else on the line. Agent means OpenClaw is in the room. There is no echo-through-OpenClaw.
          </p>
        </fieldset>
        <label class="checkbox">
          <input id="tone-mode" type="checkbox" />
          Test tone instead of mic (440 Hz)
        </label>
        <div class="buttons">
          <button id="start-btn" type="button" class="primary">Start call</button>
          <button id="end-btn" type="button" disabled>End call</button>
        </div>
      </section>
      <section class="stats card">
        <div><span>Status</span><strong id="status">idle</strong></div>
        <div><span>Mic streaming</span><strong id="mic-status">no</strong></div>
        <div><span>Bytes sent</span><strong id="bytes-sent">0</strong></div>
        <div><span>Bytes received</span><strong id="bytes-recv">0</strong></div>
      </section>
      <section class="audio-meters card" aria-label="Audio level meters">
        <h2>Audio levels</h2>
        ${meterHtml("meter-mic", "Mic uplink")}
        ${meterHtml("meter-tone", "Test tone uplink")}
        ${meterHtml("meter-downlink", "Earpiece (from hub)")}
        ${meterHtml("meter-sidetone", "Local sidetone")}
      </section>
      <section class="card">
        <h2>Event log</h2>
        <pre id="log"></pre>
      </section>
    </div>
  `;
}

export async function attachCallSim(root) {
  hangup?.();
  hangup = null;
  const { mountCallSimulator } = await import("@sim/main.ts");
  hangup = mountCallSimulator(root, {
    onCallStart: (path) => beginSimCall(path),
    onCallEnd: () => endSimCall(),
  });
}

export async function renderCallSim(app) {
  const health = await getHealth();
  app.innerHTML = `
    ${shellHtml({ page: "simulator", mock: health.mock })}
    ${simPanelHtml({ agentOn: true })}
  `;
  await attachCallSim(app.querySelector(".call-sim"));
}

export function stopCallSim() {
  hangup?.();
  hangup = null;
}
