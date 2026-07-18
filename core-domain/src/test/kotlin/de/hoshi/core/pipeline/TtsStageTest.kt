package de.hoshi.core.pipeline

import de.hoshi.core.dto.ChatEvent
import de.hoshi.core.dto.Language
import de.hoshi.core.port.TtsPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.test.StepVerifier
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * Beweist die Audio-Schicht des [TtsStage] OHNE Live-Voxtral — mit Fakes:
 *  (a) vollständige Sätze → [ChatEvent.AudioChunk] in Reihenfolge (seq monoton),
 *      eingerahmt von genau EINEM [ChatEvent.TtsAudioStart] und einem
 *      [ChatEvent.TtsAudioEnd] vor [ChatEvent.Done]; Text fließt unverändert durch.
 *  (b) Best-Effort / Never-Silent: synth wirft → KEIN Audio, aber der Text-Turn
 *      läuft komplett durch (alle TextDelta + Done bleiben erhalten).
 *  (c) leerer/blanker Text → kein Audio, kein Crash.
 *  (a3/b2) Streaming-Port ([TtsPort.synthStream] überschrieben) → MEHRERE Chunks
 *      je Satz, seq strikt monoton über Satz- UND Slice-Grenzen, TtsAudioStart beim
 *      ERSTEN Slice; Stream-Abriss behält emittierte Slices und killt den Turn nicht.
 */
class TtsStageTest {

    /** Fake-TTS: gibt je Satz ein paar „WAV"-Bytes zurück, zählt die Aufrufe. */
    private class FakeTtsPort(
        private val bytesPerCall: Int = 8,
        private val error: Throwable? = null,
    ) : TtsPort {
        val calls = AtomicInteger(0)
        val sentences = mutableListOf<String>()
        override fun synth(text: String, language: Language): Mono<ByteArray> {
            calls.incrementAndGet()
            sentences.add(text)
            if (error != null) return Mono.error(error)
            return Mono.just(ByteArray(bytesPerCall) { 1 })
        }
    }

    /**
     * Fake-STREAMING-TTS: liefert je Satz MEHRERE WAV-Slices (Verhalten eines echten
     * Streaming-Adapters, der [TtsPort.synthStream] überschreibt). Slice `i` eines
     * Satzes trägt den Payload-Wert `i+1` — so ist im Output nachweisbar, welcher
     * Chunk aus welcher Slice stammt. Optional reißt der Stream nach
     * [failAfterSlices] Slices ab (Best-Effort-Beweis mitten im Satz).
     */
    private class FakeStreamingTtsPort(
        private val slicesPerSentence: Int = 3,
        private val failAfterSlices: Int = -1,
    ) : TtsPort {
        val sentences = mutableListOf<String>()
        override fun synth(text: String, language: Language): Mono<ByteArray> =
            Mono.error(IllegalStateException("Batch-Pfad darf beim Streaming-Adapter nicht laufen"))
        override fun synthStream(text: String, language: Language): Flux<ByteArray> {
            sentences.add(text)
            val slices = Flux.range(1, slicesPerSentence).map { i -> ByteArray(8) { i.toByte() } }
            return if (failAfterSlices >= 0) {
                slices.take(failAfterSlices.toLong())
                    .concatWith(Flux.error(RuntimeException("Stream riss ab")))
            } else {
                slices
            }
        }
    }

    private fun stage(tts: TtsPort) = TtsStage(tts = tts, minChars = 4)

    private fun run(stage: TtsStage, events: List<ChatEvent>, lang: Language = Language.DE): List<ChatEvent> =
        stage.transform(Flux.fromIterable(events), lang).collectList().block(Duration.ofSeconds(5))!!

    private fun delta(text: String) = ChatEvent.TextDelta(text, provider = "LOCAL")

    // ── (a) Sätze → AudioChunk in Reihenfolge, eingerahmt, Text intakt ───────────
    @Test
    fun `vollstaendige Saetze ergeben AudioChunks in Reihenfolge mit Start vor und End nach`() {
        val tts = FakeTtsPort()
        val input = listOf(
            ChatEvent.Start(provider = "LOCAL", category = "SMALLTALK", model = "brain"),
            delta("Hallo zusammen. "),
            delta("Wie geht es dir? "),
            delta("Schoen dich zu hoeren!"),
            ChatEvent.Done(provider = "LOCAL"),
        )
        val out = run(stage(tts), input)

        // Text fließt unverändert durch (alle 3 TextDelta erhalten, in Reihenfolge).
        val texts = out.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("Hallo zusammen. Wie geht es dir? Schoen dich zu hoeren!", texts)

        // Genau ein Start, mind. ein End, terminales Done.
        assertEquals(1, out.count { it is ChatEvent.TtsAudioStart }, "genau EIN TtsAudioStart")
        assertTrue(out.first() is ChatEvent.Start, "Turn beginnt mit Start")
        assertTrue(out.last() is ChatEvent.Done, "Turn endet mit Done")

        val chunks = out.filterIsInstance<ChatEvent.AudioChunk>()
        assertEquals(3, chunks.size, "drei Sätze → drei AudioChunks")
        // seq monoton 0,1,2.
        assertEquals(listOf(0, 1, 2), chunks.map { it.seq })
        // jeder Chunk trägt nicht-leere (base64-)Bytes.
        chunks.forEach { assertTrue(Base64.getDecoder().decode(it.data).isNotEmpty(), "Audio-Bytes nicht leer") }

        // Reihenfolge: Start … TtsAudioStart vor erstem AudioChunk … TtsAudioEnd vor Done.
        val idxAudioStart = out.indexOfFirst { it is ChatEvent.TtsAudioStart }
        val idxFirstChunk = out.indexOfFirst { it is ChatEvent.AudioChunk }
        val idxEnd = out.indexOfFirst { it is ChatEvent.TtsAudioEnd }
        val idxDone = out.indexOfFirst { it is ChatEvent.Done }
        assertTrue(idxAudioStart in 0 until idxFirstChunk, "TtsAudioStart vor erstem AudioChunk")
        assertTrue(idxEnd in (idxFirstChunk + 1) until idxDone, "TtsAudioEnd nach Audio, vor Done")

        // Sprache fließt durch — Fake bekommt die Sätze.
        assertEquals(3, tts.calls.get())
    }

    // ── (a3) Streaming-Adapter → MEHRERE Slices je Satz, seq strikt monoton ──────
    @Test
    fun `Streaming-Adapter liefert mehrere Chunks je Satz mit strikt monotonem seq ueber Satz- und Slice-Grenzen`() {
        val tts = FakeStreamingTtsPort(slicesPerSentence = 3)
        val input = listOf(
            ChatEvent.Start(provider = "LOCAL", category = "SMALLTALK", model = "brain"),
            delta("Hallo zusammen. "),
            delta("Wie geht es dir?"),
            ChatEvent.Done(provider = "LOCAL"),
        )
        val out = run(stage(tts), input)

        // 2 Sätze × 3 Slices = 6 AudioChunks.
        val chunks = out.filterIsInstance<ChatEvent.AudioChunk>()
        assertEquals(6, chunks.size, "zwei Sätze mal drei Slices")
        assertEquals(listOf("Hallo zusammen.", "Wie geht es dir?"), tts.sentences)

        // seq-VERTRAG: strikt monoton aufsteigend ab 0 — über Satz- UND Slice-Grenzen
        // (FE-AudioQueue: „streng aufsteigend ab head").
        assertEquals(listOf(0, 1, 2, 3, 4, 5), chunks.map { it.seq }, "seq lückenlos strikt monoton")

        // Slice-Reihenfolge INNERHALB der Sätze bleibt erhalten (Payload 1,2,3 je Satz).
        val payloads = chunks.map { Base64.getDecoder().decode(it.data)[0].toInt() }
        assertEquals(listOf(1, 2, 3, 1, 2, 3), payloads, "Slices je Satz in Synthese-Reihenfolge")

        // Genau EIN TtsAudioStart, und zwar DIREKT vor dem ERSTEN Slice-Chunk (nicht erst
        // nach dem kompletten ersten Satz) — das ist der Latenz-Gewinn auf dem Draht.
        assertEquals(1, out.count { it is ChatEvent.TtsAudioStart }, "genau EIN TtsAudioStart trotz 6 Chunks")
        val idxAudioStart = out.indexOfFirst { it is ChatEvent.TtsAudioStart }
        val idxFirstChunk = out.indexOfFirst { it is ChatEvent.AudioChunk }
        assertEquals(idxAudioStart + 1, idxFirstChunk, "tts_audio_start unmittelbar vor der ERSTEN Slice")

        // Rahmen intakt: Ende nach letztem Chunk, vor Done; Text unverändert durch.
        val idxEnd = out.indexOfFirst { it is ChatEvent.TtsAudioEnd }
        val idxDone = out.indexOfFirst { it is ChatEvent.Done }
        assertTrue(idxEnd in (idxFirstChunk + 1) until idxDone, "TtsAudioEnd nach Audio, vor Done")
        val texts = out.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("Hallo zusammen. Wie geht es dir?", texts)
    }

    // ── (b2) Stream reißt MITTEN im Satz ab → emittierte Slices bleiben, Turn lebt ─
    @Test
    fun `Stream-Abriss mitten im Satz behaelt emittierte Slices und killt den Turn nicht`() {
        val tts = FakeStreamingTtsPort(slicesPerSentence = 3, failAfterSlices = 2)
        val input = listOf(
            ChatEvent.Start(provider = "LOCAL", category = "SMALLTALK", model = "brain"),
            delta("Hallo zusammen."),
            ChatEvent.Done(provider = "LOCAL"),
        )
        val out = run(stage(tts), input)

        // Die 2 VOR dem Fehler emittierten Slices sind da (seq 0,1), der Rest verschluckt.
        val chunks = out.filterIsInstance<ChatEvent.AudioChunk>()
        assertEquals(listOf(0, 1), chunks.map { it.seq }, "Slices vor dem Abriss bleiben gültig")
        // Rahmen + Text intakt (Never-Silent): Start kam (Audio lief), End vor Done.
        assertEquals(1, out.count { it is ChatEvent.TtsAudioStart })
        assertEquals(1, out.count { it is ChatEvent.TtsAudioEnd })
        assertTrue(out.last() is ChatEvent.Done, "Turn endet sauber mit Done")
        val texts = out.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("Hallo zusammen.", texts, "Text läuft trotz Stream-Abriss durch")
    }

    // ── (b) synth wirft → Best-Effort: Text-Turn läuft komplett durch ────────────
    @Test
    fun `Audio-Fehler killt den Text-Turn nicht (never-silent best-effort)`() {
        val tts = FakeTtsPort(error = RuntimeException("Voxtral weg"))
        val input = listOf(
            ChatEvent.Start(provider = "LOCAL", category = "SMALLTALK", model = "brain"),
            delta("Hallo zusammen. "),
            delta("Alles gut bei dir?"),
            ChatEvent.Done(provider = "LOCAL"),
        )
        val out = run(stage(tts), input)

        // Kein Audio (alles verschluckt), aber Text + Done komplett erhalten.
        assertTrue(out.none { it is ChatEvent.AudioChunk }, "bei Fehler kein Audio")
        assertTrue(out.none { it is ChatEvent.TtsAudioStart }, "ohne Audio auch kein Start")
        assertTrue(out.none { it is ChatEvent.TtsAudioEnd }, "ohne Audio auch kein End")
        val texts = out.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("Hallo zusammen. Alles gut bei dir?", texts, "Text läuft trotz TTS-Fehler durch")
        assertTrue(out.last() is ChatEvent.Done, "Turn endet sauber mit Done")
    }

    // ── (a2) provider-Tag spiegelt die konfigurierte Engine (Telemetrie-Wahrheit) ─
    @Test
    fun `TtsAudioStart provider spiegelt die konfigurierte Engine`() {
        val tts = FakeTtsPort()
        // Stage explizit auf die OpenAI-Engine konfiguriert (statt Default „voxtral").
        val stage = TtsStage(tts = tts, minChars = 4, provider = { "openai" })
        val input = listOf(
            ChatEvent.Start(provider = "LOCAL", category = "SMALLTALK", model = "brain"),
            delta("Hallo zusammen."),
            ChatEvent.Done(provider = "LOCAL"),
        )
        val out = run(stage, input)

        // Audio läuft weiterhin (mind. ein Chunk), und der Start trägt die echte Engine.
        val start = out.filterIsInstance<ChatEvent.TtsAudioStart>().single()
        assertEquals("openai", start.provider, "TtsAudioStart.provider == konfigurierte Engine")
        assertTrue(out.any { it is ChatEvent.AudioChunk }, "Audio-Chunk-Emission bleibt intakt")
    }

    // ── (c) leerer Text → kein Audio, kein Crash ─────────────────────────────────
    @Test
    fun `leerer Text erzeugt kein Audio und crasht nicht`() {
        val tts = FakeTtsPort()
        val input = listOf(
            ChatEvent.Start(provider = "LOCAL", category = "EMPTY", model = "policy"),
            delta("   "),
            ChatEvent.Done(provider = "LOCAL"),
        )
        val out = run(stage(tts), input)

        assertTrue(out.none { it is ChatEvent.AudioChunk }, "blanker Text → kein Audio")
        assertEquals(0, tts.calls.get(), "synth wird für blanken Text nicht gerufen")
        assertTrue(out.last() is ChatEvent.Done)
    }

    // ── (d) Fast-First/Grouped — Latenz-Hebel 1 (0.5-Port T081) ───────────────────
    @Test
    fun `Fast-First schneidet erste N Saetze kurz, Grouped buendelt spaetere`() {
        val tts = FakeTtsPort()
        // fastFirstN=2 → die ersten 2 Sätze mit minChars=4 (einzeln, schnelles erstes Audio);
        // ab Satz 3 groupedMinChars=40 → kurze Folgesätze verschmelzen in EINEN Synth-Call.
        val stage = TtsStage(tts = tts, minChars = 4, fastFirstN = 2, groupedMinChars = 40)
        val input = listOf(
            ChatEvent.Start(provider = "LOCAL", category = "SMALLTALK", model = "brain"),
            delta("Hallo Welt. "),
            delta("Wie gehts. "),
            delta("Ja. Nein. Vielleicht. Schon. Klar."),
            ChatEvent.Done(provider = "LOCAL"),
        )
        run(stage, input)

        // Erste zwei Sätze EINZELN (Fast-First); der kurze Rest gebündelt in EINEM Call (Grouped:
        // 35 < 40 Zeichen → keine Boundary während des Streams → Turn-Ende-Flush als ein Satz).
        assertEquals(
            listOf("Hallo Welt.", "Wie gehts.", "Ja. Nein. Vielleicht. Schon. Klar."),
            tts.sentences,
        )
    }

    // ── (d2) Ordinal-Guard — Andis Juli-Pitch-Befund 2026-07-01 ───────────────────
    @Test
    fun `Ordinal-Datum wird nicht am Ordinal-Punkt gesplittet — genau EIN synth-Call`() {
        val tts = FakeTtsPort()
        val stage = TtsStage(tts = tts, minChars = 4)
        // Streaming-Race nachgestellt: das erste Delta endet exakt auf dem Ordinal-Punkt
        // ("der 1."), das "Juli"-Delta kommt erst danach. Vor dem Fix splittete hier der
        // Puffer → zwei synth-Calls → frische Satz-Prosodie ab "Juli" (Pitch-Sprung).
        val input = listOf(
            ChatEvent.Start(provider = "LOCAL", category = "SMALLTALK", model = "brain"),
            delta("Heute ist Mittwoch, der 1."),
            delta(" Juli 2026."),
            ChatEvent.Done(provider = "LOCAL"),
        )
        run(stage, input)

        // Der ganze Datums-Satz geht als EIN Satz an die TTS (ein Call, kein Bruch).
        assertEquals(listOf("Heute ist Mittwoch, der 1. Juli 2026."), tts.sentences)
        assertEquals(1, tts.calls.get(), "genau EIN synth-Call fuer den Datums-Satz")
    }

    // ── (e) Byte-Neutralität — Default-Knöpfe ⇒ identisches Chunking wie heute ─────
    @Test
    fun `Default-Knoepfe chunken identisch wie heute (byte-neutral, keine Gruppierung)`() {
        val tts = FakeTtsPort()
        // Default: fastFirstN=0, groupedMinChars=minChars, idleFlushMs=0 → currentMin==minChars
        // für JEDEN Satz ⇒ jeder Satz einzeln synthetisiert (heutiges Verhalten).
        val stage = TtsStage(tts = tts, minChars = 4)
        val input = listOf(
            ChatEvent.Start(provider = "LOCAL", category = "SMALLTALK", model = "brain"),
            delta("Hallo Welt. "),
            delta("Wie gehts. "),
            delta("Ja. Nein. Vielleicht. Schon. Klar."),
            ChatEvent.Done(provider = "LOCAL"),
        )
        run(stage, input)

        // EXAKT dieselbe Eingabe wie der Grouped-Test, aber mit Default-Knöpfen: KEINE
        // Gruppierung, jeder Satz wird einzeln gechunkt (Beweis byte-neutral).
        assertEquals(
            listOf("Hallo Welt.", "Wie gehts.", "Ja.", "Nein.", "Vielleicht.", "Schon.", "Klar."),
            tts.sentences,
        )
    }

    // ── (f) Idle Mid-Sentence Force-Flush — Latenz-Hebel 2, virtuelle Zeit ────────
    @Test
    fun `Idle-Flush emittiert gepufferten Teilsatz nach flushMs (virtuelle Zeit)`() {
        val tts = FakeTtsPort()
        val sink = Sinks.many().unicast().onBackpressureBuffer<ChatEvent>()
        StepVerifier.withVirtualTime {
            // Stage IM Supplier bauen → der Default-Scheduler Schedulers.parallel() liefert hier
            // den von withVirtualTime installierten VirtualTimeScheduler ⇒ der Idle-Timer ist
            // über thenAwait/expectNoEvent deterministisch steuerbar (kein now() im reinen Pfad).
            val stage = TtsStage(tts = tts, minChars = 4, idleFlushMs = 300)
            stage.transform(sink.asFlux(), Language.DE)
        }
            // Teilsatz OHNE Satzzeichen → keine Boundary → bleibt gepuffert.
            .then { sink.tryEmitNext(delta("Teilsatz ohne Ende")) }
            .assertNext { assertTrue(it is ChatEvent.TextDelta, "TextDelta fließt sofort durch") }
            // Vor flushMs passiert NICHTS (kein voreiliges Audio).
            .expectNoEvent(Duration.ofMillis(299))
            // Die fehlende 1ms → Idle-Force-Flush des Teilsatzes als Audio.
            .thenAwait(Duration.ofMillis(1))
            .assertNext { assertTrue(it is ChatEvent.TtsAudioStart, "erstes Audio rahmt mit TtsAudioStart") }
            .assertNext {
                assertTrue(it is ChatEvent.AudioChunk, "geflushter Teilsatz wird zu AudioChunk")
                assertEquals(0, (it as ChatEvent.AudioChunk).seq, "seq startet bei 0")
            }
            // Turn sauber beenden — Restpuffer ist leer (schon geflusht), nur End+Done folgen.
            .then {
                sink.tryEmitNext(ChatEvent.Done(provider = "LOCAL"))
                sink.tryEmitComplete()
            }
            .assertNext { assertTrue(it is ChatEvent.TtsAudioEnd, "TtsAudioEnd vor Done") }
            .assertNext { assertTrue(it is ChatEvent.Done, "Turn endet mit Done") }
            .verifyComplete()

        // Genau der gepufferte Teilsatz ging an die TTS (einmal, nicht doppelt am Turn-Ende).
        assertEquals(listOf("Teilsatz ohne Ende"), tts.sentences)
    }

    // ── (g) Per-Turn-Voice (Backlog #6): Durchreichung bis zum Port ──────────────

    /**
     * Fake, der den voice-aware [TtsPort.synthStream]-Overload überschreibt und den
     * per-Turn-Voice je synth-Call kapert (das Verhalten eines stimm-fähigen Adapters).
     */
    private class VoiceCapturingTtsPort : TtsPort {
        val voices = mutableListOf<String?>()
        override fun synth(text: String, language: Language): Mono<ByteArray> =
            Mono.just(ByteArray(8) { 1 })
        override fun synthStream(text: String, language: Language, voice: String?): Flux<ByteArray> {
            voices.add(voice)
            return synth(text, language).flux()
        }
    }

    @Test
    fun `voice aus transform erreicht den voice-aware synthStream-Overload bei JEDEM Satz`() {
        val tts = VoiceCapturingTtsPort()
        val input = listOf(
            ChatEvent.Start(provider = "LOCAL", category = "SMALLTALK", model = "brain"),
            delta("Hallo zusammen. "),
            delta("Wie geht es dir?"),
            ChatEvent.Done(provider = "LOCAL"),
        )
        val out = stage(tts).transform(Flux.fromIterable(input), Language.DE, "nova")
            .collectList().block(Duration.ofSeconds(5))!!

        assertTrue(tts.voices.size >= 2, "mehrere Sätze ⇒ mehrere synth-Calls: ${tts.voices}")
        assertTrue(tts.voices.all { it == "nova" }, "JEDER Satz muss den Request-Voice tragen: ${tts.voices}")
        assertTrue(out.any { it is ChatEvent.AudioChunk }, "Audio fließt mit Voice weiterhin")
    }

    @Test
    fun `ohne voice-Argument bleibt der Overload-Default null — byte-neutraler Alt-Pfad`() {
        val tts = VoiceCapturingTtsPort()
        val out = run(stage(tts), listOf(delta("Hallo zusammen."), ChatEvent.Done(provider = "LOCAL")))

        assertTrue(tts.voices.isNotEmpty(), "mindestens ein synth-Call erwartet")
        assertTrue(tts.voices.all { it == null }, "Default-transform ⇒ voice null: ${tts.voices}")
        assertTrue(out.any { it is ChatEvent.AudioChunk }, "Alt-Pfad liefert unverändert Audio")
    }
}
