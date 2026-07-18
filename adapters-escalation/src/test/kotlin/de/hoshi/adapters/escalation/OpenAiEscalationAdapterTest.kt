package de.hoshi.adapters.escalation

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import de.hoshi.core.dto.Language
import de.hoshi.core.port.EscalationResult
import de.hoshi.core.port.EscalationSourceRef
import de.hoshi.kernel.EgressPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Beweist den [OpenAiEscalationAdapter] OHNE echte Cloud/echten Key (Vorbild
 * [de.hoshi.adapters.tts.OpenAiTtsAdapterTest]): ein JDK-HttpServer spielt
 * `api.openai.com` und ZÄHLT + kapert jede Anfrage.
 *
 * Die Köder-Tests sind das harte Tom-Abnahme-Kriterium (Messung #1):
 * Memory-/Token-/IP-/spk-/HA-Köder ⇒ **0 Requests**; Namens-/URL-Köder ⇒ der
 * Body trägt NUR die Maske, NIE das Original. Ein einziger Klartext-Köder im
 * Request = Lane zurück auf AUS.
 */
class OpenAiEscalationAdapterTest {

    data class RequestMeta(val authorization: String, val bodyText: String)

    /** Fake-`/v1/chat/completions`: liefert [responseBody], zählt Requests, kapert die letzte. */
    private fun withOpenAi(
        responseBody: String,
        status: Int = 200,
        block: (url: String, requests: AtomicInteger, captured: AtomicReference<RequestMeta?>) -> Unit,
    ) {
        val requests = AtomicInteger(0)
        val captured = AtomicReference<RequestMeta?>(null)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            requests.incrementAndGet()
            val raw = ex.requestBody.readBytes()
            captured.set(
                RequestMeta(
                    authorization = ex.requestHeaders.getFirst("Authorization") ?: "",
                    bodyText = String(raw, Charsets.UTF_8),
                ),
            )
            val bytes = responseBody.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", requests, captured)
        } finally {
            server.stop(0)
        }
    }

    private val jsonEscaper = ObjectMapper()

    /** Kanonische Chat-Completions-Antwort mit usage (Token-Counts für die Kosten-Buchung). */
    private fun chatJson(content: String, promptTokens: Int = 100, completionTokens: Int = 50): String {
        val escaped = jsonEscaper.writeValueAsString(content)
        return """{"id":"chatcmpl-test","choices":[{"index":0,"message":{"role":"assistant","content":$escaped}}],""" +
            """"usage":{"prompt_tokens":$promptTokens,"completion_tokens":$completionTokens}}"""
    }

    private fun tempSpendPath(): Path =
        Files.createTempDirectory("escalation-test").resolve("spend.json")

    private fun adapter(
        url: String,
        egress: EgressPort = EgressPort(),
        apiKey: String? = "test-key-xyz",
        spendStore: EscalationSpendStore = FileBackedEscalationSpendStore(tempSpendPath()),
        capCents: Double = OpenAiEscalationAdapter.DEFAULT_DAILY_CAP_CENTS,
        model: String = EscalationModelCatalog.DEFAULT_MODEL_ID,
        webSearch: Boolean = false,
    ) = OpenAiEscalationAdapter(
        egress = egress,
        apiKey = apiKey,
        spendStore = spendStore,
        dailyCapCents = capCents,
        baseUrl = url,
        timeoutSeconds = 5,
        model = model,
        webSearch = webSearch,
    )

    // ── Web-Search-Pfad (Andi-Auftrag 2026-07-19, video-kritisch) ────────────────
    //
    // Fake-Server, der BEIDE Pfade bedient: `/v1/responses` (Web-Search) und
    // `/v1/chat/completions` (Fallback-Ziel) — je eigener Request-Zähler, damit die
    // Tests beweisen können, WELCHER Pfad wie oft getroffen wurde (Erfolg = nur
    // responses; Fehler-Fallback = responses EINMAL + chat/completions EINMAL).

    private fun withResponsesApi(
        responsesBody: String,
        responsesStatus: Int = 200,
        chatBody: String? = null,
        chatStatus: Int = 200,
        block: (
            url: String,
            responsesRequests: AtomicInteger,
            chatRequests: AtomicInteger,
            capturedResponses: AtomicReference<RequestMeta?>,
            capturedChat: AtomicReference<RequestMeta?>,
        ) -> Unit,
    ) {
        val responsesRequests = AtomicInteger(0)
        val chatRequests = AtomicInteger(0)
        val capturedResponses = AtomicReference<RequestMeta?>(null)
        val capturedChat = AtomicReference<RequestMeta?>(null)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/responses") { ex ->
            responsesRequests.incrementAndGet()
            val raw = ex.requestBody.readBytes()
            capturedResponses.set(
                RequestMeta(
                    authorization = ex.requestHeaders.getFirst("Authorization") ?: "",
                    bodyText = String(raw, Charsets.UTF_8),
                ),
            )
            val bytes = responsesBody.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(responsesStatus, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.createContext("/v1/chat/completions") { ex ->
            chatRequests.incrementAndGet()
            val raw = ex.requestBody.readBytes()
            capturedChat.set(
                RequestMeta(
                    authorization = ex.requestHeaders.getFirst("Authorization") ?: "",
                    bodyText = String(raw, Charsets.UTF_8),
                ),
            )
            val body = chatBody ?: chatJson("Fallback-Antwort.\nQuelle: Test")
            val bytes = body.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(chatStatus, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", responsesRequests, chatRequests, capturedResponses, capturedChat)
        } finally {
            server.stop(0)
        }
    }

    /** Kanonische Responses-API-Antwort (`output` mit `web_search_call` + `message`/`output_text`/Annotations, `usage`). */
    private fun responsesJson(
        text: String,
        citationUrls: List<String> = emptyList(),
        inputTokens: Int = 800,
        outputTokens: Int = 300,
    ): String {
        val escapedText = jsonEscaper.writeValueAsString(text)
        val annotations = citationUrls.joinToString(",") { url ->
            val escapedUrl = jsonEscaper.writeValueAsString(url)
            """{"type":"url_citation","url":$escapedUrl,"title":"Quelle","start_index":0,"end_index":1}"""
        }
        return """{"id":"resp-test","output":[""" +
            """{"type":"web_search_call","id":"ws_1","status":"completed"},""" +
            """{"type":"message","role":"assistant","content":[{"type":"output_text","text":$escapedText,"annotations":[$annotations]}]}""" +
            """],"usage":{"input_tokens":$inputTokens,"output_tokens":$outputTokens,"total_tokens":${inputTokens + outputTokens}}}"""
    }

    @Test
    fun `webSearch=false ruft weiterhin NUR chat-completions - Responses-Pfad bleibt unberuehrt (byte-neutral)`() =
        withResponsesApi(
            responsesBody = """{"error":"darf NIE gerufen werden"}""",
            responsesStatus = 500,
            chatBody = chatJson("Der Eiffelturm ist 330 Meter hoch.\nQuelle: Wikipedia"),
        ) { url, responsesRequests, chatRequests, _, _ ->
            val result = lookup(adapter(url, webSearch = false), "Wie hoch ist der Eiffelturm?")
            assertInstanceOf(EscalationResult.Answer::class.java, result)
            assertEquals(0, responsesRequests.get(), "webSearch=false darf /v1/responses NIE rufen")
            assertEquals(1, chatRequests.get())
        }

    @Test
    fun `webSearch=true ruft POST v1-responses mit web_search-Tool im Body`() =
        withResponsesApi(responsesBody = responsesJson("Der Eiffelturm ist 330 Meter hoch.")) { url, responsesRequests, chatRequests, capturedResponses, _ ->
            val result = lookup(adapter(url, webSearch = true), "Wie hoch ist der Eiffelturm?")
            assertInstanceOf(EscalationResult.Answer::class.java, result)
            assertEquals(1, responsesRequests.get())
            assertEquals(0, chatRequests.get(), "Erfolgspfad braucht keinen Fallback-Call")
            val body = capturedResponses.get()!!.bodyText
            assertTrue(body.contains(""""type":"web_search""""), "web_search-Tool fehlt im Body: $body")
            assertTrue(body.contains("Wie hoch ist der Eiffelturm?"), "Frage fehlt im input: $body")
        }

    @Test
    fun `Web-Search-Antwort mit url_citation-Annotations wird zur Quellen-url1-url2-Liste UND strukturiert zu sources`() =
        withResponsesApi(
            responsesBody = responsesJson(
                "Der Eiffelturm ist 330 Meter hoch.",
                citationUrls = listOf("https://de.wikipedia.org/wiki/Eiffelturm", "https://www.toureiffel.paris"),
            ),
        ) { url, _, _, _, _ ->
            val result = lookup(adapter(url, webSearch = true), "Wie hoch ist der Eiffelturm?")
            val answer = assertInstanceOf(EscalationResult.Answer::class.java, result)
            assertEquals("Der Eiffelturm ist 330 Meter hoch.", answer.text, "der Text traegt NIE die URLs")
            assertEquals(
                "Quellen: https://de.wikipedia.org/wiki/Eiffelturm, https://www.toureiffel.paris",
                answer.source,
            )
            // Quellen-Struktur-Auftrag 2026-07-21: dieselben Citations reisen ZUSAETZLICH
            // strukturiert (fuers FE-„i"-Icon) — dieselbe Reihenfolge, Titel aus der Annotation.
            assertEquals(
                listOf(
                    EscalationSourceRef(title = "Quelle", url = "https://de.wikipedia.org/wiki/Eiffelturm"),
                    EscalationSourceRef(title = "Quelle", url = "https://www.toureiffel.paris"),
                ),
                answer.sources,
            )
        }

    @Test
    fun `Tracking-Query-Parameter (utm_source u-ae) werden aus den Quellen-URLs gestrippt`() =
        withResponsesApi(
            responsesBody = responsesJson(
                "Tokio hat 14.299.726 Einwohner.",
                citationUrls = listOf(
                    "https://www.metro.tokyo.lg.jp/english/index.html?utm_source=openai&utm_medium=referral",
                    "https://example.com/page?id=7&fbclid=abc123",
                ),
            ),
        ) { url, _, _, _, _ ->
            val result = lookup(adapter(url, webSearch = true), "Wie viele Einwohner hat Tokio?")
            val answer = assertInstanceOf(EscalationResult.Answer::class.java, result)
            assertFalse(answer.text.contains("utm_source"), "der Text traegt NIE Tracking-Parameter")
            assertEquals(
                listOf(
                    EscalationSourceRef(title = "Quelle", url = "https://www.metro.tokyo.lg.jp/english/index.html"),
                    EscalationSourceRef(title = "Quelle", url = "https://example.com/page?id=7"),
                ),
                answer.sources,
                "utm_source/utm_medium/fbclid raus, das echte id=7-Query-Argument bleibt",
            )
            assertFalse(answer.source.contains("utm_source"), "auch der Diary-String bleibt utm-frei")
        }

    @Test
    fun `Web-Search-Antwort ohne Annotations behaelt die bestehende ohne-Quellenangabe-Konvention UND traegt KEINE strukturierten Quellen`() =
        withResponsesApi(responsesBody = responsesJson("Der Eiffelturm ist 330 Meter hoch.")) { url, _, _, _, _ ->
            val result = lookup(adapter(url, webSearch = true, model = "gpt-5.6-sol"), "Wie hoch ist der Eiffelturm?")
            val answer = assertInstanceOf(EscalationResult.Answer::class.java, result)
            assertTrue(answer.source.contains("ohne Quellenangabe"), "ohne Citations gilt die alte Konvention: ${answer.source}")
            // (c): Modellwissen-Fallback ohne echte Quellen ⇒ leer, das FE zeigt dann kein Icon.
            assertTrue(answer.sources.isEmpty(), "ohne Citations gibt es keine strukturierte Quelle")
        }

    @Test
    fun `Responses-API usage (input_tokens-output_tokens) wird korrekt in Cents gebucht`() =
        withResponsesApi(responsesBody = responsesJson("Antwort.", inputTokens = 800, outputTokens = 300)) { url, _, _, _, _ ->
            val spend = FileBackedEscalationSpendStore(tempSpendPath())
            val result = lookup(adapter(url, webSearch = true, spendStore = spend), "Frage?")
            assertInstanceOf(EscalationResult.Answer::class.java, result)
            // 800×20ct/1M + 300×125ct/1M = 0.016 + 0.0375 = 0.0535 ct (gpt-5.4-nano-Tabelle,
            // Feldnamen input_tokens/output_tokens statt prompt_tokens/completion_tokens).
            assertEquals(0.0535, spend.spentTodayCents(), 1e-9)
        }

    @Test
    fun `UNKLAR bleibt UNKLAR auch im Web-Search-Pfad - kein Fehler, darum kein Fallback`() =
        withResponsesApi(responsesBody = responsesJson("UNKLAR")) { url, responsesRequests, chatRequests, _, _ ->
            val result = lookup(adapter(url, webSearch = true), "Wie hieß Napoleons Lieblingspferd wirklich?")
            assertEquals(EscalationResult.Unclear, result)
            assertEquals(1, responsesRequests.get())
            assertEquals(0, chatRequests.get(), "UNKLAR ist kein Fehler — kein Fallback-Call")
        }

    @Test
    fun `HTTP-Fehler (500) im Responses-Pfad faellt GENAU EINMAL auf chat-completions zurueck`() =
        withResponsesApi(
            responsesBody = """{"error":"boom"}""",
            responsesStatus = 500,
            chatBody = chatJson("Fallback-Antwort ueber chat-completions.\nQuelle: Test"),
        ) { url, responsesRequests, chatRequests, _, capturedChat ->
            val result = lookup(adapter(url, webSearch = true), "Wie hoch ist der Eiffelturm?")
            val answer = assertInstanceOf(EscalationResult.Answer::class.java, result)
            assertEquals("Fallback-Antwort ueber chat-completions.", answer.text)
            assertEquals(1, responsesRequests.get(), "genau EIN Versuch gegen /v1/responses")
            assertEquals(1, chatRequests.get(), "genau EIN Fallback-Versuch gegen /v1/chat/completions")
            assertNotNull(capturedChat.get())
        }

    @Test
    fun `kaputtes JSON im Responses-Pfad (Parse-Fehler) faellt auf chat-completions zurueck`() =
        withResponsesApi(
            responsesBody = "das ist kein json",
            chatBody = chatJson("Fallback nach kaputtem Responses-JSON.\nQuelle: Test"),
        ) { url, responsesRequests, chatRequests, _, _ ->
            val result = lookup(adapter(url, webSearch = true), "Wie hoch ist der Eiffelturm?")
            val answer = assertInstanceOf(EscalationResult.Answer::class.java, result)
            assertEquals("Fallback nach kaputtem Responses-JSON.", answer.text)
            assertEquals(1, responsesRequests.get())
            assertEquals(1, chatRequests.get())
        }

    @Test
    fun `schlaegt auch der Fallback fehl, bleibt es Unavailable - nie ein Crash`() =
        withResponsesApi(
            responsesBody = """{"error":"boom"}""",
            responsesStatus = 500,
            chatBody = """{"error":"auch der Fallback ist kaputt"}""",
            chatStatus = 500,
        ) { url, responsesRequests, chatRequests, _, _ ->
            val result = lookup(adapter(url, webSearch = true), "Wie hoch ist der Eiffelturm?")
            assertEquals(EscalationResult.Unavailable, result)
            assertEquals(1, responsesRequests.get())
            assertEquals(1, chatRequests.get())
        }

    @Test
    fun `Sprecher-Name-Maske gilt auch im Web-Search-Pfad`() =
        withResponsesApi(responsesBody = responsesJson("Er ist 36 Jahre alt.")) { url, _, _, capturedResponses, _ ->
            val egress = EgressPort(speakerName = "Andi")
            val result = lookup(adapter(url, egress = egress, webSearch = true), "Wie alt ist Andi eigentlich?")
            assertInstanceOf(EscalationResult.Answer::class.java, result)
            val body = capturedResponses.get()!!.bodyText
            assertTrue(body.contains("Wie alt ist [NAME_1] eigentlich?"), "maskierte Frage fehlt im input: $body")
            assertFalse(body.contains("Andi"), "der Klartext-Name darf NIE im Request stehen: $body")
        }

    @Test
    fun `Cap-Pruefung greift VOR dem Web-Search-Call - CapExhausted, NULL Requests auf beiden Pfaden`() =
        withResponsesApi(responsesBody = responsesJson("egal")) { url, responsesRequests, chatRequests, _, _ ->
            val spend = FileBackedEscalationSpendStore(tempSpendPath()).apply { book(50.0) }
            val result = lookup(adapter(url, webSearch = true, spendStore = spend), "Wie hoch ist der Eiffelturm?")
            assertEquals(EscalationResult.CapExhausted, result)
            assertEquals(0, responsesRequests.get())
            assertEquals(0, chatRequests.get())
        }

    private fun lookup(a: OpenAiEscalationAdapter, query: String, snippets: String = ""): EscalationResult? =
        a.lookup(query, snippets, Language.DE).block(Duration.ofSeconds(10))

    // ── Köder-Tests (Tom-Messung #1): Hard-Block ⇒ Declined + 0 Requests ─────

    @Test
    fun `Memory-Referenz-Koeder wird geblockt — Declined, NULL Requests`() =
        withOpenAi(chatJson("egal")) { url, requests, _ ->
            val result = lookup(adapter(url), "Weißt du noch, was ich dir über meine Bank erzählt hab?")
            val declined = assertInstanceOf(EscalationResult.Declined::class.java, result)
            assertTrue(declined.auditReason.contains("Memory"), "auditReason nennt die Kategorie: ${declined.auditReason}")
            assertFalse(declined.auditReason.contains("Bank"), "auditReason darf NIE Klartext tragen")
            assertEquals(0, requests.get(), "Memory-Köder darf NIE einen Call auslösen")
        }

    @Test
    fun `spk-UUID-Koeder wird geblockt — Declined, NULL Requests`() =
        withOpenAi(chatJson("egal")) { url, requests, _ ->
            val result = lookup(adapter(url), "Wem gehört das Profil spk-1a2b3c-d4e5f6?")
            assertInstanceOf(EscalationResult.Declined::class.java, result)
            assertEquals(0, requests.get(), "spk-ID-Köder darf NIE einen Call auslösen")
        }

    @Test
    fun `Bearer-Token-Koeder wird geblockt — Declined, NULL Requests`() =
        withOpenAi(chatJson("egal")) { url, requests, _ ->
            val result = lookup(adapter(url), "Was bedeutet der Header Bearer abc123DEF456ghi789jkl?")
            assertInstanceOf(EscalationResult.Declined::class.java, result)
            assertEquals(0, requests.get(), "Token-Köder darf NIE einen Call auslösen")
        }

    @Test
    fun `LAN-IP-Koeder wird geblockt — Declined, NULL Requests`() =
        withOpenAi(chatJson("egal")) { url, requests, _ ->
            val result = lookup(adapter(url), "Welcher Dienst läuft auf 192.168.1.42?")
            assertInstanceOf(EscalationResult.Declined::class.java, result)
            assertEquals(0, requests.get(), "LAN-IP-Köder darf NIE einen Call auslösen")
        }

    @Test
    fun `HA-Base-URL-Koeder wird geblockt — Declined, NULL Requests`() =
        withOpenAi(chatJson("egal")) { url, requests, _ ->
            val egress = EgressPort(haBaseUrl = "http://ha.local:8123")
            val result = lookup(adapter(url, egress = egress), "Öffne mal http://ha.local:8123 und schau nach.")
            val declined = assertInstanceOf(EscalationResult.Declined::class.java, result)
            assertFalse(declined.auditReason.contains("ha.local"), "auditReason darf die URL nicht tragen")
            assertEquals(0, requests.get(), "HA-URL-Köder darf NIE einen Call auslösen")
        }

    @Test
    fun `Koeder in den Grounding-Schnipseln wird genauso geblockt (EIN guard fuer den ganzen Payload)`() =
        withOpenAi(chatJson("egal")) { url, requests, _ ->
            val result = lookup(adapter(url), "Wie hoch ist der Eiffelturm?", snippets = "Notiz: erreichbar über 10.0.0.7")
            assertInstanceOf(EscalationResult.Declined::class.java, result)
            assertEquals(0, requests.get(), "Schnipsel-Köder darf NIE einen Call auslösen")
        }

    // ── Masken-Tests: Namens-/URL-Köder gehen NUR maskiert raus ──────────────

    @Test
    fun `Sprecher-Name geht NIE im Klartext raus — Body traegt die NAME-Maske`() =
        withOpenAi(chatJson("Er ist 36 Jahre alt.\nQuelle: Test")) { url, requests, captured ->
            val egress = EgressPort(speakerName = "Andi")
            val result = lookup(adapter(url, egress = egress), "Wie alt ist Andi eigentlich?")
            assertInstanceOf(EscalationResult.Answer::class.java, result)
            assertEquals(1, requests.get())
            val body = captured.get()!!.bodyText
            assertTrue(body.contains("Wie alt ist [NAME_1] eigentlich?"), "maskierte Frage fehlt im Body: $body")
            assertFalse(body.contains("Andi"), "der Klartext-Name darf NIE im Request stehen: $body")
        }

    @Test
    fun `URL geht NIE im Klartext raus — Body traegt die URL-Maske`() =
        withOpenAi(chatJson("Dort steht ein Impressum.\nQuelle: Test")) { url, requests, captured ->
            val result = lookup(adapter(url), "Was steht auf https://example.com/geheime-seite denn so?")
            assertInstanceOf(EscalationResult.Answer::class.java, result)
            assertEquals(1, requests.get())
            val body = captured.get()!!.bodyText
            assertTrue(body.contains("Was steht auf [URL_1] denn so?"), "maskierte Frage fehlt im Body: $body")
            assertFalse(body.contains("example.com"), "die Klartext-URL darf NIE im Request stehen: $body")
        }

    @Test
    fun `Masken-Token in der ANTWORT werden lokal rekonstruiert (reconstruct)`() =
        withOpenAi(chatJson("[NAME_1] ist 36 Jahre alt.\nQuelle: Hausbuch")) { url, _, _ ->
            val egress = EgressPort(speakerName = "Andi")
            val result = lookup(adapter(url, egress = egress), "Wie alt ist Andi eigentlich?")
            val answer = assertInstanceOf(EscalationResult.Answer::class.java, result)
            assertEquals("Andi ist 36 Jahre alt.", answer.text, "Maske muss lokal zurückgesetzt sein")
            assertEquals("Hausbuch", answer.source)
        }

    // ── Vertrag: Antwort/Quelle/UNKLAR/Kosten ────────────────────────────────

    @Test
    fun `Antwort mit Quellen-Zeile wird zu Answer(text, source, costCents) — Request traegt Modell und Bearer`() =
        withOpenAi(chatJson("Der Eiffelturm ist 330 Meter hoch.\nQuelle: Wikipedia", 100, 50)) { url, _, captured ->
            val spend = FileBackedEscalationSpendStore(tempSpendPath())
            val result = lookup(adapter(url, spendStore = spend), "Wie hoch ist der Eiffelturm?")
            val answer = assertInstanceOf(EscalationResult.Answer::class.java, result)
            assertEquals("Der Eiffelturm ist 330 Meter hoch.", answer.text)
            assertEquals("Wikipedia", answer.source)
            // 100×20ct/1M + 50×125ct/1M = 0.00825 ct (ca.-Tabelle gpt-5.4-nano).
            assertEquals(0.00825, answer.costCents, 1e-9)
            assertEquals(0.00825, spend.spentTodayCents(), 1e-9, "echte Kosten müssen gebucht sein")
            val meta = captured.get()!!
            assertEquals("Bearer test-key-xyz", meta.authorization)
            assertTrue(meta.bodyText.contains(EscalationModelCatalog.DEFAULT_MODEL_ID), "Modell-ID fehlt im Body")
            assertTrue(meta.bodyText.contains("max_completion_tokens"), "Token-Obergrenze fehlt im Body")
        }

    @Test
    fun `Antwort ohne Quellen-Zeile bekommt ehrliche Modell-Attribution`() =
        withOpenAi(chatJson("Der Eiffelturm ist 330 Meter hoch.")) { url, _, _ ->
            val result = lookup(adapter(url), "Wie hoch ist der Eiffelturm?")
            val answer = assertInstanceOf(EscalationResult.Answer::class.java, result)
            assertTrue(answer.source.contains("ohne Quellenangabe"), "fehlende Quelle muss ehrlich benannt sein: ${answer.source}")
        }

    @Test
    fun `UNKLAR wird zu Unclear — und die Kosten sind trotzdem gebucht (bezahlt ist bezahlt)`() =
        withOpenAi(chatJson("UNKLAR", 80, 5)) { url, requests, _ ->
            val spend = FileBackedEscalationSpendStore(tempSpendPath())
            val result = lookup(adapter(url, spendStore = spend), "Wie hieß Napoleons Lieblingspferd wirklich?")
            assertEquals(EscalationResult.Unclear, result)
            assertEquals(1, requests.get())
            assertTrue(spend.spentTodayCents() > 0.0, "auch ein UNKLAR-Call kostet und wird gebucht")
        }

    // ── Cap-Tests (Messung #2): 0,49 läuft, 0,50 blockt, Restart-fest ────────

    @Test
    fun `unter dem Cap (49 von 50 Cents) laeuft der Call`() =
        withOpenAi(chatJson("Antwort.\nQuelle: Test")) { url, requests, _ ->
            val spend = FileBackedEscalationSpendStore(tempSpendPath()).apply { book(49.0) }
            val result = lookup(adapter(url, spendStore = spend), "Wie hoch ist der Eiffelturm?")
            assertInstanceOf(EscalationResult.Answer::class.java, result)
            assertEquals(1, requests.get(), "49 ct gebucht ⇒ der Call muss laufen")
        }

    @Test
    fun `am Cap (50 von 50 Cents) — CapExhausted (H3), NULL Requests`() =
        withOpenAi(chatJson("Antwort.\nQuelle: Test")) { url, requests, _ ->
            val spend = FileBackedEscalationSpendStore(tempSpendPath()).apply { book(50.0) }
            val result = lookup(adapter(url, spendStore = spend), "Wie hoch ist der Eiffelturm?")
            // H3: Cap-Erschöpfung ist EIGENER Ausgang, NICHT mehr Unavailable —
            // Andi soll „Budget alle" von einem echten Netzfehler unterscheiden können.
            assertEquals(EscalationResult.CapExhausted, result)
            assertEquals(0, requests.get(), "am Cap darf KEIN Call rausgehen")
        }

    @Test
    fun `der Cap ist Restart-fest — ein NEUER Store liest die Datei des alten`() =
        withOpenAi(chatJson("Antwort.\nQuelle: Test")) { url, requests, _ ->
            val path = tempSpendPath()
            FileBackedEscalationSpendStore(path).book(50.0) // „alter Prozess" bucht bis zum Cap …
            val restarted = FileBackedEscalationSpendStore(path) // … „Neustart" liest dieselbe Datei.
            assertEquals(50.0, restarted.spentTodayCents(), 1e-9, "Zähler muss den Restart überleben")
            val result = lookup(adapter(url, spendStore = restarted), "Wie hoch ist der Eiffelturm?")
            assertEquals(EscalationResult.CapExhausted, result, "H3: Cap-Erschöpfung überlebt den Restart genauso")
            assertEquals(0, requests.get(), "auch nach Restart darf am Cap KEIN Call rausgehen")
        }

    // ── Best-Effort-Ränder ────────────────────────────────────────────────────

    @Test
    fun `ohne Key wird die API gar nicht erst gefragt — Unavailable`() =
        withOpenAi(chatJson("egal")) { url, requests, _ ->
            val result = lookup(adapter(url, apiKey = "   "), "Wie hoch ist der Eiffelturm?")
            assertEquals(EscalationResult.Unavailable, result)
            assertEquals(0, requests.get(), "ohne Key darf kein Call rausgehen")
        }

    @Test
    fun `leere Query wird nicht eskaliert — Unavailable, NULL Requests`() =
        withOpenAi(chatJson("egal")) { url, requests, _ ->
            val result = lookup(adapter(url), "   ")
            assertEquals(EscalationResult.Unavailable, result)
            assertEquals(0, requests.get())
        }

    @Test
    fun `HTTP-Fehler (500) liefert Unavailable, nie Crash`() =
        withOpenAi("""{"error":"boom"}""", status = 500) { url, _, _ ->
            val result = lookup(adapter(url), "Wie hoch ist der Eiffelturm?")
            assertEquals(EscalationResult.Unavailable, result)
        }

    @Test
    fun `API down (connection refused) liefert Unavailable, nie Crash`() {
        val a = adapter("http://127.0.0.1:1")
        val result = lookup(a, "Wie hoch ist der Eiffelturm?")
        assertEquals(EscalationResult.Unavailable, result)
    }

    @Test
    fun `kaputtes Response-JSON liefert Unavailable — und bucht die ca-Schaetzung (Call war bezahlt)`() =
        withOpenAi("das ist kein json") { url, _, _ ->
            val spend = FileBackedEscalationSpendStore(tempSpendPath())
            val result = lookup(adapter(url, spendStore = spend), "Wie hoch ist der Eiffelturm?")
            assertEquals(EscalationResult.Unavailable, result)
            assertTrue(spend.spentTodayCents() > 0.0, "auch ein unparsbarer Call wird konservativ gebucht")
        }

    @Test
    fun `leere Antwort-Wahl liefert Unavailable`() =
        withOpenAi(chatJson("")) { url, _, _ ->
            val result = lookup(adapter(url), "Wie hoch ist der Eiffelturm?")
            assertEquals(EscalationResult.Unavailable, result)
        }

    @Test
    fun `Tageswechsel setzt den Zaehler zurueck (gestern-Datei zaehlt heute nicht)`() {
        val path = tempSpendPath()
        Files.createDirectories(path.parent)
        val yesterday = LocalDate.now().minusDays(1)
        Files.writeString(path, """{"date":"$yesterday","spentCents":49.5}""")
        val store = FileBackedEscalationSpendStore(path)
        assertEquals(0.0, store.spentTodayCents(), 1e-9, "gestriger Spend zählt heute nicht")
    }

    @Test
    fun `harmlose Frage laeuft unmaskiert durch (keine PII, kein Block)`() =
        withOpenAi(chatJson("330 Meter.\nQuelle: Wikipedia")) { url, requests, captured ->
            val result = lookup(adapter(url), "Wie hoch ist der Eiffelturm?")
            assertNotNull(result)
            assertInstanceOf(EscalationResult.Answer::class.java, result)
            assertEquals(1, requests.get())
            assertTrue(captured.get()!!.bodyText.contains("Wie hoch ist der Eiffelturm?"))
        }

    // ── Recherche-Modell-Eskalation (Andi-Auftrag 2026-07-19): ZWEI Adapter-
    //    Instanzen mit UNTERSCHIEDLICHEM Modell teilen sich EINEN Spend-Store —
    //    genau das PipelineConfig-Wiring (escalationPort + researchEscalationPort
    //    injizieren dieselbe escalationSpendStore-Bean). Der Test beweist die
    //    Korrektheits-Voraussetzung direkt: der Tages-Cap gilt für BEIDE Modelle
    //    GEMEINSAM, nicht je Modell separat (kein verdoppelbares Budget).
    @Test
    fun `zwei Adapter mit verschiedenem Modell teilen sich EINEN Spend-Store - der Tages-Cap gilt gemeinsam`() =
        withOpenAi(chatJson("Antwort.\nQuelle: Test", promptTokens = 100, completionTokens = 50)) { url, requests, _ ->
            val sharedSpend = FileBackedEscalationSpendStore(tempSpendPath())
            val nano = adapter(url, spendStore = sharedSpend) // Default-Modell gpt-5.4-nano
            val sol = OpenAiEscalationAdapter(
                egress = EgressPort(),
                apiKey = "test-key-xyz",
                spendStore = sharedSpend,
                model = "gpt-5.6-sol",
                baseUrl = url,
                timeoutSeconds = 5,
                dailyCapCents = OpenAiEscalationAdapter.DEFAULT_DAILY_CAP_CENTS,
            )

            // Nano bucht zuerst (0.00825 ct bei 100/50 Tokens, s. Preis-Mathe-Test).
            val nanoResult = lookup(nano, "Wie hoch ist der Eiffelturm?")
            assertInstanceOf(EscalationResult.Answer::class.java, nanoResult)
            val spentAfterNano = sharedSpend.spentTodayCents()
            assertTrue(spentAfterNano > 0.0, "Nano-Call muss gebucht sein")

            // Sol SIEHT den Nano-Spend (derselbe Store) — der gemeinsame Cap ist knapp
            // unter dem bereits gebuchten Stand, ein Sol-Call darf darum NICHT mehr laufen.
            val solCap = OpenAiEscalationAdapter(
                egress = EgressPort(),
                apiKey = "test-key-xyz",
                spendStore = sharedSpend,
                model = "gpt-5.6-sol",
                baseUrl = url,
                timeoutSeconds = 5,
                dailyCapCents = spentAfterNano - 0.001,
            )
            val requestsBeforeSolCall = requests.get()
            val solResult = lookup(solCap, "Wie hoch ist der Kölner Dom?")
            assertEquals(
                EscalationResult.CapExhausted, solResult,
                "der Cap gilt fuer BEIDE Modelle gemeinsam — Sol sieht den bereits verbrauchten Nano-Spend",
            )
            assertEquals(requestsBeforeSolCall, requests.get(), "am gemeinsamen Cap darf Sol KEINEN Call ausloesen")

            // Sanity: ohne den knappen Cap würde Sol denselben Store weiter befüllen
            // (kein zweites, unabhängiges Budget je Modell).
            val solResultUncapped = lookup(sol, "Wie hoch ist der Kölner Dom?")
            assertInstanceOf(EscalationResult.Answer::class.java, solResultUncapped)
            assertTrue(
                sharedSpend.spentTodayCents() > spentAfterNano,
                "der Sol-Call bucht auf DENSELBEN Zähler weiter — ein Tages-Budget für beide Modelle",
            )
        }
}
