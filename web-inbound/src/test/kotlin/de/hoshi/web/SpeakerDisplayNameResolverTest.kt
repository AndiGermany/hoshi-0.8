package de.hoshi.web

import de.hoshi.core.dto.SpeakerContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * **SpeakerDisplayNameResolver** — beweist die Text-Chat-Namens-Lücke isoliert
 * (kein Spring-Context, kein Orchestrator): Flag OFF/kein Store/kein Treffer/schon
 * gesetzter Name ⇒ [SpeakerContext] unverändert; nur ein sicherer Treffer bei
 * Flag ON UND DTO-Default-`displayName` ersetzt ihn, groß geschrieben — exakt die
 * Konvention von [VoiceInboundController].
 */
class SpeakerDisplayNameResolverTest {

    private fun storeWith(dir: Path, vararg names: String): SpeakerProfileStore {
        val store = SpeakerProfileStore(dir.resolve("speaker-profiles.json"))
        for (n in names) store.upsert(n, floatArrayOf(0.1f, 0.2f, 0.3f))
        return store
    }

    @Test
    fun `null-Kontext bleibt null, egal wie das Flag steht`() {
        val resolver = SpeakerDisplayNameResolver(enabled = true, storeProvider = SpeakerDisplayNameResolver.providerOf(null))
        assertNull(resolver.resolve(null))
    }

    @Test
    fun `Flag OFF - Kontext bleibt unveraendert, selbst wenn ein Profil traefe`(@TempDir dir: Path) {
        val store = storeWith(dir, "andi")
        val resolver = SpeakerDisplayNameResolver(enabled = false, storeProvider = SpeakerDisplayNameResolver.providerOf(store))
        val context = SpeakerContext(speakerId = "andi") // displayName bleibt DTO-Default "Unbekannt"

        val result = resolver.resolve(context)

        assertSame(context, result, "Flag OFF ist ein reiner Passthrough (byte-neutral)")
    }

    @Test
    fun `Flag ON ohne SpeakerProfileStore-Bean - unveraendert (kein Crash, HOSHI_SPEAKER_ENROLL_ENABLED=false)`() {
        val resolver = SpeakerDisplayNameResolver(enabled = true, storeProvider = SpeakerDisplayNameResolver.providerOf(null))
        val context = SpeakerContext(speakerId = "andi")

        assertSame(context, resolver.resolve(context))
    }

    @Test
    fun `Flag ON + enrolltes Profil + Default-displayName - loest gross geschrieben auf`(@TempDir dir: Path) {
        val store = storeWith(dir, "andi", "cindy")
        val resolver = SpeakerDisplayNameResolver(enabled = true, storeProvider = SpeakerDisplayNameResolver.providerOf(store))
        val context = SpeakerContext(speakerId = "andi", score = 0.9)

        val result = resolver.resolve(context)

        assertEquals("Andi", result?.displayName)
        assertEquals("andi", result?.speakerId, "speakerId bleibt unangetastet")
        assertEquals(0.9, result?.score, "score bleibt unangetastet")
    }

    @Test
    fun `Flag ON + unbekannte speakerId - unveraendert, kein Raten`(@TempDir dir: Path) {
        val store = storeWith(dir, "andi")
        val resolver = SpeakerDisplayNameResolver(enabled = true, storeProvider = SpeakerDisplayNameResolver.providerOf(store))
        val context = SpeakerContext(speakerId = "gast")

        assertSame(context, resolver.resolve(context))
    }

    @Test
    fun `Flag ON + bereits gesetzter displayName (Voice-WS-Rand) - wird NICHT ueberschrieben`(@TempDir dir: Path) {
        val store = storeWith(dir, "andi")
        val resolver = SpeakerDisplayNameResolver(enabled = true, storeProvider = SpeakerDisplayNameResolver.providerOf(store))
        val context = SpeakerContext(speakerId = "andi", displayName = "Schon Gesetzt")

        assertSame(context, resolver.resolve(context))
    }
}
