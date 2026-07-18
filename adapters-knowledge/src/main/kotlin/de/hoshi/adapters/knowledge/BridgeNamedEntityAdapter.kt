package de.hoshi.adapters.knowledge

import de.hoshi.core.pipeline.HonestySignal
import de.hoshi.core.pipeline.NamedEntitySignal
import org.slf4j.LoggerFactory

/**
 * **BridgeNamedEntityAdapter** — der ECHTE [NamedEntitySignal] (Anti-Konfabulation,
 * portiert aus Hoshi 0.5 `de.hoshi.app.cloud.EntityClaimDetector`, Iter-125/T155b).
 * Ersetzt den inerten `NamedEntityStubAdapter`.
 *
 * Erkennt **Wer-ist-X-Fragen über unbekannte Eigennamen** — die Neelix-Klasse von
 * Andi-Live-Bug 2026-05-26 („Wer ist Neelix?" → e4b: „Ah, der DJ Neelix, ein guter
 * Sound-Architekt…" — Pose statt Person). Für „Neelix" liefert die Bridge null
 * Treffer; ohne Grounding improvisiert e4b selbstsicher Erfundenes.
 *
 * Konservativ (Mira/Lara-Wärme-Veto) — drei Bedingungen:
 *  1. Trigger-Pattern matcht („wer ist/war" / „kennst du" + Name).
 *  2. Kandidat passiert die Eigenname-Heuristik (≥4 Zeichen, Großbuchstabe im
 *     Original, kein Common-Wort).
 *  3. Die [BridgeKnowledgeProbe] urteilt — und übersetzt 1:1 in [HonestySignal]:
 *     - **HIT** (starker Treffer, Bridge kennt ihn) → [HonestySignal.NONE] (Pass,
 *       der normale Wiki-Pfad liefert die fundierte Antwort).
 *     - **EMPTY** (leer/schwach, Bridge healthy) → `matched=true`: warmer
 *       „kenne den Namen nicht"-Refuse (der Neelix-Gewinn).
 *     - **BRIDGE_DOWN** → `matched=true, bridgeDown=true`: ehrlich „Wissensspeicher
 *       nicht erreichbar", NICHT „kenne ich nicht".
 *
 * Albert Einstein, Marie Curie, Saturn → HIT → Pass (Bridge kennt sie). Nur echte
 * UNKNOWNS triggern.
 */
class BridgeNamedEntityAdapter(
    private val probe: BridgeKnowledgeProbe,
) : NamedEntitySignal {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Pattern: Frage-Trigger + Kandidaten-Name (1:1 aus 0.5).
     *  Group 1 Trigger („wer ist"/„wer war"/„kennst du") · Group 2 Kandidat.
     */
    private val triggerRegex = Regex(
        """(?i)\b(wer\s+ist|wer\s+war|kennst\s+du)\s+([A-ZÄÖÜa-zäöü][a-zäöüßA-ZÄÖÜ.\- ]{3,40}?)\s*[?!.]?\s*$""",
    )

    /**
     * Common-Wörter — Kandidat ist (oder beginnt mit) einem davon → kein Eigenname,
     * fällt durch (Mira-Wärme-Veto: „Wer ist mein Bruder?" darf nicht ins Gate). 1:1
     * aus 0.5; falsche Pässe sind besser als falsche Refuses.
     */
    private val commonWords = setOf(
        "mein", "meine", "meinen", "dein", "deine", "deinen", "sein", "seine",
        "ihr", "ihre", "ihren", "unser", "unsere",
        "der", "die", "das", "ein", "eine", "einen",
        "mensch", "menschen", "person", "leute", "kerl", "typ", "frau", "mann",
        "hund", "hunde", "katze", "katzen", "tier", "tiere",
        "honigbiene", "honigbienen", "biene", "bienen",
        "vater", "mutter", "bruder", "schwester", "sohn", "tochter",
        "freund", "freundin", "kollege", "kollegin", "chef", "chefin",
        "lehrer", "lehrerin", "arzt", "ärztin",
        "etwas", "irgendwas", "irgendwer", "irgendjemand",
    )

    /**
     * Eigenname-Heuristik: Kandidat muss im Original mit Großbuchstabe beginnen,
     * ≥4 Zeichen lang sein, kein Common-Wort (oder damit startend) sein.
     */
    private fun looksLikeProperName(candidate: String, originalQuery: String): Boolean {
        if (candidate.length < 4) return false
        val idx = originalQuery.indexOf(candidate, ignoreCase = true)
        if (idx < 0) return false
        val originalCandidate = originalQuery.substring(idx, idx + candidate.length)
        if (!originalCandidate.first().isUpperCase()) return false
        val firstToken = candidate.split(' ', '-').first().lowercase().trim()
        if (firstToken in commonWords) return false
        if (candidate.lowercase().trim() in commonWords) return false
        return true
    }

    override fun detect(text: String): HonestySignal {
        if (text.isBlank()) return HonestySignal.NONE
        val trimmed = text.trim()
        val match = triggerRegex.find(trimmed) ?: return HonestySignal.NONE
        val candidate = match.groupValues[2].trim().trimEnd('?', '!', '.', ',')
        if (candidate.isBlank() || !looksLikeProperName(candidate, trimmed)) return HonestySignal.NONE

        // Reiner Kandidaten-Name (kein „wer ist X") — leere coverageTokens ⇒ reine
        // bm25-Wertung: HIT = starker Treffer (KNOWN), sonst EMPTY (UNKNOWN).
        return when (val verdict = probe.probe(candidate)) {
            BridgeKnowledgeProbe.Verdict.HIT -> {
                log.debug("[entity-claim] Bridge kennt '{}' — Pass (Wiki liefert)", candidate)
                HonestySignal.NONE
            }
            BridgeKnowledgeProbe.Verdict.EMPTY -> {
                log.info("[entity-claim] '{}' unbekannt (verdict={}) → Gate (warmer Refuse)", candidate, verdict)
                HonestySignal(matched = true, bridgeDown = false)
            }
            BridgeKnowledgeProbe.Verdict.BRIDGE_DOWN -> {
                log.info("[entity-claim] '{}' verdict={} → Gate (Wissensspeicher nicht erreichbar)", candidate, verdict)
                HonestySignal(matched = true, bridgeDown = true)
            }
        }
    }
}
