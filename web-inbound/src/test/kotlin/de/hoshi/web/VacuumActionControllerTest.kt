package de.hoshi.web

import com.sun.net.httpserver.HttpServer
import de.hoshi.adapters.ha.HaHomeRegistryAdapter
import de.hoshi.adapters.ha.HaServiceCaller
import de.hoshi.adapters.ha.ServiceCallOutcome
import de.hoshi.core.port.TurnTrace
import de.hoshi.core.port.TurnTracePort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.ResponseEntity
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * **VacuumActionControllerTest** — der Tat-Vertrag der Sauger-Knöpfe, OHNE
 * Spring-Context und OHNE echtes HA (Muster [HomeEditControllerTest]): ein
 * JDK-`HttpServer` spielt den READ-Katalog für die Existenz-Prüfung, der
 * HA-Service-Call ist ein Fake-[HaServiceCaller] (Lambda).
 *
 * Abgedeckt: Service-Call-Wiring (richtiger Service, richtige Entity) · 409
 * ohne Sauger · 409 bei HA-Decke zu · **409 beim schlafenden Sauger (das
 * Zustellbarkeits-Tor, s. u.)** · 400 bei unbekannter Aktion · ehrliche
 * Fehler-Durchreichung MIT HA-Statuscode · Diary-Pin je Aktion (inkl. der
 * `toolCallRan`-Trennung zwischen „gerufen" und „vorher abgelehnt") · kein
 * optimistisches Umschreiben. Die 401-Wand deckt [PerimeterWallTest] ab.
 *
 * **Das Fake-HA spricht seit 23.08.2026 auch `GET /api/states`** — vorher tat
 * es das nicht, jede Test-Entity blieb also bei `state = null`, und der Test
 * „start ruft vacuum.start" bewies in Wahrheit, dass wir auch einem Sauger
 * hinterhertelefonieren, den HA gar nicht kennt. Genau das war der Bug (Andi:
 * „da steht, dass der auftrag an HA gegeben wurde, aber der sauger startet
 * nicht"). Die Zustände sind jetzt Teil der Fixture, weil sie Teil der Frage
 * sind.
 */
class VacuumActionControllerTest {

    /** Katalog MIT Sauger (die Zustände kommen aus `GET /api/states`, s. [statesOf]). */
    private val withVacuum =
        "wohnzimmer::Wohnzimmer" +
            "@@ENTITIES@@" +
            "vacuum.roborock::wohnzimmer::Staubsauger::"

    /** Katalog OHNE jeden Sauger. */
    private val withoutVacuum =
        "wohnzimmer::Wohnzimmer" +
            "@@ENTITIES@@" +
            "light.wohnzimmer_decke::wohnzimmer::Deckenlicht::"

    /** HA's `GET /api/states`-Antwort für genau unseren Sauger. */
    private fun statesOf(state: String): String =
        """[{"entity_id":"vacuum.roborock","state":"$state","attributes":{}}]"""

    /**
     * Fake-HA mit BEIDEN Nähten, die der [HaHomeRegistryAdapter] liest.
     * [states] ist ein Supplier, damit ein Test den Zustand MITTEN im Lauf
     * umlegen kann (Sauger schläft ein) — genau das braucht der Cache-Carry-Test.
     */
    private fun withHa(
        templateBody: String,
        states: () -> String = { statesOf("docked") },
        block: (baseUrl: String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/template") { ex ->
            val bytes = templateBody.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/states") { ex ->
            val bytes = states().toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    /** Sammelt die Diary-Zeilen, die der Controller schreibt. */
    private class RecordingDiary : TurnTracePort {
        val lines = CopyOnWriteArrayList<TurnTrace>()
        override fun record(trace: TurnTrace) {
            lines.add(trace)
        }
    }

    private data class Call(val domain: String, val service: String, val entityId: String)

    private fun controller(
        baseUrl: String,
        caller: HaServiceCaller,
        haEnabled: Boolean = true,
        diary: TurnTracePort = TurnTracePort.NOOP,
    ) = controller(HaHomeRegistryAdapter(baseUrl = baseUrl, token = "secret-token"), caller, haEnabled, diary)

    /** Variante mit MITGEBRACHTEM Adapter — für Tests, die seinen Cache selbst füllen. */
    private fun controller(
        adapter: HaHomeRegistryAdapter,
        caller: HaServiceCaller,
        haEnabled: Boolean = true,
        diary: TurnTracePort = TurnTracePort.NOOP,
    ) = VacuumActionController(
        registryAdapter = adapter,
        serviceCaller = caller,
        haEnabled = haEnabled,
        turnTrace = diary,
    )

    /** Blockt den `Mono<ResponseEntity<Any>>` synchron (Muster [HomeEditControllerTest]). */
    private fun act(c: VacuumActionController, action: String): ResponseEntity<Any> =
        c.action(action).block(Duration.ofSeconds(5))!!

    @Test
    fun `start ruft vacuum start auf der gefundenen Entity`() = withHa(withVacuum) { url ->
        val calls = CopyOnWriteArrayList<Call>()
        val res = act(
            controller(url, { d, s, e -> calls.add(Call(d, s, e)); ServiceCallOutcome.Accepted(200) }),
            "start",
        )

        assertEquals(200, res.statusCode.value())
        assertEquals(listOf(Call("vacuum", "start", "vacuum.roborock")), calls)
    }

    @Test
    fun `zur Basis ruft vacuum return_to_base auf der gefundenen Entity`() = withHa(withVacuum) { url ->
        val calls = CopyOnWriteArrayList<Call>()
        val res = act(
            controller(url, { d, s, e -> calls.add(Call(d, s, e)); ServiceCallOutcome.Accepted(200) }),
            "return_to_base",
        )

        assertEquals(200, res.statusCode.value())
        assertEquals(listOf(Call("vacuum", "return_to_base", "vacuum.roborock")), calls)
    }

    @Test
    fun `200-Body meldet ANGENOMMEN mit HA-Beleg, nie einen Zustand`() = withHa(withVacuum) { url ->
        val res = act(controller(url, { _, _, _ -> ServiceCallOutcome.Accepted(200) }), "start")

        val body = res.body as VacuumActionResult
        assertEquals("start", body.action)
        assertEquals("vacuum.roborock", body.entityId)
        assertTrue(body.accepted)
        assertEquals(200, body.haStatus)
        // Kagami: der Body trägt KEIN Zustandsfeld — der Zustand kommt aus dem Polling.
        assertTrue(
            VacuumActionResult::class.java.declaredFields.none { it.name == "state" || it.name == "status" },
            "Der Antwort-Body darf keinen Zustand behaupten",
        )
    }

    @Test
    fun `kein Sauger in der Registry - 409 vacuum-not-found, KEIN HA-Call`() = withHa(withoutVacuum) { url ->
        val calls = CopyOnWriteArrayList<Call>()
        val res = act(controller(url, { d, s, e -> calls.add(Call(d, s, e)); ServiceCallOutcome.Accepted(200) }), "start")

        assertEquals(409, res.statusCode.value())
        assertEquals("vacuum-not-found", (res.body as SettingsError).error)
        assertTrue(calls.isEmpty(), "Ohne Sauger darf HA nie gerufen werden")
    }

    // ── Das Zustellbarkeits-Tor (Bug 23.08.2026) ────────────────────────────
    // HA quittiert einen Service-Call auf eine `unavailable` Entity mit HTTP 200
    // und laesst ihn kommentarlos fallen (helpers/service.py#entity_service_call,
    // HA 2025.4.4 Z. 976-1002). Ein Versuch waere also kein Versuch, sondern nur
    // ein Haekchen — darum darf hier NICHTS rausgehen. S. RESULT.md.

    @Test
    fun `schlafender Sauger - 409 vacuum-asleep, KEIN HA-Call`() =
        withHa(withVacuum, states = { statesOf("unavailable") }) { url ->
            val calls = CopyOnWriteArrayList<Call>()
            val res = act(controller(url, { d, s, e -> calls.add(Call(d, s, e)); ServiceCallOutcome.Accepted(200) }), "start")

            assertEquals(409, res.statusCode.value())
            val err = res.body as SettingsError
            assertEquals("vacuum-asleep", err.error)
            assertTrue(
                calls.isEmpty(),
                "HA nimmt so einen Call mit 200 an und verwirft ihn — wir duerfen ihn gar nicht erst senden",
            )
            // Der Satz muss den Grund tragen, nicht nur ein Nein.
            assertTrue(err.message.contains("Energiesparmodus"), "Kein Grund im Satz: ${err.message}")
            assertTrue(err.message.contains("NICHTS gesendet"), "Der Satz behauptet nicht klar genug, dass nichts passierte")
        }

    @Test
    fun `unbekannter Zustand ist kein Zustellweg - 409 vacuum-asleep`() =
        withHa(withVacuum, states = { statesOf("unknown") }) { url ->
            val calls = CopyOnWriteArrayList<Call>()
            val res = act(controller(url, { d, s, e -> calls.add(Call(d, s, e)); ServiceCallOutcome.Accepted(200) }), "return_to_base")

            assertEquals(409, res.statusCode.value())
            assertEquals("vacuum-asleep", (res.body as SettingsError).error)
            assertTrue(calls.isEmpty())
        }

    /**
     * **Der Bug in seiner echten Gestalt.** Der Cache-Carry
     * ([de.hoshi.adapters.ha.VacuumFamily.carryCache]) liefert den schlafenden
     * Sauger als `docked` weiter — richtig fuer die ANZEIGE, und genau deshalb
     * war der „Start"-Knopf ueberhaupt sichtbar. Eine TAT darf sich darauf nicht
     * stuetzen: `state == "docked"` allein haette hier gesendet.
     */
    @Test
    fun `Cache-Carry zeigt docked, ist aber KEIN Zustellweg - 409 statt Haekchen`() {
        var asleep = false
        withHa(withVacuum, states = { statesOf(if (asleep) "unavailable" else "docked") }) { url ->
            val adapter = HaHomeRegistryAdapter(baseUrl = url, token = "secret-token")
            // 1. Der Sauger ist wach: der Adapter merkt sich `docked` als last-known-good.
            assertEquals("docked", vacuumState(adapter))
            // 2. Er schlaeft ein — invalidate() erzwingt den frischen Load (read-first).
            asleep = true
            adapter.invalidate()

            val calls = CopyOnWriteArrayList<Call>()
            val res = act(controller(adapter, { d, s, e -> calls.add(Call(d, s, e)); ServiceCallOutcome.Accepted(200) }), "start")

            assertEquals(409, res.statusCode.value())
            assertEquals("vacuum-asleep", (res.body as SettingsError).error)
            assertTrue(calls.isEmpty(), "Ein gemerkter Zustand ist kein Zustellweg")
        }
    }

    /** Gegenprobe zum Carry-Test: derselbe Aufbau, aber der Sauger bleibt wach ⇒ die Tat geht raus. */
    @Test
    fun `wacher Sauger nach Cache-Zyklus - die Tat geht raus`() {
        withHa(withVacuum, states = { statesOf("docked") }) { url ->
            val adapter = HaHomeRegistryAdapter(baseUrl = url, token = "secret-token")
            assertEquals("docked", vacuumState(adapter))
            adapter.invalidate()

            val calls = CopyOnWriteArrayList<Call>()
            val res = act(controller(adapter, { d, s, e -> calls.add(Call(d, s, e)); ServiceCallOutcome.Accepted(200) }), "start")

            assertEquals(200, res.statusCode.value())
            assertEquals(listOf(Call("vacuum", "start", "vacuum.roborock")), calls)
        }
    }

    @Test
    fun `Diary-Pin - schlafender Sauger mit toolCallRan false`() =
        withHa(withVacuum, states = { statesOf("unavailable") }) { url ->
            val diary = RecordingDiary()

            act(controller(url, { _, _, _ -> ServiceCallOutcome.Accepted(200) }, diary = diary), "start")

            val line = diary.lines.single()
            assertEquals("HOME_VACUUM_START", line.category)
            assertEquals(false, line.toolCallRan, "Vor dem Call abgelehnt ⇒ der Executor lief nicht")
            assertEquals("vacuum-asleep", line.error)
        }

    /** Liest den aktuellen Sauger-Zustand aus dem Adapter (Fixture-Helfer, keine Behauptung). */
    private fun vacuumState(adapter: HaHomeRegistryAdapter): String? =
        adapter.registry()?.areas?.flatMap { it.entities }?.firstOrNull { it.domain == "vacuum" }?.state

    @Test
    fun `HA-Decke zu - 409 vacuum-off, KEIN HA-Call`() = withHa(withVacuum) { url ->
        val calls = CopyOnWriteArrayList<Call>()
        val res = act(
            controller(url, { d, s, e -> calls.add(Call(d, s, e)); ServiceCallOutcome.Accepted(200) }, haEnabled = false),
            "start",
        )

        assertEquals(409, res.statusCode.value())
        assertEquals("vacuum-off", (res.body as SettingsError).error)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `unbekannte Aktion - 400, KEIN HA-Call`() = withHa(withVacuum) { url ->
        val calls = CopyOnWriteArrayList<Call>()
        val res = act(controller(url, { d, s, e -> calls.add(Call(d, s, e)); ServiceCallOutcome.Accepted(200) }), "send_command")

        assertEquals(400, res.statusCode.value())
        assertEquals("vacuum-unknown-action", (res.body as SettingsError).error)
        assertTrue(calls.isEmpty(), "Ein fremder Service darf nie ueber die URL erreichbar sein")
    }

    @Test
    fun `HA-Fehler wird mit Statuscode ehrlich durchgereicht`() = withHa(withVacuum) { url ->
        val res = act(
            controller(url, { _, _, _ -> ServiceCallOutcome.Failed("ha-http-503", httpStatus = 503) }),
            "start",
        )

        assertEquals(502, res.statusCode.value())
        val err = res.body as SettingsError
        assertEquals("vacuum-action-failed", err.error)
        assertTrue(err.message.contains("503"), "HA's Statuscode fehlt in der Meldung: ${err.message}")
    }

    @Test
    fun `HA antwortet gar nicht - ehrliche Meldung ohne erfundenen Status`() = withHa(withVacuum) { url ->
        val res = act(controller(url, { _, _, _ -> ServiceCallOutcome.Failed("exception:HttpTimeoutException") }), "start")

        assertEquals(502, res.statusCode.value())
        assertTrue((res.body as SettingsError).message.contains("nicht geantwortet"))
    }

    @Test
    fun `Registry unerreichbar - 502, KEIN blindes Schalten`() {
        val calls = CopyOnWriteArrayList<Call>()
        // Kein Server auf dem Port ⇒ der Adapter laedt nie ⇒ registry() bleibt null.
        val c = controller("http://127.0.0.1:1", { d, s, e -> calls.add(Call(d, s, e)); ServiceCallOutcome.Accepted(200) })

        val res = act(c, "start")

        assertEquals(502, res.statusCode.value())
        assertEquals("vacuum-unreachable", (res.body as SettingsError).error)
        assertTrue(calls.isEmpty(), "Ohne Wissen ueber den Sauger darf nicht blind geschaltet werden")
    }

    @Test
    fun `Diary-Pin - angenommene Tat mit toolCallRan true`() = withHa(withVacuum) { url ->
        val diary = RecordingDiary()

        act(controller(url, { _, _, _ -> ServiceCallOutcome.Accepted(200) }, diary = diary), "start")

        assertEquals(1, diary.lines.size)
        val line = diary.lines.first()
        assertEquals("HOME_VACUUM_START", line.category)
        assertEquals("home", line.source)
        assertTrue(line.toolCallRan, "Der Executor lief wirklich")
        assertNull(line.error)
    }

    @Test
    fun `Diary-Pin - gescheiterte Tat bleibt toolCallRan true mit Grund`() = withHa(withVacuum) { url ->
        val diary = RecordingDiary()

        act(controller(url, { _, _, _ -> ServiceCallOutcome.Failed("ha-http-503", 503) }, diary = diary), "return_to_base")

        val line = diary.lines.single()
        assertEquals("HOME_VACUUM_RETURN_TO_BASE", line.category)
        // Feld-Grenze: der Call ZAEHLT, sein Ausgang steht im error-Feld.
        assertTrue(line.toolCallRan)
        assertEquals("ha-http-503", line.error)
    }

    @Test
    fun `Diary-Pin - abgelehnte Tat mit toolCallRan false (Kreuzbeweis)`() = withHa(withoutVacuum) { url ->
        val diary = RecordingDiary()

        act(controller(url, { _, _, _ -> ServiceCallOutcome.Accepted(200) }, diary = diary), "start")

        val line = diary.lines.single()
        assertEquals("HOME_VACUUM_START", line.category)
        assertEquals(false, line.toolCallRan, "Vor dem Call abgelehnt ⇒ nichts ist passiert")
        assertEquals("vacuum-not-found", line.error)
    }

    @Test
    fun `Diary NOOP schreibt nichts und stoert die Tat nicht`() = withHa(withVacuum) { url ->
        val res = act(controller(url, { _, _, _ -> ServiceCallOutcome.Accepted(200) }, diary = TurnTracePort.NOOP), "start")

        assertEquals(200, res.statusCode.value())
    }

    @Test
    fun `ein werfendes Diary beruehrt die Tat nie`() = withHa(withVacuum) { url ->
        val exploding = TurnTracePort { throw IllegalStateException("Diary kaputt") }

        val res = act(controller(url, { _, _, _ -> ServiceCallOutcome.Accepted(200) }, diary = exploding), "start")

        assertEquals(200, res.statusCode.value())
    }
}
