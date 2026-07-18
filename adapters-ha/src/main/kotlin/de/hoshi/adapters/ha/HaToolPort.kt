package de.hoshi.adapters.ha

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.port.ToolPort
import de.hoshi.core.tools.ToolAreas
import de.hoshi.core.tools.ToolCall
import de.hoshi.core.tools.ToolResult
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * **HaToolPort** — der ERSTE reale Tat-Executor (ersetzt den [ToolPort.HONEST_PLACEHOLDER],
 * sobald er flag-gated verdrahtet ist). Spricht Home Assistant über die Standard-
 * REST-Naht und erfüllt den hexagonalen [ToolPort]:
 *
 *   `POST {baseUrl}/api/services/{domain}/{service}`
 *   Header `Authorization: Bearer <token>`, Body = `call.data` (JSON, enthält das
 *   `area_id`-Target + die Service-Params). HTTP 2xx = HA hat akzeptiert.
 *
 * **SYNCHRONER JDK-[HttpClient]** (kein WebClient): der [ToolPort] wird synchron aus
 * der reaktiven [de.hoshi.core.pipeline.TurnOrchestrator]-Kette gerufen — ein
 * `WebClient…block()` würde auf dem Reactor-Thread werfen („blocking not supported")
 * und immer in [ToolResult.Failed] enden (live gemessen 2026-06-26, der erste Funke).
 * Der JDK-Client blockt threadsicher.
 *
 * **Honesty-Charter (kein Fake, never-throw):**
 *  - kein Token ⇒ [ToolResult.NoEffect] OHNE Call (Hoshi behauptet NICHTS getan zu haben).
 *  - HTTP 2xx ⇒ Quittung. Für die **light-Domain mit `area_id`** wird die Quittung
 *    NICHT mehr aus dem bloßen HTTP-200 geraten, sondern aus einem **ehrlichen
 *    State-Readback** (0.5-`HaActionExecutor`-Essenz) geformt: ein READ-ONLY
 *    `POST /api/template` zählt die Lichter der Area (gesamt + „on" + offline) und
 *    sagt, was WIRKLICH passierte — „Licht ist an" (verifiziert) vs „angekommen, aber
 *    nichts ging an — evtl. offline" ([ToolResult.NoEffect]). 0.5-Lehre: der POST-Body
 *    von `light.turn_on/off` ist bei Area-Targeting IMMER `[]` und damit kein Effekt-
 *    Beweis; der Readback ist der einzige ehrliche Beleg.
 *  - **Delta-Baseline (kein Vorher-Lügen):** beim NACKTEN `turn_on` (nur `area_id`,
 *    keine Dimm-/Farb-Params) wird VOR dem Service-Call dieselbe Zählung als Baseline
 *    gelesen und der Erfolg am **Delta** gemessen (`an > baseline`) — „an≥1" allein
 *    lügt, wenn vorher schon irgendeine Lampe brannte (live gemessen 2026-07-09:
 *    „Licht im arbeitszimmer ist an." ohne jede Wirkung, weil eine Hue Play schon an
 *    war und 4 Deckenlampen offline). Waren schon ALLE erreichbaren an ⇒ ehrliches
 *    „ist das Licht schon an"; ging trotz Reserven nichts NEUES an ⇒ [ToolResult.NoEffect]
 *    mit Offline-Zähler. Dimm-/Farb-Calls ändern den an-Zähler nicht erwartbar und
 *    turn_off hat ein absolutes Ziel (an=0) — beide bleiben bei der bisherigen Logik.
 *    Die Baseline ist best-effort: schlägt sie fehl, gilt die alte an≥1-Klassifikation.
 *  - **Settle-Poll (kein Race):** der Readback feuert NICHT einmal sofort nach dem
 *    Service-Call (die Geräte sind dann noch mitten im Umschalten — live gemessen
 *    2026-06-26: „1 von 8 an" obwohl 2 angingen). Stattdessen wird der READ-ONLY Read
 *    in einer kurzen Schleife wiederholt, bis der **erwartete End-Zustand** erreicht ist
 *    (`turn_off` ⇒ an=0; sonst ⇒ an≥1) ODER ein striktes Gesamt-Budget
 *    ([readbackSettleMs], default ~1,8 s, Intervall [readbackPollIntervalMs] ~300 ms)
 *    aufgebraucht ist. **Früh-stop**, sobald das Ziel erreicht ist (Normalfall bleibt
 *    schnell). Klassifiziert werden die ZULETZT gelesenen, gesettelten Counts.
 *  - **Best-effort Readback:** schlägt JEDER Read fehl/timeout (kurzer Timeout,
 *    [readbackTimeoutMs]), FALLBACK auf die bisherige HTTP-200-Ok-Phrase — die Tat
 *    wird NIE als gescheitert gemeldet, nur weil der Readback nicht ging.
 *  - Für die **climate-Domain mit `area_id`** gibt es zwei eigene Ehrlichkeits-Schritte
 *    (dieselbe Grund-Idee wie beim Licht, gespiegelt statt kopiert):
 *    VOR dem Service-Call ein READ-ONLY Existenz-Check (`POST /api/template`, zählt
 *    die climate-Entities der Area, Muster wie der Temperatur-Read) — keine Entity
 *    gefunden ⇒ [ToolResult.NoEffect] „kenne ich kein Thermostat" OHNE jeden
 *    Service-Call (statt der leeren 200-Quittung, die HA für ein Area-Target ohne
 *    Treffer trotzdem liefert). NACH einem akzeptierten `set_temperature` ein
 *    State-Readback (`state_attr('temperature')` der Area-climate-Entities),
 *    settle-gepollt wie beim Licht (kurzes, eigenes Budget statt Sofort-Race): der
 *    Soll-Wert bestätigt ⇒ Ok mit dem Grad-Wert; weicht der gelesene Wert ab oder
 *    gelingt gar kein Read ⇒ ehrliches [ToolResult.NoEffect] „geschickt, aber noch
 *    nicht reagiert" statt einer geratenen Zusage.
 *  - Für **andere nicht-light-Domains** (scene/todo) ODER Calls OHNE `area_id` gibt
 *    es keinen lesbaren Zustand ⇒ bisherige HTTP-200⇒[ToolResult.Ok]-Logik unverändert.
 *  - Fehler/Timeout/Exception beim Service-Call ⇒ [ToolResult.Failed], warm statt kalt
 *    (nie ein Throw nach außen).
 *
 * Der Body wird NUR aus [ToolCall.data] gebaut — das ist die bereits Grant-
 * normalisierte data des [de.hoshi.core.port.CapabilityPort]. domain/service sind
 * kernel-validiert (Slash-Injection geblockt). Das Token wird nie geloggt.
 */
class HaToolPort(
    baseUrl: String,
    private val token: String,
    private val timeoutMs: Long = 5000,
    /**
     * Kurzer Timeout für den READ-ONLY State-Readback (`POST /api/template`).
     * Best-effort: ein träger/abwesender Readback darf die ehrliche Erfolgsmeldung
     * NICHT in ein Failed kippen — er fällt dann auf die HTTP-200-Phrase zurück.
     */
    private val readbackTimeoutMs: Long = 2500,
    /**
     * Gesamt-Budget für den Settle-Poll des Readbacks (über ALLE Versuche). Bewusst
     * gedeckelt: eine Sprach-Quittung darf nicht ewig hängen, muss aber den ECHTEN
     * Zustand treffen statt im Race zu raten. Früh-stop hält den Normalfall schnell.
     * Default 3600 ms: Hue-Einzellampen brauchen live 1–3+ s bis HA den neuen State
     * sieht (gemessen 2026-07-09; die alten 1800 ms verpassten das Delta regelmäßig).
     */
    private val readbackSettleMs: Long = 3600,
    /** Pause zwischen zwei Readback-Versuchen innerhalb des [readbackSettleMs]-Budgets. */
    private val readbackPollIntervalMs: Long = 400,
    /**
     * Eigenes, kürzeres Settle-Budget für den **climate**-Soll-Wert-Readback (Poll-
     * Intervall teilt sich [readbackPollIntervalMs]). Ein Thermostat quittiert den
     * neuen Soll-Wert i.d.R. sehr schnell (kein Dimm-/Übergangs-Fading wie beim
     * Licht) — ein kurzes Budget hält die Sprach-Antwort flott, ohne im Race zu raten.
     */
    private val climateReadbackSettleMs: Long = 1500,
) : ToolPort {
    private val log = LoggerFactory.getLogger(javaClass)
    private val base = baseUrl.trimEnd('/')
    private val mapper = ObjectMapper()
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(timeoutMs))
        .build()

    override fun execute(call: ToolCall): ToolResult {
        // READ-ONLY-Pfad (z.B. „wie warm ist es?"): NUR lesen, NIE schalten — eigener
        // Zweig, der kein /api/services anfasst. Best-effort + honest (nie Throw).
        if (call.read) return readTemperature(call)
        // Kein Token ⇒ ehrlich nichts tun (kein Call, kein Fake).
        if (token.isBlank()) {
            return ToolResult.NoEffect(
                "Ganz ehrlich: ich hab gerade kein HA-Token konfiguriert, also hab ich nichts geschaltet.",
            )
        }
        // Klima-Ehrlichkeit VOR dem Schalten: eine Area OHNE climate-Entity liefert von
        // HA trotzdem eine leere 200-Quittung (Area-Targeting trifft schlicht nichts) —
        // das wäre eine Fake-Zusage. Best-effort-Check: gelingt er nicht (null), wird
        // NICHT blockiert (fail-open), sonst würde ein Lese-Problem die echte Tat kosten.
        val climateArea = areaOf(call)?.takeIf { call.domain == "climate" }
        if (climateArea != null && hasClimateEntity(climateArea) == false) {
            return ToolResult.NoEffect("Im ${ToolAreas.label(climateArea)} kenne ich kein Thermostat.")
        }
        return try {
            // Delta-Baseline VOR dem Schalten (NUR nacktes light.turn_on mit area, s. KDoc):
            // best-effort, ein Fehler hier verhindert die Tat NIE (baseline=null ⇒ Alt-Logik).
            val baseline = if (isBareLightAreaTurnOn(call)) readbackLights(areaOf(call)!!) else null
            val body = mapper.writeValueAsString(call.data)
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$base/api/services/${call.domain}/${call.service}"))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.discarding())
            if (resp.statusCode() in 200..299) {
                // HTTP-200 = HA hat akzeptiert. Für light+area: ehrlich nachlesen,
                // was WIRKLICH passierte; sonst die bisherige „an HA geschickt"-Phrase.
                honestOutcome(call, baseline)
            } else {
                // HTTP-Status zählt, nicht der Body (Token nie loggen).
                log.warn("[ha-tool] HA {}.{} → HTTP {}", call.domain, call.service, resp.statusCode())
                failed()
            }
        } catch (e: Exception) {
            // never-throw: jeder Fehler (Timeout/Netz/Serialisierung) endet warm, nie als Crash.
            log.warn("[ha-tool] HA-Call {}.{} warf: {}", call.domain, call.service, e.message)
            failed()
        }
    }

    /**
     * Formt die Quittung nach einem akzeptierten (HTTP-2xx) Service-Call.
     *
     * Nur für die **light-Domain mit `area_id`** wird ein READ-ONLY Readback gemacht
     * und das echte Outcome klassifiziert. Für alle anderen Fälle (nicht-light, oder
     * kein area_id) gibt es keinen lesbaren Zustand → bisherige Ok-Phrase.
     */
    private fun honestOutcome(call: ToolCall, baseline: LightCounts?): ToolResult {
        val area = areaOf(call)
        if (call.domain == "climate" && call.service == "set_temperature" && area != null) {
            return honestClimateOutcome(call, area)
        }
        if (call.domain != "light" || area == null) {
            return ToolResult.Ok(okPhrase(call))
        }
        // Settle-Poll statt eines sofortigen (im Race ratenden) Reads: lies bis das
        // erwartete Ziel erreicht ist oder das Budget aufgebraucht ist (Best-effort).
        // Schlägt JEDER Read fehl/timeout → null → Fallback auf die HTTP-200-Phrase;
        // die Tat wird NIE als gescheitert gemeldet, nur weil das Lesen nicht ging.
        val counts = readbackSettled(area, call.service, baseline) ?: return ToolResult.Ok(okPhrase(call))
        return classifyLightOutcome(call, area, counts, baseline)
    }

    /**
     * Nur der NACKTE area-`turn_on` bekommt die Delta-Baseline: Dimm-/Farb-Calls
     * (brightness/color/…) ändern den an-Zähler nicht erwartbar, `turn_off` hat ein
     * absolutes Ziel (an=0) — Delta wäre dort falsch-negativ bzw. überflüssig.
     */
    private fun isBareLightAreaTurnOn(call: ToolCall): Boolean =
        call.domain == "light" && call.service == "turn_on" && areaOf(call) != null &&
            call.data.keys.all { it == "area_id" }

    /**
     * Settle-Poll: wiederholt den READ-ONLY [readbackLights] bis der erwartete
     * End-Zustand erreicht ist ([targetReached]) ODER das Gesamt-Budget
     * [readbackSettleMs] verbraucht ist (max ~6 Versuche im Default). **Früh-stop**,
     * sobald das Ziel erreicht ist — der Normalfall (Gerät reagiert schnell) bleibt
     * schnell. Striktes Zeit-/Versuchs-Budget, KEIN ungedeckelter Loop.
     *
     * Rückgabe = die ZULETZT erfolgreich gelesenen Counts (gesettelt oder Budget-Ende),
     * oder `null` wenn JEDER Versuch fehlschlug (→ Caller fällt auf die 200-Phrase zurück).
     * Never-throw: auch eine Interruption beim Warten endet warm mit dem letzten Stand.
     */
    private fun readbackSettled(area: String, service: String, baseline: LightCounts?): LightCounts? {
        val deadlineNanos = System.nanoTime() + Duration.ofMillis(readbackSettleMs).toNanos()
        val intervalNanos = Duration.ofMillis(readbackPollIntervalMs).toNanos()
        var last: LightCounts? = null
        while (true) {
            val counts = readbackLights(area)
            if (counts != null) {
                last = counts
                // Ziel erreicht → früh-stop (schnell, wenn das Gerät schnell reagiert).
                if (targetReached(service, counts, baseline)) return last
            }
            // Reicht das Budget noch für ein volles Intervall + einen weiteren Read? Sonst raus.
            if (System.nanoTime() + intervalNanos >= deadlineNanos) return last
            try {
                Thread.sleep(readbackPollIntervalMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return last
            }
        }
    }

    /**
     * Ist der erwartete End-Zustand aus [ToolCall.service] erreicht?
     *  - `turn_off` ⇒ Ziel „an=0".
     *  - nackter turn_on MIT [baseline] ⇒ Ziel „an > baseline.an" (echtes Delta) —
     *    ODER es KANN nichts mehr angehen (alle erreichbaren brannten schon:
     *    `baseline.an ≥ erreichbar`), dann ist sofort gesettelt.
     *  - sonst (Dimm/Farbe, oder Baseline fehlgeschlagen) ⇒ Ziel „an≥1" (Alt-Logik).
     *  - `total=0` (keine Lampen) gilt immer als gesettelt, weil da nichts mehr
     *    umschalten KANN (vermeidet sinnloses Voll-Budget).
     */
    private fun targetReached(service: String, counts: LightCounts, baseline: LightCounts?): Boolean =
        when {
            service == "turn_off" -> counts.on == 0
            counts.total == 0 -> true
            baseline != null -> counts.on > baseline.on || baseline.on >= reachable(counts)
            else -> counts.on >= 1
        }

    /** Wie viele Lampen der Area überhaupt schalten KÖNNEN (gesamt minus offline). */
    private fun reachable(counts: LightCounts): Int =
        (counts.total - counts.unavailable).coerceAtLeast(0)

    /**
     * READ-ONLY State-Readback via HA-Template (`POST /api/template`, KEINE Schaltung):
     * rendert `gesamt|an` für die Lichter der Area. Genau dieses Format hat sich live
     * bewährt (0.5). Best-effort: jeder Fehler/Timeout/Non-2xx ⇒ `null` (never-throw),
     * der Caller fällt dann auf die HTTP-200-Phrase zurück.
     */
    private fun readbackLights(area: String): LightCounts? {
        return try {
            // Template-Text (nach JSON-/Jinja-Parsing): zähle Area-Lichter, davon „on",
            // davon offline (unavailable/unknown — z.B. am Wandschalter stromlos).
            val template =
                "{% set ids = area_entities('$area') | select('match','^light\\.') | list %}" +
                    "{{ ids|count }}|{{ ids | map('states') | select('eq','on') | list | count }}" +
                    "|{{ ids | map('states') | select('in',['unavailable','unknown']) | list | count }}"
            val payload = mapper.writeValueAsString(mapOf("template" to template))
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$base/api/template"))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(readbackTimeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                log.warn("[ha-tool] Readback /api/template → HTTP {} (Fallback auf 200-Phrase)", resp.statusCode())
                return null
            }
            parseCounts(resp.body())
        } catch (e: Exception) {
            log.warn("[ha-tool] Readback /api/template warf: {} (Fallback auf 200-Phrase)", e.message)
            null
        }
    }

    /**
     * Parst die `gesamt|an[|offline]`-Antwort des Readback-Templates; alles Unerwartete
     * ⇒ `null`. Das 2-Teile-Format bleibt akzeptiert (offline=0), damit ein gemischter
     * Alt/Neu-Stand nie in den Fallback kippt.
     */
    private fun parseCounts(raw: String?): LightCounts? {
        val parts = raw?.trim()?.split("|") ?: return null
        if (parts.size !in 2..3) return null
        val total = parts[0].trim().toIntOrNull() ?: return null
        val on = parts[1].trim().toIntOrNull() ?: return null
        val unavailable = if (parts.size == 3) parts[2].trim().toIntOrNull() ?: return null else 0
        return LightCounts(total = total, on = on, unavailable = unavailable)
    }

    /**
     * Ergebnis des Readbacks: wie viele Lichter die Area hat ([total]), wie viele „on"
     * sind ([on]) und wie viele offline/unknown sind ([unavailable], z.B. Wandschalter aus).
     */
    private data class LightCounts(val total: Int, val on: Int, val unavailable: Int = 0)

    /**
     * Ehrliche Outcome-Klassifikation aus dem Readback (`gesamt|an|offline`):
     *
     *  - `turn_off`: an=0 ⇒ Ok „ist aus"; an>0 ⇒ NoEffect „ein paar sind noch an".
     *  - nackter turn_on MIT [baseline] (Delta-Ehrlichkeit, 2026-07-09):
     *    an>baseline ⇒ Ok „ist an" (Tat BEWIESEN) · alle erreichbaren brannten schon
     *    ⇒ Ok „ist das Licht schon an" · an unverändert trotz Reserven ⇒ NoEffect
     *    „neu angegangen ist nichts" · an=0 ⇒ NoEffect „kein Licht ging an" — die
     *    NoEffect-Phrasen nennen den Offline-Zähler, wenn Lampen unavailable sind.
     *  - sonst (dimmen / Farbe / Baseline fehlgeschlagen):
     *    gesamt=0 ⇒ NoEffect „keine Lampen gefunden"; an≥1 ⇒ Ok „ist an";
     *    an=0 (aber gesamt>0) ⇒ NoEffect „angekommen, aber nichts ging an".
     */
    private fun classifyLightOutcome(
        call: ToolCall,
        area: String,
        counts: LightCounts,
        baseline: LightCounts?,
    ): ToolResult {
        log.info(
            "[ha-tool] readback area={} service={} baseline={} → gesamt={} an={} offline={}",
            area, call.service, baseline?.on ?: "-", counts.total, counts.on, counts.unavailable,
        )
        if (call.service == "turn_off") {
            return if (counts.on == 0) {
                ToolResult.Ok("Licht im $area ist aus.")
            } else {
                ToolResult.NoEffect("Ein paar Lampen im $area sind noch an — die haben evtl. nicht reagiert.")
            }
        }
        val offlineHint = if (counts.unavailable > 0) {
            " — ${counts.unavailable} Lampen sind gerade nicht erreichbar (evtl. am Schalter aus)."
        } else {
            " — vielleicht sind die Lampen offline."
        }
        return when {
            counts.total == 0 -> ToolResult.NoEffect("Im $area hab ich gar keine Lampen gefunden.")
            // Bewusst OHNE genaue Zahl: der Poll früh-stoppt beim ersten Delta-/„on"-
            // Treffer, eine weitere Lampe kann Millisekunden später kommen → „1 von 8"
            // wäre ein Unterzähl-Race (live gemessen). „ist an" ist verifiziert + ehrlich.
            baseline != null && counts.on > baseline.on -> ToolResult.Ok("Licht im $area ist an.")
            baseline != null && counts.on >= 1 && reachable(counts) > 0 && baseline.on >= reachable(counts) ->
                ToolResult.Ok("Im $area ist das Licht schon an.")
            baseline != null && counts.on >= 1 -> ToolResult.NoEffect(
                "Im $area brennt zwar schon Licht, aber neu angegangen ist nichts$offlineHint",
            )
            baseline == null && counts.on >= 1 -> ToolResult.Ok("Licht im $area ist an.")
            else -> ToolResult.NoEffect(
                "Ich hab's an Home Assistant geschickt, aber im $area ging kein Licht an$offlineHint",
            )
        }
    }

    // ── Klima-Ehrlichkeit: Existenz-Check + Soll-Wert-Readback ──────────────────

    /**
     * READ-ONLY Existenz-Check (`POST /api/template`, KEINE Schaltung): hat die Area
     * überhaupt eine climate-Entity? Muster wie der Temperatur-Read (`area_entities`
     * gefiltert auf `climate.*`), NUR gezählt statt gelesen.
     *
     * Best-effort: schlägt der Read fehl/timeout, ist der Zustand UNBEKANNT ⇒ `null`
     * — der Caller lässt die Tat dann durch (fail-open), statt eine ECHTE Anfrage an
     * einem Lese-Problem scheitern zu lassen. Nur ein SICHER gelesenes „0" liefert `false`.
     */
    private fun hasClimateEntity(area: String): Boolean? {
        val count = try {
            val template =
                "{% set ids = area_entities('$area') | select('match','^climate\\.') | list %}{{ ids | count }}"
            val payload = mapper.writeValueAsString(mapOf("template" to template))
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$base/api/template"))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(readbackTimeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                log.warn("[ha-tool] Climate-Existenz-Check /api/template → HTTP {}", resp.statusCode())
                return null
            }
            resp.body()?.trim()?.toIntOrNull()
        } catch (e: Exception) {
            log.warn("[ha-tool] Climate-Existenz-Check warf: {}", e.message)
            null
        }
        return count?.let { it > 0 }
    }

    /**
     * Formt die Quittung nach einem akzeptierten (HTTP-2xx) `climate.set_temperature`.
     * Settle-gepollt bis der geschriebene Soll-Wert bestätigt ist (Budget
     * [climateReadbackSettleMs]) ODER das Budget aufgebraucht ist. Bestätigt ⇒ Ok mit
     * dem Grad-Wert; kein/abweichender Read ⇒ ehrliches NoEffect statt Rateglück.
     */
    private fun honestClimateOutcome(call: ToolCall, area: String): ToolResult {
        val target = targetTemperature(call) ?: return ToolResult.Ok(okPhrase(call))
        val value = readbackClimateSettled(area, target)
        return if (value != null && Math.round(value).toInt() == target) {
            ToolResult.Ok("Heizung im ${ToolAreas.label(area)} auf $target Grad.")
        } else {
            ToolResult.NoEffect("Hab's geschickt, die Heizung hat noch nicht reagiert.")
        }
    }

    /** Die angeforderte Zieltemperatur aus der normalisierten data, tolerant im Zahlentyp. */
    private fun targetTemperature(call: ToolCall): Int? =
        when (val t = call.data["temperature"]) {
            is Int -> t
            is Number -> t.toInt()
            is String -> t.toIntOrNull()
            else -> null
        }

    /**
     * Settle-Poll (spiegelt [readbackSettled] für Licht, eigenes kürzeres Budget
     * [climateReadbackSettleMs]): wiederholt den READ-ONLY [readbackClimateTemperature],
     * bis der gelesene Wert [target] trifft ODER das Budget aufgebraucht ist.
     * Früh-stop sobald bestätigt. Rückgabe = zuletzt gelesener Wert (oder `null`,
     * wenn JEDER Read fehlschlug) — der Caller entscheidet dann Ok vs. ehrliches NoEffect.
     */
    private fun readbackClimateSettled(area: String, target: Int): Double? {
        val deadlineNanos = System.nanoTime() + Duration.ofMillis(climateReadbackSettleMs).toNanos()
        val intervalNanos = Duration.ofMillis(readbackPollIntervalMs).toNanos()
        var last: Double? = null
        while (true) {
            val value = readbackClimateTemperature(area)
            if (value != null) {
                last = value
                if (Math.round(value).toInt() == target) return last
            }
            if (System.nanoTime() + intervalNanos >= deadlineNanos) return last
            try {
                Thread.sleep(readbackPollIntervalMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return last
            }
        }
    }

    /**
     * READ-ONLY State-Readback (`POST /api/template`, KEINE Schaltung): liest den
     * gesetzten Soll-Wert (`state_attr('temperature')`) der ERSTEN climate-Entity
     * der Area. Best-effort: jeder Fehler/Timeout/Non-2xx/`none` ⇒ `null`.
     */
    private fun readbackClimateTemperature(area: String): Double? {
        return try {
            val template =
                "{% set ids = area_entities('$area') | select('match','^climate\\.') | list %}" +
                    "{% set t = ids | map('state_attr','temperature') | reject('none') | list %}" +
                    "{% if t | count > 0 %}{{ t | first }}{% else %}none{% endif %}"
            val payload = mapper.writeValueAsString(mapOf("template" to template))
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$base/api/template"))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(readbackTimeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                log.warn("[ha-tool] Climate-Readback /api/template → HTTP {}", resp.statusCode())
                return null
            }
            parseTemperature(resp.body())
        } catch (e: Exception) {
            log.warn("[ha-tool] Climate-Readback /api/template warf: {}", e.message)
            null
        }
    }

    /** Die Ziel-Area aus der normalisierten data, oder `null` wenn keine getargetet ist. */
    private fun areaOf(call: ToolCall): String? =
        (call.data["area_id"] as? String)?.takeIf { it.isNotBlank() }

    /** Warme, EHRLICHE Quittung — behauptet nur „an HA geschickt", nennt die Ziel-Area falls vorhanden. */
    private fun okPhrase(call: ToolCall): String {
        val area = areaOf(call)
        return if (area != null) {
            "Ist erledigt — ich hab's an die Geräte im $area geschickt."
        } else {
            "Ist erledigt — ich hab's an Home Assistant geschickt."
        }
    }

    private fun failed(): ToolResult =
        ToolResult.Failed("Hat nicht geklappt — Home Assistant hat gerade nicht reagiert.")

    // ── READ-ONLY: Ist-Temperatur lesen ─────────────────────────────────────────

    /**
     * Liest die **Ist-Temperatur** READ-ONLY über HA-Template (`POST /api/template`,
     * KEINE Schaltung) und formt eine warme deutsche Antwort:
     *
     *  - kein Token ⇒ ehrlicher [ToolResult.NoEffect] (kein Call).
     *  - Area genannt (`data[area_id]`) ⇒ die `climate.current_temperature` der Area
     *    (Fallback: ein Temperatur-`sensor.*` der Area) ⇒ „Im <Area> sind es gerade X Grad."
     *  - kein Raum ⇒ Haus-Durchschnitt aller `climate`-Ist-Temperaturen ⇒
     *    „Im Haus sind es gerade durchschnittlich X Grad."
     *  - kein/kein numerischer Wert (`none`) ⇒ ehrlich [ToolResult.NoEffect]
     *    („Dafür hab ich gerade keinen Wert.").
     *  - HA-Fehler/Timeout/Non-2xx/Exception ⇒ [ToolResult.Failed], warm statt kalt
     *    (never-throw — ein Lese-Problem crasht den Turn nie).
     */
    private fun readTemperature(call: ToolCall): ToolResult {
        if (token.isBlank()) {
            return ToolResult.NoEffect(
                "Ganz ehrlich: ich hab gerade kein HA-Token konfiguriert, also komm ich nicht an die Temperatur ran.",
            )
        }
        val area = (call.data["area_id"] as? String)?.takeIf { it.isNotBlank() }
        val template = if (area != null) areaTemperatureTemplate(area) else houseTemperatureTemplate()
        return try {
            val payload = mapper.writeValueAsString(mapOf("template" to template))
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$base/api/template"))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(readbackTimeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                log.warn("[ha-tool] Temperatur-Read /api/template → HTTP {}", resp.statusCode())
                return readFailed()
            }
            val value = parseTemperature(resp.body())
                ?: return ToolResult.NoEffect("Dafür hab ich gerade keinen Wert.")
            ToolResult.Ok(temperaturePhrase(area, value))
        } catch (e: Exception) {
            log.warn("[ha-tool] Temperatur-Read warf: {}", e.message)
            readFailed()
        }
    }

    /** Parst die gerenderte Template-Antwort zu Grad-Celsius; `none`/leer/nicht-numerisch ⇒ `null`. */
    private fun parseTemperature(raw: String?): Double? {
        val s = raw?.trim()?.replace(',', '.') ?: return null
        if (s.isEmpty() || s.equals("none", ignoreCase = true) || s.equals("unknown", ignoreCase = true)) return null
        return s.toDoubleOrNull()
    }

    /** Warme deutsche Antwort; Komma-Dezimal, ganze Werte ohne „,0" (21.0 → „21", 21.5 → „21,5"). */
    private fun temperaturePhrase(area: String?, value: Double): String {
        val rounded = Math.round(value * 10.0) / 10.0
        val num = if (rounded % 1.0 == 0.0) rounded.toLong().toString()
        else rounded.toString().replace('.', ',')
        return if (area != null) {
            "Im ${ToolAreas.label(area)} sind es gerade $num Grad."
        } else {
            "Im Haus sind es gerade durchschnittlich $num Grad."
        }
    }

    private fun readFailed(): ToolResult =
        ToolResult.Failed("Ich komm gerade nicht an die Temperatur ran — versuch's gleich nochmal.")

    /**
     * READ-ONLY Jinja-Template: die Ist-Temperatur EINER Area — erst `climate.*`
     * (`current_temperature`), sonst ein Temperatur-`sensor.*` der Area; nichts ⇒ `none`.
     */
    private fun areaTemperatureTemplate(area: String): String =
        "{% set ids = area_entities('$area') | list %}" +
            "{% set ct = ids | select('match','^climate\\.') | map('state_attr','current_temperature') | reject('none') | list %}" +
            "{% if ct | count > 0 %}{{ ct | first | round(1) }}" +
            "{% else %}" +
            "{% set st = ids | select('match','^sensor\\.') | select('is_state_attr','device_class','temperature') | map('states') | reject('in',['unknown','unavailable','none']) | list %}" +
            "{% if st | count > 0 %}{{ st | first }}{% else %}none{% endif %}" +
            "{% endif %}"

    /** READ-ONLY Jinja-Template: Haus-Durchschnitt aller `climate`-Ist-Temperaturen; keine ⇒ `none`. */
    private fun houseTemperatureTemplate(): String =
        "{% set temps = states.climate | map(attribute='attributes.current_temperature') | reject('none') | list %}" +
            "{{ (temps | average | round(1)) if (temps | count) > 0 else 'none' }}"
}
