package de.hoshi.web

import de.hoshi.adapters.ha.HaHomeRegistryAdapter
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * **HomeRegistryController** — der READ-ONLY Rand von Scheibe 1 des
 * Geräte-Zuordnungs-Konzepts (`.orch-bus/ctx/cowork-research-2026-07-15/
 * 11-geraete-zuordnung-konzept.md`): „Räume = HA-Areas, Gruppen = HA-Labels,
 * HA bleibt die eine Wahrheit — Hoshi wird ihr freundlicher Editor." Nach dem
 * [WeatherTodayController]-Muster: ein schlanker Read-only-`@RestController`
 * hinter der [PerimeterWebFilter]-Wand (alle Pfade unter `/api/v1` sind
 * token-geschützt — ohne gültigen Token ⇒ 401).
 *
 * `GET /api/v1/home/registry` → [de.hoshi.adapters.ha.HomeRegistrySnapshot]
 * (`{areas:[{areaId,label,entities:[{entityId,domain,name,labels}]}],
 * unassigned:[...]}`). Areas OHNE Geräte bleiben in der Liste (ehrlich leer,
 * s. Adapter-KDoc); Entities OHNE Area-Zuordnung landen in `unassigned` —
 * GENAU die „tado-Lücke" sichtbar statt versteckt.
 *
 * **Fährt bewusst UNTER der bestehenden Tat-Decke `HOSHI_HA_ENABLED` mit**
 * (KEIN neues Flag): dieselbe Naht, die den HA-Token für den Tat-Executor
 * ([de.hoshi.web.PipelineConfig.toolPort]) freigibt, gibt jetzt auch diese
 * READ-ONLY Registry frei — ein zusätzliches Flag für ein rein lesendes
 * Feature auf demselben Token/derselben HA-Instanz wäre Bürokratie ohne neues
 * Risiko (Scheibe-1-Auftrag: „begründe im Report, falls du anders
 * entscheidest" — hier bewusst NICHT anders entschieden: Scheibe 1 ist
 * strikt lesend, das Risiko-Delta gegenüber dem bereits scharfen
 * Tat-Executor ist null).
 *
 * Ehrlichkeits-Regeln (kein best-effort-Schlucken wie im Turn-Pfad):
 *  - `HOSHI_HA_ENABLED=false` (Default) ⇒ 404 `home-registry-off` — das
 *    Feature EXISTIERT bei diesem Deploy nicht, KEIN HA-Call. Das FE zeigt
 *    dann den ehrlichen „noch nicht verdrahtet"-Zustand.
 *  - Nie ein erfolgreicher Load (kein/falsches Token, HA nie erreichbar) ⇒
 *    ehrlich 502 `home-registry-unreachable` — NIE erfundene Räume/Geräte.
 *  - War der Adapter mindestens einmal erfolgreich, liefert er den letzten
 *    guten Stand weiter (TTL-Cache-Muster, s. Adapter-KDoc) ⇒ 200, auch wenn
 *    HA GERADE nicht antwortet.
 */
@RestController
class HomeRegistryController(
    private val adapter: HaHomeRegistryAdapter,
    @Value("\${HOSHI_HA_ENABLED:false}") private val haEnabled: Boolean,
) {

    @GetMapping("/api/v1/home/registry")
    fun registry(): ResponseEntity<Any> {
        if (!haEnabled) {
            // Decke zu: das Feature existiert bei diesem Deploy nicht ⇒ 404,
            // kein HA-Call — das FE bleibt bei der ehrlichen „kommt"-Ansicht.
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(SettingsError("home-registry-off", FEATURE_ID, "Home Assistant ist beim Deploy deaktiviert (HOSHI_HA_ENABLED)."))
        }
        val snapshot = adapter.registry()
            ?: return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(SettingsError("home-registry-unreachable", FEATURE_ID, "Home Assistant ist gerade nicht erreichbar."))
        return ResponseEntity.ok(snapshot)
    }

    companion object {
        /** Stabile id für Fehler-Bodies (Pendant zu [WeatherTodayController.FEATURE_ID]). */
        const val FEATURE_ID = "home-registry"
    }
}
