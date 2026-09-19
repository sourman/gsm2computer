import { defineConfig } from "vite";

const HUB = process.env.HUB_URL || "http://127.0.0.1:8787";

export default defineConfig({
  base: "/portal/",
  server: {
    host: true,
    port: 5174,
    proxy: {
      "/portal/api": { target: HUB, changeOrigin: true },
      "/sms": { target: HUB, changeOrigin: true },
    },
  },
  preview: {
    host: true,
    port: 4174,
  },
});
