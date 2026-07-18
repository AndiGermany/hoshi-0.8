package de.hoshi.adapters.memory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **Working-Session-Store (räumliches Gedächtnis, S1)** — beweist die vier
 * Store-Verträge OHNE Infra (rein in-memory):
 *  1. Roundtrip: append ⇒ recentTurns liefert die Turn-Paare chronologisch
 *     geflacht (user/assistant abwechselnd).
 *  2. Gast (isGuest-Härtung wie [EpisodicMemoryAdapter]): KEIN Load, KEIN Write —
 *     0 Session-Zeilen, roh bewiesen über [WorkingSessionAdapter.storedTurnCount].
 *  3. Per-Speaker-CAP: N+1 Turns ⇒ der älteste fällt raus.
 *  4. Mandanten-Trennung: Sessions verschiedener Sprecher mischen sich nie.
 */
class WorkingSessionAdapterTest {

    @Test
    fun `roundtrip - append liefert die Turns chronologisch als user-assistant-Paare`() {
        val store = WorkingSessionAdapter()
        store.append("andi", "Wie hoch ist der Tokyo Skytree?", "Der Skytree ist 634 Meter hoch.")
        store.append("andi", "Und wann wurde ER gebaut?", "Fertiggestellt 2012.")

        val turns = store.recentTurns("andi")
        assertEquals(4, turns.size, "2 Turn-Paare = 4 Nachrichten")
        assertEquals(listOf("user", "assistant", "user", "assistant"), turns.map { it.role })
        assertEquals("Wie hoch ist der Tokyo Skytree?", turns[0].content)
        assertEquals("Der Skytree ist 634 Meter hoch.", turns[1].content)
        assertEquals("Und wann wurde ER gebaut?", turns[2].content)
        assertEquals("Fertiggestellt 2012.", turns[3].content)
    }

    @Test
    fun `gast hinterlaesst KEINE session-zeile - weder load noch write`() {
        val store = WorkingSessionAdapter()
        for (guestId in listOf("", "unknown", "gast", "böse id!")) {
            store.append(guestId, "geheimer Gast-Turn", "Antwort")
            assertEquals(
                0, store.storedTurnCount(guestId),
                "append für Gast '$guestId' muss ein No-op sein (0 rohe Zeilen)",
            )
            assertTrue(store.recentTurns(guestId).isEmpty(), "recentTurns für Gast '$guestId' muss leer sein")
        }
    }

    @Test
    fun `cap haelt - N plus 1 turns und der aelteste faellt raus`() {
        val store = WorkingSessionAdapter(capTurns = 3)
        for (i in 1..4) store.append("andi", "frage $i", "antwort $i")

        assertEquals(3, store.storedTurnCount("andi"), "CAP=3 ⇒ genau 3 Turn-Paare")
        val turns = store.recentTurns("andi")
        assertEquals(6, turns.size)
        assertEquals("frage 2", turns.first().content, "der älteste Turn (frage 1) ist rausgefallen")
        assertEquals("antwort 4", turns.last().content, "der jüngste Turn bleibt")
    }

    @Test
    fun `sessions verschiedener sprecher mischen sich nie`() {
        val store = WorkingSessionAdapter()
        store.append("andi", "Andis Frage", "Andis Antwort")
        store.append("lena", "Lenas Frage", "Lenas Antwort")

        assertEquals(listOf("Andis Frage", "Andis Antwort"), store.recentTurns("andi").map { it.content })
        assertEquals(listOf("Lenas Frage", "Lenas Antwort"), store.recentTurns("lena").map { it.content })
    }

    @Test
    fun `leerer user-text wird nicht gespeichert - unbekannter sprecher liefert leer`() {
        val store = WorkingSessionAdapter()
        store.append("andi", "   ", "warme Fallback-Antwort")
        assertEquals(0, store.storedTurnCount("andi"), "blank Turn trägt keinen Verlauf")
        assertTrue(store.recentTurns("nie-gesehen").isEmpty(), "unbekannter Sprecher ⇒ leere Liste")
    }
}
