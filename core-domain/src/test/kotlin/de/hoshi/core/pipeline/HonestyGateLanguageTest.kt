package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.pipeline.lang.LangDe
import de.hoshi.core.pipeline.lang.LanguagePackRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **Die Ehrlichkeits-Sätze sprechen die Turn-Sprache** (Andi 2026-07-25:
 * „Multilingualität von A-Z, auch alles fest Verdrahtete"). Vorher feuerte das
 * [HonestyGate] hart deutsch, auch bei `language = EN` — ausgerechnet in der
 * Textklasse, in der Hoshi zugibt, dass er NICHT weiterweiß.
 *
 * Geprüft wird beides:
 *  1. **DE bleibt BYTE-IDENTISCH** — die fünf Pools stehen hier nochmal wörtlich
 *     als unabhängige Kopie; wackelt ein Byte, fällt dieser Test (und nicht erst
 *     Andi beim Zuhören).
 *  2. **Jede Sprache antwortet aus ihrem eigenen Pack**, für JEDE Gate-Klasse,
 *     und nie mit einer deutschen Phrase (der Alt-Zustand).
 */
class HonestyGateLanguageTest {

    private val existenceMatched = ExistenceClaimSignal { HonestySignal(matched = true) }
    private val existenceBridgeDown = ExistenceClaimSignal { HonestySignal(matched = true, bridgeDown = true) }
    private val namedMatched = NamedEntitySignal { HonestySignal(matched = true) }

    private fun gate(
        existence: ExistenceClaimSignal = ExistenceClaimSignal { HonestySignal.NONE },
        named: NamedEntitySignal = NamedEntitySignal { HonestySignal.NONE },
    ): HonestyGate = HonestyGate(
        weakDomain = WeakDomainDetector(),
        onlineRequest = OnlineRequestDetector(),
        existenceClaim = existence,
        namedEntity = named,
        cloudEnabled = { false },
        disambigAskBackEnabled = false,
    )

    private fun refusal(
        text: String,
        language: Language,
        existence: ExistenceClaimSignal = ExistenceClaimSignal { HonestySignal.NONE },
        named: NamedEntitySignal = NamedEntitySignal { HonestySignal.NONE },
    ): String {
        val v = gate(existence, named).assess(text, language)
        assertTrue(v is HonestyGate.Verdict.Refuse, "$language/'$text': erwartet Refuse, war $v")
        return (v as HonestyGate.Verdict.Refuse).phrase
    }

    // ── (c) HARTE NEBENBEDINGUNG: Deutsch bleibt byte-identisch ──────────────

    @Test
    fun `DE-Pools sind BYTE-IDENTISCH zum bisherigen HonestyGate-Bestand`() {
        val de = LangDe.PACK
        assertEquals(
            listOf(
                "Ins offene Netz geh ich bewusst nicht — ich bleib bei dir. Aber in meinem eigenen Wissen schau ich gern nach: was genau suchst du?",
                "Da raus ins Internet will ich gar nicht — dafür hab ich 'nen ganzen Wissensspeicher hier. Soll ich da für dich nachsehen?",
                "Online unterwegs bin ich absichtlich nicht. Was ich aber hab, ist mein eigenes Wissen — sag mir, wonach, dann schau ich nach.",
                "Das Internet lass ich bewusst zu — aber ich hab ne Menge selbst gespeichert. Lass mich da für dich nachschlagen, okay?",
                "Nach draußen geh ich nicht, das ist Absicht. In meinem eigenen Wissen werd ich aber gern für dich fündig — was brauchst du?",
            ),
            de.honestyOnlineRequestRefusals,
        )
        assertEquals(
            listOf(
                "Kochen ist nicht meine Stärke — da führ ich dich in die Irre.",
                "Beim Rezept würd ich raten, und das wär dir keine Hilfe.",
            ),
            de.honestyRecipeRefusals,
        )
        assertEquals(
            listOf(
                "Halt — da bin ich nicht sicher, ob's das wirklich gibt. Ich würd dir lieber nichts erfinden.",
                "Gute Frage — bei sowas verlass ich mich ungern auf mein Bauchgefühl. Lieber sag ich's ehrlich: weiß ich nicht.",
                "Ehrlich? Da bin ich raus. Sowas würd ich gerne nachschauen statt raten.",
            ),
            de.honestyExistenceRefusals,
        )
        assertEquals(
            listOf(
                "Hmm, der Name sagt mir gerade nichts. Klingt nach jemandem aus einer bestimmten Szene — wer genau ist das?",
                "Sag mir mehr — Musik, Film, Geschichte, Sport? Bei dem Namen tappe ich grade im Dunkeln.",
                "Ich kenn den Namen nicht — magst du mir was dazu sagen?",
                "Ehrlich, da hab ich nichts zu — woher kennst du den Namen?",
            ),
            de.honestyNamedEntityRefusals,
        )
        assertEquals(
            listOf(
                "Ich komm gerade nicht an meinen Wissensspeicher — das kann ich dir verlässlich erst gleich sagen. Magst du's in einem Moment nochmal fragen?",
                "Hm, mein Nachschlagewerk ist im Moment nicht erreichbar. Ich will dir nichts raten — frag mich gleich nochmal, dann schau ich richtig nach.",
                "Da häng ich grad — mein Wissensspeicher antwortet nicht. Gib mir einen Moment, dann kann ich's dir ehrlich sagen.",
            ),
            de.honestyBridgeDownRefusals,
        )
    }

    @Test
    fun `assess ohne Sprach-Argument bleibt Deutsch (Bestands-Aufrufer byte-neutral)`() {
        val phrase = refusal("Wie mache ich Käsekuchen?", Language.DE)
        assertTrue(phrase in LangDe.PACK.honestyRecipeRefusals, "DE-Default erwartet, war: $phrase")
        val v = gate().assess("Wie mache ich Käsekuchen?")
        assertTrue((v as HonestyGate.Verdict.Refuse).phrase in LangDe.PACK.honestyRecipeRefusals)
    }

    // ── (b) Jede Sprache spricht ihr eigenes Pack, in JEDER Gate-Klasse ──────

    @Test
    fun `Rezept-Absage kommt in jeder Sprache aus dem eigenen Pack`() {
        for (language in Language.entries) {
            val pack = LanguagePackRegistry.forLanguage(language)
            val phrase = refusal("Wie mache ich Käsekuchen?", language)
            assertTrue(phrase in pack.honestyRecipeRefusals, "$language bekam eine fremde Phrase: $phrase")
        }
    }

    @Test
    fun `Online-Bitte bei Cloud AUS kommt in jeder Sprache aus dem eigenen Pack`() {
        for (language in Language.entries) {
            val pack = LanguagePackRegistry.forLanguage(language)
            val phrase = refusal("Kannst du das mal online nachsehen?", language)
            assertTrue(phrase in pack.honestyOnlineRequestRefusals, "$language bekam eine fremde Phrase: $phrase")
        }
    }

    @Test
    fun `Existenz-Claim kommt in jeder Sprache aus dem eigenen Pack`() {
        for (language in Language.entries) {
            val pack = LanguagePackRegistry.forLanguage(language)
            val phrase = refusal("Gibt es einen 11 Euro schein?", language, existence = existenceMatched)
            assertTrue(phrase in pack.honestyExistenceRefusals, "$language bekam eine fremde Phrase: $phrase")
        }
    }

    @Test
    fun `unbekannter Eigenname kommt in jeder Sprache aus dem eigenen Pack`() {
        for (language in Language.entries) {
            val pack = LanguagePackRegistry.forLanguage(language)
            val phrase = refusal("Wer ist Neelix?", language, named = namedMatched)
            assertTrue(phrase in pack.honestyNamedEntityRefusals, "$language bekam eine fremde Phrase: $phrase")
        }
    }

    @Test
    fun `tote Wissens-Bridge kommt in jeder Sprache aus dem eigenen Pack`() {
        for (language in Language.entries) {
            val pack = LanguagePackRegistry.forLanguage(language)
            val phrase = refusal("Gibt es einen 11 Euro schein?", language, existence = existenceBridgeDown)
            assertTrue(phrase in pack.honestyBridgeDownRefusals, "$language bekam eine fremde Phrase: $phrase")
        }
    }

    // ── Der eigentliche Fehler, den diese Scheibe behebt ─────────────────────

    @Test
    fun `keine Nicht-DE-Sprache bekommt mehr eine deutsche Ehrlichkeits-Phrase`() {
        val german = LangDe.PACK.let {
            it.honestyOnlineRequestRefusals + it.honestyRecipeRefusals + it.honestyExistenceRefusals +
                it.honestyNamedEntityRefusals + it.honestyBridgeDownRefusals
        }.toSet()
        for (language in Language.entries - Language.DE) {
            for (phrase in listOf(
                refusal("Wie mache ich Käsekuchen?", language),
                refusal("Kannst du das mal online nachsehen?", language),
                refusal("Gibt es einen 11 Euro schein?", language, existence = existenceMatched),
                refusal("Wer ist Neelix?", language, named = namedMatched),
                refusal("Gibt es einen 11 Euro schein?", language, existence = existenceBridgeDown),
            )) {
                assertFalse(phrase in german, "$language sprach noch deutsch: $phrase")
            }
        }
    }

    @Test
    fun `jede Sprache traegt vollstaendige, nicht-leere Pools in Bestands-Variantenzahl`() {
        val de = LangDe.PACK
        for (language in Language.entries) {
            val pack = LanguagePackRegistry.forLanguage(language)
            for ((label, own, ref) in listOf(
                Triple("onlineRequest", pack.honestyOnlineRequestRefusals, de.honestyOnlineRequestRefusals),
                Triple("recipe", pack.honestyRecipeRefusals, de.honestyRecipeRefusals),
                Triple("existence", pack.honestyExistenceRefusals, de.honestyExistenceRefusals),
                Triple("namedEntity", pack.honestyNamedEntityRefusals, de.honestyNamedEntityRefusals),
                Triple("bridgeDown", pack.honestyBridgeDownRefusals, de.honestyBridgeDownRefusals),
            )) {
                assertEquals(ref.size, own.size, "$language.$label: Variantenzahl wie DE")
                assertTrue(own.none { it.isBlank() }, "$language.$label: keine leere Phrase")
            }
        }
    }

    /**
     * Ehrlichkeit heißt AUCH: die Bridge-down-Phrase darf in KEINER Sprache wie
     * eine Existenz-Verneinung klingen — sonst wird aus einem Infrastruktur-Fehler
     * eine erfundene Tatsachen-Behauptung. Sprach-neutral prüfbar: mindestens eine
     * Variante lädt ausdrücklich zum erneuten Fragen ein (Fragezeichen), und keine
     * ist ein trockenes Urteil ohne Aussicht — die DE-Messlatte, exakt übernommen.
     */
    @Test
    fun `Bridge-down-Phrasen laden in jeder Sprache zum Nachfragen ein`() {
        val deWithQuestion = LangDe.PACK.honestyBridgeDownRefusals.count { it.contains("?") }
        for (language in Language.entries) {
            val pack = LanguagePackRegistry.forLanguage(language)
            assertTrue(
                pack.honestyBridgeDownRefusals.count { it.contains("?") } >= deWithQuestion,
                "$language: Bridge-down soll mindestens so oft zum erneuten Fragen einladen wie auf Deutsch",
            )
        }
    }

    /**
     * Die Repair-Phrasen bei unbekanntem Eigennamen sind in JEDER Sprache Fragen —
     * das ist ihr ganzer Zweck („sag mir mehr"), keine Absage.
     */
    @Test
    fun `Eigennamen-Phrasen sind in jeder Sprache Rueckfragen`() {
        for (language in Language.entries) {
            val pack = LanguagePackRegistry.forLanguage(language)
            for (phrase in pack.honestyNamedEntityRefusals) {
                assertTrue(phrase.contains("?"), "$language: '$phrase' ist keine Rückfrage")
            }
        }
    }
}
