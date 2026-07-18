package de.hoshi.adapters.knowledge

import de.hoshi.core.pipeline.ExistenceClaimSignal
import de.hoshi.core.pipeline.HonestySignal
import org.slf4j.LoggerFactory

/**
 * **BridgeExistenceClaimAdapter** — der ECHTE [ExistenceClaimSignal] (Anti-
 * Konfabulation, portiert aus Hoshi 0.5 `de.hoshi.app.cloud.ExistenceClaimDetector`,
 * Iter-122/T167). Ersetzt den inerten `ExistenceClaimStubAdapter`.
 *
 * Erkennt **Existenz-Fragen nach erfundenen Zahl-Entitäten** — die Klasse von
 * Andi-Live-Bug 2026-05-26 („Gibt es einen 11-Euro-Schein?" → e4b: „Ja, das ist
 * unser normales Zahlungsmittel."). Ein 4B hält keine „sei ehrlich"-Prompt-Regel
 * (B-096), darum deterministisch davor.
 *
 * Türsteher→Bibliothekar (T167, Kai): nach dem syntaktischen Zahl-Entity-Match wird
 * NICHT blind abgelehnt, sondern die Bibliothek geprobt ([BridgeKnowledgeProbe] gegen
 * Bridge `/search`):
 *  - **HIT** (Bridge kennt das Thema, z.B. „Eurobanknoten" für „12-Euro-Schein"):
 *    → [HonestySignal.NONE] (Pass): der normale Grounding-Flow groundet die echte
 *    Antwort und lässt e4b ehrlich verneinen. KEINE hardcoded Fakten-Tabelle.
 *  - **EMPTY** (echt-leer, Bridge healthy) → `matched=true`: das Gate gibt eine
 *    ehrlich-zweifelnde Absage statt einer erfundenen Bestätigung.
 *  - **BRIDGE_DOWN** → `matched=true, bridgeDown=true`: das Gate sagt ehrlich
 *    „komm grad nicht an mein Wissen" statt „gibt's nicht".
 *
 * Konservativ (Mira/Lara-Veto „nicht jede 'gibt es'-Frage blockieren"): nur Existenz-
 * Fragen mit **Zahl-Entity** triggern. „Gibt es Honigbienen?" → kein Match → NONE.
 */
class BridgeExistenceClaimAdapter(
    private val probe: BridgeKnowledgeProbe,
    private val numberWordNormalizer: NumberWordNormalizer = NumberWordNormalizer(),
) : ExistenceClaimSignal {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Pattern: Existenz-Verb + (Artikel) + Zahl + Substantiv-Kette (1:1 aus 0.5).
     *  Group 1 Trigger · Group 2 Zahl (11, 3,50, 13.5) · Group 3 Substantiv-Kette.
     *
     * Matcht: „gibt es einen 11 euro schein", „existiert ein 3,50 euro schein",
     *         „gibt es ein 13-monats-jahr".
     * Matcht NICHT (kein Zahl-Pattern): „gibt es honigbienen", „gibt es das wirklich".
     */
    private val zahlEntityRegex = Regex(
        """(?i)\b(gibt es|existiert|stimmt es dass es gibt|stimmt es dass es)\s+(?:eine?n?\s+|der\s+|den\s+|die\s+|das\s+)?(\d+(?:[,.]\d+)?)[\s-]+([a-zäöüß][a-zäöüß-]*(?:\s+[a-zäöüß][a-zäöüß-]*){0,3})""",
    )

    override fun detect(text: String): HonestySignal {
        if (text.isBlank()) return HonestySignal.NONE
        // Voice-Whisper-Output normalisieren — „elf" → „11" (idempotent für Text).
        val q = numberWordNormalizer.normalize(text).lowercase().trim()
        val match = zahlEntityRegex.find(q) ?: return HonestySignal.NONE

        val number = match.groupValues[2]
        val substantive = match.groupValues[3].trim()
        if (substantive.isBlank()) return HonestySignal.NONE
        val entity = "$number $substantive"

        // Themen-Probe OHNE Zahl (der Themen-Artikel trägt die gefragte Zahl nie im
        // Titel). coverage = Substantiv-Tokens, askedNumber = number (Zahl-Präfix-
        // Mismatch-Schutz: „0-Euro-Schein" für gefragte „12" zählt nicht als HIT).
        val coverage = substantive.split(' ', '-').filter { it.length >= 3 }
        return when (val verdict = probe.probe(substantive, coverage, askedNumber = number)) {
            BridgeKnowledgeProbe.Verdict.HIT -> {
                log.info("[existence-claim] '{}' — Bibliothek kennt Thema (HIT) → Pass an Grounding", entity)
                HonestySignal.NONE
            }
            BridgeKnowledgeProbe.Verdict.EMPTY -> {
                log.info("[existence-claim] '{}' verdict={} → Gate (ehrliche Absage)", entity, verdict)
                HonestySignal(matched = true, bridgeDown = false)
            }
            BridgeKnowledgeProbe.Verdict.BRIDGE_DOWN -> {
                log.info("[existence-claim] '{}' verdict={} → Gate (Wissensspeicher nicht erreichbar)", entity, verdict)
                HonestySignal(matched = true, bridgeDown = true)
            }
        }
    }

    /**
     * PROBE-FREIE syntaktische Sicht (portiert aus 0.5, T215): die Substantiv-Kette
     * OHNE Zahl („gibt es einen 12-Euro-Schein" → „euro schein"), oder null wenn kein
     * Zahl-Entity-Muster. Teilt das EINE Regex mit [detect] (DRY). Nützlich für einen
     * späteren Grounder, der die zahl-entkoppelte Themen-Query braucht, ohne erneut
     * zu proben (doppeltes I/O).
     */
    fun numberEntityTopic(text: String): String? {
        if (text.isBlank()) return null
        val q = numberWordNormalizer.normalize(text).lowercase().trim()
        val match = zahlEntityRegex.find(q) ?: return null
        return match.groupValues[3].trim().ifBlank { null }
    }
}
