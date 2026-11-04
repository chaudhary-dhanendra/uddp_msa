/// <reference types="vitest" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The dev and preview servers bind to localhost only and proxy the backend, so the browser sees one origin and the
// backend needs no CORS. The default 8080 is the API gateway (gateway-service); UDDP_API_URL points it elsewhere.
const backend = process.env.UDDP_API_URL ?? "http://127.0.0.1:8080";
const proxy = {
  "/api": { target: backend, changeOrigin: false },
  "/v3/api-docs": { target: backend, changeOrigin: false },
  "/swagger-ui": { target: backend, changeOrigin: false },
  "/health": { target: backend, changeOrigin: false },
};

export default defineConfig({
  plugins: [react()],
  server: { host: "127.0.0.1", port: 5173, strictPort: true, proxy },
  preview: { host: "127.0.0.1", port: 4173, strictPort: true, proxy },
  build: { sourcemap: false },
});
