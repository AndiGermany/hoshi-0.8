package de.hoshi.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * **BrainAutoSwitchServiceTest** — die Kern-Entscheidungslogik des
 * automatischen Brain-Modellwechsels (Andi-Auftrag „12B für Chat, e4b für
 * Voice", 2026-07-26): OFF ⇒ null Calls, Voice-Start async (blockiert nicht),
 * Chat-Turn wartet ab, Hysterese verhindert Pumpen, ein fehlgeschlagener/
 * fehlerhafter Wechsel bricht NIE (Never-Silent), schon geladen ⇒ no-op.
 */
class BrainAutoSwitchServiceTest {

    private class FakeHealthProbe(private val model: String?) : BrainHealthProbe {
        val calls = AtomicInteger(0)
        override fun check(): Mono<BrainHealthSnapshot> {
            calls.incrementAndGet()
            return Mono.just(BrainHealthSnapshot(status = "ok", model = model))
        }
    }

    private class FailingHealthProbe : BrainHealthProbe {
        override fun check(): Mono<BrainHealthSnapshot> = Mono.error(IllegalStateException("boom"))
    }

    private class FakeSwitchPort(
        private val result: BrainSwitchResult = BrainSwitchResult.Accepted,
        private val delay: Duration = Duration.ZERO,
    ) : BrainSwitchModelPort {
        val calledRepos = CopyOnWriteArrayList<String>()
        override fun switchModel(repo: String): Mono<BrainSwitchResult> {
            calledRepos.add(repo)
            val mono = Mono.just(result)
            return if (delay.isZero) mono else mono.delayElement(delay, Schedulers.parallel())
        }
    }

    /** Uhr, die sich nur per [advance] bewegt — deterministische Hysterese-Tests. */
    private class MutableClock(private var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = now
        fun advance(d: Duration) {
            now = now.plus(d)
        }
    }

    private fun store(dir: Path, enabled: Boolean): JsonFileBrainAutoSwitchStore {
        val s = JsonFileBrainAutoSwitchStore(dir.resolve("brain-auto-switch.json"))
        if (enabled) s.setEnabled(true)
        return s
    }

    // ── OFF ⇒ byte-neutral ───────────────────────────────────────────────────

    @Test
    fun `Setting AUS - onVoiceSessionStart ruft weder Health noch Switch`(@TempDir dir: Path) {
        val health = FakeHealthProbe("mlx-community/gemma-4-e2b-it-4bit")
        val switchPort = FakeSwitchPort()
        val service = BrainAutoSwitchService(store(dir, enabled = false), switchPort, health)

        service.onVoiceSessionStart()
        Thread.sleep(100) // async — genug Zeit, damit ein (fälschlicher) Call längst gefeuert hätte

        assertEquals(0, health.calls.get())
        assertTrue(switchPort.calledRepos.isEmpty())
    }

    @Test
    fun `Setting AUS - ensureChatModel ruft weder Health noch Switch, resolved sofort`(@TempDir dir: Path) {
        val health = FakeHealthProbe("mlx-community/gemma-4-e2b-it-4bit")
        val switchPort = FakeSwitchPort()
        val service = BrainAutoSwitchService(store(dir, enabled = false), switchPort, health)

        val result = service.ensureChatModel().block(Duration.ofSeconds(1))

        assertEquals(Unit, result)
        assertEquals(0, health.calls.get())
        assertTrue(switchPort.calledRepos.isEmpty())
    }

    // ── Schon das Ziel-Modell ⇒ no-op (kein Switch-Call) ────────────────────

    @Test
    fun `Ziel-Modell bereits geladen - kein Switch-Call`(@TempDir dir: Path) {
        val health = FakeHealthProbe(BrainModelCatalog.AUTO_SWITCH_CHAT_REPO)
        val switchPort = FakeSwitchPort()
        val service = BrainAutoSwitchService(store(dir, enabled = true), switchPort, health)

        service.ensureChatModel().block(Duration.ofSeconds(1))

        assertTrue(switchPort.calledRepos.isEmpty(), "das Ziel läuft schon ⇒ kein unnötiger Sidecar-Call")
    }

    // ── Voice-Start: asynchron, blockiert den Aufrufer nicht ────────────────

    @Test
    fun `Voice-Start - asynchron auf das Voice-Modell, Aufrufer blockiert nicht`(@TempDir dir: Path) {
        val health = FakeHealthProbe("mlx-community/gemma-4-12B-it-4bit") // NICHT das Voice-Modell
        val done = CountDownLatch(1)
        val switchPort = object : BrainSwitchModelPort {
            val calledRepos = CopyOnWriteArrayList<String>()
            override fun switchModel(repo: String): Mono<BrainSwitchResult> {
                calledRepos.add(repo)
                return Mono.fromCallable<BrainSwitchResult> {
                    Thread.sleep(200)
                    BrainSwitchResult.Accepted
                }.subscribeOn(Schedulers.boundedElastic()).doFinally { done.countDown() }
            }
        }
        val service = BrainAutoSwitchService(store(dir, enabled = true), switchPort, health)

        val t0 = System.nanoTime()
        service.onVoiceSessionStart()
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000

        assertTrue(elapsedMs < 100, "onVoiceSessionStart darf den WS-start-Frame-Handler nicht blockieren (war ${elapsedMs}ms)")
        assertTrue(done.await(2, TimeUnit.SECONDS), "der Switch muss trotzdem im Hintergrund laufen")
        assertEquals(listOf(BrainModelCatalog.AUTO_SWITCH_VOICE_REPO), switchPort.calledRepos)
    }

    // ── Text-Turn: wartet den Wechsel ab ─────────────────────────────────────

    @Test
    fun `Chat-Turn - ensureChatModel wartet den Wechsel ab, bevor der Turn weiterlaeuft`(@TempDir dir: Path) {
        val health = FakeHealthProbe("mlx-community/gemma-4-e4b-it-4bit") // NICHT das Chat-Modell
        val switchPort = FakeSwitchPort(delay = Duration.ofMillis(150))
        val service = BrainAutoSwitchService(store(dir, enabled = true), switchPort, health)

        val t0 = System.nanoTime()
        service.ensureChatModel().block(Duration.ofSeconds(2))
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000

        assertTrue(elapsedMs >= 140, "ensureChatModel muss den Wechsel ABWARTEN (war nur ${elapsedMs}ms)")
        assertEquals(listOf(BrainModelCatalog.AUTO_SWITCH_CHAT_REPO), switchPort.calledRepos)
    }

    // ── Never-Silent: ein fehlgeschlagener/fehlerhafter Wechsel bricht nie ──

    @Test
    fun `Switch Unavailable - ensureChatModel schliesst trotzdem sauber ab (Never-Silent)`(@TempDir dir: Path) {
        val health = FakeHealthProbe("mlx-community/gemma-4-e4b-it-4bit")
        val switchPort = FakeSwitchPort(result = BrainSwitchResult.Unavailable("404"))
        val service = BrainAutoSwitchService(store(dir, enabled = true), switchPort, health)

        val result = service.ensureChatModel().block(Duration.ofSeconds(2))

        assertEquals(Unit, result, "ein abgelehnter Wechsel darf den Turn NIE mit einem Fehler beenden")
    }

    @Test
    fun `Health-Probe-Fehler - ensureChatModel wirft nie, kein Switch-Call`(@TempDir dir: Path) {
        val switchPort = FakeSwitchPort()
        val service = BrainAutoSwitchService(store(dir, enabled = true), switchPort, FailingHealthProbe())

        val result = service.ensureChatModel().block(Duration.ofSeconds(2))

        assertEquals(Unit, result)
        assertTrue(switchPort.calledRepos.isEmpty())
    }

    // ── Hysterese: nie mehr als ein Wechsel-Versuch pro Fenster ─────────────

    @Test
    fun `Hysterese - zwei Entscheidungen kurz hintereinander loesen nur EINEN Switch-Call aus`(@TempDir dir: Path) {
        val health = FakeHealthProbe("mlx-community/gemma-4-e4b-it-4bit") // nie das Chat-Modell ⇒ immer "muesste wechseln"
        val switchPort = FakeSwitchPort()
        val clock = MutableClock(Instant.parse("2026-07-26T10:00:00Z"))
        val service = BrainAutoSwitchService(
            store(dir, enabled = true), switchPort, health,
            clock = clock, hysteresis = Duration.ofSeconds(30),
        )

        service.ensureChatModel().block(Duration.ofSeconds(1))
        clock.advance(Duration.ofSeconds(5)) // deutlich innerhalb des 30s-Fensters
        service.ensureChatModel().block(Duration.ofSeconds(1))

        assertEquals(1, switchPort.calledRepos.size, "der zweite Versuch muss die Hysterese greifen")
    }

    @Test
    fun `Hysterese - nach Ablauf des Fensters darf wieder gewechselt werden`(@TempDir dir: Path) {
        val health = FakeHealthProbe("mlx-community/gemma-4-e4b-it-4bit")
        val switchPort = FakeSwitchPort()
        val clock = MutableClock(Instant.parse("2026-07-26T10:00:00Z"))
        val service = BrainAutoSwitchService(
            store(dir, enabled = true), switchPort, health,
            clock = clock, hysteresis = Duration.ofSeconds(30),
        )

        service.ensureChatModel().block(Duration.ofSeconds(1))
        clock.advance(Duration.ofSeconds(31)) // Fenster vorbei
        service.ensureChatModel().block(Duration.ofSeconds(1))

        assertEquals(2, switchPort.calledRepos.size, "nach Fensterablauf darf erneut versucht werden")
    }

    @Test
    fun `Katalog - Voice und Chat sind unterschiedliche Repos aus der Whitelist`() {
        assertEquals("mlx-community/gemma-4-e4b-it-4bit", BrainModelCatalog.AUTO_SWITCH_VOICE_REPO)
        assertEquals("mlx-community/gemma-4-12B-it-4bit", BrainModelCatalog.AUTO_SWITCH_CHAT_REPO)
    }
}
