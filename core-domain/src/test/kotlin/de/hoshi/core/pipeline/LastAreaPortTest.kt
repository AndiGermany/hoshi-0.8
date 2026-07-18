package de.hoshi.core.pipeline

import de.hoshi.core.tools.ToolAreas
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Direkte Beweise für den [InMemoryLastAreaStore] + [ToolAreas.mentionsRoom]. */
class LastAreaPortTest {

    @Test
    fun `merkt und liefert die Area pro Sprecher (isoliert)`() {
        val store = InMemoryLastAreaStore()
        store.remember("alice", "kuche")
        store.remember("bob", "schlafzimmer")
        assertEquals("kuche", store.lastArea("alice"))
        assertEquals("schlafzimmer", store.lastArea("bob"))
        assertNull(store.lastArea("carol"), "kein Eintrag ⇒ null")
    }

    @Test
    fun `letzte Area gewinnt (Overwrite)`() {
        val store = InMemoryLastAreaStore()
        store.remember("alice", "kuche")
        store.remember("alice", "wohnzimmer")
        assertEquals("wohnzimmer", store.lastArea("alice"))
    }

    @Test
    fun `anonyme und Gast-ids werden nicht gemerkt und liefern keinen Recall`() {
        val store = InMemoryLastAreaStore()
        listOf("", "unknown", "gast").forEach { id ->
            store.remember(id, "kuche")
            assertNull(store.lastArea(id), "anonyme id '$id' ⇒ kein Recall")
        }
    }

    @Test
    fun `NONE merkt nie und erinnert nie`() {
        LastAreaPort.NONE.remember("alice", "kuche")
        assertNull(LastAreaPort.NONE.lastArea("alice"))
    }

    @Test
    fun `isAnonymous erkennt fehlende und Sammel-ids`() {
        assertTrue(LastAreaPort.isAnonymous(null))
        assertTrue(LastAreaPort.isAnonymous(""))
        assertTrue(LastAreaPort.isAnonymous("unknown"))
        assertTrue(LastAreaPort.isAnonymous("gast"))
        assertFalse(LastAreaPort.isAnonymous("alice"))
    }

    @Test
    fun `mentionsRoom trifft genannte Raeume token-genau`() {
        assertTrue(ToolAreas.mentionsRoom("mach das Licht in der Küche an"))
        assertTrue(ToolAreas.mentionsRoom("Licht im Wohnzimmer aus"))
        assertTrue(ToolAreas.mentionsRoom("turn on the kitchen light"))
        assertFalse(ToolAreas.mentionsRoom("schalt das Licht wieder aus"), "kein Raum genannt")
        assertFalse(ToolAreas.mentionsRoom("mach das Licht aus"))
        assertFalse(ToolAreas.mentionsRoom(""))
    }
}
