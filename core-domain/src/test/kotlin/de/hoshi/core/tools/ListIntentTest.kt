package de.hoshi.core.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Beweist die deterministische Listen-Erkennung [ListIntent]:
 *  - ADD/READ/REMOVE-Klassifikation (DE+EN),
 *  - Reihenfolge REMOVE vor READ vor ADD (Andi-Entscheidung 2026-07-08),
 *  - konservativ: nur bei eindeutigem Intent, mehrdeutig/Negation/Kompositum ⇒ null.
 *
 * Die vier Golden-Utterances (#13–15 + Wächter) sind hier NAMENTLICH markiert.
 */
class ListIntentTest {

    // ── Golden-Utterance #13: ADD mit Read-back-Erwartung ────────────────────

    @Test
    fun `Golden 13 - Setz Milch auf die Einkaufsliste ergibt ADD mit Item Milch`() {
        val call = ListIntent.classify("Setz Milch auf die Einkaufsliste.")!!
        assertEquals(ListIntent.DOMAIN, call.domain)
        assertEquals(ListIntent.ADD, call.service)
        assertEquals("Milch", call.data[ListIntent.ITEM])
    }

    // ── Golden-Utterance #14: READ ────────────────────────────────────────────

    @Test
    fun `Golden 14 - Was steht auf der Liste ergibt READ`() {
        val call = ListIntent.classify("Was steht auf der Liste?")!!
        assertEquals(ListIntent.DOMAIN, call.domain)
        assertEquals(ListIntent.READ, call.service)
    }

    // ── Golden-Utterance #15: REMOVE hat Vorrang vor ADD ─────────────────────

    @Test
    fun `Golden 15 - Nimm die Milch von der Liste ergibt REMOVE (nicht ADD)`() {
        val call = ListIntent.classify("Nimm die Milch von der Liste.")!!
        assertEquals(ListIntent.DOMAIN, call.domain)
        assertEquals(ListIntent.REMOVE, call.service, "REMOVE-Erkennung hat Vorrang vor ADD bei Doppel-Match (Andi 2026-07-08)")
        assertEquals(false, call.data[ListIntent.ALL])
        assertEquals("Milch", call.data[ListIntent.ITEM])
    }

    // ── Wächter: darf NIE im Listen-Store landen ─────────────────────────────

    @Test
    fun `Waechter - Mach mir eine Liste von Ideen fuer Papas Geburtstag bleibt beim Brain`() {
        assertNull(ListIntent.classify("Mach mir eine Liste von Ideen für Papas Geburtstag."))
    }

    @Test
    fun `Waechter Varianten - generische Listen-Erwaehnung ohne Ziel-Phrase bleibt beim Brain`() {
        assertNull(ListIntent.classify("Kannst du mir eine Liste mit Filmvorschlägen machen?"))
        assertNull(ListIntent.classify("Erstell eine Liste der besten Bücher."))
        assertNull(ListIntent.classify("Make me a list of birthday gift ideas."))
    }

    @Test
    fun `Waechter Kompositum - Gaesteliste triggert keinen Listen-Read (kein eigenstaendiges Wort liste)`() {
        assertNull(ListIntent.classify("Wer steht auf der Gästeliste?"))
        assertNull(ListIntent.classify("Füg Tom zur Gästeliste hinzu."))
    }

    // ── ADD: weitere DE-Verben + Ziel-Varianten ──────────────────────────────

    @Test
    fun `ADD DE Pack Butter auf den Einkaufszettel`() {
        val call = ListIntent.classify("Pack Butter auf den Einkaufszettel.")!!
        assertEquals(ListIntent.ADD, call.service)
        assertEquals("Butter", call.data[ListIntent.ITEM])
    }

    @Test
    fun `ADD DE Schreib Eier auf die Liste`() {
        val call = ListIntent.classify("Schreib Eier auf die Liste.")!!
        assertEquals(ListIntent.ADD, call.service)
        assertEquals("Eier", call.data[ListIntent.ITEM])
    }

    @Test
    fun `ADD DE zur Einkaufsliste (Kontraktion zur)`() {
        val call = ListIntent.classify("Füg Käse zur Einkaufsliste hinzu.")!!
        assertEquals(ListIntent.ADD, call.service)
        assertEquals("Käse", call.data[ListIntent.ITEM])
    }

    @Test
    fun `ADD Freitext-Menge - 500 g Hack bleibt als kompletter Item-Text erhalten (keine Einheiten-Ontologie)`() {
        val call = ListIntent.classify("Setz 500 g Hack auf die Einkaufsliste.")!!
        assertEquals(ListIntent.ADD, call.service)
        assertEquals("500 g Hack", call.data[ListIntent.ITEM])
    }

    @Test
    fun `ADD verblos am Satzanfang - Bananen auf die Liste`() {
        val call = ListIntent.classify("Bananen auf die Liste.")!!
        assertEquals(ListIntent.ADD, call.service)
        assertEquals("Bananen", call.data[ListIntent.ITEM])
    }

    @Test
    fun `ADD EN Put milk on the shopping list`() {
        val call = ListIntent.classify("Put milk on the shopping list.")!!
        assertEquals(ListIntent.ADD, call.service)
        assertEquals("milk", call.data[ListIntent.ITEM])
    }

    @Test
    fun `ADD EN Add bread to the list`() {
        val call = ListIntent.classify("Add bread to the list.")!!
        assertEquals(ListIntent.ADD, call.service)
        assertEquals("bread", call.data[ListIntent.ITEM])
    }

    // ── READ: weitere DE+EN-Varianten ────────────────────────────────────────

    @Test
    fun `READ DE Zeig mir die Einkaufsliste`() {
        assertEquals(ListIntent.READ, ListIntent.classify("Zeig mir die Einkaufsliste.")!!.service)
    }

    @Test
    fun `READ DE Was muss ich noch einkaufen`() {
        assertEquals(ListIntent.READ, ListIntent.classify("Was muss ich noch einkaufen?")!!.service)
    }

    @Test
    fun `READ EN Whats on the list`() {
        assertEquals(ListIntent.READ, ListIntent.classify("What's on the list?")!!.service)
    }

    @Test
    fun `READ EN Show me the list`() {
        assertEquals(ListIntent.READ, ListIntent.classify("Show me the list.")!!.service)
    }

    // ── REMOVE: weitere DE+EN-Varianten ──────────────────────────────────────

    @Test
    fun `REMOVE DE Loesch Butter von der Einkaufsliste`() {
        val call = ListIntent.classify("Lösch Butter von der Einkaufsliste.")!!
        assertEquals(ListIntent.REMOVE, call.service)
        assertEquals("Butter", call.data[ListIntent.ITEM])
        assertEquals(false, call.data[ListIntent.ALL])
    }

    @Test
    fun `REMOVE DE Streich Eier vom Einkaufszettel`() {
        val call = ListIntent.classify("Streich Eier vom Einkaufszettel.")!!
        assertEquals(ListIntent.REMOVE, call.service)
        assertEquals("Eier", call.data[ListIntent.ITEM])
    }

    @Test
    fun `REMOVE DE Entfern Kaese von der Liste`() {
        val call = ListIntent.classify("Entfern Käse von der Liste.")!!
        assertEquals(ListIntent.REMOVE, call.service)
        assertEquals("Käse", call.data[ListIntent.ITEM])
    }

    @Test
    fun `REMOVE EN Remove milk from the list`() {
        val call = ListIntent.classify("Remove milk from the list.")!!
        assertEquals(ListIntent.REMOVE, call.service)
        assertEquals("milk", call.data[ListIntent.ITEM])
    }

    @Test
    fun `REMOVE EN Take milk off the list`() {
        val call = ListIntent.classify("Take milk off the list.")!!
        assertEquals(ListIntent.REMOVE, call.service)
        assertEquals("milk", call.data[ListIntent.ITEM])
    }

    // ── CLEAR (Sonderfall von REMOVE: all=true) ──────────────────────────────

    @Test
    fun `CLEAR DE Liste leeren`() {
        val call = ListIntent.classify("Liste leeren.")!!
        assertEquals(ListIntent.REMOVE, call.service)
        assertEquals(true, call.data[ListIntent.ALL])
    }

    @Test
    fun `CLEAR DE Loesch die ganze Liste`() {
        val call = ListIntent.classify("Lösch die ganze Liste.")!!
        assertEquals(ListIntent.REMOVE, call.service)
        assertEquals(true, call.data[ListIntent.ALL])
    }

    @Test
    fun `CLEAR EN Clear the list`() {
        val call = ListIntent.classify("Clear the list.")!!
        assertEquals(ListIntent.REMOVE, call.service)
        assertEquals(true, call.data[ListIntent.ALL])
    }

    // ── Konservativ: mehrdeutig / kein Trigger / Negation ⇒ null ─────────────

    @Test
    fun `Negation DE Setz keine Milch auf die Liste ergibt null`() {
        assertNull(ListIntent.classify("Setz keine Milch auf die Liste."))
    }

    @Test
    fun `Negation EN Dont add milk to the list ergibt null`() {
        assertNull(ListIntent.classify("Don't add milk to the list."))
    }

    @Test
    fun `kein Listen-Wort ergibt null`() {
        assertNull(ListIntent.classify("Mach das Licht aus."))
        assertNull(ListIntent.classify("Wie warm ist es?"))
        assertNull(ListIntent.classify("Stell einen Timer auf 10 Minuten."))
    }

    @Test
    fun `leerer Text ergibt null`() {
        assertNull(ListIntent.classify(""))
        assertNull(ListIntent.classify("   "))
    }

    @Test
    fun `Playlist ist kein Listen-Kompositum-Treffer`() {
        assertNull(ListIntent.classify("Spiel meine Playlist ab."))
    }
}
