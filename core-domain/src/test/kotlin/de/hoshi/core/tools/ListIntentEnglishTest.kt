package de.hoshi.core.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * **Die Liste versteht Englisch.** Schwester-Datei zu [TimerIntentEnglishTest],
 * gleicher Anlass (Andi 2026-07-25: Antworten mehrsprachig, Erkenner nicht).
 *
 * **Der schlimmste gemessene Befund war hier — und er war STILL:** „what's on the
 * shopping list" landete VOR dieser Runde nicht als Vorlese-Frage, sondern als
 * EINTRAG namens „whats" auf der Einkaufsliste (die verb-lose Kurzform
 * `ADD_BARE_RX` frisst alles, was auf „… on the list" endet). Ebenso legte „did I
 * put milk on the list" Milch tatsächlich an. Eine Liste, die auf eine FRAGE hin
 * heimlich schreibt, ist schlimmer als eine, die nichts versteht — deshalb steht
 * die Frage-Sperre ([EN_QUESTION_OPENERS]) hier mit eigenen Fällen.
 */
class ListIntentEnglishTest {

    private fun assertAdd(text: String, item: String) {
        val call = ListIntent.classify(text)
        assertEquals(ListIntent.ADD, call?.service, "kein ADD: $text")
        assertEquals(item, call?.data?.get(ListIntent.ITEM), "Item von: $text")
    }

    private fun assertRead(text: String) =
        assertEquals(ListIntent.READ, ListIntent.classify(text)?.service, "kein READ: $text")

    private fun assertClear(text: String) {
        val call = ListIntent.classify(text)
        assertEquals(ListIntent.REMOVE, call?.service, "kein REMOVE: $text")
        assertEquals(true, call?.data?.get(ListIntent.ALL), "war kein Komplett-Leeren: $text")
    }

    private fun assertRemove(text: String, item: String) {
        val call = ListIntent.classify(text)
        assertEquals(ListIntent.REMOVE, call?.service, "kein REMOVE: $text")
        assertEquals(false, call?.data?.get(ListIntent.ALL), "war fälschlich ein Komplett-Leeren: $text")
        assertEquals(item, call?.data?.get(ListIntent.ITEM), "Item von: $text")
    }

    private fun assertNoListIntent(text: String) =
        assertNull(ListIntent.classify(text), "hätte NICHT zünden dürfen: $text")

    // ── ADD ──────────────────────────────────────────────────────────────────

    @Test
    fun `englische aufnahme-befehle`() {
        assertAdd("add milk to the list", "milk")
        assertAdd("Add milk to the shopping list.", "milk")
        assertAdd("put bread on the list", "bread")
        assertAdd("add eggs to my list", "eggs")
        assertAdd("add milk to my grocery list", "milk")
        assertAdd("note milk on the list", "milk")
        assertAdd("write bread on the shopping list", "bread")
        assertAdd("jot down butter on the list", "butter")
    }

    @Test
    fun `hoefliche auftraege bleiben auftraege`() {
        assertAdd("can you add milk to the list", "milk")
        assertAdd("could you put bread on the shopping list", "bread")
        assertAdd("please add milk to the list", "milk")
    }

    @Test
    fun `freitext-item bleibt unangetastet`() {
        assertAdd("add 500 g of mince to the list", "500 g of mince")
        assertAdd("add milk and bread to the list", "milk and bread")
    }

    // ── READ ─────────────────────────────────────────────────────────────────

    @Test
    fun `englische vorlese-fragen`() {
        assertRead("what's on the list")
        assertRead("what is on the list")
        assertRead("what's on the shopping list")
        assertRead("whats still on my shopping list")
        assertRead("what is left on the list")
        assertRead("show me the list")
        assertRead("show me the shopping list")
        assertRead("read out the list")
        assertRead("read my grocery list")
        assertRead("what do I need to buy")
    }

    // ── REMOVE / CLEAR ───────────────────────────────────────────────────────

    @Test
    fun `englische streich-befehle`() {
        assertRemove("remove milk from the list", "milk")
        assertRemove("take milk off the list", "milk")
        assertRemove("delete milk from the shopping list", "milk")
        assertRemove("cross milk off the list", "milk")
        assertRemove("scratch milk off the list", "milk")
        assertRemove("erase milk from the list", "milk")
    }

    @Test
    fun `englisches komplett-leeren`() {
        assertClear("clear the list")
        assertClear("empty the list")
        assertClear("empty the shopping list")
        assertClear("clear my list")
        assertClear("wipe the list")
        assertClear("clear the grocery list")
        assertClear("remove everything from the list")
        assertClear("take everything off the list")
    }

    // ── Gegen-Tests: „list" ist ein Alltagswort ───────────────────────────────

    /**
     * Der eigentliche Fehlalarm-Wächter. „list" kommt in harmlosen englischen
     * Sätzen ständig vor; ohne Ziel-Phrase („to/on … the list") und ohne
     * Listen-Nomen als eigenes Wort darf NICHTS zünden.
     */
    @Test
    fun `list ohne listen-kontext zuendet nie`() {
        assertNoListIntent("the list of ingredients is long")
        assertNoListIntent("can you list the options")
        assertNoListIntent("list the files in the folder")
        assertNoListIntent("make a list of ideas for dad's birthday")
        assertNoListIntent("the shopping list app is broken")
        assertNoListIntent("I need to set the table")
        assertNoListIntent("add two and two")
    }

    /**
     * **Fragen schreiben nicht.** Genau diese Sätze legten VOR dieser Runde still
     * Einträge an („whats", „is milk", „did I put milk"). Jetzt gehen sie
     * entweder als READ durch (wenn sie eindeutig die Liste vorlesen wollen) oder
     * gar nicht (⇒ normaler Turn) — aber sie SCHREIBEN nie.
     */
    @Test
    fun `englische fragen legen niemals einen eintrag an`() {
        listOf(
            "what's on the shopping list",
            "is milk on the list",
            "do I have milk on the list",
            "did I put milk on the list",
            "how much milk is on the list",
            "who put milk on the list",
        ).forEach { text ->
            assertEquals(
                ListIntent.ADD != ListIntent.classify(text)?.service, true,
                "hat still einen Eintrag angelegt: $text",
            )
        }
    }

    @Test
    fun `negation schaltet auch auf englisch ab`() {
        assertNoListIntent("don't put milk on the list")
        assertNoListIntent("no milk on the list")
    }
}
