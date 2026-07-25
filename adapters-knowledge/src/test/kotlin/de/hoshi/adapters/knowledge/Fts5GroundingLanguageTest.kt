package de.hoshi.adapters.knowledge

import com.sun.net.httpserver.HttpServer
import de.hoshi.core.dto.Language
import de.hoshi.core.dto.RouteCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration

/**
 * **Sprach-Naht des Wiki-Grounding-Blocks** ([Fts5GroundingAdapter] + [WikiBlockTexts],
 * Scheibe 2026-07-25). Beweist die drei Zusagen der Scheibe an einer echten (kanned)
 * Bridge-Antwort:
 *
 *  1. **Jede Sprache trägt ihren eigenen Rahmen** — Stichprobe je DE/EN/ES/FR/IT.
 *  2. **DE ist byte-identisch** zum Stand vor der Scheibe (voller Block als
 *     `assertEquals`, nicht nur `contains` — ein einziges verrutschtes Zeichen im
 *     Prompt, den Andi seit Monaten hört, fällt hier auf).
 *  3. **Zitierter Inhalt wird NICHT übersetzt** — der deutsche Wiki-Titel und die
 *     deutsche Passage stehen in einem EN-Turn WÖRTLICH im Block; nur Kopf,
 *     ANWEISUNG und Vertrag wechseln die Sprache.
 */
class Fts5GroundingLanguageTest {

    private val adenauerJson = """
        {
          "query": "konrad adenauer",
          "totalHits": 1,
          "hits": [
            {
              "articleId": 123,
              "title": "Konrad Adenauer",
              "bm25Score": -68.46,
              "extract": "Konrad Adenauer war von 1949 bis 1963 der erste Bundeskanzler der Bundesrepublik Deutschland.",
              "summary": null,
              "facts": []
            }
          ]
        }
    """.trimIndent()

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
              "facts": ["40.000 Zähnchen"]
            }
          ]
        }
    """.trimIndent()

    private fun withBridge(json: String, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/search") { ex ->
            val bytes = json.toByteArray()
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

    private fun blockFor(url: String, language: Language, numberContract: Boolean = false): String =
        Fts5GroundingAdapter(baseUrl = url, enableNumberContract = numberContract)
            .groundingBlock("Wer war Konrad Adenauer?", RouteCategory.FACT_SHORT, language)
            .block(Duration.ofSeconds(5)) ?: ""

    // ── (1) Stichprobe je Sprache ────────────────────────────────────────────────

    @Test
    fun `jede Sprache traegt Kopf und ANWEISUNG in ihrer eigenen Sprache`() = withBridge(adenauerJson) { url ->
        val expectedHead = mapOf(
            Language.DE to "HINTERGRUND (nur für dich",
            Language.EN to "BACKGROUND (for you only",
            Language.ES to "CONTEXTO (solo para ti",
            Language.FR to "CONTEXTE (pour toi uniquement",
            Language.IT to "CONTESTO (solo per te",
        )
        val expectedInstruction = mapOf(
            Language.DE to "ANWEISUNG: Nutze diese Fakten",
            Language.EN to "INSTRUCTION: Use these facts",
            Language.ES to "INSTRUCCIÓN: Usa estos datos",
            Language.FR to "INSTRUCTION : Utilise ces faits",
            Language.IT to "ISTRUZIONE: Usa questi fatti",
        )
        // Vollständigkeit ist Teil der Zusage: alle fünf Sprachen, keine still vergessen.
        assertEquals(Language.entries.toSet(), expectedHead.keys)

        Language.entries.forEach { lang ->
            val block = blockFor(url, lang)
            assertTrue(block.contains(expectedHead.getValue(lang)), "$lang: Kopf in Landessprache — $block")
            assertTrue(block.contains(expectedInstruction.getValue(lang)), "$lang: ANWEISUNG in Landessprache — $block")
        }
    }

    @Test
    fun `nicht-deutsche Turns bekommen den Hinweis auf die deutsche Quelle, DE nicht`() =
        withBridge(adenauerJson) { url ->
            assertTrue(blockFor(url, Language.EN).contains("in German; answer in English"))
            assertTrue(blockFor(url, Language.ES).contains("en alemán; responde igualmente en español"))
            assertTrue(blockFor(url, Language.FR).contains("en allemand ; réponds quand même en français"))
            assertTrue(blockFor(url, Language.IT).contains("in tedesco; rispondi comunque in italiano"))
            // DE: der Hinweis wäre inhaltsleer UND würde den eingefrorenen Block ändern.
            assertTrue(blockFor(url, Language.DE).endsWith("„Wikipedia“."))
        }

    @Test
    fun `ZAHLEN-VERTRAG folgt der Sprache und bleibt in JEDER zeichen-nackt`() = withBridge(factsJson) { url ->
        val expected = mapOf(
            Language.DE to "ZAHLEN-VERTRAG:",
            Language.EN to "NUMBER CONTRACT:",
            Language.ES to "CONTRATO DE CIFRAS:",
            Language.FR to "CONTRAT DES CHIFFRES :",
            Language.IT to "CONTRATTO DELLE CIFRE:",
        )
        Language.entries.forEach { lang ->
            val block = Fts5GroundingAdapter(baseUrl = url, enableNumberContract = true)
                .groundingBlock("Wie viele Zähne hat eine Weinbergschnecke?", RouteCategory.FACT_SHORT, lang)
                .block(Duration.ofSeconds(5)) ?: ""
            val marker = expected.getValue(lang)
            assertTrue(block.contains(marker), "$lang: Vertrags-Kopf — $block")
            // Der Wert-Anker bleibt in jeder Sprache derselbe Vertrag (die Wand strippt «»).
            assertTrue(block.contains("«40.000 Zähnchen»"), "$lang: Zahl-Span verbatim — $block")
            // Zeichen-Hygiene (Live-Befund 2026-07-02: das 4B kopiert jedes gezeigte
            // Anführungs-Muster) gilt in JEDER Sprache, nicht nur in der deutschen.
            val contract = block.substringAfter(marker)
            assertFalse(contract.contains("„"), "$lang: keine typografischen Anführungszeichen im Vertrag — $contract")
            assertFalse(contract.contains("“"), "$lang: keine typografischen Anführungszeichen im Vertrag — $contract")
            assertFalse(contract.contains("«…»"), "$lang: kein Meta-Marker-Literal im Vertrag — $contract")
        }
    }

    // ── (2) DE byte-identisch ────────────────────────────────────────────────────

    @Test
    fun `DE-Block ist BYTE-IDENTISCH zum Stand vor der Sprach-Naht`() = withBridge(adenauerJson) { url ->
        val expected = "\n\n---\n" +
            "HINTERGRUND (nur für dich, im Gespräch NICHT erwähnen):\n" +
            "• Konrad Adenauer: Konrad Adenauer war von 1949 bis 1963 der erste Bundeskanzler " +
            "der Bundesrepublik Deutschland.\n" +
            "ANWEISUNG: Nutze diese Fakten und antworte knapp im eigenen warmen Stil — " +
            "zitiere nichts wörtlich und erwähne nie „den Text“, „den Artikel“ oder „Wikipedia“."
        assertEquals(expected, blockFor(url, Language.DE), "DE ist eingefroren: kein Byte anders")
    }

    // ── (3) Rahmen übersetzt, Zitat nicht ────────────────────────────────────────

    @Test
    fun `EN-Turn uebersetzt NUR den Rahmen - der deutsche Wiki-Treffer bleibt woertlich stehen`() =
        withBridge(adenauerJson) { url ->
            val block = blockFor(url, Language.EN)

            // Zitierter Inhalt: Titel + Passage kommen aus dem DEUTSCHEN Index und
            // gehen unangetastet durch (Quellen/Nutzerdaten werden nie übersetzt).
            assertTrue(
                block.contains(
                    "• Konrad Adenauer: Konrad Adenauer war von 1949 bis 1963 der erste Bundeskanzler " +
                        "der Bundesrepublik Deutschland.",
                ),
                "die deutsche Passage steht WÖRTLICH im EN-Block: $block",
            )
            // Rahmen: englisch — und KEIN deutscher Rahmen-Rest mehr.
            assertTrue(block.contains("BACKGROUND (for you only, do NOT mention it in the conversation):"))
            assertFalse(block.contains("HINTERGRUND"), "kein deutscher Kopf im EN-Turn: $block")
            assertFalse(block.contains("ANWEISUNG"), "keine deutsche Anweisung im EN-Turn: $block")
        }
}
