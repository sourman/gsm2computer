import { getCalls, getHealth, getUsage } from "./api.js";
import { href } from "./router.js";
import { formatFixturePeer, shellHtml } from "./shell.js";

export async function renderDashboard(app) {
  const healthRes = await getHealth();
  const health = healthRes.data || {};
  const callsRes = await getCalls();
  const calls = Array.isArray(callsRes.data) ? callsRes.data : [];
  const usageRes = await getUsage(healthRes.mock ? null : health);
  const last = lastCall(calls, health.last_call_tap);
  const mock = Boolean(healthRes.mock || callsRes.mock);
  const mode = health.mode || "idle";

  app.innerHTML = `
    ${shellHtml({ page: "home", mock })}
    <main class="dash">
      <div class="dash-col">
        <section class="card">
          <h2>Hub status</h2>
          ${renderLamps(health)}
        </section>
        <section class="card">
          <h2>Last call</h2>
          ${last ? renderLastCall(last) : `<p class="muted">No calls recorded.</p>`}
        </section>
      </div>
      <div class="dash-col">
        <section class="card">
          <h2>Token / usage</h2>
          ${renderUsage(usageRes.data)}
        </section>
      </div>
      <section class="dash-strip">
        <div class="dash-strip-preset">
          <span class="lamp-label">Routing preset</span>
          <span class="lamp-value">${escapeHtml(String(mode).toUpperCase())}</span>
        </div>
        <pre class="teletype dash-teletype">${escapeHtml(pwSnippet(mode))}</pre>
        <div class="dash-strip-links">
          <a class="primary-link secondary" href="${href("/routing")}">Open switchboard</a>
          <a class="primary-link secondary" href="${href("/simulator")}">Call simulator</a>
        </div>
      </section>
      <p class="dash-tail">Last preset applied: ${escapeHtml(String(mode).toUpperCase())} · ${escapeHtml(pwSnippet(mode))}${last ? ` · last call ${escapeHtml(relativeTime(last.started || ""))}` : ""}</p>
    </main>
  `;
}

function pwSnippet(mode) {
  if (mode === "openclaw") {
    return "openclaw_bus:monitor_* |-> gsm_bus:playback_*";
  }
  if (mode === "loopback") {
    return "(no pw-link; uplink → gsm_bus.monitor)";
  }
  if (mode === "conference") {
    return "conference star: GSM and OpenClaw; WA/TG listen GSM only";
  }
  if (mode === "clear") {
    return "(buses idle)";
  }
  return String(mode);
}

function renderLamps(health) {
  const ok = Boolean(health.ok);
  const talk = health.openclaw_talk || "unknown";
  const mode = health.mode || "idle";
  return `<div class="lamp-row">
    <div class="lamp-cell">
      <span class="lamp ${ok ? "good" : "bad"}"></span>
      <span class="lamp-copy">
        <span class="lamp-label">Hub</span>
        <span class="lamp-value">${ok ? "OK" : "Down"}</span>
      </span>
    </div>
    <div class="lamp-cell">
      <span class="lamp ${talk === "webrtc-ui" ? "good" : "warn"}"></span>
      <span class="lamp-copy">
        <span class="lamp-label">Talk path</span>
        <span class="lamp-value">${escapeHtml(talk)}</span>
      </span>
    </div>
    <div class="lamp-cell">
      <span class="lamp preset-lamp"></span>
      <span class="lamp-copy">
        <span class="lamp-label">PipeWire</span>
        <span class="lamp-value">${escapeHtml(String(mode).toUpperCase())}</span>
      </span>
    </div>
  </div>`;
}

function lastCall(calls, tap) {
  const row = calls[0] || null;
  const tapSeconds = tapSecondsFrom(tap);
  const tapNumber = tapNumberFrom(tap);
  if (!row && !tap) return null;
  const started = row?.started_at || tap?.ended_at || tap?.started_at || null;
  const duration = row?.duration_sec ?? tapSeconds ?? null;
  const number = row?.number || tapNumber || "";
  return { started, duration, number, fromTap: Boolean(tap) && !row };
}

function tapSecondsFrom(tap) {
  if (!tap) return null;
  const streams = tap.streams || {};
  let max = 0;
  for (const s of Object.values(streams)) {
    const n = Number(s?.seconds);
    if (n > max) max = n;
  }
  if (max > 0) return Math.round(max);
  const mixes = tap.mixes || {};
  for (const s of Object.values(mixes)) {
    const n = Number(s?.seconds);
    if (n > max) max = n;
  }
  return max > 0 ? Math.round(max) : null;
}

function tapNumberFrom(tap) {
  if (!tap) return "";
  const meta = tap.meta || {};
  return meta.number || meta.peer || meta.from || "";
}

function renderLastCall(last) {
  const when = last.started ? relativeTime(last.started) : "time unknown";
  const dur = last.duration != null ? fmtDuration(last.duration) : "duration unknown";
  const num = last.number ? formatFixturePeer(last.number) : "number unknown";
  return `<p class="fixture-num">${escapeHtml(num)}</p>
    <p class="muted last-call-meta">${escapeHtml(dur)}<br/>${escapeHtml(when)}</p>`;
}

function renderUsage(usage) {
  if (!usage) return `<p class="muted">No usage data.</p>`;
  const label = usage.label || "Usage";
  const used = usage.used == null ? "n/a" : String(usage.used);
  const limit = usage.limit == null ? "n/a" : String(usage.limit);
  const note = usage.note ? `<p class="muted">${escapeHtml(usage.note)}</p>` : "";
  return `<p>${escapeHtml(label)}</p>
    <div class="kv">
      <span class="lamp-label">Used</span><span class="lamp-value">${escapeHtml(used)}</span>
      <span class="lamp-label">Limit</span><span class="lamp-value">${escapeHtml(limit)}</span>
    </div>
    ${note}`;
}

function fmtDuration(sec) {
  const n = Number(sec) || 0;
  const m = Math.floor(n / 60);
  const s = n % 60;
  return m ? `${m}m ${s}s` : `${s}s`;
}

function relativeTime(iso) {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const diff = Date.now() - d.getTime();
  const abs = Math.abs(diff);
  const rtf = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" });
  const divisions = [
    { amount: 60, unit: "second" },
    { amount: 60, unit: "minute" },
    { amount: 24, unit: "hour" },
    { amount: 7, unit: "day" },
    { amount: 4.34524, unit: "week" },
    { amount: 12, unit: "month" },
    { amount: Number.POSITIVE_INFINITY, unit: "year" },
  ];
  let duration = abs / 1000;
  for (const division of divisions) {
    if (duration < division.amount) {
      const value = Math.round(duration) * (diff >= 0 ? -1 : 1);
      return rtf.format(value, division.unit);
    }
    duration /= division.amount;
  }
  return d.toLocaleString();
}

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}
