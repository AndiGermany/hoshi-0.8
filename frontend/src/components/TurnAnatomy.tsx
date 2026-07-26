import type { ChatEvent, RecognizedSpeaker } from '../api/types';
import { CloudGlyph, LockGlyph } from './icons';
import { useUiStrings } from '../i18n';
import { de } from '../i18n/de';
import type { TurnAnatomyStrings } from '../i18n/types';

/**
 * **§4 Turn-Anatomie** (Cowork-Aoi-Spec 20260702-2201): die Denk-Stufen-Zeile
 * mit ECHTEN Häkchen über der Antwort + die Chips unter der Antwort. Bubble-los
 * bleibt — beides sind stille Text-Zeilen im Aoi-Token-Set, keine neuen Boxen.
 *
 * Ehrlichkeits-Gesetz (dasselbe wie bei der Welle: nichts leuchtet, was nichts
 * misst): jede Stufe und jeder Chip hängt an einem ECHTEN Wire-Event dieses
 * Turns — die Zeile ist ein append-only-Protokoll dessen, was wirklich passiert
 * ist. Es gibt KEINE vorgerenderten „pending"-Stufen (das wäre ein Versprechen,
 * z. B. „spricht", das ein Text-Turn nie einlöst) und KEINE erfundenen Stufen.
 *
 * Was die Spec §4 nennt, der Server aber (noch) nicht liefert, ist bewusst
 * AUSGELASSEN statt simuliert:
 *  - Entity-Korrektur der STT-Kaskade („Brejcha erkannt — ‚brescha' korrigiert")
 *    — kein Wire-Event; die echte Kaskade heute: speaker → transcript.
 *  - Quellen-NAME (`Spotify`/`Wikipedia`) — der Turn trägt nur provider/
 *    category/grounded, keinen Quellennamen.
 *  - Ziel+Volume (`Küche · 40%`) — kein per-Turn-Geräteziel auf dem Draht.
 *  - Nachhör-Fenster (`hört noch 6s zu`) — 0.8 ist Push-to-Talk, es GIBT kein
 *    Nachhör-Fenster; eine solche Zeile wäre gelogen.
 *
 * Alles hier ist pur (Reducer + Ableitung, kein Netz/DOM-Zwang) → headless
 * testbar; die zwei kleinen Komponenten rendern nur die abgeleiteten Items.
 */

/** Der Weg dieses Turns — 1:1 aus dem `start`-Event (RouteDecision am Draht). */
export interface TurnRoute {
  provider: string;
  model: string;
  category: string;
  /** `start.grounded` (FactCoverageGate): Antwort durch Grounding gedeckt. */
  grounded: boolean;
  /**
   * `start.escalated` (Andi-Befund 2026-07-26, „lügender lokal-Chip"): lief
   * dieser Turn über den bezahlten Extended-Think-S2-Eskalationspfad
   * (`TurnOrchestrator.escalationTurn`)? Dieser Pfad behält `provider="LOCAL"`
   * (Routing-Sicht) — NUR `escalated` trägt die WAHRE Herkunft. Optional/
   * fehlend (Alt-Turns, Fastpath/Tool-Starts) ⇒ ehrlich `false`.
   */
  escalated?: boolean;
  /**
   * `start.escalationProvider` (Muster [escalated]): das Eskalations-Modell-
   * Label (z. B. `"openai-nano"`) — NUR gesetzt bei `escalated===true`.
   */
  escalationProvider?: string;
}

/**
 * Der pro-Turn-Anatomie-Zustand: welche echten Events dieser Turn schon
 * gesehen hat. Lebt am Assistant-Turn (ChatView) und wird ausschließlich
 * über {@link anatomyOnEvent} fortgeschrieben.
 */
export interface TurnAnatomyState {
  /** Sprach- oder Tipp-Turn — steuert, ob die STT-Kaskaden-Stufen existieren. */
  kind: 'text' | 'voice';
  /** Voice: die Aufnahme wurde angenommen und hochgeladen (Turn-Erzeugung). */
  heard: boolean;
  /** `speaker`-Event (S3): wer sprach — Gast bleibt ehrlich Gast (Vera-Regel). */
  speaker: RecognizedSpeaker | null;
  /** `step kind=transcript`: die Aufnahme wurde verstanden. */
  understood: boolean;
  /** `start`: der Router hat den Weg gewählt. */
  route: TurnRoute | null;
  /** Erstes `delta`: die Antwort läuft. */
  answering: boolean;
  /** `tts_audio_start`: Hoshi spricht die Antwort. */
  speaking: boolean;
  /** `error.stage`: WO die Kaskade riss (STT/LLM/SIDECAR/TTS) — sichtbar, ehrlich. */
  errorStage: string | null;
}

/** Frische Anatomie bei Turn-Erzeugung. Voice: „gehört" ist ab Upload wahr. */
export function emptyAnatomy(kind: 'text' | 'voice'): TurnAnatomyState {
  return {
    kind,
    heard: kind === 'voice',
    speaker: null,
    understood: false,
    route: null,
    answering: false,
    speaking: false,
    errorStage: null,
  };
}

/**
 * Pure Reducer: faltet ein Wire-Event in die Turn-Anatomie. Events ohne
 * Anatomie-Wirkung (audio/done/tts_audio_end/fremde steps sowie ein zweites
 * delta) geben die SELBE Referenz zurück — Aufrufer können daran billig
 * erkennen, dass nichts zu patchen ist.
 */
export function anatomyOnEvent(prev: TurnAnatomyState, ev: ChatEvent): TurnAnatomyState {
  switch (ev.event) {
    case 'speaker':
      return {
        ...prev,
        speaker: { name: ev.recognizedSpeaker, confidence: ev.confidence, isGuest: ev.isGuest },
      };
    case 'step':
      return ev.kind === 'transcript' && !prev.understood ? { ...prev, understood: true } : prev;
    case 'start':
      return {
        ...prev,
        route: {
          provider: ev.provider,
          model: ev.model,
          category: ev.category,
          // Additives Wire-Feld: nur ein ECHTES true zählt (fehlt bei Alt-Events).
          grounded: ev.grounded === true,
          // Additive Wire-Felder (Andi-Befund 2026-07-26): die WAHRE Herkunft
          // eines eingelösten Eskalations-Turns — s. TurnRoute-KDoc.
          escalated: ev.escalated === true,
          escalationProvider: ev.escalationProvider ?? '',
        },
      };
    case 'delta':
      return prev.answering ? prev : { ...prev, answering: true };
    case 'tts_audio_start':
      return prev.speaking ? prev : { ...prev, speaking: true };
    case 'error':
      return { ...prev, errorStage: ev.stage ?? 'LLM' };
    default:
      return prev;
  }
}

/** Eine Stufe der Denk-Stufen-Zeile — immer schon PASSIERT (✓) oder GERISSEN (✕). */
export interface TurnStage {
  key: 'heard' | 'speaker' | 'understood' | 'route' | 'answering' | 'speaking' | 'error';
  label: string;
  /** true = hier riss die Kaskade (✕ statt ✓, Fehlerton). */
  failed?: boolean;
  /** Detail als title-Tooltip (z. B. provider · model · category am „Weg gewählt"). */
  title?: string;
}

/**
 * Leitet die sichtbaren Stufen aus der Anatomie ab — append-only in
 * Pipeline-Reihenfolge (so treffen die Events auch ein). Jede gerenderte
 * Stufe IST passiert; nichts wird vorab versprochen.
 */
export function turnStages(a: TurnAnatomyState, t: TurnAnatomyStrings = de.turnAnatomy): TurnStage[] {
  const items: TurnStage[] = [];
  if (a.kind === 'voice' && a.heard) items.push({ key: 'heard', label: t.heard });
  if (a.speaker) {
    // Vera-Regel sichtbar: unter der Schwelle NIE ein geratener Name.
    const wer = !a.speaker.isGuest && a.speaker.name ? a.speaker.name : t.guest;
    items.push({ key: 'speaker', label: t.recognized(wer) });
  }
  if (a.understood) items.push({ key: 'understood', label: t.understood });
  if (a.route) {
    items.push({
      key: 'route',
      label: t.route,
      title: `${a.route.provider} · ${a.route.model} · ${a.route.category}`,
    });
  }
  if (a.answering) items.push({ key: 'answering', label: t.answering });
  if (a.speaking) items.push({ key: 'speaking', label: t.speaking });
  if (a.errorStage) items.push({ key: 'error', label: a.errorStage, failed: true });
  return items;
}

/** Anzeigename der Cloud-Provider (Wire-Enum → warmes Label); unbekannt = as-is. */
const PROVIDER_LABEL: Record<string, string> = {
  OPENAI: 'OpenAI',
  ANTHROPIC: 'Anthropic',
  HEDGE: 'Hedge',
  OPENCLAW: 'OpenClaw',
};

/**
 * Text des Quelle/Egress-Chips: LOCAL blieb auf dem Gerät („lokal"), alles
 * andere IST ein Cloud-Provider → Name + „ging online" (ehrlich, Tom-Regel).
 *
 * `t` ist injizierbar (Default: der DE-Katalog, s. {@link turnStages}) — Andi-
 * Sweep 24.07 (README-Screenshot-Befund): „lokal" blieb hartkodiert Deutsch,
 * egal welche UI-Sprache aktiv war. Tests, die ohne zweites Argument aufrufen,
 * sehen unverändert Deutsch (byte-gleich); {@link TurnChips} reicht den Katalog
 * der AKTIVEN UI-Sprache durch.
 */
export function providerChipText(
  provider: string,
  t: TurnAnatomyStrings = de.turnAnatomy,
): string {
  if (provider === 'LOCAL') return t.local;
  return `${PROVIDER_LABEL[provider] ?? provider}${t.cloudSuffix}`;
}

/**
 * Markenname aus einem `escalationProvider`-Wire-String (z. B. `"openai-nano"`,
 * `"openai-sol"` — BE-Konvention `EscalationModelCatalog.providerLabel`:
 * IMMER `"<marke>-<modellname>"`). Best-effort Präfix-Match auf
 * {@link PROVIDER_LABEL}; ein unbekanntes Präfix bleibt der rohe String (nie
 * stillschweigend „Cloud" — Tom-Regel).
 */
function escalationBrandLabel(escalationProvider: string): string {
  const prefix = escalationProvider.split('-')[0]?.toUpperCase();
  return (prefix && PROVIDER_LABEL[prefix]) || escalationProvider;
}

/**
 * Text des Quelle/Egress-Chips für eine VOLLE Turn-Route (Andi-Befund
 * 2026-07-26, „lügender lokal-Chip", Live-Repro: eine per „ja" eingelöste
 * Extended-Think-S2-Eskalation zeigte den „lokal"-Chip, obwohl die Antwort
 * über eine bezahlte Cloud-Anfrage kam). Der BE-Eskalationspfad
 * (`TurnOrchestrator.escalationTurn`) behält `provider="LOCAL"` — das ist die
 * ROUTING-Sicht (FACT_SHORT wird immer erst lokal geroutet), NICHT die
 * Herkunft der Antwort. Die WAHRE Herkunft trägt `route.escalated` +
 * `route.escalationProvider`, seit 2026-07-05 Teil des Wire-Vertrags
 * ([ChatEvent.Start.escalated]/`.escalationProvider`) — bis zu diesem Fix las
 * das FE beide Felder nie, darum zeigte der Chip bei JEDER Eskalation
 * fälschlich „lokal".
 *
 * `escalated!==true` (Bestandspfad, byte-identisch) ⇒ fällt zurück auf
 * {@link providerChipText}(route.provider) — unverändertes Verhalten für
 * jeden Turn ohne Eskalation.
 */
export function originChipText(
  route: TurnRoute,
  t: TurnAnatomyStrings = de.turnAnatomy,
): string {
  if (route.escalated === true) {
    return `${escalationBrandLabel(route.escalationProvider || route.provider)}${t.cloudSuffix}`;
  }
  return providerChipText(route.provider, t);
}

/**
 * Die Denk-Stufen-Zeile ÜBER der Antwort: echte Häkchen, still (11.5px,
 * hint-Farbe), wächst live mit den Events. Kein Container, solange noch
 * keine Stufe passiert ist (Text-Turn vor `start`).
 */
export function TurnStagesRow({ anatomy }: { anatomy: TurnAnatomyState }) {
  const { turnAnatomy } = useUiStrings();
  const stages = turnStages(anatomy, turnAnatomy);
  if (stages.length === 0) return null;
  return (
    <ol className="turnstages" aria-label={turnAnatomy.rowLabel}>
      {stages.map((s) => (
        <li
          key={s.key}
          className={`turnstage${s.failed ? ' turnstage--failed' : ''}`}
          title={s.title}
        >
          {/* ✓/✕ sind typografische Glyphs (Emoji-Sweep-Whitelist), keine Emojis. */}
          <span className="turnstage__mark" aria-hidden="true">
            {s.failed ? '✕' : '✓'}
          </span>
          {s.label}
        </li>
      ))}
    </ol>
  );
}

/**
 * Die Chips UNTER der fertigen Antwort (§4): Quelle/Egress als SVG-Glyph +
 * Text (Wolke = ging online, Schloss = blieb lokal) und — nur wenn wirklich
 * gemessen — der Grounding-Chip. Ohne `start`-Event (kein Weg bekannt) rendert
 * nichts; erfundene Chips (Ziel/Volume, Nachhör-Fenster) gibt es nicht.
 */
export function TurnChips({ anatomy }: { anatomy: TurnAnatomyState }) {
  const { turnAnatomy } = useUiStrings();
  const route = anatomy.route;
  if (!route) return null;
  // Andi-Befund 2026-07-26: `escalated===true` IST online, auch wenn
  // `provider` (die Routing-Sicht) "LOCAL" bleibt — s. originChipText-KDoc.
  const cloud = route.escalated === true || route.provider !== 'LOCAL';
  return (
    <div className="turnchips">
      <span
        className="turnchip"
        title={cloud ? turnAnatomy.cloudTitle : turnAnatomy.localTitle}
      >
        {cloud ? (
          <CloudGlyph className="turnchip__ico" />
        ) : (
          <LockGlyph className="turnchip__ico" />
        )}
        {originChipText(route, turnAnatomy)}
      </span>
      {route.grounded && (
        <span className="turnchip" title={turnAnatomy.groundedTitle}>
          {turnAnatomy.grounded}
        </span>
      )}
    </div>
  );
}
