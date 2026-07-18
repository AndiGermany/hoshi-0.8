package de.hoshi.web

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.reactive.CorsWebFilter
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource

/**
 * **CorsConfig** — die additive CORS-Naht, damit das React-Frontend das Backend
 * DIREKT aus dem Browser (cross-origin) erreicht, statt über den Vite-Dev-Proxy
 * (der auf der macOS-Local-Network-Privacy-Wand für `node` scheitert).
 *
 * **Robuster Ansatz — Reihenfolge vor der Auth-Wand:** Der [CorsWebFilter] läuft
 * mit [CORS_FILTER_ORDER] (= -200) VOR der [PerimeterWebFilter]-Wand
 * ([FILTER_ORDER] = -100). Für einen **Preflight** (`OPTIONS` mit `Origin` +
 * `Access-Control-Request-Method`) trägt der Spring-`CorsWebFilter` die
 * `Access-Control-Allow-*`-Header ein und **kurzschließt die Kette**
 * (`chain.filter` wird NICHT aufgerufen) — der Preflight erreicht die Token-Wand
 * also nie und kann nicht mit 401 abgewiesen werden (ein Preflight trägt nie
 * einen Token). Das ist der Grund für die Filter-Ordnung statt eines
 * `WebFluxConfigurer.addCorsMappings` (das erst auf Handler-Mapping-Ebene greift,
 * also NACH der WebFilter-Wand — der Preflight wäre dort schon 401).
 *
 * **Auth bleibt scharf:** Für eine ECHTE `GET`/`POST` (kein Preflight) hängt der
 * Filter nur die CORS-Header an und reicht den Request via `chain.filter` WEITER
 * → die [PerimeterWebFilter]-Wand entscheidet unverändert über den Token. CORS
 * fügt also nur Header hinzu und schwächt die Token-Auth NICHT. Requests ohne
 * `Origin`-Header (CLI/Loopback) sind keine CORS-Requests und bleiben unberührt.
 *
 * **Konfiguration (Env, analog zu den anderen Naht-Flags):**
 *  - `HOSHI_CORS_ORIGINS` (komma-separiert) — exakt erlaubte Origins. Default
 *    `http://localhost:5180,http://127.0.0.1:5180` ⇒ out-of-the-box Dev.
 *  - Zusätzlich ein fester LAN-Pattern `http://192.168.*.*:5180`
 *    ([LAN_ORIGIN_PATTERN]), damit die LAN-IP des Macs (z.B.
 *    `http://192.168.178.135:5180`) ohne Extra-Config trägt.
 *  - Methoden `GET, POST, OPTIONS`; Header `Authorization, X-Hoshi-Token,
 *    Content-Type, Accept`; keine Expose-Header; `allowCredentials=false`
 *    (der Token reist als Header, nicht als Cookie — kein Credentialed-Request,
 *    darum ist `*`/Pattern-Origins erlaubt und kein Cookie-Leak möglich).
 *  - Gilt für die `/api`-Pfade (Pattern `/api` + `**`).
 */
@Configuration
class CorsConfig {

    @Bean
    @Order(CORS_FILTER_ORDER)
    fun corsWebFilter(
        @Value("\${HOSHI_CORS_ORIGINS:http://localhost:5180,http://127.0.0.1:5180}")
        originsCsv: String,
    ): CorsWebFilter {
        val origins = originsCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }

        val config = CorsConfiguration().apply {
            allowedOrigins = origins
            // LAN-Pattern: die wechselnde Mac-LAN-IP auf :5180 ohne Extra-Config.
            allowedOriginPatterns = listOf(LAN_ORIGIN_PATTERN)
            allowedMethods = listOf(HttpMethod.GET.name(), HttpMethod.POST.name(), HttpMethod.OPTIONS.name())
            allowedHeaders = listOf(
                HttpHeaders.AUTHORIZATION,
                PerimeterWebFilter.X_HOSHI_TOKEN,
                HttpHeaders.CONTENT_TYPE,
                HttpHeaders.ACCEPT,
            )
            // Token ist ein Header, kein Cookie → keine Credentials nötig.
            allowCredentials = false
        }

        val source = UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/api/**", config)
        }
        return CorsWebFilter(source)
    }

    companion object {
        /**
         * VOR der Auth-Wand ([FILTER_ORDER] = -100): so kurzschließt der
         * CORS-Filter den Preflight, bevor die Token-Wand ihn (token-los) 401t.
         */
        const val CORS_FILTER_ORDER = -200

        /** Mac-LAN auf dem FE-Port — die letzten beiden Oktette sind frei. */
        const val LAN_ORIGIN_PATTERN = "http://192.168.*.*:5180"
    }
}
