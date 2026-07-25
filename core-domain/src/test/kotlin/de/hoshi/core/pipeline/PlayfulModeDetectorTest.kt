package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatMessage
import de.hoshi.core.dto.Language
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **Der Erkenner-Test des Spiel-Registers** (Vorfall „Kuh mit Hose", 2026-07-25).
 *
 * Zwei Richtungen, ungleich gewichtet:
 *  - **Treffer** — die Formen, in denen Menschen ein Gedankenspiel eröffnen (DE+EN).
 *  - **KEIN Treffer** — und das ist die teure Richtung: eine echte Wissensfrage darf
 *    NIE als Spiel gelten, sonst erfindet Hoshi statt zu erden. Darum sind die
 *    Gegen-Tests hier in der Überzahl.
 */
class PlayfulModeDetectorTest {

    private val detector = PlayfulModeDetector(enabled = true)

    // ── OFF ist byte-neutral ─────────────────────────────────────────────────────

    @Test
    fun `DISABLED erkennt nie ein Spiel - auch nicht den Vorfall selbst`() {
        assertFalse(
            PlayfulModeDetector.DISABLED.detect("Stell dir vor, eine Kuh zieht eine Hose an."),
            "Default OFF ⇒ konstant false ⇒ byte-neutral",
        )
    }

    // ── Treffer: explizite hypothetische Marker (DE) ─────────────────────────────

    @Test
    fun `deutsche Spiel-Marker oeffnen das Register`() {
        listOf(
            "Stell dir vor, eine Kuh. Wie zieht sie ihre Hose an?",
            "Stell dir das mal vor: der Mond wäre aus Käse.",
            "Stellen wir uns mal vor, es gäbe keine Schwerkraft.",
            "Was wäre, wenn Menschen fliegen könnten?",
            "Was wäre wenn morgen alle Uhren rückwärts laufen?",
            "Angenommen, du wärst ein Toaster — was wäre dein Lieblingsbrot?",
            "Mal angenommen, ich hätte drei Arme.",
            "Nehmen wir mal an, der Fluss fließt bergauf.",
            "Rein hypothetisch: könntest du auf dem Mars wohnen?",
            "Kleines Gedankenexperiment für dich.",
        ).forEach { text ->
            assertTrue(detector.detect(text), "sollte als Spiel gelten: „$text\"")
        }
    }

    // ── Treffer: explizite hypothetische Marker (EN) ─────────────────────────────

    @Test
    fun `englische Spiel-Marker oeffnen das Register`() {
        listOf(
            "Imagine a cow. How does she put on her trousers?",
            "What if the sky was green?",
            "Suppose you had to pick one colour forever.",
            "Let's say I could talk to plants.",
            "Just for fun: which planet would you live on?",
            "Hypothetically, could a fish ride a bicycle?",
            "Here is a thought experiment for you.",
            "Pretend you are a lighthouse keeper.",
        ).forEach { text ->
            assertTrue(detector.detect(text), "should count as play: „$text\"")
        }
    }

    // ── Treffer: Absurditäts-Paar (Tier + menschliches Objekt), ohne Marker ──────

    @Test
    fun `Absurditaets-Paar oeffnet das Register auch ohne Marker`() {
        assertTrue(detector.detect("Wie zieht eine Kuh ihre Hose an?"))
        assertTrue(detector.detect("Kann eine Katze Fahrrad fahren, wenn sie einen Helm und eine Hose trägt?"))
        assertTrue(detector.detect("Does a penguin wear a necktie to work?"))
    }

    // ── DIE TEURE RICHTUNG: echte Wissensfragen bleiben Wissensfragen ────────────

    @Test
    fun `echte Wissensfragen gelten NIE als Spiel`() {
        listOf(
            "Wie hoch ist der Eiffelturm?",
            "Wie wird das Wetter morgen?",
            "Wer war Konrad Adenauer?",
            "Woher kommt der Name Mittwoch?",
            "Wie viele Zähne hat eine Kuh?",
            "Wie schnell kann ein Pferd laufen?",
            "Warum bellen Hunde nachts?",
            "In welchem Jahr wurde die Mauer gebaut?",
            "How tall is the Eiffel Tower?",
            "What is the weather like tomorrow?",
            "How many teeth does a cow have?",
            "Who wrote Faust?",
        ).forEach { text ->
            assertFalse(detector.detect(text), "darf NIE als Spiel gelten: „$text\"")
        }
    }

    @Test
    fun `Smalltalk bleibt Smalltalk - kein Spiel`() {
        listOf("Hallo, wie geht es dir?", "Kurz: alles ok bei dir?", "How are you doing?")
            .forEach { assertFalse(detector.detect(it), "Smalltalk ist kein Spiel: „$it\"") }
    }

    @Test
    fun `Ernst-Blocker schlagen jeden Marker - eine Bedeutungsfrage bleibt Wissensfrage`() {
        assertFalse(detector.detect("Was bedeutet ‚hypothetisch'?"))
        assertFalse(detector.detect("Was heißt ‚imagine' auf Deutsch?"))
        assertFalse(detector.detect("What does 'suppose' mean?"))
        assertFalse(detector.detect("Übersetze ‚imagine' bitte."))
    }

    @Test
    fun `angenommen mitten im Satz ist KEIN Marker`() {
        assertFalse(
            detector.detect("Wurde der Antrag angenommen?"),
            "„angenommen\" heißt mitten im Satz etwas völlig anderes",
        )
        assertFalse(detector.detect("Home Assistant hat den Befehl angenommen, oder?"))
    }

    @Test
    fun `mehrdeutige Tier-Objekt-Woerter reissen keine Fehlalarme auf`() {
        assertFalse(
            detector.detect("Why does a dog pant when it is hot?"),
            "„pants\" als Verb bei Hunden ist bewusst NICHT im Objekt-Vokabular",
        )
        assertFalse(detector.detect("Wie alt wird ein Hund im Schnitt?"))
    }

    /**
     * Live im Test gefundene Homographen: DE und EN teilen sich EINE Vokabel-Liste,
     * darum darf kein Objekt-Wort in der anderen Sprache etwas ganz anderes heißen.
     * `hat` (EN Kopfbedeckung == dt. Hilfsverb) ließ „Wie viele Zähne HAT eine Kuh?"
     * als Spiel gelten; `hut` (dt. Kopfbedeckung == engl. Hütte) dasselbe Muster
     * andersherum. Beide sind deshalb aus dem Objekt-Vokabular entfernt.
     */
    @Test
    fun `Homographen zwischen DE und EN reissen keine Fehlalarme auf`() {
        assertFalse(detector.detect("Wie viele Beine hat ein Pferd?"), "„hat\" ist ein dt. Hilfsverb, keine Mütze")
        assertFalse(detector.detect("Does a dog live in a hut?"), "„hut\" ist engl. eine Hütte, keine Kopfbedeckung")
    }

    // ── Faden halten: der Folge-Turn OHNE eigenen Marker ─────────────────────────

    private val opener =
        "Stell dir vor, eine Kuh. Wie zieht sie ihre Hose an? Über die vorderen Pfoten oder über die hinteren Pfoten?"

    private fun historyAfterOpener(answer: String): List<ChatMessage> = listOf(
        ChatMessage("user", opener),
        ChatMessage("assistant", answer),
    )

    @Test
    fun `Folge-Turn mit Token-Anker haelt den Faden`() {
        val history = historyAfterOpener("Ich glaube, die hinteren Pfoten sind dafür besser geeignet.")
        assertTrue(
            detector.detect(
                "Die brauchst du schon um rum zu stehen, aber die hinteren ja auch, sonst kann sie ja nicht stehen.",
                history,
            ),
            "„hinteren\" ist ein Inhalts-Token des Eröffners ⇒ derselbe Faden",
        )
    }

    @Test
    fun `Folge-Turn OHNE Anker faellt aus dem Spiel - echte Frage bleibt geerdet`() {
        val history = historyAfterOpener("Ich glaube, die hinteren Pfoten sind dafür besser geeignet.")
        assertFalse(
            detector.detect("Wie hoch ist der Eiffelturm?", history),
            "kein gemeinsames Inhalts-Token ⇒ Spiel aus ⇒ die Frage wird normal geerdet",
        )
        assertFalse(detector.detect("Wie wird das Wetter morgen?", history))
    }

    @Test
    fun `ohne Verlauf gibt es keine Faden-Fortsetzung`() {
        assertFalse(
            detector.detect("Die brauchst du schon um rum zu stehen, aber die hinteren ja auch."),
            "ohne Eröffner im Verlauf trägt der Folge-Satz nichts Spielerisches",
        )
    }

    @Test
    fun `ein ausdruecklicher Ausstieg beendet den Faden sofort`() {
        val history = historyAfterOpener("Die hinteren Pfoten, würde ich sagen.")
        assertFalse(
            detector.detect("Mal im Ernst: hat eine Kuh vorne wirklich Pfoten?", history),
            "„im Ernst\" beendet das Spiel, auch wenn Anker-Tokens vorkommen",
        )
        assertFalse(detector.detect("Seriously, how many hooves does a cow have?", historyAfterOpener("Back ones.")))
    }

    @Test
    fun `nur User-Turns koennen ein Spiel eroeffnen`() {
        val history = listOf(
            ChatMessage("assistant", "Stell dir vor, eine Kuh mit einer Hose!"),
            ChatMessage("user", "Wie viele Zähne hat eine Kuh?"),
        )
        assertFalse(
            detector.detect("Und wie viele Mägen hat sie?", history),
            "Hoshis eigener Satz darf kein Spiel eröffnen — sonst hält sich der Modus selbst am Leben",
        )
    }

    // ── Prompt-Hinweis ───────────────────────────────────────────────────────────

    @Test
    fun `der Spiel-Hinweis folgt der Turn-Sprache und traegt den Ehrlichkeits-Kern`() {
        assertTrue(PlayfulModeDetector.playfulHint(Language.DE).contains("SPIELMODUS"))
        assertTrue(PlayfulModeDetector.playfulHint(Language.EN).contains("PLAY MODE"))
        assertTrue(
            PlayfulModeDetector.playfulHint(Language.DE).contains("Tatsache"),
            "fabulieren ja — als Tatsache ausgeben nie",
        )
        assertTrue(PlayfulModeDetector.playfulHint(Language.EN).contains("fact"))
    }
}
