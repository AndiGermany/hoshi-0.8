package de.hoshi.core.port

import de.hoshi.core.dto.Language
import reactor.core.publisher.Mono

/**
 * **SttPort** — die Sprach-ERKENNUNGS-Naht (hexagonaler Port). Das Spiegelbild
 * von [TtsPort]: macht den Turn HÖRBEREIT — rohe Mic-/WAV-Bytes rein → Transkript
 * raus. Damit kann Andi Hoshi **ansprechen**, statt nur zu tippen.
 *
 * Bewusst winzig + testbar (ein `fun interface`): der Inbound-Rand
 * (`/api/v1/voice`) hängt am Interface, nicht am konkreten Whisper-Adapter —
 * Unit-Tests injizieren einen Fake statt den Live-Sidecar (:9001) zu brauchen.
 *
 * **Best-Effort-Vertrag (Never-Silent):** Transkription ist KÜR-vor-Turn, nie ein
 * Turn-Killer. Eine Impl liefert bei Stille/leerem Input/Sidecar-Fehler einen
 * **leeren String** (`Mono.just("")`) statt zu werfen — der Aufrufer übersetzt
 * das leere Transkript in `no_input`/eine warme STT-Absage, nie in einen Crash.
 *
 * [language] fließt als Hint durch (Whisper `language`-Code) — multilingual von
 * Anfang an, genau wie [TtsPort.synth]. **NULLABLE** (0.8, bilinguales AUTO): `null`
 * heißt "kein Sprach-Hint" -> der Sidecar erkennt die Sprache selbst (Whisper auto-
 * detect, wenn der `language`-Query-Param weggelassen wird). Bestehende Aufrufer mit
 * konkreter [Language] bleiben unveraendert (DE/EN-Hint wie bisher).
 */
fun interface SttPort {
    /**
     * Roh-Audio (WAV PCM16 mono) → Transkript. Leerer String = „nichts verstanden"
     * (Stille, zu kurzer Input oder Sidecar-Fehler) — der Aufrufer behandelt das
     * als `no_input`, nie als Fehler. [language] `null` ⇒ Sidecar auto-detectet.
     */
    fun transcribe(audioWav: ByteArray, language: Language?): Mono<String>
}
