/**
 * Create gsm2computer project tickets in Linear (safwatly workspace).
 *
 * Prereq: safwatly Linear via mcporter (once per repo):
 *   mcporter auth linear
 *   # pick safwatly in browser — tokens land in .mcporter/oauth/linear
 *
 * Usage:
 *   bun run scripts/create-linear-tickets.ts --dry-run
 *   bun run scripts/create-linear-tickets.ts
 */
import { execFileSync } from "node:child_process";

const PROJECT = "gsm2computer";

type Ticket = {
  title: string;
  description: string;
  priority?: 1 | 2 | 3 | 4; // 1=urgent .. 4=low
};

const TICKETS: Ticket[] = [
  {
    title: "Infra: safwat-eu Tailscale + OpenClaw gateway",
    description: `## Done criteria
- EC2 \`safwat-eu\` on Tailscale mesh (pixel-7 reachable)
- OpenClaw installed, OpenAI + Z.AI keys configured
- Gateway running with \`gateway.bind=tailnet\`

## Notes
Foundation for phone-only computer stack. Headed display available (not headless) for apps that block headless.`,
    priority: 2,
  },
  {
    title: "Spike: evaluate Linux audio switchboard software",
    description: `## Goal
Pick base layer for VB-Audio-Cable-style routing on Linux (PipeWire).

## Candidates
- [PipeWeaver](https://github.com/pipeweaver/pipeweaver) — matrix mixing, web UI, API, streaming-focused
- [pipewire-web-remote](https://github.com/oudeis01/pipewire-web-remote) — live patchbay web UI
- [pipeflow](https://github.com/trusch/pipeflow) — graph editor + gRPC remote control
- [patchcord](https://github.com/Milkshiift/patchcord) — JSON stdio API for virtual sinks/routes

## Output
Recommendation doc: which handles multi-producer/multi-consumer mix, live web state, programmatic API.`,
    priority: 2,
  },
  {
    title: "PipeWire virtual audio bus on safwat-eu",
    description: `## Goal
Create persistent virtual sinks/sources (VB-cable equivalent) on the headed EC2 box.

## Requirements
- Multiple virtual devices for switchboard participants (GSM, OpenClaw, WhatsApp, Telegram, …)
- OS can route one source to many sinks and mix many sources into one sink
- Survive reboot (WirePlumber drop-ins or daemon-managed)

## Depends on
Audio switchboard software spike.`,
    priority: 2,
  },
  {
    title: "Audio switchboard core (routing matrix service)",
    description: `## Architecture
Hub is a **switchboard**, not an app hierarchy. All participants are equal peers:
- GSM bridge phone
- OpenClaw (or any agent)
- WhatsApp, Telegram, other apps

Each can produce and consume audio simultaneously. Hub patches/mixes between virtual devices.

\`\`\`
[GSM] [WA] [TG] [OpenClaw] [agent N]  ← equal peers
         ↕ switchboard (mix + fan-out)
     PipeWire virtual bus
\`\`\`

## API (internal)
- \`get_state()\` — current routing matrix
- \`patch(source, sink, gain?)\` / \`unpatch\`
- \`set_mode(name)\` — apply saved routing preset
- Agent-agnostic: OpenClaw is client #1, not the hub owner`,
    priority: 1,
  },
  {
    title: "Web UI: live routing state view + edit",
    description: `## Goal
Browser UI to view and modify the routing matrix in real time.

## Approach
- Prefer extending/adopting existing tool (PipeWeaver UI, pipewire-web-remote) if it fits
- Otherwise minimal custom UI on top of switchboard API
- Reachable over Tailscale only (no public exposure required)

## Acceptance
Operator can see all virtual devices, active patches, and rewire live during a call.`,
    priority: 3,
  },
  {
    title: "GSM hub: phone μ-law WebSocket → virtual audio",
    description: `## Goal
Terminate GSM call audio on safwat-eu as a switchboard participant.

## Phone side (minimal change)
Keep existing \`HubStreamClient\` μ-law WebSocket protocol; repoint \`STREAM_TOKEN_URL\` to hub on Tailscale.

## Hub side
- Accept WebSocket per call (8 kHz μ-law duplex)
- Inject caller audio into GSM virtual **mic** on the bus
- Read GSM virtual **speaker** and stream back to phone

## v1
Open mode: anyone can call. Allowlist later.`,
    priority: 1,
  },
  {
    title: "OpenClaw as switchboard voice client",
    description: `## Goal
OpenClaw participates in calls as a direct peer on the audio bus — not only via WhatsApp/Telegram.

## Requirements
- OpenClaw Talk/voice wired to virtual mic/speaker devices
- Can be patched in/out of a call via switchboard
- Agent-agnostic design: same client interface for future non-OpenClaw agents

## Note
OpenClaw is the starting agent, not a fixed dependency of the hub.`,
    priority: 2,
  },
  {
    title: "SMS routing control plane (deterministic)",
    description: `## Goal
SMS to gateway phone sets hub routing mode — **hub-side only**, not on the Android app.

## Why
Hard, deterministic override path. No agent interpretation required.

## Examples (TBD syntax)
- \`MODE openclaw\` — GSM ↔ OpenClaw only
- \`MODE whatsapp +1555...\`
- \`STATUS\` — reply with current routing matrix

## Requirements
- Parse inbound SMS on hub (gateway phone forwards or hub polls)
- Apply routing preset atomically
- Log all mode changes`,
    priority: 2,
  },
  {
    title: "MCP tools for switchboard routing",
    description: `## Goal
Expose switchboard API as MCP tools so agents (OpenClaw first) can manage routing by voice or automation.

## Tools (draft)
- \`switchboard_get_state\`
- \`switchboard_patch\` / \`switchboard_unpatch\`
- \`switchboard_set_mode\`
- \`switchboard_list_participants\`

## Design
Same routing state as SMS control plane. SMS remains the guaranteed deterministic override; MCP is the programmable path.`,
    priority: 3,
  },
  {
    title: "WhatsApp + Telegram as switchboard peers",
    description: `## Goal
Patch GSM audio to/from WhatsApp and Telegram calls as equal bus participants.

## Scenarios
- GSM → WhatsApp only (simple bridge)
- GSM + WhatsApp + Telegram + OpenClaw concurrently (mixed conference)
- Agent sets up group call via MCP, then switches audio patch

## Note
Apps are consumers/producers like any other peer — not children of OpenClaw.`,
    priority: 3,
  },
  {
    title: "E2E smoke test: dial GSM → OpenClaw voice",
    description: `## Test plan
1. Dial gateway SIM from external phone
2. Audio lands on switchboard GSM participant
3. Hub patches GSM ↔ OpenClaw
4. Two-way voice conversation with OpenClaw agent
5. Hang up cleanly

## Environment
- pixel-7 on Tailscale
- safwat-eu hub + OpenClaw on Tailscale
- \`STREAM_TOKEN_URL\` pointing at hub`,
    priority: 2,
  },
  {
    title: "Docs: phone-only computer architecture + README",
    description: `## Updates
- README: phone-only computer vision (call = only interface)
- Headed machine note (display exists; owner doesn't use it)
- Switchboard architecture diagram (equal peers, not OpenClaw-centric)
- Tailscale networking notes
- SMS + MCP control plane overview`,
    priority: 4,
  },
  {
    title: "Future: inbound caller allowlist",
    description: `## Goal
Restrict who can trigger GSM → hub sessions.

## Deferred
Open mode for v1. Add E.164 allowlist + SMS admin commands when ready.`,
    priority: 4,
  },
];

function mcpCall(tool: string, args: Record<string, unknown> = {}): Record<string, unknown> {
  const json = execFileSync(
    "mcporter",
    ["call", `linear.${tool}`, "--args", JSON.stringify(args), "--output", "json"],
    { encoding: "utf8", maxBuffer: 10 * 1024 * 1024 },
  );
  return JSON.parse(json) as Record<string, unknown>;
}

function parseArgs(argv: string[]) {
  return { dryRun: argv.includes("--dry-run") };
}

async function main() {
  const { dryRun } = parseArgs(process.argv.slice(2));

  // Sanity check: mcporter can reach Linear
  try {
    mcpCall("list_projects", { query: PROJECT, limit: 5 });
  } catch (e) {
    console.error(
      "Linear MCP not ready. Run: mcporter auth linear\n" +
        "Approve in browser and select the **safwatly** workspace (not compliancy-group).\n",
    );
    throw e;
  }

  const projects = mcpCall("list_projects", { query: PROJECT, limit: 10 }) as {
    projects?: { id: string; name: string; teams?: { id: string; name: string; key: string }[] }[];
  };

  const project = projects.projects?.find((p) => p.name.toLowerCase() === PROJECT.toLowerCase());
  if (!project) {
    throw new Error(`Project "${PROJECT}" not found in current Linear workspace. Are you authenticated to safwatly?`);
  }

  const team = project.teams?.[0]?.name ?? project.teams?.[0]?.key;
  if (!team) {
    throw new Error(`No team linked to project "${PROJECT}". Add a team in Linear first.`);
  }

  console.log(`Project: ${project.name} (${project.id})`);
  console.log(`Team: ${team}`);
  console.log(`Tickets to create: ${TICKETS.length}`);
  if (dryRun) {
    for (const t of TICKETS) console.log(`  - [P${t.priority ?? 0}] ${t.title}`);
    return;
  }

  const created: { title: string; url?: string; id?: string }[] = [];
  for (const ticket of TICKETS) {
    const result = mcpCall("save_issue", {
      title: ticket.title,
      description: ticket.description,
      project: PROJECT,
      team,
      ...(ticket.priority ? { priority: ticket.priority } : {}),
    }) as { id?: string; url?: string; identifier?: string };

    created.push({
      title: ticket.title,
      id: result.identifier ?? result.id,
      url: result.url as string | undefined,
    });
    console.log(`Created: ${result.identifier ?? result.id} — ${ticket.title}`);
  }

  console.log("\nDone:", created.length, "issues");
  for (const c of created) {
    if (c.url) console.log(`  ${c.id}: ${c.url}`);
  }
}

main().catch((err) => {
  console.error(err instanceof Error ? err.message : err);
  process.exit(1);
});
