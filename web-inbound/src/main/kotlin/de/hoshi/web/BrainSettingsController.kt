package de.hoshi.web

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

/**
 * **BrainSettingsController** — der Settings-Rand der Brain-Modell-Wahl
 * (Andi-Auftrag „Brain (LLM)"-Sektion), Muster [TtsSettingsController]. Anders
 * als TTS-Engine/Lookup-Modell gibt es HIER keinen eigenen Laufzeit-Store: das
 * `aktiv`-Feld kommt IMMER LIVE aus `GET {brainBaseUrl}/health` (kein
 * behauptetes Cache-„aktiv" — kein optimistisches UI).
 *
 * Endpoints:
 *  - GET /api/v1/settings/brain → {aktiv, modelle:[{id,label,repo}], status}.
 *    `aktiv` ist die kurze Settings-Id des GEMESSENEN Modells (leer, wenn das
 *    gemessene Modell keinem Whitelist-Eintrag entspricht oder der Sidecar
 *    unreachable ist). `status` ist `"ok"`/`"loading"`/`"unreachable"` — roh aus
 *    der Probe, s. [BrainHealthProbe].
 *  - PUT /api/v1/settings/brain → Body {id}. Unbekannte/leere id (nicht in
 *    [BrainModelCatalog]) ⇒ 422 (unknown-model — HART auf die Zwei-Modell-
 *    Whitelist, 16-GB-Wand); ist der Sidecar nicht erreichbar ODER kennt
 *    `/switch-model` noch nicht (404, wird PARALLEL gebaut) ⇒ 502
 *    (switch-unavailable) mit dem ehrlichen Hinweis „Brain-Sidecar kann noch
 *    kein Umschalten / nicht erreichbar"; sonst 200 + der aktuelle (LIVE
 *    gelesene) Zustand — der Wechsel selbst dauert 60-120s, das FE pollt GET
 *    bis `status=ok` mit dem neuen Modell (KEIN optimistisches UI hier: ein
 *    200 heißt „Wechsel angenommen", NICHT „Wechsel fertig"). Ein `Accepted`
 *    merkt zusätzlich das GEWÄHLTE Modell in [modelStore] — NUR für die
 *    Ops-Drift-Prüfung ([SidecarHealthService]/[JsonFileBrainModelStore]-KDoc),
 *    die LIVE `aktiv`-Zeile hier bleibt unverändert die gemessene `/health`-
 *    Wahrheit. Ein Persist-Fehler wird nur geloggt (kein 500 an den User —
 *    der Sidecar-Wechsel selbst ist bereits angenommen).
 */
@RestController
class BrainSettingsController(
    private val healthProbe: BrainHealthProbe,
    private val switchPort: BrainSwitchModelPort,
    private val modelStore: JsonFileBrainModelStore? = null,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/api/v1/settings/brain")
    fun brainSettings(): Mono<BrainSettingsView> = view()

    @PutMapping("/api/v1/settings/brain")
    fun setModel(@RequestBody body: BrainModelRequest): Mono<ResponseEntity<Any>> {
        val id = body.id?.trim().orEmpty()
        val info = BrainModelCatalog.byId(id)
            ?: return Mono.just(
                ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(SettingsError("unknown-model", id, "Unbekanntes Brain-Modell.")),
            )
        return switchPort.switchModel(info.repo).flatMap<ResponseEntity<Any>> { result ->
            when (result) {
                is BrainSwitchResult.Accepted -> {
                    // GEWÄHLT gewinnt ab jetzt für die Drift-Prüfung — unabhängig davon,
                    // ob der Sidecar den Swap schon fertig hat (s. KDoc oben).
                    runCatching { modelStore?.setSelectedRepo(info.repo) }
                        .onFailure { log.warn("[brain-settings] konnte gewähltes Modell nicht persistieren: {}", it.toString()) }
                    // Readback statt Behauptung: das GET danach zeigt den WIRKLICH
                    // gemessenen Zustand — direkt nach dem Anstoß meist noch das alte
                    // Modell/status=loading, NIE ein vorgetäuschtes "schon fertig".
                    view().map<ResponseEntity<Any>> { ResponseEntity.ok(it) }
                }
                is BrainSwitchResult.Unavailable ->
                    Mono.just(
                        ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                            .body(
                                SettingsError(
                                    "switch-unavailable",
                                    id,
                                    "Brain-Sidecar kann noch kein Umschalten / nicht erreichbar.",
                                ),
                            ),
                    )
            }
        }
    }

    private fun view(): Mono<BrainSettingsView> = healthProbe.check().map { snapshot ->
        BrainSettingsView(
            aktiv = BrainModelCatalog.byRepo(snapshot.model)?.id ?: "",
            modelle = BrainModelCatalog.MODELS.map { BrainModelOption(it.id, it.label, it.repo) },
            status = snapshot.status,
        )
    }
}

/** Eine Zeile der Modell-Auswahl fürs FE-Dropdown. */
data class BrainModelOption(val id: String, val label: String, val repo: String)

/** Wire-Vertrag: das LIVE gemessene aktive Modell + die Whitelist + der rohe Health-Status. */
data class BrainSettingsView(val aktiv: String, val modelle: List<BrainModelOption>, val status: String)

/** PUT-Body: die gewünschte Modell-Id (z.B. `{"id":"e4b"}`). */
data class BrainModelRequest(val id: String?)
