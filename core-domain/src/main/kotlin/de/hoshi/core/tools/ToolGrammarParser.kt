package de.hoshi.core.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * **ToolGrammarParser** — die PURE, fail-soft Extraktion der vom Brain
 * STRUKTURELL erzwungenen Tool-JSON (PATH B, `tool_grammar=true`).
 *
 * Das neue Wire-Format des e4b-Brain (`server_e4b.py`, :8041) ist ein EINZELNES
 * JSON-Objekt — NICHT mehr das alte `<|tool_call>…<tool_call|>`-Markerformat
 * (das ist unter dem Logits-Enforcer physisch unmöglich):
 * ```
 * {"tool": "<light_set|climate_set|scene_activate|read_state|none>", "args": {...}}
 * ```
 * - `args`-Werte können String ODER Zahl sein → hier zu **String** normalisiert,
 *   sodass die string-typisierte [AgenticToolRegistry.resolve] sie unverändert frisst.
 * - `"none"` = strukturelles, EHRLICHES Ablehnen (kein Fake-Confirm) → [Result.None].
 * - Defekt/kein JSON/fehlende Felder ⇒ [Result.Malformed] (wirft NIE).
 *
 * **Honesty/Leak-Schutz:** der Parser gibt NIE den Roh-Text zurück. Der Caller
 * spricht ausschließlich aufgelöste Quittungen/Absagen — die rohe JSON erreicht
 * den User nie (Residue-Guard im Geist von 0.5).
 *
 * Spring-frei; nur jackson-databind (parse-only) + [ParsedToolCall].
 */
object ToolGrammarParser {

    /** Ein gemeinsamer, thread-sicherer Reader (ObjectMapper.readTree ist thread-safe). */
    private val MAPPER = ObjectMapper()

    /**
     * Das Ergebnis der Tool-Grammar-Auswertung. Bewusst dreiwertig, damit der
     * Caller `none` (bewusstes Ablehnen) von `malformed` (kaputt/kein JSON)
     * UNTERSCHEIDEN und beides ehrlich – aber differenziert – behandeln kann.
     */
    sealed interface Result {
        /** Ein konkreter Tool-Wunsch (`tool != none`); via Registry aufzulösen. */
        data class Call(val parsed: ParsedToolCall) : Result

        /** `tool == "none"`: der Brain hat bewusst KEINE Tat gewählt (kein Fake-Confirm). */
        data object None : Result

        /** Kein/defektes JSON, fehlendes/leeres `tool`, falscher Typ. */
        data object Malformed : Result
    }

    /**
     * Parst den GESAMMELTEN Brain-Text (alle Tool-Turn-Deltas zusammengefügt).
     * Fail-soft: jeder Defekt ⇒ [Result.Malformed], nie eine Exception.
     */
    fun parse(raw: String): Result {
        val text = raw.trim()
        // Muss ein JSON-Objekt sein. Unter Tool-Grammar ist alles andere unmöglich;
        // taucht es doch auf (Forcing aus / Fallback), wird es NIE gesprochen → malformed.
        if (!text.startsWith("{")) return Result.Malformed

        val node: JsonNode = runCatching { MAPPER.readTree(text) }.getOrNull() ?: return Result.Malformed
        if (!node.isObject) return Result.Malformed

        val toolNode = node.get("tool")
        if (toolNode == null || !toolNode.isTextual) return Result.Malformed
        val tool = toolNode.asText().trim()
        if (tool.isEmpty()) return Result.Malformed
        if (tool == "none") return Result.None

        // args → Map<String,String>: Zahlen werden stringifiziert (asText), sodass die
        // registry-resolve (die brightness_pct/temperature via toIntOrNull liest) greift.
        val argsNode = node.get("args")
        val args = LinkedHashMap<String, String>()
        if (argsNode != null && argsNode.isObject) {
            val fields = argsNode.fields()
            while (fields.hasNext()) {
                val (key, value) = fields.next()
                if (value == null || value.isNull) continue
                args[key] = if (value.isValueNode) value.asText() else value.toString()
            }
        }
        return Result.Call(ParsedToolCall(tool, args))
    }
}
