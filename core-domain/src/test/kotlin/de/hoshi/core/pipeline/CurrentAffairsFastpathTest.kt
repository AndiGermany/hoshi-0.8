package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.pipeline.lang.LangEn
import de.hoshi.core.port.CurrentAffairsFreshness
import de.hoshi.core.port.CurrentAffairsItem
import de.hoshi.core.port.CurrentAffairsPort
import de.hoshi.core.port.CurrentAffairsQuery
import de.hoshi.core.port.CurrentAffairsSnapshot
import de.hoshi.core.port.CurrentAffairsSourceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference

/**
 * Contract of the brain-free news briefing: WHAT counts as a news question
 * (DE+EN edges, and the date question that must NOT be swallowed) and HOW a
 * snapshot is spoken in all four freshness states.
 */
class CurrentAffairsFastpathTest {

    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")

    /** 2026-08-15, 09:30 Berlin — the read moment; the successful pull was 09:12. */
    private val clock: Clock = Clock.fixed(Instant.parse("2026-08-15T07:30:00Z"), berlin)
    private val refreshedAt: Instant = Instant.parse("2026-08-15T07:12:00Z")

    private fun item(
        id: String,
        title: String,
        snippet: String? = null,
    ) = CurrentAffairsItem(
        id = id,
        source = CurrentAffairsSourceId.TAGESSCHAU,
        title = title,
        snippet = snippet,
        canonicalUrl = "https://www.tagesschau.de/$id",
        publishedAt = refreshedAt,
        fetchedAt = refreshedAt,
        attribution = "tagesschau.de",
    )

    private fun snapshot(
        items: List<CurrentAffairsItem>,
        freshness: CurrentAffairsFreshness = CurrentAffairsFreshness.FRESH,
        lastSuccessfulRefreshAt: Instant? = refreshedAt,
    ) = CurrentAffairsSnapshot(
        items = items,
        observedAt = Instant.parse("2026-08-15T07:30:00Z"),
        lastSuccessfulRefreshAt = lastSuccessfulRefreshAt,
        freshness = freshness,
    )

    private fun fastpath(data: CurrentAffairsSnapshot? = null, seen: AtomicReference<CurrentAffairsQuery?>? = null) =
        CurrentAffairsFastpath(
            port = { query ->
                seen?.set(query)
                data ?: snapshot(emptyList(), CurrentAffairsFreshness.UNAVAILABLE, null)
            },
            clock = clock,
        )

    private val threeItems = listOf(
        item("1", "Bundestag beschließt neues Energiepaket"),
        item("2", "Unwetter im Süden"),
        item("3", "Tarifrunde geht in die nächste Verhandlungsrunde"),
    )

    // ── Recognizer: positive DE ───────────────────────────────────────────────

    @Test
    fun `erkennt die deutschen Kanten`() {
        val f = fastpath()
        listOf(
            "Was ist heute wichtig?",
            "Hoshi, was ist heute wichtig",
            "Was gibt es Neues?",
            "Was gibt's Neues?",
            "Gibt es was Neues?",
            "Gibt's Neuigkeiten?",
            "Gibt es Neuigkeiten",
            "Was ist heute passiert?",
            "Was ist heute Wichtiges passiert?",
        ).forEach { assertTrue(f.isCurrentAffairsQuery(it), "sollte matchen: $it") }
    }

    // ── Recognizer: positive EN ───────────────────────────────────────────────

    @Test
    fun `erkennt die englischen Kanten`() {
        val f = fastpath()
        listOf(
            "What's new today?",
            "What is new today",
            "What's important today?",
            "Anything important today?",
            "Any news today?",
            "Is there any news?",
            "What's in the news?",
        ).forEach { assertTrue(f.isCurrentAffairsQuery(it), "sollte matchen: $it") }
    }

    // ── Recognizer: the negatives that keep the neighbouring fastpaths alive ──

    @Test
    fun `was ist heute fuer ein Tag ist eine DATUMS-Frage und matcht NIE`() {
        val f = fastpath()
        listOf(
            "Was ist heute für ein Tag?",
            "Was ist heute fuer ein Tag",
            "Welcher Tag ist heute?",
            "What day is it today?",
            "What's the date?",
        ).forEach { assertFalse(f.isCurrentAffairsQuery(it), "darf NICHT matchen: $it") }
        // Gegenprobe: der Datums-Fastpath beansprucht genau diese Sätze.
        assertTrue(DateFastpath(clock = clock).isDateQuery("Was ist heute für ein Tag?"))
    }

    @Test
    fun `beilaeufiges heute oder news matcht nicht`() {
        val f = fastpath()
        listOf(
            "Was gibt es zu essen?",
            "Wie ist das Wetter heute?",
            "Mach heute Abend das Licht an",
            "Read the news article to me",
            "Ist heute noch etwas im Kalender?",
        ).forEach { assertFalse(f.isCurrentAffairsQuery(it), "darf NICHT matchen: $it") }
    }

    // ── Rendering FRESH ───────────────────────────────────────────────────────

    @Test
    fun `FRESH nennt die Stand-Zeit der letzten ERFOLGREICHEN Abfrage und je Meldung einen Satz`() {
        val out = fastpath(snapshot(threeItems)).handle("Was ist heute wichtig?", Language.DE)

        assertEquals(
            "Stand 9 Uhr 12: Bundestag beschließt neues Energiepaket. Unwetter im Süden. " +
                "Tarifrunde geht in die nächste Verhandlungsrunde.",
            out,
        )
    }

    @Test
    fun `die Stand-Zeit kommt NICHT aus observedAt`() {
        // observedAt ist 9:30 (Lesezeitpunkt), lastSuccessfulRefreshAt 9:12 —
        // gesprochen wird der Abruf, nie das Lesen.
        val out = fastpath(snapshot(threeItems)).handle("Was ist heute wichtig?", Language.DE)!!

        assertTrue(out.startsWith("Stand 9 Uhr 12:"), out)
        assertFalse(out.contains("9 Uhr 30"), "observedAt darf nie als Stand sprechen: $out")
    }

    @Test
    fun `der Snippet-Kern haengt an der Schlagzeile, doppelter Text nicht`() {
        val out = fastpath(
            snapshot(
                listOf(
                    item("1", "Unwetter im Süden", snippet = "Zwei Landkreise haben Katastrophenalarm ausgelöst."),
                    item("2", "Tarifrunde vertagt", snippet = "Tarifrunde vertagt"),
                ),
            ),
        ).handle("Was gibt es Neues?", Language.DE)

        assertEquals(
            "Stand 9 Uhr 12: Unwetter im Süden — Zwei Landkreise haben Katastrophenalarm ausgelöst. " +
                "Tarifrunde vertagt.",
            out,
        )
    }

    @Test
    fun `URLs werden nie vorgelesen`() {
        val out = fastpath(
            snapshot(
                listOf(
                    item(
                        "1",
                        "Neues Energiepaket https://www.tagesschau.de/inland/energie-101.html",
                        snippet = "Mehr dazu unter www.tagesschau.de im Liveblog.",
                    ),
                ),
            ),
        ).handle("Was ist heute wichtig?", Language.DE)!!

        assertFalse(out.contains("http"), out)
        assertFalse(out.contains("www."), out)
        assertTrue(out.contains("Neues Energiepaket"), out)
    }

    // ── Rendering STALE / EMPTY / UNAVAILABLE ─────────────────────────────────

    @Test
    fun `STALE nennt die Stand-Zeit MIT Alters-Hinweis`() {
        val out = fastpath(snapshot(threeItems, CurrentAffairsFreshness.STALE))
            .handle("Was ist heute wichtig?", Language.DE)!!

        assertTrue(out.startsWith("Stand 9 Uhr 12, älter als üblich: "), out)
        assertTrue(out.contains("Bundestag beschließt neues Energiepaket."), out)
    }

    @Test
    fun `EMPTY und UNAVAILABLE sagen ehrlich nichts vor - nie aktuell`() {
        val empty = fastpath(snapshot(emptyList(), CurrentAffairsFreshness.EMPTY))
            .handle("Was ist heute wichtig?", Language.DE)
        val unavailable = fastpath(snapshot(emptyList(), CurrentAffairsFreshness.UNAVAILABLE, null))
            .handle("Was ist heute wichtig?", Language.DE)

        assertEquals(CurrentAffairsFastpath.NONE_RECEIPT, empty)
        assertEquals(CurrentAffairsFastpath.NONE_RECEIPT, unavailable)
        assertFalse(empty!!.contains("aktuell"), "nie 'aktuell' behaupten: $empty")
        assertFalse(empty.contains("Stand"), "keine Stand-Zeit ohne Meldungen: $empty")
    }

    @Test
    fun `UNAVAILABLE ist der Default des NONE-Ports - der ungewired-Fall spricht ehrlich`() {
        val f = CurrentAffairsFastpath(CurrentAffairsPort.NONE, clock = clock)

        assertEquals(CurrentAffairsFastpath.NONE_RECEIPT, f.handle("Was ist heute wichtig?", Language.DE))
    }

    @Test
    fun `FRESH ohne sprechbaren Inhalt faellt auf die ehrliche Phrase zurueck statt auf eine nackte Stand-Zeit`() {
        val out = fastpath(snapshot(listOf(item("1", "https://www.tagesschau.de/x"))))
            .handle("Was ist heute wichtig?", Language.DE)

        assertEquals(CurrentAffairsFastpath.NONE_RECEIPT, out)
    }

    // ── Sprache, Deckel, Port-Query ───────────────────────────────────────────

    @Test
    fun `EN spricht das englische Sprachpaket samt 12-Stunden-Uhr`() {
        val out = fastpath(snapshot(threeItems)).handle("What's new today?", Language.EN)!!

        assertTrue(out.startsWith("As of 9:12 am: "), out)
        val none = fastpath(snapshot(emptyList(), CurrentAffairsFreshness.EMPTY))
            .handle("What's new today?", Language.EN)
        assertEquals(LangEn.PACK.currentAffairsNone, none)
    }

    @Test
    fun `der Port wird mit limit 3 und Haushaltsdefault gefragt - genau einmal`() {
        val seen = AtomicReference<CurrentAffairsQuery?>(null)
        fastpath(snapshot(threeItems), seen).handle("Was ist heute wichtig?", Language.DE)

        val query = seen.get()!!
        assertEquals(CurrentAffairsFastpath.SPOKEN_ITEM_LIMIT, query.limit)
        assertNull(query.viewerId, "null = Haushaltsdefault, nie ein geratener Profilname")
        assertTrue(query.sources.isEmpty(), "leer = alle konfigurierten Quellen")
    }

    @Test
    fun `mehr Meldungen als der Deckel und ueberlange Titel sprengen die Sprechlaenge nicht`() {
        val long = "Sehr langer Titel ".repeat(80).trim()
        val out = fastpath(
            snapshot(listOf(item("1", long), item("2", long), item("3", long), item("4", long))),
        ).handle("Was ist heute wichtig?", Language.DE)!!

        assertTrue(out.length <= CurrentAffairsFastpath.MAX_SPOKEN_CHARS, "Sprechlänge: ${out.length}")
        assertTrue(out.startsWith("Stand 9 Uhr 12: Sehr langer Titel"), out)
    }

    // ── Flag-OFF ──────────────────────────────────────────────────────────────

    @Test
    fun `DISABLED antwortet nie und liest den Port nicht`() {
        val seen = AtomicReference<CurrentAffairsQuery?>(null)
        val off = CurrentAffairsFastpath(
            port = { query -> seen.set(query); snapshot(threeItems) },
            clock = clock,
            enabled = false,
        )

        assertNull(off.handle("Was ist heute wichtig?", Language.DE))
        assertNull(seen.get(), "Flag OFF ⇒ kein Port-Zugriff")
        assertNull(CurrentAffairsFastpath.DISABLED.handle("Was ist heute wichtig?", Language.DE))
    }

    @Test
    fun `eine Nicht-Frage liest den Port nicht`() {
        val seen = AtomicReference<CurrentAffairsQuery?>(null)
        fastpath(snapshot(threeItems), seen).handle("Mach das Licht an", Language.DE)

        assertNull(seen.get(), "kein Treffer ⇒ kein Port-Zugriff")
    }
}
