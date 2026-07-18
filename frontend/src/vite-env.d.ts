/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE?: string;
  readonly VITE_TOKEN?: string;
  readonly VITE_SPEAKER_ID?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
