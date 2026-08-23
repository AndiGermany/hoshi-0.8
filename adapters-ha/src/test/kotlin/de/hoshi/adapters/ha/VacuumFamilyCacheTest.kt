package de.hoshi.adapters.ha

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * **VacuumFamilyCacheTest** — der Cache-Carry aus Andis Nachtrag (2026-08-21,
 * wörtlich: „Beim Sauger, ja, das ist Lärm, aber dann müssen wir die Daten
 * cachen und verwenden. Meistens ist er einfach im Energiesparmodus.").
 *
 * [VacuumFamily.carryCache] ist eine REINE Funktion (Snapshot rein, Snapshot
 * raus) — kein HA, kein Netz, keine echte Uhr. Bewiesen: unavailable ⇒
 * Snapshot-Werte + Marker · Rückkehr ⇒ Marker weg (live gewinnt IMMER) ·
 * Obergrenze überschritten ⇒ ehrliches Unavailable-Bild · Fremd-Domains
 * unberührt · der Draht bleibt für alte Parser lesbar.
 */
class VacuumFamilyCacheTest {

    private val now: Instant = Instant.parse("2026-08-21T12:00:00Z")
    private val seenAt: Instant = Instant.parse("2026-08-21T09:30:00Z") // 2,5 h alt
    private val maxAge: Duration = Duration.ofHours(24)

    private fun entity(
        entityId: String,
        state: String?,
        attrs: Map<String, String> = emptyMap(),
        lastKnown: LastKnownEntityState? = null,
    ) = HomeRegistryEntity(
        entityId = entityId,
        domain = entityId.substringBefore('.'),
        name = entityId,
        state = state,
        attrs = attrs,
        lastKnown = lastKnown,
    )

    /** Der schlafende Sauger: `unavailable`, aber mit gemerktem Stand (wie ihn der Adapter anhängt). */
    private fun sleepingSnapshot(): HomeRegistrySnapshot = HomeRegistrySnapshot(
        areas = listOf(
            HomeRegistryArea(
                areaId = "wohnzimmer",
                label = "Wohnzimmer",
                entities = listOf(
                    entity(
                        "vacuum.roborock",
                        state = "unavailable",
                        lastKnown = LastKnownEntityState("docked", mapOf("battery_level" to "97"), seenAt.toString()),
                    ),
                    // Fremde Entity im selben Raum — darf NIE angefasst werden.
                    entity("light.decke", state = "unavailable", lastKnown = LastKnownEntityState("on", emptyMap(), seenAt.toString())),
                ),
            ),
        ),
        unassigned = listOf(
            entity(
                "sensor.roborock_batterie",
                state = "unavailable",
                lastKnown = LastKnownEntityState("97", mapOf("unit_of_measurement" to "%"), seenAt.toString()),
            ),
            // Gleicher Präfix, ANDERES Geraet (laengerer Stamm) — darf NIE angefasst werden.
            entity("sensor.roborock2_batterie", state = "unavailable", lastKnown = LastKnownEntityState("12", emptyMap(), seenAt.toString())),
        ),
    )

    private fun all(snapshot: HomeRegistrySnapshot): Map<String, HomeRegistryEntity> =
        (snapshot.areas.flatMap { it.entities } + snapshot.unassigned).associateBy { it.entityId }

    @Test
    fun `unavailable - Familie liefert gemerkte Werte MIT Cache-Marker`() {
        val out = all(VacuumFamily.carryCache(sleepingSnapshot(), now, maxAge))

        val vacuum = out.getValue("vacuum.roborock")
        assertEquals("docked", vacuum.state)
        assertEquals("97", vacuum.attrs["battery_level"])
        assertEquals(seenAt.toEpochMilli(), vacuum.fromCacheSinceMs)

        val battery = out.getValue("sensor.roborock_batterie")
        assertEquals("97", battery.state)
        assertEquals("%", battery.attrs["unit_of_measurement"])
        assertEquals(seenAt.toEpochMilli(), battery.fromCacheSinceMs)
    }

    @Test
    fun `Cache-Carry laesst lastKnown zusaetzlich stehen`() {
        val out = all(VacuumFamily.carryCache(sleepingSnapshot(), now, maxAge))

        assertNotNull(out.getValue("vacuum.roborock").lastKnown, "lastKnown darf kein Leser verlieren")
    }

    @Test
    fun `fremde Domain und fremder Stamm bleiben unberuehrt`() {
        val out = all(VacuumFamily.carryCache(sleepingSnapshot(), now, maxAge))

        val light = out.getValue("light.decke")
        assertEquals("unavailable", light.state)
        assertNull(light.fromCacheSinceMs)

        val other = out.getValue("sensor.roborock2_batterie")
        assertEquals("unavailable", other.state)
        assertNull(other.fromCacheSinceMs)
    }

    @Test
    fun `Rueckkehr - live gewinnt, Marker verschwindet`() {
        val awake = HomeRegistrySnapshot(
            areas = listOf(
                HomeRegistryArea(
                    "wohnzimmer",
                    "Wohnzimmer",
                    listOf(entity("vacuum.roborock", state = "cleaning", attrs = mapOf("battery_level" to "42"))),
                ),
            ),
            unassigned = emptyList(),
        )

        val out = all(VacuumFamily.carryCache(awake, now, maxAge))

        val vacuum = out.getValue("vacuum.roborock")
        assertEquals("cleaning", vacuum.state)
        assertEquals("42", vacuum.attrs["battery_level"])
        assertNull(vacuum.fromCacheSinceMs, "Ein LIVE-Wert darf nie als Cache markiert werden")
    }

    @Test
    fun `Obergrenze ueberschritten - ehrliches Unavailable-Bild statt Cache`() {
        // Stand 2,5 h alt, Obergrenze 1 h ⇒ kein Carry.
        val out = all(VacuumFamily.carryCache(sleepingSnapshot(), now, Duration.ofHours(1)))

        val vacuum = out.getValue("vacuum.roborock")
        assertEquals("unavailable", vacuum.state)
        assertNull(vacuum.fromCacheSinceMs)
        assertNotNull(vacuum.lastKnown, "Das heutige Unavailable-Bild behaelt lastKnown")
    }

    @Test
    fun `Obergrenze ZERO schaltet den Carry ab`() {
        val out = all(VacuumFamily.carryCache(sleepingSnapshot(), now, Duration.ZERO))

        assertEquals("unavailable", out.getValue("vacuum.roborock").state)
        assertNull(out.getValue("vacuum.roborock").fromCacheSinceMs)
    }

    @Test
    fun `kein Sauger im Snapshot - Snapshot unveraendert`() {
        val noVacuum = HomeRegistrySnapshot(
            areas = listOf(HomeRegistryArea("wohnzimmer", "Wohnzimmer", listOf(entity("light.decke", "unavailable")))),
            unassigned = emptyList(),
        )

        assertEquals(noVacuum, VacuumFamily.carryCache(noVacuum, now, maxAge))
    }

    @Test
    fun `find waehlt denselben Sauger wie das FE - Areas vor unassigned`() {
        val snapshot = HomeRegistrySnapshot(
            areas = listOf(HomeRegistryArea("flur", "Flur", listOf(entity("vacuum.im_raum", "docked")))),
            unassigned = listOf(entity("vacuum.ohne_raum", "docked")),
        )

        assertEquals("vacuum.im_raum", VacuumFamily.find(snapshot)?.entityId)
    }

    @Test
    fun `Alt-Parser-Probe - fromCacheSinceMs fehlt im JSON solange nichts gecacht ist`() {
        val mapper = ObjectMapper()
        val live = entity("vacuum.roborock", state = "docked")

        val json = mapper.writeValueAsString(live)

        assertFalse(json.contains("fromCacheSinceMs"), "Ohne Cache muss der Draht byte-identisch bleiben: $json")
    }

    @Test
    fun `Alt-Parser-Probe - gecachte Entity traegt das Feld und bleibt sonst gleich`() {
        val mapper = ObjectMapper()
        val cached = all(VacuumFamily.carryCache(sleepingSnapshot(), now, maxAge)).getValue("vacuum.roborock")

        val json = mapper.writeValueAsString(cached)

        assertTrue(json.contains("\"fromCacheSinceMs\":${seenAt.toEpochMilli()}"), json)
        // Alle bisherigen Felder stehen weiterhin an ihrem Platz (ein alter Parser liest sie unveraendert).
        listOf("entityId", "domain", "name", "labels", "state", "attrs", "lastKnown").forEach {
            assertTrue(json.contains("\"$it\""), "Feld $it fehlt im Draht: $json")
        }
    }

    // ── isLive: die ANZEIGE darf cachen, die TAT nicht (Bug 23.08.2026) ──────
    // HA verwirft Service-Calls auf `unavailable` Entities still und quittiert
    // trotzdem 200 (helpers/service.py#entity_service_call). Der Carry oben ist
    // fuer die Kachel richtig — als Zustellweg ist er eine Luege.

    @Test
    fun `isLive - live brauchbarer Zustand ist zustellbar`() {
        assertTrue(VacuumFamily.isLive(entity("vacuum.roborock", state = "docked")))
        assertTrue(VacuumFamily.isLive(entity("vacuum.roborock", state = "cleaning")))
    }

    @Test
    fun `isLive - unavailable, unknown und null sind es nicht`() {
        assertFalse(VacuumFamily.isLive(entity("vacuum.roborock", state = "unavailable")))
        assertFalse(VacuumFamily.isLive(entity("vacuum.roborock", state = "unknown")))
        assertFalse(VacuumFamily.isLive(entity("vacuum.roborock", state = null)))
    }

    @Test
    fun `isLive - der Cache-Carry macht docked NICHT zustellbar`() {
        val carried = all(VacuumFamily.carryCache(sleepingSnapshot(), now, maxAge)).getValue("vacuum.roborock")

        // Genau die Falle: der Zustand LIEST sich zustellbar …
        assertEquals("docked", carried.state)
        // … ist es aber nicht, und nur das Cache-Flag verraet es.
        assertNotNull(carried.fromCacheSinceMs)
        assertFalse(VacuumFamily.isLive(carried), "Ein gemerkter Zustand ist kein Zustellweg")
    }

    @Test
    fun `isLive - zurueck im Netz ist wieder zustellbar (der Carry laesst live in Ruhe)`() {
        // Aufgewacht: LIVE `cleaning`, der gemerkte Stand haengt noch dran.
        val wokeUp = HomeRegistrySnapshot(
            areas = listOf(
                HomeRegistryArea(
                    areaId = "wohnzimmer",
                    label = "Wohnzimmer",
                    entities = listOf(
                        entity(
                            "vacuum.roborock",
                            state = "cleaning",
                            lastKnown = LastKnownEntityState("docked", emptyMap(), seenAt.toString()),
                        ),
                    ),
                ),
            ),
            unassigned = emptyList(),
        )

        val live = all(VacuumFamily.carryCache(wokeUp, now, maxAge)).getValue("vacuum.roborock")

        assertEquals("cleaning", live.state, "Live gewinnt IMMER")
        assertNull(live.fromCacheSinceMs)
        assertTrue(VacuumFamily.isLive(live))
    }
}
