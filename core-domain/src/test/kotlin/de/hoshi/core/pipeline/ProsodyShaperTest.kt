package de.hoshi.core.pipeline

import de.hoshi.core.dto.PersonaEmotion
import de.hoshi.core.dto.ProsodyTone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Portiert aus Hoshi 0.5 (de.hoshi.app.streaming.ProsodyShaperTest), Imports auf de.hoshi.core.* angepasst. */
class ProsodyShaperTest {

    @Test
    fun `toneFor mappt Emotion auf drei Tonfaelle`() {
        assertEquals(ProsodyTone.CALM, ProsodyShaper.toneFor(PersonaEmotion.CALM))
        assertEquals(ProsodyTone.ENERGETIC, ProsodyShaper.toneFor(PersonaEmotion.CHEERFUL))
        assertEquals(ProsodyTone.NORMAL, ProsodyShaper.toneFor(PersonaEmotion.WARM))
        assertEquals(ProsodyTone.NORMAL, ProsodyShaper.toneFor(PersonaEmotion.FOCUSED))
        assertEquals(ProsodyTone.NORMAL, ProsodyShaper.toneFor(PersonaEmotion.NEUTRAL))
    }

    @Test
    fun `NORMAL und ENERGETIC reichen Text unveraendert durch`() {
        val t = "Hab's! Mega, oder?!"
        assertEquals(t, ProsodyShaper.shape(t, ProsodyTone.NORMAL))
        assertEquals(t, ProsodyShaper.shape(t, ProsodyTone.ENERGETIC))
    }

    @Test
    fun `CALM daempft Ausrufe zu Punkten`() {
        assertEquals("Schön ruhig.", ProsodyShaper.shape("Schön ruhig!", ProsodyTone.CALM))
        assertEquals("Mega.", ProsodyShaper.shape("Mega!!!", ProsodyTone.CALM))
    }

    @Test
    fun `CALM erhaelt Fragen trotz Ausruf`() {
        assertEquals("Wirklich?", ProsodyShaper.shape("Wirklich?!", ProsodyTone.CALM))
        assertEquals("Echt?", ProsodyShaper.shape("Echt!?", ProsodyTone.CALM))
        assertEquals(
            "Alles gut? Schlaf schön.",
            ProsodyShaper.shape("Alles gut? Schlaf schön!", ProsodyTone.CALM),
        )
    }

    @Test
    fun `CALM laesst blanken und normalen Text in Ruhe`() {
        assertEquals("", ProsodyShaper.shape("", ProsodyTone.CALM))
        assertEquals("Gute Nacht.", ProsodyShaper.shape("Gute Nacht.", ProsodyTone.CALM))
    }
}
