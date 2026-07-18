package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.dto.Persona
import de.hoshi.core.dto.PersonaEmotion
import de.hoshi.core.dto.TimeOfDay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Portiert aus Hoshi 0.5 (de.hoshi.app.streaming.PersonaServiceTest).
 * Entkopplung: kein Spring; `PersonaEmotion`/`TimeOfDay` aus `de.hoshi.core.dto`.
 */
class PersonaServiceTest {

    private val svc = PersonaService()

    @Test
    fun `temperatureFor maps each emotion to a distinct value`() {
        val temps = PersonaEmotion.values().map { svc.temperatureFor(it) }
        assertEquals(temps.distinct().size, temps.size, "every emotion needs its own temperature")
    }

    @Test
    fun `temperatureFor is monotonic from FOCUSED to CHEERFUL`() {
        val ordered = listOf(
            PersonaEmotion.FOCUSED,
            PersonaEmotion.CALM,
            PersonaEmotion.NEUTRAL,
            PersonaEmotion.WARM,
            PersonaEmotion.CHEERFUL,
        )
        val temps = ordered.map { svc.temperatureFor(it) }
        for (i in 1 until temps.size) {
            assertTrue(temps[i] > temps[i - 1], "temp[${ordered[i]}] = ${temps[i]} should be > temp[${ordered[i-1]}] = ${temps[i-1]}")
        }
    }

    @Test
    fun `temperatureFor stays within sane bounds`() {
        for (e in PersonaEmotion.values()) {
            val t = svc.temperatureFor(e)
            assertTrue(t in 0.1..1.0, "temperature for $e out of bounds: $t")
        }
    }

    @Test
    fun `currentEmotionEnum defaults to CALM at NIGHT and NEUTRAL otherwise when unset`() {
        svc.setEmotion(null)
        assertEquals(PersonaEmotion.CALM, svc.currentEmotionEnum(TimeOfDay.NIGHT))
        assertEquals(PersonaEmotion.NEUTRAL, svc.currentEmotionEnum(TimeOfDay.MORNING))
        assertEquals(PersonaEmotion.NEUTRAL, svc.currentEmotionEnum(TimeOfDay.AFTERNOON))
        assertEquals(PersonaEmotion.NEUTRAL, svc.currentEmotionEnum(TimeOfDay.EVENING))
    }

    @Test
    fun `explicit setEmotion overrides TimeOfDay default`() {
        svc.setEmotion(PersonaEmotion.CHEERFUL)
        assertEquals(PersonaEmotion.CHEERFUL, svc.currentEmotionEnum(TimeOfDay.NIGHT))
        svc.setEmotion(null)
    }

    @Test
    fun `systemPrompt enforces German and no-markdown rule`() {
        val prompt = svc.systemPrompt(emotion = PersonaEmotion.NEUTRAL)
        assertTrue(prompt.contains("Deutsch"), "must instruct German output")
        assertTrue(prompt.contains("Kein Markdown") || prompt.contains("kein Markdown"), "must forbid markdown")
        assertTrue(prompt.contains("Hoshi"), "must mention persona name")
    }

    @Test
    fun `systemPrompt is short enough for E2B context window`() {
        val prompt = svc.systemPrompt(
            displayName = "Andre",
            availableRooms = listOf("wohnzimmer", "schlafzimmer", "küche", "bad", "büro"),
        )
        assertTrue(prompt.length < 2500, "system prompt is ${prompt.length} chars — too long for E2B")
    }

    @Test
    fun `systemPrompt rules come before context for recency-bias inversion`() {
        val prompt = svc.systemPrompt(emotion = PersonaEmotion.NEUTRAL)
        val rulesIdx = prompt.indexOf("REGELN:")
        val contextIdx = prompt.indexOf("KONTEXT:")
        assertTrue(rulesIdx in 0 until contextIdx, "rules must come before context (rules=$rulesIdx, context=$contextIdx)")
    }

    @Test
    fun `systemPrompt is byte-identical across all emotions for cache-prefix reuse`() {
        val prompts = PersonaEmotion.values().map { e ->
            e to svc.systemPrompt(
                emotion = e,
                displayName = "Andre",
                availableRooms = listOf("wohnzimmer", "küche"),
            )
        }
        val reference = prompts.first().second
        for ((emotion, prompt) in prompts) {
            assertEquals(
                reference, prompt,
                "systemPrompt($emotion) must equal systemPrompt(${prompts.first().first}) — emotion must not leak into prompt text",
            )
        }
    }

    @Test
    fun `systemPrompt drops emotion-specific directive cues`() {
        for (e in PersonaEmotion.values()) {
            val prompt = svc.systemPrompt(emotion = e)
            for (cue in listOf("Stimmung:", "// FOCUSED", "// CALM", "// WARM", "// CHEERFUL")) {
                assertTrue(
                    !prompt.contains(cue),
                    "systemPrompt($e) must not contain emotion cue '$cue' — would break cache-prefix",
                )
            }
        }
    }

    @Test
    fun `systemPrompt is deterministic across multiple invocations`() {
        val a = svc.systemPrompt(
            emotion = PersonaEmotion.NEUTRAL,
            displayName = "Andre",
            availableRooms = listOf("wohnzimmer", "küche"),
        )
        val b = svc.systemPrompt(
            emotion = PersonaEmotion.NEUTRAL,
            displayName = "Andre",
            availableRooms = listOf("wohnzimmer", "küche"),
        )
        assertEquals(a, b, "systemPrompt must be byte-identical across calls — KV-cache invariant")
    }

    @Test
    fun `systemPrompt enthaelt Anleitungs-Fewshot fuer Rezept-Klasse`() {
        val prompt = svc.systemPrompt(emotion = PersonaEmotion.NEUTRAL)
        assertTrue(prompt.contains("Wie macht man Rührei"), "Few-Shot 'Rührei' (Anleitung) fehlt")
        assertTrue(prompt.contains("Zwölf-Euro-Schein"), "Few-Shot 'Negation-aus-Liste' fehlt")
    }

    @Test
    fun `systemPrompt enthaelt weichen Anleitungs-Anker`() {
        val prompt = svc.systemPrompt(emotion = PersonaEmotion.NEUTRAL)
        assertTrue(prompt.contains("BEI ANLEITUNGEN"), "Anker für Anleitungs-Klasse fehlt")
        assertTrue(prompt.contains("Erzählerisch"), "Anleitungs-Stilvorgabe fehlt")
        assertTrue(prompt.contains("Mengen umgangssprachlich"), "Mengen-Regel fehlt — TTS-Hörbarkeit")
    }

    @Test
    fun `systemPrompt enthaelt natuerliches Wer-ist-X-Vorbild`() {
        val prompt = svc.systemPrompt(emotion = PersonaEmotion.NEUTRAL)
        assertTrue(prompt.contains("U: Wer ist Donald Duck?"), "Wissens-Few-Shot 'Wer ist Donald Duck?' fehlt")
        assertTrue(prompt.contains("Die Comic-Ente von Disney"), "Antwort muss konkretes Bild + Funke Haltung sein")
        assertTrue(
            !prompt.contains("alter Hase") && !prompt.contains("halt echt ein Klassiker"),
            "Gestelzte Floskeln dürfen nicht als Few-Shot-Vorbild dienen",
        )
    }

    @Test
    fun `Standard-Prompt seedet keine Slang-Tokens mehr und ankert Ton-Spiegelung positiv`() {
        // Gemessener Bug: der 4B-Brain echote „Alter"/„Bitch" unprovoziert, weil die
        // alte Regelzeile die Tokens auflistete. Positiver Anker schlägt Seed-Liste.
        val prompt = svc.systemPrompt(emotion = PersonaEmotion.NEUTRAL)
        assertTrue(!prompt.contains("moralisieren"), "alte Slang-Seed-Regel muss weg sein")
        assertTrue(!prompt.contains("kurz mitspielen"), "alte Slang-Seed-Regel muss weg sein")
        assertTrue(prompt.contains("nur zurück, wenn die Person anfängt"), "positiver Slang-Anker fehlt")
    }

    @Test
    fun `Wissensfragen-Regel ist warm und auf Augenhoehe, mit Unsicherheits-Gegenbeispiel`() {
        // Gemessener Bug: „souverän/Lexikon-Stil" befahl Attitüde ohne Wärme → Herablassung
        // („das ist einfach, wenn man die Grundlagen kennt"). Fix: Augenhöhe + ehrliches Nachfragen.
        val prompt = svc.systemPrompt(emotion = PersonaEmotion.NEUTRAL)
        assertTrue(!prompt.contains("Lexikon-Stil"), "belehrende Alt-Regel muss weg sein")
        assertTrue(prompt.contains("Augenhöhe") && prompt.contains("nie belehrend"), "Wärme-/Augenhöhe-Anker fehlt")
        assertTrue(prompt.contains("soll ich nachschauen"), "Unsicherheits-Gegenbeispiel (ehrliches Nachfragen) fehlt")
    }

    @Test
    fun `systemPrompt bleibt unter Cache-Limit auch mit Anti-Lexikon-Shot`() {
        val prompt = svc.systemPrompt(
            displayName = "Andre",
            availableRooms = listOf("wohnzimmer", "schlafzimmer", "küche", "bad", "büro"),
        )
        assertTrue(prompt.length < 2500, "system prompt is ${prompt.length} chars — Cache-Limit gesprengt")
    }

    // ── Multilingual-Sprachsteuerung: die Antwortsprache wird EXPLIZIT instruiert ──

    @Test
    fun `systemPrompt language EN instructs English and frames warmly`() {
        val prompt = svc.systemPrompt(emotion = PersonaEmotion.NEUTRAL, language = Language.EN)
        assertTrue(prompt.contains("Always answer in English"), "EN-Prompt muss die englische Sprach-Instruktion tragen")
        assertTrue(prompt.contains("Hoshi"), "muss die Persona benennen")
        // Warm/kumpelhaft, nicht roboterhaft:
        assertTrue(prompt.contains("buddy") || prompt.contains("warm"), "EN-Persona muss warm/kumpelhaft gerahmt sein")
        // Wirklich englisch — der DE-Prefix darf NICHT in den EN-Prompt lecken:
        assertTrue(!prompt.contains("Antworte IMMER auf Deutsch"), "EN-Prompt darf nicht ins Deutsche kippen")
    }

    @Test
    fun `systemPrompt language DE keeps the byte-fixed German prefix intact`() {
        val prompt = svc.systemPrompt(emotion = PersonaEmotion.NEUTRAL, language = Language.DE)
        assertTrue(prompt.contains("Antworte IMMER auf Deutsch"), "DE-Prompt muss die deutsche Sprach-Instruktion tragen")
        assertTrue(prompt.contains("Du bist Hoshi"), "byte-fester DE-Prefix muss intakt bleiben")
        assertTrue(!prompt.contains("Always answer in English"), "DE-Prompt darf keine EN-Instruktion tragen")
    }

    @Test
    fun `systemPrompt default language equals DE byte-for-byte`() {
        // Additiv: die Sprach-Steuerung darf das Default-(DE-)Verhalten NICHT verschieben.
        val default = svc.systemPrompt(displayName = "Andre", availableRooms = listOf("küche"))
        val explicitDe = svc.systemPrompt(displayName = "Andre", availableRooms = listOf("küche"), language = Language.DE)
        assertEquals(explicitDe, default, "Default muss byte-identisch zu language=DE sein")
    }

    @Test
    fun `systemPrompt does not leak TimeOfDay tokens`() {
        val prompt = svc.systemPrompt(
            emotion = PersonaEmotion.NEUTRAL,
            displayName = "Andre",
            availableRooms = listOf("wohnzimmer"),
        )
        for (token in listOf("Es ist Morgen", "Es ist Nachmittag", "Es ist Abend", "Es ist Nacht")) {
            assertTrue(!prompt.contains(token), "system prompt must not contain TimeOfDay hint '$token'")
        }
    }

    // ── Persona-Charakter-Steuerung: vier byte-stabile Charaktere (Standard/Kumpel/Knapp/Ruhig) ──

    private val rooms = listOf("wohnzimmer", "schlafzimmer", "küche", "bad", "büro")

    @Test
    fun `systemPrompt Persona STANDARD ist byte-identisch zur Emotion-Overload (DE)`() {
        val viaPersona = svc.systemPrompt(persona = Persona.STANDARD, displayName = "Andre", availableRooms = rooms)
        val viaEmotion = svc.systemPrompt(displayName = "Andre", availableRooms = rooms)
        assertEquals(viaEmotion, viaPersona, "STANDARD muss byte-identisch zum heutigen DE-Prompt sein")
    }

    @Test
    fun `systemPrompt Persona STANDARD ist byte-identisch zur Emotion-Overload (EN)`() {
        val viaPersona = svc.systemPrompt(persona = Persona.STANDARD, displayName = "Andre", availableRooms = rooms, language = Language.EN)
        val viaEmotion = svc.systemPrompt(displayName = "Andre", availableRooms = rooms, language = Language.EN)
        assertEquals(viaEmotion, viaPersona, "STANDARD muss byte-identisch zum heutigen EN-Prompt sein")
    }

    @Test
    fun `jede Persona liefert einen DISTINKTEN, byte-stabilen Body im Budget (DE und EN)`() {
        for (lang in listOf(Language.DE, Language.EN)) {
            val bodies = Persona.entries.associateWith { p ->
                svc.systemPrompt(persona = p, displayName = "Andre", availableRooms = rooms, language = lang)
            }
            // distinkt: alle vier Bodies unterscheiden sich.
            assertEquals(Persona.entries.size, bodies.values.toSet().size, "Personas müssen distinkte Bodies liefern ($lang)")
            for ((p, body) in bodies) {
                // byte-stabil: zweiter Aufruf identisch (KV-Cache-Prefix-Invariante).
                val again = svc.systemPrompt(persona = p, displayName = "Andre", availableRooms = rooms, language = lang)
                assertEquals(body, again, "Persona $p Body muss byte-stabil über Turns sein ($lang)")
                // Budget: bleibt unter dem Cache-Limit.
                assertTrue(body.length < 2500, "Persona $p Body ist ${body.length} Zeichen — über Budget ($lang)")
            }
        }
    }

    @Test
    fun `Persona swappt die Tonzeile (DE) und bleibt distinkt zur Standard-Tonzeile`() {
        val standard = svc.systemPrompt(persona = Persona.STANDARD, displayName = "Andre")
        val kumpel = svc.systemPrompt(persona = Persona.KUMPEL, displayName = "Andre")
        val knapp = svc.systemPrompt(persona = Persona.KNAPP, displayName = "Andre")
        val ruhig = svc.systemPrompt(persona = Persona.RUHIG, displayName = "Andre")
        assertTrue(standard.contains("warm, locker, kumpelhaft"), "STANDARD-Tonzeile fehlt")
        assertTrue(kumpel.contains("flapsig und spielfreudig"), "KUMPEL-Tonzeile fehlt")
        assertTrue(knapp.contains("wortkarg und sachlich"), "KNAPP-Tonzeile fehlt")
        assertTrue(ruhig.contains("sanft und gelassen"), "RUHIG-Tonzeile fehlt")
        // SWAP, kein Append: die Standard-Tonzeile darf NICHT in den anderen Bodies stehen.
        for (other in listOf(kumpel, knapp, ruhig)) {
            assertTrue(!other.contains("warm, locker, kumpelhaft"), "Persona swappt die Tonzeile, hängt nicht an")
        }
        // Die unveränderlichen Strukturblöcke bleiben in ALLEN Personas erhalten.
        for (body in listOf(standard, kumpel, knapp, ruhig)) {
            assertTrue(body.contains("REGELN:") && body.contains("KONTEXT:"), "REGELN/KONTEXT-Gerüst muss bleiben")
            assertTrue(body.contains("Antworte IMMER auf Deutsch"), "Sprach-Instruktion muss in jeder Persona stehen")
        }
        assertNotEquals(standard, kumpel)
    }

    @Test
    fun `moodFor mappt Personas auf ihre Default-Stimmung (STANDARD = Tageszeit-Default)`() {
        assertEquals(PersonaEmotion.CHEERFUL, svc.moodFor(Persona.KUMPEL))
        assertEquals(PersonaEmotion.FOCUSED, svc.moodFor(Persona.KNAPP))
        assertEquals(PersonaEmotion.CALM, svc.moodFor(Persona.RUHIG))
        // STANDARD ohne feste Stimmung → die Tageszeit-abgeleitete Default-Emotion.
        assertEquals(svc.currentEmotionEnum(), svc.moodFor(Persona.STANDARD))
        // Die Persona-Temperaturen entsprechen der Spezifikation: KUMPEL hoch, KNAPP/RUHIG niedrig.
        assertTrue(svc.temperatureFor(svc.moodFor(Persona.KUMPEL)) > svc.temperatureFor(PersonaEmotion.NEUTRAL))
        assertTrue(svc.temperatureFor(svc.moodFor(Persona.KNAPP)) < svc.temperatureFor(PersonaEmotion.NEUTRAL))
        assertTrue(svc.temperatureFor(svc.moodFor(Persona.RUHIG)) < svc.temperatureFor(PersonaEmotion.NEUTRAL))
    }
}
