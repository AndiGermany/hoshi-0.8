package de.hoshi.web

import com.fasterxml.jackson.annotation.JsonInclude
import de.hoshi.core.port.RingingItem
import de.hoshi.core.port.RingingItemPort
import de.hoshi.core.port.ScheduledItemPort
import de.hoshi.core.port.ScheduledKind
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * **ScheduledItemFireService** — der Fire-Service der Wecker-Lane: pollt den
 * [ScheduledItemPort] und verschiebt faellige Items in den [FiredItemsStore], wo das FE
 * sie via `GET /api/v1/scheduled/fired` abholt und LOKAL klingelt.
 *
 * **Ring-1-Fix (2026-07-03, „der Timer hat heute nicht geklappt"):** frueher war der
 * fired-GET consume-once (drain) — der ERSTE pollende Tab (seit dem Occlusion-Fix
 * pollen auch verdeckte!) schnappte den Event weg, alle anderen sahen nie etwas.
 * Jetzt ist der GET **idempotent**: er liefert ALLE unbestaetigten gefeuerten Items,
 * jeder Tab sieht sie; erst ein explizites `POST /api/v1/scheduled/fired/{id}/ack`
 * raeumt sie weg ([FiredItemsStore.ack]).
 *
 * Mechanik:
 *  - Eigener single-thread Daemon-Scheduler, Poll alle [pollIntervalMs] (~1s).
 *  - Faellig = `dueAtEpochMs <= clock.millis()` (die [Clock] ist injizierbar — Tests
 *    treiben [pollOnce] direkt mit fixer Uhr, ganz ohne Scheduler).
 *  - Faellig ⇒ ERST [ScheduledItemPort.cancel] (entfernt aus dem Store; bei der
 *    file-backed Impl persist-then-commit, also kein Doppel-Klingeln nach Restart),
 *    DANN in den Fired-Store. Ein `cancel == false` heisst: schon weg (Race mit einem
 *    parallelen Cancel) ⇒ nicht feuern.
 *
 * **Ueberfaellig-Politik v2 (ehrlich statt still):** Items, die beim Poll mehr als
 * [firedLateAfterMs] (5 min) UEBER-faellig sind (erster Poll nach Downtime: Backend
 * war aus, Rechner schlief), werden NICHT mehr still verworfen, sondern mit
 * `missed=true` gefeuert — das FE sagt dann ehrlich „Timer war um HH:MM faellig —
 * hab dich nicht erreicht" statt nachtraeglich zu klingeln. Zusaetzlich markiert der
 * [FiredItemsStore] Items, die laenger als [FiredItemsStore.MISSED_AFTER_MS] (30 min)
 * unbestaetigt liegen, beim Lesen als `missed` (abholbar bleiben sie trotzdem).
 *
 * **Flag-gated Start:** [start] tut bei `enabled=false` NICHTS (kein Thread, kein
 * Poll — byte-neutral, passend zum Default-OFF-Wiring `HOSHI_TIMER_FIRE_ENABLED`).
 * [close] faehrt den Scheduler herunter (Bean-destroyMethod-tauglich).
 *
 * **Ring-Downlink-Hook** ([onFired], PREP-wecker-am-satelliten): entkoppelter Funktions-
 * Seam, den [pollOnce] bei JEDER Feuerung ruft — No-op-Default ⇒ ohne Wiring byte-neutral.
 */
class ScheduledItemFireService(
    private val store: ScheduledItemPort,
    private val fired: FiredItemsStore,
    private val enabled: Boolean,
    private val clock: Clock = Clock.systemUTC(),
    private val pollIntervalMs: Long = 1_000,
    private val firedLateAfterMs: Long = FIRED_LATE_AFTER_MS,
    /**
     * **Fire-Hook (PREP-wecker-am-satelliten, Scheibe 2) — Funktions-Seam, Default No-op
     * ⇒ byte-neutral.** Feuert GENAU EINMAL je gefeuertem Item, direkt nachdem es in den
     * [FiredItemsStore] gelegt wurde (derselbe Moment, nicht später) — der Aufrufer
     * (Ring-Downlink-Service in [PipelineConfig]) entscheidet selbst, ob/wie er darauf
     * reagiert (Flag-gated, `originSatelliteId=null` ⇒ er tut ohnehin nichts). Dieser
     * Service selbst kennt WEDER [de.hoshi.core.port.DeviceDownlinkPort] NOCH irgendeine
     * Retry-Logik — reine Entkopplung (Muster [AudioWebSocketHandler.onDeviceConnected]).
     */
    private val onFired: (id: String, label: String?, originSatelliteId: String?) -> Unit = { _, _, _ -> },
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var scheduler: ScheduledExecutorService? = null

    /** `true` sobald der Poll-Scheduler laeuft — Test-Naht fuer den Flag-OFF-Beweis. */
    val isRunning: Boolean
        get() = scheduler != null

    /**
     * Startet den Poll-Scheduler — NUR wenn [enabled]; sonst No-op (Flag-OFF =
     * kein Thread, kein Effekt). Idempotent: ein zweiter [start] tut nichts.
     */
    @Synchronized
    fun start() {
        if (!enabled) {
            log.info("[timer-fire] HOSHI_TIMER_FIRE_ENABLED=false - Fire-Service bleibt aus (kein Poll-Thread).")
            return
        }
        if (scheduler != null) return
        val exec = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "hoshi-timer-fire").apply { isDaemon = true }
        }
        // Ein werfender Poll wuerde den fixed-delay-Task fuer immer toeten — daher
        // wird JEDE Exception gefangen und nur geloggt (der naechste Poll kommt).
        exec.scheduleWithFixedDelay({
            try {
                pollOnce()
            } catch (e: Exception) {
                log.warn("[timer-fire] Poll fehlgeschlagen (naechster kommt): {}", e.toString())
            }
        }, 0, pollIntervalMs, TimeUnit.MILLISECONDS)
        scheduler = exec
        log.info("[timer-fire] Fire-Service laeuft (Poll alle {} ms).", pollIntervalMs)
    }

    /**
     * EIN Poll-Durchlauf (die Test-Naht — Tests rufen das direkt mit fixer [Clock]):
     * faellige Items aus dem Store entfernen und in den [FiredItemsStore] feuern.
     * Mehr als [firedLateAfterMs] ueber-faellig (Downtime) ⇒ `missed=true` statt
     * still verwerfen (Ueberfaellig-Politik v2, siehe Klassen-KDoc).
     */
    fun pollOnce() {
        val now = clock.millis()
        for (item in store.query().filter { it.dueAtEpochMs <= now }) {
            // Erst aus dem Store (persistent) entfernen; false = schon weg (Race) => nicht feuern.
            if (!store.cancel(item.id)) continue
            val overdueMs = now - item.dueAtEpochMs
            val missed = overdueMs > firedLateAfterMs
            fired.add(
                FiredItem(
                    id = item.id,
                    kind = item.kind,
                    label = item.label,
                    dueAtEpochMs = item.dueAtEpochMs,
                    firedAtEpochMs = now,
                    missed = missed,
                    // Wecker-Ursprung durchreichen (die Gerät-/Session-Id, die den Wecker
                    // stellte) — null bleibt null (alt-Client) ⇒ FE klingelt überall.
                    origin = item.origin,
                ),
            )
            // PREP-wecker-am-satelliten (Scheibe 2): GENAU EINMAL je Feuerung, direkt hier —
            // der No-op-Default hält diesen Aufruf ohne Wiring folgenlos (byte-neutral).
            onFired(item.id, item.label, item.originSatelliteId)
            if (missed) {
                log.warn(
                    "[timer-fire] {} '{}' (id={}) war {} ms ueber-faellig (> {} ms, Downtime) - als VERPASST gefeuert (missed=true).",
                    item.kind, item.label ?: "", item.id, overdueMs, firedLateAfterMs,
                )
            } else {
                log.info("[timer-fire] {} (id={}) gefeuert - {} ms nach Faelligkeit.", item.kind, item.id, overdueMs)
            }
        }
    }

    /** Faehrt den Poll-Scheduler herunter (Bean-`destroyMethod`). Idempotent. */
    @Synchronized
    override fun close() {
        scheduler?.shutdownNow()
        scheduler = null
    }

    companion object {
        /** Gnadenfrist: bis 5 min Verspaetung klingelt es normal, darueber `missed=true`. */
        const val FIRED_LATE_AFTER_MS: Long = 5 * 60 * 1_000
    }
}

/**
 * Ein GEFEUERTES Item — das Wire-Format der Klingel-Naht
 * (`GET /api/v1/scheduled/fired`):
 * `{id, kind, label?, dueAtEpochMs, firedAtEpochMs, missed, origin?}`.
 * `label=null`/`origin=null` werden im JSON weggelassen (NON_NULL), exakt der
 * `label?`/`origin?`-Contract.
 * `missed=true` heisst ehrlich: dieses Klingeln hat den Menschen (mutmasslich)
 * nicht erreicht — entweder erst nach Downtime gefeuert (> 5 min ueber-faellig)
 * oder seit > 30 min unbestaetigt (siehe [FiredItemsStore]).
 * `origin` (additiv) ist die Gerät-/Session-Id, die den Wecker STELLTE (aus dem
 * [de.hoshi.core.port.ScheduledItem.origin]) — das FE trifft damit sein
 * Ursprungs-/Eskalations-Urteil; `null` (alt-Client) ⇒ FE klingelt überall.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class FiredItem(
    val id: String,
    val kind: ScheduledKind,
    val label: String? = null,
    val dueAtEpochMs: Long,
    val firedAtEpochMs: Long,
    val missed: Boolean = false,
    val origin: String? = null,
)

/**
 * **FiredItemsStore** — haelt gefeuerte, noch UNBESTAETIGTE Items, bis jemand sie
 * per Ack quittiert (die Nachfolge-Naht des frueheren consume-once-Buffers).
 *
 * Vertrag:
 *  - [add] legt ein gefeuertes Item ab (id = Schluessel, Re-Fire ueberschreibt);
 *    mehr als [CAPACITY] unbestaetigte ⇒ das AELTESTE (kleinstes `firedAtEpochMs`)
 *    faellt raus — die Obergrenze gegen ewiges Ansammeln.
 *  - [pending] ist **idempotent** (liest, konsumiert NICHTS): alle unbestaetigten
 *    Items, aelteste zuerst; laenger als [MISSED_AFTER_MS] unbestaetigt ⇒ beim
 *    Lesen `missed=true` (abholbar bleibt es — ehrlich statt still verwerfen).
 *  - [ack] quittiert genau ein Item — erst DANN ist es weg (fuer ALLE Tabs).
 *
 * Zwei Impls: [InMemoryFiredItemsStore] (fluechtig) und [FileBackedScheduledItemStore]
 * (Restart-fest, gleicher JSON-Store wie die aktiven Items — [PipelineConfig] waehlt).
 */
interface FiredItemsStore {

    /** Legt ein gefeuertes Item ab; bei vollem Ring faellt das AELTESTE raus. */
    fun add(item: FiredItem)

    /**
     * Alle unbestaetigten Items (aelteste Feuerung zuerst) — idempotent, konsumiert
     * NICHTS. Laenger als [MISSED_AFTER_MS] unbestaetigt ⇒ `missed=true` im Ergebnis.
     */
    fun pending(nowMs: Long): List<FiredItem>

    /** Quittiert genau ein Item (dann weg, fuer alle Tabs); unbekannte id ⇒ `false`. */
    fun ack(id: String): Boolean

    /** Aktuelle Anzahl unbestaetigter Items (Test-/Diagnose-Naht, konsumiert nichts). */
    fun size(): Int

    companion object {
        /** Obergrenze unbestaetigter Klingel-Ereignisse — das aelteste faellt raus. */
        const val CAPACITY: Int = 20

        /** Nach 30 min unbestaetigt gilt ein Klingeln als VERPASST (`missed=true`). */
        const val MISSED_AFTER_MS: Long = 30 * 60 * 1_000

        /** Deterministische Reihenfolge: aelteste Feuerung zuerst (Tie: Faelligkeit, id). */
        val FIRED_ORDER: Comparator<FiredItem> =
            compareBy({ it.firedAtEpochMs }, { it.dueAtEpochMs }, { it.id })

        /**
         * Gemeinsame Lese-Politik beider Impls: sortiert nach [FIRED_ORDER] und markiert
         * alles, was laenger als [MISSED_AFTER_MS] unbestaetigt liegt, als `missed`.
         */
        fun withReadTimeMissed(items: Collection<FiredItem>, nowMs: Long): List<FiredItem> =
            items.sortedWith(FIRED_ORDER).map {
                if (!it.missed && nowMs - it.firedAtEpochMs > MISSED_AFTER_MS) it.copy(missed = true) else it
            }
    }
}

/**
 * In-Memory-[FiredItemsStore] — fuer das Wiring OHNE Datei-Persistenz (und Tests):
 * unbestaetigte Klingel-Ereignisse ueberleben hier KEINEN Restart (ehrlich dokumentiert;
 * die Restart-feste Variante ist der [FileBackedScheduledItemStore]).
 *
 * Thread-Sicherheit: `@Synchronized` auf allen Zugriffen (Fire-Thread schreibt,
 * Request-Threads lesen/quittieren).
 */
class InMemoryFiredItemsStore : FiredItemsStore {

    private val items = LinkedHashMap<String, FiredItem>()

    @Synchronized
    override fun add(item: FiredItem) {
        items[item.id] = item
        while (items.size > FiredItemsStore.CAPACITY) {
            val oldest = items.values.minWithOrNull(FiredItemsStore.FIRED_ORDER) ?: break
            items.remove(oldest.id)
        }
    }

    @Synchronized
    override fun pending(nowMs: Long): List<FiredItem> =
        FiredItemsStore.withReadTimeMissed(items.values, nowMs)

    @Synchronized
    override fun ack(id: String): Boolean = items.remove(id) != null

    @Synchronized
    override fun size(): Int = items.size
}

/**
 * **FiredItemsRingingAdapter** — macht einen [FiredItemsStore] als
 * [de.hoshi.core.port.RingingItemPort] für den [de.hoshi.core.pipeline.TimerFastpath]
 * sichtbar (`core-domain` darf nicht auf `web-inbound`s [FiredItemsStore] zeigen —
 * diese Naht schließt die Lücke additiv, ohne die Schichtgrenze zu verletzen).
 *
 * Schließt den Live-Bug Andi 2026-07-15: „Stoppe den Timer" fand ein bereits
 * gefeuertes (klingelndes) Item bisher NICHT, weil es den [ScheduledItemPort] schon
 * verlassen hatte — nur der [FiredItemsStore] wusste noch davon. [ringing] liefert
 * dieselben Items wie [FiredItemsStore.pending] (nur ohne Restzeit-Semantik gemappt),
 * [stopRinging] ist ein reiner Alias auf [FiredItemsStore.ack] — EINE Wahrheit, kein
 * zweiter Klingel-Zustand.
 */
class FiredItemsRingingAdapter(
    private val fired: FiredItemsStore,
    private val clock: Clock = Clock.systemUTC(),
) : RingingItemPort {

    override fun ringing(): List<RingingItem> =
        fired.pending(clock.millis()).map { RingingItem(id = it.id, kind = it.kind, label = it.label) }

    override fun stopRinging(id: String): Boolean = fired.ack(id)
}
