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
 *  - URL (`http(s)://…`) → ENTFERNT (nicht maskiert: eine vorgelesene
 *    „eckige Klammer U-R-L" waere selbst Laerm — Weglassen ist strikt sicherer)
 *  - Markdown-Quellenangabe `[Label](http…)` und Quellen-Schwaenze
 *    („Source: Quellen: https://…") → ENTFERNT
 *  - Markdown-Auszeichnung (`**`, `*`, `` ` ``, `~~`, fuehrende `#`) → ENTFERNT
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
        // 1b. Markdown-Quellenangabe KOMPLETT WEG, bevor die URL-Maske greift.
        //     Andi-Befund 21.07: Recherche-Antworten enden auf „([toureiffel.paris](https://…))".
        //     Ohne diese Regel maskiert Schritt 2 nur die URL — gesprochen wurde dann
        //     „Klammer auf toureiffel Punkt paris Klammer zu Klammer auf URL …", also der
        //     Klammer-Salat statt der Quelle. Der ANGEZEIGTE Text behält die Quelle
        //     (Info-Icon/`escalationSources`); gesprochen wird sie nie — genau das war das
        //     Ziel des Quellen-Umbaus (f3113ae), nur griff es nicht für die Inline-Zitate,
        //     die das externe Modell selbst in seinen Fließtext schreibt.
        out = MARKDOWN_CITATION_PATTERN.replace(out, "")
        // 1c. Quellen-SCHWANZ am Satzende: „Source: Quellen: https://…" (Andi 21.07 —
        //     das Label kommt real doppelt und in zwei Sprachen). Muss VOR der
        //     URL-Behandlung greifen, sonst bliebe „Source: Quellen:" allein stehen und
        //     würde vorgelesen.
        out = SOURCE_TAIL_PATTERN.replace(out, "")
        // 2. URL: für GESPROCHENEN Text ENTFERNEN statt maskieren. Eine vorgelesene
        //    „eckige Klammer U-R-L" ist selbst Lärm — und Weglassen ist für die
        //    „sprich niemals ein Geheimnis"-Regel strikt sicherer als Maskieren.
        //    (Der angezeigte Text ist davon unberührt; dies ist der TTS-Pfad.)
        out = URL_PATTERN.replace(out, "")
        // 2c. Markdown-Auszeichnung, die sonst als Zeichen gesprochen würde:
        //     **fett**, *kursiv*, `code`, ~~durchgestrichen~~, führende #-Überschriften.
        //     Der TEXT bleibt, nur die Steuerzeichen fallen weg.
        out = MARKDOWN_MARKS_PATTERN.replace(out, "")
        out = HEADING_MARKS_PATTERN.replace(out, "")
        // 2b. Aufräumen, was das Entfernen hinterlässt: doppelte Leerzeichen und ein
        //     Leerzeichen vor Satzzeichen. Sonst hört man die Lücke als Stolperer.
        out = LEFTOVER_SPACE_BEFORE_PUNCT.replace(out, "$1")
        out = MULTI_SPACE.replace(out, " ").trim()
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

        /**
         * Markdown-Quellenangabe `[Label](http…)`, optional selbst noch in runde Klammern
         * gefasst — genau die Form, die das Recherche-Modell an seine Antworten hängt.
         * Bewusst NUR mit `http(s)`-Ziel: ein Fließtext-`[so]` oder eine Klammer ohne Link
         * bleibt unangetastet. Ein evtl. vorangehendes Leerzeichen wird mitgenommen.
         */
        /**
         * Quellen-Schwanz: ein oder mehrere Labels („Quelle(n)"/„Source(s)", beliebig
         * verschachtelt) gefolgt von einer URL, optional mit Schlusspunkt. Real gesehen:
         * `Source: Quellen: https://…`. Ohne diese Regel bliebe das Label stehen.
         */
        private val SOURCE_TAIL_PATTERN = Regex(
            """\s*(?:\((?=\s*(?:quellen?|sources?)\s*:))?(?:(?:quellen?|sources?)\s*:\s*)+https?://[^\s)]*\)?\.?""",
            RegexOption.IGNORE_CASE,
        )

        /** Markdown-Auszeichnungszeichen, die gesprochen als Zeichen hörbar wären. */
        private val MARKDOWN_MARKS_PATTERN = Regex("""\*{1,3}|_{2,}|~~|`""")

        /** Führende Überschriften-Rauten am Zeilenanfang. */
        private val HEADING_MARKS_PATTERN = Regex("""(?m)^\s{0,3}#{1,6}\s*""")

        private val MARKDOWN_CITATION_PATTERN =
            Regex("""\s*\(?\[[^\]\n]{1,120}]\(\s*https?://[^)\s]+\s*\)\)?""", RegexOption.IGNORE_CASE)

        /** Leerzeichen, das nach dem Entfernen vor einem Satzzeichen übrig bleibt. */
        private val LEFTOVER_SPACE_BEFORE_PUNCT = Regex("""\s+([.,;:!?])""")

        /** Mehrfache Leerzeichen nach dem Entfernen. */
        private val MULTI_SPACE = Regex(""" {2,}""")
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
