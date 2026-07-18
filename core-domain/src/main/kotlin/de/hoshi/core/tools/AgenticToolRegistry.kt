package de.hoshi.core.tools

/**
 * **AgenticToolRegistry** — die PURE Brücke zwischen dem gemma-Tool-Layer und dem
 * Kernel-[ToolCall]. Zwei Aufgaben:
 *
 *  1. [schemas]: liefert die `tools`-JSON-Struktur, die der Brain (`/v1/chat`,
 *     `tools`-Feld) erwartet — eine Liste von `{"type":"function","function":{...}}`
 *     je agentischem Tool, deutsch beschrieben.
 *  2. [resolve]: mappt einen vom Brain emittierten + [ToolCallParser]-geparsten
 *     [ParsedToolCall] auf einen permit-kompatiblen [ToolCall] (oder `null`).
 *
 * Die definierten Tools sind bewusst deckungsgleich mit den
 * `CapabilityKernel.DEFAULT_PERMITS` (light.turn_on/off, scene.turn_on,
 * climate.set_temperature) — der Happy-Path Grantet. Räume werden via die EINE
 * kanonische [ToolAreas]-Map (geteilt mit dem `DeterministicToolIntentClassifier`)
 * auf die 7 echten HA-`area_id`s aufgelöst (Küche → `kuche`, NICHT `kueche`).
 *
 * Spring-frei, side-effect-frei: nur `kotlin-stdlib` + [ToolCall]/[ParsedToolCall].
 */
object AgenticToolRegistry {

    /**
     * Die gemma-`tools`-Schemas (ein `function`-Eintrag je Tool). Direkt als
     * `tools`-Feld in den `/v1/chat`-Body serialisierbar (reine Map/List/String).
     */
    fun schemas(): List<Map<String, Any?>> = listOf(
        function(
            name = "light_set",
            description = "Schaltet oder dimmt das Licht in einem Raum. " +
                "state=on schaltet ein, state=off schaltet aus. Optional brightness_pct " +
                "(0–100) zum Dimmen und color_name für eine Farbe.",
            properties = mapOf(
                "area" to stringParam("Der Raum, z.B. wohnzimmer, kuche, schlafzimmer.", ToolAreas.AREAS),
                "state" to stringParam("Gewünschter Zustand des Lichts.", listOf("on", "off")),
                "brightness_pct" to mapOf(
                    "type" to "integer",
                    "description" to "Helligkeit in Prozent (0–100), optional.",
                ),
                "color_name" to mapOf(
                    "type" to "string",
                    "description" to "Farbname (englisch), z.B. red, blue, warm. Optional.",
                ),
            ),
            required = listOf("area", "state"),
        ),
        function(
            name = "climate_set",
            description = "Setzt die Zieltemperatur der Heizung/Klima in einem Raum.",
            properties = mapOf(
                "area" to stringParam("Der Raum, z.B. wohnzimmer, schlafzimmer, badezimmer.", ToolAreas.AREAS),
                "temperature" to mapOf(
                    "type" to "integer",
                    "description" to "Zieltemperatur in Grad Celsius (z.B. 21).",
                ),
            ),
            required = listOf("area", "temperature"),
        ),
        function(
            name = "scene_activate",
            description = "Aktiviert eine benannte Szene (z.B. Kino, Entspannen).",
            properties = mapOf(
                "name" to mapOf(
                    "type" to "string",
                    "description" to "Name der Szene, die aktiviert werden soll.",
                ),
            ),
            required = listOf("name"),
        ),
    )

    /**
     * Mappt name+args eines geparsten gemma-Tool-Calls auf einen permit-kompatiblen
     * [ToolCall]. Unbekanntes Tool, fehlender/unbekannter Raum oder defekte
     * Pflicht-Args ⇒ `null` (fail-soft — dann übernimmt der normale Brain-Pfad).
     */
    fun resolve(parsed: ParsedToolCall): ToolCall? = when (parsed.name) {
        "light_set" -> resolveLight(parsed.args)
        "climate_set" -> resolveClimate(parsed.args)
        "scene_activate" -> resolveScene(parsed.args)
        else -> null
    }

    /** Die bekannten agentischen Tool-Namen (für Residue-Erkennung). */
    val NAMES: Set<String> = setOf("light_set", "climate_set", "scene_activate")

    /**
     * **Residue-Wächter (Honesty/Leak-Schutz).** Erkennt INTERNE Tool-Syntax, die der
     * Brain manchmal OHNE die `<|tool_call>`-Marker ausspuckt (live gemessen:
     * `climate_set{area:<|"|>schlafzimmer<|"|>,…}` — der reguläre Parser verfehlt das,
     * dann würde rohe Syntax + das `<|"|>`-Quote-Token an den User geleakt). Beides darf
     * NIE in einer echten Antwort stehen → der Caller gibt stattdessen eine warme Absage.
     */
    fun looksLikeResidue(text: String): Boolean =
        text.contains("<|\"|>") || NAMES.any { text.contains("$it{") }

    // ── light_set → light.turn_on / light.turn_off (area-getargetet) ─────────────
    private fun resolveLight(args: Map<String, String>): ToolCall? {
        val area = ToolAreas.resolveArea(args["area"]) ?: return null
        val brightness = args["brightness_pct"]?.toIntOrNull()
        val color = args["color_name"]?.takeIf { it.isNotBlank() }
        // „an", sobald state=on ODER eine helligkeit/farbe gesetzt ist; sonst „aus".
        val on = args["state"] == "on" || brightness != null || color != null

        val data = LinkedHashMap<String, Any?>()
        data["area_id"] = area
        if (on) {
            // brightness_pct/color_name sind NUR im turn_on-Permit erlaubt.
            brightness?.let { data["brightness_pct"] = it.coerceIn(0, 100) }
            color?.let { data["color_name"] = it }
        }
        return ToolCall(
            domain = "light",
            service = if (on) "turn_on" else "turn_off",
            entityId = null,
            data = data,
        )
    }

    // ── climate_set → climate.set_temperature (area-getargetet) ──────────────────
    private fun resolveClimate(args: Map<String, String>): ToolCall? {
        val area = ToolAreas.resolveArea(args["area"]) ?: return null
        val raw = args["temperature"] ?: return null
        // (N2) HA lehnt Extremwerte sonst graceful ab → defensiv in eine plausible
        // Heiz-/Klima-Range klemmen (5–35 °C), statt einen 99/-10-Wert weiterzureichen.
        val temp = (raw.toIntOrNull() ?: raw.toDoubleOrNull()?.toInt() ?: return null).coerceIn(5, 35)
        return ToolCall(
            domain = "climate",
            service = "set_temperature",
            entityId = null,
            data = mapOf("area_id" to area, "temperature" to temp),
        )
    }

    // ── scene_activate → scene.turn_on (entity-getargetet via slug) ──────────────
    private fun resolveScene(args: Map<String, String>): ToolCall? {
        val name = args["name"]?.takeIf { it.isNotBlank() } ?: return null
        val s = ToolAreas.slug(name)
        if (s.isBlank()) return null
        return ToolCall(domain = "scene", service = "turn_on", entityId = "scene.$s")
    }

    // ── gemma-Schema-Helfer ─────────────────────────────────────────────────────
    private fun function(
        name: String,
        description: String,
        properties: Map<String, Any?>,
        required: List<String>,
    ): Map<String, Any?> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to name,
            "description" to description,
            "parameters" to mapOf(
                "type" to "object",
                "properties" to properties,
                "required" to required,
            ),
        ),
    )

    private fun stringParam(description: String, enum: List<String>): Map<String, Any?> =
        mapOf("type" to "string", "description" to description, "enum" to enum)
}
