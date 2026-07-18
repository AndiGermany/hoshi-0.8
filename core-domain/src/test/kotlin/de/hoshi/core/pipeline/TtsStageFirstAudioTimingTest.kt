package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.Language
import de.hoshi.core.port.TtsPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * **ttsFirstAudioMs-Messpunkt (Perf-Diary)** — deterministisch mit Fake-Clock:
 * Stage-Start (Subscribe von [TtsStage.transform]) → erster [ChatEvent.AudioChunk],
 * additiv ins durchfließende [ChatEvent.Done.stageTimings] gemerged. Ehrlichkeit:
 * ohne Audio (leerer TTS-Port) bleibt das Done die IDENTISCHE Instanz — nie ein
 * erfundenes 0; vorhandene Timings anderer Schichten bleiben erhalten.
 */
class TtsStageFirstAudioTimingTest {

    private class FakeClock(vararg ticks: Long) : () -> Long {
        private val queue = ArrayDeque(ticks.toList())
        private var last = ticks.last()
        override fun invoke(): Long = queue.removeFirstOrNull()?.also { last = it } ?: last
    }

    // Satz MIT Endzeichen (≥ minChars): das Audio fließt schon BEIM Delta — wie
    // im echten Streaming-Turn (der Rest-Flush-Sonderfall emittiert seit jeher
    // kein TtsAudioEnd; das Done-Timing deckt AUCH ihn, s. Merge-Test).
    private fun turn(done: ChatEvent.Done = ChatEvent.Done(provider = "LOCAL")): Flux<ChatEvent> = Flux.just(
        ChatEvent.Start(provider = "LOCAL", category = "FACT_SHORT", model = "brain"),
        ChatEvent.TextDelta("Der Turm ist hoch.", provider = "LOCAL"),
        done,
    )

    @Test
    fun `stage-start bis erstes audio wird gepinnt gemessen und ans Done gemerged`() {
        // Uhr-Ablesungen: (1) Subscribe=1000 · (2) erster Chunk=1350 · (3) AudioEnd=1500.
        val stage = TtsStage(
            tts = TtsPort { _, _ -> Mono.just(byteArrayOf(1, 2, 3)) },
            clockMs = FakeClock(1000L, 1350L, 1500L),
        )
        val events = stage.transform(turn(), Language.DE).collectList().block(Duration.ofSeconds(5))!!

        assertTrue(events.any { it is ChatEvent.TtsAudioStart }, "Audio lief wirklich")
        val done = events.filterIsInstance<ChatEvent.Done>().single()
        assertEquals(350L, done.stageTimings?.ttsFirstAudioMs, "1350−1000 aus der Fake-Uhr")
        val end = events.filterIsInstance<ChatEvent.TtsAudioEnd>().single()
        assertEquals(150L, end.actualMs, "actualMs nutzt dieselbe injizierte Uhr (1500−1350)")
    }

    @Test
    fun `vorhandene stageTimings anderer schichten bleiben beim merge erhalten`() {
        val stage = TtsStage(
            tts = TtsPort { _, _ -> Mono.just(byteArrayOf(1)) },
            clockMs = FakeClock(1000L, 1350L, 1500L),
        )
        val upstreamDone = ChatEvent.Done(
            provider = "LOCAL",
            stageTimings = ChatEvent.StageTimings(groundingMs = 42, brainTtftMs = 250),
        )
        val done = stage.transform(turn(upstreamDone), Language.DE)
            .collectList().block(Duration.ofSeconds(5))!!
            .filterIsInstance<ChatEvent.Done>().single()

        assertEquals(
            ChatEvent.StageTimings(groundingMs = 42, brainTtftMs = 250, ttsFirstAudioMs = 350),
            done.stageTimings,
            "die TtsStage merged NUR ihr eigenes Feld — Orchestrator-Messungen unangetastet",
        )
    }

    @Test
    fun `kein audio (leerer tts-port) - Done bleibt die identische instanz, null statt 0`() {
        val stage = TtsStage(
            tts = TtsPort { _, _ -> Mono.empty() },
            clockMs = FakeClock(1000L),
        )
        val original = ChatEvent.Done(provider = "LOCAL")
        val done = stage.transform(turn(original), Language.DE)
            .collectList().block(Duration.ofSeconds(5))!!
            .filterIsInstance<ChatEvent.Done>().single()

        assertSame(original, done, "ohne Audio fließt das Done byte-identisch durch")
        assertNull(done.stageTimings)
    }
}
