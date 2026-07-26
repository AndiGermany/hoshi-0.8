package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.dto.SmartHomeAction
import de.hoshi.core.pipeline.lang.LangDe
import de.hoshi.core.pipeline.lang.LanguagePackRegistry

/**
 * Smart-Home-Bestätigungs-Pool mit Anti-Repeat-Ring (PORT-Einheit aus dem
 * Hoshi-0.5 brain-streaming-Ledger, dort `IntentResponseFormatter`).
 *
 * Anti-Repeat-Ring mit Tiefe [AntiRepeatPicker.DEFAULT_DEPTH] pro Slot (seit
 * 2026-07-26 als [AntiRepeatPicker] extrahiert, s. dessen KDoc) — derselbe
 * Pool gibt nicht zwei (oder drei) Mal hintereinander dieselbe Variante zurück.
 *
 * Acks sind warm/kurz/zustands-eindeutig und **finit + prerender-tauglich**: 2–3
 * explizite Varianten pro Aktion, Räume fix ([ROOMS]) — so kann eine AudioBank
 * jede mögliche Quittung vorrendern (instant Audio statt TTS-Latenz).
 *
 * Entkoppelt von Spring: kein `@Service` — reines Kotlin. Das Wiring kommt im
 * Orchestrator.
 *
 * **Sprachpaket-Kern (0.8, Andi-Auftrag 2026-07-20):** die Phrasen-Pools leben
 * im `de.hoshi.core.pipeline.lang`-Paket (EIN [de.hoshi.core.pipeline.lang.LanguagePack]
 * pro Sprache) — dieser Formatter liest sie nur noch, er besitzt sie nicht mehr.
 *
 * **Multilingual von A-Z (Andi 2026-07-25):** „Smart-Home-Bestätigungen -> sowas
 * soll natürlich auch auf englisch, auch beim wetter und was wir sonst fest
 * verdrahtet haben. es soll multilingual werden. von A-Z". Damit ist die frühere
 * Ausnahme AUFGEHOBEN: es gibt keine Textklasse mehr, die den `language`-Parameter
 * ignoriert. **JEDE** Methode hier zieht ihren Pool über
 * [LanguagePackRegistry.forLanguage] — Konversations-Schicht wie Smart-Home-Acks.
 * DE bleibt dabei byte-identisch (default [Language.DEFAULT] ⇒ [LangDe]).
 *
 * **Nutzerdaten werden NIE übersetzt:** `room` kommt als HA-Raumname herein und
 * wird nur in den `{room}`-Slot gesetzt (+ Erst-Buchstabe groß, wie eh und je) —
 * „Wohnzimmer" bleibt „Wohnzimmer", auch in „The light in Wohnzimmer is on."
 */
class ResponseFormatter {

    /** Die Ack-Pools der aktiven Sprache — EIN Zugriffspunkt, keine Feld-Bindung an DE mehr. */
    private fun acks(language: Language) = LanguagePackRegistry.forLanguage(language).smartHomeAcks

    /**
     * Anti-Repeat-Slot-Key **pro Sprache**: die Ringe von „light_on_room" auf
     * Deutsch und auf Englisch dürfen sich nicht gegenseitig Indizes wegnehmen
     * (unterschiedliche Pools, unterschiedliche Größen). Für einen Ein-Sprach-
     * Betrieb — also den DE-Bestand — ändert das nichts.
     */
    private fun slot(name: String, language: Language) = "$name:${language.code}"

    /**
     * Anti-Repeat-Ring: die letzten N Indizes werden bei der Auswahl
     * ausgeschlossen, damit „Klar — Wohnzimmer." nicht in jeder zweiten
     * Antwort kommt. Seit 2026-07-26 die extrahierte, wiederverwendbare
     * [AntiRepeatPicker]-Instanz (s. deren KDoc) — [pick] delegiert nur noch,
     * Verhalten byte-identisch zum vorherigen Inline-Ring.
     */
    private val picker = AntiRepeatPicker()

    fun lightOn(room: String?, language: Language = Language.DEFAULT): String =
        if (room != null) format("light_on_room", acks(language).lightOnRoom, language, room = room)
        else pick(slot("light_on_no_room", language), acks(language).lightOnNoRoom)

    fun lightOff(room: String?, language: Language = Language.DEFAULT): String =
        if (room != null) format("light_off_room", acks(language).lightOffRoom, language, room = room)
        else pick(slot("light_off_no_room", language), acks(language).lightOffNoRoom)

    fun lightDim(room: String?, value: Int?, language: Language = Language.DEFAULT): String = when {
        value != null && room != null ->
            format("light_dim_room", acks(language).lightDimRoom, language, room = room, value = value.toString())
        value != null ->
            format("light_dim_no_room", acks(language).lightDimNoRoom, language, value = value.toString())
        else -> pick(slot("light_dim_no_value", language), acks(language).lightDimNoValue)
    }

    fun scene(language: Language = Language.DEFAULT): String =
        pick(slot("scene", language), acks(language).scene)
    fun coverOpen(language: Language = Language.DEFAULT): String =
        pick(slot("cover_open", language), acks(language).coverOpen)
    fun coverClose(language: Language = Language.DEFAULT): String =
        pick(slot("cover_close", language), acks(language).coverClose)
    fun unknown(language: Language = Language.DEFAULT): String =
        pick(slot("unknown", language), acks(language).unknown)

    fun climate(room: String?, value: Int?, language: Language = Language.DEFAULT): String = when {
        value != null && room != null ->
            format("climate_room", acks(language).climateRoom, language, room = room, value = value.toString())
        value != null ->
            format("climate_value_no_room", acks(language).climateValueNoRoom, language, value = value.toString())
        else -> pick(slot("climate_no_value", language), acks(language).climateNoValue)
    }

    /** Farbwechsel — [colorName] ist erkannte Nutzer-/Geräte-Eingabe und wird NICHT übersetzt. */
    fun lightColor(colorName: String?, language: Language = Language.DEFAULT): String =
        if (colorName != null) {
            fill(pick(slot("light_color_named", language), acks(language).lightColorNamed), null, null, colorName)
        } else {
            pick(slot("light_color_unnamed", language), acks(language).lightColorUnnamed)
        }

    // ── NoEffect-Ehrlichkeit ──────────────────────────────────────────────────

    /** „War schon dunkel." — Licht-AUS lief ins Leere (war schon aus). */
    fun lightOffNoEffect(room: String?, language: Language = Language.DEFAULT): String =
        if (room != null) format("light_off_noeffect_room", acks(language).lightOffNoEffectRoom, language, room = room)
        else pick(slot("light_off_noeffect_no_room", language), acks(language).lightOffNoEffectNoRoom)

    /** „War schon hell." — Licht-AN lief ins Leere (war schon an). */
    fun lightOnNoEffect(room: String?, language: Language = Language.DEFAULT): String =
        if (room != null) format("light_on_noeffect_room", acks(language).lightOnNoEffectRoom, language, room = room)
        else pick(slot("light_on_noeffect_no_room", language), acks(language).lightOnNoEffectNoRoom)

    /** „Steht schon ungefähr auf X%." — LIGHT_DIM war schon (±5%) auf dem Zielwert. */
    fun lightDimNoEffect(room: String?, value: Int?, language: Language = Language.DEFAULT): String = when {
        value != null && room != null -> format(
            "light_dim_noeffect_room", acks(language).lightDimNoEffectRoom, language,
            room = room, value = value.toString(),
        )
        value != null -> format(
            "light_dim_noeffect_no_room", acks(language).lightDimNoEffectNoRoom, language,
            value = value.toString(),
        )
        else -> pick(slot("generic_noeffect", language), acks(language).genericNoEffect)
    }

    /** „War schon offen." — Rollladen war schon im Zielzustand. */
    fun coverOpenNoEffect(language: Language = Language.DEFAULT): String =
        pick(slot("cover_open_noeffect", language), acks(language).coverOpenNoEffect)

    /** „War schon zu." — Rollladen war schon im Zielzustand. */
    fun coverCloseNoEffect(language: Language = Language.DEFAULT): String =
        pick(slot("cover_close_noeffect", language), acks(language).coverCloseNoEffect)

    /** „Steht schon auf {value} Grad." — Thermostat war schon auf Zielwert. */
    fun climateNoEffect(room: String?, value: Int?, language: Language = Language.DEFAULT): String = when {
        value != null && room != null -> format(
            "climate_noeffect_room", acks(language).climateNoEffectRoom, language,
            room = room, value = value.toString(),
        )
        value != null -> format(
            "climate_noeffect_no_room", acks(language).climateNoEffectNoRoom, language,
            value = value.toString(),
        )
        else -> pick(slot("generic_noeffect", language), acks(language).genericNoEffect)
    }

    /** Generischer NoEffect-Fallback (Szene / UNKNOWN / kein Slot). */
    fun genericNoEffect(language: Language = Language.DEFAULT): String =
        pick(slot("generic_noeffect", language), acks(language).genericNoEffect)

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
        val pack = acks(language)
        if (room.isNullOrBlank()) {
            return pick(slot("partial_offline_no_room", language), pack.partialOfflineNoRoom)
        }
        val many = offline > 1
        val (slotName, pool) = when (action) {
            SmartHomeAction.LIGHT_OFF ->
                if (many) "light_off_partial_many" to pack.lightOffPartialOfflineMany
                else "light_off_partial_one" to pack.lightOffPartialOfflineOne
            else ->
                if (many) "light_on_partial_many" to pack.lightOnPartialOfflineMany
                else "light_on_partial_one" to pack.lightOnPartialOfflineOne
        }
        // applied==1: Zahl-Anker raus — die warmen „der Rest"-Varianten tragen jeden Count.
        val effective = if (applied == 1) pool.dropLast(1) else pool
        return pick(slot(slotName, language), effective)
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
            pick(slot("unsupported_cover", language), acks(language).unsupportedCover)
        SmartHomeAction.CLIMATE_SET ->
            pick(slot("unsupported_climate", language), acks(language).unsupportedClimate)
        SmartHomeAction.SCENE_ACTIVATE ->
            pick(slot("unsupported_scene", language), acks(language).unsupportedScene)
        else ->
            pick(slot("unsupported_generic", language), acks(language).unsupportedGeneric)
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

    private fun format(
        slotName: String,
        pool: List<String>,
        language: Language,
        room: String? = null,
        value: String? = null,
    ): String {
        val raw = pick(slot(slotName, language), pool)
        return fill(raw, room, value)
    }

    /**
     * Setzt {room}/{value}/{color} ein — identisch für Live-Auswahl und Prerender.
     *
     * [room] ist **Nutzerdatum** (HA-Raumname): es wird nur eingesetzt und im
     * ersten Buchstaben groß geschrieben, NIE übersetzt — in JEDER Sprache.
     */
    private fun fill(template: String, room: String?, value: String?, color: String? = null): String {
        var out = template
        if (room != null) out = out.replace("{room}", room.replaceFirstChar { it.uppercase() })
        if (value != null) out = out.replace("{value}", value)
        if (color != null) out = out.replace("{color}", color)
        return out
    }

    /**
     * Enumeriert die **vollständige, endliche** Menge aller Smart-Home-Acks, die
     * `lightOn/lightOff/.../climate` je zurückgeben können — über alle [ROOMS] und
     * (für Dim/Climate) alle [PRERENDER_VALUES]. Genau diese Texte landen im
     * AudioBank-Prerender. Single Source of Truth.
     *
     * Nimmt seit 2026-07-25 die [language] entgegen (Default [Language.DEFAULT] ⇒
     * DE, byte-identisch zum Bestand): eine AudioBank wird immer für GENAU EINE
     * Stimme/Sprache vorgerendert, darum eine Sprache pro Aufruf statt aller auf
     * einmal.
     */
    fun prerenderAcks(language: Language = Language.DEFAULT): List<String> {
        val out = LinkedHashSet<String>()
        val pack = acks(language)

        for (r in ROOMS) {
            pack.lightOnRoom.forEach { out += fill(it, r, null) }
            pack.lightOffRoom.forEach { out += fill(it, r, null) }
            for (v in PRERENDER_VALUES) {
                pack.lightDimRoom.forEach { out += fill(it, r, v.toString()) }
                pack.climateRoom.forEach { out += fill(it, r, v.toString()) }
            }
        }
        for (v in PRERENDER_VALUES) {
            pack.lightDimNoRoom.forEach { out += fill(it, null, v.toString()) }
        }
        out += pack.scene
        out += pack.coverOpen
        out += pack.coverClose
        out += pack.unknown
        // Die vier „kein Slot"-Fixtexte (früher hier inline, jetzt im Katalog).
        out += pack.lightOnNoRoom
        out += pack.lightOffNoRoom
        out += pack.lightDimNoValue
        out += pack.climateNoValue
        return out.toList()
    }

    /**
     * Wählt einen Pool-Eintrag über den [picker] — delegiert an
     * [AntiRepeatPicker.pick] (Anti-Repeat-Tiefe [AntiRepeatPicker.DEFAULT_DEPTH]).
     */
    private fun pick(slot: String, pool: List<String>): String = picker.pick(slot, pool)

    companion object {
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
