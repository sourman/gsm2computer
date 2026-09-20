import { callRecordingUrl, getCalls, getHealth } from "./api.js";
import { paintUnreadBadge } from "./desk-state.js";
import { formatFixturePeer, shellHtml } from "./shell.js";

const PLAY_EMPTY = "No OpenClaw recording for this call.";

const state = {
  calls: [],
  error: "",
  mock: false,
  playingId: null,
  playError: "",
};

let boundApp = null;
let callAudio = null;

export function getCallsPlaybackState() {
  return { playingId: state.playingId, playError: state.playError };
}

export function resetCallPlayback() {
  if (callAudio) {
    try {
      callAudio.pause();
    } catch {
      /* ignore */
    }
  }
  callAudio = null;
  state.playingId = null;
  state.playError = "";
}

function onCallAudioEnded() {
  state.playingId = null;
  state.playError = "";
  if (boundApp) paint(boundApp);
}

function onCallAudioError() {
  state.playingId = null;
  state.playError = PLAY_EMPTY;
  if (boundApp) paint(boundApp);
}

export function attachCallAudio(audio) {
  callAudio = audio;
  if (!audio) return audio;
  audio.preload = "none";
  audio.addEventListener("ended", onCallAudioEnded);
  audio.addEventListener("error", onCallAudioError);
  return audio;
}

function ensureCallAudio() {
  if (callAudio) return callAudio;
  if (typeof Audio === "undefined") return null;
  return attachCallAudio(new Audio());
}

export function toggleCallPlayback(callId, audio = ensureCallAudio()) {
  state.playError = "";
  if (!callId) return;
  if (state.playingId === callId && audio && !audio.paused) {
    audio.pause();
    state.playingId = null;
    if (boundApp) paint(boundApp);
    return;
  }
  if (!audio) {
    state.playingId = null;
    state.playError = PLAY_EMPTY;
    if (boundApp) paint(boundApp);
    return;
  }
  if (state.playingId && state.playingId !== callId) {
    audio.pause();
  }
  const url = callRecordingUrl(callId);
  audio.src = url;
  // play() must run in this turn (the click). Awaiting fetch/Range first drops
  // the user-gesture and Chrome/Safari reject play() while playingId still paints.
  let playAttempt;
  try {
    playAttempt = audio.play();
  } catch {
    state.playingId = null;
    state.playError = PLAY_EMPTY;
    if (boundApp) paint(boundApp);
    return;
  }
  state.playingId = callId;
  if (boundApp) paint(boundApp);
  if (playAttempt && typeof playAttempt.then === "function") {
    playAttempt.catch(() => {
      if (state.playingId !== callId) return;
      state.playingId = null;
      state.playError = PLAY_EMPTY;
      if (boundApp) paint(boundApp);
    });
  }
}

export async function renderCalls(app) {
  boundApp = app;
  try {
    state.error = "";
    const healthRes = await getHealth();
    state.mock = Boolean(healthRes.mock);
    const res = await getCalls();
    state.calls = Array.isArray(res.data) ? res.data : [];
    state.mock = state.mock || Boolean(res.mock);
  } catch (err) {
    state.error = err.message || String(err);
  }
  paint(app);
}

export function bindCallsApp(app) {
  boundApp = app;
}

export async function handleCallsEvent(app, type) {
  const target = app || boundApp;
  if (!target) return;
  if (type === "call") await renderCalls(target);
}

function paint(app) {
  app.innerHTML = `
    ${shellHtml({ page: "calls", mock: state.mock })}
    ${state.error ? `<div class="error">${escapeHtml(state.error)}</div>` : ""}
    <main>${renderCallList()}</main>
  `;
  paintUnreadBadge();
  bindCallCards(app);
}

function renderCallList() {
  if (!state.calls.length) {
    return `<div class="empty">Mixer idle. No GSM or simulator legs bridged.</div>`;
  }
  return `<div class="call-list">${state.calls
    .map((c) => {
      const incoming = c.direction !== "out";
      const dir = incoming ? "Incoming" : "Outgoing";
      const mode = c.switchboard_mode || "idle";
      const playing = state.playingId === c.id;
      const session = c.session_id
        ? `<div class="meta session-id">${escapeHtml(c.session_id)}</div>`
        : "";
      const playLabel = playing ? "Pause" : "Play";
      const playIcon = playing ? "❚❚" : "▶";
      return `<button type="button" class="call-card${playing ? " playing" : ""}" data-call-id="${escapeAttr(c.id)}" aria-pressed="${playing ? "true" : "false"}" title="Play OpenClaw recording">
        <div class="call-card-head">
          <span class="lamp ${incoming ? "good" : "warn"}"></span>
          <h2>${escapeHtml(dir)}</h2>
          <span class="preset-chip">${escapeHtml(mode)}</span>
          <span class="call-play" aria-hidden="true">${playIcon}</span>
          <span class="sr-only">${playLabel} recording</span>
        </div>
        <p class="fixture-num">${escapeHtml(formatFixturePeer(c.number) || c.number || "")}</p>
        <div class="call-dur">${escapeHtml(fmtDuration(c.duration_sec))}</div>
        <div class="meta">${escapeHtml(fmtTime(c.started_at))}</div>
        ${session}
      </button>`;
    })
    .join("")}</div>${
      state.playError ? `<div class="call-play-empty">${escapeHtml(state.playError)}</div>` : ""
    }`;
}

function bindCallCards(app) {
  app.querySelectorAll("[data-call-id]").forEach((el) => {
    el.addEventListener("click", () => {
      const id = el.getAttribute("data-call-id");
      if (id) toggleCallPlayback(id);
    });
  });
}

function fmtTime(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString([], { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
}

function fmtDuration(sec) {
  const n = Number(sec) || 0;
  const m = Math.floor(n / 60);
  const s = n % 60;
  return m ? `${m}m ${s}s` : `${s}s`;
}

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function escapeAttr(value) {
  return escapeHtml(value).replace(/'/g, "&#39;");
}
