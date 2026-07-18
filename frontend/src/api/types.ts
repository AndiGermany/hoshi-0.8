// Wire-Vertrag mit dem 0.8-Backend (de.hoshi.core.dto.ChatEvent / ChatRequest).
// Diskriminator-Feld ist `event` (@JsonTypeInfo property = "event").

// 'auto' = bilinguale Auto-Erkennung (DE/EN) pro Eingabe. Es ist KEIN Backend-
// Enum-Wert: das FE schickt es als `languagePolicy` (AUTO/DE/EN); das konkrete
// `language`-Feld bleibt DE/EN (Fallback) — siehe api/chat.ts & api/voice.ts.
export type Language = 'auto' | 'de' | 'en';

/** Sprecher-Kontext, damit das Backend Entity-/Episodic-Memory pro Sprecher führt. */
export interface SpeakerContext {
  speakerId: string;
}

/**
 * FE-Sicht auf das additive `speaker`-SSE-Event (Stimm-ERKENNUNG S3, wer sprach) —
 * gespiegelt vom BE-Contract `ChatEvent.Speaker`. Nur auf dem Voice-Pfad und nur
 * bei `HOSHI_SPEAKER_RECOGNITION_ENABLED=true`; alte Clients sehen es nie.
 *
 * Vera-Regel: `name === null` ODER `isGuest === true` ⇒ **Gast** (unter der
 * Konfidenz-Schwelle wird NIE eine Person zugeordnet). `confidence` ist der beste
 * Cosine-Score (0..1) — rein informativ (Tooltip), nie die Bindung.
 */
export interface RecognizedSpeaker {
  name: string | null;
  confidence: number;
  isGuest: boolean;
}

/**
 * Eine abgeschlossene Turn-Nachricht für das Gesprächsgedächtnis — Spiegel der
 * BE-DTO `de.hoshi.core.domain.ChatMessage(role, content)`. `role` ist EXAKT
 * „user"/„assistant" (das Backend fenstert die Liste per HOSHI_MEMORY_WINDOW_TURNS).
 */
export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
}

export interface ChatRequest {
  text: string;
  chatId?: string | null;
  /** false = reiner Text-Turn (kein TTS). Das schlanke FE spielt kein Audio. */
  speak?: boolean;
  provider?: string | null;
  model?: string | null;
  language?: Language;
  speakerContext?: SpeakerContext;
  /**
   * Bisherige abgeschlossene Turns (ältester zuerst) fürs Gesprächsgedächtnis.
   * Optional, Default leer → backward-compatible (Alt-Body byte-identisch, wenn
   * kein Verlauf mitgeschickt wird). Der TurnOrchestrator fenstert nochmal.
   */
  history?: ChatMessage[];
}

/**
 * Eine strukturierte Quellen-Referenz (Spiegel von `de.hoshi.core.port.EscalationSourceRef`,
 * Quellen-Struktur-Auftrag 2026-07-21): `title` ist oft nicht gesetzt, dann zeigt
 * das FE den Host aus `url` (s. ChatView `sourceLabel`).
 */
export interface EscalationSourceRef {
  title?: string | null;
  url: string;
}

export type ChatEvent =
  // `grounded` ist ein ADDITIVES Wire-Feld (BE serialisiert ChatEvent.Start
  // vollständig, Default false): trug dieser Turn gedecktes Grounding im Sinne
  // des FactCoverageGate? Speist den „Wissen gedeckt"-Chip der Turn-Anatomie
  // (§4) — nur ein echtes `true` zählt, fehlend/undefined heißt ehrlich „nein".
  | {
      event: 'start';
      provider: string;
      category: string;
      model: string;
      personaEmotion?: string;
      grounded?: boolean;
    }
  | { event: 'delta'; text: string; provider?: string }
  | { event: 'audio'; data: string; seq: number }
  | { event: 'tts_audio_start'; provider: string; estimatedMs?: number | null }
  | { event: 'tts_audio_end'; actualMs: number }
  | { event: 'step'; kind: string; message: string }
  // Stimm-ERKENNUNG (S3): additiv, dem `transcript`-Step VORANGESTELLT. sse.ts
  // reicht es schon durch (prüft nur `typeof .event === 'string'`); nur der
  // Voice-Pfad wertet es aus (wer sprach → Chip + dynamischer speakerId).
  | { event: 'speaker'; recognizedSpeaker: string | null; confidence: number; isGuest: boolean }
  // `escalationSources` ist ein ADDITIVES Wire-Feld (BE serialisiert ChatEvent.Done
  // vollständig, Default kein Feld — s. `@JsonInclude(NON_NULL)`): NUR bei echten
  // `url_citation`-Treffern eines Recherche-Turns gesetzt (Quellen-Struktur-Auftrag
  // 2026-07-21). Der Antwort-Text trägt seither NIE mehr eine angehängte Quellen-/
  // URL-Zeile — die Attribution reist AUSSCHLIESSLICH hier, fürs „i"-Icon in ChatView.
  // Fehlend/undefined ⇒ kein Icon (kein Beleg, kein Beleg-Icon).
  | {
      event: 'done';
      provider?: string;
      totalSentences?: number;
      ttsHandled?: boolean;
      escalationSources?: EscalationSourceRef[];
    }
  | { event: 'error'; message: string; stage?: string };

export type ChatEventName = ChatEvent['event'];

// ── Skills (S2.3) — Wire-Vertrag von de.hoshi.web.SkillStateView ──────────────

/** Skill-Tier: LOCAL bleibt on-device, EGRESS ruft online (→ „geht online"-Badge). */
export type SkillTier = 'LOCAL' | 'EGRESS';

/**
 * Eine Skill-Zeile aus `GET /api/v1/settings/skills` (Spiegel von
 * `de.hoshi.web.SkillStateView`). Zwei-Stufen-Wahrheit:
 *  - `ceilingOpen` — ist die Deploy-Zeit-Decke (ENV) offen?
 *  - `enabled`     — der Laufzeit-Store-Wert (Andis Toggle).
 *  - `effective`   — `ceilingOpen && enabled`: was der Classifier wirklich sieht.
 *  - `locked`      — `!ceilingOpen`: der Toggle greift nicht (beim Deploy deaktiviert).
 */
export interface Skill {
  id: string;
  labelDe: string;
  labelEn: string;
  tier: SkillTier;
  ceilingOpen: boolean;
  enabled: boolean;
  effective: boolean;
  locked: boolean;
}
