package de.hoshi.core.pipeline

/**
 * **Geteilte Recherche-Muster** (Andi-Auftrag 2026-07-19, „recherchiere online"
 * soll ein eigenes, gründlicheres Modell rufen — s. [ResearchIntentRecognizer]):
 * ZWEI Bauteile teilen sich dieselben Muster — [LookupIntentRecognizer] (Naht C:
 * IST das überhaupt eine Nachschlag-Bitte?) UND [ResearchIntentRecognizer] (die
 * ENGERE Frage: ist es speziell die „Recherche"-Wortfamilie, die das teurere
 * Modell rechtfertigt?) — EINE Regex-Wahrheit statt zweier Kopien.
 *
 *  - Der VERB-Stamm „recherchier" (recherchiere/recherchierst/recherchiert/
 *    recherchieren) ist ein unzweideutiger Imperativ/Infinitiv — triggert
 *    ALLEIN, an jeder Wortposition (STT-tolerant, `(?:^| )`-Anker wie überall
 *    in dieser Datei).
 *  - Das NOMEN „Recherche" triggert NUR in der Zeige-Kombination „Recherche
 *    dazu/hierzu/davon/darüber" — UND NUR, wenn UNMITTELBAR davor KEIN Artikel/
 *    Possessivpronomen steht: „meine/die/eine/seine … Recherche darüber ergab…"
 *    ist eine AUSSAGE über eine vorhandene Recherche, keine Bitte, und darf NIE
 *    matchen (false-positive-avers, Gegen-Tests in [LookupIntentRecognizerTest]).
 */
private val RECHERCHE_VERB_STANDALONE = Regex("(?:^| )recherchier")

/** Artikel/Possessivpronomen, die „Recherche" zur AUSSAGE statt zur BITTE machen (s.o.). */
private const val RECHERCHE_DETERMINER_EXCLUSION =
    "(?<!meine )(?<!meiner )(?<!dein )(?<!deine )(?<!deiner )" +
        "(?<!sein )(?<!seine )(?<!seiner )(?<!ihre )(?<!ihrer )(?<!unsere )(?<!unserer )" +
        "(?<!eure )(?<!eurer )(?<!die )(?<!der )(?<!des )(?<!eine )(?<!einer )(?<!ein )" +
        "(?<!keine )(?<!keiner )(?<!diese )(?<!dieser )(?<!jene )(?<!jener )"
private val RECHERCHE_NOUN_DIRECTIVE = Regex(
    "(?:^| )$RECHERCHE_DETERMINER_EXCLUSION" +
        "recherche (?:dazu|hierzu|davon|dar(?:ü|ue)ber)(?: |$)",
)

/**
 * **LookupIntentRecognizer** — der deterministische Erkenner einer EXPLIZITEN
 * Nachschlag-Bitte („schau bitte online nach", „guck im Internet", „schlag das
 * online nach", „recherchier das", EN „look it up online", „search online").
 *
 * Motivation (Live-Befund): auf „Schaust du bitte online nach?" freestylet das
 * lokale 4B eine Antwort ÜBER „das Internet", statt zu eskalieren — weil dieser
 * Satz für die Pipeline nur Prosa ist, kein Intent. Dieser Erkenner macht die
 * Bitte zu einem deterministischen Signal: der Orchestrator behandelt sie als
 * Consent (existiert ein offenes Nachschlag-Angebot ⇒ einlösen; sonst die
 * vorherige Frage direkt eskalieren — die explizite Bitte IST der Consent).
 *
 * **Konservativ (false-positive-avers), DE+EN, STT-tolerant (lowercase):** ein
 * Treffer verlangt ENTWEDER ein unzweideutiges Standalone-Wort ([STANDALONE],
 * z.B. „recherchier") ODER die KOMBINATION aus einem Nachschau-Verb
 * ([VERB_PATTERNS], „schau/guck/schlag/such/look/search …") UND einem
 * Online-Scope-Marker ([SCOPE_PATTERNS], „online/internet/netz/web") — eine reine
 * Wissensfrage ÜBER das Internet („Wie funktioniert das Internet?", „Was ist
 * Online-Banking?") trägt keinen Nachschau-Verb und matcht NIE. Alle Muster laufen
 * gegen den NORMALISIERTEN Text (lowercase, Apostrophe weg, nur Buchstaben/Ziffern
 * + einzelne Spaces — wie [EscalationModeFastpath]) und ankern Wort-Grenzen als
 * `(?:^| )` statt `\b` (ASCII-`\b` stolpert über Umlaute).
 *
 * Bewusst ein eigenes, quellen-unabhängiges Bauteil (Muster [AffirmationRecognizer]
 * / [LocationAnswerRecognizer]): enger geschnitten als der [OnlineRequestDetector]
 * (der auch „gibt es …?"/„wie viele …?" fängt und darum als DIREKT-Eskalations-
 * Trigger zu breit wäre) — nur die unmissverständliche „schlag-es-nach"-Bitte
 * darf ohne Rückfrage nach draußen führen.
 */
object LookupIntentRecognizer {

    /** Lowercase, Apostrophe weg, alles außer DE-Buchstaben/Ziffern → Space (wie [EscalationModeFastpath]). */
    private fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("[’'`´ʼ]"), "")
            .replace(Regex("[^a-zäöüß0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Unzweideutige Standalone-Bitten — triggern ALLEIN (kein Scope-Marker nötig):
     * „recherchier(e) das" ist ohne jeden Zusatz eine Nachschlag-Bitte. Die
     * Recherche-Muster (Verb-Stamm + Nomen-Zeige-Kombination) sind mit
     * [ResearchIntentRecognizer] GETEILT (s. dessen KDoc) — jeder Recherche-
     * Treffer ist darum strukturell auch ein Naht-C-Treffer.
     */
    private val STANDALONE: List<Regex> = listOf(
        RECHERCHE_VERB_STANDALONE,
        RECHERCHE_NOUN_DIRECTIVE,
        Regex("(?:^| )web ?suche"),
        Regex("(?:^| )internet ?suche"),
        // EN-Pendants (Andi-Vorfall 2026-07-25): Wendungen, die OHNE Netz-Marker
        // unmissverständlich eine Nachschau-Bitte SIND. Ein blosses „google"
        // fehlt bewusst („What is Google?" darf nie triggern) — es zählt nur mit
        // Objekt/Modal davor/dahinter.
        Regex("(?:^| )look (?:it|that|this|them) up"),
        Regex("(?:^| )(?:search|browse) the (?:web|internet|net)"),
        Regex("(?:^| )google (?:it|that|this|for me)"),
        Regex("(?:^| )(?:can|could|please|just) (?:you )?google"),
    )

    /**
     * Nachschau-Verben (am Wort-ANFANG, damit „besuch"/„versuch"/„sehr" NICHT über
     * „such"/„seh" mitgefangen werden) — nur in KOMBINATION mit einem Scope-Marker.
     * „schau" deckt „schaust/schaue/schauen", „guck" deckt „guckst" usw.
     *
     * **EN „research"** (Andi-Auftrag 2026-07-20, Sprachpaket-Kern): mirrors
     * DE „recherchier" — nur in Kombination mit einem [SCOPE_PATTERNS]-Marker
     * (also z.B. „research online"), NICHT standalone wie das DE-Pendant, weil
     * „research" im Englischen auch ein bloßes NOMEN ist („my research shows…")
     * — konservativ, false-positive-avers wie der Rest dieser Datei.
     */
    private val VERB_PATTERNS: List<Regex> = listOf(
        Regex(
            "(?:^| )(?:schau|guck|sieh|schlag|schläg|nachschau|nachguck|nachschlag|such|prüf|pruef|check|" +
                // EN (Andi-Vorfall 2026-07-25): „look"/„search"/„check" sind häufige
                // Alltagswörter und zählen — wie ihre DE-Vorbilder — NUR mit einem
                // [SCOPE_PATTERNS]-Marker. „find out" bewusst ZWEIWORTIG (das blosse
                // „find"/„finde" fängt sonst „Ich finde das Internet toll"); „google"
                // fehlt hier bewusst (es stünde sonst auch in „Was macht Google
                // online?" — einer Aussage ÜBER die Firma) und steht stattdessen in
                // [STANDALONE], wo es ein Objekt/Modal neben sich braucht.
                "look|search|research|find out|browse)",
        ),
    )

    /**
     * Online-Scope-Marker als GANZE Tokens („online"/„internet"/„netz"/„web") —
     * deckt auch „im internet"/„ins netz"/„im web" (der Scope-Token steht dort als
     * eigenes Wort), ohne „webseite"/„onlineshop" mitzufangen (Token-Grenze `(?: |$)`).
     */
    private val SCOPE_PATTERNS: List<Regex> = listOf(
        Regex("(?:^| )(?:online|internet|netz|web)(?: |$)"),
    )

    /**
     * TRUE gdw. [text] eine explizite Nachschlag-Bitte ist: ein Standalone-Wort ODER
     * (Nachschau-Verb UND Online-Scope). Leer ⇒ false.
     */
    fun matches(text: String): Boolean {
        val norm = normalize(text)
        if (norm.isEmpty()) return false
        if (STANDALONE.any { it.containsMatchIn(norm) }) return true
        val hasVerb = VERB_PATTERNS.any { it.containsMatchIn(norm) }
        val hasScope = SCOPE_PATTERNS.any { it.containsMatchIn(norm) }
        return hasVerb && hasScope
    }

    /**
     * Füllwörter, die zum Intent-Präfix gehören, aber selbst keine Query tragen.
     * EN-Ergänzungen (2026-07-25) sind bewusst Wörter, die es im Deutschen NICHT
     * gibt — der Scanner ist sprach-agnostisch, DE-Sätze dürfen sich dadurch nicht
     * um ein Byte anders verhalten.
     */
    private val FILLER_WORDS = setOf("bitte", "mal", "kurz", "doch", "please", "just", "quickly")

    /** Wie [SCOPE_PATTERNS], als Token-Set für den Wort-für-Wort-Scanner in [extractInlineQuery]. */
    private val SCOPE_WORDS = setOf("online", "internet", "netz", "web")

    /**
     * Präpositionen (+ EN-Artikel) direkt VOR einem Scope-Wort („im Internet",
     * „ins Netz", „on **the** web") — vom [SCOPE_PATTERNS]-Regex bereits toleriert
     * (der Anker prüft nur das Scope-Wort selbst, nicht was davor steht); der
     * Wort-für-Wort-Scanner braucht diese Kategorie EXPLIZIT, sonst bricht er an
     * „im"/„on" ab, bevor er das folgende Scope-Wort erreicht. Nur konsumiert,
     * wenn nach höchstens [MAX_SCOPE_PREP_HOPS] weiteren Tokens dieser Kategorie
     * WIRKLICH ein Scope-Wort folgt ([leadsToScope]) — sonst ist „im"/„in"/„the"
     * schon Teil der eigentlichen Query. Genau diese Bedingung macht das sehr
     * häufige „the" hier ungefährlich: es wird NUR als Anlauf auf „web/internet/
     * net" geschluckt, nie mitten in einer Frage.
     */
    private val SCOPE_PREPOSITIONS = setOf("im", "ins", "in", "on", "the")

    /** „on the web" = 2 Anläufe („on", „the") bis zum Scope-Wort — mehr braucht keine Sprache. */
    private const val MAX_SCOPE_PREP_HOPS = 2

    /**
     * TRUE gdw. ab Token [index] (exklusiv) nach höchstens [MAX_SCOPE_PREP_HOPS]
     * weiteren [SCOPE_PREPOSITIONS]-Tokens ein [SCOPE_WORDS]-Token folgt.
     */
    private fun leadsToScope(tokens: List<MatchResult>, index: Int): Boolean {
        var j = index + 1
        var hops = 0
        while (j < tokens.size && hops <= MAX_SCOPE_PREP_HOPS) {
            val next = tokenNorm(tokens[j].value)
            if (next in SCOPE_WORDS) return true
            if (next !in SCOPE_PREPOSITIONS) return false
            j++
            hops++
        }
        return false
    }

    /**
     * **EN-Rahmenwörter der Bitte** (Modal / Subjekt / Partikel / unbestimmter
     * Artikel), die zum Intent-Präfix gehören, aber selbst kein Thema tragen:
     * „**can you** look **it up**", „**take a** look online **for** …",
     * „check **it out** online". Wie [FILLER_WORDS] nur im Präfix-Scan wirksam —
     * das erste Token AUSSERHALB aller Kategorien beendet den Scan, ab dort ist
     * alles Query. Bewusst KEIN Wort, das auch deutsch ist (insbesondere NICHT
     * „an"): der Scanner ist sprach-agnostisch, DE muss byte-gleich bleiben.
     */
    private val EN_FRAME_WORDS = setOf(
        "can", "could", "would", "you", "do", "take", "a", "for", "up",
        "it", "that", "this", "out", "me", "us", "some",
    )

    /**
     * Wie [VERB_PATTERNS], als Präfix-Liste für den Wort-für-Wort-Scanner in
     * [extractInlineQuery] („schau" deckt „schaust/schaue/schauen" per `startsWith` ab).
     */
    private val VERB_STEMS = listOf(
        "schau", "guck", "sieh", "schlag", "schläg", "nachschau", "nachguck",
        "nachschlag", "such", "prüf", "pruef", "check", "look", "search", "research",
        // EN (2026-07-25) — „find" fehlt hier ABSICHTLICH als Stamm (es würde per
        // `startsWith` auch „findings" in einer echten Query schlucken); die
        // zweiwortige Form „find out" hat einen eigenen Scanner-Zweig.
        "browse", "google",
    )

    /** Das trennbare Zweitwort von „find out" (eigener Scanner-Zweig, s. [VERB_STEMS]). */
    private const val FIND_OUT_PARTICLE = "out"

    /** s. [RECHERCHE_DETERMINER_EXCLUSION] — dieselbe Determiner-Liste, hier als Token-Set. */
    private val RECHERCHE_NOUN_DETERMINERS = setOf(
        "meine", "meiner", "dein", "deine", "deiner", "sein", "seine", "seiner",
        "ihre", "ihrer", "unsere", "unserer", "eure", "eurer", "die", "der",
        "des", "eine", "einer", "ein", "keine", "keiner", "diese", "dieser",
        "jene", "jener",
    )

    /** Die Zeige-Wörter, die aus dem bloßen Nomen „Recherche" eine Bitte machen (s. [RECHERCHE_NOUN_DIRECTIVE]). */
    private val RECHERCHE_NOUN_FOLLOWERS = setOf("dazu", "hierzu", "davon", "darüber", "darueber")

    /**
     * **Subjekt-Pronomen einer invertierten Frageform** (Andi-Live-Repro 2026-07-20,
     * Teil B: „Schaust **du** online nach?" — eine reine Consent-Bitte OHNE eigenen
     * Sachinhalt — bekam als vermeintlichen Inline-Rest „du online nach?" zurück
     * und eskalierte DAS statt der vorherigen Sachfrage. Grund: nach dem
     * konsumierten Verb-Stamm „schau…" steht bei einer DE-Inversionsfrage
     * zwangsläufig das Subjekt-Pronomen — grammatische Hülle, kein Sachinhalt.
     * Konsumiert (NICHT als eigener Trigger — s. Scanner-Branch unten) NUR wenn
     * [consumedTrigger] bereits `true` ist (wir sind noch im erkannten Präfix);
     * an Token-Index 0 (kein Trigger bisher) bleibt jedes Vorkommen unverändert
     * beim `else`-Zweig, der [restFrom] mit `consumedTrigger=false` aufruft ⇒
     * ohnehin `null` (Konservativ-Vertrag oben unverändert).
     */
    private val PRONOUN_WORDS = setOf("du", "ihr", "sie", "er", "wir")

    /** Mindestlänge einer extrahierten Inline-Query — kürzer ist mit hoher Wahrscheinlichkeit nur ein Füllwort-Rest. */
    private const val MIN_INLINE_QUERY_LENGTH = 8

    /** Satzzeichen, die am Ende einer extrahierten Query nichts verloren haben. */
    private val TRAILING_PUNCTUATION = ".,;:!"

    /** Führende/nachgestellte Nicht-Buchstaben/-Ziffern (behält Umlaute/ß über `\p{L}`). */
    private val EDGE_PUNCTUATION = Regex("^[^\\p{L}\\p{Nd}]+|[^\\p{L}\\p{Nd}]+$")

    /** Normalisiertes Vergleichs-Token: lowercase, ohne Rand-Satzzeichen (Apostrophe/Umlaute bleiben Buchstaben). */
    private fun tokenNorm(raw: String): String = raw.lowercase().replace(EDGE_PUNCTUATION, "")

    /**
     * TRUE gdw. an Token-Index [index] ein „recherche"-NOMEN in Zeige-Kombination
     * steht (Muster [RECHERCHE_NOUN_DIRECTIVE]): das FOLGENDE Token ist ein
     * Zeige-Wort ([RECHERCHE_NOUN_FOLLOWERS]) UND das VORANGEHENDE Token (falls
     * vorhanden) ist KEIN Artikel/Possessivpronomen ([RECHERCHE_NOUN_DETERMINERS])
     * — „meine Recherche dazu" bleibt eine Aussage, keine Bitte.
     */
    private fun isRechercheDirective(tokens: List<MatchResult>, index: Int): Boolean {
        val next = tokens.getOrNull(index + 1)?.value?.let(::tokenNorm) ?: return false
        if (next !in RECHERCHE_NOUN_FOLLOWERS) return false
        val prev = tokens.getOrNull(index - 1)?.value?.let(::tokenNorm)
        return prev == null || prev !in RECHERCHE_NOUN_DETERMINERS
    }

    /**
     * Der ABGESTREIFTE Rest ab Token-Index [index] im ORIGINAL-[text] (Groß-/
     * Kleinschreibung + Ziffern erhalten — „GTA 6" bleibt „GTA 6", nicht „gta 6")
     * — `null`, wenn [consumedTrigger] nie wahr wurde (kein erkanntes Präfix),
     * kein Rest übrig ist, der Rest unter [MIN_INLINE_QUERY_LENGTH] Zeichen liegt,
     * oder der Rest NUR aus Füllwörtern besteht (alle drei: kein brauchbarer
     * Inline-Query-Kandidat — der Aufrufer fällt dann auf die bestehende
     * Rückfrage/Vorherige-Frage-Logik zurück).
     */
    private fun restFrom(tokens: List<MatchResult>, index: Int, text: String, consumedTrigger: Boolean): String? {
        if (!consumedTrigger || index >= tokens.size) return null
        val rest = text.substring(tokens[index].range.first)
            .trim()
            .trimEnd { it in TRAILING_PUNCTUATION }
            .trim()
        if (rest.length < MIN_INLINE_QUERY_LENGTH) return null
        val allFiller = rest.split(Regex("\\s+")).all { tokenNorm(it).let { t -> t.isEmpty() || t in FILLER_WORDS } }
        if (allFiller) return null
        return rest
    }

    /**
     * **Naht-C-Inline-Query-Extraktion** (Andi-Fix 2026-07-20, Live-Repro: „Schau
     * bitte online nach, wann GTA 6 erscheint." bekam trotz enthaltener Frage die
     * Rückfrage „was genau soll ich nachschauen?" statt sie zu erkennen).
     *
     * Läuft NUR, wenn [text] bereits als Lookup-/Recherche-Bitte erkannt wurde
     * (Aufrufer-Vertrag, s. [TurnOrchestrator.lookupIntentTurn]) — scannt dann
     * PRÄFIX-ANCHORED (bewusst konservativ: ein Trigger, der nicht am Anfang der
     * Äußerung steht, wird NICHT geöffnet, s.u.) Wort für Wort vom Anfang, solange
     * jedes Token EINES von diesen ist:
     *  - ein Füllwort ([FILLER_WORDS]: bitte/mal/kurz/doch, EN please/just),
     *  - eine Präposition/ein Artikel direkt VOR einem Scope-Wort
     *    ([SCOPE_PREPOSITIONS] + [leadsToScope]: „im Internet", „ins Netz",
     *    „on the web"),
     *  - ein EN-Rahmenwort ([EN_FRAME_WORDS]: „**can you** look **it up**",
     *    „**take a** look online **for** …" — Andi-Vorfall 2026-07-25: ohne diese
     *    Kategorie brach der Scan schon am ersten Token „Take" ab und die im Satz
     *    stehende Frage („a recept of pizza") ging verloren),
     *  - ein Online-Scope-Wort ([SCOPE_WORDS]),
     *  - das trennbare Verb-Partikel „nach" (nur NACHDEM ein Trigger schon griff —
     *    „schau … nach" ist EIN Verb, „nachschauen" getrennt geschrieben),
     *  - ein Subjekt-Pronomen einer invertierten Frageform ([PRONOUN_WORDS]: „du"/
     *    „ihr"/„sie"/„er"/„wir" — NUR nachdem ein Trigger schon griff, z.B.
     *    „Schaust **du** online nach?", Andi-Live-Repro 2026-07-20 Teil B — s.
     *    [PRONOUN_WORDS]-KDoc),
     *  - der Recherche-Verb-Stamm („recherchier…"),
     *  - die zweiwortige EN-Form „find out" ([FIND_OUT_PARTICLE]),
     *  - „Recherche" in Zeige-Kombination ([isRechercheDirective]),
     *  - eines der Nachschau-Verben ([VERB_STEMS], per `startsWith` — deckt
     *    Konjugationen wie „schaust/schauen").
     *
     * Das ERSTE Token, das KEINER dieser Kategorien entspricht, beendet den Scan —
     * alles AB DORT (im Original-Text, Groß-/Kleinschreibung erhalten) ist die
     * Kandidat-Query ([restFrom] prüft Mindestlänge + Nicht-nur-Füllwörter).
     *
     * **Konservativ (false-negative statt false-positive):** kein erkanntes
     * Präfix am ALLERERSTEN Token (z.B. „Kannst du das online nachschauen" — der
     * Verb-Treffer „nachschauen" steht dort NICHT am Anfang) ⇒ `null`, der
     * Aufrufer fällt auf sein bestehendes Verhalten zurück (Pending/vorherige
     * Frage/Rückfrage) — kein Rate-Risiko bei uneindeutiger Wortstellung.
     *
     * `null` ⇒ „heutiges Verhalten" (Rückfrage/vorherige Frage), s. [restFrom]
     * für die Details der Mindestlänge/Füllwort-Prüfung.
     */
    fun extractInlineQuery(text: String): String? {
        val tokens = Regex("\\S+").findAll(text).toList()
        if (tokens.isEmpty()) return null
        var i = 0
        var consumedTrigger = false
        while (i < tokens.size) {
            val norm = tokenNorm(tokens[i].value)
            when {
                norm.isEmpty() -> i++
                norm in FILLER_WORDS -> i++
                norm == "nach" && consumedTrigger -> i++
                norm in PRONOUN_WORDS && consumedTrigger -> i++
                norm in SCOPE_PREPOSITIONS && leadsToScope(tokens, i) -> i++
                norm in EN_FRAME_WORDS -> i++
                norm in SCOPE_WORDS -> { consumedTrigger = true; i++ }
                norm == "find" && tokens.getOrNull(i + 1)?.value?.let(::tokenNorm) == FIND_OUT_PARTICLE -> {
                    consumedTrigger = true
                    i += 2
                }
                norm.startsWith("recherchier") -> { consumedTrigger = true; i++ }
                norm == "recherche" && isRechercheDirective(tokens, i) -> { consumedTrigger = true; i += 2 }
                VERB_STEMS.any { norm.startsWith(it) } -> { consumedTrigger = true; i++ }
                else -> return restFrom(tokens, i, text, consumedTrigger)
            }
        }
        return restFrom(tokens, i, text, consumedTrigger)
    }
}

/**
 * **ResearchIntentRecognizer** — der ENGERE Erkenner eines expliziten
 * „recherchiere/recherche"-Imperativs (Andi-Auftrag 2026-07-19: „recherchiere
 * (das) online" soll ein eigenes, GRÜNDLICHERES (und teureres) Modell rufen,
 * die gpt-5.6-Familie statt des Nano-Defaults, s.
 * [de.hoshi.adapters.escalation.EscalationModelCatalog]).
 *
 * **Strenge TEILMENGE von [LookupIntentRecognizer]** (geteilte Muster, s. deren
 * KDoc am Datei-Kopf): jeder [matches]-Treffer hier ist AUCH ein
 * [LookupIntentRecognizer.matches]-Treffer — dieser Recognizer entscheidet NUR
 * die MODELL-Wahl innerhalb der bereits laufenden Naht C, er öffnet KEINEN
 * eigenen Eingang in den Orchestrator. Der [de.hoshi.core.pipeline.TurnOrchestrator]
 * nutzt ihn NUR, wenn ein Recherche-Modell konfiguriert ist (Property
 * `hoshi.escalation.research-model` nicht leer) — sonst bleibt JEDE „recherchiere…"-
 * Bitte auf dem Standard-Modell, exakt wie vor diesem Auftrag (byte-neutral).
 *
 * Semantik (Andi-Vorgabe): der explizite Imperativ IST selbst der Consent — kein
 * zusätzliches Deflect-Nachfragen (identisch zu jeder anderen Naht-C-Bitte); der
 * Tages-Kosten-Cap bleibt unangetastet und greift für BEIDE Modelle gemeinsam
 * (EIN Spend-Store, s. PipelineConfig-Wiring — kein zweites, umgehbares Budget).
 */
object ResearchIntentRecognizer {

    /** Lowercase, Apostrophe weg, alles außer DE-Buchstaben/Ziffern → Space (wie [LookupIntentRecognizer]). */
    private fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("[’'`´ʼ]"), "")
            .replace(Regex("[^a-zäöüß0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * TRUE gdw. [text] eine explizite Recherche-Bitte ist: der VERB-Stamm
     * „recherchier…" (deckt „recherchiere/recherchier das/recherchiere online/
     * online recherchieren" — der Stamm matcht unabhängig von Wortposition)
     * ODER die Nomen-Zeige-Kombination „Recherche dazu/hierzu/davon/darüber"
     * OHNE vorangehenden Artikel/Possessivpronomen. Leer ⇒ false.
     */
    fun matches(text: String): Boolean {
        val norm = normalize(text)
        if (norm.isEmpty()) return false
        return RECHERCHE_VERB_STANDALONE.containsMatchIn(norm) || RECHERCHE_NOUN_DIRECTIVE.containsMatchIn(norm)
    }
}

/**
 * **BrainAbstainRecognizer** — der KONSERVATIVE Erkenner eines ehrlichen
 * „das weiß ich nicht" in einer BEREITS GESTREAMTEN Brain-Antwort (DE+EN).
 *
 * Motivation (Live-Befund, Wurzel a): passt das lokale Brain bei einer
 * FACT_SHORT-Frage ehrlich („Gibt es einen 12,50-Euro-Schein?" ⇒ „Das weiß ich
 * nicht sicher."), erzeugt dieses ehrliche Passen heute KEIN offenes Nachschlag-
 * Angebot (nur der Code-Deflect der FactCoverage-Stufe tut das) — ein späteres
 * „ja"/„schau online nach" hätte also nichts einzulösen. Dieser Erkenner schließt
 * die Lücke: erkennt der Orchestrator das ehrliche Passen, registriert er ein
 * [PendingLookup] (ohne die Antwort anzufassen), das der Consent-/Intent-Pfad
 * dann einlösen kann.
 *
 * **Bewusst Phrasen-Heuristik (Rest-Risiko, im Report benannt):** die
 * Antwort-Entropie ([de.hoshi.core.dto.LlmDelta.logprob] → `answerEntropy`) wäre
 * das robustere Signal, ist aber heute NUR Messung (kein Verhalten hängt am Wert,
 * „Abstain = S2 nach Kalibrierung") und braucht logprob-Deltas eines gepatchten
 * Brains. Bis dahin ist die Phrasen-Erkennung die einzig verfügbare Naht — darum
 * ENG gehalten (nur starke „ich-weiß-es-nicht"-Marker) und wirkungs-arm gebaut:
 * ein false-positive kostet höchstens ein Nachschlag-Angebot, das nach TTL
 * ([PendingLookupPort.DEFAULT_TTL]) ungenutzt verfällt — es ändert NIE die Antwort.
 *
 * Der Erkenner normalisiert nur lowercase + Apostrophe-Strip (damit „don't"→„dont",
 * „i'm"→„im", „can't"→„cant") und prüft auf ganze Marker-Substrings.
 */
object BrainAbstainRecognizer {

    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("[’'`´ʼ]"), "")

    /**
     * Starke, eng gefasste Abstain-Marker (DE+EN, Apostrophe-frei). Nur unzweideutige
     * „ich weiß es (gerade) nicht / bin nicht sicher / keine Ahnung"-Wendungen — keine
     * weichen Hedges („ich glaube", „vermutlich"), die auch in einer echten Antwort stehen.
     */
    private val MARKERS: List<String> = listOf(
        // DE
        "weiß ich nicht", "weiss ich nicht", "das weiß ich nicht", "das weiss ich nicht",
        "weiß ich leider nicht", "weiß ich gerade nicht", "weiß ich grad nicht",
        "bin ich nicht sicher", "bin ich mir nicht sicher", "nicht sicher sagen",
        "nicht sicher parat", "nicht sicher, ob", "da bin ich überfragt", "da bin ich ueberfragt",
        "keine ahnung", "kann ich dir nicht sagen", "kann ich nicht sagen",
        "hab ich nicht parat", "habe ich nicht parat", "hab ich gerade nicht parat",
        "hab ich grad nicht parat", "entzieht sich meiner kenntnis",
        // DE — Testprotokoll 20.07 ~1:00 (Wurzel a): das lokale Brain umging die
        // obigen Marker deterministisch mit „keine Echtzeitdaten vorrätig — frag
        // lieber einen Börsen…"-artigen Formulierungen — eine EIGENE, unzweideutige
        // Marker-Klasse: „(keine|hab/habe keine) Echtzeit-/aktuelle Daten" ist IMMER
        // ein ehrliches Passen (kein Hedge, der Wissen enthält), egal wo im Text.
        "keine echtzeitdaten", "keine echtzeit-daten", "keine echtzeitinformationen",
        "keine aktuellen daten", "keine aktuelle daten", "keine live-daten", "keine livedaten",
        "hab keine echtzeitdaten", "habe keine echtzeitdaten",
        // EN
        "i dont know", "i do not know", "i am not sure", "im not sure",
        "not entirely sure", "not quite sure", "no idea",
        "cant say for sure", "cannot say for sure", "im not certain", "i am not certain",
        // EN — Pendant zur Echtzeitdaten-Klasse (s.o.).
        "no real-time data", "no real time data", "no realtime data", "no current data",
    )

    /** Satz-Ender (für [FIRST_SENTENCE_MARKERS], s. dort). */
    private val SENTENCE_END = Regex("[.!?]")

    /**
     * **Wortstellungs-Varianten mit VORANGESTELLTEM Subjekt** (Andi-Live-Repro
     * 2026-07-20): „**Ich weiß nicht genau.** Das ist noch nicht festgemacht."
     * traf KEINEN der obigen [MARKERS] — dort steht überall das Subjekt NACH dem
     * Verb („weiß ICH nicht"), hier steht es DAVOR („ICH weiß nicht").
     *
     * **False-Positive-Gefahr, darum ZWEI Schutzplanken statt eines simplen
     * `contains`:** dieselbe Wendung taucht auch als bloßer HEDGE in einer
     * echten, inhaltlichen Antwort auf („Ich weiß nicht, ob dir das reicht —
     * hier die Fakten: …" ist KEIN Passen, die Antwort liefert danach echten
     * Inhalt).
     *  1) Nur im ERSTEN SATZ der Antwort gesucht ([firstSentence]) — ein
     *     ehrliches Passen IST der Anfang der Antwort, keine spätere Nebenbemerkung.
     *  2) Die Wendung darf NICHT direkt von einem Komma gefolgt sein (negative
     *     Lookahead `(?!,)`) — ein Komma leitet fast immer einen unterordnenden
     *     Nebensatz ein ("…, ob/dass/wie …") und macht die Wendung damit zum
     *     Hedge INNERHALB einer Antwort statt zum vollständigen Passen.
     */
    private val FIRST_SENTENCE_MARKERS: List<Regex> = listOf(
        Regex("ich wei(?:ß|ss) nicht(?!,)"),
        Regex("wei(?:ß|ss) nicht genau(?!,)"),
        Regex("ich wei(?:ß|ss) es nicht(?!,)"),
        Regex("wei(?:ß|ss) es nicht genau(?!,)"),
        // Testprotokoll 20.07 ~1:00 (Wurzel a), zwei weitere Umgehungs-Stile, mit
        // denen das Brain deterministisch an den obigen Markern vorbeirutschte:
        //
        //  1) „Hab nur gehört…" — ein Verweis auf bloßes Hörensagen OHNE
        //     Fortsetzung (Ellipse/Satzende). Dieselbe Komma-Schutzplanke wie oben:
        //     folgt DIREKT ein Komma („Hab nur gehört, dass X passiert"), liefert
        //     der Nebensatz eine echte Aussage — kein Passen, darum `(?!,)`.
        Regex("hab(?:e)? nur gehört(?!,)"),
        // 2) „Gestern war doch kein Rennen, oder?" — eine verneinte Aussage, die
        //    das Brain als Rückfrage AN den User zurückwirft, statt selbst zu
        //    antworten (Ausweich-Stil, kein Fakt). Der ganze erste Satz endet auf
        //    „…kein X, oder" (das Fragezeichen liegt hinter [firstSentence]s
        //    Schnitt, s. [SENTENCE_END]) — eng genug, dass eine normale
        //    Faktenantwort das nicht zufällig trifft.
        Regex("kein\\w*.{0,40},\\s*oder$"),
    )

    /** Der erste Satz von [norm] (bis zum ersten `.`/`!`/`?`, oder ganz [norm] ohne Satzende). */
    private fun firstSentence(norm: String): String {
        val end = SENTENCE_END.find(norm)?.range?.first ?: norm.length
        return norm.substring(0, end)
    }

    /** TRUE gdw. [answer] einen starken Abstain-Marker trägt (leer ⇒ false). */
    fun isAbstain(answer: String): Boolean {
        if (answer.isBlank()) return false
        val norm = normalize(answer)
        if (MARKERS.any { norm.contains(it) }) return true
        val firstSentence = firstSentence(norm)
        return FIRST_SENTENCE_MARKERS.any { it.containsMatchIn(firstSentence) }
    }
}

/**
 * **ConsentRecognizer** — der konservative Erkenner einer KURZEN, unzweideutigen
 * Zustimmung als GANZE Äußerung (Naht C, Andi-Auftrag 2026-07-20: „ein schlichtes
 * ja muss das Online-Angebot einlösen").
 *
 * **Verhältnis zu [AffirmationRecognizer] (`PendingLookupPort.kt`, Naht B):**
 * beide erkennen dieselbe Klasse kurzer Zustimmungen, für dieselbe Store-Wahrheit
 * ([PendingLookupPort]) — [AffirmationRecognizer] läuft UNBEDINGT am Turn-Kopf
 * ([TurnOrchestrator.handleTurn], VOR Naht C) und fängt darum die meisten
 * Zustimmungen bereits ab. [ConsentRecognizer] ist die EXPLIZITE zweite
 * Einlöse-Stelle an Naht C für den schmalen Rest, den [AffirmationRecognizer]
 * (noch) nicht kennt (z.B. „jo"/„jap") — bewusst EIGENES, quellen-unabhängiges
 * Bauteil (Muster [AffirmationRecognizer] selbst), NICHT dieselbe Instanz
 * wiederverwendet, damit Naht C ihre eigene, hier dokumentierte Wortliste trägt.
 * Der Orchestrator verlangt an Naht C ZUSÄTZLICH ein offenes Angebot (peek des
 * bereits konsumierten `pendingThink`, s. dort) — OHNE offenes Angebot bleibt
 * ein bloßes Zustimmungswort IMMER normaler Smalltalk, nie ein Eintritt in
 * diese Naht (Consent ohne Frage ist kein Consent).
 *
 * **Konservativ, WHOLE-utterance (kein Substring):** [text] normalisiert
 * (lowercase, alles außer Buchstaben/Ziffern als Trenner) muss EXAKT einer der
 * [CONSENTS]-Phrasen entsprechen — „Ja, aber warum?" (mehr als [MAX_TOKENS]
 * Tokens durch das zusätzliche „aber warum") ist NIE ein Consent.
 */
object ConsentRecognizer {

    /** Max. Token-Zahl — die längste Phrase im Pool hat 2 Wörter, +1 Sicherheitsmarge. */
    const val MAX_TOKENS: Int = 3

    private val TOKEN_SPLIT = Regex("[^a-zäöüß0-9]+")

    /** Die exakte, deterministische Consent-Liste (normalisierte Token-Folgen). */
    private val CONSENTS: Set<String> = setOf(
        // DE
        "ja", "ja bitte", "ja gerne", "gerne",
        "mach das", "mach mal", "bitte",
        "ok", "okay", "jo", "jap", "klar",
        // EN (Sprachpaket-Kern 2026-07-20: "please"/"please do" ergänzt — Andi-Auftrag
        // nannte sie explizit als EN-Consent-Muster, analog zum DE "bitte"/"mach das").
        "yes", "sure", "please", "please do",
    )

    /** TRUE gdw. [text] normalisiert EXAKT eine der [CONSENTS]-Phrasen ist (≤ [MAX_TOKENS] Tokens). */
    fun matches(text: String): Boolean {
        val tokens = text.lowercase().split(TOKEN_SPLIT).filter { it.isNotBlank() }
        if (tokens.isEmpty() || tokens.size > MAX_TOKENS) return false
        return tokens.joinToString(" ") in CONSENTS
    }
}

/**
 * **TopicAnswerRecognizer** — beantwortet die EINE Frage der Themen-Rückfrage
 * (Naht C4, Andi-Vorfall 2026-07-25): „was genau soll ich nachschauen?" wurde
 * gefragt — ist die NÄCHSTE Nachricht das gesuchte THEMA, oder ist sie es nicht?
 *
 * Semantisch ist das der Gegen-Erkenner zu [ConsentRecognizer]/
 * [AffirmationRecognizer]: dort löst ein „ja" ein BEKANNTES Thema ein, hier IST
 * die Nachricht selbst das Thema. Darum gilt hier die umgekehrte Logik — nicht
 * eine enge Positiv-Liste, sondern eine enge NEGATIV-Liste: (fast) alles ist ein
 * Thema, AUSSER den drei Klassen, die erkennbar keines sind:
 *
 *  1. **Leer** — nichts zum Nachschlagen.
 *  2. **Bloße Zustimmung** („ja", „ok", „yes", „sure"): trägt keinen Sachinhalt.
 *     Eine Zustimmung auf eine WAS-Frage ist keine Antwort — das Angebot verfällt
 *     still (one-shot), statt „ja" nach draußen zu schicken.
 *  3. **Abwinken** ([CANCELS], „egal", „vergiss es", „never mind", „nein"):
 *     der Nutzer nimmt die Bitte zurück — still verfallen lassen.
 *
 * Das Abwinken wird — anders als die Zustimmung — auch als PRÄFIX erkannt
 * („egal, mach das Licht an"): wer abwinkt und im selben Atemzug etwas anderes
 * will, hat GANZ sicher kein Nachschlag-Thema genannt.
 *
 * **Was dieser Erkenner NICHT leistet (bewusst, s. [TurnOrchestrator]):** er
 * unterscheidet kein „Thema" von einem „Befehl" („mach das Licht an"). Der
 * offensichtliche Themenwechsel per Befehl wird eine Ebene höher abgefangen —
 * dort, wo die deterministischen Fastpaths ohnehin schon entscheiden, ob eine
 * Äußerung ein Befehl ist. Ein Erkenner, eine Zuständigkeit.
 */
object TopicAnswerRecognizer {

    private val TOKEN_SPLIT = Regex("[^a-zäöüß0-9]+")

    /**
     * Abwink-Phrasen, die auch als PRÄFIX zählen („egal, mach das Licht an"):
     * Wendungen, mit denen KEIN denkbares Nachschlag-Thema beginnt.
     */
    private val CANCEL_PREFIXES: List<String> = listOf(
        // DE
        "egal", "ist egal", "alles egal", "vergiss", "vergiss es", "vergiss das",
        "schon gut", "lass gut sein", "passt schon", "nicht nötig", "nicht noetig",
        "kein bedarf", "nein",
        // EN
        "never mind", "nevermind", "forget", "forget it", "forget that",
        "no thanks", "no thank you", "doesnt matter", "does not matter",
        "skip it", "leave it",
    )

    /**
     * Abwink-Wörter, die NUR als GANZE Äußerung zählen — als Präfix wären sie zu
     * gierig und würden echte Themen wegwerfen („**No** Mans Sky Release",
     * „**Nichts** über 5 Euro", „**ne** Pizza").
     */
    private val CANCEL_EXACT: Set<String> = setOf(
        "nee", "ne", "nö", "noe", "nichts", "nix",
        "no", "nope", "nothing",
    )

    /** Normalisierte Token-Folge (lowercase, alles außer Buchstaben/Ziffern als Trenner). */
    private fun normalized(text: String): String =
        text.lowercase().split(TOKEN_SPLIT).filter { it.isNotBlank() }.joinToString(" ")

    /** TRUE gdw. [text] ein Abwinken ist ([CANCEL_EXACT] ganz / [CANCEL_PREFIXES] am Anfang). */
    fun isCancel(text: String): Boolean {
        val norm = normalized(text)
        if (norm.isEmpty()) return false
        if (norm in CANCEL_EXACT) return true
        return CANCEL_PREFIXES.any { norm == it || norm.startsWith("$it ") }
    }

    /**
     * TRUE gdw. [text] als das gesuchte NACHSCHLAG-THEMA taugt: nicht leer, keine
     * bloße Zustimmung, kein Abwinken (s. Klassen-KDoc).
     */
    fun isTopic(text: String): Boolean {
        if (text.isBlank()) return false
        if (normalized(text).isEmpty()) return false
        if (isCancel(text)) return false
        return !AffirmationRecognizer.matches(text) && !ConsentRecognizer.matches(text)
    }
}
