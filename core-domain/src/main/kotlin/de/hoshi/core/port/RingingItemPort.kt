package de.hoshi.core.port

/**
 * **RingingItemPort** — die hexagonale Naht, über die der
 * [de.hoshi.core.pipeline.TimerFastpath] ein bereits GEFEUERTES, gerade klingelndes
 * Item sieht und per Stopp-Befehl beenden (= quittieren) kann.
 *
 * **Wurzel des Live-Bugs (Andi 2026-07-15):** ein fälliges Item verlässt beim Feuern
 * den [ScheduledItemPort] (der Fire-Service — `ScheduledItemFireService.pollOnce` in
 * `web-inbound` — cancelt es dort UND legt es in einen separaten „gefeuert, noch
 * unbestätigt"-Speicher, den das FE per Poll abholt und LOKAL klingelt, bis eine
 * Ack-Quittung kommt). [TimerFastpath.handleCancel] kannte bis dahin NUR den
 * [ScheduledItemPort] — ein klingelnder Wecker war für „stoppe den Timer" unsichtbar:
 * die Antwort war entweder die ehrliche, aber FALSCHE Leer-Phrase („Da läuft gerade
 * kein Timer.") oder traf (bei weiteren geplanten Items) den falschen Timer, während
 * der eigentliche Klingelton unbeeindruckt weiterlief.
 *
 * Diese Naht schließt die Lücke additiv: [ringing] macht die klingelnden Items für
 * CANCEL sichtbar, [stopRinging] quittiert eines davon (Ack) — ohne dass der
 * `core-domain`-Layer je auf `web-inbound`s `FiredItemsStore` zeigen müsste (der
 * Adapter dort implementiert diese Schnittstelle).
 *
 * [NONE] ist der verhaltens-neutrale Default (nie klingelnd, nichts zu stoppen) —
 * passend für Aufrufer ohne Klingel-Naht (z.B. [de.hoshi.core.pipeline.TimerFastpath.DISABLED],
 * ältere Tests): additiv/rückwärts-kompatibel, kein Effekt, byte-neutral.
 */
interface RingingItemPort {
    /** Alle aktuell klingelnden (gefeuerten, noch unbestätigten) Items. */
    fun ringing(): List<RingingItem>

    /** Beendet das Klingeln EINES Items (= Ack); `true` wenn eines beendet wurde. */
    fun stopRinging(id: String): Boolean

    companion object {
        /** Default: nie klingelnd, nichts zu stoppen (kein Effekt). */
        val NONE: RingingItemPort = object : RingingItemPort {
            override fun ringing(): List<RingingItem> = emptyList()
            override fun stopRinging(id: String): Boolean = false
        }
    }
}

/**
 * Ein klingelndes Item — reine Anzeige-/Stopp-Daten für CANCEL. Bewusst OHNE
 * `dueAtEpochMs`: ein bereits gefeuertes Item kennt keine „Restzeit" mehr, nur noch
 * einen Klingel-Zustand.
 *
 * @property id    dieselbe id wie im ursprünglichen [ScheduledItem] (Ack-Schlüssel).
 * @property kind  Timer/Wecker/Erinnerung (steuert Nomen in der Quittung).
 * @property label optionaler Sprech-/Anzeige-Text, identisch zum ursprünglichen Item.
 */
data class RingingItem(
    val id: String,
    val kind: ScheduledKind,
    val label: String? = null,
)
