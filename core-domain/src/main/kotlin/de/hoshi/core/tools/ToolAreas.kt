package de.hoshi.core.tools

/**
 * **ToolAreas** — die EINE Quelle der Wahrheit für Raum-/Area-Auflösung im Tool-Pfad.
 *
 * Zuvor lag dieselbe ROOMS/AREAS-Map doppelt (im `DeterministicToolIntentClassifier`
 * UND in der `AgenticToolRegistry`) → Drift-Risiko, wenn nur eine Kopie gepflegt wird.
 * Beide leben in `core-domain` und ziehen jetzt aus diesem `object` (saubere
 * Abhängigkeitsrichtung: `core.pipeline` → `core.tools`).
 *
 * Die [ROOMS]-Map ist **gegen Andis reale HA-Registry verifiziert** (2026-06-26,
 * read-only `{{ areas() }}` gegen HA 2025.4.4). Wichtig: HA slugifiziert ü→u
 * (NICHT ue) → „Küche" = `kuche` (nicht `kueche`). Licht-Entities je Area:
 * wohnzimmer 7 · kuche 6 · schlafzimmer 8 · arbeitszimmer 10 · flur 6 · keller 1 ·
 * **badezimmer 0** (existiert, hat aber keine Lichter).
 */
object ToolAreas {

    /** Raum-Wort → echte HA-`area_id` (DE + EN-Aliase), konservativ. */
    val ROOMS: Map<String, String> = mapOf(
        "wohnzimmer" to "wohnzimmer",
        "schlafzimmer" to "schlafzimmer", "bedroom" to "schlafzimmer",
        "küche" to "kuche", "kueche" to "kuche", "kuche" to "kuche", "kitchen" to "kuche",
        "arbeitszimmer" to "arbeitszimmer", "büro" to "arbeitszimmer",
        "buero" to "arbeitszimmer", "office" to "arbeitszimmer",
        "flur" to "flur", "hallway" to "flur",
        "keller" to "keller", "basement" to "keller",
        "bad" to "badezimmer", "badezimmer" to "badezimmer", "bathroom" to "badezimmer",
    )

    /**
     * Die echten HA-`area_id`s, **abgeleitet** aus [ROOMS] (keine zweite Liste pflegen).
     * `distinct().sorted()` → genau die 7 realen Areas, deterministisch geordnet.
     */
    val AREAS: List<String> = ROOMS.values.distinct().sorted()

    /**
     * Dieselben Areas wie [AREAS], aber in **deklarierter Reihenfolge** (Erstnennung
     * in [ROOMS], NICHT alphabetisch). Additiv — [AREAS] bleibt für seine
     * bestehenden Konsumenten ([SceneMatcher], `AgenticToolRegistry`) unverändert.
     * Für Anzeigen, bei denen die Reihenfolge zählt (z.B. eine Rückfrage „Wohnzimmer,
     * Schlafzimmer, Küche…?" statt der alphabetischen „Arbeitszimmer, Badezimmer…?")
     * — genutzt vom [de.hoshi.core.port.AreaCatalogPort]-Default.
     */
    val AREAS_ORDERED: List<String> = ROOMS.values.distinct().toList()

    /**
     * `area_id` → sprechbares deutsches Label (für warme Quittungen, z.B. die
     * Temperatur-Antwort). HA-slugs sind klein + ohne Umlaut (`kuche`); fürs Sprechen
     * wollen wir „Küche". Unbekannte Areas fallen auf den kapitalisierten Slug zurück.
     */
    val LABELS: Map<String, String> = mapOf(
        "wohnzimmer" to "Wohnzimmer",
        "schlafzimmer" to "Schlafzimmer",
        "kuche" to "Küche",
        "arbeitszimmer" to "Arbeitszimmer",
        "flur" to "Flur",
        "keller" to "Keller",
        "badezimmer" to "Badezimmer",
    )

    /** Sprechbares Label einer `area_id`; unbekannt ⇒ kapitalisierter Slug, `null` ⇒ leer. */
    fun label(areaId: String?): String {
        val id = areaId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return ""
        return LABELS[id] ?: id.replaceFirstChar { it.uppercase() }
    }

    /**
     * Raum-Wort → echte HA-`area_id`; unbekannt/leer ⇒ `null`. Trimmt + lowercased,
     * sodass beide Aufrufer (Classifier wie Registry) dieselbe Toleranz haben.
     */
    fun resolveArea(raw: String?): String? {
        val key = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return ROOMS[key]
    }

    /** Token-Splitter für [mentionsRoom] (identisch zum Classifier — DE-Buchstaben + Ziffern). */
    private val TOKEN_SPLIT = Regex("[^a-zäöüß0-9]+")

    /**
     * `true`, wenn [text] mindestens ein bekanntes Raum-Wort als eigenes Token nennt
     * (gegen [ROOMS], token-genau — kein Substring-Fehlfeuer). Für die Anaphern-Logik:
     * EXPLIZIT genannter Raum ⇒ kein Last-Area-Fallback (der genannte Raum gewinnt).
     */
    fun mentionsRoom(text: String): Boolean {
        if (text.isBlank()) return false
        return text.lowercase().split(TOKEN_SPLIT).any { it.isNotBlank() && ROOMS.containsKey(it) }
    }

    /**
     * Slug-Normalisierung (ä→ae …, Rest → `_`). `lowercase()` zuerst, damit auch
     * Roh-Namen mit Großbuchstaben (z.B. Szenen-Namen vom Brain) korrekt sluggen;
     * für die schon klein-tokenisierten Classifier-Tokens ist das ein No-op.
     */
    fun slug(s: String): String =
        s.lowercase()
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
            .replace(Regex("[^a-z0-9]+"), "_").trim('_')
}
