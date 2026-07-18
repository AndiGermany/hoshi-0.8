package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.port.ListEntry
import de.hoshi.core.port.ListPort
import de.hoshi.core.port.addWithDedupe
import de.hoshi.core.tools.ListIntent
import de.hoshi.core.tools.ToolCall
import java.time.Clock
import java.util.UUID

/**
 * **ListFastpath** — der brain-freie Vollzug eines List-[ToolCall] (`domain ==
 * "list"`): [ListIntent]-Treffer gegen den [ListPort] ausführen (ADD/READ/REMOVE)
 * und eine warme, deterministische deutsche/englische Quittung MIT Read-back des
 * Items sprechen. Ruft den Brain NIE. Exakt nach dem [TimerFastpath]-Muster
 * gebaut (Andi-JA 2026-07-08, „Listen auf die Ring-1-Karte").
 *
 * **Dedupe ist EINE geteilte Wahrheit** (Andi-Entscheidung 2026-07-08: „Milch"
 * zweimal → „2× Milch" statt Ablehnung/Duplikat) — [handleAdd] ruft
 * [de.hoshi.core.port.addWithDedupe], DIESELBE Funktion, die auch die REST-Naht
 * (`ListsController`, web-inbound) nutzt. Keine Drift zwischen Voice/Text und REST.
 *
 * **Read-back statt Formular-Ton** (Mira-/Sara-Veto „grün≠lebt"): die Quittung
 * liest IMMER aus dem frischen Store-Stand vor (`entry.text`/`items()`), NIE aus
 * der bloßen Absicht — ein STT-Garble bei Produktnamen ist so sofort hörbar
 * (PREP-Risiko 5: kein phonetischer Resolver in v1, aber das Read-back macht
 * jeden Garble sofort hörbar und trivial korrigierbar).
 *
 * **Exaktes Matching bei REMOVE** (bewusst KEIN Fuzzy-Substring wie beim
 * Timer-Label): zwei ähnliche Items, z.B. „Ei"/„Eis", dürfen sich nie
 * versehentlich treffen — konservativ, ein verpasster Treffer ist besser als
 * eine falsche Löschung persönlicher Daten.
 *
 * **v1 kennt nur EINE Liste** ([ListPort.DEFAULT_LIST_ID], Andi-Entscheidung
 * 2026-07-08) — der Port trägt `listId` bereits für später, dieser Fastpath
 * reicht ihn aber überall fest durch.
 *
 * [DISABLED] (auf [ListPort.NONE]) ist der nie-erreichte Default: ohne
 * `HOSHI_LIST_ENABLED` emittiert der Classifier keinen List-Call, der Zweig im
 * [TurnOrchestrator] ist tot ⇒ byte-neutral.
 */
class ListFastpath(
    private val store: ListPort,
    private val clock: Clock = Clock.systemDefaultZone(),
    /** Injizierbar für deterministische ids in Tests. */
    private val idGen: () -> String = { UUID.randomUUID().toString() },
) {

    /** Vollzieht den List-Call und liefert die fertige, sprechbare Quittung. */
    fun handle(call: ToolCall, language: Language): String = when (call.service) {
        ListIntent.ADD -> handleAdd(call, language)
        ListIntent.READ -> handleRead(language)
        ListIntent.REMOVE -> handleRemove(call, language)
        else -> ""
    }

    /**
     * Legt das genannte Item an ODER merged es dedupe-artig in einen bestehenden
     * Eintrag ([de.hoshi.core.port.addWithDedupe] — case-insensitiver, getrimmter
     * Text-Vergleich; die ERSTE Schreibweise gewinnt, nur `quantity` steigt).
     */
    private fun handleAdd(call: ToolCall, language: Language): String {
        val text = (call.data[ListIntent.ITEM] as? String)?.trim().orEmpty()
        if (text.isBlank()) return ""
        val saved = store.addWithDedupe(ListPort.DEFAULT_LIST_ID, text, clock.millis(), idGen)
        return receiptForAdd(saved, language)
    }

    private fun receiptForAdd(entry: ListEntry, language: Language): String {
        val en = language == Language.EN
        val label = display(entry)
        return if (en) "Got it, $label is on the list now." else "Alles klar, $label steht jetzt drauf."
    }

    /**
     * Liest die Liste vor — leer ⇒ ehrlich „leer" (NIE eine Gegenfrage,
     * Nachtwächter-Prinzip wie [TimerFastpath.handleQuery]).
     */
    private fun handleRead(language: Language): String {
        val en = language == Language.EN
        val items = store.items(ListPort.DEFAULT_LIST_ID)
        if (items.isEmpty()) return if (en) "The list is empty." else "Die Liste ist leer."
        val enumeration = items.joinToString(", ") { display(it) }
        return if (en) "On the list: $enumeration." else "Auf der Liste steht: $enumeration."
    }

    /**
     * Entfernt entweder ALLES ([ListIntent.ALL]==true, „Liste leeren") oder EIN
     * genanntes Item (case-insensitiver, EXAKTER Text-Vergleich — s. Klassen-KDoc).
     * Kein Treffer ⇒ ehrlich statt zu raten (dieselbe Ehrlichkeits-Linie wie
     * [TimerFastpath.notFoundPhrase]).
     */
    private fun handleRemove(call: ToolCall, language: Language): String {
        val en = language == Language.EN
        val all = call.data[ListIntent.ALL] as? Boolean ?: false
        if (all) {
            val n = store.clear(ListPort.DEFAULT_LIST_ID)
            return when {
                n == 0 -> if (en) "The list is already empty." else "Die Liste ist schon leer."
                en -> "Okay, cleared the list — $n item${if (n == 1) "" else "s"} gone."
                else -> "Okay, die Liste ist jetzt leer ($n gelöscht)."
            }
        }
        val text = (call.data[ListIntent.ITEM] as? String)?.trim().orEmpty()
        if (text.isBlank()) return ""
        val items = store.items(ListPort.DEFAULT_LIST_ID)
        val match = items.firstOrNull { it.text.equals(text, ignoreCase = true) }
            ?: return if (en) "$text isn't on the list." else "$text steht gar nicht auf der Liste."
        store.remove(match.id)
        return if (en) "Removed ${match.text} from the list." else "${match.text} ist von der Liste runter."
    }

    /** „2× Milch" bei Dedupe-Zähler >1, sonst nur der Item-Text (Andi-Format 2026-07-08). */
    private fun display(entry: ListEntry): String =
        if (entry.quantity > 1) "${entry.quantity}× ${entry.text}" else entry.text

    companion object {
        /** Nie erreichter Default (Flag-OFF): kein Store, keine Quittung. */
        val DISABLED = ListFastpath(ListPort.NONE)
    }
}
