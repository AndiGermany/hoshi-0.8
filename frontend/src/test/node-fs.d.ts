/**
 * Minimal-Typ für den EINEN Node-Baustein, den ausschließlich Tests brauchen:
 * der CSS-Riegel in `themegroups.test.tsx` liest die echten Stylesheets, statt
 * ihre Selektoren im Test noch einmal abzuschreiben.
 *
 * `@types/node` ist im Frontend bewusst NICHT installiert (schlankes FE, s. den
 * Kommentar in vite.config.ts). Diese Ambient-Deklaration typt genau eine
 * Funktion — kein `process`, kein globaler Node-Namensraum, damit Browser-Code
 * weiterhin sichtbar bricht, wenn er Node-APIs benutzen will.
 *
 * Warum nicht `import css from '…?raw'`: Vitest ersetzt CSS-Module standardmäßig
 * durch leere Strings (`test.css: false`) — der Riegel liefe dann ins Leere.
 */
declare module 'node:fs' {
  export function readFileSync(path: string, encoding: 'utf8'): string;
}
