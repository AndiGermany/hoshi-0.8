package de.hoshi.adapters.knowledge

import com.sun.net.httpserver.HttpServer
import de.hoshi.core.dto.RouteCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration

/**
 * Beweist den [Fts5GroundingAdapter] OHNE Live-Infra: ein winziger JDK-HttpServer
 * spielt die Knowledge-Bridge und liefert kanned `/search`-JSON. Pure — der echte
 * Bridge-Beweis steckt im Live-Smoke (`bin/hoshi ground`).
 */
class Fts5GroundingAdapterTest {

    /** Bridge-Antwort im echten :8035-Format (gekürzt auf die genutzten Felder). */
    private val adenauerJson = """
        {
          "query": "konrad adenauer",
          "totalHits": 1,
          "hits": [
            {
              "articleId": 123,
              "title": "Konrad Adenauer",
              "bm25Score": -68.46,
              "extract": "Konrad Adenauer war von 1949 bis 1963 der erste Bundeskanzler\n  der Bundesrepublik Deutschland.",
              "summary": null,
              "facts": []
            }
          ]
        }
    """.trimIndent()

    /** Bridge-Antwort MIT markierten Zahl-Spans (facts) — wie nach `fact_query`. */
    private val factsJson = """
        {
          "query": "weinbergschnecke zähne",
          "totalHits": 1,
          "hits": [
            {
              "articleId": 7,
              "title": "Weinbergschnecke",
              "bm25Score": -42.0,
              "extract": "Die Weinbergschnecke hat rund 40.000 Zähnchen auf ihrer Raspelzunge.",
              "summary": null,
              "facts": ["40.000 Zähnchen", "1921"]
            }
          ]
        }
    """.trimIndent()

    private fun withBridge(
        json: String,
        status: Int = 200,
        block: (String, java.util.concurrent.atomic.AtomicReference<String?>) -> Unit,
    ) {
        val capturedQuery = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/search") { ex ->
            capturedQuery.set(ex.requestURI.query)
            val bytes = json.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", capturedQuery)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `Wissensfrage liefert kompakten Grounding-Block aus der Bridge-Antwort`() =
        withBridge(adenauerJson) { url, captured ->
            val adapter = Fts5GroundingAdapter(baseUrl = url)
            val block = adapter.groundingBlock("Wer war Konrad Adenauer?", RouteCategory.FACT_SHORT)
                .block(Duration.ofSeconds(5)) ?: ""

            assertTrue(block.isNotBlank(), "Block darf nicht leer sein")
            assertTrue(block.contains("Konrad Adenauer"), "Titel muss im Block stehen")
            assertTrue(block.contains("erste Bundeskanzler"), "Passage-Fakt muss im Block stehen")
            assertTrue(block.contains("HINTERGRUND"), "Block trägt den Hintergrund-Marker")
            // Roh-Newlines aus dem Extract sind geglättet (kein Roh-Dump).
            assertFalse(block.contains("Bundeskanzler\n  der"), "Whitespace muss geglättet sein")
            // Such-Query wurde auf Content-Tokens reduziert (kein „wer/war" mehr).
            val q = captured.get() ?: ""
            assertTrue(q.contains("konrad"), "Such-Query enthält konrad: $q")
            assertFalse(q.contains("wer"), "Frage-Gerüst raus aus der Such-Query: $q")
        }

    @Test
    fun `Bridge down liefert best-effort leeren Block, nie Crash`() {
        // Port, auf dem nichts lauscht → connection refused → leerer Block.
        val adapter = Fts5GroundingAdapter(baseUrl = "http://127.0.0.1:1", timeout = Duration.ofSeconds(2))
        val block = adapter.groundingBlock("Wer war Konrad Adenauer?", RouteCategory.FACT_SHORT)
            .block(Duration.ofSeconds(5))
        assertEquals("", block)
    }

    @Test
    fun `Bridge-Fehler (500) liefert leeren Block`() =
        withBridge("kaputt", status = 500) { url, _ ->
            val adapter = Fts5GroundingAdapter(baseUrl = url)
            val block = adapter.groundingBlock("Wer war Konrad Adenauer?", RouteCategory.FACT_SHORT)
                .block(Duration.ofSeconds(5))
            assertEquals("", block)
        }

    @Test
    fun `leere Trefferliste liefert leeren Block`() =
        withBridge("""{"query":"x","totalHits":0,"hits":[]}""") { url, _ ->
            val adapter = Fts5GroundingAdapter(baseUrl = url)
            val block = adapter.groundingBlock("Gibt es etwas Unauffindbares?", RouteCategory.FACT_SHORT)
                .block(Duration.ofSeconds(5))
            assertEquals("", block)
        }

    @Test
    fun `schwacher BM25-Treffer wird verworfen`() =
        withBridge(
            """{"hits":[{"title":"Schwach","bm25Score":-1.0,"extract":"kaum relevant","summary":null}]}""",
        ) { url, _ ->
            val adapter = Fts5GroundingAdapter(baseUrl = url) // bm25Max = -3.0 → -1.0 fällt raus
            val block = adapter.groundingBlock("Irgendwas Schwaches", RouteCategory.FACT_SHORT)
                .block(Duration.ofSeconds(5))
            assertEquals("", block)
        }

    // ── WikiNumberContract (T140-Port, Flag default OFF) ────────────────────────

    @Test
    fun `WikiNumberContract ON verankert facts als verbatim-Spans plus Instruktion`() =
        withBridge(factsJson) { url, captured ->
            val adapter = Fts5GroundingAdapter(baseUrl = url, enableNumberContract = true)
            val block = adapter.groundingBlock("Wie viele Zähne hat eine Weinbergschnecke?", RouteCategory.FACT_SHORT)
                .block(Duration.ofSeconds(5)) ?: ""

            assertTrue(block.contains("«40.000 Zähnchen»"), "Zahl-Span steht verbatim in Guillemets: $block")
            assertTrue(block.contains("«1921»"), "zweiter Span steht verbatim: $block")
            assertTrue(block.contains("gleiche Ziffern, gleiche Einheit"), "Zitier-Instruktion steht im Block: $block")
            // Fakt-DIREKT-Formel (geschärfter Vertrag): Wert als direkte Eigenschaft im
            // ERSTEN Satz, nicht relativierend — mit dem Eiffelturm-Beispiel.
            assertTrue(
                block.contains("als direkte Eigenschaft im ERSTEN Satz"),
                "Fakt-DIREKT-Formel steht im Vertrag: $block",
            )
            assertTrue(
                block.contains("zum Beispiel: Der Eiffelturm ist 330 Meter hoch — ganz schön was."),
                "Beispiel OHNE Anführungszeichen (4B kopiert jedes gezeigte Markierungs-Muster): $block",
            )
            // Kein Meta-«…»-Literal und keine „…“-Anführungszeichen im VERTRAGSTEIL —
            // das 4B kopierte beides live in die Antwort (mit „…“ „368 Metern“). Die
            // Basis-ANWEISUNG davor nutzt „…“ seit jeher ohne beobachtetes Leak.
            val contract = block.substringAfter("ZAHLEN-VERTRAG:")
            assertFalse(contract.contains("«…»"), "kein Meta-Marker-Literal im Vertrag: $contract")
            assertFalse(contract.contains("„"), "keine typografischen Anführungszeichen im Vertrag: $contract")
            // Die „Zeichen «» NICHT mitschreiben"-Zeile ist bewusst RAUS — den Marker-Strip
            // erledigt deterministisch TurnOrchestrator.stripContractMarkers (Wand statt Tapete).
            assertFalse(block.contains("NICHT mitschreiben"), "Prompt-Regel durch Wand ersetzt: $block")
            // Volle Frage wurde als fact_query an die Bridge geschickt (triggert facts).
            val q = captured.get() ?: ""
            assertTrue(q.contains("fact_query"), "fact_query-Param an die Bridge: $q")
        }

    @Test
    fun `WikiNumberContract OFF laesst den Block byte-neutral und schickt kein fact_query`() =
        withBridge(factsJson) { url, captured ->
            val on = Fts5GroundingAdapter(baseUrl = url, enableNumberContract = true)
                .groundingBlock("Wie viele Zähne hat eine Weinbergschnecke?", RouteCategory.FACT_SHORT)
                .block(Duration.ofSeconds(5)) ?: ""
            // Default-Adapter = Flag OFF. Diese Anfrage läuft als LETZTE → captured = OFF-URL.
            val off = Fts5GroundingAdapter(baseUrl = url)
                .groundingBlock("Wie viele Zähne hat eine Weinbergschnecke?", RouteCategory.FACT_SHORT)
                .block(Duration.ofSeconds(5)) ?: ""

            assertFalse(off.contains("«"), "OFF: kein verbatim-Span im Block")
            assertFalse(off.contains("ZEICHENGENAU"), "OFF: keine Zahlen-Vertrag-Instruktion")
            // ON ist exakt OFF + additiver Vertrag → der bisherige Block bleibt byte-identisch.
            assertTrue(on.startsWith(off), "ON ist OFF-Block + additiver Zahlen-Vertrag")
            assertTrue(on.length > off.length, "ON hängt den Vertrag additiv an")
            val q = captured.get() ?: ""
            assertFalse(q.contains("fact_query"), "OFF: kein fact_query-Param an die Bridge: $q")
        }

    @Test
    fun `WikiNumberContract ON ohne facts haengt nichts an (defensiv)`() =
        withBridge(adenauerJson) { url, _ -> // adenauerJson trägt "facts": []
            val adapter = Fts5GroundingAdapter(baseUrl = url, enableNumberContract = true)
            val block = adapter.groundingBlock("Wer war Konrad Adenauer?", RouteCategory.FACT_SHORT)
                .block(Duration.ofSeconds(5)) ?: ""

            assertTrue(block.isNotBlank(), "normaler Grounding-Block bleibt")
            assertFalse(block.contains("«"), "leere facts → kein Span-Zusatz")
            assertFalse(block.contains("ZEICHENGENAU"), "leere facts → keine Zahlen-Vertrag-Instruktion")
        }

    @Test
    fun `Nicht-Wissens-Kategorie groundet nicht (kein Bridge-Call)`() =
        withBridge(adenauerJson) { url, captured ->
            val adapter = Fts5GroundingAdapter(baseUrl = url)
            val block = adapter.groundingBlock("Sag mal Hallo", RouteCategory.SMALLTALK)
                .block(Duration.ofSeconds(5))
            assertEquals("", block)
            assertEquals(null, captured.get(), "Bridge darf bei Smalltalk nicht angefragt werden")
        }

    // ── Frage-Frame-Strip + Exact-Title-Boost + geschärfte Abstain ──────────────

    @Test
    fun `searchQuery strippt DE-Frageframe auf das Head-Noun`() {
        val a = Fts5GroundingAdapter(baseUrl = "http://127.0.0.1:1")
        assertEquals("mittwoch", a.searchQuery("Woher kommt der Name Mittwoch?"))
        assertEquals("algebra", a.searchQuery("Woher stammt das Wort Algebra?"))
        assertEquals("photosynthese", a.searchQuery("Was bedeutet Photosynthese?"))
    }

    @Test
    fun `searchQuery strippt EN-Frageframe (leading plus come-from)`() {
        val a = Fts5GroundingAdapter(baseUrl = "http://127.0.0.1:1")
        assertEquals("wednesday", a.searchQuery("where does the name Wednesday come from"))
    }

    @Test
    fun `searchQuery strippt Hoeflichkeits-Praeambel plus DE-Nebensatz-Stellung (Andis Live-Formulierung)`() {
        val a = Fts5GroundingAdapter(baseUrl = "http://127.0.0.1:1")
        // Der Live-Vorfall 2026-07-01: exakt diese Formulierung konfabulierte, weil das
        // Frame nicht am Satzanfang stand + das Verb ans Nebensatz-Ende gewandert war.
        assertEquals("mittwoch", a.searchQuery("Kannst du mir erklären, woher der Name Mittwoch kommt?"))
        assertEquals("kaffee", a.searchQuery("Weißt du, woher das Wort Kaffee stammt?"))
        assertEquals("mittwoch", a.searchQuery("Erklär mir mal, woher der Name Mittwoch kommt?"))
        assertEquals("wednesday", a.searchQuery("Can you tell me where does the name Wednesday come from"))
    }

    @Test
    fun `Hoeflichkeits-Praeambel ohne Frame bleibt tolerant (kein Abstain-Zwang, kein Verstuemmel)`() {
        val a = Fts5GroundingAdapter(baseUrl = "http://127.0.0.1:1")
        // Kein definitorisches Frame → normaler Token-Pfad; Helgoland-Klasse muss weiter grounden.
        val q = a.searchQuery("Kannst du mir sagen, wann die Insel Helgoland zerbrach?")
        assertTrue(q.contains("helgoland"), "Head-Begriff muss ueberleben: $q")
        // Nicht-Frame-Sätze mit End-Verb „kommt" bleiben unangetastet (DE_TRAILING nur bei matched).
        assertTrue(a.searchQuery("Wer kommt heute zu Besuch?").contains("besuch"))
    }

    @Test
    fun `definitorische Frage mit exaktem Titel-Treffer groundet`() =
        withBridge(
            """{"hits":[{"title":"Photosynthese","bm25Score":-71.0,"extract":"Grüne Pflanzen wandeln Licht in Energie.","summary":null}]}""",
        ) { url, _ ->
            val a = Fts5GroundingAdapter(baseUrl = url)
            val block = a.groundingBlock("Was bedeutet Photosynthese?", RouteCategory.FACT_SHORT)
                .block(Duration.ofSeconds(5)) ?: ""
            assertTrue(block.contains("Photosynthese"), "exakter Titel groundet: $block")
            assertTrue(block.contains("Grüne Pflanzen"), "Passage im Block: $block")
        }

    @Test
    fun `definitorische Frage ohne exakten Titel-Treffer abstiniert (Mittwoch)`() =
        withBridge(
            """{"hits":[{"title":"Mittwochsfazit","bm25Score":-8.4,"extract":"Kolumne","summary":null},
                        {"title":"Mittwoch 04:45","bm25Score":-8.4,"extract":"Film","summary":null}]}""",
        ) { url, _ ->
            val a = Fts5GroundingAdapter(baseUrl = url)
            // Beide Treffer passieren das BM25-Gate (-8.4 <= -3.0), aber KEINER heißt
            // exakt "Mittwoch" → ehrlich abstinieren statt tangential grounden.
            val block = a.groundingBlock("Woher kommt der Name Mittwoch?", RouteCategory.FACT_SHORT)
                .block(Duration.ofSeconds(5))
            assertEquals("", block, "kein exakter Titel-Treffer → Lane A deflektet ehrlich")
        }

    @Test
    fun `Exact-Title-Boost schlaegt staerkeren BM25 einer mehrdeutigen Gruppe`() =
        withBridge(
            """{"hits":[{"title":"Photosynthese (Kabarettgruppe)","bm25Score":-60.0,"extract":"Ein Kabarett-Ensemble.","summary":null},
                        {"title":"Photosynthese","bm25Score":-40.0,"extract":"Grüne Pflanzen wandeln Licht um.","summary":null}]}""",
        ) { url, _ ->
            val a = Fts5GroundingAdapter(baseUrl = url) // topN=1
            val block = a.groundingBlock("Was bedeutet Photosynthese?", RouteCategory.FACT_SHORT)
                .block(Duration.ofSeconds(5)) ?: ""
            assertTrue(block.contains("Grüne Pflanzen"), "exakter Titel gewinnt trotz schwächerem BM25: $block")
            assertFalse(block.contains("Kabarett"), "mehrdeutige Gruppe verliert gegen exakten Titel: $block")
        }

    // ── Modale Füllwörter (Live-Befund 2026-07-05: „eigentlich" → „Eigentliche Eulen") ──

    @Test
    fun `searchQuery strippt modale Fuellwoerter als ganze Woerter (Live-Fall Mittwoch)`() {
        val a = Fts5GroundingAdapter(baseUrl = "http://127.0.0.1:1")
        // Der Live-Vorfall 2026-07-05: „eigentlich" blieb in der FTS-Query → BM25
        // traf „Eigentliche Eulen"/„Eigentliche Enten" → Konfabulation.
        val q = a.searchQuery("Warum heißt der Mittwoch eigentlich Mittwoch?")
        assertFalse(q.contains("eigentlich"), "Füllwort muss raus aus der FTS-Query: $q")
        assertTrue(q.contains("mittwoch"), "Subjekt muss überleben: $q")
        assertEquals("heißt mittwoch", q)
        // Satz-initiale (großgeschriebene) Partikel + freigelegtes Komma.
        assertEquals("heißt mittwoch", a.searchQuery("Eigentlich, warum heißt der Mittwoch Mittwoch?"))
        // Weitere Partikel der konservativen Liste (denn/überhaupt/halt/eben/noch mal).
        assertEquals("photosynthese", a.searchQuery("Was bedeutet denn überhaupt Photosynthese?"))
        assertEquals("photosynthese", a.searchQuery("Was bedeutet halt eben Photosynthese?"))
        val hauptstadt = a.searchQuery("Wie hieß noch mal die Hauptstadt von Australien?")
        assertFalse(hauptstadt.contains("noch"), "»noch mal« ist gestrippt: $hauptstadt")
        assertTrue(hauptstadt.contains("hauptstadt"), "Subjekt überlebt: $hauptstadt")
    }

    @Test
    fun `Live-Fall Mittwoch-eigentlich groundet — Bridge-Query ohne Fuellwort`() =
        withBridge(
            """{"hits":[{"title":"Mittwoch","bm25Score":-21.0,"extract":"Der Mittwoch ist der Wochentag zwischen Dienstag und Donnerstag.","summary":null}]}""",
        ) { url, captured ->
            val a = Fts5GroundingAdapter(baseUrl = url)
            val block = a.groundingBlock("Warum heißt der Mittwoch eigentlich Mittwoch?", RouteCategory.FACT_SHORT)
                .block(Duration.ofSeconds(5)) ?: ""
            assertTrue(block.contains("Wochentag"), "Mittwoch-Artikel groundet: $block")
            val q = captured.get() ?: ""
            assertFalse(q.contains("eigentlich"), "Füllwort erreicht die Bridge nicht mehr: $q")
            assertTrue(q.contains("mittwoch"), "Subjekt steht in der Bridge-Query: $q")
        }

    @Test
    fun `Fuellwort-Strip trifft nur GANZE Woerter — flektierte Titel und Komposita ueberleben`() {
        val a = Fts5GroundingAdapter(baseUrl = "http://127.0.0.1:1")
        // „Eigentliche" (flektierte Form im Taxonomie-Titel) ist NICHT das Modalwort
        // „eigentlich" — die Ganz-Wort-Regel löst den Konflikt per Konstruktion.
        assertEquals("eigentliche eulen", a.searchQuery("Was sind Eigentliche Eulen?"))
        // Bindestrich zählt als Wort-Fortsetzung: „Eben-Emael" bleibt ganz.
        val fort = a.searchQuery("Was ist das Fort Eben-Emael?")
        assertTrue(fort.contains("eben"), "Eben-Emael bleibt auffindbar: $fort")
        assertTrue(fort.contains("emael"), "Eben-Emael bleibt auffindbar: $fort")
    }

    @Test
    fun `Titel mit Fuellwort-Stamm bleibt per Titel-Nennung auffindbar (Eigentliche Eulen)`() =
        withBridge(
            """{"hits":[{"title":"Eigentliche Eulen","bm25Score":-52.0,"extract":"Die Eigentlichen Eulen (Striginae) sind eine Unterfamilie der Eulen.","summary":null}]}""",
        ) { url, captured ->
            val a = Fts5GroundingAdapter(baseUrl = url)
            val block = a.groundingBlock("Was sind Eigentliche Eulen?", RouteCategory.FACT_SHORT)
                .block(Duration.ofSeconds(5)) ?: ""
            assertTrue(block.contains("Eigentliche Eulen"), "Titel-Nennung groundet weiter: $block")
            assertTrue(block.contains("Unterfamilie"), "Passage im Block: $block")
            val q = captured.get() ?: ""
            assertTrue(q.contains("eigentliche eulen"), "Titel-Wörter bleiben in der Bridge-Query: $q")
        }

    @Test
    fun `Praeambel plus Fuellwort kombiniert normalisiert aufs Head-Noun`() {
        val a = Fts5GroundingAdapter(baseUrl = "http://127.0.0.1:1")
        assertEquals("mittwoch", a.searchQuery("Kannst du mir erklären, woher der Name Mittwoch eigentlich kommt?"))
        assertEquals("mittwoch", a.searchQuery("Kannst du mir erklären, woher eigentlich der Name Mittwoch kommt?"))
        assertEquals("kaffee", a.searchQuery("Weißt du, woher denn das Wort Kaffee stammt?"))
    }

    @Test
    fun `bekannter Trade-off — nacktes Fuellwort als Wort-Subjekt faellt auf die Original-Query zurueck`() {
        val a = Fts5GroundingAdapter(baseUrl = "http://127.0.0.1:1")
        // Fragt jemand nach dem WORT „eigentlich" selbst, bleibt nach Strip+Frame
        // nichts übrig → Fallback = Original-Query (das Wort geht nicht verloren);
        // die definitorische Abstain-Regel deflektiert dann ehrlich statt zu raten.
        assertEquals("Was bedeutet das Wort eigentlich?", a.searchQuery("Was bedeutet das Wort eigentlich?"))
    }

    @Test
    fun `Freitext-Frage ohne Frame groundet weiter ohne exakten Titel (Einstein-Fall)`() =
        // "Wer war X" ist KEIN definitorisches Frame → keine Exact-Title-Pflicht;
        // "Albert Einstein" groundet, obwohl der Titel nicht exakt "einstein" ist.
        withBridge(
            """{"hits":[{"title":"Albert Einstein","bm25Score":-17.8,"extract":"Physiker der Relativitätstheorie.","summary":null}]}""",
        ) { url, _ ->
            val a = Fts5GroundingAdapter(baseUrl = url)
            val block = a.groundingBlock("Wer war Einstein?", RouteCategory.FACT_SHORT)
                .block(Duration.ofSeconds(5)) ?: ""
            assertTrue(block.contains("Albert Einstein"), "Freitext-Frage groundet ohne exakten Titel: $block")
        }
}
