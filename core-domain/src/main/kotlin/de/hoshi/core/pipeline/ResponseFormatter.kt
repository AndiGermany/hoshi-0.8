package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.dto.SmartHomeAction
import de.hoshi.core.pipeline.lang.LangDe
import de.hoshi.core.pipeline.lang.LanguagePackRegistry
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Smart-Home-Bestätigungs-Pool mit Anti-Repeat-Ring (PORT-Einheit aus dem
 * Hoshi-0.5 brain-streaming-Ledger, dort `IntentResponseFormatter`).
 *
 * Anti-Repeat-Ring mit Tiefe [REPEAT_DEPTH] pro Slot — derselbe Pool gibt nicht
 * zwei (oder drei) Mal hintereinander dieselbe Variante zurück.
 *
 * Acks sind warm/kurz/zustands-eindeutig und **finit + prerender-tauglich**: 2–3
 * explizite Varianten pro Aktion, Räume fix ([ROOMS]) — so kann eine AudioBank
 * jede mögliche Quittung vorrendern (instant Audio statt TTS-Latenz).
 *
 * Entkoppelt von Spring: kein `@Service` — reines Kotlin. Das Wiring kommt im
 * Orchestrator.
 *
 * **Sprachpaket-Kern (0.8, Andi-Auftrag 2026-07-20):** die Phrasen-Pools leben
 * jetzt im `de.hoshi.core.pipeline.lang`-Paket (EIN [de.hoshi.core.pipeline.lang.LanguagePack]
 * pro Sprache) — dieser Formatter liest sie nur noch, er besitzt sie nicht mehr.
 * ZWEI Kategorien, bewusst UNTERSCHIEDLICH sprach-gebunden:
 *  - **Smart-Home-/Timer-Reflexe** (lightOn/lightOff/.../climate/unsupported/…):
 *    bleiben IMMER Deutsch ([LangDe.SMART_HOME_ACKS]), unabhängig vom übergebenen
 *    [Language] — Andi-Vorgabe „Reflexe NICHT anfassen". Der `language`-Parameter
 *    bleibt in der Signatur (Zukunfts-/API-Stabilität), wird hier aber bewusst
 *    ignoriert.
 *  - **Konversations-Schicht** (Cloud-Consent + Abstain-Angebot): folgt der
 *    aktiven Sprache über [LanguagePackRegistry.forLanguage] — DE bleibt
 *    byte-identisch (default [Language.DEFAULT]), EN/ES/FR/IT docken hier an.
 */
class ResponseFormatter {

    private val smartHome = LangDe.SMART_HOME_ACKS

    /**
     * Anti-Repeat-Tiefe: die letzten N Indizes werden bei der Auswahl
     * ausgeschlossen, damit „Klar — Wohnzimmer." nicht in jeder zweiten
     * Antwort kommt.
     */
    private val recentIndices = ConcurrentHashMap<String, ArrayDeque<Int>>()

    fun lightOn(room: String?, language: Language = Language.DEFAULT): String =
        if (room != null) format("light_on_room", smartHome.lightOnRoom, room = room) else "Licht ist an."

    fun lightOff(room: String?, language: Language = Language.DEFAULT): String =
        if (room != null) format("light_off_room", smartHome.lightOffRoom, room = room) else "Licht ist aus."

    fun lightDim(room: String?, value: Int?, language: Language = Language.DEFAULT): String = when {
        value != null && room != null -> format("light_dim_room", smartHome.lightDimRoom, room = room, value = value.toString())
        value != null                 -> format("light_dim_no_room", smartHome.lightDimNoRoom, value = value.toString())
        else                          -> "Ist gedimmt."
    }

    fun scene(language: Language = Language.DEFAULT): String = pick("scene", smartHome.scene)
    fun coverOpen(language: Language = Language.DEFAULT): String = pick("cover_open", smartHome.coverOpen)
    fun coverClose(language: Language = Language.DEFAULT): String = pick("cover_close", smartHome.coverClose)
    fun unknown(language: Language = Language.DEFAULT): String = pick("unknown", smartHome.unknown)

    fun climate(room: String?, value: Int?, language: Language = Language.DEFAULT): String = when {
        value != null && room != null -> format("climate_room", smartHome.climateRoom, room = room, value = value.toString())
        value != null                 -> "Auf $value Grad."
        else                          -> "Ist eingestellt."
    }

    fun lightColor(colorName: String?, language: Language = Language.DEFAULT): String =
        if (colorName != null) "Farbe ist $colorName." else "Farbe ist geändert."

    // ── NoEffect-Ehrlichkeit ──────────────────────────────────────────────────

    /** „War schon dunkel." — Licht-AUS lief ins Leere (war schon aus). */
    fun lightOffNoEffect(room: String?, language: Language = Language.DEFAULT): String =
        if (room != null) format("light_off_noeffect_room", smartHome.lightOffNoEffectRoom, room = room)
        else pick("light_off_noeffect_no_room", smartHome.lightOffNoEffectNoRoom)

    /** „War schon hell." — Licht-AN lief ins Leere (war schon an). */
    fun lightOnNoEffect(room: String?, language: Language = Language.DEFAULT): String =
        if (room != null) format("light_on_noeffect_room", smartHome.lightOnNoEffectRoom, room = room)
        else pick("light_on_noeffect_no_room", smartHome.lightOnNoEffectNoRoom)

    /** „Steht schon ungefähr auf X%." — LIGHT_DIM war schon (±5%) auf dem Zielwert. */
    fun lightDimNoEffect(room: String?, value: Int?, language: Language = Language.DEFAULT): String = when {
        value != null && room != null ->
            format("light_dim_noeffect_room", smartHome.lightDimNoEffectRoom, room = room, value = value.toString())
        value != null ->
            format("light_dim_noeffect_no_room", smartHome.lightDimNoEffectNoRoom, value = value.toString())
        else -> pick("generic_noeffect", smartHome.genericNoEffect)
    }

    /** „War schon offen." — Rollladen war schon im Zielzustand. */
    fun coverOpenNoEffect(language: Language = Language.DEFAULT): String =
        pick("cover_open_noeffect", smartHome.coverOpenNoEffect)

    /** „War schon zu." — Rollladen war schon im Zielzustand. */
    fun coverCloseNoEffect(language: Language = Language.DEFAULT): String =
        pick("cover_close_noeffect", smartHome.coverCloseNoEffect)

    /** „Steht schon auf {value} Grad." — Thermostat war schon auf Zielwert. */
    fun climateNoEffect(room: String?, value: Int?, language: Language = Language.DEFAULT): String = when {
        value != null && room != null ->
            format("climate_noeffect_room", smartHome.climateNoEffectRoom, room = room, value = value.toString())
        value != null ->
            format("climate_noeffect_no_room", smartHome.climateNoEffectNoRoom, value = value.toString())
        else -> pick("generic_noeffect", smartHome.genericNoEffect)
    }

    /** Generischer NoEffect-Fallback (Szene / UNKNOWN / kein Slot). */
    fun genericNoEffect(language: Language = Language.DEFAULT): String =
        pick("generic_noeffect", smartHome.genericNoEffect)

    /**
     * Dispatch-Einstieg für die NoEffect-Quittung: bildet die Action auf die
     * richtige warme Ehrlichkeits-Phrase ab. Single Source of Truth — der Caller
     * delegiert hierher, statt selbst Action→Text zu mappen.
     */
    fun noEffect(
        action: SmartHomeAction,
        room: String?,
        value: Int? = null,
        language: Language = Language.DEFAULT,
    ): String = when (action) {
        SmartHomeAction.LIGHT_ON,
        SmartHomeAction.LIGHT_COLOR -> lightOnNoEffect(room, language)
        SmartHomeAction.LIGHT_DIM   -> lightDimNoEffect(room, value, language)
        SmartHomeAction.LIGHT_OFF   -> lightOffNoEffect(room, language)
        SmartHomeAction.COVER_OPEN  -> coverOpenNoEffect(language)
        SmartHomeAction.COVER_CLOSE -> coverCloseNoEffect(language)
        SmartHomeAction.CLIMATE_SET -> climateNoEffect(room, value, language)
        SmartHomeAction.SCENE_ACTIVATE,
        SmartHomeAction.UNKNOWN     -> genericNoEffect(language)
    }

    /**
     * Ehrliche PartialOffline-Quittung: [applied] Lampen haben reagiert, [offline]
     * melden sich nicht. EIN warmer Satz, der die offline-Lampe(n) benennt.
     *
     * Singular/Plural-Schnitt über [offline]; bei [applied]==1 wird der Zahl-Anker
     * weggelassen ("1 sind an" liest sich falsch).
     */
    fun partialOffline(
        action: SmartHomeAction,
        room: String?,
        applied: Int,
        offline: Int,
        language: Language = Language.DEFAULT,
    ): String {
        if (room.isNullOrBlank()) return pick("partial_offline_no_room", smartHome.partialOfflineNoRoom)
        val many = offline > 1
        val (slot, pool) = when (action) {
            SmartHomeAction.LIGHT_OFF ->
                if (many) "light_off_partial_many" to smartHome.lightOffPartialOfflineMany
                else "light_off_partial_one" to smartHome.lightOffPartialOfflineOne
            else ->
                if (many) "light_on_partial_many" to smartHome.lightOnPartialOfflineMany
                else "light_on_partial_one" to smartHome.lightOnPartialOfflineOne
        }
        // applied==1: Zahl-Anker raus — die warmen „der Rest"-Varianten tragen jeden Count.
        val effective = if (applied == 1) pool.dropLast(1) else pool
        return pick(slot, effective)
            .replace("{room}", room.replaceFirstChar { it.uppercase() })
            .replace("{applied}", applied.toString())
            .replace("{offline}", offline.toString())
    }

    /**
     * HA exponiert den Service nicht → ehrlich melden, dass es das Gerät nicht
     * gibt. Action-aware fürs passende Wort, warm statt service-bot-ig. KEINE
     * Erfolgsbestätigung.
     */
    fun unsupported(action: SmartHomeAction, language: Language = Language.DEFAULT): String = when (action) {
        SmartHomeAction.COVER_OPEN,
        SmartHomeAction.COVER_CLOSE ->
            pick("unsupported_cover", smartHome.unsupportedCover)
        SmartHomeAction.CLIMATE_SET ->
            pick("unsupported_climate", smartHome.unsupportedClimate)
        SmartHomeAction.SCENE_ACTIVATE ->
            pick("unsupported_scene", smartHome.unsupportedScene)
        else ->
            pick("unsupported_generic", smartHome.unsupportedGeneric)
    }

    // ── Konversations-Schicht: folgt der aktiven Sprache ─────────────────────

    fun cloudConsentAsk(language: Language = Language.DEFAULT): String =
        pick("cloud_consent_ask", LanguagePackRegistry.forLanguage(language).cloudConsentAsk)
    /** Aufgreifende Consent-Frage bei EXPLIZITER Online-Bitte. */
    fun cloudConsentAskExplicit(language: Language = Language.DEFAULT): String =
        pick("cloud_consent_ask_explicit", LanguagePackRegistry.forLanguage(language).cloudConsentAskExplicit)
    /** Kurze Bestätigung wenn Andi „Ja" gesagt hat. */
    fun cloudConsentAccept(language: Language = Language.DEFAULT): String =
        pick("cloud_consent_accept", LanguagePackRegistry.forLanguage(language).cloudConsentAccept)
    /** Übergang wenn Andi „Nein" gesagt hat — vor der lokalen Antwort. */
    fun cloudConsentDecline(language: Language = Language.DEFAULT): String =
        pick("cloud_consent_decline", LanguagePackRegistry.forLanguage(language).cloudConsentDecline)
    /** Hörbares Angebot NACH einem ehrlichen Brain-Abstain (Naht D) — Anti-Repeat wie [cloudConsentAsk]. */
    fun abstainLookupOffer(language: Language = Language.DEFAULT): String =
        pick("abstain_lookup_offer", LanguagePackRegistry.forLanguage(language).abstainLookupOffer)

    private fun format(slot: String, pool: List<String>, room: String? = null, value: String? = null): String {
        val raw = pick(slot, pool)
        return fill(raw, room, value)
    }

    /** Setzt {room}/{value} ein — identisch für Live-Auswahl und Prerender. */
    private fun fill(template: String, room: String?, value: String?): String {
        var out = template
        if (room != null) out = out.replace("{room}", room.replaceFirstChar { it.uppercase() })
        if (value != null) out = out.replace("{value}", value)
        return out
    }

    /**
     * Enumeriert die **vollständige, endliche** Menge aller Smart-Home-Acks, die
     * `lightOn/lightOff/.../climate` je zurückgeben können — über alle [ROOMS] und
     * (für Dim/Climate) alle [PRERENDER_VALUES]. Genau diese Texte landen im
     * AudioBank-Prerender. Single Source of Truth.
     */
    fun prerenderAcks(): List<String> {
        val out = LinkedHashSet<String>()

        for (r in ROOMS) {
            smartHome.lightOnRoom.forEach { out += fill(it, r, null) }
            smartHome.lightOffRoom.forEach { out += fill(it, r, null) }
            for (v in PRERENDER_VALUES) {
                smartHome.lightDimRoom.forEach { out += fill(it, r, v.toString()) }
                smartHome.climateRoom.forEach { out += fill(it, r, v.toString()) }
            }
        }
        for (v in PRERENDER_VALUES) {
            smartHome.lightDimNoRoom.forEach { out += fill(it, null, v.toString()) }
        }
        out += smartHome.scene
        out += smartHome.coverOpen
        out += smartHome.coverClose
        out += smartHome.unknown
        out += listOf(
            "Licht ist an.", "Licht ist aus.", "Ist gedimmt.", "Ist eingestellt.",
        )
        return out.toList()
    }

    /**
     * Wählt einen Pool-Eintrag, der **nicht** unter den letzten [REPEAT_DEPTH]
     * gewählten Indizes ist. Bei Pool-Größen ≤ REPEAT_DEPTH degradiert die Logik
     * auf „nicht direkt der Letzte" (sonst läuft der Ring leer).
     */
    private fun pick(slot: String, pool: List<String>): String {
        if (pool.isEmpty()) return ""
        if (pool.size == 1) return pool[0]

        val ring = recentIndices.getOrPut(slot) { ArrayDeque(REPEAT_DEPTH) }
        val effectiveDepth = minOf(REPEAT_DEPTH, pool.size - 1)
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
        const val REPEAT_DEPTH = 3

        /** Die festen HA-live-Räume. Quelle der Wahrheit für die endliche Ack-Menge. */
        val ROOMS = listOf(
            "wohnzimmer", "küche", "schlafzimmer", "arbeitszimmer",
            "flur", "keller", "bad",
        )

        /** Dim-/Klima-Stufen, für die Acks vorgerendert werden (Licht 10–100 %, Klima 16–24 °C). */
        val PRERENDER_VALUES = listOf(
            10, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 30, 40, 50, 60, 70, 75, 80, 90, 100,
        )
    }
}
