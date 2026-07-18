package de.hoshi.core.port

import de.hoshi.core.tools.ToolAreas

/**
 * Eine bekannte Area: die echte HA-`area_id` (bereits slugifiziert, z.B. `kuche`),
 * ihr sprechbarer Anzeigename (z.B. `Küche`) und die Wort-Aliase, über die ein
 * roher (klein geschriebener) Text-Token auf sie matcht — DE+EN, Umlaute + Fugen-
 * Formen (s. [ToolAreas.ROOMS] für die historische Quelle des statischen Defaults).
 *
 * [aliases] enthält bewusst NICHT zwingend [areaId]/[label] selbst — die Aufrufer
 * ([de.hoshi.core.pipeline.DeterministicToolIntentClassifier]) ergänzen area_id +
 * kleingeschriebenes Label defensiv als zusätzliche Match-Tokens.
 */
data class AreaInfo(
    val areaId: String,
    val label: String,
    val aliases: Set<String> = emptySet(),
)

/**
 * Hexagonaler Port, der die bekannten Räume ("areas") liefert — die Quelle der
 * Raum-Auflösung im Tool-Pfad ([de.hoshi.core.pipeline.DeterministicToolIntentClassifier]).
 * Der Kern kennt nur diese Naht (analog [SceneCatalogPort]); WOHER die Areas
 * kommen (die statische [ToolAreas]-Liste, oder künftig ein READ-ONLY-Sync gegen
 * die echte HA-Area-Registry) lebt im Adapter (`adapters-ha.HaAreaCatalogAdapter`).
 *
 * **[STATIC] ist der verhaltens-neutrale Default:** exakt die heutige
 * [ToolAreas.ROOMS]-Aliastabelle, byte-neutral ohne jedes Wiring — kein HA-Call,
 * niemals leer. Andi will die Liste künftig DYNAMISCH aus der echten HA-Area-
 * Registry synchron halten (Live-Befund 2026-07-15: eine in HA umbenannte oder
 * neu angelegte Area soll ohne Redeploy auftauchen) — dieser Port ist GENAU die
 * Naht dafür; der Classifier bleibt dabei komplett unverändert (er kennt nur den
 * Port, nie die konkrete Quelle).
 */
fun interface AreaCatalogPort {
    /** Bekannte Areas in stabiler Anzeige-Reihenfolge (für Aufzählungen wie eine Rückfrage). */
    fun areas(): List<AreaInfo>

    companion object {
        /**
         * Rein aus [ToolAreas] abgeleitet (keine zweite Wortliste pflegen): jede echte
         * `area_id` bekommt ALLE ihre historischen Alias-Wörter (aus [ToolAreas.ROOMS]
         * rückwärts gruppiert) + ihr sprechbares [ToolAreas.label]. Reihenfolge =
         * [ToolAreas.AREAS_ORDERED] (Erstnennung, NICHT alphabetisch — „Wohnzimmer"
         * soll vor „Keller" kommen).
         */
        val STATIC: AreaCatalogPort = AreaCatalogPort {
            val aliasesByArea: Map<String, Set<String>> =
                ToolAreas.ROOMS.entries.groupBy({ it.value }, { it.key }).mapValues { it.value.toSet() }
            ToolAreas.AREAS_ORDERED.map { id ->
                AreaInfo(areaId = id, label = ToolAreas.label(id), aliases = aliasesByArea[id].orEmpty())
            }
        }
    }
}
