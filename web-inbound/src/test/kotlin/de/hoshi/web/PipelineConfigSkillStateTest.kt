package de.hoshi.web

import de.hoshi.core.pipeline.DeterministicToolIntentClassifier
import de.hoshi.core.pipeline.ToolIntentClassifier
import de.hoshi.core.skills.SkillId
import de.hoshi.core.skills.SkillStatePort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Beweist das S2.2-Wiring in [PipelineConfig]:
 *  - `skillState`-Bean ist ohne `skills.json` byte-identisch zur früheren
 *    `ofStatic(...)`-Belegung (über alle Decken-Belegungen).
 *  - `intentClassifier` behält den DISABLED-Guard an der ENV-Decke und liest sonst
 *    den `skillState`-Port.
 * Reine Konstruktor-Verdrahtung — kein Spring-Context, kein Netz.
 */
class PipelineConfigSkillStateTest {

    private val config = PipelineConfig()
    private val ha = "http://localhost:8123"

    private fun nonexistentSettings(dir: Path): String = dir.resolve("skills.json").toString()

    @Test
    fun `skillState ohne json ist byte-identisch zu ofStatic ueber die Decken-Matrix`(@TempDir dir: Path) {
        val flags = listOf(true, false)
        for (tools in flags) for (scenes in flags) for (timer in flags) for (calc in flags) {
            val bean = config.skillState(
                toolsEnabled = tools,
                scenesEnabled = scenes,
                timerEnabled = timer,
                calculatorEnabled = calc,
                settingsPath = nonexistentSettings(dir),
            )
            val ofStatic = SkillStatePort.ofStatic(
                smartHome = tools,
                scenes = tools && scenes,
                timer = timer,
                calculator = calc,
            )
            for (id in SkillId.entries) {
                assertEquals(
                    ofStatic.isEnabled(id), bean.isEnabled(id),
                    "byte-neutral skillState: $id bei tools=$tools scenes=$scenes timer=$timer calc=$calc",
                )
            }
        }
    }

    @Test
    fun `intentClassifier alle Decken zu ⇒ DISABLED (Guard bleibt)`(@TempDir dir: Path) {
        val skillState = config.skillState(
            toolsEnabled = false, scenesEnabled = false, timerEnabled = false, calculatorEnabled = false,
            settingsPath = nonexistentSettings(dir),
        )
        val classifier = config.intentClassifier(
            toolsEnabled = false, scenesEnabled = false, timerEnabled = false, calculatorEnabled = false,
            calcEmbeddedEnabled = false, listEnabled = false, areasDynamicEnabled = false, haBaseUrl = ha, skillState = skillState,
        )
        assertSame(ToolIntentClassifier.DISABLED, classifier, "alle Decken zu ⇒ verhaltens-neutraler DISABLED")
    }

    @Test
    fun `intentClassifier eine Decke offen ⇒ deterministisch und liest den skillState-Port`(@TempDir dir: Path) {
        val skillState = config.skillState(
            toolsEnabled = false, scenesEnabled = false, timerEnabled = true, calculatorEnabled = false,
            settingsPath = nonexistentSettings(dir),
        )
        val classifier = config.intentClassifier(
            toolsEnabled = false, scenesEnabled = false, timerEnabled = true, calculatorEnabled = false,
            calcEmbeddedEnabled = false, listEnabled = false, areasDynamicEnabled = false, haBaseUrl = ha, skillState = skillState,
        )
        assertInstanceOf(
            DeterministicToolIntentClassifier::class.java, classifier,
            "offene Decke ⇒ deterministischer Classifier",
        )
    }

    @Test
    fun `intentClassifier NUR die List-Decke offen ⇒ deterministisch (Andi-JA 2026-07-08)`(@TempDir dir: Path) {
        val skillState = config.skillState(
            toolsEnabled = false, scenesEnabled = false, timerEnabled = false, calculatorEnabled = false,
            settingsPath = nonexistentSettings(dir),
        )
        val classifier = config.intentClassifier(
            toolsEnabled = false, scenesEnabled = false, timerEnabled = false, calculatorEnabled = false,
            calcEmbeddedEnabled = false, listEnabled = true, areasDynamicEnabled = false, haBaseUrl = ha, skillState = skillState,
        )
        assertInstanceOf(
            DeterministicToolIntentClassifier::class.java, classifier,
            "HOSHI_LIST_ENABLED allein oeffnet die Decke, unabhaengig von HOSHI_TOOLS_ENABLED",
        )
    }
}
