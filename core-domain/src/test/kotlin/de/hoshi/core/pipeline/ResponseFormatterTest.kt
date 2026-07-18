package de.hoshi.core.pipeline

import de.hoshi.core.dto.SmartHomeAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Portiert aus Hoshi 0.5 (de.hoshi.app.streaming.IntentResponseFormatterTest).
 * Entkopplung: kein Spring; `SmartHomeAction` aus `de.hoshi.core.dto`.
 *
 * Anti-Repeat-Ring der Tiefe 3 + Pool-Größen ≥6 → keine Variante darf innerhalb
 * von 3 aufeinanderfolgenden Aufrufen wiederkommen.
 */
class ResponseFormatterTest {

    private val formatter = ResponseFormatter()

    @Test
    fun `lightOn ohne Raum gibt fixen Wert`() {
        assertEquals("Licht ist an.", formatter.lightOn(null))
    }

    @Test
    fun `lightOn mit Raum produziert variierende Antworten`() {
        val outputs = (1..30).map { formatter.lightOn("Wohnzimmer") }.toSet()
        assertTrue(outputs.size >= 2, "Expected ≥2 unique variants, got ${outputs.size}: $outputs")
    }

    @Test
    fun `Anti-Repeat-Tiefe verhindert Wiederholung in 3-Fenster`() {
        val outputs = (1..100).map { formatter.lightOn("Wohnzimmer") }
        for (i in 2 until outputs.size) {
            val window = outputs.subList(i - 2, i + 1)
            assertTrue(
                window[0] != window[1] || window[1] != window[2] || window[0] != window[2],
                "Gefunden 3 gleiche in Folge an Position $i: $window"
            )
        }
        for (i in 1 until outputs.size) {
            assertNotEquals(outputs[i - 1], outputs[i],
                "Position $i: ${outputs[i]} folgt direkt auf ${outputs[i - 1]}")
        }
    }

    @Test
    fun `Raum-Platzhalter wird ersetzt und kapitalisiert`() {
        val out = formatter.lightOn("wohnzimmer")
        assertTrue(out.contains("Wohnzimmer"), "Erwarte kapitalisiertes Wohnzimmer in: $out")
    }

    @Test
    fun `lightDim mit Raum und Wert formatiert beide Platzhalter`() {
        val out = formatter.lightDim("Wohnzimmer", 50)
        assertTrue(out.contains("Wohnzimmer") || out.contains("Wohnzimmer".lowercase()), "Erwarte Wohnzimmer in: $out")
        assertTrue(out.contains("50"), "Erwarte 50 in: $out")
    }

    @Test
    fun `lightDim ohne Raum aber mit Wert nutzt no-room-Pool`() {
        val out = formatter.lightDim(null, 75)
        assertTrue(out.contains("75"), "Erwarte 75 in: $out")
        assertTrue(!out.contains("{"), "Keine ungefüllten Platzhalter: $out")
    }

    @Test
    fun `lightDim ganz ohne Werte gibt Default zurück`() {
        assertEquals("Ist gedimmt.", formatter.lightDim(null, null))
    }

    @Test
    fun `kleine Pools degradieren Anti-Repeat-Tiefe ohne Endlosschleife`() {
        val outputs = (1..50).map { formatter.scene() }
        val unique = outputs.toSet()
        assertTrue(unique.size >= 2, "Pool nutzt nicht genug Varianten: $unique")
    }

    @Test
    fun `unknown-Pool produziert keine direkten Wiederholungen`() {
        val outputs = (1..40).map { formatter.unknown() }
        for (i in 1 until outputs.size) {
            assertNotEquals(outputs[i - 1], outputs[i])
        }
    }

    // ── Cloud-Consent-Pools ──────────────────────────────────────────────────

    @Test
    fun `cloudConsentAsk Pool hat mindestens 4 distinct Phrasen`() {
        val outputs = (1..40).map { formatter.cloudConsentAsk() }.toSet()
        assertTrue(outputs.size >= 4, "cloudConsentAsk braucht >=4 Varianten, hatte: $outputs")
    }

    @Test
    fun `cloudConsentAsk klingt nach Frage`() {
        repeat(15) {
            val msg = formatter.cloudConsentAsk()
            assertTrue(msg.contains("?"), "cloudConsentAsk muss als Frage erkennbar sein: '$msg'")
            assertTrue(!msg.contains("??"), "kein Fragezeichen-Overload: '$msg'")
        }
    }

    @Test
    fun `cloudConsentAccept ist kurz (max 7 Woerter)`() {
        repeat(15) {
            val msg = formatter.cloudConsentAccept()
            val wordCount = msg.split(Regex("\\s+")).count { it.isNotBlank() }
            assertTrue(wordCount <= 7, "cloudConsentAccept zu lang ($wordCount Wörter): '$msg'")
        }
    }

    @Test
    fun `cloudConsentDecline klingt nach Zustimmung mit lokalem Fortsetzen`() {
        repeat(15) {
            val msg = formatter.cloudConsentDecline()
            val acknowledges = msg.contains("okay", ignoreCase = true) ||
                msg.contains("klar", ignoreCase = true) ||
                msg.contains("verstand", ignoreCase = true) ||
                msg.contains("gut", ignoreCase = true)
            assertTrue(acknowledges, "cloudConsentDecline muss zustimmend klingen: '$msg'")
        }
    }

    @Test
    fun `alle Cloud-Consent-Pools liefern mindestens zwei distinct Phrasen`() {
        val pools = mapOf<String, (Int) -> String>(
            "cloudConsentAsk" to { _ -> formatter.cloudConsentAsk() },
            "cloudConsentAccept" to { _ -> formatter.cloudConsentAccept() },
            "cloudConsentDecline" to { _ -> formatter.cloudConsentDecline() },
        )
        for ((name, gen) in pools) {
            val seen = (0..29).map { gen(it) }.toSet()
            assertTrue(seen.size >= 2, "$name liefert nur ${seen.size} distinct: $seen")
        }
    }

    // ── NoEffect-Ehrlichkeit ──────────────────────────────────────────────────

    /** Kein kaltes/technisches Wort in einer NoEffect-Phrase. */
    private fun assertWarmAndHonest(msg: String) {
        val forbidden = listOf(
            "offline", "unavailable", "unknown", "noeffect", "no effect",
            "connection", "reagiert nicht", "nicht verbunden", "service",
            "http", "websocket", "error", "fehler", "timeout", "null",
        )
        for (f in forbidden) {
            assertTrue(!msg.lowercase().contains(f), "NoEffect-Phrase darf nicht technisch/kalt sein ('$f'): '$msg'")
        }
        assertTrue(!msg.contains("{"), "Keine ungefüllten Platzhalter: '$msg'")
        assertTrue(msg.lowercase().contains("schon"), "NoEffect-Phrase soll 'schon' tragen: '$msg'")
    }

    @Test
    fun `lightOff NoEffect ist ehrlich und NICHT die Erfolgsphrase`() {
        val success = (1..30).map { formatter.lightOff("Wohnzimmer") }.toSet()
        repeat(20) {
            val msg = formatter.lightOffNoEffect("Wohnzimmer")
            assertTrue(msg !in success, "NoEffect darf nicht mit Erfolgsphrase kollidieren: '$msg' in $success")
            assertWarmAndHonest(msg)
            assertTrue(msg.contains("Wohnzimmer"), "Raum erwartet: '$msg'")
        }
    }

    @Test
    fun `lightOn NoEffect sagt war schon an statt Erfolg`() {
        val success = (1..30).map { formatter.lightOn("Schlafzimmer") }.toSet()
        repeat(20) {
            val msg = formatter.lightOnNoEffect("Schlafzimmer")
            assertTrue(msg !in success, "NoEffect != Erfolg: '$msg'")
            assertWarmAndHonest(msg)
        }
    }

    @Test
    fun `lightOff NoEffect ohne Raum bleibt warm und ehrlich`() {
        repeat(20) { assertWarmAndHonest(formatter.lightOffNoEffect(null)) }
    }

    @Test
    fun `cover NoEffect sagt war schon offen bzw zu`() {
        repeat(15) {
            assertWarmAndHonest(formatter.coverOpenNoEffect())
            assertWarmAndHonest(formatter.coverCloseNoEffect())
        }
    }

    @Test
    fun `climate NoEffect nennt Wert und bleibt ehrlich`() {
        repeat(15) {
            val msg = formatter.climateNoEffect("Bad", 21)
            assertTrue(msg.contains("21"), "Wert erwartet: '$msg'")
            assertWarmAndHonest(msg)
        }
    }

    @Test
    fun `noEffect Dispatch waehlt pro Action die ehrliche Phrase`() {
        val off = formatter.noEffect(SmartHomeAction.LIGHT_OFF, "Küche")
        assertWarmAndHonest(off)
        val on = formatter.noEffect(SmartHomeAction.LIGHT_ON, "Küche")
        assertWarmAndHonest(on)
        val dim = formatter.noEffect(SmartHomeAction.LIGHT_DIM, "Küche", 50)
        assertWarmAndHonest(dim)
        val climate = formatter.noEffect(SmartHomeAction.CLIMATE_SET, "Küche", 22)
        assertTrue(climate.contains("22"), "Wert erwartet: '$climate'")
        assertWarmAndHonest(climate)
        assertWarmAndHonest(formatter.noEffect(SmartHomeAction.SCENE_ACTIVATE, null))
        assertWarmAndHonest(formatter.noEffect(SmartHomeAction.UNKNOWN, null))
    }

    @Test
    fun `noEffect LIGHT_OFF unterscheidet sich klar vom Erfolg LIGHT_OFF`() {
        val successSet = (1..40).map { formatter.lightOff("Flur") }.toSet()
        val noEffectSet = (1..40).map { formatter.lightOffNoEffect("Flur") }.toSet()
        val overlap = successSet intersect noEffectSet
        assertTrue(overlap.isEmpty(), "Erfolg- und NoEffect-Phrasen dürfen nicht überlappen: $overlap")
    }

    @Test
    fun `NoEffect-Pools liefern mindestens zwei distinct Phrasen`() {
        val pools = listOf<() -> String>(
            { formatter.lightOffNoEffect("Bad") },
            { formatter.lightOnNoEffect("Bad") },
            { formatter.lightOffNoEffect(null) },
            { formatter.lightOnNoEffect(null) },
            { formatter.coverOpenNoEffect() },
            { formatter.coverCloseNoEffect() },
            { formatter.genericNoEffect() },
        )
        for ((i, gen) in pools.withIndex()) {
            val seen = (0..29).map { gen() }.toSet()
            assertTrue(seen.size >= 2, "Pool $i liefert nur ${seen.size} distinct: $seen")
        }
    }

    // ── PartialOffline ────────────────────────────────────────────────────────

    @Test
    fun `partialOffline ersetzt alle Slots und leakt keine Engine-Woerter`() {
        val engineWords = listOf("offline", "unavailable", "unknown", "entity", "timeout", "{")
        val cases = listOf(
            Triple(SmartHomeAction.LIGHT_ON, 2, 1),
            Triple(SmartHomeAction.LIGHT_ON, 1, 1),
            Triple(SmartHomeAction.LIGHT_ON, 3, 2),
            Triple(SmartHomeAction.LIGHT_OFF, 2, 1),
            Triple(SmartHomeAction.LIGHT_OFF, 1, 2),
        )
        for ((action, applied, offline) in cases) {
            repeat(20) {
                val s = formatter.partialOffline(action, "wohnzimmer", applied, offline)
                assertTrue(s.isNotBlank(), "leere Antwort für $action a=$applied o=$offline")
                assertTrue(s.contains("Wohnzimmer"), "Raum nicht ersetzt: '$s'")
                for (w in engineWords) {
                    assertTrue(!s.lowercase().contains(w), "Engine-Wort '$w' geleakt: '$s'")
                }
            }
        }
    }

    @Test
    fun `partialOffline mit applied 1 erzeugt keinen Zahl-Anker`() {
        repeat(50) {
            val s = formatter.partialOffline(SmartHomeAction.LIGHT_ON, "bad", applied = 1, offline = 1)
            assertTrue(!s.contains("1 sind"), "Zahl-Anker bei applied==1 geleakt: '$s'")
        }
    }

    @Test
    fun `partialOffline ohne Raum nutzt warmen Fallback ohne Template-Leak`() {
        val s = formatter.partialOffline(SmartHomeAction.LIGHT_ON, null, applied = 2, offline = 1)
        assertTrue(s.isNotBlank())
        assertTrue(!s.contains("{"), "Template-Leak: '$s'")
    }

    // ── LIGHT_DIM NoEffect ────────────────────────────────────────────────────

    @Test
    fun `lightDimNoEffect nennt Wert ehrlich-ungefaehr und bleibt warm`() {
        repeat(20) {
            val s = formatter.lightDimNoEffect("Wohnzimmer", 50)
            assertTrue(s.contains("50"), "Wert erwartet: '$s'")
            assertTrue(s.contains("Wohnzimmer"), "Raum erwartet: '$s'")
            assertTrue(s.contains("ungefähr") || s.contains("etwa"), "Toleranz-Wort erwartet: '$s'")
            assertTrue(!s.contains("{"), "Template-Leak: '$s'")
        }
    }

    @Test
    fun `noEffect Dispatch route LIGHT_DIM auf die wert-bewusste DIM-Phrase`() {
        val s = formatter.noEffect(SmartHomeAction.LIGHT_DIM, "Bad", 30)
        assertTrue(s.contains("30"), "DIM-NoEffect muss den Wert nennen: '$s'")
        assertTrue(s.contains("ungefähr") || s.contains("etwa"), "ehrlich-ungefähr: '$s'")
    }

    @Test
    fun `unsupported ist action-aware und warm`() {
        assertTrue(formatter.unsupported(SmartHomeAction.COVER_OPEN).contains("Rollo"), "Cover→Rollo")
        assertTrue(formatter.unsupported(SmartHomeAction.CLIMATE_SET).lowercase().let {
            it.contains("heizung") || it.contains("thermostat")
        }, "Climate→Heizung/Thermostat")
        assertTrue(formatter.unsupported(SmartHomeAction.SCENE_ACTIVATE).lowercase().let {
            it.contains("szene") || it.contains("stimmung")
        }, "Scene→Szene/Stimmung")
    }

    @Test
    fun `prerenderAcks ist endlich und frei von Template-Leaks`() {
        val acks = formatter.prerenderAcks()
        assertTrue(acks.isNotEmpty(), "Prerender-Menge darf nicht leer sein")
        for (a in acks) {
            assertTrue(!a.contains("{"), "Template-Leak in Prerender-Ack: '$a'")
        }
    }
}
