package de.hoshi.core.pipeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Portiert aus Hoshi 0.5 (de.hoshi.app.streaming.HonestyGateTest).
 *
 * Entkopplung: die infra-koppelnden Detektoren (Existence/Named-Entity proben die
 * Wissens-Bridge) sind als schmale Ports gefaket; die reinen Heuristiken
 * ([WeakDomainDetector]/[OnlineRequestDetector]) laufen ECHT (1:1 mitportiert),
 * sodass die Recipe-/Online-Klasse wie in 0.5 erkannt wird. `SkillRegistry` →
 * `cloudEnabled`-Supplier, `HoshiProperties.disambigAskBack` → Flag.
 *
 * Kern-Akzeptanz: Rezept/Existenz-Claim/Online-Bitte → Refusal (Cloud aus) bzw.
 * AskConsent (Cloud an), NIE Pass. Normale/groundbare Fragen bleiben Pass.
 */
class HonestyGateTest {

    private val existenceMatched = ExistenceClaimSignal { HonestySignal(matched = true) }
    private val existenceBridgeDown = ExistenceClaimSignal { HonestySignal(matched = true, bridgeDown = true) }
    private val namedMatched = NamedEntitySignal { HonestySignal(matched = true) }
    private val namedBridgeDown = NamedEntitySignal { HonestySignal(matched = true, bridgeDown = true) }

    private fun gate(
        cloudOn: Boolean,
        existence: ExistenceClaimSignal = ExistenceClaimSignal { HonestySignal.NONE },
        named: NamedEntitySignal = NamedEntitySignal { HonestySignal.NONE },
        disambigAskBackEnabled: Boolean = false,
    ): HonestyGate = HonestyGate(
        weakDomain = WeakDomainDetector(),
        onlineRequest = OnlineRequestDetector(),
        existenceClaim = existence,
        namedEntity = named,
        cloudEnabled = { cloudOn },
        disambigAskBackEnabled = disambigAskBackEnabled,
    )

    @Test
    fun `Rezept bei Cloud aus liefert ehrliche Refusal (kein Brain)`() {
        val v = gate(cloudOn = false).assess("Wie mache ich Käsekuchen?")
        assertTrue(v is HonestyGate.Verdict.Refuse, "erwartet Refuse, war $v")
        val phrase = (v as HonestyGate.Verdict.Refuse).phrase.lowercase()
        assertTrue(phrase.contains("kochen") || phrase.contains("rezept"),
            "Refusal soll Rezept-bezogen sein: ${v.phrase}")
    }

    @Test
    fun `Rezept bei Cloud an liefert AskConsent`() {
        assertEquals(HonestyGate.Verdict.AskConsent, gate(cloudOn = true).assess("Wie backe ich Brot?"))
    }

    @Test
    fun `Wetter morgen passiert das Gate`() {
        assertEquals(HonestyGate.Verdict.Pass, gate(cloudOn = false).assess("Wie wird das Wetter morgen?"))
        assertEquals(HonestyGate.Verdict.Pass, gate(cloudOn = true).assess("Wie wird das Wetter morgen?"))
    }

    @Test
    fun `Forecast uebermorgen passiert das Gate (Cloud egal)`() {
        assertEquals(HonestyGate.Verdict.Pass, gate(cloudOn = true).assess("Regnet es übermorgen?"))
        assertEquals(HonestyGate.Verdict.Pass, gate(cloudOn = false).assess("Regnet es übermorgen?"))
    }

    @Test
    fun `Wochentag-Wetter passiert das Gate`() {
        assertEquals(HonestyGate.Verdict.Pass, gate(cloudOn = false).assess("Wird es am Samstag sonnig?"))
    }

    @Test
    fun `Wetter heute ist Gegenwart und bleibt Pass`() {
        assertEquals(HonestyGate.Verdict.Pass, gate(cloudOn = false).assess("Wie ist das Wetter gerade?"))
        assertEquals(HonestyGate.Verdict.Pass, gate(cloudOn = false).assess("Regnet es heute?"))
    }

    // ── Existence-Claim mit Zahl-Entity ──────────────────────────────────────

    @Test
    fun `Andi-Live-Bug 11 Euro Schein bei Cloud aus liefert ehrliche Refusal`() {
        val v = gate(cloudOn = false, existence = existenceMatched).assess("Gibt es einen 11 Euro schein?")
        assertTrue(v is HonestyGate.Verdict.Refuse, "erwartet Refuse, war $v")
        val phrase = (v as HonestyGate.Verdict.Refuse).phrase.lowercase()
        assertTrue(
            phrase.contains("nicht sicher") || phrase.contains("erfinden") ||
                phrase.contains("weiß ich nicht") || phrase.contains("nachschauen") ||
                phrase.contains("raten"),
            "Refusal soll ehrlich-zweifelnd sein: ${v.phrase}"
        )
    }

    @Test
    fun `Existenz-Claim bei Bridge-down liefert Wissensspeicher-nicht-erreichbar statt gibt-s-nicht`() {
        val v = gate(cloudOn = false, existence = existenceBridgeDown).assess("Gibt es einen 11 Euro schein?")
        assertTrue(v is HonestyGate.Verdict.Refuse, "erwartet Refuse, war $v")
        val phrase = (v as HonestyGate.Verdict.Refuse).phrase.lowercase()
        assertTrue(
            phrase.contains("wissensspeicher") || phrase.contains("nachschlagewerk") ||
                phrase.contains("nicht erreichbar") || phrase.contains("antwortet nicht") ||
                phrase.contains("komm gerade nicht") || phrase.contains("nochmal"),
            "Bridge-down-Refusal soll auf Erreichbarkeit zielen: ${v.phrase}"
        )
        assertTrue(
            !phrase.contains("erfinden") && !phrase.contains("nicht sicher ob"),
            "Bridge-down darf nicht wie eine Existenz-Verneinung klingen: ${v.phrase}"
        )
    }

    // ── Explizite Online-Nachschau-Bitte ─────────────────────────────────────

    @Test
    fun `Andi-Live-Bug online schauen bei Cloud aus liefert souveraene Refusal statt Luege`() {
        val v = gate(cloudOn = false).assess("Kannst du für mich online schauen, wie viele es davon gibt?")
        assertTrue(v is HonestyGate.Verdict.Refuse, "erwartet Refuse, war $v")
        val phrase = (v as HonestyGate.Verdict.Refuse).phrase.lowercase()
        assertTrue(
            phrase.contains("wissen") || phrase.contains("gespeichert") || phrase.contains("nachschlagen"),
            "Refusal soll das eigene Wissen anbieten: ${v.phrase}"
        )
        assertTrue(
            !phrase.contains("ich kann nicht") && !phrase.contains("müsste ich passen"),
            "Refusal darf nicht wie ein Defekt klingen: ${v.phrase}"
        )
    }

    @Test
    fun `online-Bitte bei Cloud an liefert AskConsentExplicit`() {
        assertEquals(HonestyGate.Verdict.AskConsentExplicit, gate(cloudOn = true).assess("Kannst du das mal online nachsehen?"))
        assertEquals(HonestyGate.Verdict.AskConsentExplicit, gate(cloudOn = true).assess("Google das bitte für mich."))
    }

    @Test
    fun `nur ONLINE_REQUEST liefert AskConsentExplicit — andere Kinds bleiben AskConsent`() {
        assertEquals(HonestyGate.Verdict.AskConsent, gate(cloudOn = true).assess("Wie backe ich Brot?"))
        assertEquals(
            HonestyGate.Verdict.AskConsent,
            gate(cloudOn = true, existence = existenceMatched).assess("Existiert ein 13-Monats-Jahr?"),
        )
    }

    @Test
    fun `online-Bitte gewinnt ueber Existenz-Claim`() {
        val v = gate(cloudOn = false, existence = existenceMatched)
            .assess("Kannst du online schauen, ob es einen 12 Euro Schein gibt?")
        assertTrue(v is HonestyGate.Verdict.Refuse, "erwartet Refuse, war $v")
        val phrase = (v as HonestyGate.Verdict.Refuse).phrase.lowercase()
        assertTrue(
            phrase.contains("wissen") || phrase.contains("gespeichert") || phrase.contains("nachschlagen"),
            "Online-Bitte soll die ONLINE_REQUEST-Phrase liefern: ${v.phrase}"
        )
    }

    @Test
    fun `Wissensfrage ueber das Internet bleibt Pass`() {
        for (q in listOf(
            "Wie funktioniert das Internet?",
            "Was ist Google?",
            "Wer hat das Internet erfunden?",
        )) {
            assertEquals(HonestyGate.Verdict.Pass, gate(cloudOn = false).assess(q),
                "'$q' sollte Pass sein (Thema, keine Nachschau-Bitte)")
        }
    }

    @Test
    fun `Existenz-Claim mit Zahl bei Cloud an liefert AskConsent`() {
        assertEquals(
            HonestyGate.Verdict.AskConsent,
            gate(cloudOn = true, existence = existenceMatched).assess("Existiert ein 13-Monats-Jahr?"),
        )
    }

    @Test
    fun `harmlose gibt-es-Frage ohne Zahl bleibt Pass`() {
        for (q in listOf("Gibt es Honigbienen?", "Gibt es Pizza Hawaii?", "Existiert das Bermuda-Dreieck?")) {
            assertEquals(HonestyGate.Verdict.Pass, gate(cloudOn = false).assess(q),
                "'$q' sollte Pass sein (kein Zahl-Pattern)")
        }
    }

    @Test
    fun `normale Wissensfrage bleibt Pass (behavior-preserving)`() {
        for (q in listOf(
            "Wer war Albert Einstein?",
            "Was ist die Hauptstadt von Frankreich?",
            "Erzähl mir was über Saturn.",
            "Wie spät ist es?",
        )) {
            assertEquals(HonestyGate.Verdict.Pass, gate(cloudOn = false).assess(q), "'$q' sollte Pass sein")
        }
    }

    @Test
    fun `leere Query bleibt Pass`() {
        assertEquals(HonestyGate.Verdict.Pass, gate(cloudOn = true).assess(""))
        assertEquals(HonestyGate.Verdict.Pass, gate(cloudOn = true).assess("   "))
    }

    // ── EXISTENCE_NAMED_ENTITY (Neelix-Klasse) ───────────────────────────────

    @Test
    fun `Andi-Live-Bug Wer ist Neelix mit leerer Bridge bei Cloud aus liefert Refusal`() {
        val v = gate(cloudOn = false, named = namedMatched).assess("Wer ist Neelix?")
        assertTrue(v is HonestyGate.Verdict.Refuse, "erwartet Refuse, war $v")
        val phrase = (v as HonestyGate.Verdict.Refuse).phrase.lowercase()
        assertTrue(
            phrase.contains("name") || phrase.contains("kenn") || phrase.contains("dunkeln") || phrase.contains("woher"),
            "Refusal soll warm-neugierig sein: ${v.phrase}"
        )
    }

    @Test
    fun `Wer ist Neelix bei Cloud an liefert AskConsent`() {
        assertEquals(HonestyGate.Verdict.AskConsent, gate(cloudOn = true, named = namedMatched).assess("Wer ist Neelix?"))
    }

    @Test
    fun `Wer war Albert Einstein mit Bridge-Hit bleibt Pass`() {
        assertEquals(HonestyGate.Verdict.Pass, gate(cloudOn = false).assess("Wer war Albert Einstein?"))
    }

    @Test
    fun `Wer ist mein Bruder bleibt Pass - Common-Wort-Schutz`() {
        // „mein" = Common-Wort, kein Eigenname → der echte Detector würde nicht feuern (named=NONE).
        assertEquals(HonestyGate.Verdict.Pass, gate(cloudOn = false).assess("Wer ist mein Bruder?"))
    }

    @Test
    fun `Kennst du Honigbienen bleibt Pass - Common-Wort-Schutz`() {
        assertEquals(HonestyGate.Verdict.Pass, gate(cloudOn = false).assess("Kennst du Honigbienen?"))
    }

    @Test
    fun `named-entity bei toter Bridge liefert Reachability-Phrase statt kenne-ich-nicht`() {
        val v = gate(cloudOn = false, named = namedBridgeDown).assess("Wer ist Marie Curie?")
        assertTrue(v is HonestyGate.Verdict.Refuse, "erwartet Refuse, war $v")
        val phrase = (v as HonestyGate.Verdict.Refuse).phrase.lowercase()
        assertTrue(
            phrase.contains("wissensspeicher") || phrase.contains("nachschlagewerk") ||
                phrase.contains("nicht erreichbar") || phrase.contains("antwortet nicht") ||
                phrase.contains("komm gerade nicht"),
            "soll Reachability-Phrase sein: ${v.phrase}"
        )
        assertTrue(
            !phrase.contains("der name sagt mir") && !phrase.contains("tappe ich grade im dunkeln"),
            "Bridge-down darf nicht wie 'kenne ich nicht' klingen: ${v.phrase}"
        )
    }

    @Test
    fun `named-entity unbekannt bei lebender Bridge bleibt normale kenne-ich-nicht-Refusal`() {
        val v = gate(cloudOn = false, named = namedMatched).assess("Wer ist Neelix?")
        assertTrue(v is HonestyGate.Verdict.Refuse, "erwartet Refuse, war $v")
        val phrase = (v as HonestyGate.Verdict.Refuse).phrase.lowercase()
        assertTrue(
            !phrase.contains("wissensspeicher") && !phrase.contains("nachschlagewerk") && !phrase.contains("antwortet nicht"),
            "bei lebender Bridge KEINE Reachability-Phrase: ${v.phrase}"
        )
    }

    // ── Refuse-Gate-Defer zu Disambig-Flow ───────────────────────────────────

    @Test
    fun `named-entity + disambig-ask-back aktiv liefert Pass statt Refuse`() {
        val v = gate(cloudOn = false, named = namedMatched, disambigAskBackEnabled = true).assess("Wer ist Neelix?")
        assertTrue(v is HonestyGate.Verdict.Pass, "erwartet Pass (Defer zu Disambig), war $v")
    }

    @Test
    fun `named-entity + disambig-ask-back DEAKTIVIERT bleibt Refuse`() {
        val v = gate(cloudOn = false, named = namedMatched, disambigAskBackEnabled = false).assess("Wer ist Neelix?")
        assertTrue(v is HonestyGate.Verdict.Refuse, "ohne Flag bleibt Refuse, war $v")
    }
}
