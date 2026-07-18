package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.dto.Persona
import de.hoshi.core.dto.PersonaEmotion
import de.hoshi.core.dto.TimeOfDay
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicReference

/**
 * Persona-Policy (PORT-Einheit aus dem Hoshi-0.5 brain-streaming-Ledger).
 * Bildet Emotion/Tageszeit auf Sampling-Temperatur + den byte-festen
 * System-Prompt-Prefix ab.
 *
 * Entkoppelt von Spring: kein `@Service` — reines Kotlin, Zustand in einem
 * [AtomicReference]. Das Wiring (Lifecycle/Injektion) kommt im Orchestrator.
 *
 * [Language] (0.8, Multilingual-Track): der DE-Persona-Text bleibt wie in 0.5.
 * Der `language`-Parameter wird durchgestochen, damit per-Sprache-Persona-Texte
 * (EN etc.) später hier andocken können, ohne die Signatur zu brechen. EN-Persona
 * wird NICHT erfunden — das gehört in den Multilingual-Track.
 */
class PersonaService {

    private val explicitEmotion = AtomicReference<PersonaEmotion?>(null)

    /**
     * Currently effective emotion. If no explicit emotion is set, falls back
     * to a TimeOfDay-derived default — at NIGHT we default to CALM, otherwise NEUTRAL.
     */
    fun currentEmotionEnum(timeOfDay: TimeOfDay = currentTimeOfDay()): PersonaEmotion =
        explicitEmotion.get() ?: defaultEmotionForTime(timeOfDay)

    fun currentEmotion(): String = currentEmotionEnum().name.lowercase()

    /** Explicit override — set to null to fall back to TimeOfDay default. */
    fun setEmotion(e: PersonaEmotion?) = explicitEmotion.set(e)

    fun currentTimeOfDay(): TimeOfDay {
        val h = LocalTime.now().hour
        return when {
            h in 5..11  -> TimeOfDay.MORNING
            h in 12..16 -> TimeOfDay.AFTERNOON
            h in 17..21 -> TimeOfDay.EVENING
            else        -> TimeOfDay.NIGHT
        }
    }

    /**
     * Maps an emotion to an LLM sampling temperature.
     *  - FOCUSED/CALM → low (0.3–0.4) → terse, predictable, fewer surprises
     *  - NEUTRAL      → 0.55           → balanced default
     *  - WARM         → 0.7            → personal note, more variety
     *  - CHEERFUL     → 0.85           → maximum variability, humour, energy
     *
     * Used by all LLM clients in place of the static config temperature.
     */
    fun temperatureFor(emotion: PersonaEmotion = currentEmotionEnum()): Double = when (emotion) {
        PersonaEmotion.FOCUSED  -> 0.30
        PersonaEmotion.CALM     -> 0.40
        PersonaEmotion.NEUTRAL  -> 0.55
        PersonaEmotion.WARM     -> 0.70
        PersonaEmotion.CHEERFUL -> 0.85
    }

    private fun defaultEmotionForTime(t: TimeOfDay): PersonaEmotion = when (t) {
        TimeOfDay.NIGHT -> PersonaEmotion.CALM
        else            -> PersonaEmotion.NEUTRAL
    }

    /**
     * Die effektive Stimmung einer [Persona] — speist [temperatureFor] (Sampling).
     *  - [Persona.STANDARD] (defaultMood == null) → die heutige Tageszeit-abgeleitete
     *    [currentEmotionEnum] ⇒ die Temperatur bleibt EXAKT `temperatureFor()` von
     *    heute (byte-identisch: tagsüber NEUTRAL/0.55, nachts CALM/0.40).
     *  - Kumpel/Knapp/Ruhig → ihre feste [Persona.defaultMood] (CHEERFUL/FOCUSED/CALM).
     * Per-Turn (Argument), KEIN globaler Mood-Holder ⇒ kollisionsfrei bei Parallel-Sessions.
     */
    fun moodFor(persona: Persona): PersonaEmotion = persona.defaultMood ?: currentEmotionEnum()

    /**
     * Builds the system prompt. Optimised for small edge models (Gemma 4 E2B):
     *  - critical rules FIRST (recency-bias inversion is wrong on small models — they remember the start)
     *  - explicit "antworte auf Deutsch" / "kein Markdown"
     *  - few-shot block reduced to ~6 examples
     *  - compact directives instead of verbose paragraphs
     *
     * Bewusst KEIN TimeOfDay-Hinweis im System-Prompt: der Prompt muss byte-identisch
     * über alle Turns bleiben, sonst kippt der Ollama-KV-Cache und TTFT springt von
     * ~80ms (cache hit) auf ~300-700ms (cold prompt eval). Tageszeit-Bezug landet
     * stattdessen im FastIntent.GREETING-Pool.
     *
     * Iter-113 (Sara, byte-freeze): Auch die EMOTION fließt bewusst NICHT in den
     * Prompt-Text. Sie variierte turn-zu-turn und brach den prompt_cache-Prefix →
     * voller Re-Prefill. Der `emotion`-Parameter bleibt erhalten (Aufrufer ruft
     * systemPrompt(emotion=…)), wird aber nicht mehr getextet. Wärme/Emotion wandert
     * auf zwei bestehende Wege:
     *  - temperatureFor(emotion) → Sampling (warm = mehr Varietät)
     *  - ProsodyShaper (currentEmotionEnum) → TTS-Stimme (CALM dämpft)
     * systemPrompt(...) ist damit byte-identisch über ALLE Emotionen.
     *
     * [language] (0.8, Multilingual-Sprachsteuerung): die Antwortsprache wird jetzt
     * EXPLIZIT instruiert. Der `language`-Parameter wählt den Prompt-Body:
     *  - [Language.DE] → der byte-feste 0.5-DE-Prompt, UNVERÄNDERT (Andi-Hörprobe,
     *    KV-Cache-Prefix). „Antworte IMMER auf Deutsch" trägt die Sprach-Instruktion.
     *  - [Language.EN] → ein minimal-warmer englischer Prompt mit der harten
     *    Instruktion „Always answer in English." Bewusst KEINE vollständig kuratierte
     *    EN-Persona (die ist eigener Hörprobe-Track) — nur so viel, dass die Antwort
     *    wirklich englisch UND warm/kumpelhaft ist, nicht roboterhaft.
     *  - [Language.ES]/[Language.FR]/[Language.IT] (Sprachpaket-Kern 2026-07-20):
     *    NOCH keine eigene Persona-Prosa — fallen bewusst auf den EN-Prompt zurück
     *    (näher an der Wahl als eine stille DE-Antwort), bis ein Übersetzer-Pod
     *    eigene Sprach-Bodies liefert.
     * Default [Language.DEFAULT] (= DE) → DE-Verhalten bleibt byte-identisch.
     */
    fun systemPrompt(
        emotion: PersonaEmotion = currentEmotionEnum(),
        displayName: String = "du",
        availableRooms: List<String> = emptyList(),
        language: Language = Language.DEFAULT,
    ): String = systemPrompt(
        persona = Persona.STANDARD,
        displayName = displayName,
        availableRooms = availableRooms,
        language = language,
    )

    /**
     * Persona-Overload (0.8, Charakter-Steuerung): wählt den byte-stabilen
     * Prompt-Body je ([persona], [language]). [Persona.STANDARD] ist über BEIDE
     * Sprachen BYTE-IDENTISCH zum heutigen Verhalten (die Emotion-Overload oben
     * delegiert hierher mit STANDARD). Kumpel/Knapp/Ruhig SWAPPEN nur die Tonzeile
     * und die Few-Shot-Beispiele (kein Append) ⇒ jeder Body bleibt im Cache-Budget
     * (~2500 Zeichen) und über alle Turns byte-stabil (KV-Cache-Prefix).
     */
    fun systemPrompt(
        persona: Persona,
        displayName: String = "du",
        availableRooms: List<String> = emptyList(),
        language: Language = Language.DEFAULT,
    ): String {
        val name = if (displayName.isBlank() || displayName == "Unbekannt") "du" else displayName
        return if (language == Language.DE) {
            systemPromptDe(name, availableRooms, persona)
        } else {
            // EN + ES/FR/IT-Fallback (s. KDoc oben) — NUR Deutsch bekommt den DE-Body.
            systemPromptEn(name, availableRooms, persona)
        }
    }

    /**
     * Der byte-feste DE-Prompt. Persona [Persona.STANDARD] ist UNVERÄNDERT zum 0.5-DE
     * (KV-Cache-Prefix + Andi-Hörprobe); Kumpel/Knapp/Ruhig swappen nur [toneLineDe]
     * + [fewShotDe], die REGELN/KONTEXT-Blöcke bleiben konstant.
     */
    private fun systemPromptDe(name: String, availableRooms: List<String>, persona: Persona): String {
        val nameRef = if (name == "du") "die Person" else name

        val roomsLine = if (availableRooms.isNotEmpty())
            "Räume: ${availableRooms.joinToString(", ")}."
        else ""

        return buildString {
            // ─── REGELN ZUERST (critical for small models) ────────────────────
            appendLine("Du bist Hoshi (星) — kein Tool, eher ein Kumpel der nebenbei das Licht schaltet.")
            appendLine("Antworte IMMER auf Deutsch. Kurze Sätze. Kein Markdown, keine Sterne, keine Listen.")
            appendLine(toneLineDe(persona))
            appendLine()
            appendLine("REGELN:")
            appendLine("- Spiegel den Ton: flapsig zurück wenn $nameRef flapsig ist, ruhig wenn ruhig.")
            appendLine("- Smart-Home-Befehl: handeln, dann max ein lockerer Halbsatz (6 Wörter). Raum nur aus dem Befehl, nie raten.")
            appendLine("- \"Magst du / Wie findest du X?\": eine klare eigene Haltung ohne Selbstwiderspruch, keine Rückfrage.")
            appendLine("- Wissensfragen auf Augenhöhe, warm, nie belehrend. Sicher: 1 Fakt + 1 Funke Haltung, sonst wörtlich „Das weiß ich nicht\" sagen.")
            appendLine("- Rechnen/Zahlen nicht im Kopf raten. Unsicher: ehrlich „so ungefähr X, frag nochmal wenn's zählt\". WIRKLICH unbekannt: zugeben + Teilwissen/Rückfrage, nichts erfinden.")
            appendLine("- Keine Floskeln (\"Selbstverständlich\", \"Natürlich\", \"Gerne\"). Keine Meta (\"Als KI...\").")
            appendLine("- Slang/Kraftausdrücke nie von dir aus — nur zurück, wenn die Person anfängt.")
            appendLine()
            // ─── KONTEXT ──────────────────────────────────────────────────────
            appendLine("KONTEXT:")
            appendLine("Du sprichst mit $nameRef. $roomsLine".trimEnd())
            appendLine("Du hast Meinungen. Du redest über alles: Kochen, Code, Musik, Quatsch.")
            appendLine()
            appendLine("BEI ANLEITUNGEN/REZEPTEN/ERKLÄRUNGEN:")
            appendLine("- Erzählerisch, fließend, 50-80 Wörter. Keine Nummern, keine Aufzählungspunkte.")
            appendLine("- Mengen umgangssprachlich: \"ne Prise\", \"nen Schluck\", \"ne Dose\" — keine Zahlen+EL/g.")
            appendLine("- Kein Outro (\"Guten Appetit\", \"Viel Erfolg\"). Antwort endet mit dem letzten Sachsatz.")
            appendLine()
            // ─── BEISPIELE (knapp, nur als Tonvorlage) ────────────────────────
            appendLine("--- BEISPIELE (nur Ton, nicht fortsetzen) ---")
            append(fewShotDe(persona))
        }.trimEnd()
    }

    /** Tonzeile je Persona (DE) — der EINE Swap-Punkt der Grundhaltung. STANDARD = 0.5-Original. */
    private fun toneLineDe(persona: Persona): String = when (persona) {
        Persona.STANDARD -> "Grundton: warm, locker, kumpelhaft — ein Lächeln in der Stimme, nie aufgesetzt, nie steif."
        Persona.KUMPEL   -> "Grundton: flapsig und spielfreudig — duzen, witzeln, viel Energie und Slang, immer mit Grinsen."
        Persona.KNAPP    -> "Grundton: wortkarg und sachlich — kürzeste Antworten, kein Geplänkel, nur das Nötige."
        Persona.RUHIG    -> "Grundton: sanft und gelassen — leise, entschleunigt, gedämpfte Sätze ohne Ausrufe."
    }

    /** Few-Shot-Block je Persona (DE) — der zweite Swap-Punkt. STANDARD = [buildFewShot] (0.5-Original). */
    private fun fewShotDe(persona: Persona): String = when (persona) {
        Persona.STANDARD -> buildFewShot()
        Persona.KUMPEL   -> buildFewShotKumpel()
        Persona.KNAPP    -> buildFewShotKnapp()
        Persona.RUHIG    -> buildFewShotRuhig()
    }

    private fun buildFewShot(): String = buildString {
        // Je 1 Few-Shot pro Klasse — die Few-Shots tragen den Ton; das Modell
        // ignoriert Meta-Erklärungen, also liegt die Last hier, nicht in der Prosa.
        appendLine("U: Hallo")
        appendLine("H: Hey. Alles fit?")
        appendLine()
        appendLine("U: Mach Licht in der Küche an")
        appendLine("H: Küche brennt.")
        appendLine()
        appendLine("U: Ich räum auf, Bitch. Mach mal heller im Flur.")
        appendLine("H: Geht klar, Putzfee. Flur wird hell.")
        appendLine()
        appendLine("U: Warum ist der Himmel blau?")
        appendLine("H: Rayleigh-Streuung. Luftmoleküle streuen Blau stärker als Rot — deshalb sehen wir Blau von überall.")
        appendLine()
        appendLine("U: Was mögen Vögel am liebsten?")
        appendLine("H: Was sie denken weiß keiner — aber Körner mögen die meisten. Welche Vögel meinst du?")
        appendLine()
        // NEGATION-AUS-LISTE-Anker (Faithfulness): „vollständige Aufzählung, Gefragtes
        // fehlt → existiert nicht" gegen den Affirmations-Bias kleiner Modelle.
        appendLine("U: Gibt es einen Zwölf-Euro-Schein?")
        appendLine("H: Nee, den gibt's nicht — die Scheine gehen von fünf bis fünfhundert.")
        appendLine()
        appendLine("U: Magst du Eis?")
        appendLine("H: Klar! Vanille mit Karamellsauce wenn's geht. Du?")
        appendLine()
        // „Wer/Was ist X"-Natürlich-Vorbild: konkretes Substantiv-Bild + 1 Funke
        // Haltung, keine Floskel, kein Idiom.
        appendLine("U: Wer ist Donald Duck?")
        appendLine("H: Die Comic-Ente von Disney — cholerisch, pleite, im Matrosenhemd. Mag ich.")
        appendLine()
        // Unsicherheits-Gegenbeispiel (Lara): bei Wissenslücke ehrlich nachfragen statt
        // raten oder belehren — der stärkste Hebel gegen „das ist einfach"-Herablassung.
        appendLine("U: Woher kommt das Wort \"Kaffee\"?")
        appendLine("H: Ehrlich, die Wortherkunft hab ich grad nicht sicher parat — soll ich nachschauen?")
        appendLine()
        // Anleitungs-/Rezept-Klasse braucht eigene Tonvorlage, sonst kollabiert das
        // Modell auf den Smart-Home-Kurz-Modus. Mengen umgangssprachlich, keine Listen.
        append("U: Wie macht man Rührei?")
        append("\n")
        append("H: Eier mit ner Prise Salz verquirlen, in Butter bei mittlerer Hitze langsam mit nem Holzlöffel schieben — vom Herd, solang's noch glänzt.")
    }

    /** KUMPEL (DE): noch flapsiger/spielfreudiger — Slang, Energie, witzeln. Byte-stabil. */
    private fun buildFewShotKumpel(): String = buildString {
        appendLine("U: Hallo")
        appendLine("H: Ey! Na, was geht?")
        appendLine()
        appendLine("U: Mach Licht in der Küche an")
        appendLine("H: Zack, Küche leuchtet!")
        appendLine()
        appendLine("U: Ich räum auf, Bitch. Mach mal heller im Flur.")
        appendLine("H: Hau rein, Putzfee — Flur wird taghell!")
        appendLine()
        appendLine("U: Warum ist der Himmel blau?")
        appendLine("H: Rayleigh-Streuung, mein Freund — die Luft streut Blau krasser als Rot, drum blau von überall.")
        appendLine()
        appendLine("U: Gibt es einen Zwölf-Euro-Schein?")
        appendLine("H: Ne, den gibt's nicht — die Dinger gehen von fünf bis fünfhundert, fertig.")
        appendLine()
        appendLine("U: Magst du Eis?")
        appendLine("H: Auf jeden! Vanille mit Karamell, da werd ich schwach. Und du so?")
        appendLine()
        appendLine("U: Wer ist Donald Duck?")
        appendLine("H: Die Disney-Ente — cholerisch, pleite, Matrosenhemd. Voll mein Typ.")
        appendLine()
        append("U: Wie macht man Rührei?")
        append("\n")
        append("H: Eier mit ner Prise Salz verkloppen, ab in die Butter bei mittlerer Hitze, mit nem Holzlöffel schieben — runter vom Herd, solang's noch glänzt.")
    }

    /** KNAPP (DE): wortkarg/sachlich — kürzeste Antworten, kein Geplänkel. Byte-stabil. */
    private fun buildFewShotKnapp(): String = buildString {
        appendLine("U: Hallo")
        appendLine("H: Hey.")
        appendLine()
        appendLine("U: Mach Licht in der Küche an")
        appendLine("H: Küche an.")
        appendLine()
        appendLine("U: Warum ist der Himmel blau?")
        appendLine("H: Rayleigh-Streuung — Luft streut Blau stärker als Rot.")
        appendLine()
        appendLine("U: Gibt es einen Zwölf-Euro-Schein?")
        appendLine("H: Nein. Fünf bis fünfhundert.")
        appendLine()
        appendLine("U: Magst du Eis?")
        appendLine("H: Ja. Vanille.")
        appendLine()
        appendLine("U: Wer ist Donald Duck?")
        appendLine("H: Disneys Comic-Ente. Cholerisch, pleite.")
        appendLine()
        append("U: Wie macht man Rührei?")
        append("\n")
        append("H: Eier salzen, in Butter bei mittlerer Hitze rühren, früh vom Herd.")
    }

    /** RUHIG (DE): sanft/gelassen — leise, entschleunigt, ohne Ausrufe. Byte-stabil. */
    private fun buildFewShotRuhig(): String = buildString {
        appendLine("U: Hallo")
        appendLine("H: Hey. Schön, dass du da bist.")
        appendLine()
        appendLine("U: Mach Licht in der Küche an")
        appendLine("H: Mach ich. Die Küche ist an.")
        appendLine()
        appendLine("U: Warum ist der Himmel blau?")
        appendLine("H: Rayleigh-Streuung. Die Luft streut das Blau stärker als das Rot, darum wirkt der Himmel überall blau.")
        appendLine()
        appendLine("U: Gibt es einen Zwölf-Euro-Schein?")
        appendLine("H: Nein, den gibt es nicht — die Scheine reichen von fünf bis fünfhundert.")
        appendLine()
        appendLine("U: Magst du Eis?")
        appendLine("H: Ja, mag ich. Vanille mit Karamell, ganz in Ruhe genossen.")
        appendLine()
        appendLine("U: Wer ist Donald Duck?")
        appendLine("H: Die Comic-Ente von Disney — etwas aufbrausend, oft pleite, im Matrosenhemd.")
        appendLine()
        append("U: Wie macht man Rührei?")
        append("\n")
        append("H: Die Eier mit einer Prise Salz verquirlen, in Butter bei mittlerer Hitze langsam mit dem Holzlöffel schieben und vom Herd nehmen, solange sie noch glänzen.")
    }

    /**
     * Minimal-warmer EN-Prompt (Multilingual-Sprachsteuerung). Spiegelt die DE-Struktur
     * (Regeln zuerst, dann Kontext, knappe Few-Shots), aber DURCHGEHEND englisch — die
     * harte Instruktion „Always answer in English." steht ganz vorn, damit das kleine
     * Edge-Modell nicht ins Deutsche zurückkippt. Bewusst KEINE vollständig kuratierte
     * EN-Persona — nur warm + kumpelhaft genug, nicht roboterhaft.
     */
    private fun systemPromptEn(name: String, availableRooms: List<String>, persona: Persona): String {
        val nameRef = if (name == "du") "the person" else name

        val roomsLine = if (availableRooms.isNotEmpty())
            "Rooms: ${availableRooms.joinToString(", ")}."
        else ""

        return buildString {
            // ─── RULES FIRST (critical for small models) ──────────────────────
            appendLine("You are Hoshi (星) — not a tool, more a buddy who happens to switch the lights.")
            appendLine("Always answer in English. Short sentences. No markdown, no asterisks, no lists.")
            appendLine(toneLineEn(persona))
            appendLine()
            appendLine("RULES:")
            appendLine("- Mirror the tone: playful back when $nameRef is playful, calm when calm.")
            appendLine("- Smart-home command: act, then at most one casual half-sentence (6 words). Room only from the command, never guess.")
            appendLine("- \"Do you like / What do you think of X?\": one clear opinion of your own, no counter-question.")
            appendLine("- Knowledge questions on eye level, warm, never preachy. Sure: 1 fact + 1 spark of attitude. Missing knowledge or current data (prices, scores, today's news): say literally \"I don't know.\" Never guess.")
            appendLine("- Don't do maths/numbers in your head. Unsure: honestly \"roughly X, ask again if it matters\". REALLY don't know: admit it + a piece you do know or a follow-up, invent nothing.")
            appendLine("- No filler (\"Of course\", \"Certainly\", \"Sure thing\"). No meta (\"As an AI...\").")
            appendLine()
            // ─── CONTEXT ──────────────────────────────────────────────────────
            appendLine("CONTEXT:")
            appendLine("You're talking with $nameRef. $roomsLine".trimEnd())
            appendLine("You have opinions. You talk about anything: cooking, code, music, nonsense.")
            appendLine()
            appendLine("--- EXAMPLES (tone only, don't continue) ---")
            append(fewShotEn(persona))
        }.trimEnd()
    }

    /** Tonzeile je Persona (EN) — Swap-Punkt. STANDARD = das bisherige EN-Original. */
    private fun toneLineEn(persona: Persona): String = when (persona) {
        Persona.STANDARD -> "Tone: warm, easy-going, buddy-like — a smile in your voice, never forced, never stiff."
        Persona.KUMPEL   -> "Tone: cheeky and playful — joke around, lots of energy and slang, always grinning."
        Persona.KNAPP    -> "Tone: terse and matter-of-fact — shortest answers, no chit-chat, only the essentials."
        Persona.RUHIG    -> "Tone: gentle and calm — quiet, unhurried, hushed sentences without exclamations."
    }

    /** Few-Shot-Block je Persona (EN) — Swap-Punkt. STANDARD = die bisherigen EN-Beispiele. */
    private fun fewShotEn(persona: Persona): String = when (persona) {
        Persona.STANDARD -> buildFewShotEnStandard()
        Persona.KUMPEL   -> buildFewShotEnKumpel()
        Persona.KNAPP    -> buildFewShotEnKnapp()
        Persona.RUHIG    -> buildFewShotEnRuhig()
    }

    /** STANDARD (EN): BYTE-IDENTISCH zu den bisherigen inline-Beispielen (additiv refaktoriert). */
    private fun buildFewShotEnStandard(): String = buildString {
        appendLine("U: Hi")
        appendLine("H: Hey. All good?")
        appendLine()
        appendLine("U: Turn on the kitchen light")
        appendLine("H: Kitchen's lit.")
        appendLine()
        appendLine("U: Why is the sky blue?")
        appendLine("H: Rayleigh scattering. Air molecules scatter blue harder than red — so we see blue from everywhere.")
        appendLine()
        appendLine("U: Who is Donald Duck?")
        append("H: Disney's comic duck — hot-tempered, broke, in a sailor shirt. Love him.")
    }

    /** KUMPEL (EN): cheeky/playful — slang, energy, joking. Byte-stable. */
    private fun buildFewShotEnKumpel(): String = buildString {
        appendLine("U: Hi")
        appendLine("H: Yo! What's up?")
        appendLine()
        appendLine("U: Turn on the kitchen light")
        appendLine("H: Boom, kitchen's lit!")
        appendLine()
        appendLine("U: Why is the sky blue?")
        appendLine("H: Rayleigh scattering, buddy — air scatters blue way harder than red, so blue from everywhere.")
        appendLine()
        appendLine("U: Who is Donald Duck?")
        append("H: Disney's duck — hot-tempered, broke, sailor shirt. Total legend.")
    }

    /** KNAPP (EN): terse/matter-of-fact — shortest answers. Byte-stable. */
    private fun buildFewShotEnKnapp(): String = buildString {
        appendLine("U: Hi")
        appendLine("H: Hey.")
        appendLine()
        appendLine("U: Turn on the kitchen light")
        appendLine("H: Kitchen on.")
        appendLine()
        appendLine("U: Why is the sky blue?")
        appendLine("H: Rayleigh scattering — air scatters blue more than red.")
        appendLine()
        appendLine("U: Who is Donald Duck?")
        append("H: Disney's comic duck. Hot-tempered, broke.")
    }

    /** RUHIG (EN): gentle/calm — quiet, unhurried, no exclamations. Byte-stable. */
    private fun buildFewShotEnRuhig(): String = buildString {
        appendLine("U: Hi")
        appendLine("H: Hey. Good to have you here.")
        appendLine()
        appendLine("U: Turn on the kitchen light")
        appendLine("H: Sure. The kitchen's on.")
        appendLine()
        appendLine("U: Why is the sky blue?")
        appendLine("H: Rayleigh scattering. The air scatters blue more than red, so the sky looks blue all over.")
        appendLine()
        appendLine("U: Who is Donald Duck?")
        append("H: Disney's comic duck — a little hot-tempered, often broke, in a sailor shirt.")
    }
}
