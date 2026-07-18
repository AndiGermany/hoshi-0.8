package de.hoshi.core.pipeline

import de.hoshi.core.dto.SpeakerContext

/**
 * **SpeakerTrust** — DAS eine Vertrauens-Gate zwischen einer BEHAUPTETEN Sprecher-Identität
 * ([SpeakerContext]) und dem, was die Multi-User-Memory-Nähte ([EntityContextPort]/
 * [EntityMemoryWriter]/[EpisodicRecallPort]/`de.hoshi.core.port.WorkingSessionWriter`)
 * tatsächlich unter einer `speakerId` lesen bzw. schreiben dürfen (P1-Privacy-Fix,
 * adversariell verifiziert).
 *
 * **Bedrohungsmodell:** [de.hoshi.core.dto.ChatRequest.speakerContext] kommt bei einem
 * Chat-Turn roh aus dem JSON-Body — JEDER Träger des EINEN geteilten Perimeter-Tokens (z.B.
 * das Kind über das Web-Frontend) kann `speakerContext:{speakerId:"andi"}` senden. Ohne
 * dieses Gate liest [TurnPromptAssembler] Andis private Fakten (Entity-Memory) und seinen
 * persönlichen Gesprächsverlauf (Episodic-Recall) unter dieser BEHAUPTETEN Id — UND
 * `de.hoshi.web.ChatStreamController.rememberAfter` SCHREIBT neue, ggf. FALSCHE Fakten
 * unter Andis Id fest (Memory-Vergiftung). Derselbe unverifizierte [SpeakerContext] fließt
 * vom WS-Rand ([de.hoshi.web.AudioWebSocketHandler], `start`/`speaker`-Frames) über exakt
 * denselben [de.hoshi.core.dto.ChatRequest.speakerContext] in denselben Turn — dieses Gate
 * sitzt bewusst an der MEMORY-Naht (nicht am Ingress) und deckt den WS-Pfad darum
 * AUTOMATISCH mit ab, ohne dass [de.hoshi.web.AudioWebSocketHandler] selbst angefasst
 * werden muss.
 *
 * Die EINZIGE Quelle, die eine `speakerId` tatsächlich VERIFIZIERT, ist die Stimm-Erkennung
 * (`SpeakerIdentifyService`/CAM++, S3, `de.hoshi.web.CosineSpeakerIdentifyService`): sie
 * liefert einen Cosine-Score in [SpeakerContext.score] — bei einem echten Treffer deutlich
 * über 0 (kalibrierte Schwelle, siehe `hoshi.speaker.recognition.threshold`), bei einem
 * rohen, unbewiesenen FE-/API-Claim bleibt er beim [SpeakerContext]-Default `0.0`.
 *
 * **Design-Entscheidung „score-basiert":** [SpeakerContext] trägt den Score SCHON HEUTE mit
 * — kein neues Feld, kein FE-Change, kein zweiter Wire-Vertrag. Ein Claim mit
 * `score >= threshold` gilt als vertraut. Dass ein böswilliger Client den Score theoretisch
 * selbst mitschicken könnte, ist ein ANDERES Problem (Kanal-/Ingress-Trennung zwischen dem
 * biometrischen WS-Pfad und einem offenen Chat-JSON-Pfad, künftige Scheibe) — dieses Gate
 * zieht NUR die Linie „Score genügt ⇒ vertrauen"; es erzeugt keine Fälschungssicherheit für
 * den Score-Wert selbst.
 *
 * **Design-Entscheidung „Gast-Fallback" statt „gar kein Memory":** ein NICHT vertrauter
 * Claim kollabiert auf [GUEST_SPEAKER_ID] (`"gast"`) statt auf `null`/Abbruch. Das trifft
 * EXAKT die längst gehärtete, geprüfte Gast-Behandlung der Memory-Adapter
 * (`de.hoshi.adapters.memory.EntityMemoryAdapter.isGuest`: leer/`unknown`/`gast`/ungültig
 * ⇒ NIE laden, NIE schreiben) — dieselbe Härtung, die der bereits VERIFIZIERTE Voice-Pfad
 * (`de.hoshi.web.VoiceInboundController`) für einen echten Gast schon nutzt. Wiederverwendung
 * statt einer zweiten Bedeutung von „kein Zugriff" ⇒ kleinste Änderung an den Aufrufstellen
 * (sie bekommen immer eine konkrete `speakerId`-Zeichenkette, nie einen Sonderfall) UND der
 * ehrlichste Zustand: ein nicht vertrauter Sprecher wird technisch WIE EIN GAST behandelt,
 * nicht wie ein Fehler.
 *
 * **Default OFF:** [resolve] mit `enforced=false` liefert IMMER den behaupteten Claim
 * unverändert zurück (bzw. `null` bei fehlendem Kontext) — UNABHÄNGIG vom Score. Byte-neutral
 * zum Verhalten vor diesem Gate. Scharf geschaltet erst über das Flag
 * `HOSHI_SPEAKER_TRUST_ENFORCED` (Default `false`, Wiring am web-inbound-Rand — `core-domain`
 * bleibt Spring-frei und kennt keine Property-Namen).
 */
object SpeakerTrust {

    /**
     * Neutrale Gast-Kennung — dieselbe Zeichenkette wie
     * `de.hoshi.adapters.memory.EntityMemoryAdapter.GUEST` und
     * `de.hoshi.web.VoiceInboundController.GUEST_SPEAKER_ID` (bewusst dupliziert: `core-domain`
     * kennt keine Adapter/Controller — siehe Modul-Grenze in `core-domain/build.gradle.kts`,
     * „Reiner Domänen-Kern: NUR Reactor + kotlin-stdlib").
     */
    const val GUEST_SPEAKER_ID = "gast"

    /**
     * Ergebnis der Vertrauens-Entscheidung.
     *
     * @property speakerId die Id, die eine Memory-Naht tatsächlich verwenden darf (entweder
     *   der Claim ODER [GUEST_SPEAKER_ID]).
     * @property trusted `true`, wenn [speakerId] der ECHTE Claim ist (Gate OFF oder Score über
     *   der Schwelle); `false`, wenn auf den Gast zurückgefallen wurde.
     */
    data class TrustedSpeaker(val speakerId: String, val trusted: Boolean)

    /** Der sichere Rückfall: Gast, nichts vertraut. */
    val GUEST: TrustedSpeaker = TrustedSpeaker(GUEST_SPEAKER_ID, trusted = false)

    /**
     * Löst einen (möglicherweise unverifizierten) [context] zu der [TrustedSpeaker] auf, die
     * eine Memory-Naht (Recall ODER Write) tatsächlich verwenden darf. Reine Wertelogik —
     * kein I/O, kein Wurf, never-throw.
     *
     * - `enforced == false` (Default): **byte-neutral.** `context != null` ⇒ IMMER
     *   `TrustedSpeaker(context.speakerId, trusted = true)`, UNABHÄNGIG vom Score (exakt der
     *   heutige rohe Pass-Through). `context == null` ⇒ `null` — die Aufrufstelle behält ihren
     *   bisherigen `?:`-Fallback/Kurzschluss (siehe [TurnPromptAssembler.assemble] bzw.
     *   `de.hoshi.web.ChatStreamController.rememberAfter`, die beide schon vor diesem Gate
     *   `null` auf einen neutralen Default abbildeten).
     * - `enforced == true` UND `context != null` UND `context.score >= threshold`: der Claim
     *   gilt als verifiziert (echte Stimm-Erkennung oder ein gleichwertig starkes Signal) ⇒
     *   der Claim wird vertraut.
     * - `enforced == true` UND (`context == null` ODER `context.score < threshold`): [GUEST] ⇒
     *   kein Cross-User-Recall, kein Write unter der behaupteten fremden Id — weder für einen
     *   zu unsicheren Claim NOCH für einen komplett fehlenden Kontext.
     *
     * Zentral für BEIDE Nähte (Recall in [TurnPromptAssembler] + Write in
     * `de.hoshi.web.ChatStreamController.rememberAfter`): dieselbe Funktion, dasselbe Flag,
     * dieselbe Schwelle — damit es keine zweite, abweichende Umgehung gibt.
     */
    fun resolve(context: SpeakerContext?, enforced: Boolean, threshold: Double): TrustedSpeaker? {
        if (context == null) return if (enforced) GUEST else null
        if (!enforced || context.score >= threshold) return TrustedSpeaker(context.speakerId, trusted = true)
        return GUEST
    }
}
