package de.hoshi.web

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoshi.core.dto.Persona
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * **JsonFilePersonaStore** — der LAUFZEIT-Zustand des Server-seitigen Persona-Settings
 * (Andi 16.07: „vieles eher serverseitig"), persistiert als kleine JSON-Datei
 * (`~/.hoshi/persona.json` bzw. `hoshi.persona.path` / `HOSHI_PERSONA_PATH`). ZEILE FÜR
 * ZEILE nach dem [JsonFileEscalationModeStore]-Muster — der einzige Unterschied: ein
 * [Persona]-Enum statt eines [de.hoshi.core.pipeline.EscalationMode].
 *
 * Anders als der Escalation-Store liefert [persona] bewusst `Persona?` (nicht einen festen
 * Default): so kann der [de.hoshi.core.pipeline.PersonaResolver] die Fallback-Kette
 * „explizites Request-Feld > Server-Store > STANDARD" sauber unterscheiden — `null` heißt
 * „nie gesetzt ⇒ der nächste Glied der Kette (STANDARD) greift", exakt wie der
 * [JsonFileWeatherLocationStore] `null` fährt.
 *
 * Pro ws-Turn liest die Resolver-Naht billig aus dem [Volatile]-Cache (NIE pro Turn von
 * der Platte). Die Datei wird genau einmal beim Konstruieren gelesen und danach nur bei
 * [setPersona] (Settings-PUT) atomar neu geschrieben.
 *
 * Robust per Doktrin: fehlende/kaputte/unbekannte Datei ⇒ kein gesetzter Wert ⇒ [persona]
 * liefert `null` und die STANDARD-Persona greift unverändert — heutiges Verhalten
 * byte-gleich; kein LESE-Fehler wirft je. Ein SCHREIB-Fehler ([setPersona]) ist dagegen
 * NICHT schluckbar: er wirft, der Cache bleibt unangetastet (persist-then-commit) — der
 * Controller liefert ehrlich 5xx statt fake-200.
 */
class JsonFilePersonaStore(
    path: Path,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    /** Absolut normalisiert, damit das Temp-File IMMER im selben Verzeichnis landet (atomarer Rename). */
    val path: Path = path.toAbsolutePath()

    @Volatile
    private var cached: Persona? = null

    init {
        reload()
    }

    /**
     * Laufzeit-Read (billiger Cache-Read, kein Datei-I/O): die gespeicherte Persona,
     * oder `null` wenn nie eine gesetzt wurde (⇒ die STANDARD-Persona greift).
     */
    fun persona(): Persona? = cached

    /**
     * **Atomar setzen — persist-then-commit** (Settings-PUT): ZUERST atomar auf die
     * Platte, DANN — nur bei bewiesenem Persist — der Cache. Schlägt der Schreibvorgang
     * fehl, WIRFT [writeSnapshot] und der Cache bleibt unangetastet
     * (`cache == letzter bewiesener Platten-Zustand`).
     *
     * @throws IOException wenn die Persistenz fehlschlägt (Cache dann NICHT verändert).
     */
    @Synchronized
    fun setPersona(persona: Persona) {
        writeSnapshot(persona)
        cached = persona
    }

    /** Datei lesen (best-effort, wirft NIE). Kaputt/fehlend/unbekannter Wert ⇒ kein Cache ⇒ `null`. */
    private fun reload() {
        cached = null
        runCatching {
            if (!Files.exists(path)) return
            val root = mapper.readTree(path.toFile()) ?: return
            cached = parseStrict(root.path(PERSONA_FIELD).asText(""))
        }
    }

    /** Temp-File im Zielverzeichnis + atomarer Rename; Schreib-Fehler WIRFT (Temp best-effort geräumt). */
    private fun writeSnapshot(persona: Persona) {
        val dir = path.parent ?: throw IOException("Persona-Pfad hat kein Verzeichnis: $path")
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, ".persona", ".tmp")
        try {
            Files.write(tmp, mapper.writeValueAsBytes(mapOf(PERSONA_FIELD to persona.name)))
            moveOnto(tmp, path)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(tmp) }
            throw e
        }
    }

    /** Atomarer Rename, mit Fallback für Dateisysteme ohne ATOMIC_MOVE. */
    private fun moveOnto(tmp: Path, target: Path) {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        /** Das eine JSON-Feld: `{"persona":"KUMPEL"}` (Enum-NAME, nicht der PascalCase-Wire-Code). */
        const val PERSONA_FIELD = "persona"

        /**
         * **Strikte Persona-Parse** (nicht [Persona.fromCode]!): [Persona.fromCode] kollabiert
         * Unbekanntes still auf STANDARD — genau falsch für einen persistierten Store, in dem ein
         * kaputter/veralteter Wert „nie gesetzt" (⇒ `null` ⇒ STANDARD-Fallback der Kette) heißen
         * MUSS, nicht „explizit STANDARD gewählt". Matcht case-insensitiv auf Enum-NAME ODER
         * Wire-[Persona.code]; unbekannt/leer ⇒ `null`.
         */
        fun parseStrict(raw: String?): Persona? {
            val c = raw?.trim().orEmpty()
            if (c.isEmpty()) return null
            return Persona.entries.firstOrNull { it.name.equals(c, ignoreCase = true) || it.code.equals(c, ignoreCase = true) }
        }
    }
}
