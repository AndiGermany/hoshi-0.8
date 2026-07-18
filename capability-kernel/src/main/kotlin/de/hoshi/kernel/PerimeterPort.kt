package de.hoshi.kernel

import java.security.MessageDigest

/**
 * **PerimeterPort** — die Auth-Wand (ANDI-1), portiert aus Hoshi 0.5
 * `IngressAuthFilter`, ABER reduziert auf die **reine Entscheidungslogik**.
 * KEIN Spring-WebFilter hier (der kommt später in `:web-inbound`); nur die
 * testbare Funktion, die der Filter dann aufruft.
 *
 * Regeln (wenn [enabled]):
 *  - Loopback bleibt IMMER frei (ct-106-lokale Aufrufe, Health).
 *  - Geschützte Pfade (`/api/`, `/ws/`, `/actuator/`, Ausnahme exakt
 *    `/api/health`) brauchen den Bearer-Token. **Konstantzeit-Vergleich**
 *    ([MessageDigest.isEqual]) — kein Timing-Orakel, Token wird nie geloggt.
 *  - Leerer konfigurierter Token + enabled = **fail-closed**: jeder
 *    Nicht-Loopback-Request auf geschützte Pfade ⇒ UNAUTHORIZED.
 *
 * `enabled=false` ⇒ No-op (alles ALLOW) — Verhaltens-Parität zum 0.5-Default-OFF.
 */
class PerimeterPort(
    private val enabled: Boolean,
    private val configuredToken: String,
) {

    sealed class PerimeterDecision {
        /** Durchlassen. */
        data object Allow : PerimeterDecision()

        /** Abweisen — 401-äquivalent. [reason] intern fürs Log, nie der Token. */
        data class Unauthorized(val reason: String) : PerimeterDecision()
    }

    /**
     * Entscheidet über einen einzelnen Request anhand von Pfad, Loopback-Status
     * und präsentiertem Token. Reine Funktion, keine Seiteneffekte.
     *
     * @param presentedToken der vom Client präsentierte Token (Bearer-Header
     *        ODER `?token=` beim WS-Handshake) — bereits ohne „Bearer "-Präfix.
     */
    fun authorize(path: String, isLoopback: Boolean, presentedToken: String?): PerimeterDecision {
        // Default-Pfad: Flag aus ⇒ Wand unsichtbar.
        if (!enabled) return PerimeterDecision.Allow
        // Unkritische Pfade (FE-Assets, `/`, exakt /api/health) sind frei.
        if (!isProtected(path)) return PerimeterDecision.Allow
        // Loopback bleibt immer frei.
        if (isLoopback) return PerimeterDecision.Allow

        return if (tokenOk(presentedToken)) {
            PerimeterDecision.Allow
        } else {
            PerimeterDecision.Unauthorized("kein/ungültiger Token für $path")
        }
    }

    /**
     * Konstantzeit-Vergleich ([MessageDigest.isEqual]) — kein Timing-Orakel.
     * Leerer konfigurierter Token = fail-closed: enabled ohne Token heißt, KEIN
     * Nicht-Loopback-Request kommt durch.
     */
    private fun tokenOk(presented: String?): Boolean {
        if (configuredToken.isBlank() || presented == null) return false
        return MessageDigest.isEqual(
            presented.toByteArray(Charsets.UTF_8),
            configuredToken.toByteArray(Charsets.UTF_8),
        )
    }

    companion object {
        const val WS_PATH_PREFIX = "/ws/"

        /** Exakt-Ausnahme: Basis-Health bleibt für LAN-Monitoring erreichbar. */
        const val HEALTH_PATH = "/api/health"

        /**
         * Geschützt sind API, WebSocket und Actuator. Alles andere (FE-Assets,
         * `/`, SPA-Routen) bleibt frei. Exakt `/api/health` ist die einzige
         * Ausnahme innerhalb von `/api/`.
         */
        fun isProtected(path: String): Boolean {
            if (path == HEALTH_PATH) return false
            return path.startsWith("/api/") ||
                path.startsWith(WS_PATH_PREFIX) ||
                path.startsWith("/actuator/")
        }
    }
}
