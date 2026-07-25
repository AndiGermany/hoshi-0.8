package de.hoshi.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import java.lang.reflect.Method

/**
 * **TtsBuildPathSingleTruthTest** — der generische Riegel gegen den Fehlertyp
 * „zwei Wege, eine Regel, nur ein Weg gepflegt".
 *
 * **Die Vorgeschichte (zwei Mal derselbe Fehler).** Ein [de.hoshi.core.port.TtsPort]
 * entstand auf ZWEI Wegen:
 *  1. `PipelineConfig.ttsPort` — der BOOT-Weg. Den nimmt
 *     [TtsRuntimeConfig.delegatingTtsPort], solange niemand die Engine-/Stimm-Einstellung
 *     angefasst hat, also im Normalfall einer frischen Installation.
 *  2. [TtsEngineFactory] — der LAUFZEIT-Weg (`PUT /api/v1/settings/tts`).
 *
 * Und sie liefen zwei Mal auseinander:
 *  - **Sicherheitslücke:** die Sanitize-Hülle wurde nur in der Fabrik nachgezogen —
 *    im Boot-Pfad sprachen say/piper/voxtral Rohtext ([PipelineConfigTtsSanitizeTest]).
 *  - **Stiller No-op:** der Verbalizer hing nur in der Fabrik, die Loudness-Normalisierung
 *    nur im Boot-Bean ⇒ `HOSHI_TTS_VERBALIZE_ENABLED=true` tat auf einer frischen
 *    Installation NICHTS, und jeder Engine-Switch verlor die Loudness.
 *
 * **Die strukturelle Antwort (0.8.1):** es gibt nur noch EINEN Bau-Weg. `ttsPort` löst
 * `HOSHI_TTS` in eine Engine-Id auf und lässt die [TtsEngineFactory] bauen. Dieser Test
 * hält genau das fest — er ist bewusst NICHT auf ein einzelnes Flag zugeschnitten,
 * sondern verbietet die Rückkehr der Bau-Zweitschrift als solche. Wer wieder eine zweite
 * Kette bauen will, muss diesen Test aktiv anfassen und kann es nicht versehentlich tun.
 */
class TtsBuildPathSingleTruthTest {

    private val ttsPort: Method = PipelineConfig::class.java.methods.single { it.name == "ttsPort" }

    /** Alle Klassen, aus denen eine TTS-Kette besteht — Engine-Adapter UND Dekoratoren. */
    private val chainClasses = listOf(
        "de/hoshi/adapters/tts/OpenAiTtsAdapter",
        "de/hoshi/adapters/tts/SayTtsAdapter",
        "de/hoshi/adapters/tts/PiperTtsAdapter",
        "de/hoshi/adapters/tts/VoxtralTtsAdapter",
        "de/hoshi/adapters/tts/VerbalizingTtsPort",
        "de/hoshi/adapters/tts/LoudnessNormalizingTtsPort",
        "de/hoshi/adapters/tts/TtsLoudnessNormalizer",
        "de/hoshi/adapters/tts/IcuVerbalizer",
        "de/hoshi/web/SanitizingTtsPort",
        "de/hoshi/web/NeverSpeakTtsSanitizer",
    )

    @Test
    fun `PipelineConfig ttsPort BAUT nicht mehr selbst - es bekommt die Fabrik gereicht`() {
        assertTrue(
            ttsPort.parameterTypes.any { it == TtsEngineFactory::class.java },
            "ttsPort muss die EINE Fabrik injiziert bekommen, hatte: ${ttsPort.parameterTypes.map { it.simpleName }}",
        )
        // Die Bean braucht genau zwei Zutaten: die Fabrik + den Boot-Wunsch HOSHI_TTS.
        // Alles weitere (Base-URLs, Stimmen, Flags) waere Baumaterial — und damit die
        // Einladung, hier wieder eine eigene Kette zu bauen.
        assertEquals(
            2,
            ttsPort.parameterCount,
            "ttsPort darf ausser Fabrik + HOSHI_TTS nichts mehr brauchen, hatte: " +
                "${ttsPort.parameterTypes.map { it.simpleName }}",
        )
        assertEquals(
            listOf("\${HOSHI_TTS:}"),
            valueExprsOf(ttsPort),
            "der einzige erlaubte @Value ist der Boot-Wunsch selbst",
        )
    }

    @Test
    fun `PipelineConfig referenziert ueberhaupt keine TTS-Kettenklasse mehr`() {
        // Reflection sieht keine Methoden-RUEMPFE — der Konstantenpool der .class-Datei
        // aber schon: jede konstruierte/referenzierte Klasse steht dort als UTF-8-Eintrag.
        // Kommt keine einzige Ketten-Klasse mehr vor, KANN diese Config keine zweite Kette
        // zusammensetzen. Das ist der eigentliche Einzigkeits-Beweis.
        val bytes = PipelineConfig::class.java
            .getResourceAsStream("PipelineConfig.class")!!
            .use { it.readBytes() }
        val pool = String(bytes, Charsets.ISO_8859_1)

        val found = chainClasses.filter { pool.contains(it) }
        assertTrue(
            found.isEmpty(),
            "PipelineConfig baut wieder selbst an der TTS-Kette (referenziert: $found) — " +
                "genau so entstanden die Boot-Pfad-Luecke und der stille Verbalize-No-op. " +
                "Dekoratoren gehoeren AUSSCHLIESSLICH in TtsEngineFactory.build().",
        )
    }

    @Test
    fun `die Fabrik referenziert sie alle - der Gegenbeweis, dass die Kette wirklich dort haengt`() {
        // Ohne diese Gegenprobe waere der Test oben auch gruen, wenn die Kette schlicht
        // verschwunden waere (z.B. nach einem verunglueckten Merge).
        val bytes = TtsEngineFactory::class.java
            .getResourceAsStream("TtsEngineFactory.class")!!
            .use { it.readBytes() }
        val pool = String(bytes, Charsets.ISO_8859_1)

        val missing = chainClasses.filterNot { pool.contains(it) }
        assertTrue(
            missing.isEmpty(),
            "die EINE Bauwahrheit haengt diese Kettenglieder nicht mehr an: $missing",
        )
    }

    @Test
    fun `alle HOSHI_TTS-Flags der Kette stehen an EINER Stelle - der Fabrik-Bean`() {
        // Symmetrie braucht man nur zwischen zwei Wegen. Es gibt nur noch einen: samtliche
        // `HOSHI_TTS_*`-Bau-Flags werden ausschliesslich an der Fabrik-Aufrufstelle gelesen.
        // (`HOSHI_TTS` selbst — der Engine-WUNSCH, kein Bau-Flag — darf weiterhin an
        // mehreren Raendern gelesen werden; er wird ueberall durch dieselbe Funktion
        // TtsEngineIds.canonicalOf geschickt.)
        val factoryBean = TtsRuntimeConfig::class.java.methods.single { it.name == "ttsEngineFactory" }
        val flagsAtFactory = valueExprsOf(factoryBean).filter { it.startsWith("\${HOSHI_TTS_") }.toSet()

        assertTrue(
            flagsAtFactory.containsAll(
                setOf(
                    "\${HOSHI_TTS_SANITIZE_ENABLED:true}",
                    "\${HOSHI_TTS_VERBALIZE_ENABLED:false}",
                    "\${HOSHI_TTS_LOUDNESS_ENABLED:false}",
                    "\${HOSHI_TTS_STREAM_ENABLED:false}",
                ),
            ),
            "die Bau-Flags muessen an der EINEN Aufrufstelle stehen, gefunden: $flagsAtFactory",
        )

        // Und die Fabrik muss ALLES, was sie kann, von dort auch gereicht bekommen: gleiche
        // Parameter-Zahl wie ihr Ctor. Wer der Fabrik ein Feld hinzufuegt und vergisst, es am
        // Bean zu binden, bekaeme sonst wieder einen stillen Default (exakt der
        // Verbalize-No-op von 0.8.0).
        val ctorParams = TtsEngineFactory::class.java.declaredConstructors
            .filterNot { it.isSynthetic }
            .maxOf { it.parameterCount }
        assertEquals(
            ctorParams,
            factoryBean.parameterCount,
            "TtsRuntimeConfig.ttsEngineFactory muss JEDEN Ctor-Parameter der Fabrik explizit binden " +
                "(sonst greift ein stiller Default statt der Env)",
        )
    }

    private fun valueExprsOf(m: Method): List<String> =
        m.parameterAnnotations.asSequence()
            .flatMap { it.asSequence() }
            .filterIsInstance<Value>()
            .map { it.value }
            .toList()
}
