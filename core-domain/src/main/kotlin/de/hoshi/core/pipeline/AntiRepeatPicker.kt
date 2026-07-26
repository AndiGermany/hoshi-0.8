package de.hoshi.core.pipeline

import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * **Anti-Repeat-Ring** — EINE wiederverwendbare Zufalls-Auswahl mit
 * Wiederholungs-Sperre je Slot, extrahiert aus [ResponseFormatter] (Andi-
 * Auftrag 2026-07-26, Nachtrag zur Sprüche-Überarbeitung: „Bekommen wir die
 * Sätze auch gestreut? Nicht immer die gleiche Antwort — alles soll natürlich
 * klingen, nur nicht statisch").
 *
 * Schließt die letzten [depth] gewählten Indizes je `slot` aus, damit derselbe
 * Pool nicht zwei-/dreimal hintereinander dieselbe Variante liefert — exakt
 * der Mechanismus, den [ResponseFormatter] für die Smart-Home-Acks UND die
 * Cloud-Consent-Pools (`cloudConsentAccept` & Co.) schon vorher benutzte.
 *
 * **EIN Algorithmus, mehrere Ring-Instanzen — kein zweites Zufalls-System:**
 * [ResponseFormatter] hält EINE Instanz (Spring-Singleton, s.
 * `PipelineConfig.responseFormatter`, ⇒ Prozess-weiter Ring über die ganze
 * Laufzeit). [FactCoverageGate] und [TurnOrchestrator] halten je EINE EIGENE
 * companion-gebundene Instanz (companion object ⇒ ebenfalls JVM-Singleton,
 * dieselbe Lebensdauer-Semantik) — DIESELBE Ring-Logik, nur getrennte
 * Zustände je Aufrufer (unabhängige Pools brauchen keinen gemeinsamen Ring).
 */
class AntiRepeatPicker(private val depth: Int = DEFAULT_DEPTH) {

    private val recentIndices = ConcurrentHashMap<String, ArrayDeque<Int>>()

    /**
     * Wählt einen Pool-Eintrag, der **nicht** unter den letzten [depth]
     * gewählten Indizes für [slot] ist. Bei Pool-Größen ≤ [depth] degradiert
     * die Logik auf „nicht direkt der Letzte" (sonst läuft der Ring leer).
     * Leerer Pool ⇒ `""` (never-silent bleibt Sache des Aufrufers), Pool-
     * Größe 1 ⇒ immer `pool[0]` (kein Ring nötig, deterministisch).
     */
    fun pick(slot: String, pool: List<String>): String {
        if (pool.isEmpty()) return ""
        if (pool.size == 1) return pool[0]

        val ring = recentIndices.getOrPut(slot) { ArrayDeque(depth) }
        val effectiveDepth = minOf(depth, pool.size - 1)
        val excluded = ring.toSet()

        var idx = Random.nextInt(pool.size)
        var tries = 0
        while (idx in excluded && tries < pool.size * 2) {
            idx = Random.nextInt(pool.size)
            tries++
        }
        if (idx in excluded) {
            idx = (0 until pool.size).firstOrNull { it !in excluded } ?: 0
        }

        synchronized(ring) {
            ring.addLast(idx)
            while (ring.size > effectiveDepth) ring.removeFirst()
        }
        return pool[idx]
    }

    companion object {
        /** Anti-Repeat-Tiefe — die letzten N Wahl-Indizes werden ausgeschlossen. */
        const val DEFAULT_DEPTH = 3
    }
}
