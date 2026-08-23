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

/**
 * Build-Stempel (base36-Zeitstempel des Builds) fürs Cache-Busting der
 * dynamisch nachgeladenen Theme-Dateien — s. vite.config.ts (define) und
 * styles/themeLoader.ts (buildId). Wie __HOSHI_VERSION__ eine reine
 * Build-Zeit-Konstante; vitest zieht das define NICHT (Muster TopNav).
 */
declare const __HOSHI_BUILD_ID__: string;
