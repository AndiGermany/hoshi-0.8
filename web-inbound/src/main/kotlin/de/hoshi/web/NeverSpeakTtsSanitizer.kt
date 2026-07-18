package de.hoshi.web

import de.hoshi.core.port.TtsSanitizePort

/**
 * **NeverSpeakTtsSanitizer** — die konkrete [TtsSanitizePort]-Impl fuer den
 * Cloud-TTS-Egress (`OpenAiTtsAdapter` → `api.openai.com`). Schliesst das P0-Leck:
 * der rohe Antworttext darf NICHT mehr ungefiltert an die Cloud, aber das Audio
 * soll warm bleiben.
 *
 * **Maskiert (Never-Speak — diese Spans duerfen NIE in einen Cloud-Call):**
 *  - Token / API-Key (JWT `eyJ…`, `Bearer …`, `sk-…`, lange Hex-Secrets) → `[TOKEN]`
 *  - URL (`http(s)://…`) → `[URL]`
 *  - Private LAN-IP (RFC-1918 + Loopback) → `[IP]`
 *  - UUID (8-4-4-4-12) → `[ID]`
 *  - HA-Entity-ID (`domain.objekt`, z.B. `light.wohnzimmer`) → `[ID]`
 *
 * **BEHAELT (sonst kaltes/kaputtes Audio):** Namen und normalen Inhalt. Bewusst
 * `sanitize`-Semantik (maskieren), NICHT `guard` (blocken) — eine warme Memory-
 * Antwort mit einem Namen darf gesprochen werden, nur die strukturierten Geheimnisse
 * fallen raus.
 *
 * Die Kategorien spiegeln die Never-Speak-/Hard-Block-Muster des
 * `capability-kernel`-`EgressPort` (Token/IP leben dort im `guard`-Block, URL/UUID/
 * Entity-ID in `sanitize`) — hier in EINEN maskierenden Pass gefaltet. Bewusst
 * regex-basiert, kein Modell sieht den Klartext. Reihenfolge: spezifischste Geheimnisse
 * zuerst (Token), dann URL (schluckt eine evtl. eingebettete IP), dann IP/UUID/Entity-ID.
 */
class NeverSpeakTtsSanitizer : TtsSanitizePort {

    override fun sanitizeForSpeech(text: String): String {
        if (text.isBlank()) return text
        var out = text
        // 1. Token / API-Key / Secret (spezifischste Geheimnisse zuerst).
        out = JWT_PATTERN.replace(out, TOKEN_MASK)
        out = BEARER_PATTERN.replace(out, TOKEN_MASK)
        out = SK_KEY_PATTERN.replace(out, TOKEN_MASK)
        out = LONG_HEX_PATTERN.replace(out, TOKEN_MASK)
        // 2. URL ZUERST vor IP — eine URL mit eingebetteter LAN-IP wird als Ganzes maskiert.
        out = URL_PATTERN.replace(out, URL_MASK)
        // 3. Private LAN-IP.
        out = LAN_IP_PATTERN.replace(out, IP_MASK)
        // 4. UUID.
        out = UUID_PATTERN.replace(out, ID_MASK)
        // 5. HA-Entity-ID (domain.objekt).
        out = ENTITY_ID_PATTERN.replace(out, ID_MASK)
        return out
    }

    private companion object {
        const val TOKEN_MASK = "[TOKEN]"
        const val URL_MASK = "[URL]"
        const val IP_MASK = "[IP]"
        const val ID_MASK = "[ID]"

        // ── Token / Secrets ──────────────────────────────────────────────────
        // HA-Long-Lived-Access-Tokens sind JWTs (eyJ…header.payload.signature).
        private val JWT_PATTERN =
            Regex("""\beyJ[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}""")
        private val BEARER_PATTERN =
            Regex("""\bBearer\s+[A-Za-z0-9._~+/=-]{12,}""", RegexOption.IGNORE_CASE)
        private val SK_KEY_PATTERN = Regex("""\bsk-[A-Za-z0-9]{16,}\b""")
        private val LONG_HEX_PATTERN = Regex("""\b[0-9a-f]{32,}\b""", RegexOption.IGNORE_CASE)

        // ── Strukturierte PII ────────────────────────────────────────────────
        private val URL_PATTERN = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
        private val UUID_PATTERN =
            Regex("""\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""", RegexOption.IGNORE_CASE)
        // HA-Entity-ID: domain.objekt (z.B. light.wohnzimmer) — wie im EgressPort.
        private val ENTITY_ID_PATTERN = Regex("""\b[a-z_]+\.[a-z0-9_]{2,}\b""")

        // Private LAN-IPs (RFC-1918 + Loopback) — wie im EgressPort.
        private val LAN_IP_PATTERN = Regex(
            """\b(?:10\.\d{1,3}\.\d{1,3}\.\d{1,3}""" +
                """|172\.(?:1[6-9]|2[0-9]|3[01])\.\d{1,3}\.\d{1,3}""" +
                """|192\.168\.\d{1,3}\.\d{1,3}""" +
                """|127\.\d{1,3}\.\d{1,3}\.\d{1,3})\b""",
        )
    }
}
