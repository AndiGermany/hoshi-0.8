package de.hoshi.core.pipeline

import java.util.concurrent.ConcurrentHashMap

/**
 * **LastAreaPort** — ein winziges, pro-Sprecher gehaltenes Gedächtnis der ZULETZT
 * von DIESEM Sprecher angesteuerten HA-`area_id`. Speist die deterministische
 * Anaphern-Auflösung im [TurnOrchestrator]: ein Licht-/Klima-Befehl OHNE genannten
 * Raum („schalt das Licht wieder aus") fällt auf die zuletzt bewusst geschaltete
 * Area dieses Sprechers zurück (statt Brain-Deflection), wenn — und NUR wenn — es
 * eine solche Historie gibt.
 *
 * Bewusst pro [speakerId] (NICHT chatId) — konsistent zu den anderen Gedächtnis-
 * Nähten (Entity/Episodic) und Identity-isoliert: kein Raten über Sprecher hinweg.
 *
 * [NONE] ist der verhaltens-neutrale Default (merkt nie, erinnert nie) ⇒ der
 * speakerId-lose / nicht-verdrahtete Pfad bleibt byte-identisch.
 */
interface LastAreaPort {
    /** Die zuletzt von [speakerId] angesteuerte `area_id`, oder `null` (keine Historie). */
    fun lastArea(speakerId: String): String?

    /** Merkt [areaId] als die zuletzt von [speakerId] angesteuerte Area. */
    fun remember(speakerId: String, areaId: String)

    companion object {
        /** Default: merkt nie, erinnert nie ⇒ kein Last-Area-Fallback (Verhalten unverändert). */
        val NONE: LastAreaPort = object : LastAreaPort {
            override fun lastArea(speakerId: String): String? = null
            override fun remember(speakerId: String, areaId: String) {}
        }

        /**
         * Gast-/anonyme/fehlende id ⇒ KEIN Last-Area (kein Recall, kein Store, kein
         * Fallback). Hält den speakerId-losen Pfad byte-identisch und vermeidet, dass
         * sich über die Sammel-id „unknown"/„gast" verschiedene Sprecher mischen.
         * Spiegelt bewusst die Gast-Logik der Memory-Adapter (ohne Modul-Kopplung).
         */
        fun isAnonymous(speakerId: String?): Boolean =
            speakerId == null || speakerId.isBlank() || speakerId == "unknown" || speakerId == "gast"
    }
}

/**
 * In-Memory-Impl: `ConcurrentHashMap<speakerId, areaId>`. Pure, framework-frei,
 * thread-safe. Anonyme/Gast-ids werden NICHT gemerkt und liefern keinen Recall —
 * der Fallback gilt nur für echte, identifizierte Sprecher.
 */
class InMemoryLastAreaStore : LastAreaPort {
    private val bySpeaker = ConcurrentHashMap<String, String>()

    override fun lastArea(speakerId: String): String? =
        if (LastAreaPort.isAnonymous(speakerId)) null else bySpeaker[speakerId]

    override fun remember(speakerId: String, areaId: String) {
        if (LastAreaPort.isAnonymous(speakerId) || areaId.isBlank()) return
        bySpeaker[speakerId] = areaId
    }
}
