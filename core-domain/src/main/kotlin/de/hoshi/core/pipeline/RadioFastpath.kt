package de.hoshi.core.pipeline

import de.hoshi.core.dto.Language
import de.hoshi.core.pipeline.lang.fallsBackToEnglish
import de.hoshi.core.port.RadioCallOutcome
import de.hoshi.core.port.RadioPort

/**
 * **RadioFastpath** — der brain-freie Vollzug eines eindeutigen Internetradio-
 * Wunschs (Musik Stufe A): „spiel radio wdr 2" / „spiel wdr 2 radio" ⇒
 * [RadioPort.search] + [RadioPort.play] auf dem konfigurierten [target];
 * „radio aus/stopp" ⇒ [RadioPort.stop]. Ruft den Brain NIE.
 *
 * Geschwister zum [DateFastpath] (IN-SITU): die Erkennung trägt der Fastpath
 * selbst — kein eigener Intent-Parser, kein [de.hoshi.core.tools.ToolCall],
 * kein HA-Schreib-Gate (ein Radiostart ist ein lokaler Convenience-Wunsch).
 * [handle] liefert `null`, wenn der Text KEIN Radio-Wunsch ist ⇒ der
 * Orchestrator fällt unverändert in den normalen Turn (byte-neutral).
 *
 * **ACHTUNG I/O:** [handle] macht bei Treffern echtes Netzwerk-I/O (Stream-
 * Suche + Abspiel-Call). Der [TurnOrchestrator] prüft darum erst das billige,
 * netzfreie [matches] und legt [handle] dann auf `boundedElastic` (P0
 * Event-Loop-Muster wie `toolReadTurn`/`honesty.assess`).
 *
 * KONSERVATIV (false-positive-avers): NUR die kuratierten DE/EN/ES/FR/IT-Muster
 * (Andi-Befund 2026-07-25: die Antworten waren längst mehrsprachig, die
 * ERKENNUNG selbst blieb deutsch — s. [PLAY_PREFIX_EN] ff.) — beiläufiges
 * „Radio" in Plauderei triggert in keiner der fünf Sprachen etwas. Die
 * Antworten sind kurz und warm (Lara-Ton, kein Slop):
 *  - Treffer, VERIFIZIERT ([RadioCallOutcome.VERIFIED]): „<Station> läuft —
 *    auf dem Receiver." (play) / „Radio ist aus." (stop) — der [RadioPort]
 *    hat den echten `media_player`-State gelesen (oder der Readback selbst
 *    ist best-effort ausgefallen, s. [RadioCallOutcome]-KDoc).
 *  - Akzeptiert, aber State NIE erreicht ([RadioCallOutcome.ACCEPTED_STATE_NOT_REACHED],
 *    P2-Bug-Fix 2026-07-11: HTTP-2xx allein bewies nie den echten Zustand —
 *    „läuft" durfte nicht länger reine Behauptung sein): ehrliches
 *    „Ich hab <Station> an den Receiver geschickt, aber er spielt (noch)
 *    nicht — evtl. ist er aus oder auf einem anderen Eingang." (play) /
 *    „Ich hab dem Receiver Stopp gesagt, aber er scheint noch zu spielen —
 *    vielleicht hat er nicht reagiert." (stop)
 *  - NICHT angenommen ([RadioCallOutcome.NOT_ACCEPTED]): „<Station> hab ich
 *    gefunden, aber der Receiver reagiert gerade nicht." (play) / „Der
 *    Receiver reagiert gerade nicht." (stop)
 *  - NOT_FOUND (Andi-Schwelle: kein stilles Falsch-Matching):
 *    „<name> kenn ich nicht sicher — meinst du was Ähnliches?"
 *
 * [DISABLED] (`enabled = false` ist auch der Kotlin-Default) ist der
 * nie-antwortende Default (Flag-OFF): ohne `HOSHI_RADIO_ENABLED` liefert
 * [matches] immer `false` und [handle] immer `null` ⇒ toter Zweig ⇒ byte-neutral.
 */
class RadioFastpath(
    private val radio: RadioPort,
    /**
     * Abspielziel in Domänen-Sprache (beim HA-Adapter die `media_player.*`-
     * Entity-Id des Receivers, via `HOSHI_RADIO_TARGET`). Leer ⇒ ehrliche
     * „mir fehlt das Abspielziel"-Antwort statt eines stillen No-ops.
     */
    private val target: String = "",
    /** Flag-OFF-Naht: `false` (Default!) ⇒ [handle] liefert IMMER `null`. */
    private val enabled: Boolean = false,
) {

    /**
     * Billige, netzfreie Vorprüfung: ist [text] ein eindeutiger Radio-Wunsch?
     * `false` bei Flag-OFF/leer — der Orchestrator betritt den (I/O-)Pfad dann nie.
     */
    fun matches(text: String): Boolean {
        if (!enabled || text.isBlank()) return false
        val norm = normalize(text)
        return norm in STOP_PHRASES || extractStationName(norm) != null
    }

    /**
     * Vollzieht den Radio-Wunsch und liefert die fertige, sprechbare Quittung;
     * jeder Nicht-Treffer (kein Radio-Wunsch, Flag-OFF, leer) ⇒ `null` (⇒ normaler Turn).
     */
    fun handle(text: String, language: Language): String? {
        if (!enabled || text.isBlank()) return null
        val norm = normalize(text)
        if (norm in STOP_PHRASES) return handleStop(language)
        val name = extractStationName(norm) ?: return null
        return handlePlay(name, language)
    }

    // ── Vollzug ──────────────────────────────────────────────────────────────

    private fun handlePlay(name: String, language: Language): String {
        val en = language.fallsBackToEnglish
        // Ehrlich VOR dem Suchen: ohne Abspielziel wäre jeder Treffer ein leeres Versprechen.
        if (target.isBlank()) {
            return if (en) "I can't play radio yet — no playback target is set up."
            else "Radio kann ich noch nicht abspielen — mir fehlt das Abspielziel."
        }
        // Andi-Schwelle lebt im Port ([RadioPort.search]): unähnlich ⇒ null ⇒ warmes NOT_FOUND.
        val station = radio.search(name)
            ?: return if (en) "I'm not sure I know $name — did you mean something similar?"
            else "$name kenn ich nicht sicher — meinst du was Ähnliches?"
        // Die Phrase kommt aus dem GELESENEN State (RadioCallOutcome), nicht aus der
        // bloßen Absicht „HTTP hat akzeptiert" — grün≠lebt (P2-Bug-Fix 2026-07-11).
        return when (radio.play(station, target)) {
            RadioCallOutcome.VERIFIED ->
                if (en) "${station.name} is playing — on the receiver."
                else "${station.name} läuft — auf dem Receiver."
            RadioCallOutcome.ACCEPTED_STATE_NOT_REACHED ->
                if (en) {
                    "I sent ${station.name} to the receiver, but it isn't playing (yet) — " +
                        "maybe it's off or on a different input."
                } else {
                    "Ich hab ${station.name} an den Receiver geschickt, aber er spielt (noch) nicht — " +
                        "evtl. ist er aus oder auf einem anderen Eingang."
                }
            RadioCallOutcome.NOT_ACCEPTED ->
                if (en) "I found ${station.name}, but the receiver isn't responding right now."
                else "${station.name} hab ich gefunden, aber der Receiver reagiert gerade nicht."
        }
    }

    private fun handleStop(language: Language): String {
        val en = language.fallsBackToEnglish
        if (target.isBlank()) {
            return if (en) "There's no radio target set up — nothing to stop."
            else "Da ist noch kein Radio-Ziel eingerichtet — nichts zu stoppen."
        }
        return when (radio.stop(target)) {
            RadioCallOutcome.VERIFIED ->
                if (en) "Radio is off." else "Radio ist aus."
            RadioCallOutcome.ACCEPTED_STATE_NOT_REACHED ->
                if (en) {
                    "I told the receiver to stop, but it still seems to be playing — maybe it didn't respond."
                } else {
                    "Ich hab dem Receiver Stopp gesagt, aber er scheint noch zu spielen — " +
                        "vielleicht hat er nicht reagiert."
                }
            RadioCallOutcome.NOT_ACCEPTED ->
                if (en) "The receiver isn't responding right now."
                else "Der Receiver reagiert gerade nicht."
        }
    }

    // ── Reine Erkennung (uhr- und netzfrei) ──────────────────────────────────

    /**
     * Extrahiert den Stationsnamen aus dem normalisierten Text — nur für die
     * kuratierten Muster „spiel(e) [mal] [das] radio <name>" / „spiel(e) [mal]
     * <name> [im] radio" und ihre EN/ES/FR/IT-Pendants ([PLAY_PREFIXES]/
     * [PLAY_SUFFIXES], Andi-Befund 2026-07-25); sonst `null`. Stop-Wörter als
     * „Name" (z.B. „spiel radio aus") sind kein Play-Wunsch. DE zuerst in
     * beiden Listen ⇒ unveränderte Priorität/Verhalten für deutsche Sätze.
     */
    private fun extractStationName(norm: String): String? {
        val name = PLAY_PREFIXES.firstNotNullOfOrNull { it.find(norm)?.groupValues?.get(1) }
            ?: PLAY_SUFFIXES.firstNotNullOfOrNull { it.find(norm)?.groupValues?.get(1) }
        return name?.trim()?.takeIf { it.isNotEmpty() && it !in STOP_WORDS }
    }

    /** Lowercase, Apostrophe weg, alles außer DE-Buchstaben/Ziffern → Space (wie [DateFastpath]). */
    private fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("[’'`´ʼ]"), "")
            .replace(Regex("[^a-zäöüß0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    companion object {
        /** Nie-antwortender Default (Flag-OFF): der Radio-Zweig ist tot ⇒ byte-neutral. */
        val DISABLED = RadioFastpath(RadioPort.NONE)

        /** „spiel(e) [mal] [das] radio <name>" — Name hinter dem Radio-Wort. */
        private val PLAY_PREFIX = Regex("^spiele? (?:mal )?(?:das )?radio (.+)$")

        /** „spiel(e) [mal] <name> [im] radio" — Name vor dem Radio-Wort. */
        private val PLAY_SUFFIX = Regex("^spiele? (?:mal )?(.+?) (?:im )?radio$")

        // EN/ES/FR/IT-Pendants (Andi-Befund 2026-07-25: die Quittungen waren
        // längst mehrsprachig, die ERKENNUNG selbst blieb deutsch). „radio" ist
        // in allen fünf Sprachen dasselbe Wort — variabel ist nur das Verb/der
        // Artikel davor. [normalize] entfernt Akzente ersatzlos (kein `é`→`e`,
        // sondern `é`→` `) — die Muster unten sind deshalb bewusst akzentfrei
        // geschrieben, sonst könnten sie den normalisierten Text NIE treffen.
        //
        // „play "/„pon"/„mets"/„metti" sind eindeutige Radio-Bedienverben — kein
        // Konflikt mit Alltagssprache in der geforderten Wortfolge mit „radio".

        /** EN: „play [the] radio <name>". */
        private val PLAY_PREFIX_EN = Regex("^play (?:the )?radio (.+)$")

        /** ES: „pon/reproduce [la] radio <name>". */
        private val PLAY_PREFIX_ES = Regex("^(?:pon|reproduce) (?:la )?radio (.+)$")

        /** FR: „mets/joue [la] radio <name>" (kein „à" — [normalize] entfernt es ersatzlos). */
        private val PLAY_PREFIX_FR = Regex("^(?:mets|joue) (?:la )?radio (.+)$")

        /** IT: „metti/riproduci [la] radio <name>". */
        private val PLAY_PREFIX_IT = Regex("^(?:metti|riproduci) (?:la )?radio (.+)$")

        /** DE zuerst ⇒ unveränderte Priorität/Verhalten für deutsche Sätze. */
        private val PLAY_PREFIXES = listOf(PLAY_PREFIX, PLAY_PREFIX_EN, PLAY_PREFIX_ES, PLAY_PREFIX_FR, PLAY_PREFIX_IT)

        /** EN: „play <name> [on] [the] radio". */
        private val PLAY_SUFFIX_EN = Regex("^play (.+?) (?:on (?:the )?)?radio$")

        /** ES: „pon/reproduce <name> [en] [la] radio". */
        private val PLAY_SUFFIX_ES = Regex("^(?:pon|reproduce) (.+?) (?:en (?:la )?)?radio$")

        /** FR: „mets/joue <name> [la] radio" (kein „à la" — [normalize] entfernt „à" ersatzlos). */
        private val PLAY_SUFFIX_FR = Regex("^(?:mets|joue) (.+?) (?:la )?radio$")

        /** IT: „metti/riproduci <name> [alla] radio". */
        private val PLAY_SUFFIX_IT = Regex("^(?:metti|riproduci) (.+?) (?:alla )?radio$")

        /** DE zuerst ⇒ unveränderte Priorität/Verhalten für deutsche Sätze. */
        private val PLAY_SUFFIXES = listOf(PLAY_SUFFIX, PLAY_SUFFIX_EN, PLAY_SUFFIX_ES, PLAY_SUFFIX_FR, PLAY_SUFFIX_IT)

        /**
         * Kuratierte Stop-Phrasen, EXAKT gegen den normalisierten Text geprüft
         * (konservativ: „im Radio lief gestern…" triggert nichts). EN/ES/FR/IT
         * bewusst akzentfrei formuliert (s. [PLAY_PREFIX_EN]-Block) UND ohne
         * Präpositionen, die in ihrer Sprache eine Doppelbedeutung tragen (ES
         * „para" ist auch „für" — „para la radio" bliebe darum absichtlich
         * draußen, s. RESULT-Zeile).
         */
        private val STOP_PHRASES = setOf(
            "radio aus", "radio stopp", "radio stop",
            "stopp das radio", "stop das radio",
            "mach das radio aus", "schalt das radio aus",
            // EN
            "radio off", "stop radio", "stop the radio", "turn off the radio", "turn the radio off",
            // ES
            "apaga la radio", "radio apagada",
            // FR
            "coupe la radio",
            // IT
            "spegni la radio", "ferma la radio",
        )

        /** Wörter, die als extrahierter „Name" keinen Play-Wunsch ergeben. */
        private val STOP_WORDS = setOf("aus", "an", "stopp", "stop", "ein", "off", "on")
    }
}
