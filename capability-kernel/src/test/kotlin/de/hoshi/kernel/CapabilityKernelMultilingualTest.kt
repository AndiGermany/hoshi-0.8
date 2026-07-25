package de.hoshi.kernel

import de.hoshi.core.dto.Language
import de.hoshi.core.pipeline.lang.LangDe
import de.hoshi.core.pipeline.lang.LanguagePackRegistry
import de.hoshi.core.tools.GateDecision
import de.hoshi.core.tools.ToolCall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **CapabilityKernelMultilingualTest** — die gesprochene Tat-Verweigerung folgt der
 * Turn-Sprache (Andi 2026-07-25: „es soll multilingual werden. von A-Z").
 *
 * Zwei Dinge bleiben dabei bewusst UNBERÜHRT und werden hier mitgeprüft:
 *  - **Die Entscheidung selbst.** Sprache färbt nur den Satz, nie das Verdict.
 *    Ein Deny bleibt in jeder Sprache ein Deny, ein Grant ein Grant — sonst wäre
 *    aus einem Trust-Kernel ein Sprach-Kernel geworden.
 *  - **Der [CapabilityKernel.Decision.Deny.reason].** Der ist Log-/Diagnose-Text
 *    für Andi und bleibt deutsch — er wird nie gesprochen.
 */
class CapabilityKernelMultilingualTest {

    private val kernel = CapabilityKernel()

    /** `lock.unlock` steht auf keiner Allowlist ⇒ DEFAULT DENY-ALL. */
    private fun denyDecision(language: Language) =
        assertInstanceOf(
            CapabilityKernel.Decision.Deny::class.java,
            kernel.permit("lock", "unlock", "lock.haustuer", emptyMap(), language),
        )

    // ── DE byte-identisch ────────────────────────────────────────────────────

    @Test
    fun `DE-Absagen sind byte-identisch zum Bestand`() {
        assertEquals(
            listOf(
                "Das mach ich gerade lieber nicht — dafür hab ich keine Freigabe.",
                "Da halt ich mich zurück: das schalte ich nicht einfach so.",
                "Lieber nicht — sowas lass ich bewusst, solange es nicht freigegeben ist.",
                "Das fass ich nicht an. Wenn das wirklich gewollt ist, müssen wir's erst freischalten.",
            ),
            LangDe.CAPABILITY_DENY.refusals,
        )
        assertEquals(CapabilityKernel.PHRASE_INVALID, LangDe.CAPABILITY_DENY.invalid)
    }

    /** Ohne Sprach-Argument (Default DE) fällt exakt eine der vier deutschen Phrasen. */
    @Test
    fun `ohne Sprach-Argument bleibt die Absage deutsch`() {
        repeat(30) {
            val deny = assertInstanceOf(
                CapabilityKernel.Decision.Deny::class.java,
                kernel.permit("lock", "unlock", "lock.haustuer", emptyMap()),
            )
            assertTrue(
                LangDe.CAPABILITY_DENY.refusals.contains(deny.phrase),
                "Default muss aus dem DE-Pool kommen, war: '${deny.phrase}'",
            )
        }
    }

    /** Die Slash-Injection-Absage bleibt für DE wörtlich die alte Konstante. */
    @Test
    fun `Slash-Injection-Phrase bleibt fuer DE die Bestands-Konstante`() {
        val deny = assertInstanceOf(
            CapabilityKernel.Decision.Deny::class.java,
            kernel.permit("light/../lock", "unlock", "lock.haustuer", emptyMap(), Language.DE),
        )
        assertEquals(CapabilityKernel.PHRASE_INVALID, deny.phrase)
    }

    // ── Jede Sprache spricht sich selbst ─────────────────────────────────────

    @Test
    fun `Absage folgt der Sprache - jede Sprache aus ihrem eigenen Pool`() {
        for (language in Language.entries) {
            val pack = LanguagePackRegistry.forLanguage(language).capabilityDeny
            repeat(30) {
                val deny = denyDecision(language)
                assertTrue(
                    pack.refusals.contains(deny.phrase),
                    "$language: eigene Absage erwartet, war '${deny.phrase}'",
                )
                if (language != Language.DE) {
                    assertFalse(
                        LangDe.CAPABILITY_DENY.refusals.contains(deny.phrase),
                        "$language: kein DE-Durchschlag: '${deny.phrase}'",
                    )
                }
            }
        }
    }

    @Test
    fun `Slash-Injection-Phrase folgt der Sprache`() {
        for (language in Language.entries) {
            val deny = assertInstanceOf(
                CapabilityKernel.Decision.Deny::class.java,
                kernel.permit("light/../lock", "unlock", "lock.haustuer", emptyMap(), language),
            )
            assertEquals(LanguagePackRegistry.forLanguage(language).capabilityDeny.invalid, deny.phrase)
        }
    }

    @Test
    fun `jede Sprache hat vier eigene, nicht-leere Absagen`() {
        val seen = mutableSetOf<String>()
        for (language in Language.entries) {
            val pack = LanguagePackRegistry.forLanguage(language).capabilityDeny
            assertEquals(4, pack.refusals.size, "$language: vier Varianten wie DE")
            assertTrue(pack.refusals.none { it.isBlank() }, "$language: keine leere Absage")
            assertTrue(pack.invalid.isNotBlank(), "$language: invalid-Phrase darf nicht leer sein")
            assertTrue(seen.add(pack.refusals.first()), "$language: eigene Formulierung erwartet")
        }
    }

    // ── Sprache färbt NUR den Satz, nie das Verdict ──────────────────────────

    @Test
    fun `die Entscheidung selbst ist sprach-unabhaengig`() {
        for (language in Language.entries) {
            // Erlaubt in JEDER Sprache (light.turn_on steht auf der Default-Allowlist).
            assertInstanceOf(
                CapabilityKernel.Decision.Grant::class.java,
                kernel.permit("light", "turn_on", "light.wohnzimmer", mapOf("brightness_pct" to 60), language),
                "$language: Grant darf nicht von der Sprache abhängen",
            )
            // Verweigert in JEDER Sprache (Range-Verletzung).
            assertInstanceOf(
                CapabilityKernel.Decision.Deny::class.java,
                kernel.permit("light", "turn_on", "light.wohnzimmer", mapOf("brightness_pct" to 500), language),
                "$language: Deny darf nicht von der Sprache abhängen",
            )
            // Hard-Off bleibt Hard-Deny in JEDER Sprache.
            assertInstanceOf(
                CapabilityKernel.Decision.Deny::class.java,
                CapabilityKernel(CapabilityPolicy(enabled = false))
                    .permit("light", "turn_on", "light.wohnzimmer", emptyMap(), language),
                "$language: enabled=false bleibt Hard-Deny",
            )
        }
    }

    /** Der interne Grund bleibt deutsch — er ist Diagnose, kein Produkt-Text. */
    @Test
    fun `der Deny-reason bleibt sprach-unabhaengig deutsch`() {
        for (language in Language.entries) {
            assertEquals(
                "capability 'lock.unlock' nicht freigegeben",
                denyDecision(language).reason,
                "$language: reason ist Log-Text, nicht übersetzt",
            )
        }
    }

    // ── Die Naht: die Sprache reist auf dem ToolCall ─────────────────────────

    /**
     * [CapabilityPort.check] ist ein Ein-Argument-Port — die Sprache kommt über
     * [ToolCall.language] herein. Genau dieser Weg (Adapter → Kernel) wird hier
     * geprüft, sonst wäre der mehrsprachige Kernel im Betrieb unerreichbar.
     */
    @Test
    fun `KernelCapabilityAdapter reicht die ToolCall-Sprache an den Kernel durch`() {
        val adapter = KernelCapabilityAdapter()
        for (language in Language.entries) {
            val pack = LanguagePackRegistry.forLanguage(language).capabilityDeny
            repeat(20) {
                val decision = adapter.check(
                    ToolCall("lock", "unlock", "lock.haustuer", language = language),
                )
                val deny = assertInstanceOf(GateDecision.Deny::class.java, decision)
                assertTrue(
                    pack.refusals.contains(deny.phrase),
                    "$language: Adapter muss die Sprache durchreichen, war '${deny.phrase}'",
                )
            }
        }
    }

    /** Ein ToolCall ohne gesetzte Sprache bleibt deutsch — byte-neutral für jeden Alt-Aufrufer. */
    @Test
    fun `ToolCall ohne gesetzte Sprache bleibt deutsch`() {
        val adapter = KernelCapabilityAdapter()
        repeat(20) {
            val deny = assertInstanceOf(
                GateDecision.Deny::class.java,
                adapter.check(ToolCall("lock", "unlock", "lock.haustuer")),
            )
            assertTrue(LangDe.CAPABILITY_DENY.refusals.contains(deny.phrase))
        }
    }
}
