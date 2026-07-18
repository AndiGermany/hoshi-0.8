package de.hoshi.core.tools

/**
 * **SceneMatcher** — matcht freien User-Text gegen die ECHTEN HA-`scene_id`s
 * (z.B. `wohnzimmer_nordlichter`, `kuche_gedimmt`, `flur_nachtlicht`). Reines
 * Kotlin, keine Side-Effects, kein Framework, kein Brain.
 *
 * Heute kennt der [de.hoshi.core.pipeline.DeterministicToolIntentClassifier] nur
 * den naiven Pfad `scene.<token nach "szene"/"scene">`. Dieser Matcher erlaubt
 * „mach die Nordlichter an" / „Szene Küche gedimmt" / „nachtlicht im flur" → den
 * passenden REALEN `scene_id`.
 *
 * **KONSERVATIV (false-positive-avers).** Der Algorithmus:
 *  1. Text → klein-normalisierte Tokens. **Wichtig:** die Normalisierung folgt der
 *     HA-Slugifizierung (ä→a, ö→o, ü→u, ß→ss — NICHT die ae/oe/ue-Expansion von
 *     [ToolAreas.slug]), weil HA seine `scene_id`s genau so bildet (Küche=`kuche`,
 *     Frühlingsblüten=`fruhlingsbluten`, Tropendämmerung=`tropendammerung`).
 *  2. Jeder `scene_id` → Tokens (split auf `_`).
 *  3. Score je Szene = Anzahl ihrer (distinkten) Tokens, die im Text-Token-Set
 *     vorkommen (Raum-Token zählen mit).
 *  4. **Distinktiv-Gate:** eine Szene ist nur Kandidat, wenn ein NICHT-Raum-,
 *     NICHT-generisches Token getroffen wurde (z.B. „nordlichter", „gedimmt",
 *     „nachtlicht"). Ein bloßer Raum-Token (wohnzimmer) ODER ein generisches
 *     Licht-Wort (licht/lampe) reicht NICHT → so kollidiert „wohnzimmer licht an"
 *     nicht mit einer Szene.
 *  5. Mehrere Kandidaten: höchster Score gewinnt; bei Gleichstand UND einem im
 *     Text genannten Raum die Szene mit passendem Raum (nur wenn EINDEUTIG); sonst
 *     (mehrdeutig) → `null`. Kein distinktiver Treffer → `null`.
 */
object SceneMatcher {

    /** @return den passenden `scene_id` (ohne `scene.`-Präfix) oder `null` (konservativ). */
    fun match(text: String, sceneIds: List<String>): String? {
        if (sceneIds.isEmpty()) return null
        val textTokens = tokenize(text).toSet()
        if (textTokens.isEmpty()) return null

        // Im Text genannte Räume → echte HA-area_id(s) (für den Gleichstands-Tie-Break).
        val textRooms: Set<String> = textTokens.mapNotNull { ToolAreas.ROOMS[it] }.toSet()

        val candidates = ArrayList<Candidate>()
        for (sceneId in sceneIds) {
            val sceneTokens = sceneId.split('_').filter { it.isNotBlank() }.distinct()
            if (sceneTokens.isEmpty()) continue
            val matched = sceneTokens.filter { it in textTokens }
            if (matched.isEmpty()) continue
            // Distinktiv-Gate: mind. ein getroffenes Token ist weder Raum noch generisch.
            val hasDistinctive = matched.any { it !in ROOM_TOKENS && it !in GENERIC_TOKENS }
            if (!hasDistinctive) continue
            val room = sceneTokens.firstOrNull { it in ROOM_TOKENS }
            candidates.add(Candidate(sceneId = sceneId, score = matched.size, room = room))
        }
        if (candidates.isEmpty()) return null

        val maxScore = candidates.maxOf { it.score }
        val top = candidates.filter { it.score == maxScore }
        if (top.size == 1) return top.first().sceneId

        // Gleichstand: nur auflösen, wenn der Text genau EINE der Top-Szenen über ihren
        // Raum eindeutig trifft. Sonst bleibt es mehrdeutig → kein Raten.
        if (textRooms.isNotEmpty()) {
            val roomMatched = top.filter { it.room != null && it.room in textRooms }
            if (roomMatched.size == 1) return roomMatched.first().sceneId
        }
        return null
    }

    private data class Candidate(val sceneId: String, val score: Int, val room: String?)

    /** HA-Slug-konforme Tokenisierung (ä→a, ö→o, ü→u, ß→ss), damit der Text gegen die
     *  realen `scene_id`s matcht (die genau so gebildet sind). */
    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss")
            .split(NON_ALNUM)
            .filter { it.isNotBlank() }

    private val NON_ALNUM = Regex("[^a-z0-9]+")

    /** Die echten HA-area_ids (aus [ToolAreas]) — ein bloßer Raum-Token ist NICHT distinktiv. */
    private val ROOM_TOKENS: Set<String> = ToolAreas.AREAS.toSet()

    /** Generische Licht-Wörter, die als Szenen-Token vorkommen (z.B. `wohnzimmer_tv_licht`),
     *  aber zu unspezifisch sind, um ALLEIN eine Szene zu triggern (false-positive-Schutz:
     *  „mach das licht an" darf keine Szene matchen — die Szene bleibt über „tv" greifbar). */
    private val GENERIC_TOKENS: Set<String> =
        setOf("licht", "lichter", "light", "lights", "lampe", "lampen", "lamp")
}
