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
 */
export function providerChipText(provider: string): string {
  if (provider === 'LOCAL') return 'lokal';
  return `${PROVIDER_LABEL[provider] ?? provider} · ging online`;
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
  const route = anatomy.route;
  if (!route) return null;
  const cloud = route.provider !== 'LOCAL';
  return (
    <div className="turnchips">
      <span
        className="turnchip"
        title={
          cloud
            ? 'Diese Antwort kam über einen Cloud-Provider'
            : 'Diese Antwort blieb auf dem Gerät'
        }
      >
        {cloud ? (
          <CloudGlyph className="turnchip__ico" />
        ) : (
          <LockGlyph className="turnchip__ico" />
        )}
        {providerChipText(route.provider)}
      </span>
      {route.grounded && (
        <span
          className="turnchip"
          title="Die Antwort war durch geladenes Wissen gedeckt (FactCoverage-Gate)"
        >
          Wissen gedeckt
        </span>
      )}
    </div>
  );
}
