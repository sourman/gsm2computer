/** Local fallback when GET/POST /switchboard/state|mode is unreachable. */

export const PRESETS = [
  {
    id: "openclaw",
    lamp: "#c4a35a",
    blurb: "GSM + OpenClaw. Each hears the other, not themselves.",
  },
  {
    id: "loopback",
    lamp: "#3dd68c",
    blurb: "GSM only. Echo is a wire test, not the agent.",
  },
  {
    id: "conference",
    lamp: "#ff8a4c",
    blurb: "Everyone in. Each hears the others, not themselves.",
  },
  {
    id: "clear",
    lamp: "#8b9bb8",
    blurb: "Empty room.",
  },
];

export const PLAYERS = [
  { id: "gsm_bus", label: "GSM", role: "Phone / handset", color: "#f4c430" },
  { id: "openclaw_bus", label: "OpenClaw", role: "Talk agent", color: "#b8a8cc" },
  { id: "whatsapp_bus", label: "WhatsApp", role: "Reserved", color: "#3dd68c" },
  { id: "telegram_bus", label: "Telegram", role: "Reserved", color: "#5b8cff" },
];

export const PARTICIPANTS = PLAYERS;

function stereo(from, to) {
  return [
    { from: `${from}:monitor_FL`, to: `${to}:playback_FL` },
    { from: `${from}:monitor_FR`, to: `${to}:playback_FR` },
  ];
}

export function linksForMode(mode) {
  switch (mode) {
    case "openclaw":
      return stereo("openclaw_bus", "gsm_bus");
    case "loopback":
      return [];
    case "conference":
      return [
        ...stereo("gsm_bus", "openclaw_bus"),
        ...stereo("gsm_bus", "whatsapp_bus"),
        ...stereo("gsm_bus", "telegram_bus"),
        ...stereo("openclaw_bus", "gsm_bus"),
      ];
    default:
      return [];
  }
}

function labelOf(id) {
  return PLAYERS.find((p) => p.id === id)?.label || id;
}

export function seatedForPreset(mode) {
  if (mode === "openclaw") return ["gsm_bus", "openclaw_bus"];
  if (mode === "loopback") return ["gsm_bus"];
  if (mode === "conference") return PLAYERS.map((p) => p.id);
  return [];
}

export function onlyGsm(seated) {
  return seated.length === 1 && seated[0] === "gsm_bus";
}

/** Active audio mesh from seated roster, ignoring deafened parties. */
export function roomFromSeated(seated, echoGsm, deafened = []) {
  const ids = new Set(seated);
  const deaf = new Set(deafened);
  const inRoom = PLAYERS.filter((p) => ids.has(p.id));
  const heard = inRoom.filter((p) => !deaf.has(p.id));
  const gsmSolo = onlyGsm(seated);
  const echo = Boolean(echoGsm && gsmSolo && !deaf.has("gsm_bus"));

  let well = "Empty";
  let caption = "Click Join to bring a party on the line.";
  if (inRoom.length === 0) {
    well = "Empty";
    caption = "Nobody on the line.";
  } else if (deaf.size && heard.length === 0) {
    well = "All deafened";
    caption = "Everyone is in the bay but nobody can hear anyone.";
  } else if (deaf.size) {
    well = "Partial";
    caption = "Deafened parties cannot hear or be heard. Join restores audio.";
  } else if (echo) {
    well = "GSM echo";
    caption = "GSM is alone. Your voice is played back so you can test the wire. This does not go through OpenClaw.";
  } else if (gsmSolo) {
    well = "GSM only";
    caption = "GSM is alone. Echo is off, so the earpiece is silent. Turn on echo below if you want a wire test.";
  } else {
    well = "No echo";
    caption = "Each party hears the others. Nobody hears themselves.";
  }

  const seats = PLAYERS.map((p) => {
    const inLine = ids.has(p.id);
    const isDeaf = inLine && deaf.has(p.id);
    return {
      ...p,
      seated: inLine,
      deafened: isDeaf,
      talk: inLine && !isDeaf,
      listen: inLine && !isDeaf,
    };
  });

  let id = "clear";
  if (inRoom.length === 0) {
    id = "clear";
  } else if (onlyGsm(seated)) {
    id = "loopback";
  } else if (ids.has("openclaw_bus") && ids.has("gsm_bus") && ids.size === 2) {
    id = "openclaw";
  } else {
    id = "conference";
  }

  return {
    id,
    title: "Room",
    mixMinus: heard.length > 1,
    well,
    caption,
    seats,
  };
}

function statusForSeated(seated, echoGsm, deafened = []) {
  const names = seated.map(labelOf).join(" ") || "(empty)";
  const deaf = deafened.map(labelOf).join(" ") || "none";
  const echo = onlyGsm(seated) && echoGsm ? "echo on" : "no self-echo";
  return `=== bay ===\n${names}\n=== deafened ===\n${deaf}\n=== ear ===\n${echo}`;
}

export function mockStateForMode(mode) {
  const id = PRESETS.some((p) => p.id === mode) ? mode : "openclaw";
  const applied = id === "clear" ? "clear" : id;
  const seated = seatedForPreset(id);
  return {
    ok: true,
    mode: id === "clear" ? null : id,
    links: linksForMode(id),
    status_text: statusForSeated(seated, false),
    stderr: null,
    applied,
    message: id === "clear" ? "room empty" : `mode: ${id} (mock)`,
    mock: true,
    seated,
    echoGsm: false,
  };
}

export function statusForSeatedPublic(seated, echoGsm, deafened = []) {
  return statusForSeated(seated, echoGsm, deafened);
}

export const INITIAL_MOCK = mockStateForMode("openclaw");
