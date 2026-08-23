/**
 * Der Kontrast-Beweis für NATSUMATSURI (zweite Fassung, „Hanabi-Taikai").
 * ───────────────────────────────────────────────────────────────────────────
 * Dieses Thema ist der EINFACHSTE Messfall der ganzen Reihe, und zwar durch
 * Konstruktion — es lohnt sich, zu verstehen warum:
 *
 *   Die gesamte Bewegung steht IM Bild (natsumatsuri-hanabi.svg): sieben
 *   Blüten-Uhren, zwei Schuss-Uhren, drei für die Laternen, drei fürs Wasser,
 *   eine für den Rauch. In der Themen-CSS gibt es KEINE einzige Animation mehr
 *   (die Laternenkette der ersten Fassung, die dort noch schwankte und atmete,
 *   ist in die Zeichnung gewandert). Es gibt also nichts, was man von außen
 *   einfrieren müsste — und `animation-play-state` erreicht die Uhren in einem
 *   per `background-image` geladenen SVG ohnehin nicht.
 *
 *   Stattdessen greift `--force-prefers-reduced-motion`: der Schalter hält auch
 *   die Uhren IM Bild an, weil die Zeichnung dieselbe Medienabfrage in ihrem
 *   eigenen <style> trägt. Und weil dort JEDER Keyframe bei opacity:1 beginnt
 *   und nur nach unten läuft, ist der angehaltene Zustand der HELLSTE: alle
 *   sieben Blüten stehen gleichzeitig in voller Pracht.
 *
 * DAS IST DER KNIFF. Ein Feuerwerk hat seinen schlechtesten Kontrastmoment
 * nicht bei t=0 und nicht in der Mitte einer Bewegung, sondern dann, wenn
 * ALLES gleichzeitig brennt — ein Zustand, den der Betrieb nie zeigt (dort
 * verteilen die versetzten Uhren die Blüten über die Zeit), den die Zeichnung
 * aber als ihren Ruhezustand malt. Das Szenario `finale` misst damit eine
 * obere Schranke, keine Stichprobe: heller als dort wird das Bild nie.
 *
 * Die vier Zeitproben daneben sind kein zweiter Beweis, sondern eine
 * Gegenprobe — sie belegen, dass sich überhaupt etwas bewegt (vier verschiedene
 * Bilder) und dass jede einzelne Phase erwartungsgemäß UNTER dem Finale liegt.
 *
 *   node tools/theme-contrast/measure.mjs tools/theme-contrast/natsumatsuri.scenarios.mjs
 */

export default {
  theme: 'natsumatsuri',
  viewports: [
    [1440, 900],
    [1280, 800],
    [1024, 768],
  ],
  probeTokens: [
    '--text-1',
    '--text-2',
    '--text-3',
    '--text-4',
    '--accent',
    '--bg-base',
    '--bg-surface',
    '--bg-subtle',
  ],
  textTokens: ['--text-1', '--text-4'],
  floor: 4.5,
  scenarios: [
    {
      // DER MASSGEBLICHE WERT: alle sieben Blüten gleichzeitig auf voller
      // Deckung. Zugleich der prefers-reduced-motion-Beweis — das Bild bleibt
      // vollständig stehen, es hört nur auf, sich zu verändern.
      name: 'finale',
      note: 'alle 7 Blüten voll = hellstmögliches Bild + reduced-motion-Beweis',
      flags: ['--force-prefers-reduced-motion'],
    },
    { name: 'hanabi-t1', note: 'Rhythmus bei 1 s virtueller Zeit', vt: 1000 },
    { name: 'hanabi-t4', note: 'Rhythmus bei 4 s', vt: 4000 },
    { name: 'hanabi-t7', note: 'Rhythmus bei 7 s', vt: 7000 },
    { name: 'hanabi-t10', note: 'Rhythmus bei 10 s', vt: 10000 },
  ],
};
