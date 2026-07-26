package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.Language
import de.hoshi.core.dto.Persona
import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.dto.RouteDecision
import de.hoshi.core.dto.RouteProvider
import de.hoshi.core.dto.SpeakerContext
import de.hoshi.core.dto.TurnPrompt
import reactor.core.publisher.Mono

/**
 * Entity-Memory-Naht: liefert den (optionalen) Gedächtnis-Kontext-Block für einen
 * Sprecher. Die Memory-Infra (Embeddings/Store) existiert noch nicht in 0.8 →
 * schmaler Port, damit die Assembly-Policy ohne Infra testbar bleibt.
 *
 * **Turn-Sprache ist PFLICHT-Parameter (Sprach-Naht-Scheibe 2026-07-25, Muster
 * [GroundingPort]):** der Block geht WÖRTLICH in den System-Prompt und trägt neben
 * den (nie übersetzten) Fakt-Zeilen einen RAHMEN samt Handlungs-Anweisung — ein
 * deutscher Rahmen zieht die Antwort selbst bei englischer Persona nach Deutsch.
 * Kein Default-Wert: wer den Port implementiert, MUSS die Sprache annehmen (und
 * darf sie sichtbar ignorieren, wenn sein Block sprachneutral ist) — ein
 * Default-Argument hätte genau die stillen Sprach-Verluste erlaubt, die den
 * [GroundingPort] eine Runde gekostet haben.
 */
fun interface EntityContextPort {
    /** `null` = kein Block. */
    fun contextBlock(speakerId: String, language: Language): String?
}

/**
 * Store-Naht des Entity-Gedächtnisses (Multi-User-Memory, 0.8): NACH der Antwort
 * eines Turns wird hier — OHNE zusätzlichen Brain-Call, rein deterministisch —
 * extrahiert+persistiert, was über den Sprecher gemerkenswert ist. Bewusst
 * getrennt vom [EntityContextPort] (Recall lesend), damit der Lese-Pfad
 * (Prompt-Assembly) keine Schreib-Abhängigkeit trägt.
 *
 * Default-OFF (Privacy): das verdrahtete [NOOP] tut nichts. Erst bei
 * `HOSHI_MEMORY_ENABLED=true` wird der echte Adapter (sqlite, speakerId-keyed)
 * gebunden.
 */
fun interface EntityMemoryWriter {
    /** Deterministische Extraktion aus [turnText] (+ optional [answer]) → Persistenz je [speakerId]. */
    fun remember(speakerId: String, turnText: String, answer: String)

    companion object {
        /** Verhaltens-neutraler Default (Memory OFF) — speichert NIE. */
        val NOOP: EntityMemoryWriter = EntityMemoryWriter { _, _, _ -> }
    }
}

/**
 * Wiki-Grounding-Naht: liefert für eine Wissensfrage einen Fakten-Block (leer =
 * kein Treffer). Infra (articles.db/Bridge) bleibt im Adapter.
 *
 * **Turn-Sprache ist PFLICHT-Parameter (Multilingual-Welle, 2026-07-24 →
 * entdoppelt 2026-07-25):** [groundingBlock] trägt die SPRACHE DES TURNS — der
 * Grounding-Block (z.B. der Wetter-Block aus
 * [de.hoshi.adapters.knowledge.WeatherGroundingProvider]) geht WÖRTLICH in den
 * Brain-Prompt und muss deshalb der Turn-Sprache folgen, NICHT einer separaten
 * Anzeigesprache: ein deutscher Datenblock + eine deutsche ANWEISUNG holen die
 * Antwort selbst bei englischer Persona zuverlässig nach Deutsch zurück.
 *
 * **Warum GENAU EIN Member (und kein `fun interface` mehr):** die erste Fassung
 * rüstete die Sprache über ein ZWEITES, nicht-abstraktes Overload nach (Kotlin
 * verbietet Default-Werte am einen abstrakten Member eines `fun interface`).
 * Folge: drei von vier Implementierungen überschrieben nur die 2-Arg-Variante
 * und verloren die Sprache STILL — ohne Compiler- oder Test-Warnung. Seit
 * 2026-07-25 gibt es deshalb nur noch DIESE eine Signatur: wer den Port
 * implementiert, MUSS die Sprache annehmen (und darf sie bewusst ignorieren,
 * wenn seine Quelle sprachneutral ist). Der Preis — Lambdas werden zu
 * `object : GroundingPort { … }` — ist die Compiler-Garantie wert.
 *
 * Sprach-agnostische Implementierungen (z.B. der Leer-Port [EMPTY]) nehmen
 * [language] entgegen und ignorieren ihn sichtbar; sprachbewusste Scheiben
 * (Wetter) bauen ihren Text daraus.
 */
interface GroundingPort {
    fun groundingBlock(query: String, category: RouteCategory, language: Language): Mono<String>

    /**
     * Enger, ausschließlich lokaler Wissens-Lookup. Der Port ist bewusst
     * **default-deny**: bestehende Grounding-Implementierungen dürfen nicht
     * versehentlich Wetter, Cloud-Antworten oder cloudstämmige Caches in einen
     * als lokal behaupteten Wissenspfad einspeisen. Nur Adapter mit belegbar
     * lokaler Quelle überschreiben diese Methode explizit.
     */
    fun localKnowledgeBlock(query: String, category: RouteCategory, language: Language): Mono<String> =
        Mono.just("")

    companion object {
        /**
         * Port mit KONSTANTEM Block — sprach-unabhängig, weil an einem festen
         * String nichts zu übersetzen ist. Ersetzt die früheren
         * `GroundingPort { _, _ -> Mono.just(x) }`-Lambdas (Stub-Wiring,
         * Test-Fakes), ohne dass jemand die Sprach-Signatur umgehen kann.
         */
        fun fixed(block: String): GroundingPort = object : GroundingPort {
            override fun groundingBlock(query: String, category: RouteCategory, language: Language): Mono<String> =
                Mono.just(block)
        }

        /**
         * Verhaltens-neutraler Leer-Port (nie ein Block) — der frühere
         * `GroundingPort { _, _ -> Mono.just("") }`-Lambda-Ausdruck, jetzt als
         * EINE benannte Wahrheit (Muster [EntityMemoryWriter.NOOP]).
         */
        val EMPTY: GroundingPort = fixed("")
    }
}

/**
 * Episodic-Recall-Naht: persönlicher Kontext aus früheren Turns (leer = keiner).
 * Optional (Default OFF) → der Assembler trägt diesen Port nullable.
 *
 * **Schlüssel-Konsistenz:** gekeyt nach [speakerId] (NICHT chatId) — Store
 * ([de.hoshi.core.port.EpisodicWriter]) UND Recall müssen denselben Schlüssel
 * treffen, konsistent mit dem Entity-Gedächtnis + der Multi-User-Vision.
 *
 * **Turn-Sprache ist PFLICHT-Parameter** — dieselbe Begründung wie beim
 * [EntityContextPort]: der Recall-Block („[Früher gesagt: …]") rahmt WÖRTLICHE
 * frühere Nutzer-Texte; der RAHMEN folgt der Turn-Sprache, der Inhalt nie.
 */
fun interface EpisodicRecallPort {
    fun recallBlock(speakerId: String, text: String, language: Language): Mono<String>
}

/**
 * Per-Turn-Prompt-Assembly (PORT-Einheit aus dem Hoshi-0.5 brain-streaming-Ledger,
 * dort `TurnPromptAssembler`): die reihenfolge-kritische String-Komposition
 * persona → entity → follow → episodic → grounding mit der parallelen
 * `Mono.zip(grounding, episodic)`-Stufe.
 *
 * Entkoppelt von Spring + Infra: statt `HoshiProperties` + den konkreten
 * Memory-/Grounding-Services nimmt der Konstruktor schmale Ports
 * ([EntityContextPort]/[GroundingPort]/[EpisodicRecallPort]) und direkte Flags
 * ([availableRooms]/[wikiGroundingEnabled]) entgegen. Reines Kotlin, kein `@Service`.
 *
 * **Behavior-preserving:** die fünf Kompositionsschritte, die `\n\n`-Trenner, die
 * `isNotEmpty`-Guards, das Provider-/Flag-Gating (Grounding+Episodic nur LOCAL;
 * Grounding zusätzlich nur bei [wikiGroundingEnabled]) und die feste Reihenfolge
 * (Episodic VOR Grounding) sind 1:1 aus 0.5.
 *
 * **P1-Privacy-Ergänzung:** beide Recall-Nähte (Entity in [baseSystemPrompt], Episodic in
 * [assemble]) laufen durch [SpeakerTrust.resolve], BEVOR die `speakerId` an
 * [EntityContextPort]/[EpisodicRecallPort] geht — siehe [SpeakerTrust]-KDoc für das
 * Bedrohungsmodell (client-behauptete Identität) und die Design-Entscheidungen.
 */
class TurnPromptAssembler(
    private val persona: PersonaService,
    private val entityMemory: EntityContextPort,
    private val grounding: GroundingPort,
    private val episodicMemory: EpisodicRecallPort?,
    /**
     * **Ambient/Mood-Wärme-Naht (0.8) — flag-gated, default OFF.** [AmbientWarmthPort.NONE]
     * (Default) gibt nie einen Hinweis ⇒ [baseSystemPrompt] ist byte-identisch zum
     * bisherigen Verhalten. Erst bei `HOSHI_AMBIENT_ENABLED=true` schichtet der
     * clock-gebundene Adapter einen kleinen Wärme-Hinweis ans ENDE (nach Persona+Entity).
     */
    private val ambient: AmbientWarmthPort = AmbientWarmthPort.NONE,
    private val availableRooms: List<String> = emptyList(),
    private val wikiGroundingEnabled: Boolean = true,
    /**
     * Steuer-Sentinel: ein Grounding-Block, der gleich diesem Wert ist, ist KEIN
     * Kontext (Bridge tot) und wird nie ins Prompt geschichtet — bleibt aber im
     * [AssembledPrompt.groundBlock], damit der Aufrufer ihn am Konsum-Punkt erkennt.
     */
    private val bridgeDownSentinel: String = BRIDGE_DOWN_SENTINEL,
    /**
     * **Server-side Working-Memory-Window** (Defense-in-Depth, portiert aus Hoshi 0.5
     * `ChatMemoryService`: dort `windowTurns * 2` mit `removeFirst()`-Trim auf der
     * ArrayDeque). Obergrenze auf die an den Brain gereichte Konversations-History,
     * gemessen in TURNS (1 Turn = 1 User + 1 Assistant = 2 Nachrichten). Schützt
     * gegen einen Client, der den vollen Verlauf schickt und so den 16-GB-Brain-KV
     * aufbläht.
     *
     * **Default 0 = KEIN Cap (byte-neutral):** nur ein POSITIVER Wert kappt; bei
     * `<= 0` fließt die History unverändert durch — exakt das heutige Verhalten.
     * Flag `HOSHI_MEMORY_WINDOW_TURNS` (Wiring in PipelineConfig).
     */
    private val historyWindowTurns: Int = 0,
    /**
     * Zeitquelle der Grounding-Messung ([AssembledPrompt.groundingMs]) —
     * injizierbar für deterministische Tests (Fake-Clock), Default die echte
     * Nano-Uhr. Rein additiv: kein bestehender Aufrufer ändert sich.
     */
    private val nanoTime: () -> Long = System::nanoTime,
    /**
     * **Sprecher-Vertrauens-Gate (P1-Privacy) — flag-gated, default OFF ⇒ byte-neutral.**
     * Siehe [SpeakerTrust] für das Bedrohungsmodell + die Design-Entscheidungen. Bei `false`
     * (Default) bleiben [baseSystemPrompt] (Entity-Recall) UND [assemble] (Episodic-Recall)
     * EXAKT beim heutigen Verhalten: die behauptete `speakerId` wird ungeprüft verwendet. Bei
     * `true` entscheidet [SpeakerTrust.resolve] anhand von `SpeakerContext.score` >=
     * [speakerTrustThreshold], ob der Claim vertraut wird oder auf den Gast kollabiert (kein
     * Cross-User-Recall). Wird VOM SELBEN Flag (`HOSHI_SPEAKER_TRUST_ENFORCED`) gespeist wie
     * das Write-Gate in `de.hoshi.web.ChatStreamController.rememberAfter` — EINE Entscheidung,
     * zwei Nähte.
     */
    private val speakerTrustEnforced: Boolean = false,
    /**
     * Score-Schwelle des Gates (nur wirksam bei [speakerTrustEnforced]); Default = derselbe
     * `known`-Tau wie die Stimm-Erkennung (`hoshi.speaker.recognition.threshold`, siehe
     * [SpeakerTrust]-KDoc).
     */
    private val speakerTrustThreshold: Double = 0.80,
) {

    /**
     * Ergebnis der Prompt-Assembly.
     *  - [finalPrompt]: das fertig geschichtete System-Prompt für die Generierung.
     *  - [groundBlock]: der reine Wiki-Grounding-Block (leer = kein Treffer). Wird
     *    vom Aufrufer NACH dem Stream weitergereicht (Topic-Caching/Häppchen).
     *  - [groundingMs]: Dauer des ECHTEN [GroundingPort]-Calls (Perf-Diary,
     *    additiv). `null` = es gab keinen Call (Nicht-LOCAL / Grounding OFF) —
     *    nie ein erfundenes 0.
     */
    data class AssembledPrompt(
        val finalPrompt: String,
        val groundBlock: String,
        val groundingMs: Long? = null,
    )

    /**
     * Synchroner Teil: Basis-System-Prompt (Persona) + optionaler Entity-Memory-
     * Block. Separat exponiert, weil er VOR dem reaktiven Teil läuft.
     *
     * [language] (Multilingual-Sprachsteuerung): wird an die Persona durchgereicht,
     * damit der System-Prompt die Antwortsprache EXPLIZIT instruiert (EN/DE).
     * Default [Language.DEFAULT] → DE-Verhalten unverändert.
     *
     * [persona] (Charakter-Steuerung): wählt den byte-stabilen Persona-Body
     * (Standard/Kumpel/Knapp/Ruhig). Default [Persona.STANDARD] → byte-identisch
     * zum heutigen Verhalten. (`this.persona` = der PersonaService; das Argument
     * `persona` = die gewählte [Persona] des Turns.)
     */
    fun baseSystemPrompt(
        speaker: SpeakerContext,
        language: Language = Language.DEFAULT,
        persona: Persona = Persona.STANDARD,
    ): String {
        val baseSystemPrompt = this.persona.systemPrompt(
            persona = persona,
            displayName = speaker.displayName,
            availableRooms = availableRooms,
            language = language,
        )
        // Vertrauens-Gate (P1): OFF ⇒ exakt speaker.speakerId (byte-neutral, der `?:`-Fallback
        // greift hier faktisch nie, weil `speaker` an dieser Stelle nie null ist); ON ⇒
        // SpeakerTrust entscheidet Claim-vs-Gast anhand des Scores (siehe SpeakerTrust-KDoc).
        val trustedSpeakerId =
            SpeakerTrust.resolve(speaker, speakerTrustEnforced, speakerTrustThreshold)?.speakerId ?: speaker.speakerId
        // Turn-Sprache durchreichen (Sprach-Naht 2026-07-25): der Gedächtnis-RAHMEN
        // folgt der Sprache des Turns, die gespeicherten Fakten selbst nie.
        val entityCtx = entityMemory.contextBlock(trustedSpeakerId, language)
        val withEntity = if (entityCtx != null) "$baseSystemPrompt\n\n$entityCtx" else baseSystemPrompt
        // Ambient-Wärme-Hinweis ans ENDE (flag-gated): OFF ⇒ NONE ⇒ null ⇒ byte-neutral.
        val ambientHint = ambient.warmthHint(language)
        return if (!ambientHint.isNullOrBlank()) "$withEntity\n\n$ambientHint" else withEntity
    }

    /**
     * Kappt die an den Brain gereichte [history] auf die letzten
     * [historyWindowTurns]*2 Nachrichten (N User + N Assistant — siehe Feld-Doku).
     *
     * Reine, deterministische Logik (kein I/O, kein Reactor):
     *  - `historyWindowTurns <= 0` ⇒ **unverändert** zurück (Default 0 = kein Cap,
     *    byte-neutral, exakt heutiges Verhalten).
     *  - `> 0` ⇒ `takeLast(historyWindowTurns * 2)` (ist die History kürzer als das
     *    Fenster, gibt `takeLast` sie unverändert zurück).
     *
     * Aufgerufen vom [TurnOrchestrator] direkt vor jedem `brain.streamChat(history=…)`.
     */
    fun windowHistory(history: List<ChatMessage>): List<ChatMessage> =
        if (historyWindowTurns > 0) history.takeLast(historyWindowTurns * 2) else history

    /**
     * Reaktiver Teil: holt Wiki-Grounding + Episodic-Recall PARALLEL ([Mono.zip])
     * und schichtet das finale Prompt.
     *
     * Grounding nur bei LOCAL-Provider + [wikiGroundingEnabled] (Cloud-Modelle
     * wissen's selbst). Episodic nur LOCAL + wenn Port da (Default OFF → `""`).
     * Reihenfolge (unverändert): persönlicher Kontext (Episodic) VOR Fakten (Grounding).
     *
     * @param systemPrompt Basis-Prompt aus [baseSystemPrompt] (persona+entity).
     * @param followBlock  Multi-Turn-Kontext-Block ("" = keiner).
     * @param groundingQuery überschreibt `ctx.text` bei Multi-Turn-Folgen.
     */
    fun assemble(
        ctx: TurnPrompt,
        decision: RouteDecision,
        systemPrompt: String,
        followBlock: String,
        groundingQuery: String? = null,
    ): Mono<AssembledPrompt> =
        assembleWith(
            ctx = ctx,
            decision = decision,
            systemPrompt = systemPrompt,
            followBlock = followBlock,
            groundingQuery = groundingQuery,
            groundingLookup = grounding::groundingBlock,
        )

    /**
     * Dieselbe Prompt-Assembly wie [assemble], aber über die default-deny
     * [GroundingPort.localKnowledgeBlock]-Naht. Damit bleiben Reihenfolge,
     * Episodic-Parallelisierung, Sentinel-Behandlung und Messpunkt identisch;
     * lediglich die zulässige Grounding-Quelle ist enger.
     */
    fun assembleLocalKnowledge(
        ctx: TurnPrompt,
        decision: RouteDecision,
        systemPrompt: String,
        followBlock: String,
        groundingQuery: String? = null,
    ): Mono<AssembledPrompt> =
        assembleWith(
            ctx = ctx,
            decision = decision,
            systemPrompt = systemPrompt,
            followBlock = followBlock,
            groundingQuery = groundingQuery,
            groundingLookup = grounding::localKnowledgeBlock,
        )

    /**
     * Einziger Assembly-Kern für den normalen und den eng lokalen Grounding-Pfad.
     * [groundingLookup] wird bei LOCAL+Wiki-ON genau einmal innerhalb von
     * [Mono.defer] aufgerufen; damit misst [AssembledPrompt.groundingMs] in beiden
     * Pfaden dieselbe echte Operation.
     */
    private fun assembleWith(
        ctx: TurnPrompt,
        decision: RouteDecision,
        systemPrompt: String,
        followBlock: String,
        groundingQuery: String?,
        groundingLookup: (String, RouteCategory, Language) -> Mono<String>,
    ): Mono<AssembledPrompt> {
        val promptWithFollow =
            if (followBlock.isNotEmpty()) systemPrompt + followBlock else systemPrompt

        // Perf-Diary: Dauer des ECHTEN Grounding-Calls (−1 = kein Call ⇒ null im
        // Ergebnis). Gemessen um genau den GroundingPort-Call — der Nicht-LOCAL-/
        // OFF-Zweig ruft nie und misst nie (byte-neutral, kein erfundenes 0).
        val groundingElapsedMs = java.util.concurrent.atomic.AtomicLong(-1)
        val groundingMono: Mono<String> =
            if (decision.provider == RouteProvider.LOCAL && wikiGroundingEnabled) {
                Mono.defer {
                    val t0 = nanoTime()
                    // Turn-Sprache durchreichen (Multilingual-Welle 2026-07-24): der
                    // Grounding-Block muss der SPRACHE DES TURNS folgen (siehe
                    // [GroundingPort]-KDoc), nicht einer separaten Anzeigesprache.
                    groundingLookup(groundingQuery ?: ctx.text, decision.category, ctx.language)
                        .defaultIfEmpty("")
                        .doOnNext { groundingElapsedMs.set((nanoTime() - t0) / 1_000_000) }
                }
            } else {
                Mono.just("")
            }
        // Episodic-Recall NUR bei LOCAL-Provider (Privacy-Gate: wörtlicher früherer
        // User-Klartext darf nie ungefiltert in den Cloud-Prompt). Default OFF → "".
        // Schlüssel = speakerId (NICHT chatId): identisch zur Store-Naht + Entity-
        // Gedächtnis (Multi-User). Ohne Sprecher → "unknown" (Gast → kein Recall).
        // Vertrauens-Gate (P1): OFF ⇒ exakt der bisherige Fallback (byte-neutral); ON ⇒
        // SpeakerTrust entscheidet Claim-vs-Gast — dieselbe Funktion wie in
        // [baseSystemPrompt] + ChatStreamController.rememberAfter (siehe SpeakerTrust-KDoc).
        val episodicSpeaker =
            SpeakerTrust.resolve(ctx.speaker, speakerTrustEnforced, speakerTrustThreshold)?.speakerId ?: "unknown"
        val episodicMono: Mono<String> =
            if (decision.provider == RouteProvider.LOCAL) {
                // Turn-Sprache durchreichen (Sprach-Naht 2026-07-25) — s. EntityContextPort.
                episodicMemory?.recallBlock(episodicSpeaker, ctx.text, ctx.language) ?: Mono.just("")
            } else {
                Mono.just("")
            }

        return Mono.zip(groundingMono, episodicMono)
            .map { tup ->
                val groundBlock = tup.t1
                val episodicBlock = tup.t2
                // Reihenfolge: persönlicher Kontext (Episodic) vor Fakten (Grounding).
                val withEpisodic =
                    if (episodicBlock.isNotEmpty()) promptWithFollow + "\n\n" + episodicBlock else promptWithFollow
                // Der Bridge-Down-Sentinel ist ein STEUER-marker, KEIN Kontext —
                // niemals ins Prompt schichten, aber im groundBlock zurückgeben.
                val bridgeDown = groundBlock == bridgeDownSentinel
                val finalPrompt =
                    if (groundBlock.isNotEmpty() && !bridgeDown) withEpisodic + groundBlock else withEpisodic
                AssembledPrompt(
                    finalPrompt = finalPrompt,
                    groundBlock = groundBlock,
                    groundingMs = groundingElapsedMs.get().takeIf { it >= 0 },
                )
            }
    }

    companion object {
        /** Default-Sentinel für „Wissensspeicher nicht erreichbar" (aus 0.5 `WikiGroundingService`). */
        const val BRIDGE_DOWN_SENTINEL = "__BRIDGE_DOWN__"

        /**
         * **Herkunfts-Marker „Cache-Hit aus dem Nachgeschlagen-Store"** (Extended
         * Think S3/S4, additiv geteilte Wahrheit — Muster [BRIDGE_DOWN_SENTINEL]):
         * [de.hoshi.adapters.knowledge.NachgeschlagenGroundingProvider] bettet
         * DIESEN exakten Substring in seinen HINTERGRUND-Block, wenn er eine
         * gecachte Nachgeschlagen-Antwort liefert (schon vorher wörtlich als
         * „Herkunfts-Marker" dokumentiert/getestet — hier nur zur geteilten
         * Konstante erhoben, der Block-TEXT bleibt byte-gleich). Der
         * [TurnOrchestrator] prüft NACH [assemble] den
         * [AssembledPrompt.groundBlock] auf dieses Vorkommen, um
         * [de.hoshi.core.dto.ChatEvent.Start.cacheHit] ehrlich zu setzen.
         *
         * **Lebt bewusst HIER (core-domain), NICHT im Provider:** `adapters-knowledge`
         * hängt VON `core-domain` ab (`implementation(project(":core-domain"))`),
         * NICHT umgekehrt — der Kern bleibt „Reiner Domänen-Kern: NUR Reactor +
         * kotlin-stdlib" (core-domain/build.gradle.kts), ein core-domain→adapter-
         * Import wäre ein verbotener Rückwärts-Pfeil im Modul-Graph. Der Provider
         * IMPORTIERT diese Konstante beim Bauen seines Blocks — „eine Wahrheit,
         * zwei Ränder" (dasselbe Muster wie [BRIDGE_DOWN_SENTINEL] und
         * [de.hoshi.core.pipeline.lang.LanguagePack.factCoverageDeflect]).
         */
        const val NACHGESCHLAGEN_ORIGIN_MARKER = "neulich nachgeschlagen"

        /**
         * **Sprachneutrale Fortsetzung des Herkunfts-Markers (Ehrlichkeits-Schuld-Fix,
         * seit v0.8.1-rc1 in den Release-Notes: „die cacheHit-Telemetrie erkennt ihren
         * Herkunfts-Marker nur auf Deutsch"):** [NACHGESCHLAGEN_ORIGIN_MARKER] deckte
         * bislang NUR die deutsche Blockfassung. Diese vier Konstanten sind das EN/ES/
         * FR/IT-Gegenstück — je ein eindeutiger Substring, der EXAKT in der jeweiligen
         * Sprachfassung von
         * [de.hoshi.adapters.knowledge.NachgeschlagenBlockTexts.plainInstruction]/
         * [de.hoshi.adapters.knowledge.NachgeschlagenBlockTexts.fencedInstruction]
         * vorkommt (verifiziert gegen den heutigen Wortlaut, s.
         * [de.hoshi.core.pipeline.TurnOrchestratorCacheHitLanguageTest]).
         *
         * **Bewusst hier REPLIZIERT statt importiert, wie [NACHGESCHLAGEN_ORIGIN_MARKER]
         * selbst:** core-domain darf nicht auf `adapters-knowledge` zeigen (Modul-Graph,
         * `adapters-knowledge` hängt VON `core-domain` ab, nicht umgekehrt) — anders als
         * beim deutschen Marker kann der Provider die EN/ES/FR/IT-Sätze also nicht aus
         * diesen Konstanten zusammensetzen (die Sätze sind dort frei formuliert, der
         * Marker ist nur ein Teil-String davon). Drift-Schutz kommt darum aus dem Test,
         * nicht aus dem Compiler: [de.hoshi.core.pipeline.TurnOrchestratorCacheHitLanguageTest]
         * UND `NachgeschlagenGroundingLanguageTest` (adapters-knowledge) prüfen denselben
         * Wortlaut von zwei Seiten.
         */
        const val NACHGESCHLAGEN_ORIGIN_MARKER_EN = "looked this up recently"

        /** s. [NACHGESCHLAGEN_ORIGIN_MARKER_EN]-KDoc — die spanische Fassung. */
        const val NACHGESCHLAGEN_ORIGIN_MARKER_ES = "consulté hace poco"

        /** s. [NACHGESCHLAGEN_ORIGIN_MARKER_EN]-KDoc — die französische Fassung. */
        const val NACHGESCHLAGEN_ORIGIN_MARKER_FR = "cherché cela récemment"

        /** s. [NACHGESCHLAGEN_ORIGIN_MARKER_EN]-KDoc — die italienische Fassung. */
        const val NACHGESCHLAGEN_ORIGIN_MARKER_IT = "cercato di recente"

        /**
         * **Exhaustiv über [Language]: der Herkunfts-Marker DIESER Turn-Sprache.**
         * Der [de.hoshi.core.pipeline.TurnOrchestrator] prüft NACH [assemble] den
         * [AssembledPrompt.groundBlock] NUR auf DIESEN EINEN, sprachgenauen Marker
         * (statt sicherheitshalber alle fünf durchzuprobieren) — der Block wurde ja
         * bereits FÜR [ctx.language] gebaut, s. [de.hoshi.core.dto.TurnPrompt.language].
         */
        fun nachgeschlagenOriginMarker(language: Language): String = when (language) {
            Language.DE -> NACHGESCHLAGEN_ORIGIN_MARKER
            Language.EN -> NACHGESCHLAGEN_ORIGIN_MARKER_EN
            Language.ES -> NACHGESCHLAGEN_ORIGIN_MARKER_ES
            Language.FR -> NACHGESCHLAGEN_ORIGIN_MARKER_FR
            Language.IT -> NACHGESCHLAGEN_ORIGIN_MARKER_IT
        }
    }
}
