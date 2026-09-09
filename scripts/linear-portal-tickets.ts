/**
 * Create / skip-if-exists Linear tickets for the hub messaging portal (ADR 0006).
 *
 * Filed (safwatly / gsm2computer):
 *   SAF-29 Hub SQLite + portal REST/SSE
 *   SAF-30 PWA messaging portal
 *   SAF-31 Pixel outbound SMS + call log sync
 *   SAF-32 Tailscale HTTPS / trusted cert
 *   SAF-33 System CallLog backfill (v1.1)
 *   SAF-34 OpenClaw MCP calls_recent / sms_thread
 *
 * Does **not** re-create the original gsm2computer bootstrap tickets
 * (`scripts/create-linear-tickets.ts`).
 *
 * Prereq: safwatly Linear via mcporter (once per repo):
 *   mcporter auth linear
 *   # pick safwatly in browser — tokens land in .mcporter/oauth/linear
 *
 * Usage:
 *   bun run scripts/linear-portal-tickets.ts --dry-run
 *   bun run scripts/linear-portal-tickets.ts
 */
import { execFileSync } from "node:child_process";

const PROJECT = "gsm2computer";

type Priority = 1 | 2 | 3 | 4;

type PortalTicket = {
  key: string;
  title: string;
  description: string;
  priority: Priority;
  state: "Todo" | "Backlog";
  relatedToExisting?: string[];
  blockedByKeys?: string[];
  relatedToKeys?: string[];
};

const ADR = "docs/adr/0006-portal-messaging.md";
const SPEC = "docs/BUILD_PORTAL_SPEC.md";

const TICKETS: PortalTicket[] = [
  {
    key: "hub-api",
    title: "Hub: SQLite + portal REST/SSE API",
    priority: 2,
    state: "Todo",
    relatedToExisting: ["SAF-12", "SAF-18"],
    description: `## Goal

Persist SMS and call history on the hub and expose them over REST + SSE for the messaging PWA.

ADR: \`${ADR}\` (accepted). Build spec: \`${SPEC}\`.

Depends on inbound SMS already reaching the hub ([SAF-18](https://linear.app/safwatly/issue/SAF-18/android-sms-forwarder-to-hub-control-api)). Does **not** replace [SAF-12](https://linear.app/safwatly/issue/SAF-12/sms-routing-control-plane-deterministic) (\`MODE\`/\`STATUS\` routing).

## Schema (suggested)

\`\`\`sql
messages(id TEXT PK, direction TEXT, peer TEXT, body TEXT, ts TEXT, status TEXT)
calls(id TEXT PK, direction TEXT, number TEXT, started_at TEXT, duration_sec INT,
      switchboard_mode TEXT, session_id TEXT, tap_summary TEXT)
outbox(id TEXT PK, to_number TEXT, body TEXT, created_at TEXT, status TEXT)
\`\`\`

Normalize peers to E.164 where possible. Retention: **forever**.

## API

- \`GET /portal/\` — PWA static files (may land with the PWA ticket)
- \`GET /portal/api/threads\`
- \`GET /portal/api/messages?peer=\`
- \`POST /portal/api/messages/send\` — queue outbound
- \`GET /portal/api/calls\`
- \`GET /portal/api/events\` — SSE (new message, new call)
- Existing \`POST /sms\` **must persist** to SQLite in addition to STATUS/MODE routing
- Pixel ingest: \`POST /calls\`, \`GET /sms/outbox\` (+ ack) as agreed with the Pixel ticket

Auth v1: Tailscale mesh only (no portal bearer token).

## Acceptance

- [ ] Idempotent SQLite init / migrations
- [ ] \`POST /sms\` still routes STATUS/MODE and now stores the message
- [ ] Threads / messages / send / calls endpoints match the build spec
- [ ] SSE notifies subscribers of new messages and calls
- [ ] Hub tests in \`hub/tests/\` (or existing pytest pattern) cover persist + outbox queue
- [ ] No secrets in git; do not break one-live-call (ADR 0004) or Talk webrtc-ui default
`,
  },
  {
    key: "pwa",
    title: "PWA messaging portal (threads, compose, calls tab)",
    priority: 2,
    state: "Todo",
    blockedByKeys: ["hub-api"],
    relatedToExisting: ["SAF-19"],
    description: `## Goal

Installable PWA at \`/portal/\` so the operator can read/reply to SMS and view call history from any Tailscale device (Redmi, laptop) without opening the Pixel gateway app.

ADR: \`${ADR}\`. Build spec: \`${SPEC}\`.

This is **not** the switchboard operator console ([SAF-19](https://linear.app/safwatly/issue/SAF-19/hub-command-and-control-web-ui-switchboard-operator-console)). Messaging + call log only.

## UX

- Tabs: **Messages** (thread list + conversation + compose) and **Calls** (history)
- Mobile-friendly layout; \`manifest.json\` + service worker (installable)
- SSE while the tab is open; Web Push is **after** trusted HTTPS (separate ticket)
- Empty states and error states for no threads / send failure

## Acceptance

- [ ] Served from hub at \`/portal/\` (\`hub/portal/\` static build)
- [ ] Thread list, conversation view, compose send → outbox
- [ ] Calls tab shows direction, number, time, duration, switchboard_mode, session/tap when present
- [ ] Live updates via SSE when the tab is open
- [ ] Works on a mobile viewport (QA: chad-browser against Tailscale URL or \`http://100.101.181.110:8787/portal/\`)
- [ ] No gateway tokens or \`#token=\` URLs in the frontend
`,
  },
  {
    key: "pixel",
    title: "Pixel: outbound SMS + call log sync to hub",
    priority: 2,
    state: "Todo",
    relatedToExisting: ["SAF-12", "SAF-18"],
    blockedByKeys: ["hub-api"],
    description: `## Goal

Two-way SMS and hub-side call history from the Pixel 7 gateway.

ADR: \`${ADR}\`. Inbound forwarder ([SAF-18](https://linear.app/safwatly/issue/SAF-18/android-sms-forwarder-to-hub-control-api)) stays as-is. This ticket adds **send** + **call records**.

Also unblocks SMS **replies** for [SAF-12](https://linear.app/safwatly/issue/SAF-12/sms-routing-control-plane-deterministic) \`STATUS\` / \`MODE\` confirmations (hub can enqueue outbound).

## Pixel changes

1. \`SEND_SMS\` in the manifest + Magisk \`service.sh\` grant
2. On call end (\`GatewayService\` / \`CallOrchestrator\`): \`POST /calls\` with full metadata — direction, number, started_at, duration_sec, switchboard_mode, session_id / tap summary when the voice bridge was active
3. Background poll every **10s**: \`GET /sms/outbox\` (or agreed path), send via \`SmsManager\`, POST ack
4. Do **not** break existing \`POST /sms\` inbound or STATUS/MODE routing
5. Outbox poll must not block the audio WebSocket thread
6. v1 call log = bridge \`CallLogStore\` entries (system Android \`CallLog\` backfill is a follow-up ticket)

## Acceptance

- [ ] Compose from the portal results in an SMS leaving the Pixel SIM
- [ ] Hangup of a bridged call appears on \`GET /portal/api/calls\` with full metadata
- [ ] Inbound SMS still forwards (SAF-18)
- [ ] HubEndpoints-style tests for any new URL helpers
- [ ] Deploy hub + APK together when the wire changes
`,
  },
  {
    key: "https",
    title: "Tailscale HTTPS / trusted cert for hub portal",
    priority: 2,
    state: "Todo",
    relatedToKeys: ["pwa"],
    description: `## Goal

Chrome trusts \`https://ip-172-31-21-244.mining-ling.ts.net\` for the hub portal (needed for installable PWA + Web Push later).

ADR: \`${ADR}\` decision 6. Hub today: HTTP \`100.101.181.110:8787\`. Investigate the current \`:8443\` red-lock on the hub host.

## Options

- \`tailscale serve https /portal\` mapping to local hub
- Or proxy portal on \`:8443\` with Tailscale-managed cert

## Acceptance

- [ ] Chrome on a tailnet client shows a trusted lock for the portal URL (no cert warning)
- [ ] Portal loads over that HTTPS URL
- [ ] Document the Serve / cert setup (ops note in the build spec or README) without committing tokens
- [ ] Do not bind CDP or hub control to non-localhost except via Tailscale
`,
  },
  {
    key: "calllog-backfill",
    title: "System CallLog backfill (portal v1.1)",
    priority: 3,
    state: "Backlog",
    blockedByKeys: ["pixel"],
    description: `## Goal

v1 portal call history is **bridge \`CallLogStore\` only** (max 20 local entries, calls the gateway handled). Fast follow-up: backfill / sync the system Android \`CallLog\` provider so missed/non-bridge calls appear on the hub.

ADR: \`${ADR}\` decision 5. Do **not** block portal v1 on this.

## Acceptance

- [ ] Pixel reads system \`CallLog\` (permission already granted) and posts missing records to the hub
- [ ] Dedup against bridge-posted rows (same number + time window)
- [ ] Portal Calls tab shows backfilled entries without breaking v1 metadata fields
- [ ] Document what is still missing (e.g. switchboard_mode / tap summary for non-bridge calls)
`,
  },
  {
    key: "mcp",
    title: "OpenClaw MCP: calls_recent / sms_thread",
    priority: 4,
    state: "Backlog",
    relatedToExisting: ["SAF-13"],
    blockedByKeys: ["hub-api"],
    description: `## Goal

Expose hub portal data to OpenClaw as MCP tools so the agent can answer “who called?” / “what did they text?” over the same SQLite store.

ADR: \`${ADR}\` consequences. **Future** — not portal v1.

This is **not** switchboard routing MCP ([SAF-13](https://linear.app/safwatly/issue/SAF-13/mcp-tools-for-switchboard-routing): \`switchboard_get_state\` / patch / mode). Separate tools, same OpenClaw client.

## Tools (draft)

- \`calls_recent\` — recent call rows (direction, number, time, duration, mode)
- \`sms_thread\` — messages for a peer (E.164)

May wrap \`GET /portal/api/calls\` and \`GET /portal/api/messages\` rather than a second store.

## Acceptance

- [ ] Tools read the hub SQLite / portal API (no second source of truth)
- [ ] Documented next to SAF-13 so agents do not overload routing MCP
- [ ] No portal bearer-token work unless auth v2 has shipped
`,
  },
];

type LinearIssue = {
  id: string;
  title: string;
  url?: string;
  status?: string;
};

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

function existingByTitle(issues: LinearIssue[]): Map<string, LinearIssue> {
  const map = new Map<string, LinearIssue>();
  for (const i of issues) {
    map.set(i.title.trim().toLowerCase(), i);
  }
  return map;
}

async function main() {
  const { dryRun } = parseArgs(process.argv.slice(2));

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

  const listed = mcpCall("list_issues", { project: PROJECT, limit: 100 }) as { issues?: LinearIssue[] };
  const found = existingByTitle(listed.issues ?? []);

  console.log(`Project: ${project.name} (${project.id})`);
  console.log(`Team: ${team}`);
  console.log(`Portal tickets: ${TICKETS.length}`);
  if (dryRun) {
    for (const t of TICKETS) {
      const hit = found.get(t.title.toLowerCase());
      console.log(`  - [${t.state} P${t.priority}] ${t.title}${hit ? ` (exists ${hit.id})` : ""}`);
    }
    return;
  }

  const keyToId = new Map<string, string>();
  const results: { key: string; id: string; url?: string; created: boolean }[] = [];

  for (const ticket of TICKETS) {
    const hit = found.get(ticket.title.toLowerCase());
    if (hit) {
      keyToId.set(ticket.key, hit.id);
      results.push({ key: ticket.key, id: hit.id, url: hit.url, created: false });
      console.log(`Exists: ${hit.id} — ${ticket.title}`);
      continue;
    }

    const relatedTo = [...(ticket.relatedToExisting ?? [])];
    const blockedBy: string[] = [];
    for (const k of ticket.relatedToKeys ?? []) {
      const id = keyToId.get(k);
      if (id) relatedTo.push(id);
    }
    for (const k of ticket.blockedByKeys ?? []) {
      const id = keyToId.get(k);
      if (id) blockedBy.push(id);
    }

    const result = mcpCall("save_issue", {
      title: ticket.title,
      description: ticket.description,
      project: PROJECT,
      team,
      priority: ticket.priority,
      state: ticket.state,
      ...(relatedTo.length ? { relatedTo } : {}),
      ...(blockedBy.length ? { blockedBy } : {}),
    }) as { id?: string; url?: string; identifier?: string };

    const id = String(result.identifier ?? result.id);
    keyToId.set(ticket.key, id);
    found.set(ticket.title.toLowerCase(), { id, title: ticket.title, url: result.url });
    results.push({ key: ticket.key, id, url: result.url, created: true });
    console.log(`Created: ${id} — ${ticket.title}`);
  }

  console.log("\nPortal issues:");
  for (const r of results) {
    const mark = r.created ? "created" : "existed";
    console.log(`  ${r.id} (${r.key}, ${mark})${r.url ? ` ${r.url}` : ""}`);
  }
}

main().catch((err) => {
  console.error(err instanceof Error ? err.message : err);
  process.exit(1);
});
