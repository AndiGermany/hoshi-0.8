/**
 * Der Kontrast-Beweis für AOI (Fassung v2).
 * ───────────────────────────────────────────────────────────────────────────
 * DIE KETTE IST KURZ — und das ist die eigentliche Aussage dieser Datei.
 * v1 stapelte Zeichnung (body::before) × Lichtschicht (body::after) × Schleier
 * (:root::after) und musste alle drei zusammen messen. v2 hat das Licht in die
 * Szene selbst gelegt; über der Lesespalte liegen jetzt nur noch:
 *
 *     Szene (deckendes SVG)  ×  Schleier (Tinte #0c1017, Alpha 0.88)
 *
 * Der Schleier ist rein SUBTRAKTIV: er blendet die Szene gegen einen Grund,
 * der dunkler ist als jeder Punkt der Szene, also kann er einen Bildpunkt nur
 * DUNKLER machen. Auf dunklem Grund heißt dunkler immer: mehr Kontrast. Damit
 * genügt es, den HELLSTEN erreichbaren Zustand der Szene zu messen.
 *
 * UND DER IST BEKANNT. Alle fünf Uhrenfamilien wohnen im SVG, und jede
 * Keyframe dort beginnt bei `opacity: 1` und läuft nur nach unten (REZEPT B).
 * `--force-prefers-reduced-motion` hält auch die Uhren in einem per
 * `background-image` geladenen Bild an — und hält sie damit auf ihrem
 * HELLSTEN Moment. Das Szenario `ruhe-hell` misst deshalb den echten oberen
 * Rand und nicht eine zufällig erwischte Phase. Es ist zugleich der
 * reduced-motion-Beweis.
 *
 * Die vier Zeitproben daneben sind kein zweiter Beweis, sondern eine Probe auf
 * die Behauptung: findet eine von ihnen einen schlechteren Wert als
 * `ruhe-hell`, dann stimmt die Behauptung „jede Keyframe geht nur runter"
 * nicht, und die Konstruktion ist widerlegt statt bestätigt.
 *
 * Ein `animation-delay` von außen bräuchte dieses Thema nicht mehr: es gibt
 * keine einzige Animation in aoi.css, die man einfrieren könnte.
 *
 *   node tools/theme-contrast/measure.mjs tools/theme-contrast/aoi.scenarios.mjs
 */

export default {
  theme: 'aoi',
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
      name: 'ruhe-hell',
      note: 'alle Uhren im SVG still = Szene auf ihrem HELLSTEN + reduced-motion-Beweis',
      flags: ['--force-prefers-reduced-motion'],
    },
    { name: 'bahnen-t1', note: 'Lichtbahnen bei 1 s virtueller Zeit', vt: 1000 },
    { name: 'bahnen-t4', note: 'Lichtbahnen bei 4 s', vt: 4000 },
    { name: 'bahnen-t7', note: 'Lichtbahnen bei 7 s', vt: 7000 },
    { name: 'bahnen-t10', note: 'Lichtbahnen bei 10 s', vt: 10000 },
  ],
};
