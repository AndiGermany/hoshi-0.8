package de.hoshi.adapters.radio

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.port.RadioCallOutcome
import de.hoshi.core.port.RadioPort
import de.hoshi.core.port.RadioStation
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * **HaRadioPort** — die [RadioPort]-Implementierung der Stufe A:
 *
 *  - [search] delegiert an den [RadioBrowserAdapter] (Name → Stream-URL,
 *    Andi-Schwelle inklusive).
 *  - [play]/[stop] sprechen **Home Assistant** über die Standard-REST-Naht
 *    (exakt das [de.hoshi.adapters.ha.HaToolPort]-Muster: synchroner
 *    JDK-[HttpClient], never-throw, Token wird NIE geloggt):
 *
 *      `POST {base}/api/services/media_player/play_media`
 *      Body `{"entity_id": target, "media_content_id": streamUrl,
 *             "media_content_type": "music"}`
 *      `POST {base}/api/services/media_player/media_stop`
 *      Body `{"entity_id": target}`
 *
 * `target` ist die `media_player.*`-Entity-Id des Abspielgeräts. **Befund
 * 2026-07-02:** der Yamaha RX-V6A ist (noch) NICHT als HA-media_player da —
 * dieser Adapter ist bewusst generisch: sobald Andi die MusicCast-Integration
 * in HA anlegt (oder ein anderes Ziel wählt), greift er unverändert.
 *
 * **P2-Bug-Fix 2026-07-11 (Ehrlichkeit, grün≠lebt):** [play]/[stop] meldeten
 * bisher „läuft"/„aus" ALLEIN aus dem HTTP-2xx des Service-Calls — kein Beweis,
 * dass der Receiver wirklich reagiert hat (offline/falscher Eingang/toter
 * Stream blieben unsichtbar). Jetzt folgt nach einem akzeptierten Call ein
 * READ-ONLY **State-Readback** (`GET /api/states/{target}`, liest NUR das
 * `.state`-Feld — anders als [de.hoshi.adapters.ha.HaToolPort]s Area-Template
 * reicht hier ein simpler Single-Entity-Read, weil `target` schon die konkrete
 * Entity-Id ist und nichts über eine Area aggregiert werden muss) mit kurzem
 * **Settle-Poll** (Budget [readbackSettleMs], Intervall [readbackPollIntervalMs],
 * Früh-stop sobald der Ziel-State erreicht ist):
 *  - play-Ziel: State `playing` (`buffering` zählt als „im Anlauf" mit).
 *  - stop-Ziel: State `idle`/`paused`/`off`/`standby`.
 *
 * **Honesty-Charter (never-throw, [RadioCallOutcome] trägt das Ergebnis):**
 *  - kein Token / leeres Target ⇒ [RadioCallOutcome.NOT_ACCEPTED] OHNE Call
 *    (Hoshi behauptet nichts).
 *  - Service-Call-Fehler/Timeout/Non-2xx ⇒ [RadioCallOutcome.NOT_ACCEPTED],
 *    KEIN Readback (es gibt nichts zu bestätigen).
 *  - Service-Call akzeptiert (2xx) + Readback erreicht den Ziel-State (früh
 *    oder binnen Budget) ⇒ [RadioCallOutcome.VERIFIED].
 *  - Service-Call akzeptiert (2xx) + Readback lief (mind. 1 gültige Antwort),
 *    Ziel-State aber NIE erreicht ⇒ [RadioCallOutcome.ACCEPTED_STATE_NOT_REACHED]
 *    — ehrlich, statt aus dem HTTP-2xx „läuft" zu erfinden.
 *  - Service-Call akzeptiert (2xx), aber JEDER Readback-Versuch scheitert
 *    (Netz/Timeout/kaputte Antwort) ⇒ Best-effort-**Fallback** auf
 *    [RadioCallOutcome.VERIFIED] — ein kaputtes Lesen darf die akzeptierte
 *    Tat NIE in eine unsichere/gescheiterte Meldung kippen (never-throw an
 *    den Rändern).
 */
class HaRadioPort(
    private val browser: RadioBrowserAdapter,
    baseUrl: String,
    private val token: String,
    private val timeoutMs: Long = 5000,
    /**
     * Kurzer Timeout für EINEN READ-ONLY State-Read (`GET /api/states/{target}`).
     * Best-effort: ein einzelner träger Read darf den Settle-Poll nicht blockieren —
     * er zählt einfach als gescheiterter Versuch (s. [readState]).
     */
    private val readbackTimeoutMs: Long = 1200,
    /**
     * Gesamt-Budget für den Settle-Poll (über ALLE Versuche). Bewusst gedeckelt
     * (Andi-Vorgabe ~1,5–2,5 s): eine Sprach-Quittung darf nicht ewig hängen, muss
     * aber den ECHTEN Zustand treffen statt aus dem bloßen HTTP-2xx zu raten.
     * Früh-stop ([readbackSettled]) hält den Normalfall (Gerät reagiert schnell) schnell.
     */
    private val readbackSettleMs: Long = 2000,
    /** Pause zwischen zwei Readback-Versuchen innerhalb des [readbackSettleMs]-Budgets. */
    private val readbackPollIntervalMs: Long = 350,
) : RadioPort {
    private val log = LoggerFactory.getLogger(javaClass)
    private val base = baseUrl.trimEnd('/')
    private val mapper = ObjectMapper()
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(timeoutMs))
        .build()

    override fun search(name: String): RadioStation? = browser.search(name)

    override fun play(station: RadioStation, target: String): RadioCallOutcome =
        callService(
            service = "play_media",
            body = mapOf(
                "entity_id" to target,
                "media_content_id" to station.streamUrl,
                "media_content_type" to "music",
            ),
            target = target,
            targetStates = PLAYING_STATES,
        )

    override fun stop(target: String): RadioCallOutcome =
        callService(
            service = "media_stop",
            body = mapOf("entity_id" to target),
            target = target,
            targetStates = STOPPED_STATES,
        )

    /**
     * POST an `media_player/{service}`; ohne HTTP-2xx ⇒ [RadioCallOutcome.NOT_ACCEPTED]
     * (never-throw, Token nie geloggt). Bei 2xx folgt der READ-ONLY [readbackSettled]
     * gegen [targetStates] — die Tat selbst wird durch den Readback NIE zu einem
     * Fehlschlag umklassifiziert (s. Klassen-KDoc, Fallback-Regel).
     */
    private fun callService(
        service: String,
        body: Map<String, Any?>,
        target: String,
        targetStates: Set<String>,
    ): RadioCallOutcome {
        // Ehrlich nichts tun statt blind zu feuern: ohne Token/Ziel gibt es keinen Call.
        if (token.isBlank() || target.isBlank()) {
            log.warn("[ha-radio] {} übersprungen: {} fehlt", service, if (token.isBlank()) "Token" else "Target")
            return RadioCallOutcome.NOT_ACCEPTED
        }
        val accepted = try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$base/api/services/media_player/$service"))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.discarding())
            val ok = resp.statusCode() in 200..299
            if (!ok) log.warn("[ha-radio] media_player.{} → HTTP {}", service, resp.statusCode())
            ok
        } catch (e: Exception) {
            // never-throw: jeder Fehler (Timeout/Netz/Serialisierung) endet ehrlich als false.
            log.warn("[ha-radio] media_player.{} warf: {}", service, e.message)
            false
        }
        if (!accepted) return RadioCallOutcome.NOT_ACCEPTED
        return readbackSettled(target, targetStates)
    }

    /**
     * Settle-Poll: liest den `media_player`-State via [readState] wiederholt, bis er
     * in [targetStates] liegt (**Früh-stop** ⇒ [RadioCallOutcome.VERIFIED]) ODER das
     * Gesamt-Budget [readbackSettleMs] verbraucht ist (striktes Budget, KEIN
     * ungedeckelter Loop). Never-throw: auch eine Interruption beim Warten endet warm.
     *
     * Klassifikation am Budget-Ende:
     *  - mindestens EIN Read lieferte einen gültigen (wenn auch falschen) State ⇒
     *    [RadioCallOutcome.ACCEPTED_STATE_NOT_REACHED] (ehrlich — wir WISSEN, dass das
     *    Ziel nicht erreicht wurde).
     *  - JEDER Read scheiterte (Timeout/Netz/kaputte Antwort) ⇒ best-effort
     *    [RadioCallOutcome.VERIFIED] (wir wissen NICHTS über den State — Fallback auf
     *    die alte optimistische HTTP-200-Phrase statt eines falschen „nicht erreicht").
     */
    private fun readbackSettled(target: String, targetStates: Set<String>): RadioCallOutcome {
        val deadlineNanos = System.nanoTime() + Duration.ofMillis(readbackSettleMs).toNanos()
        val intervalNanos = Duration.ofMillis(readbackPollIntervalMs).toNanos()
        var sawValidReading = false
        while (true) {
            val state = readState(target)
            if (state != null) {
                sawValidReading = true
                // Ziel erreicht → früh-stop (schnell, wenn der Receiver schnell reagiert).
                if (state in targetStates) return RadioCallOutcome.VERIFIED
            }
            // Reicht das Budget noch für ein volles Intervall + einen weiteren Read? Sonst raus.
            if (System.nanoTime() + intervalNanos >= deadlineNanos) {
                return if (sawValidReading) RadioCallOutcome.ACCEPTED_STATE_NOT_REACHED else RadioCallOutcome.VERIFIED
            }
            try {
                Thread.sleep(readbackPollIntervalMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return if (sawValidReading) RadioCallOutcome.ACCEPTED_STATE_NOT_REACHED else RadioCallOutcome.VERIFIED
            }
        }
    }

    /**
     * READ-ONLY State-Read: `GET /api/states/{target}` (KEINE Schaltung), liefert
     * den `.state`-Wert der Entity (z.B. `"playing"`) oder `null` bei JEDEM
     * Fehler/Timeout/Non-2xx/kaputtem JSON (never-throw, best-effort — der Caller
     * behandelt `null` als „dieser Versuch zählt nicht, weiter/aufgeben").
     */
    private fun readState(target: String): String? {
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$base/api/states/$target"))
                .header("Authorization", "Bearer $token")
                .timeout(Duration.ofMillis(readbackTimeoutMs))
                .GET()
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                log.warn("[ha-radio] Readback /api/states/{} → HTTP {}", target, resp.statusCode())
                return null
            }
            mapper.readTree(resp.body()).path("state").asText("").trim().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            log.warn("[ha-radio] Readback /api/states/{} warf: {}", target, e.message)
            null
        }
    }

    companion object {
        /** HA `media_player`-States, die „spielt wirklich" bedeuten (`buffering` = im Anlauf, zählt mit). */
        private val PLAYING_STATES = setOf("playing", "buffering")

        /** HA `media_player`-States, die „spielt wirklich NICHT (mehr)" bedeuten. */
        private val STOPPED_STATES = setOf("idle", "paused", "off", "standby")
    }
}
