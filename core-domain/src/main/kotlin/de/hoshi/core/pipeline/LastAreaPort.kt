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
 * **When may memory speak?** (F2/Irori) — the deterministic anaphora cue that lets
 * the remembered area of [LastAreaPort] BEAT the room the turn is physically spoken
 * in ([de.hoshi.core.dto.ChatRequest.originAreaId]). Hand's rule: presence beats
 * memory, UNLESS the sentence points back ("mach das wieder aus").
 *
 * Two strengths, because the caller knows how ambiguous its sentence is:
 *  - [STRONG] words are unambiguous back-references in any sentence.
 *  - [WEAK] words are only a back-reference when the sentence names NO device at
 *    all (the classifier's clarify branch) — in "mach das Licht an" the very same
 *    "das" is a plain article and must never move the target room.
 *
 * Pure token matching (no model, no store): a false negative just means the room is
 * taken from presence or asked for; a false positive would switch a foreign room.
 */
object AnaphoraCue {
    private val TOKEN_SPLIT = Regex("[^a-zäöüß0-9]+")

    /** Unambiguous back-references (DE+EN) — count in ANY sentence. */
    private val STRONG = setOf("wieder", "nochmal", "erneut", "again")

    /** Pronoun-ish references — count ONLY where no device is named (see KDoc). */
    private val WEAK = setOf("das", "es", "dies", "auch", "that", "it", "too")

    fun present(text: String, allowWeak: Boolean): Boolean {
        if (text.isBlank()) return false
        val tokens = text.lowercase().split(TOKEN_SPLIT).filter { it.isNotBlank() }
        if (tokens.any { it in STRONG }) return true
        return allowWeak && tokens.any { it in WEAK }
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
