/**
 * Der Kontrast-Beweis für YOAKE (zweite Fassung).
 * ───────────────────────────────────────────────────────────────────────────
 * Yoake ist der schwierigste Fall der ganzen Reihe, und zwar aus einem Grund,
 * der nichts mit der Zeichnung zu tun hat: es ist ein DUNKLES Thema, dessen
 * MOTIV Licht ist. Bei nagareboshi kann man die Nacht dunkler malen, wenn es
 * eng wird; hier wäre „dunkler" die Abschaffung des Themas. Der Kontrast muss
 * also aus der Textleiter und dem Schleier kommen, nicht aus dem Bild.
 *
 * WAS DIESE MESSUNG EINFACHER MACHT ALS BEIM PILOTEN: in yoake.css läuft keine
 * einzige Animation mehr. Alle Uhren stehen im SVG (Glut 53–79 s ·
 * Lichtbahnen 37–47 s · Wolken 83–97 s · Sterne 64–81 s · Fenster 23–41 s),
 * und dort läuft jede Keyframe von opacity:1 nur nach UNTEN. Daraus folgt
 * zweierlei, und beides ist der eigentliche Beweis:
 *
 *   (a) Der ANGEHALTENE Zustand ist der HELLSTE. `sonne-hell` hält mit
 *       `--force-prefers-reduced-motion` auch die Uhren im Bild an (die
 *       Zeichnung trägt dieselbe Medienabfrage in ihrem eigenen <style>) und
 *       misst damit den echten oberen Rand — nicht eine Stichprobe. Es gibt
 *       keinen Moment im Umlauf, der heller wäre.
 *
 *   (b) Dasselbe Szenario ist zugleich der prefers-reduced-motion-Beweis. Zwei
 *       Fliegen: der Ruhezustand ist vollständig UND der Worst Case.
 *
 * Die vier Zeitproben sind trotzdem da, aber sie beweisen etwas anderes: dass
 * sich überhaupt etwas bewegt, und dass zwischendurch keine Kombination
 * entsteht, an die die Konstruktion nicht gedacht hat. Sie MÜSSEN alle über
 * `sonne-hell` liegen; täten sie es nicht, wäre irgendwo eine Keyframe nach
 * oben gelaufen und die ganze Beweisführung hin.
 *
 *   node tools/theme-contrast/measure.mjs tools/theme-contrast/yoake.scenarios.mjs
 */

export default {
  theme: 'yoake',
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
  /* Gemessen wird gegen die LAUTESTE und die LEISESTE Stufe. Die leiseste ist
     die bindende: --text-1 hat über --bg-base 17:1 und wird nie knapp, --text-4
     entscheidet über jeden einzelnen Zahlenwert dieser Reihe. */
  textTokens: ['--text-1', '--text-4'],
  floor: 4.5,
  scenarios: [
    {
      name: 'sonne-hell',
      note: 'alle Uhren still = Glut, Bahnen und Fenster auf ihrem HELLSTEN + reduced-motion-Beweis',
      flags: ['--force-prefers-reduced-motion'],
    },
    { name: 'lauf-t1', note: 'Herzschlag bei 1 s virtueller Zeit', vt: 1000 },
    { name: 'lauf-t4', note: 'Herzschlag bei 4 s', vt: 4000 },
    { name: 'lauf-t7', note: 'Herzschlag bei 7 s', vt: 7000 },
    { name: 'lauf-t10', note: 'Herzschlag bei 10 s', vt: 10000 },
  ],
};
