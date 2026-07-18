import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// @types/node ist bewusst nicht installiert (schlankes FE). Der Dev-Proxy liest
// `process.env.VITE_PROXY_TARGET` nur zur Config-Zeit in Node — diese schlanke
// Ambient-Deklaration typt das, ohne eine Node-Typdependency zu ziehen.
declare const process: { env: Record<string, string | undefined> };

// Hoshi 0.8 — schlank. Default-Backend :8090 (per VITE_API_BASE überschreibbar).
// Vitest-Config lebt bewusst getrennt in vitest.config.ts (vermeidet den
// Dual-Vite-Typkonflikt aus vitests genestetem vite).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5180, // bewusst NICHT 8090 (Backend) — kein Port-Clash mit Lane A.
    // Dev-Proxy: FE-Calls auf /api → echtes Backend, OHNE CORS-Wand. Ziel via
    // VITE_PROXY_TARGET (Default lokaler launchd-0.8 :8090). Für Remote-Test gegen
    // ct-106: VITE_PROXY_TARGET=http://192.168.178.106:8082 (mit VITE_API_BASE leer).
    proxy: {
      '/api': {
        target: process.env.VITE_PROXY_TARGET || 'http://localhost:8090',
        changeOrigin: true,
      },
    },
  },
});
