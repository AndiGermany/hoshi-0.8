package de.hoshi.core.pipeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit-Wand der VIER deterministischen Recognizer der Lookup-Intent-Naht:
 *  - [LookupIntentRecognizer] — die explizite „schau online nach"-Bitte (DE+EN,
 *    STT-tolerant), konservativ genug, dass reine Wissensfragen ÜBER das Netz NIE
 *    triggern — plus [LookupIntentRecognizer.extractInlineQuery] (Andi-Fix
 *    2026-07-20): trägt die Bitte selbst eine Frage, wird der Rest NACH dem
 *    abgestreiften Intent-Präfix als Query extrahiert, statt ehrlich
 *    nachzufragen.
 *  - [ResearchIntentRecognizer] — die ENGERE „recherchiere/recherche"-Bitte
 *    (Andi-Auftrag 2026-07-19), eine strenge Teilmenge von [LookupIntentRecognizer],
 *    die das Recherche-Modell statt Nano wählt — false-positive-avers gegen
 *    „Recherche" als bloßes Substantiv in einer Aussage.
 *  - [BrainAbstainRecognizer] — das ehrliche „das weiß ich nicht" in einer
 *    Brain-Antwort, eng gefasst gegen weiche Hedges.
 *  - [ConsentRecognizer] — die KURZE, unzweideutige Zustimmung als GANZE
 *    Äußerung (Andi-Auftrag 2026-07-20, Naht C), konservativ WHOLE-utterance
 *    (kein Substring) gegen Sätze, die nur zufällig „ja" enthalten.
 */
class LookupIntentRecognizerTest {

    // ── LookupIntentRecognizer — POSITIV ─────────────────────────────────────────
    @Test
    fun `explizite Nachschlag-Bitten triggern (DE + EN, STT-tolerant)`() {
        listOf(
            "Schau bitte online nach.",
            "Schaust du bitte online nach?", // der Live-Befund-Satz
            "guck im Internet",
            "Schlag das online nach",
            "recherchier das",
            "recherchiere das mal",
            "look it up online",
            "search online",
            "kannst du das online nachschauen",
            "such das mal im netz",
            "Recherche dazu", // Andi-Auftrag 2026-07-19: Nomen-Zeige-Kombination
        ).forEach { assertTrue(LookupIntentRecognizer.matches(it), "sollte matchen: »$it«") }
    }

    // ── LookupIntentRecognizer — NEGATIV (konservativ) ───────────────────────────
    @Test
    fun `Wissensfragen ueber das Netz und Alltag triggern NIE`() {
        listOf(
            "", "  ",
            "Wie funktioniert das Internet?",
            "Was ist Online-Banking?",
            "ja", "nein", "okay",
            "Wie hoch ist der Eiffelturm?",
            "Mach das Licht an",
            "Gibt es einen 12,50-Euro-Schein?",
            "Ich war gestern online einkaufen", // kein Nachschau-Verb
        ).forEach { assertFalse(LookupIntentRecognizer.matches(it), "sollte NICHT matchen: »$it«") }
    }

    // ── LookupIntentRecognizer — POSITIV, EN (Andi-Vorfall 2026-07-25) ───────────
    @Test
    fun `englische Nachschlag-Bitten triggern genauso wie deutsche`() {
        listOf(
            "Take a look online for a recept of pizza.", // Andis Satz WÖRTLICH
            "Take a look online.",
            "Can you look that up?",
            "look it up",
            "search the web for pizza recipes",
            "Browse the internet for news.",
            "google it",
            "Can you google the opening hours?",
            "find out online when GTA 6 is released",
            "check online whether it rains tomorrow",
        ).forEach { assertTrue(LookupIntentRecognizer.matches(it), "sollte matchen: »$it«") }
    }

    // ── LookupIntentRecognizer — NEGATIV, EN-Fehlalarm-Wand ──────────────────────
    //    „look"/„check"/„search" sind häufige Alltagswörter: OHNE Netz-Marker
    //    dürfen sie NIE eine Nachschlag-Bitte sein (dieselbe Regel wie DE „schau").
    @Test
    fun `englische Alltagssaetze ohne Netz-Marker triggern NIE`() {
        listOf(
            "I'll check the oven.",
            "look at this picture",
            "Look, that's funny!",
            "Can you check if the door is locked?",
            "I'm searching for my keys.",
            "Take a look at the kitchen light.",
            "What is Google?",
            "Google is a big company.",
            "Was macht Google online?", // Aussage ÜBER die Firma, trotz Scope-Wort
            "How does the internet work?",
            "I find the internet fascinating.", // „find" allein ist bewusst KEIN Verb
        ).forEach { assertFalse(LookupIntentRecognizer.matches(it), "sollte NICHT matchen: »$it«") }
    }

    // ── extractInlineQuery — POSITIV, EN (Andi-Vorfall 2026-07-25) ───────────────
    //    Der Kern von Bug 1b: das EN-Intent-Präfix („Take a look online for a")
    //    wurde nie abgestreift, weil der Scanner schon am ersten Token „Take"
    //    abbrach — die im Satz stehende Frage ging verloren.
    @Test
    fun `englisches Intent-Praefix wird abgestreift, das Thema bleibt`() {
        assertEquals(
            "recept of pizza",
            LookupIntentRecognizer.extractInlineQuery("Take a look online for a recept of pizza."),
            "Andis Satz woertlich (inkl. seines Tippfehlers) — das Thema steht im Satz",
        )
        assertEquals(
            "pizza recipe",
            LookupIntentRecognizer.extractInlineQuery("Take a look online for a pizza recipe"),
        )
        assertEquals(
            "the opening hours of the museum",
            LookupIntentRecognizer.extractInlineQuery("Can you look online for the opening hours of the museum"),
            "Praefix mit Modal + Subjekt (»can you«) wird mit abgestreift",
        )
        assertEquals(
            "the tallest building in the world",
            LookupIntentRecognizer.extractInlineQuery("search the web for the tallest building in the world"),
            "»the web« (Artikel VOR Scope-Wort) gehoert zum Praefix, das zweite »the« zur Query",
        )
        assertEquals(
            "when GTA 6 is released",
            LookupIntentRecognizer.extractInlineQuery("find out online when GTA 6 is released"),
            "zweiwortiges »find out«",
        )
    }

    // ── extractInlineQuery — NEGATIV, EN: reine Bitte ohne Thema ⇒ null ──────────
    @Test
    fun `englische Bitte ohne Thema liefert extractInlineQuery null`() {
        listOf(
            "Take a look online.", // der Vorfall-Einstieg ohne Thema
            "look it up online",
            "Can you look that up?",
            "search the web please",
            "check it out online",
        ).forEach {
            assertNull(
                LookupIntentRecognizer.extractInlineQuery(it),
                "reine Bitte ohne Sachinhalt darf keinen Inline-Rest liefern: »$it«",
            )
        }
    }

    // ── extractInlineQuery — POSITIV (Andi-Fix 2026-07-20, Live-Repro) ───────────
    @Test
    fun `traegt die Bitte selbst eine Frage, wird der Rest nach dem Intent-Praefix extrahiert`() {
        assertEquals(
            "wann GTA 6 erscheint",
            LookupIntentRecognizer.extractInlineQuery("Schau bitte online nach, wann GTA 6 erscheint."),
            "T3-Satz woertlich — die Frage steckt im Satz, keine Rueckfrage noetig",
        )
        assertEquals(
            "wie hoch der Eiffelturm ist",
            LookupIntentRecognizer.extractInlineQuery("recherchiere online, wie hoch der Eiffelturm ist"),
            "Recherche-Praefix wird identisch abgestreift (Andi: gleiche Extraktion fuer den Recherche-Pfad)",
        )
        assertEquals(
            "wie warm es morgen wird",
            LookupIntentRecognizer.extractInlineQuery("Guck doch mal im Internet nach, wie warm es morgen wird"),
            "mehrere Fuellwoerter (doch/mal) werden mit abgestreift",
        )
    }

    // ── extractInlineQuery — NEGATIV (kein Rest ⇒ heutiges Verhalten) ───────────
    @Test
    fun `ohne brauchbaren Rest liefert extractInlineQuery null - heutiges Verhalten bleibt`() {
        listOf(
            "schau mal online nach", // kein Rest ueberhaupt (Andi-Beispiel)
            "Schau bitte online nach.",
            "recherchiere online",
            "schau online nach, ok", // Rest < 8 Zeichen
            "Kannst du das online nachschauen", // Trigger nicht praefix-anchored (steht nicht am Anfang)
            "", "   ",
        ).forEach {
            assertNull(LookupIntentRecognizer.extractInlineQuery(it), "sollte null liefern (kein Inline-Rest): »$it«")
        }
    }

    // ── extractInlineQuery — NEGATIV, Konsens-Kontext-Carry (Andi-Live-Repro
    //    2026-07-20 Teil B): eine reine Lookup-CONSENT-Form ohne eigenen
    //    Sachinhalt darf NIE ihr eigenes Subjekt-Pronomen ("du"/"ihr"/...) als
    //    vermeintlichen Inline-Rest zurückgeben — das führte live dazu, dass die
    //    META-FRAGE selbst ("du online nach?") eskaliert wurde, statt auf die
    //    vorherige Sachfrage/Rückfrage durchzufallen (s. TurnOrchestrator §c). ──
    @Test
    fun `reine Consent-Form mit Subjekt-Pronomen liefert extractInlineQuery null`() {
        listOf(
            "Schaust du online nach?", // T-Repro woertlich (Andi, 20.07 ~22 Uhr)
            "schaust du online nach",
            "Schaut ihr online nach?",
            "Guckst du mal nach?",
        ).forEach {
            assertNull(
                LookupIntentRecognizer.extractInlineQuery(it),
                "reine Consent-Form ohne Sachinhalt darf keinen Inline-Rest liefern: »$it«",
            )
        }
    }

    // ── ResearchIntentRecognizer — POSITIV (Andi-Auftrag 2026-07-19) ─────────────
    @Test
    fun `explizite Recherche-Imperative triggern (Verb-Stamm + Nomen-Zeige-Kombination, Gross-Klein, eingebettet)`() {
        listOf(
            "recherchiere",
            "Recherchier das",
            "recherchiere online",
            "Online recherchieren",
            "recherche dazu",
            "Recherche hierzu",
            "recherche davon",
            "recherche darüber",
            "RECHERCHIERE DAS MAL ONLINE", // Gross-Klein
            "Kannst du bitte kurz dazu recherchieren?", // eingebetteter Satz, Verb-Stamm
            "Ich möchte gern, dass du kurz Recherche dazu machst.", // eingebettet, Nomen-Form
        ).forEach { assertTrue(ResearchIntentRecognizer.matches(it), "sollte matchen: »$it«") }
    }

    @Test
    fun `jeder Recherche-Treffer ist auch ein Lookup-Intent-Treffer (Teilmengen-Vertrag)`() {
        listOf(
            "recherchiere", "recherchiere online", "recherche dazu", "recherche darüber",
        ).forEach {
            assertTrue(
                LookupIntentRecognizer.matches(it),
                "ResearchIntentRecognizer ist laut KDoc eine strenge Teilmenge — muss auch hier matchen: »$it«",
            )
        }
    }

    // ── ResearchIntentRecognizer — NEGATIV (false-positive-avers) ────────────────
    @Test
    fun `bloss Recherche als Substantiv in einer Aussage triggert NIE`() {
        listOf(
            "", "  ",
            "Meine Recherche hat ergeben, dass es keinen 12,50-Euro-Schein gibt.",
            "Ich habe schon einige Recherche betrieben.",
            "Die Recherche war aufwendig.",
            "Was ist eine Recherche eigentlich?",
            // Artikel/Possessivpronomen direkt vor "Recherche" + Zeige-Wort danach —
            // GENAU der Fall, den die Determiner-Ausschluss-Regel abfangen muss:
            // eine AUSSAGE über eine vorhandene Recherche, keine Bitte.
            "Die Recherche dazu war aufwendig.",
            "Seine Recherche darüber überraschte alle.",
            "Meine Recherche dazu ist noch nicht fertig.",
        ).forEach { assertFalse(ResearchIntentRecognizer.matches(it), "sollte NICHT matchen: »$it«") }
    }

    // ── BrainAbstainRecognizer — POSITIV ─────────────────────────────────────────
    @Test
    fun `ehrliches Passen wird erkannt (DE + EN, Apostrophe-tolerant)`() {
        listOf(
            "Das weiß ich nicht sicher.",
            "Ehrlich, das weiß ich leider nicht.",
            "Da bin ich mir nicht sicher.",
            "Keine Ahnung, ob es das gibt.",
            "Das kann ich dir nicht sagen.",
            "I don't know that one.",
            "I'm not sure about that.",
            "No idea, honestly.",
        ).forEach { assertTrue(BrainAbstainRecognizer.isAbstain(it), "sollte Abstain sein: »$it«") }
    }

    // ── BrainAbstainRecognizer — POSITIV, Wortstellungs-Varianten (Andi-Live-Repro
    //    2026-07-20: „…wann GTA 6 auf den Markt kommt?" ⇒ „**Ich weiß nicht genau.**
    //    Das ist noch nicht festgemacht." verfehlte JEDEN bisherigen Marker, weil
    //    dort das Subjekt VOR statt NACH dem Verb steht.) ──────────────────────────
    @Test
    fun `Wortstellungs-Varianten mit vorangestelltem Subjekt werden erkannt (T1-Antwort woertlich)`() {
        listOf(
            "**Ich weiß nicht genau.** Das ist noch nicht festgemacht.", // T1-Antwort wörtlich
            "Ich weiß nicht genau.",
            "Ich weiss nicht genau, wann das erscheint.",
            "Ich weiß es nicht.",
            "Ich weiss es nicht.",
            "Ich weiß es nicht genau.",
        ).forEach { assertTrue(BrainAbstainRecognizer.isAbstain(it), "sollte Abstain sein: »$it«") }
    }

    // ── BrainAbstainRecognizer — POSITIV, Testprotokoll 20.07 ~1:00 (Wurzel a):
    //    das lokale Brain umging die bisherigen Abstain-Marker deterministisch mit
    //    genau diesen drei Umgehungs-Stilen — jetzt alle drei erkannt. ────────────
    @Test
    fun `die drei Umgehungs-Formulierungen aus dem Testprotokoll 20-07 werden als Abstain erkannt`() {
        listOf(
            "Hab nur gehört...", // Hörensagen ohne Fortsetzung (Ellipse/Satzende)
            "Gestern war doch kein Rennen, oder?", // Rueckfrage-Ausweich statt Antwort
            "Keine Echtzeitdaten vorrätig — frag lieber einen Börsenexperten.", // neue Marker-Klasse
        ).forEach { assertTrue(BrainAbstainRecognizer.isAbstain(it), "sollte Abstain sein (Testprotokoll 20.07): »$it«") }
    }

    // ── BrainAbstainRecognizer — NEGATIV (keine weichen Hedges/echte Antworten) ──
    @Test
    fun `echte Antworten und weiche Hedges sind kein Abstain`() {
        listOf(
            "", "   ",
            "Der Eiffelturm ist 330 Meter hoch.",
            "Ich glaube, es sind ungefähr 300 Meter.",
            "Das ist eine gute Frage!",
            "Es gibt keinen 12,50-Euro-Schein.", // definitive Antwort, kein Passen
        ).forEach { assertFalse(BrainAbstainRecognizer.isAbstain(it), "sollte KEIN Abstain sein: »$it«") }
    }

    // ── BrainAbstainRecognizer — NEGATIV, False-Positive-Guards der drei NEUEN
    //    Testprotokoll-Marker (20.07): ein Hörensagen-Hedge MIT echtem Inhalt
    //    danach (Komma-Schutzplanke), ein "kein" ohne die "…, oder"-Bounce-Form,
    //    und "aktuelle Zahlen" (kein "Daten"-Substring) duerfen NIE triggern. ────
    @Test
    fun `Nahe-Formulierungen der neuen Testprotokoll-Marker sind KEIN Abstain`() {
        listOf(
            // Komma direkt nach "gehört" ⇒ Nebensatz mit echtem Inhalt, kein Passen.
            "Ich hab nur gehört, dass der Eiffelturm 330 Meter hoch ist, das stimmt tatsächlich.",
            // "kein" ohne die "..., oder"-Ruecken-Frage-Form ⇒ normale Aussage.
            "Er hatte gestern kein Fieber mehr und ging wieder zur Arbeit.",
            // "aktuellen Zahlen" ist kein "aktuellen/echtzeit Daten"-Substring.
            "Ich hab die aktuellen Zahlen gerade parat: der Kurs steht bei 120 Euro.",
        ).forEach { assertFalse(BrainAbstainRecognizer.isAbstain(it), "sollte KEIN Abstain sein: »$it«") }
    }

    // ── BrainAbstainRecognizer — NEGATIV, False-Positive-Guard der neuen
    //    Wortstellungs-Varianten: „ich weiß nicht, ob …" als bloßer HEDGE
    //    innerhalb einer echten, inhaltlichen Antwort (Komma leitet einen
    //    Nebensatz ein, kein vollständiges Passen) darf NIE als Abstain gelten. ──
    @Test
    fun `ich weiss nicht als Hedge in einer echten Antwort ist KEIN Abstain`() {
        listOf(
            "Ich weiß nicht, ob dir das reicht — hier die Fakten: der Eiffelturm ist 330 Meter hoch.",
            "Ich weiss nicht, ob das schon feststeht, aber der aktuelle Stand ist Mai 2026.",
        ).forEach { assertFalse(BrainAbstainRecognizer.isAbstain(it), "sollte KEIN Abstain sein (Hedge, kein Passen): »$it«") }
    }

    // ── ConsentRecognizer — POSITIV (kurze, unzweideutige Zustimmung) ────────────
    @Test
    fun `kurze Zustimmungen als GANZE Aeusserung triggern (DE + EN)`() {
        listOf(
            "ja", "Ja", "JA", "ja bitte", "ja gerne", "gerne",
            "mach das", "mach mal", "bitte",
            "ok", "okay", "jo", "jap", "klar",
            "yes", "sure",
            " ja ", "ja!", "ja.", "Ja?", // Groß-/Klein, Satzzeichen, Whitespace
        ).forEach { assertTrue(ConsentRecognizer.matches(it), "sollte matchen: »$it«") }
    }

    // ── ConsentRecognizer — NEGATIV (konservativ, WHOLE-utterance) ───────────────
    @Test
    fun `Saetze die nur ja enthalten oder zu lang sind triggern NIE`() {
        listOf(
            "", "  ",
            "ja aber warum", // der KDoc-Beispielfall — enthält "ja", ist aber ein Satz
            "ja, aber was ist mit morgen?",
            "nein",
            "ja klar okay", // zu viele Tokens
            "ich glaube ja",
            "na ja",
            "vielleicht",
            "Wie hoch ist der Eiffelturm?",
        ).forEach { assertFalse(ConsentRecognizer.matches(it), "sollte NICHT matchen: »$it«") }
    }

    // ── TopicAnswerRecognizer — POSITIV (Andi-Vorfall 2026-07-25) ────────────────
    //    Antwort auf „was genau soll ich nachschauen?" — (fast) alles ist ein Thema.
    @Test
    fun `eine Sach-Antwort auf die Themen-Rueckfrage ist ein Thema`() {
        listOf(
            "A Recept of Pizza", // Andis Folge-Nachricht WÖRTLICH
            "ein Pizzarezept",
            "Pizza",
            "wann GTA 6 erscheint",
            "the tallest building in the world",
            "No Mans Sky release date", // beginnt mit „No" — trotzdem ein Thema
            "nichts über 5 Euro", // beginnt mit „nichts" — trotzdem ein Thema
        ).forEach { assertTrue(TopicAnswerRecognizer.isTopic(it), "sollte ein Thema sein: »$it«") }
    }

    // ── TopicAnswerRecognizer — NEGATIV: Zustimmung + Abwinken ───────────────────
    @Test
    fun `Zustimmung und Abwinken sind KEIN Thema - die Rueckfrage verfaellt still`() {
        listOf(
            "", "   ",
            "ja", "ok", "okay", "yes", "sure", "jo", // Zustimmung ohne Sachinhalt
            "egal", "Egal.", "egal, mach das Licht an", // Abwinken (auch als Präfix)
            "vergiss es", "schon gut", "nein", "nee", "nichts",
            "never mind", "Never mind!", "nevermind", "forget it", "no thanks",
            "no", "nope", "nothing",
        ).forEach { assertFalse(TopicAnswerRecognizer.isTopic(it), "sollte KEIN Thema sein: »$it«") }
    }
}
