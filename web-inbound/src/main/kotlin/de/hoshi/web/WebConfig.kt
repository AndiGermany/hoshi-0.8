package de.hoshi.web

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.web.reactive.config.ResourceHandlerRegistry
import org.springframework.web.reactive.config.WebFluxConfigurer
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.router
import org.springframework.web.reactive.resource.PathResourceResolver
import reactor.core.publisher.Mono
import java.nio.file.Paths

/**
 * **WebConfig** — FE-Serving aus dem Backend (same-origin), portiert aus Hoshi 0.5
 * `de.hoshi.app.config.WebConfig`. Damit oeffnet Andi die UI wieder ueber
 * `http://ct-106:8082/` statt `npm run dev` lokal: das Backend liefert das
 * gebaute Vite-Bundle, der Browser spricht `/api/...` same-origin (kein CORS,
 * kein Vite-Dev-Proxy, keine node-RAM-Last auf dem Mac).
 *
 * **Flag-gated, default OFF (byte-neutral):**
 *  - Spring-Property `hoshi.web.serve-frontend` (ENV `HOSHI_WEB_SERVE_FRONTEND`).
 *  - Greift `@ConditionalOnProperty` NICHT (Property fehlt oder != `true`), wird die
 *    ganze Konfig-Klasse NICHT geladen ⇒ kein `WebFluxConfigurer`, KEIN
 *    Resource-Handler, kein Root-Router ⇒ exakt das heutige Verhalten (der
 *    `npm run dev`-CORS-Pfad bleibt unberuehrt). Gleiches Muster wie
 *    [WebSocketConfig] (`HOSHI_WS_AUDIO_ENABLED`).
 *  - Erst bei `=true` serviert das Backend die FE aus `hoshi.web.static-dir`
 *    (ENV `HOSHI_WEB_STATIC_DIR`, default `/opt/hoshi-0.8/web`).
 *
 * **Perimeter bleibt scharf:** [de.hoshi.kernel.PerimeterPort] klassifiziert die
 * Praefixe `api/`, `ws/`, `actuator/` (ausser exakt `/api/health`) als geschuetzt
 * und ALLES andere (Root, `index.html`, `assets/`, `favicon`, SPA-Routen) als
 * oeffentlich — unabhaengig von diesem Flag. Die FE-Assets sind also bereits
 * token-frei ladbar (Browser laedt die Seite ohne Token), waehrend der `api`-Pfad
 * token-geschuetzt bleibt. Darum braucht das FE-Serving KEINE Perimeter-Aenderung.
 *
 * **SPA-Pattern:** EIN catch-all Resource-Handler mit custom
 * [PathResourceResolver]. Die reine Entscheidung steckt in [resolveSpa]
 * (unit-testbar): existierende statische Datei → serviert; die Praefixe api, ws,
 * actuator → NIE index.html-Fallback (regulaer 404/Controller); Root/unbekannt →
 * index.html (Client-Routing + Browser-Reload). Der Resource-Handler laeuft mit
 * `LOWEST_PRECEDENCE`, die `@RestController` und das WS-Handler-Mapping
 * (Order -1) haben hoehere Prioritaet — werden also NICHT ueberschrieben.
 */
@Configuration
@ConditionalOnProperty(name = ["hoshi.web.serve-frontend"], havingValue = "true")
class WebConfig(
    @Value("\${hoshi.web.static-dir:/opt/hoshi-0.8/web}")
    private val staticDir: String,
) : WebFluxConfigurer {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Statisches Verzeichnis ohne abschliessenden Slash. */
    private val dir: String = staticDir.trimEnd('/')

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        log.info("[web] FE-Serving aktiv aus {} (catch-all + SPA-fallback)", dir)

        registry.addResourceHandler("/**")
            .addResourceLocations("file:$dir/")
            .resourceChain(true)
            .addResolver(object : PathResourceResolver() {
                override fun getResource(resourcePath: String, location: Resource): Mono<Resource> {
                    // Best-effort: existiert die angefragte statische Datei wirklich?
                    val requested = runCatching {
                        val r = location.createRelative(resourcePath)
                        if (r.exists() && r.isReadable && !r.file.isDirectory) r else null
                    }.getOrNull()
                    val index = location.createRelative("index.html")

                    return when (resolveSpa(resourcePath, requestedIsFile = requested != null)) {
                        SpaResolution.PASS_THROUGH -> Mono.empty()
                        SpaResolution.SERVE_INDEX ->
                            if (index.exists()) Mono.just(index) else Mono.empty()
                        SpaResolution.SERVE_REQUESTED ->
                            Mono.just(requested ?: index)
                    }
                }
            })
    }

    /**
     * Root-Pfad `/` expliziter Handler → index.html. WebFlux routet den leeren
     * Root-Pfad nicht zuverlaessig ueber den Wildcard-Resource-Handler (SPA-Routen
     * wie `/foo` gehen, `/` nicht). Dieser Router schliesst die Luecke (1:1-Essenz
     * aus 0.5). Existiert das Bundle noch nicht (Static-Dir leer) → 503 statt Crash.
     */
    @Bean
    fun rootIndexRouter(): RouterFunction<ServerResponse> {
        val index: Resource = FileSystemResource(Paths.get(dir, "index.html"))
        return router {
            GET("/") {
                if (index.exists()) {
                    ServerResponse.ok().contentType(MediaType.TEXT_HTML).bodyValue(index)
                } else {
                    ServerResponse.status(503).bodyValue("FE-Bundle fehlt")
                }
            }
        }
    }

    /** Ergebnis der reinen SPA-Fallback-Entscheidung. */
    enum class SpaResolution {
        /** Die angefragte statische Datei existiert → unveraendert ausliefern. */
        SERVE_REQUESTED,

        /** Root oder unbekannte SPA-Route → index.html (Client-Routing). */
        SERVE_INDEX,

        /** Praefixe api, ws, actuator → NIE index.html; Controller/404 uebernehmen. */
        PASS_THROUGH,
    }

    companion object {
        /**
         * Reine, seiteneffektfreie SPA-Fallback-Entscheidung — der testbare Kern
         * des Resolvers. `resourcePath` ist der Pfad RELATIV zum Handler, also OHNE
         * fuehrenden Slash (`/api/foo` → `api/foo`, `/` → leer, `/assets/x.js` →
         * `assets/x.js`). `requestedIsFile` = ob die angefragte statische Datei
         * existiert und lesbar (kein Verzeichnis) ist.
         */
        fun resolveSpa(resourcePath: String, requestedIsFile: Boolean): SpaResolution {
            // API/WS/Actuator NICHT auf index.html fallen lassen — die werden eh von
            // @RestController / WS-Mapping bedient; ein unbekannter Pfad hier → 404.
            if (resourcePath.startsWith("api/") ||
                resourcePath.startsWith("ws/") ||
                resourcePath.startsWith("actuator/")
            ) {
                return SpaResolution.PASS_THROUGH
            }
            // Root / leerer Pfad → index.html.
            if (resourcePath.isEmpty() || resourcePath == "/") return SpaResolution.SERVE_INDEX
            // Existierende Datei serviert, sonst SPA-Fallback auf index.html.
            return if (requestedIsFile) SpaResolution.SERVE_REQUESTED else SpaResolution.SERVE_INDEX
        }
    }
}
