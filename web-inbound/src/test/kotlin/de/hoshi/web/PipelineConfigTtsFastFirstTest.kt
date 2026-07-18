package de.hoshi.web

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.Language
import de.hoshi.core.pipeline.TtsStage
import de.hoshi.core.port.TtsPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Beweist das Wiring des Fast-First-Time-to-First-Audio-Hebels in
 * [PipelineConfig.ttsStage] (Ticket #4). Das MASTER-Flag `HOSHI_TTS_FAST_FIRST_ENABLED`:
 *
 *  - OFF (Default) ist byte-neutral: die u.g. Preset-Knöpfe (2 / 24 / 300) werden
 *    IGNORIERT, das Chunking ist identisch zu einem nackten [TtsStage] mit Defaults
 *    (jeder Satz einzeln, kein Idle-Timer) — exakt das heutige Verhalten.
 *  - ON aktiviert das Backlog-Preset: die ersten Sätze gehen EINZELN raus (schnelles
 *    erstes Audio, ganze kurze Phrase — nicht zerhackt), kurze Folgesätze gruppieren in
 *    EINEN Synth-Call (flüssigere Stimme) ⇒ weniger Chunks als der Default-Pfad.
 *
 * Reine Konstruktor-Verdrahtung über echtes Chunking-Verhalten — kein Spring-Context,
 * kein Netz, deterministisch (idleFlush=0 ⇒ kein Timer im Pfad).
 */
class PipelineConfigTtsFastFirstTest {

    private val config = PipelineConfig()

    /** Fake-TTS: ein paar „WAV"-Bytes je Satz, merkt sich die synthetisierten Sätze. */
    private class FakeTtsPort : TtsPort {
        val sentences = mutableListOf<String>()
        private val calls = AtomicInteger(0)
        override fun synth(text: String, language: Language): Mono<ByteArray> {
            calls.incrementAndGet()
            sentences.add(text)
            return Mono.just(ByteArray(8) { 1 })
        }
    }

    private fun delta(text: String) = ChatEvent.TextDelta(text, provider = "LOCAL")

    /** Vier kurze, je >=12-Zeichen-Sätze (<24) — diskriminieren Default- vs. Grouped-Pfad. */
    private fun input(): List<ChatEvent> = listOf(
        ChatEvent.Start(provider = "LOCAL", category = "SMALLTALK", model = "brain"),
        delta("Guten Morgen dir. "),
        delta("Schoen geht es mir. "),
        delta("Heute scheint Sonne. "),
        delta("Wir gehen spazieren."),
        ChatEvent.Done(provider = "LOCAL"),
    )

    private fun runStage(stage: TtsStage): List<ChatEvent> =
        stage.transform(Flux.fromIterable(input()), Language.DE)
            .collectList().block(Duration.ofSeconds(5))!!

    @Test
    fun `Flag OFF ist byte-neutral - identisches Chunking wie der nackte Default-Stage`() {
        // Referenz: nacktes TtsStage mit reinen Defaults (= heutiges Verhalten).
        val refTts = FakeTtsPort()
        runStage(TtsStage(tts = refTts))

        // Config-Pfad mit Flag OFF, ABER absichtlich mit gesetzten Preset-Knöpfen:
        // OFF muss sie ignorieren ⇒ exakt dieselbe Chunk-Folge wie die Referenz.
        val offTts = FakeTtsPort()
        runStage(
            config.ttsStage(
                ttsPort = offTts,
                ttsImpl = "",
                fastFirstEnabled = false,
                fastFirstN = 2,
                groupedMinChars = 24,
                idleFlushMs = 300,
            ),
        )

        assertEquals(
            refTts.sentences, offTts.sentences,
            "Flag OFF chunkt byte-identisch zum nackten Default-Stage (Preset-Knöpfe ignoriert)",
        )
        // Default-Pfad: jeder der vier Sätze EINZELN (keine Gruppierung).
        assertEquals(4, offTts.sentences.size, "Default-Pfad: vier einzelne Sätze")
    }

    @Test
    fun `Flag ON aktiviert Fast-First - erste Saetze einzeln, kurzer Rest gruppiert`() {
        val onTts = FakeTtsPort()
        runStage(
            config.ttsStage(
                ttsPort = onTts,
                ttsImpl = "",
                fastFirstEnabled = true,
                fastFirstN = 2,
                groupedMinChars = 24,
                // idleFlush hier bewusst 0 (deterministisch, kein Timer) — der
                // Idle-Force-Flush-Pfad ist separat in TtsStageTest unter virtueller Zeit belegt.
                idleFlushMs = 0,
            ),
        )

        // Weniger Chunks als der Default (4) ⇒ es WURDE gruppiert.
        assertTrue(
            onTts.sentences.size < 4,
            "Fast-First/Grouped erzeugt weniger Chunks als der Default-Pfad: ${onTts.sentences}",
        )
        // Erstes Audio ist eine GANZE kurze Phrase (nicht zerhackt) — Ravi-Veto.
        val first = onTts.sentences.first()
        assertTrue(first.contains("Guten Morgen"), "erster Chunk ist die ganze erste Phrase: $first")
        assertTrue(first.trim().endsWith("."), "erster Chunk endet an echter Satzgrenze (nicht mitten im Wort)")
        // Der kurze Rest (Satz 3+4) verschmilzt in EINEN späten Chunk (Grouped).
        val last = onTts.sentences.last()
        assertTrue(
            last.contains("Sonne") && last.contains("spazieren"),
            "späte kurze Sätze gruppieren in EINEN Chunk: $last",
        )
    }
}
