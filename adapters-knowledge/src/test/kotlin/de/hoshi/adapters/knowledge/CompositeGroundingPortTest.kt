package de.hoshi.adapters.knowledge

import de.hoshi.core.dto.RouteCategory
import de.hoshi.core.pipeline.GroundingPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * Beweist die Composite-Strategie (Wetter zuerst, sonst Wiki) mit reinen Fake-Ports —
 * keine Infra, keine HTTP-Calls.
 */
class CompositeGroundingPortTest {

    /** Fake-Port mit fester Antwort, der mitzählt, ob er angefragt wurde. */
    private class FakePort(private val answer: String, var called: Boolean = false) : GroundingPort {
        override fun groundingBlock(query: String, category: RouteCategory): Mono<String> {
            called = true
            return Mono.just(answer)
        }
    }

    private val cat = RouteCategory.FACT_SHORT

    @Test
    fun `Wetter-Block gewinnt und die Wiki-Scheibe wird gar nicht erst angefragt`() {
        val weather = FakePort("WETTER-BLOCK")
        val wiki = FakePort("WIKI-BLOCK")
        val block = CompositeGroundingPort(weather, wiki).groundingBlock("Wetter morgen?", cat)
            .block(Duration.ofSeconds(2))

        assertEquals("WETTER-BLOCK", block)
        assertTrue(weather.called, "Wetter wird zuerst gefragt")
        assertFalse(wiki.called, "Wiki wird bei Wetter-Treffer NICHT angefragt")
    }

    @Test
    fun `ohne Wetter-Block faellt der Composite zur Wiki-Scheibe durch (byte-identisch)`() {
        val weather = FakePort("") // keine Wetter-Absicht / API leer
        val wiki = FakePort("WIKI-BLOCK")
        val block = CompositeGroundingPort(weather, wiki).groundingBlock("Wer war Adenauer?", cat)
            .block(Duration.ofSeconds(2))

        // Der durchgereichte Block ist GENAU der Wiki-Block — die Wetter-Scheibe ist
        // für Nicht-Wetter-Fragen transparent (byte-neutral zum reinen Wiki-Pfad).
        assertEquals("WIKI-BLOCK", block)
        assertTrue(wiki.called, "Wiki übernimmt, wenn Wetter leer ist")
    }

    @Test
    fun `ein Fehler in der Wetter-Scheibe faellt sauber zur Wiki-Scheibe durch`() {
        val weather = object : GroundingPort {
            override fun groundingBlock(query: String, category: RouteCategory): Mono<String> =
                Mono.error(RuntimeException("boom"))
        }
        val wiki = FakePort("WIKI-BLOCK")
        val block = CompositeGroundingPort(weather, wiki).groundingBlock("Wetter morgen?", cat)
            .block(Duration.ofSeconds(2))

        assertEquals("WIKI-BLOCK", block, "Wetter-Fehler darf den Turn nie kippen")
    }

    // ── Extended Think S3: die dritte Scheibe (nachgeschlagen), NACH weather, VOR wiki ──

    @Test
    fun `ohne explizite dritte Scheibe - Zwei-Argument-Konstruktor bleibt byte-identisch`() {
        // Bestehende Aufrufer (2 positionale Argumente) kompilieren unverändert UND
        // verhalten sich unverändert — der Default-Stub liefert immer "".
        val weather = FakePort("")
        val wiki = FakePort("WIKI-BLOCK")
        val block = CompositeGroundingPort(weather, wiki).groundingBlock("Wer war Adenauer?", cat)
            .block(Duration.ofSeconds(2))
        assertEquals("WIKI-BLOCK", block)
    }

    @Test
    fun `Nachgeschlagen-Block gewinnt gegen leeres Wetter, Wiki wird NICHT gefragt`() {
        val weather = FakePort("")
        val nachgeschlagen = FakePort("CACHE-BLOCK")
        val wiki = FakePort("WIKI-BLOCK")
        val block = CompositeGroundingPort(weather, wiki, nachgeschlagen).groundingBlock("Wie hoch ist der Eiffelturm?", cat)
            .block(Duration.ofSeconds(2))

        assertEquals("CACHE-BLOCK", block)
        assertTrue(weather.called, "Wetter wird zuerst gefragt")
        assertTrue(nachgeschlagen.called, "Nachgeschlagen wird gefragt, wenn Wetter leer ist")
        assertFalse(wiki.called, "Wiki wird bei Cache-Treffer NICHT angefragt")
    }

    @Test
    fun `Wetter gewinnt weiterhin gegen einen Nachgeschlagen-Treffer`() {
        val weather = FakePort("WETTER-BLOCK")
        val nachgeschlagen = FakePort("CACHE-BLOCK")
        val wiki = FakePort("WIKI-BLOCK")
        val block = CompositeGroundingPort(weather, wiki, nachgeschlagen).groundingBlock("Wetter morgen?", cat)
            .block(Duration.ofSeconds(2))

        assertEquals("WETTER-BLOCK", block)
        assertFalse(nachgeschlagen.called, "Nachgeschlagen wird bei Wetter-Treffer NICHT angefragt")
        assertFalse(wiki.called)
    }

    @Test
    fun `weder Wetter noch Nachgeschlagen - faellt zu Wiki durch`() {
        val weather = FakePort("")
        val nachgeschlagen = FakePort("")
        val wiki = FakePort("WIKI-BLOCK")
        val block = CompositeGroundingPort(weather, wiki, nachgeschlagen).groundingBlock("Wer war Adenauer?", cat)
            .block(Duration.ofSeconds(2))

        assertEquals("WIKI-BLOCK", block)
        assertTrue(nachgeschlagen.called)
        assertTrue(wiki.called)
    }

    @Test
    fun `ein Fehler in der Nachgeschlagen-Scheibe faellt sauber zur Wiki-Scheibe durch`() {
        val weather = FakePort("")
        val nachgeschlagen = object : GroundingPort {
            override fun groundingBlock(query: String, category: RouteCategory): Mono<String> =
                Mono.error(RuntimeException("boom"))
        }
        val wiki = FakePort("WIKI-BLOCK")
        val block = CompositeGroundingPort(weather, wiki, nachgeschlagen).groundingBlock("Wie hoch ist der Eiffelturm?", cat)
            .block(Duration.ofSeconds(2))

        assertEquals("WIKI-BLOCK", block, "Nachgeschlagen-Fehler darf den Turn nie kippen")
    }
}
