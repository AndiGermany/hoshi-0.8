package de.hoshi.core.supervision

/**
 * Deklarative Beschreibung EINES bekannten Sidecars. Ersetzt die in 0.5 verstreute
 * Wahrheit (Bash-`HOSHI_SERVICES`-Array + launchd-Plists + Backend-Service-Liste) durch
 * EINE Quelle:
 *
 *  - [name]          stabiler Anzeige-/Schlüssel-Name (z.B. `brain(e4b)`).
 *  - [url]           Basis-URL der Naht (ohne `/health`).
 *  - [ramCostMb]     grobe residente RAM-Kosten — Futter für den [RamBudgetPort].
 *  - [brainGated]    true = belegt den EINEN Brain-Slot (16-GB-Wand: e4b ODER 12b, nie beide).
 *  - [expectedModel] optionaler Soll-Substring fürs gemessene `model` (Drift-Erkennung).
 *  - [restartCommand] der GEPLANTE Restart-Befehl — wird NICHT ausgeführt (Andi-Gate),
 *                     nur als Plan im Report gezeigt.
 */
data class SidecarSpec(
    val name: String,
    val url: String,
    val ramCostMb: Long,
    val brainGated: Boolean = false,
    val expectedModel: String? = null,
    val restartCommand: String = "",
)

/**
 * **SidecarRegistry** — die EINE Quelle der bekannten Sidecars. Analog zur
 * 0.5-`HOSHI_SERVICES`-Registry, aber deklarativ und an genau einer Stelle.
 */
class SidecarRegistry(val sidecars: List<SidecarSpec>) {

    fun byName(name: String): SidecarSpec? = sidecars.firstOrNull { it.name == name }

    companion object {
        /**
         * Die etablierte 0.x-Mac-Topologie (Stand fakten-anker / LEDGER-sidecars):
         * Brain e4b :8041 (brainGated, gemessenes `model`), Whisper-STT :9001,
         * CAM++ Speaker-ID :9002, Knowledge-Bridge :8035, Voxtral-TTS :8042.
         *
         * RAM-Kosten sind bewusst grob (16-GB-bewusste Größenordnung): der e4b-Brain
         * ist der dicke Posten (~8 GB resident), die ONNX/MLX-Sidecars klein.
         */
        fun mac(): SidecarRegistry = SidecarRegistry(
            listOf(
                SidecarSpec(
                    name = "brain(e4b)",
                    url = "http://localhost:8041",
                    ramCostMb = 8000,
                    brainGated = true,
                    expectedModel = "gemma-4-e4b-it-4bit",
                    restartCommand = "launchctl kickstart -k gui/$UID/io.hoshi.sidecar.brain-e4b",
                ),
                SidecarSpec(
                    name = "whisper-stt",
                    url = "http://localhost:9001",
                    ramCostMb = 1600,
                    expectedModel = "whisper-large-v3-turbo",
                    restartCommand = "launchctl kickstart -k gui/$UID/io.hoshi.sidecar.whisper",
                ),
                SidecarSpec(
                    name = "campplus-spk",
                    url = "http://localhost:9002",
                    ramCostMb = 200,
                    expectedModel = "CAM++",
                    restartCommand = "launchctl kickstart -k gui/$UID/io.hoshi.sidecar.speaker-id",
                ),
                SidecarSpec(
                    name = "knowledge-bridge",
                    url = "http://localhost:8035",
                    ramCostMb = 300,
                    restartCommand = "launchctl kickstart -k gui/$UID/io.hoshi.sidecar.knowledge-bridge",
                ),
                SidecarSpec(
                    name = "voxtral-tts",
                    url = "http://localhost:8042",
                    ramCostMb = 3000,
                    expectedModel = "Voxtral",
                    restartCommand = "launchctl kickstart -k gui/$UID/io.hoshi.sidecar.voxtral",
                ),
            ),
        )

        /** UID-Platzhalter — nur für den (gegateten) Plan-Text, nie ausgeführt. */
        private const val UID = "501"
    }
}
