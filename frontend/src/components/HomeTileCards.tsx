import { useEffect, useRef, useState, type HTMLAttributes, type ReactNode } from 'react';
import type { HomeRegistryState } from '../api/homeRegistry';
import { sendVacuumAction, type VacuumAction } from '../api/vacuumActions';
import {
  climateExtremes,
  climateRoomRows,
  findVacuumFamily,
  fmtTemp,
  formatClock,
  formatMaintenanceDuration,
  formatRelativeAge,
  homeTileUnavailableSince,
  isSameLocalDay,
  vacuumActionAvailability,
  vacuumFamilyAmber,
  vacuumFamilyAttached,
  vacuumFamilyBattery,
  vacuumFamilyCacheSince,
  vacuumFamilyErrorDetails,
  vacuumFamilyProgress,
  vacuumFamilyRoom,
  vacuumFamilyStatus,
  vacuumLastClean,
  vacuumLastKnownBattery,
  vacuumLastKnownStatus,
  vacuumMaintenanceSeconds,
  vacuumMaintenanceValue,
  type ClimateRoomRow,
  type VacuumFamily,
  type VacuumMaintenanceValue,
  type VacuumTileStatus,
} from './homeTiles';
import type { HomeTileSize } from './homeWidgets';
import { useHomeTileLastSeen } from '../hooks/useSettings';
import { useUiStrings } from '../i18n';

/**
 * **HomeTiles** — die Sauger-/Klima-Kachel des Zuhause-Reiters (Andi-Auftrag
 * 2026-08-11: „Zuhause-Kacheln, die man sich verdient"). Reine, prop-
 * getriebene Präsentation (Muster der Läuft-/Einkaufs-Karten in
 * `IdleFace.tsx`) — die Datenableitung selbst liegt in `components/
 * homeTiles.ts`. Beide Kacheln nehmen denselben `registry`-Snapshot: `null`
 * (Fetch läuft) und `off`/`unreachable` (Naht aus/kaputt) zeigen dieselbe
 * ehrliche stille Zeile wie eine nicht erreichbare Entity — die Kachel
 * rendert erst überhaupt (aktivierbar in den Einstellungen), wenn ihre
 * Quelle beim letzten bekannten Snapshot real war; ein späterer Ausfall
 * bleibt trotzdem ehrlich sichtbar statt kommentarlos zu verschwinden.
 *
 * **Sauger-Metrik-Familie (Andi-Auftrag 2026-08-13):** die Sauger-Kachel
 * liest NICHT mehr nur die `vacuum.*`-Entity, sondern ihre ganze Geschwister-
 * Familie (`sensor.*`/`binary_sensor.*` mit demselben Stamm-Präfix, s.
 * `components/homeTiles.ts#findVacuumFamily`) — jede Zeile NUR bei
 * brauchbarem Wert, Amber defensiv, Details s. dortige KDoc.
 *
 * **Last-known-good-Fallback (additiv, Andi-Auftrag 2026-08-13, „Sauger-
 * Sichtbarkeits-Lücke" — der Roborock hängt ~23 h/Tag im WLAN-Tiefschlaf, das
 * 15-min/60-s-Poll-Raster trifft sein Wach-Fenster fast nie):** ist der LIVE-
 * Zustand unbrauchbar, aber der BE hat einen `lastKnown`-Stand mitgeschickt,
 * zeigen BEIDE Kacheln eine warme „Zuletzt gesehen …"-Zeile statt der stillen
 * „nicht erreichbar"-Zeile — NIE amber, ein ALTER Fehler ist kein AKTUELLER
 * Alarm. `nowMs` ist injiziert (Muster `IdleFace`s `nowMs`-Prop) statt
 * `Date.now()` direkt zu rufen, damit die relative Zeit testbar bleibt;
 * Default `Date.now()` hält bestehende `<VacuumTile>`/`<ClimateTile>`-Aufrufer
 * ohne dieses Prop unverändert lauffähig.
 *
 * **Honest presence (slice S2, DESIGN-widgets-settings-2026-08-15 §2.4):** both
 * tiles now remember, per browser, when their source was last demonstrably
 * alive ({@link useHomeTileLastSeen}). A source that falls back after having
 * been seen no longer whispers in 13px — it says for how long it has been gone,
 * in the tile's normal 18px `.idle__hometilestale` voice and never in amber.
 * A source this browser has NEVER seen keeps the old quiet line: first
 * appearance stays earned, exactly as before. Precedence when the live state is
 * unusable: BE `lastKnown` (real old data, richest) → FE presence memory (we
 * know it existed, not what it said) → quiet line (we know nothing).
 *
 * **Size (W1, DESIGN-widget-raster-2026-08-18 §3.2/§3.3):** both tiles take a
 * `size` prop (default `'M'` — a bare test call without one behaves like the
 * settings default) that gates which LIVE fields render — S is the status
 * sentence only, M adds room/battery resp. two room rows, L adds the rest
 * (progress, error details, the maintenance fold; 4 room rows + fold).
 *
 * **XL (W5, §3.3)** exists for the climate tile (up to
 * {@link CLIMATE_TILE_XL_VISIBLE} room rows in two columns, rest folded as
 * before) — **and since 22.08. for the vacuum tile too** (`homeWidgets.ts`),
 * where it lays the maintenance block out in two columns.
 *
 * The unreachable/last-known-good fallback lines are deliberately
 * size-INDEPENDENT: an outage is the same outage regardless of how large the
 * tile currently is. **The cache whisper ("Stand 14:20") is size-independent
 * for the same reason** — stale values are stale values on a 1×1 tile too.
 *
 * ---
 *
 * **Andi 21.08., wörtlich — die drei Sachen, die diese Datei seither anders macht:**
 *
 * 1. *„Zuletzt gesehen vor 2 Min.: Schläft in der Ladestation / Akku 100 % —
 *    ich hätte gerne, dass du die Informationen bitte nützlich anzeigst" +
 *    „Das ist Lärm, meistens ist er einfach im Energiesparmodus."* Die
 *    „Zuletzt gesehen"-Zeile war nie falsch — sie war nur die Antwort auf die
 *    falsche Frage. Ein Roborock im Energiesparmodus ist NICHT abwesend, er
 *    schläft in seiner Ladestation, und der BE trägt seine Werte seit dem
 *    Cache-Carry (`fromCacheSinceMs`) durch den Schlaf. Damit ist die Kachel im
 *    Normalfall wieder ein **Zustandssatz** („Bereit in der Ladestation · Akku
 *    100 %"); die laute Abwesenheits-Zeile bleibt für die echte Abwesenheit
 *    jenseits des Cache-Fensters übrig, wo sie hingehört. Dass die Werte aus
 *    dem Gedächtnis kommen, sagt eine **leise** Fußnote („Stand 14:20") —
 *    verschwiegen wird nichts, geschrien auch nichts.
 * 2. *„Was haben wir noch, was man hinzufügen kann, wenn man das Widget größer
 *    macht?"* Vier gemappte, aber nie abgeleitete Felder: `lastCleanEnd` +
 *    `lastCleanStart` („zuletzt fertig 14:20 · 1 h 40"), `moppDrying`, und die
 *    vier Wartungs-Restzeiten, die auf L noch hinter einem `<details>` lagen,
 *    obwohl die 2×2-Fläche längst da war (§3.2: „Auf 2×2 ist ein Fold sinnlos").
 * 3. *„Können wir den Sauger starten und nach Hause fahren lassen?"* Zwei
 *    Knöpfe im Kachel-Stil ({@link VacuumActions}) auf `POST
 *    /api/v1/home/vacuum/{start|return_to_base}`. **Kein optimistisches
 *    Umschreiben**: eine 200 heißt „Home Assistant hat den Auftrag
 *    angenommen", nicht „der Sauger fährt" — die Kachel behält ihren Zustand,
 *    bis das Polling etwas anderes sagt.
 */

/**
 * `article`-Rahmen, byte-gleich zu den anderen `idle__tiles`-Karten
 * (Läuft/Einkauf) — ABER nur mit dem „live"-Pill, wenn wirklich ein echter
 * Zustand da ist (`live`). Nicht erreichbar ⇒ kein Pill (die ehrliche stille
 * Zeile im Rumpf reicht, ein „live"-Badge über einer „nicht erreichbar"-
 * Zeile wäre unehrlich); Amber (Sauger-Fehler) ⇒ zusätzliche `tile--warn`-
 * Klasse, NIE zusammen mit „nicht erreichbar".
 *
 * **Durchreiche-Pflicht (W5, gefunden am Klima-XL-Bild):** `placeTile`
 * (`HomeStage.tsx`) setzt die berechnete Zelle per `cloneElement` als
 * `style.gridColumn/gridRow` auf das **Wurzelelement** dessen, was `node()`
 * liefert — sein KDoc sagt ausdrücklich „an `<article className="tile
 * idle__tile …">`, always". Für die inline gebauten Kacheln stimmt das; Klima
 * und Sauger liefern aber eine **Komponente** (`<ClimateTile/>`/`<VacuumTile/>`),
 * und eine Komponente schluckt `style` stumm, statt es weiterzureichen. Ergebnis
 * vor diesem Fix, gemessen: die Klima-Kachel stand bei gespeichertem XL auf
 * **285 × 269 px** (CSS-Auto-Platzierung, 1×1) statt auf 880 × 550 — der
 * XL-Inhalt rechnete mit voller Bühnenbreite, die Zelle gab eine Spalte her.
 * Genau der S1-Fehler („bleibt XL, nur zusammengepresst"), gegen den das
 * ganze Modell gebaut ist.
 *
 * Deshalb sind {@link ClimateTile}/{@link VacuumTile} und diese Karte für
 * alles **transparent**, was die Bühne injiziert: `style`, `data-widget-id`,
 * `data-sizing`/`data-edit` sowie die Edit-A11y (`tabIndex`, `role`,
 * `aria-*`, `onKeyDown`). Ein zusätzliches Wrapper-`<div>` wäre der Gegen-Weg
 * gewesen — `placeTile` lehnt ihn begründet ab (es würde ein Grid-Item ohne
 * die CSS-Größen der Kachel einziehen), also reicht die Kachel durch.
 */
function HomeTileCard({
  name,
  live,
  amber,
  step,
  headAction,
  children,
  ...rest
}: {
  name: string;
  live: boolean;
  amber?: boolean;
  /**
   * Ein einzelnes Bedienelement RECHTS IM KOPF statt unter dem Rumpf — der
   * Platz, den eine flache 1×1-Kachel noch hat (Andi 23.08.: „Den Start button
   * hätte ich für den sauger auch gerne im kleinen widget. hier soll es ein
   * play button sein.").
   *
   * Warum im Kopf und nicht als eigene Zeile: die Kopfzeile ist 23 px hoch und
   * hat rechts nur Luft. Ein 44-px-Ziel darin macht sie 44 px hoch — die
   * S-Kachel kostet das 21 px. Als eigene ZEILE hätte derselbe Knopf 50 px
   * gekostet (44 + Fuge) und die Zustandszeile auf einer 108-px-Kachel
   * (iPad hoch, gemessen mit `tools/zuhause-probe/schnitt.mjs`) unter die
   * Kante gedrückt — also genau den Schnitt erzeugt, den dieselbe Bestellung
   * abschaffen will.
   */
  headAction?: ReactNode;
  /**
   * Die EFFEKTIVE Stufe als DOM-Haken (W6) — dasselbe `data-size`, das die
   * Wetter-Kachel seit 19.08. trägt. Es gibt der Typo einen Griff, ohne dass
   * die Komponente Größen ausrechnet: „auf einer hohen Kachel dürfen die
   * Zeilen Flur-Größe haben" ist eine CSS-Frage, keine React-Frage.
   */
  step?: HomeTileSize;
  children: ReactNode;
} & HTMLAttributes<HTMLElement>) {
  return (
    <article
      {...rest}
      className={`tile idle__tile tile--live${amber ? ' tile--warn' : ''}${live ? '' : ' tile--unreachable'}`}
      data-status={live ? 'live' : 'unreachable'}
      data-size={step}
    >
      {/* KEINE `live`-Pille mehr (W6, Andi 20.08.: „Das Live kann aus den
          Widgets raus, das gibt uns etwas Platz"). Sie sagte auf jeder Kachel
          dasselbe Wort und war damit keine Auskunft, sondern Tapete — die
          EHRLICHEN Zustände tragen weiterhin ihre eigene Sprache: `tile--warn`
          (amber Rahmen), `tile--unreachable` + die stille „nicht erreichbar"-
          Zeile, und beim Lagebild der Amber-STALE-Hinweis. Verloren geht nur
          das Frische-Abzeichen, nicht die Wahrheit. */}
      <div className="tile__head">
        <span className="tile__name">{name}</span>
        {headAction}
      </div>
      {children}
    </article>
  );
}

/** „12 h" (Wert + Einheit) oder nur „12", wenn HA keine Einheit mitliefert — NIE eine Einheit erfunden. */
function formatMaintenanceValue(v: VacuumMaintenanceValue): string {
  return v.unit ? `${v.value} ${v.unit}` : v.value;
}

/**
 * **Die eigentliche Lesbarkeits-Reparatur** (Andi 22.08., wörtlich:
 * „Hauptbürste: 634362 s … aber nicht in Sekunden ^^",
 * `ORDER-sauger-wartung-lesbar-2026-08-22.md`): `v.value` als Sekunden lesen
 * ({@link vacuumMaintenanceSeconds}, NUR bei bekannter Einheit — NIE geraten)
 * und daraus „noch ~7 Tage"/„überfällig seit ~12 h" bauen
 * ({@link formatMaintenanceDuration}). Kennt HA-Einheit ODER Zahl nicht,
 * bleibt der alte, ehrliche Wert+Einheit-Text der Rückfall — ein künftiger
 * Sauger, der z.B. Prozent statt Zeit liefert, bekommt dadurch KEINE erfundene
 * Restzeit angedichtet.
 *
 * `overdue` trägt NUR die Info für die dezente Warn-Optik der Zeile (Auftrag:
 * „mit dezenter Warn-Optik") — bewusst NICHT dieselbe laute Amber-Klasse wie
 * ein echter Sauger-Fehler (`vacuumFamilyAmber`): eine überfällige Bürste ist
 * eine Erinnerung, kein Alarm.
 */
function formatMaintenanceRow(v: VacuumMaintenanceValue, t: VacuumStrings): { text: string; overdue: boolean } {
  const seconds = vacuumMaintenanceSeconds(v);
  if (seconds === null) return { text: formatMaintenanceValue(v), overdue: false };
  return { text: formatMaintenanceDuration(seconds, t.maintenance), overdue: seconds < 0 };
}

/**
 * Dauer eines Reinigungslaufs als „1 h 40 min" / „25 min". Auf ganze Minuten
 * gerundet und bei mindestens 1 min gehalten — ein Lauf, den HA mit 12
 * Sekunden Differenz meldet, ist eher ein Sensor-Zucken als eine Reinigung,
 * und „0 min" wäre die unbrauchbarste aller Angaben.
 */
function formatSpan(ms: number, t: VacuumStrings): string {
  const totalMinutes = Math.max(1, Math.round(ms / 60_000));
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  return hours > 0 ? t.duration.hoursMinutes(hours, minutes) : t.duration.minutes(minutes);
}

/** Die Sauger-Texte aus dem Katalog — einmal benannt, damit die Helfer unten nicht dreimal denselben Typ-Ausdruck tragen. */
type VacuumStrings = ReturnType<typeof useUiStrings>['idleFace']['homeTiles']['vacuum'];

/**
 * **Wartungsblock der Sauger-Kachel, aufgeklappt statt gefaltet** (§3.2
 * wörtlich: „Auf 2×2 ist ein `<details>`-Fold sinnlos — die Fläche ist da").
 * Hauptbürste-/Seitenbürste-/Filter-/Sensoren-Restzeit + Mopp-/Wasserkasten-
 * Anbringung + **Mopp-Trocknung** (`dock_mopp_trocknung`, seit dem
 * Familien-Bau gemappt und bis 22.08. nie angezeigt) — jede Zeile NUR, wenn
 * ihr Wert brauchbar ist.
 *
 * Komplett leer (die Familie liefert nichts davon) ⇒ **gar nichts**, auch
 * keine Überschrift: eine Rubrik „Wartung" über null Zeilen wäre genau das
 * Auffüllen, das §2.3 verbietet.
 *
 * `columns` (XL) legt dieselben Zeilen zweispaltig — Muster der Klima-XL-Liste
 * (`.idle__cardlist--two`), kein zweiter Layout-Weg.
 *
 * **Mopp + Wasserkasten als EINE Zeile** (Andi 22.08. wörtlich: „Mopp dran /
 * Wasserkasten dran" — zwei eigene `<li>` für zwei Anbau-Sensoren zerlegten
 * einen einzigen Blick in zwei Blicke). Beide Sensoren brauchbar ⇒ EINE
 * Zeile, mit demselben „ · "-Trenner wie der Heizt-Zusatz der Klima-Kachel;
 * fehlt einer der beiden GANZ, bleibt die Zeile des anderen allein stehen
 * (Verdien-Regel unverändert: „Zeile entfällt, wenn der Sensor fehlt" gilt
 * pro Sensor, nicht für das Paar).
 */
function VacuumMaintenance({ family, t, columns }: { family: VacuumFamily; t: VacuumStrings; columns: boolean }) {
  const mainBrush = vacuumMaintenanceValue(family.mainBrushTimeLeft);
  const sideBrush = vacuumMaintenanceValue(family.sideBrushTimeLeft);
  const filter = vacuumMaintenanceValue(family.filterTimeLeft);
  const sensor = vacuumMaintenanceValue(family.sensorTimeLeft);
  const mopp = vacuumFamilyAttached(family.moppAttached);
  const waterbox = vacuumFamilyAttached(family.waterboxAttached);
  const drying = vacuumFamilyAttached(family.moppDrying);
  const rows: ReactNode[] = [];
  const row = (key: string, label: (value: string) => string, v: VacuumMaintenanceValue | null) => {
    if (!v) return;
    const { text, overdue } = formatMaintenanceRow(v, t);
    rows.push(
      <li key={key} className={overdue ? 'idle__cardlist__row--overdue' : undefined}>
        {label(text)}
      </li>,
    );
  };
  row('mainBrush', t.maintenance.mainBrush, mainBrush);
  row('sideBrush', t.maintenance.sideBrush, sideBrush);
  row('filter', t.maintenance.filter, filter);
  row('sensor', t.maintenance.sensor, sensor);
  const moppText = mopp === null ? null : mopp ? t.maintenance.moppAttached : t.maintenance.moppNotAttached;
  const waterboxText =
    waterbox === null ? null : waterbox ? t.maintenance.waterboxAttached : t.maintenance.waterboxNotAttached;
  if (moppText && waterboxText) rows.push(<li key="mopwaterbox">{`${moppText} · ${waterboxText}`}</li>);
  else if (moppText) rows.push(<li key="mopwaterbox">{moppText}</li>);
  else if (waterboxText) rows.push(<li key="mopwaterbox">{waterboxText}</li>);
  // Nur die EINE interessante Hälfte: „trocknet gerade" ist eine Nachricht,
  // „trocknet nicht" ist der Normalzustand und damit Tapete (dieselbe
  // Zurückhaltung wie beim Amber: im Zweifel keine Zeile).
  if (drying === true) rows.push(<li key="drying">{t.maintenance.moppDrying}</li>);
  if (rows.length === 0) return null;
  return (
    <>
      <p className="idle__hometilesubhead">{t.maintenance.summary}</p>
      <ul className={`idle__cardlist idle__cardlist--vacuum${columns ? ' idle__cardlist--two' : ''}`}>{rows}</ul>
    </>
  );
}

/**
 * Wie lange das Annahme-Feedback am Knopf stehen bleibt, bevor es leise
 * verschwindet. Vier Sekunden sind lang genug zum Lesen aus 2 m und kurz genug,
 * dass die Meldung nicht zur Tapete wird — und deutlich kürzer als das
 * Registry-Poll-Raster, damit die Bestätigung nicht neben einem bereits
 * aktualisierten Zustand stehen bleibt.
 */
export const VACUUM_ACK_MS = 4000;

/**
 * **Die zwei Knöpfe** (Andi 21.08.: „Können wir den Sauger starten und nach
 * Hause fahren lassen?"). Semantik NICHT hier, sondern in
 * `homeTiles.ts#vacuumActionAvailability` — eine reine Funktion, die man ohne
 * DOM prüfen kann. Diese Komponente ist nur Hand und Rückmeldung.
 *
 * **Drei Regeln, die sie NICHT bricht:**
 *
 * 1. **Kein optimistisches Umschreiben.** Nach einer 200 ändert sich am
 *    Kachel-Zustand nichts. Der BE `invalidate()`t seinen Registry-Cache, der
 *    nächste Poll liest frisch bei HA nach — steht dort noch `docked`, ist DAS
 *    die Wahrheit und keine Panne. Eine Kachel, die schon „saugt" behauptet,
 *    während der Sauger noch in der Station steht, wäre genau die Sorte
 *    Freundlichkeit, die Vertrauen kostet.
 * 2. **Nur ehrliches Feedback.** Angenommen ⇒ ein kurzer Satz, der sagt, was
 *    wirklich passiert ist („Home Assistant hat den Auftrag"). Fehlgeschlagen
 *    ⇒ die Server-Meldung im Klartext (die BE-Bodies sind bereits deutsch und
 *    ehrlich, inkl. HA's echtem Statuscode bei 502) — nie ein erfundener Grund.
 * 3. **Im EDIT-Modus inert** (W4/W7-Regel). `HomeStage` setzt allen
 *    Kachel-Kindern `inert` und CSS `pointer-events:none`, sobald Andi
 *    anordnet — sonst würde ein Zug an der Kachel den Sauger losschicken. Die
 *    Knöpfe verlassen sich NICHT darauf: sie lesen das `data-edit` der Kachel
 *    selbst und schalten sich `disabled` + aus dem Tab-Fluss. Zwei Riegel für
 *    einen Klick, der einen Roboter durch die Wohnung schickt.
 */
function VacuumActions({
  status,
  editing,
  t,
  compact = false,
}: {
  status: VacuumTileStatus;
  editing: boolean;
  t: VacuumStrings;
  /**
   * **Die S-Stufe: NUR Play** (Andi 23.08., wörtlich: „Den Start button hätte
   * ich für den sauger auch gerne im kleinen widget. hier soll es ein play
   * button sein.").
   *
   * Derselbe Endpoint, dieselbe Semantik, derselbe Riegel im Edit-Modus — nur
   * ohne Beschriftung und ohne die zweite Tat. „Zur Basis" bleibt bewusst
   * draußen: ein Sauger, der unterwegs ist, braucht eine Entscheidung, und
   * eine Entscheidung in ein 44-px-Quadrat ohne Wort zu pressen wäre eine
   * Falle (welcher der beiden Knöpfe war das noch mal?). Kann der Sauger
   * gerade nicht starten, erscheint hier GAR NICHTS — `canStart` ist dieselbe
   * reine Funktion wie für den großen Knopf, nicht eine zweite Meinung.
   *
   * Die Rückmeldezeile bleibt im DOM (`aria-live`), nur unsichtbar: wer sie
   * nicht sieht, HÖRT sie weiterhin. Sichtbares Feedback trägt in dieser Stufe
   * der gesperrte Knopf, solange die Tat unterwegs ist.
   */
  compact?: boolean;
}) {
  const [pending, setPending] = useState<VacuumAction | null>(null);
  const [accepted, setAccepted] = useState<VacuumAction | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const ackTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => { if (ackTimer.current !== null) clearTimeout(ackTimer.current); }, []);
  const { canStart, canReturn } = vacuumActionAvailability(status);
  if (compact ? !canStart : !canStart && !canReturn) return null;

  const run = async (action: VacuumAction) => {
    if (ackTimer.current !== null) clearTimeout(ackTimer.current);
    setPending(action);
    setAccepted(null);
    setFailure(null);
    try {
      await sendVacuumAction(action, t.actions.networkError);
      setAccepted(action);
      ackTimer.current = setTimeout(() => setAccepted(null), VACUUM_ACK_MS);
    } catch (e) {
      setFailure(e instanceof Error && e.message ? e.message : t.actions.failed);
    } finally {
      setPending(null);
    }
  };

  const button = (action: VacuumAction, label: string) => (
    <button
      type="button"
      className="idle__hometileaction"
      // `pending` sperrt BEIDE Knöpfe: zwei gleichzeitig laufende Taten am
      // selben Gerät wären eine Wette darauf, welche zuerst bei HA ankommt.
      disabled={editing || pending !== null}
      tabIndex={editing ? -1 : undefined}
      onClick={() => void run(action)}
    >
      {label}
    </button>
  );

  const note = (
    /* EINE Rückmeldezeile für beide Knöpfe, `aria-live` — wer sie nicht
       sieht, hört sie. Reihenfolge: Fehler schlägt Bestätigung (ein
       gescheiterter Versuch nach einem geglückten ist die neuere Nachricht). */
    <p
      className={compact ? 'sr-only' : 'idle__hometileactionnote'}
      role="status"
      aria-live="polite"
    >
      {failure ?? (pending !== null ? t.actions.sending : accepted !== null ? t.actions.accepted : '')}
    </p>
  );

  if (compact) {
    return (
      <>
        <button
          type="button"
          className="idle__hometileaction idle__hometileaction--play"
          aria-label={t.actions.start}
          title={t.actions.start}
          disabled={editing || pending !== null}
          tabIndex={editing ? -1 : undefined}
          onClick={() => void run('start')}
        >
          {/* Ein gezeichnetes Dreieck, kein „▶"-Zeichen: das Unicode-Symbol
              wird auf iOS als farbiges Emoji ersetzt und sprengt damit Farbe
              wie Grundlinie der Kachel. */}
          <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true" focusable="false">
            <path d="M4 2.6 13 8l-9 5.4z" fill="currentColor" />
          </svg>
        </button>
        {note}
      </>
    );
  }

  return (
    <div className="idle__hometileactions">
      <div className="idle__hometileactionrow">
        {canStart && button('start', t.actions.start)}
        {canReturn && button('return_to_base', t.actions.returnToBase)}
      </div>
      {note}
    </div>
  );
}

export function VacuumTile({
  registry,
  nowMs = Date.now(),
  size = 'M',
  ...rest
}: {
  registry: HomeRegistryState | null;
  nowMs?: number;
  size?: HomeTileSize;
  /**
   * Kommt von `HomeStage#placeTile` per `cloneElement`, wenn Andi anordnet.
   * Wird NICHT herausdestrukturiert, sondern nur mitgelesen — `...rest` muss
   * es weiterhin ans `<article>` durchreichen (die CSS-Regeln des Edit-Modus
   * hängen daran).
   */
  'data-edit'?: string;
} & HTMLAttributes<HTMLElement>) {
  const { idleFace, locale } = useUiStrings();
  const t = idleFace.homeTiles.vacuum;
  const cs = size;
  const editing = rest['data-edit'] === 'true';
  const family = registry !== null && registry.kind === 'live' ? findVacuumFamily(registry.data) : null;
  const status = vacuumFamilyStatus(family);
  // S2: refresh the presence stamp on every tick the sauger really answers,
  // read it back when it does not. Called BEFORE the early return below —
  // hooks are unconditional, and the value is needed in exactly that branch.
  const lastSeenMs = useHomeTileLastSeen('vacuum', status.kind === 'unreachable' ? null : nowMs);
  if (status.kind === 'unreachable') {
    // Last-known-good-Fallback (s. Datei-KDoc): NUR wenn die vacuum.*-Entity
    // selbst einen gemerkten Stand trägt — bewusst NICHT amber, egal welcher
    // Zustand zuletzt gemerkt wurde (ein alter „error" ist kein akuter Alarm).
    const lastKnown = vacuumLastKnownStatus(family);
    if (lastKnown) {
      const relative = formatRelativeAge(lastKnown.seenAtMs, nowMs, idleFace.homeTiles.age);
      const battery = vacuumLastKnownBattery(family);
      return (
        <HomeTileCard {...rest} step={cs} name={t.name} live={false}>
          <p className="idle__hometilestale">{t.lastKnownLine(relative, t.status[lastKnown.status])}</p>
          {battery !== null && <p className="idle__hometilesub">{t.battery(battery)}</p>}
        </HomeTileCard>
      );
    }
    // S2 honest presence: no old state to show, but this browser HAS seen the
    // sauger alive — then the outage itself is the news, and it is said out
    // loud (18px/--text-3, never amber: an old outage is not an acute alarm).
    const since = homeTileUnavailableSince(registry, lastSeenMs);
    if (since !== null) {
      return (
        <HomeTileCard {...rest} step={cs} name={t.name} live={false}>
          <p className="idle__hometilestale">
            {idleFace.homeTiles.unavailableSince(
              formatRelativeAge(since, nowMs, idleFace.homeTiles.age),
            )}
          </p>
        </HomeTileCard>
      );
    }
    return (
      <HomeTileCard {...rest} step={cs} name={t.name} live={false}>
        <p className="idle__hometileunavailable">{t.unreachable}</p>
      </HomeTileCard>
    );
  }
  const amber = vacuumFamilyAmber(family, status);
  const statusText = status.kind === 'known' ? t.status[status.status] : t.hybridStatus[status.status];
  // S: Zustandssatz allein. M: + Raum + Akku, aber IN derselben Zeile
  // (Andis Bild „Bereit in der Ladestation · Akku 100 %" ist EIN Satz, keine
  // Liste aus drei Absätzen). L/XL: + Fortschritt, „zuletzt fertig", Fehler-
  // details, Wartung aufgeklappt (§3.2).
  const big = cs === 'L' || cs === 'XL';
  const room = cs === 'S' ? null : vacuumFamilyRoom(family);
  const battery = cs === 'S' ? null : vacuumFamilyBattery(family);
  const progress = big ? vacuumFamilyProgress(family) : null;
  const errorDetails = big ? vacuumFamilyErrorDetails(family) : [];
  const lastClean = big ? vacuumLastClean(family, nowMs) : null;
  // Die Fußnote ist size-UNABHÄNGIG, aus demselben Grund wie die
  // Abwesenheits-Zeile: gecachte Werte sind auf einer 1×1-Kachel genauso
  // gecacht wie auf einer 4×2.
  const cacheSince = vacuumFamilyCacheSince(family);
  let headline = statusText;
  if (room) headline = t.withRoom(headline, room);
  if (battery !== null) headline = t.withBattery(headline, battery);
  return (
    <HomeTileCard
      {...rest}
      step={cs}
      name={t.name}
      live
      amber={amber}
      headAction={cs === 'S' ? <VacuumActions status={status} editing={editing} t={t} compact /> : undefined}
    >
      <p className={amber ? 'idle__hometilewarn' : 'idle__hometileline'}>{headline}</p>
      {progress !== null && <p className="idle__hometilesub">{t.progress(progress)}</p>}
      {lastClean !== null && (
        <p className="idle__hometilesub">
          {/* Am selben Kalendertag reicht die Uhrzeit; ist der Lauf älter,
              wäre „zuletzt fertig 14:20" ohne Datum eine Falle — dann sagt die
              Zeile das Alter in denselben Zeitwörtern wie der Rest des Hauses
              (`homeTiles.age`), statt ein zweites Datumsformat einzuführen. */}
          {isSameLocalDay(lastClean.endMs, nowMs)
            ? t.lastCleanToday(formatClock(lastClean.endMs, locale))
            : t.lastCleanAgo(formatRelativeAge(lastClean.endMs, nowMs, idleFace.homeTiles.age))}
          {lastClean.durationMs !== null && ` · ${t.lastCleanDuration(formatSpan(lastClean.durationMs, t))}`}
        </p>
      )}
      {errorDetails.map((e) => (
        <p key={e.source} className="idle__hometilewarn">
          {e.source === 'vacuum' ? t.vacuumErrorDetail(e.value) : t.dockErrorDetail(e.value)}
        </p>
      ))}
      {big && family !== null && <VacuumMaintenance family={family} t={t} columns={cs === 'XL'} />}
      {cs !== 'S' && <VacuumActions status={status} editing={editing} t={t} />}
      {cacheSince !== null && (
        <p className="idle__hometilewhisper">{t.cacheSince(formatClock(cacheSince, locale))}</p>
      )}
    </HomeTileCard>
  );
}

/** Ab dieser Zeilenzahl faltet die Klima-Kachel den Rest hinter „+n weitere" (Andi-Vorgabe „max 4 Zeilen" — jetzt die L-Stufe, §3.3). */
export const CLIMATE_TILE_VISIBLE = 4;
/** M-Stufe (§3.3): 2 Raumzeilen direkt sichtbar, Rest gefaltet wie bei L. */
export const CLIMATE_TILE_M_VISIBLE = 2;
/**
 * XL-Stufe (§3.3). Die Zahl ist ein DECKEL, keine Vorgabe: **es gibt so viele
 * Zeilen, wie es Räume gibt.** Wer drei Thermostate hat, sieht bei XL drei
 * Zeilen und ruhige Fläche — kein Füllmaterial (§2.3 „L/XL erfindet niemals
 * Inhalt"). Über dem Deckel greift dieselbe „+n weitere"-Faltung wie bei M/L.
 *
 * **12 statt der Doc-Zahl 8 (Selbstabnahme W5, gemessen).** Das Bild mit 10
 * echten Räumen zeigte 8 Zeilen, faltete 2 weg — und ließ darunter **55 % der
 * 880 × 550-Kachel leer.** Die Faltung ist dafür da, die Kachel zu schützen,
 * wenn der Inhalt sie sprengt; hier hat sie echte Räume versteckt, während
 * Platz frei stand. 12 = 6 Zeilen je Spalte, das passt auch auf eine kurze
 * Bühne (2 × 132 px) noch in den Scroll-Käfig. Die Doc-Zahl 8 war eine
 * Schätzung vor dem ersten Bild — **Rate-Stelle für Andi**, sie steht so in
 * §3.3.
 */
export const CLIMATE_TILE_XL_VISIBLE = 12;

function ClimateRow({ row, locale, nowMs }: { row: ClimateRoomRow; locale: string; nowMs: number }) {
  const { idleFace } = useUiStrings();
  const t = idleFace.homeTiles.climate;
  if (!row.available || row.currentTemperature === null || row.targetTemperature === null) {
    // Last-known-good-Fallback (s. Datei-KDoc): NUR diese eine Zeile weicht ab,
    // der Rest der Kachel/anderer Räume bleibt unberührt.
    const lk = row.lastKnown;
    if (lk && lk.currentTemperature !== null && lk.targetTemperature !== null) {
      const relative = formatRelativeAge(lk.seenAtMs, nowMs, idleFace.homeTiles.age);
      return (
        <li className="idle__hometilestale">
          {t.lastKnownRoomLine(row.label, fmtTemp(lk.currentTemperature, locale), fmtTemp(lk.targetTemperature, locale), relative)}
        </li>
      );
    }
    return <li className="idle__hometileunavailable">{t.roomUnreachable(row.label)}</li>;
  }
  return (
    <li>
      {t.roomLine(row.label, fmtTemp(row.currentTemperature, locale), fmtTemp(row.targetTemperature, locale))}
      {row.heating && <span className="idle__heatingdot"> · {t.heating}</span>}
    </li>
  );
}

export function ClimateTile({
  registry,
  nowMs = Date.now(),
  size = 'M',
  // `stageProps` statt `rest`: der Name `rest` gehört hier unten schon den
  // gefalteten Raumzeilen (`rows.slice(visible)`).
  ...stageProps
}: {
  registry: HomeRegistryState | null;
  nowMs?: number;
  size?: HomeTileSize;
} & HTMLAttributes<HTMLElement>) {
  const { idleFace, locale } = useUiStrings();
  const t = idleFace.homeTiles.climate;
  const cs = size;
  const rows = registry !== null && registry.kind === 'live' ? climateRoomRows(registry.data) : [];
  const unreachable = registry === null || registry.kind !== 'live' || rows.length === 0;
  // S2: presence is judged for the TILE, not per room — a single room that
  // drops out already has its own honest line (`lastKnownRoomLine`/
  // `roomUnreachable`) and leaves the rest of the card standing. What this
  // remembers is the coarser fact "there were climate rooms at all".
  const lastSeenMs = useHomeTileLastSeen('climate', unreachable ? null : nowMs);
  const since = unreachable ? homeTileUnavailableSince(registry, lastSeenMs) : null;
  const visible =
    cs === 'M'
      ? CLIMATE_TILE_M_VISIBLE
      : cs === 'XL'
        ? CLIMATE_TILE_XL_VISIBLE
        : CLIMATE_TILE_VISIBLE;
  const shown = rows.slice(0, visible);
  const rest = rows.slice(visible);
  // S (§3.3): warmest/coldest room as ONE sentence, no list at all. Punctuation
  // only below (no words) — no i18n key exists for this shape yet, flagged in
  // RESULT.md; room labels are user data and were never translated anyway.
  const extremes = cs === 'S' ? climateExtremes(rows) : null;
  return (
    <HomeTileCard {...stageProps} step={cs} name={t.name} live={!unreachable}>
      {unreachable ? (
        since !== null ? (
          <p className="idle__hometilestale">
            {idleFace.homeTiles.unavailableSince(
              formatRelativeAge(since, nowMs, idleFace.homeTiles.age),
            )}
          </p>
        ) : (
          <p className="idle__hometileunavailable">{t.unreachable}</p>
        )
      ) : cs === 'S' ? (
        extremes === null ? (
          <p className="idle__hometileunavailable">{t.unreachable}</p>
        ) : (
          <p className="idle__hometileline">
            {extremes.warmest.areaId === extremes.coldest.areaId
              ? `${extremes.warmest.label} ${fmtTemp(extremes.warmest.currentTemperature, locale)}`
              : `${extremes.warmest.label} ${fmtTemp(extremes.warmest.currentTemperature, locale)} · ${extremes.coldest.label} ${fmtTemp(extremes.coldest.currentTemperature, locale)}`}
          </p>
        )
      ) : (
        <>
          {/* XL (§3.3): die Raumzeilen zweispaltig. Die Faltung darunter
              bleibt einspaltig — sie ist ein Nachschlag, keine zweite Liste. */}
          <ul
            className={`idle__cardlist idle__cardlist--climate${cs === 'XL' ? ' idle__cardlist--two' : ''}`}
          >
            {shown.map((row) => (
              <ClimateRow row={row} locale={locale} nowMs={nowMs} key={row.areaId} />
            ))}
          </ul>
          {rest.length > 0 && (
            <details className="idle__hometilerest">
              <summary className="idle__hometilerestsummary">{t.restSummary(rest.length)}</summary>
              <ul className="idle__cardlist idle__cardlist--climate">
                {rest.map((row) => (
                  <ClimateRow row={row} locale={locale} nowMs={nowMs} key={row.areaId} />
                ))}
              </ul>
            </details>
          )}
        </>
      )}
    </HomeTileCard>
  );
}
