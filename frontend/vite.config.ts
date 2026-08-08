import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
// @types/node ist bewusst nicht installiert (schlankes FE) — ein `declare module
// 'node:fs'` ginge hier NICHT (vite.config.ts ist selbst ein ES-Modul; TS behandelt
// das dann als Modul-Augmentation und verlangt, dass 'node:fs' schon auflösbar
// ist — genau das Henne-Ei-Problem, das wir umgehen wollen). Darum: `@ts-expect-error`
// auf dem Import, Rest der Datei bleibt sauber getypt.
// @ts-expect-error — kein @types/node installiert; `node:fs` existiert zur Laufzeit
// unter Node/Vite trotzdem, nur ungetypt (readFileSync landet dadurch auf `any`).
import { readFileSync } from 'node:fs';

// Der Dev-Proxy liest `process.env.VITE_PROXY_TARGET` nur zur Config-Zeit in
// Node — diese schlanke Ambient-Deklaration typt das, ohne eine Node-Typdependency
// zu ziehen.
declare const process: { env: Record<string, string | undefined> };

/**
 * Versions-Wahrheit: EINZIGE Quelle ist gradle.properties (Zeile 1,
 * `version=<x.y.z>`). Wird beim Build gelesen und unten per `define` als
 * globale Konstante __HOSHI_VERSION__ ins Bundle injiziert — TopNav rendert
 * nur noch diese Konstante, keine zweite, hart codierte Wahrheit im FE mehr.
 * Fallback "dev" wenn die Datei fehlt/unlesbar ist oder die Zeile nicht
 * matcht — lieber ehrlich "dev" zeigen als eine geratene Versionsnummer.
 */
function readHoshiVersion(): string {
  try {
    const raw = readFileSync('../gradle.properties', 'utf8');
    const match = raw.match(/^version=(.+)$/m);
    const version = match?.[1]?.trim();
    return version || 'dev';
  } catch {
    return 'dev';
  }
}

// Hoshi 0.8 — schlank. Default-Backend :8090 (per VITE_API_BASE überschreibbar).
// Vitest-Config lebt bewusst getrennt in vitest.config.ts (vermeidet den
// Dual-Vite-Typkonflikt aus vitests genestetem vite).
export default defineConfig({
  plugins: [react()],
  define: {
    __HOSHI_VERSION__: JSON.stringify(readHoshiVersion()),
  },
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
