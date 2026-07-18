package de.hoshi.core.port

import reactor.core.publisher.Mono

/**
 * **SttSurprisalPort — der Verhör-Detektor (S1, MESSEN-first).** Das
 * Geschwister von [de.hoshi.core.dto.LlmDelta.logprob]/`answerEntropy`
 * ([de.hoshi.core.pipeline.TurnOrchestrator]), nur am ANDEREN Ende des Turns:
 * NICHT „wie unsicher war der Brain bei seiner ANTWORT", sondern „wie
 * unwahrscheinlich ist DAS, was Whisper gerade verstanden haben will" — der
 * Rohstoff für einen künftigen Rückfrage-Trigger („hab ich dich richtig
 * verstanden?", S2), wenn ein STT-Transkript verdächtig überrascht.
 *
 * Bewusst ein `fun interface` (genau EINE Methode) — EXAKT das Muster von
 * [EscalationPort]/[SttPort]: Tests injizieren einen Lambda-Fake statt einen
 * Live-Sidecar zu brauchen.
 *
 * **Reactor- statt Nullable-Vertrag:** ein [Mono] kann kein `null` tragen —
 * „nicht messbar" heißt darum `Mono.empty()` (KEIN `Mono.error`), NIE ein
 * geworfener Fehler. Der Konsument ([de.hoshi.core.pipeline.TurnOrchestrator])
 * behandelt eine leere Mono exakt wie ein `null`-Ergebnis: Flag/Port aus,
 * Score-Endpoint fehlt (404 — heutiger Brain-Server, s. Klassen-KDoc des
 * Adapters), Timeout, kaputtes JSON — ALLES kollabiert ehrlich auf „nichts
 * gemessen", NIE auf einen erfundenen Wert und NIE auf einen Turn-Abbruch.
 *
 * **Best-Effort + hartes Zeitbudget:** eine Implementierung kapselt IHREN
 * EIGENEN Timeout (≤500 ms, s. Adapter-KDoc) — die Messung darf den Turn NIE
 * spürbar aufhalten (der Aufrufer legt zusätzlich defensiv denselben Deckel
 * obendrauf, s. `TurnOrchestrator.STT_SURPRISAL_TIMEOUT`).
 *
 * **KEIN Verhalten hängt am Wert** — reine Diary-Telemetrie (S1). Die
 * Rückfrage-/Abstain-Wirkung ist S2, NACH echter Kalibrier-Datenlage (exakt
 * dieselbe Zurückhaltung wie beim Antwort-Entropie-Sensor).
 *
 * TODO **S2: Entity-Whitelist gegen Eigennamen-Spikes** — ein Musiktitel, ein
 * seltener Eigenname oder ein Fremdwort erzeugt STRUKTURELL hohe Surprisal,
 * OHNE dass Whisper sich verhört hätte (das Sprachmodell kennt den Namen nur
 * einfach nicht). Ohne eine Whitelist/Erkennung solcher Fälle würde ein
 * naiver S2-Rückfrage-Trigger ständig bei völlig korrekt verstandenen
 * Eigennamen nachfragen — false positives, die nerven statt zu helfen. NICHT
 * Teil dieser Scheibe (S1 ist messen-only); S2 muss das lösen, BEVOR ein
 * Rückfrage-Verhalten an den Score gekoppelt wird.
 */
fun interface SttSurprisalPort {
    /**
     * Bewertet [text] (das rohe Whisper-Transkript, VOR jeder weiteren
     * Verarbeitung) gegen das Score-Modell. Liefert GENAU EIN [SttSurprisal]
     * bei gelungener Messung, sonst `Mono.empty()` — wirft NIE.
     */
    fun score(text: String): Mono<SttSurprisal>
}

/**
 * **Ergebnis einer STT-Surprisal-Messung** — der Token-Surprisal
 * (`-logprob`, in nats, ≥ 0) des Transkripts laut Score-Modell, aggregiert
 * über die Tokens der Äußerung. Höher = das Modell hätte GENAU DIESE
 * Wortfolge nicht erwartet (Verhör-Verdacht ODER ein seltener Eigenname —
 * s. Entity-Whitelist-TODO am [SttSurprisalPort]).
 *
 * @param meanSurprisal Mittelwert über alle Tokens des Transkripts.
 * @param maxSurprisal Höchster Einzel-Token-Surprisal (ein einzelnes,
 *   extrem unwahrscheinliches Token kann im Mittelwert untergehen — der
 *   Ausreißer selbst ist der stärkere Verhör-Hinweis, s. S2-Kalibrierung).
 * @param tokenCount Anzahl der bewerteten Tokens (Kontext für die Mittelung;
 *   ein 1-Token-Transkript hat einen strukturell volatileren Mittelwert).
 */
data class SttSurprisal(
    val meanSurprisal: Double,
    val maxSurprisal: Double,
    val tokenCount: Int,
)
