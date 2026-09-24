import path from "node:path";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, "../..");
const DEFAULT_HUB = "http://hub.mining-ling.ts.net:8787";

function hubTokenProxy() {
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

// Dev (`npm run dev`) serves at `/`. Production default is `/portal/` for the hub static path.
export default defineConfig(({ command }) => {
  const base = process.env.VITE_BASE || (command === "serve" ? "/" : "/portal/");
  return {
    plugins: [hubTokenProxy()],
    base,
    resolve: {
      alias: {
        "@sim": path.resolve(repoRoot, "simulator/src"),
      },
    },
    build: {
      outDir: "dist",
      emptyOutDir: true,
      assetsDir: "assets",
    },
    server: {
      port: 5174,
      strictPort: true,
      fs: {
        allow: [repoRoot],
      },
      proxy: {
        "/portal/api": {
          target: "http://127.0.0.1:8787",
          changeOrigin: true,
        },
        "/health": {
          target: "http://127.0.0.1:8787",
          changeOrigin: true,
        },
        "/switchboard": {
          target: "http://127.0.0.1:8787",
          changeOrigin: true,
        },
      },
    },
  };
});
