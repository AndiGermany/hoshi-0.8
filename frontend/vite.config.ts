import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
// @types/node ist bewusst nicht installiert (schlankes FE) — ein `declare module
// 'node:fs'` ginge hier NICHT (vite.config.ts ist selbst ein ES-Modul; TS behandelt
// das dann als Modul-Augmentation und verlangt, dass 'node:fs' schon auflösbar
// ist — genau das Henne-Ei-Problem, das wir umgehen wollen). Darum: `@ts-expect-error`
// auf dem Import, Rest der Datei bleibt sauber getypt.
// @ts-expect-error — kein @types/node installiert; `node:fs` existiert zur Laufzeit
// unter Node/Vite trotzdem, nur ungetypt (readFileSync landet dadurch auf `any`).
import { readdirSync, readFileSync, writeFileSync, statSync } from 'node:fs';
// @ts-expect-error — same reason as node:fs above; createHash lands on `any`.
import { createHash } from 'node:crypto';
// @ts-expect-error — same reason as node:fs above. Node's built-in zlib carries
// Brotli since v11.7, so the precompress plugin below needs NO extra dependency.
import { brotliCompressSync, gzipSync, constants as zlibConstants } from 'node:zlib';
// @ts-expect-error — same reason as node:fs above; resolve lands on `any`.
import { resolve as resolvePath } from 'node:path';

// Der Dev-Proxy liest `process.env.VITE_PROXY_TARGET` nur zur Config-Zeit in
// Node — diese schlanke Ambient-Deklaration typt das, ohne eine Node-Typdependency
// zu ziehen.
declare const process: { env: Record<string, string | undefined> };

// tsconfig.node.json faehrt `lib: ["ES2023"]` — kein DOM, keine Node-Typen. Das
// Precompress-Plugin unten loggt seine Bilanz in die Build-Ausgabe; diese
// Ein-Zeilen-Deklaration typt genau das, wieder ohne @types/node zu ziehen.
declare const console: { log(message: string): void };

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

/**
 * Lists every file under `dir`, relative to `dir`, POSIX-style (`/`-joined)
 * and sorted — the sort makes traversal order irrelevant so the same tree
 * always yields the same sequence (required for a reproducible hash below).
 */
function listFilesRecursive(dir: string, prefix = ''): string[] {
  const files: string[] = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const relPath = prefix ? `${prefix}/${entry.name}` : entry.name;
    if (entry.isDirectory()) {
      files.push(...listFilesRecursive(`${dir}/${entry.name}`, relPath));
    } else if (entry.isFile()) {
      files.push(relPath);
    }
  }
  return files.sort();
}

/**
 * Content-derived build id for theme cache-busting (consumed by
 * themeLoader.ts buildId()). Was `Date.now()` until 2026-08-13, which made
 * every build differ even with zero source changes — that broke both the
 * dist byte-equality proof and the deploy pipeline's FE-diff detector
 * (which compares this id and therefore always saw "changed"). SHA-256 over
 * every file under public/ (sorted path, then path+content) means: same
 * input -> same id, so `npm run build` is reproducible again and the id only
 * moves when a theme/manifest file actually changes.
 */
function readBuildId(): string {
  const hash = createHash('sha256');
  for (const file of listFilesRecursive('public')) {
    hash.update(file);
    hash.update('\n');
    hash.update(readFileSync(`public/${file}`));
  }
  return hash.digest('hex').slice(0, 8);
}

/**
 * Dateiendungen, deren Inhalt Text ist und sich darum lohnt vorzukomprimieren.
 * Bewusst eine Allow-List statt einer Deny-List: alles Unbekannte (Fonts, PNG,
 * ICO, MP3) bleibt unangetastet — die sind bereits komprimiert, ein .br daneben
 * wuerde nur Platz kosten und nichts sparen.
 */
const PRECOMPRESS_EXTENSIONS = [
  '.js', '.mjs', '.cjs', '.css', '.html', '.json', '.svg', '.txt', '.xml', '.map', '.webmanifest',
];

/**
 * Unterhalb dieser Groesse wird NICHT vorkomprimiert. 1024 Byte ist exakt die
 * Schwelle, die Spring im servlet-seitigen `EncodedResourceResolver` als
 * `DEFAULT_MINIMUM_SIZE` fuehrt: darunter frisst der Content-Encoding-Overhead
 * den Gewinn auf, und eine Datei unter einer MTU geht ohnehin in einem Paket
 * raus. Trifft hier nur `index.html` (455 B).
 */
const PRECOMPRESS_MIN_BYTES = 1024;

/**
 * **Precompress** — legt beim Production-Build neben jedes Textasset in `dist/`
 * ein `.br` (Brotli) und ein `.gz` (Gzip). Das Backend serviert die dann per
 * Accept-Encoding-Verhandlung aus (`EncodedResourceResolver` in WebConfig.kt,
 * Spring-nativ, `.br` vor `.gz`) — WebFlux komprimiert von sich aus NICHT, die
 * FE ging bis hierher komplett unkomprimiert ueber die Leitung.
 *
 * **Keine neue Abhaengigkeit:** Node bringt Brotli seit v11.7 in `node:zlib`
 * mit, darum dieses ~40-Zeilen-Plugin statt eines vite-plugin-compression o.ae.
 * Ein Build-Step in `pipeline/deploy.sh` waere die Alternative gewesen — hier im
 * Build ist es besser aufgehoben: `npm run build` allein erzeugt schon das
 * vollstaendige, deploybare `dist/`, und `deploy-fe.sh` tart ohnehin den ganzen
 * Ordner (die `.br`/`.gz` fahren ohne Pipeline-Aenderung mit).
 *
 * **Deterministisch** (Pflicht — siehe readBuildId oben): Brotli ist bei festen
 * Parametern bitgleich, und Nodes `gzipSync` schreibt MTIME 0 in den Header.
 * Zwei Builds aus derselben Quelle liefern damit weiterhin byte-gleiche `dist/`.
 *
 * Laeuft nur im Build (`apply: 'build'`) und im `closeBundle`-Hook, also NACH
 * dem Kopieren von `public/` — sonst blieben die Theme-SVGs/CSS aussen vor,
 * und genau die sind mit ~1,5 MB der groesste Brocken.
 */
function precompressPlugin() {
  let outDir = 'dist';
  return {
    name: 'hoshi-precompress',
    apply: 'build' as const,
    configResolved(config: { root: string; build: { outDir: string } }) {
      outDir = resolvePath(config.root, config.build.outDir);
    },
    closeBundle() {
      let originalTotal = 0;
      let brotliTotal = 0;
      let count = 0;
      for (const rel of listFilesRecursive(outDir)) {
        const lower = rel.toLowerCase();
        if (!PRECOMPRESS_EXTENSIONS.some((ext) => lower.endsWith(ext))) continue;
        const abs = `${outDir}/${rel}`;
        if (statSync(abs).size < PRECOMPRESS_MIN_BYTES) continue;
        // readFileSync/brotliCompressSync landen ohne @types/node auf `any` —
        // genutzt werden nur `.length` und das Weiterreichen an writeFileSync.
        const raw = readFileSync(abs);

        const brotli = brotliCompressSync(raw, {
          params: {
            [zlibConstants.BROTLI_PARAM_QUALITY]: zlibConstants.BROTLI_MAX_QUALITY,
            [zlibConstants.BROTLI_PARAM_MODE]: zlibConstants.BROTLI_MODE_TEXT,
            [zlibConstants.BROTLI_PARAM_SIZE_HINT]: raw.length,
          },
        });
        const gzip = gzipSync(raw, { level: 9 });

        // Nur schreiben, wenn es wirklich kleiner wird — sonst liegt da eine
        // Variante, die das Backend brav ausliefert und die groesser ist als
        // das Original. Bei Textassets nie der Fall, aber billig abzusichern.
        if (brotli.length < raw.length) writeFileSync(`${abs}.br`, brotli);
        if (gzip.length < raw.length) writeFileSync(`${abs}.gz`, gzip);

        originalTotal += raw.length;
        brotliTotal += Math.min(brotli.length, raw.length);
        count += 1;
      }
      const saved = originalTotal - brotliTotal;
      const percent = originalTotal > 0 ? Math.round((saved / originalTotal) * 100) : 0;
      // eslint-disable-next-line no-console
      console.log(
        `precompress: ${count} Dateien, ${kib(originalTotal)} -> ${kib(brotliTotal)} brotli (-${percent}%)`,
      );
    },
  };
}

/** Bytes als gerundete KiB — nur fuer die Build-Ausgabe oben. */
function kib(bytes: number): string {
  return `${(bytes / 1024).toFixed(1)} KiB`;
}

// Hoshi 0.8 — schlank. Default-Backend :8090 (per VITE_API_BASE überschreibbar).
// Vitest-Config lebt bewusst getrennt in vitest.config.ts (vermeidet den
// Dual-Vite-Typkonflikt aus vitests genestetem vite).
export default defineConfig({
  plugins: [
    react(),
    // Versions-Wahrheit auch im Browser-Tab (Andi-Fund 08.08: der <title>
    // behauptete noch „0.8.2"): index.html trägt den Platzhalter
    // %HOSHI_VERSION%, ersetzt zur Build-/Dev-Zeit aus derselben Quelle wie
    // __HOSHI_VERSION__ — keine dritte Wahrheit, kein Runtime-Flackern.
    {
      name: 'hoshi-title-version',
      transformIndexHtml(html: string) {
        return html.replace(/%HOSHI_VERSION%/g, readHoshiVersion());
      },
    },
    // Muss NACH allen Asset-erzeugenden Plugins stehen: laeuft in closeBundle
    // ueber das fertige dist/ und legt .br/.gz daneben (siehe precompressPlugin).
    precompressPlugin(),
  ],
  define: {
    __HOSHI_VERSION__: JSON.stringify(readHoshiVersion()),
    // Cache-bust anchor for theme files (themeLoader.ts): public/themes/*.css
    // carry no hash in their filename — without a ?v= anchor Andi saw the old
    // state from the browser cache after a theme fix (Ukiyo/Fuji, 2026-08-11).
    // Content-derived (readBuildId above), not per-version: theme fixes land
    // within the same 0.8.x, and it only changes when public/ actually does.
    __HOSHI_BUILD_ID__: JSON.stringify(readBuildId()),
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
