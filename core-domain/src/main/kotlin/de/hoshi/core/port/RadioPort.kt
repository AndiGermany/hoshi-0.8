package de.hoshi.core.port

/**
 * Eine aufgelöste Internetradio-Station in **Domänen-Sprache** (keine
 * radio-browser-/HA-Begriffe im Kern): der anzeigbare Name + die direkt
 * abspielbare Stream-URL.
 */
data class RadioStation(
    /** Anzeigename der Station (z.B. „WDR 2"), wie die Quelle ihn führt. */
    val name: String,
    /** Direkt abspielbare Stream-URL (http/https-Audio-Stream). */
    val streamUrl: String,
)

/**
 * Ehrliches Ergebnis eines media_player-Tat-Calls ([RadioPort.play]/[RadioPort.stop])
 * MIT READ-ONLY State-Readback (0.7-`HaToolPort`-Essenz, s. dessen KDoc: Delta-
 * Baseline + Settle-Poll für Licht). Genau 3 Werte — [RadioFastpath] bildet jeden
 * auf GENAU eine Phrase ab, nie geraten:
 *
 *  - [NOT_ACCEPTED]: HA hat den Service-Call NICHT angenommen (HTTP-Fehler/Timeout/
 *    Exception) ODER Token/Ziel fehlten — die bisherige „false"-Bedeutung, UNVERÄNDERT.
 *  - [VERIFIED]: HA hat angenommen (2xx) UND entweder (a) der READ-ONLY State-Readback
 *    bestätigt den Ziel-State (play → `playing`/`buffering`, stop → `idle`/`paused`/
 *    `off`/`standby`), ODER (b) der Readback selbst ist fehlgeschlagen/getimeoutet —
 *    Best-effort-Fallback auf die alte optimistische HTTP-200-Phrase: eine Tat wird
 *    NIE allein wegen eines kaputten Lesens als unsicher gemeldet (never-throw an den
 *    Rändern, exakt das `HaToolPort`-Muster).
 *  - [ACCEPTED_STATE_NOT_REACHED]: HA hat angenommen (2xx), der Readback LIEF
 *    erfolgreich (mind. eine gültige State-Antwort), aber der Ziel-State wurde
 *    innerhalb des Settle-Budgets NIE erreicht — ehrliches „angekommen, aber
 *    (noch) nicht da" statt eines stillen Falsch-Jubels (der P2-Bug, den dieser
 *    Typ behebt: grün≠lebt, HTTP-2xx allein bewies nie den echten Zustand).
 */
enum class RadioCallOutcome { NOT_ACCEPTED, VERIFIED, ACCEPTED_STATE_NOT_REACHED }

/**
 * **RadioPort** — die hexagonale Naht für risikofreies Internetradio (Musik
 * Stufe A): einen Stationsnamen zu einer [RadioStation] auflösen ([search])
 * und sie auf einem Abspielziel starten/stoppen ([play]/[stop]).
 *
 * `target` ist das Abspielziel in Domänen-Sprache — beim HA-Adapter die
 * `media_player.*`-Entity-Id des Receivers. Der Kern kennt weder HA noch
 * radio-browser: beides lebt in `:adapters-radio`.
 *
 * **Honesty-Charter:** Implementierungen werfen NIE nach außen. [search]
 * liefert `null`, wenn kein Treffer die Namensähnlichkeits-Schwelle erreicht
 * (kein stilles Falsch-Matching — Andi-Anforderung). [play]/[stop] liefern
 * [RadioCallOutcome.NOT_ACCEPTED], wenn das Ziel nicht erreichbar/konfiguriert
 * ist — der Aufrufer (RadioFastpath) formt daraus die ehrliche warme Antwort.
 * Für [RadioCallOutcome.VERIFIED]/[RadioCallOutcome.ACCEPTED_STATE_NOT_REACHED]
 * s. [RadioCallOutcome]-KDoc.
 */
interface RadioPort {

    /** Löst einen (normalisierten) Stationsnamen auf; unähnlich/nichts ⇒ `null`. */
    fun search(name: String): RadioStation?

    /** Startet [station] auf dem Abspielziel [target]; s. [RadioCallOutcome]. */
    fun play(station: RadioStation, target: String): RadioCallOutcome

    /** Stoppt die Wiedergabe auf [target]; s. [RadioCallOutcome]. */
    fun stop(target: String): RadioCallOutcome

    companion object {
        /** Nie-erreichter Default (Flag-OFF): findet nichts, spielt nichts, stoppt nichts. */
        val NONE: RadioPort = object : RadioPort {
            override fun search(name: String): RadioStation? = null
            override fun play(station: RadioStation, target: String): RadioCallOutcome = RadioCallOutcome.NOT_ACCEPTED
            override fun stop(target: String): RadioCallOutcome = RadioCallOutcome.NOT_ACCEPTED
        }
    }
}
