import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// Unit-Tests ohne Live-Backend. `react()` transformiert JSX/TSX (für die
// Übersicht-Render-Tests via renderToStaticMarkup); `environment: 'node'`
// reicht — Static-Markup braucht kein DOM, kein Fetch, keinen Port.
//
// `defineConfig` kommt aus 'vitest/config' (typt das `test`-Feld nativ). Der
// `react()`-Plugin wird gegen das Top-Level-vite getypt; der dokumentierte
// Dual-Vite-Typkonflikt (vitests genestetes vite) wird per Cast neutralisiert.
export default defineConfig({
  plugins: [react() as never],
  test: {
    environment: 'node',
    include: ['src/**/*.test.{ts,tsx}'],
    // Deterministische Tests: eine lokale `.env.local` (z.B. VITE_SPEAKER_ID=<dein
    // Name>) darf die Default-Prüfungen NICHT verfälschen. Leer erzwungen → der
    // `|| 'gast'`-Fallback in config.ts liefert im Test immer den Ship-Default.
    env: { VITE_SPEAKER_ID: '' },
  },
});
