package de.hoshi.core.port

import java.util.concurrent.ConcurrentHashMap

/**
 * **ScheduledItemPort** — die hexagonale Naht für deterministische Timer/Wecker/
 * Erinnerungen (TimerFastpath, Essenz aus Hoshi 0.5 `ScheduledItemStore`).
 *
 * Ein [ScheduledItem] trägt eine **absolute** Fälligkeit ([ScheduledItem.dueAtEpochMs],
 * Epoch-Millis) — NIE eine Restdauer: ein „10-Minuten-Timer" wird beim Anlegen gegen
 * die injizierte Uhr in `now + 600_000` aufgelöst (die `now()`-Quelle lebt im
 * [de.hoshi.core.pipeline.TimerFastpath] als injizierter `java.time.Clock`, NICHT
 * hier). So ist der Store selbst rein/uhrfrei: er hält nur absolute Zeitpunkte.
 *
 * **In-Mem reicht für Welle 1** ([InMemoryScheduledItemStore]): zuverlässige Anlage +
 * Query + Cancel. Persistenz (sqlite/json) + das Klingeln/die fällige Ansage (Audio-
 * Naht) kommen später — der Port ist die stabile Naht dafür.
 *
 * [NONE] ist der verhaltens-neutrale Default (speichert nie, liefert nie) — passend
 * zum Default-OFF-Flag: ohne `HOSHI_TIMER_ENABLED` wird der Pfad nie betreten.
 */
interface ScheduledItemPort {
    /** Legt ein Item an (Schlüssel = [ScheduledItem.id]) und gibt es zurück. */
    fun set(item: ScheduledItem): ScheduledItem

    /** Alle aktiven Items (Snapshot), aufsteigend nach Fälligkeit. */
    fun query(): List<ScheduledItem>

    /** Storniert genau ein Item; `true`, wenn eines entfernt wurde. */
    fun cancel(id: String): Boolean

    /** Storniert ALLE aktiven Items; liefert die Anzahl der entfernten. */
    fun cancelAll(): Int

    companion object {
        /** Default: speichert nie, liefert nie ⇒ kein Timer-Effekt (Flag-OFF-passend). */
        val NONE: ScheduledItemPort = object : ScheduledItemPort {
            override fun set(item: ScheduledItem): ScheduledItem = item
            override fun query(): List<ScheduledItem> = emptyList()
            override fun cancel(id: String): Boolean = false
            override fun cancelAll(): Int = 0
        }
    }
}

/**
 * Ein geplantes Item — bewusst reine Daten (Spring-frei, uhrfrei).
 *
 * @property id        eindeutige id (z.B. UUID), Schlüssel im Store.
 * @property kind      Timer/Wecker/Erinnerung (steuert die warme Quittung).
 * @property dueAtEpochMs **absoluter** Fälligkeitszeitpunkt (Epoch-Millis), nie Restdauer.
 * @property label     optionaler Sprech-/Anzeige-Text (z.B. „Pizza" bei einer Erinnerung).
 * @property origin    optionale Ursprungs-Id (Gerät-/Session-Id, die den Wecker STELLTE) —
 *                     wandert beim Feuern in den `FiredItem` und lässt das FE später
 *                     entscheiden, WO geklingelt wird. Additiv/rückwärts-kompatibel:
 *                     `null` (Default, alte Clients ohne `deviceId`) heißt „kein
 *                     Ursprung bekannt" ⇒ das FE klingelt überall (sicherer Default).
 * @property originSatelliteId optionale Ursprungs-`satelliteId` (Wecker-am-Satelliten-Klingeln,
 *                     PREP-wecker-am-satelliten) — GETRENNT von [origin]: [origin] ist die
 *                     FE-`deviceId` (Ursprungs-Bimmeln/Eskalation im Browser, Commit `2dec25d`),
 *                     [originSatelliteId] dagegen NUR gesetzt, wenn der Timer über `/ws/audio`
 *                     (den echten Voice-PE-Satelliten) gestellt wurde — die `satelliteId` aus
 *                     dessen `start`-Frame (Muster [de.hoshi.core.dto.ChatRequest.deviceId],
 *                     Nachtmodus-`satelliteId`). Sie adressiert später den server-initiierten
 *                     WS-Downlink ([de.hoshi.core.port.DeviceDownlinkPort]) beim Feuern — der
 *                     FE-Pfad bleibt davon unberührt. `null` (Chat/FE/alte Clients) ⇒ kein
 *                     Satelliten-Ziel bekannt ⇒ nur der FE-Pfad klingelt (byte-neutraler Default).
 */
data class ScheduledItem(
    val id: String,
    val kind: ScheduledKind,
    val dueAtEpochMs: Long,
    val label: String? = null,
    val origin: String? = null,
    val originSatelliteId: String? = null,
)

/** Art des geplanten Items. */
enum class ScheduledKind { TIMER, ALARM, REMINDER }

/**
 * Thread-sichere In-Memory-Impl (`ConcurrentHashMap<id, ScheduledItem>`). Pure,
 * framework-frei, **uhrfrei** (hält nur absolute Fälligkeiten). Cancel entfernt das
 * Item ganz (keine Status-Maschine — für Welle 1 ohne Fire-Service genügt das).
 *
 * [query] liefert einen nach Fälligkeit sortierten Snapshot, sodass die Quittung
 * deterministisch „der nächste zuerst" aufzählt.
 */
class InMemoryScheduledItemStore : ScheduledItemPort {
    private val items = ConcurrentHashMap<String, ScheduledItem>()

    override fun set(item: ScheduledItem): ScheduledItem {
        items[item.id] = item
        return item
    }

    override fun query(): List<ScheduledItem> = items.values.sortedBy { it.dueAtEpochMs }

    override fun cancel(id: String): Boolean = items.remove(id) != null

    override fun cancelAll(): Int {
        val n = items.size
        items.clear()
        return n
    }
}
