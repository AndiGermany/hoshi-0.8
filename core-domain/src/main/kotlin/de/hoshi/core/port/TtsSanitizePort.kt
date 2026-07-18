package de.hoshi.core.port

/**
 * **TtsSanitizePort** — die Egress-Naht VOR der Cloud-TTS (hexagonaler Port). Ein
 * Antwortsatz, der gleich an eine externe Sprach-Synthese (OpenAI `/v1/audio/speech`)
 * geht, laeuft zuerst hier durch: Never-Speak-Spans (Token/API-Key, URL, IP, UUID/ID,
 * HA-Entity-ID) werden maskiert, Namen und normaler Inhalt BLEIBEN erhalten (sonst
 * kaltes/kaputtes Audio).
 *
 * **Warum ein eigener Port (statt direkt den `capability-kernel`-EgressPort):** der
 * `adapters-tts`-Adapter sieht `capability-kernel` NICHT (Hexagon-Grenze). Der Port
 * lebt darum hier im reinen Kern; die konkrete Maskier-Logik wird vom Inbound-Adapter
 * (`web-inbound`) injiziert. Der lokale Voxtral-Pfad braucht den Port NICHT (kein
 * Egress) — nur der CLOUD-Adapter [de.hoshi.adapters.tts] nimmt ihn.
 *
 * **Byte-neutraler Default [IDENTITY]:** ungesetzt/flag-OFF gibt der Port den Text
 * UNVERAENDERT zurueck — der bestehende Pfad bleibt byte-identisch. Das Scharfschalten
 * (Flag `HOSHI_TTS_SANITIZE_ENABLED`) ist eine Deploy-Entscheidung.
 */
fun interface TtsSanitizePort {
    /**
     * Maskiert die Never-Speak-Spans in [text] VOR dem Cloud-Egress und liefert den
     * sprechbaren Rest zurueck. [IDENTITY] gibt [text] unveraendert zurueck.
     */
    fun sanitizeForSpeech(text: String): String

    companion object {
        /**
         * Verhaltens-neutraler Default (Sanitize OFF) — gibt den Text BYTE-IDENTISCH
         * zurueck. So bleibt der heutige Cloud-TTS-Pfad unveraendert, bis das Flag scharf
         * geschaltet wird.
         */
        val IDENTITY: TtsSanitizePort = TtsSanitizePort { it }
    }
}
