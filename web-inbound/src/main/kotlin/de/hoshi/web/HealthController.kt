package de.hoshi.web

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * **HealthController** — minimale Endpunkte zum Beweis der Wand.
 *
 *  - `GET /api/health` → 200 — laut [de.hoshi.kernel.PerimeterPort] die einzige
 *    Ausnahme innerhalb von `/api/` (Basis-Health bleibt öffentlich fürs
 *    LAN-Monitoring), also auch ohne Token erreichbar.
 *  - `GET /api/v1/ping` → 200 — geschützter Pfad, kommt nur mit gültigem Token
 *    durch die [PerimeterWebFilter]-Wand. Ohne/falscher Token ⇒ 401.
 */
@RestController
class HealthController {

    @GetMapping("/api/health")
    fun health(): Map<String, String> = mapOf("status" to "up")

    @GetMapping("/api/v1/ping")
    fun ping(): Map<String, Boolean> = mapOf("pong" to true)
}
