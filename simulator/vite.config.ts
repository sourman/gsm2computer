import type { Connect } from "vite";
import { defineConfig } from "vite";

const DEFAULT_HUB = "http://100.101.181.110:8787";

/** Proxy POST /proxy/token?hub=... → {hub}/token to avoid browser CORS in dev. */
function hubTokenProxy(): { name: string; configureServer: (server: { middlewares: Connect.Server }) => void } {
  return {
    name: "hub-token-proxy",
    configureServer(server) {
      server.middlewares.use("/proxy/token", async (req, res) => {
        if (req.method !== "POST" && req.method !== "GET") {
          res.statusCode = 405;
          res.end("method not allowed");
          return;
        }
        const url = new URL(req.url ?? "", "http://localhost");
        const hub = (url.searchParams.get("hub") || DEFAULT_HUB).replace(/\/$/, "");
        const target = `${hub}/token`;
        try {
          const upstream = await fetch(target, { method: "POST" });
          const body = await upstream.text();
          res.statusCode = upstream.status;
          res.setHeader("Content-Type", "application/json");
          res.end(body);
        } catch (err) {
          res.statusCode = 502;
          res.end(JSON.stringify({ error: String(err) }));
        }
      });
    },
  };
}

export default defineConfig({
  plugins: [hubTokenProxy()],
  server: {
    host: true, // listen on Tailscale/LAN, not just localhost
    allowedHosts: [".mining-ling.ts.net", "debian-ahmed", "localhost"],
    proxy: {
      "/health": { target: process.env.HUB_URL ?? DEFAULT_HUB, changeOrigin: true },
    },
  },
});
