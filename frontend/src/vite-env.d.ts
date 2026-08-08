/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE?: string;
  readonly VITE_TOKEN?: string;
  readonly VITE_SPEAKER_ID?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

/**
 * Versions-Wahrheit: von vite.config.ts per `define` aus gradle.properties
 * (Zeile 1, `version=…`) injiziert — Build-Zeit-Konstante, keine echte
 * Laufzeit-Variable. Fallback "dev", falls gradle.properties beim Build
 * fehlt/unlesbar war. Siehe frontend/vite.config.ts (readHoshiVersion).
 */
declare const __HOSHI_VERSION__: string;
