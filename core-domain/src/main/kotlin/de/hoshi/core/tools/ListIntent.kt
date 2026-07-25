package de.hoshi.core.tools

/**
 * **ListIntent** — die deterministische, LLM-freie Erkennung eines eindeutigen
 * Einkaufslisten-Befehls (DE **und** EN) in einen [ToolCall] (`domain == "list"`).
 * Exakt nach dem [TimerIntent]-Muster gebaut (Andi-JA 2026-07-08, „Listen auf die
 * Ring-1-Karte" — die letzte Ring-1-Lücke neben dem Wecker).
 *
 * KONSERVATIV: erkennt nur eindeutige Befehle. Reihenfolge **REMOVE vor READ vor
 * ADD** (Andi-Entscheidung 2026-07-08: „REMOVE-Erkennung hat Vorrang vor ADD bei
 * Doppel-Match" — exakt die CANCEL-vor-QUERY-vor-SET-Begründung des Timers: sonst
 * frisst der breite ADD-Trigger ein „nimm Milch von der Liste"). ADD verlangt
 * ZWINGEND ein explizites Listen-Nomen („liste"/„einkaufsliste"/„einkaufszettel"/
 * „shopping list") UND eine Ziel-Phrase davor („auf/zur/in die … Liste") UND ein
 * extrahierbares Item — ein bloßes „Liste" mitten in einem völlig anderen Satz
 * („mach mir eine Liste von Ideen für Papas Geburtstag") erzeugt NIE einen
 * Treffer, weil dort KEIN Zielwort (auf/zur/in/…) unmittelbar vor dem Listen-Nomen
 * steht — die Ziel-Regex greift schlicht nicht (s. [ADD_DEST]). Dasselbe
 * Konservativitäts-Prinzip wie beim Wecker: mehrdeutig/kein Trigger ⇒ `null` ⇒
 * normaler Turn (Brain) — das IST der Wächter gegen Scope-Creep Richtung
 * NLU-Maschinerie (Kai-/Lara-Veto, PREP-Risiko 1).
 *
 * **Freitext-Item, keine Einheiten-Ontologie** (Andi-Entscheidung 2026-07-08):
 * „500 g Hack" wird NICHT in Menge+Einheit+Name zerlegt — der komplette Wortlaut
 * zwischen Verb und Ziel-Phrase ist das Item, unangetastet.
 *
 * **Kompositum-Schutz:** [LIST_NOUN] verlangt per Lookbehind, dass vor dem
 * Listen-Nomen KEIN Buchstabe steht — sonst würde „Wer steht auf der
 * Gästeliste?" fälschlich als Listen-Read durchgehen (die Wortfuge
 * „Gäste"+„liste" ist EIN Kompositum-Wort, kein eigenständiges „liste").
 *
 * **Dedupe/Mengen-Zähler ist NICHT Teil dieser Klasse** — [ListIntent] liefert nur
 * den rohen Item-Text; ob er einen bestehenden Eintrag zusammenführt („2× Milch"),
 * entscheidet [de.hoshi.core.port.addWithDedupe] gegen den aktuellen Store-Stand
 * (uhrfrei/store-frei bleibt auch dieser Parser).
 */
object ListIntent {

    /** ToolCall-Domain dieses Fast-Paths (vom Orchestrator gegen den List-Zweig geprüft). */
    const val DOMAIN = "list"
    const val ADD = "add"
    const val READ = "read"
    const val REMOVE = "remove"

    /** REMOVE-Datenschlüssel: `true` = die ganze Liste leeren, `false` = ein genanntes Item. */
    const val ALL = "all"

    /** ADD/REMOVE-Datenschlüssel: der extrahierte Freitext-Item-Name. */
    const val ITEM = "item"

    /**
     * Klassifiziert den (Original-)Text in einen List-[ToolCall] oder `null`.
     * Sprache wird NICHT gebraucht (Trigger sind bilingual) — die Quittungs-Sprache
     * reicht der Orchestrator separat in den [de.hoshi.core.pipeline.ListFastpath].
     */
    fun classify(text: String): ToolCall? {
        if (text.isBlank()) return null
        val qNorm = normalize(text)
        if (qNorm.isEmpty()) return null

        // (0) Negation ⇒ KEIN Befehl (konservativ, token-genau — wie TimerIntent).
        val tokens = qNorm.split(' ').toSet()
        if (tokens.any { it in NEGATION || it.startsWith("kein") }) return null

        // (1) REMOVE — VOR READ VOR ADD (Andi-Entscheidung 2026-07-08). Zwei Formen:
        //     (1a) die ganze Liste leeren (literale Trigger, analog Timers
        //          CANCEL_TRIGGERS „alle timer löschen"),
        //     (1b) ein genanntes Item entfernen (Verb + Ziel-Phrase „von/aus der Liste").
        if (CLEAR_TRIGGERS.any { qNorm.contains(it) } || EN_CLEAR_TRIGGERS.any { qNorm.contains(it) }) {
            return ToolCall(domain = DOMAIN, service = REMOVE, data = mapOf(ALL to true))
        }
        REMOVE_RX.find(text)?.let { m ->
            cleanItem(m.groupValues[1])?.let { item ->
                return ToolCall(domain = DOMAIN, service = REMOVE, data = mapOf(ALL to false, ITEM to item))
            }
        }

        // (2) READ — literale Fragephrasen (analog Timers QUERY_TRIGGERS).
        if (READ_TRIGGERS.any { qNorm.contains(it) } || EN_READ_TRIGGERS.any { qNorm.contains(it) }) {
            return ToolCall(domain = DOMAIN, service = READ)
        }

        // (2b) Englische FRAGE-/Kopula-Eröffnung ⇒ niemals ein ADD. „list" ist im
        //      Englischen ein Alltagswort, und die verb-lose Kurzform [ADD_BARE_RX]
        //      („<Item> auf die Liste") frisst sonst jede Frage, die auf „… on the
        //      list" endet: „what's on the shopping list" landete als Eintrag
        //      „whats" auf der Liste, „did I put milk on the list" legte Milch AN
        //      (Messung 2026-07-25 — ein STILLES Falsch-Schreiben, das Schlimmste,
        //      was eine Liste tun kann). Die Wortliste ist rein englisch (kein
        //      „was"/„wer"/„wie") ⇒ ein deutscher Satz erreicht diesen Zweig nie.
        //      Höfliche Aufträge („can you add …") stehen bewusst NICHT drin.
        if (qNorm.substringBefore(' ') in EN_QUESTION_OPENERS) return null

        // (3) ADD — braucht Verb+Ziel-Phrase (bevorzugt) oder mindestens die reine
        //     Ziel-Phrase am Satzanfang; beides verlangt zwingend das Listen-Nomen.
        (ADD_WITH_VERB_RX.find(text) ?: ADD_WITH_EN_VERB_RX.find(text) ?: ADD_BARE_RX.find(text))?.let { m ->
            cleanItem(m.groupValues[1])?.let { item ->
                return ToolCall(domain = DOMAIN, service = ADD, data = mapOf(ITEM to item))
            }
        }

        return null
    }

    /** Roh-Fund von den Extraktions-Regexen säubern: trimmen + Satzzeichen am Rand weg; leer ⇒ null. */
    private fun cleanItem(raw: String): String? {
        val cleaned = raw.trim().trim(',', '.', '!', '?', ';', ':', '-').trim()
        return cleaned.ifBlank { null }
    }

    /** Lowercase, Apostrophe weg, alles außer DE-Buchstaben/Ziffern → Space (wie [TimerIntent]). */
    private fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("[’'`´ʼ]"), "")
            .replace(Regex("[^a-zäöüß0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    // ── Wortlisten + Regexe (DE + EN), konservativ ───────────────────────────

    private val NEGATION = setOf("nicht", "nie", "niemals", "not", "no", "dont", "never")

    /**
     * Das explizite Listen-Nomen — PFLICHT für JEDEN Treffer (ADD/REMOVE-Ziel-Phrase).
     * `(?<![\p{L}])` davor verhindert einen Treffer MITTEN in einem Kompositum wie
     * „Gästeliste"/„Wunschliste"/„Playlist" (kein Buchstabe direkt davor ⇒ echtes
     * eigenständiges Wort, keine Wortfuge).
     */
    private val LIST_NOUN =
        """(?<![\p{L}])(?:einkaufsliste|einkaufszettel|shopping\s*list|grocery\s*list|liste|list)\b"""

    /** ADD-Ziel: „auf/zur/zu/in/on/onto/to (die/der/den/dem/meine/the/my)* <Listen-Nomen>". */
    private val ADD_DEST =
        """\b(?:auf|zur|zu|in|on|onto|to)\b\s+(?:die\s+|der\s+|den\s+|dem\s+|meine\s+|meiner\s+|the\s+|my\s+)*$LIST_NOUN"""

    /** REMOVE-Ziel: „von/vom/aus/off/from (der/dem/die/meiner/the/my)* <Listen-Nomen>". */
    private val REMOVE_DEST =
        """\b(?:von|vom|aus|off|from)\b\s+(?:der\s+|dem\s+|die\s+|meiner\s+|meinem\s+|the\s+|my\s+)*$LIST_NOUN"""

    /** „Setz/Pack/Schreib/Leg/Füg/Notier/Add/Put <Item> auf/zur/… die Liste." — Item = Gruppe 1. */
    private val ADD_WITH_VERB_RX = Regex(
        """\b(?:setz|setze|pack|packe|schreib|schreibe|leg|lege|f[üu]g|fuege|notier|notiere|add|put)\b""" +
            """\s+(?:mir\s+|noch\s+|bitte\s+)*(.+?)\s+$ADD_DEST""",
        RegexOption.IGNORE_CASE,
    )

    /** Verb-lose Kurzform am Satzanfang: „<Item> auf die Liste." — Item = Gruppe 1. */
    private val ADD_BARE_RX = Regex("""^(.+?)\s+$ADD_DEST""", RegexOption.IGNORE_CASE)

    /**
     * ADD-Ziel mit NUR englischen Artikeln — die Sicherung für [ADD_WITH_EN_VERB_RX]:
     * „note" ist im Deutschen ein Substantiv („die Note"), das englische Verb dürfte
     * deshalb NIE in einen deutschen Satz greifen („die Note von Mathe auf die Liste"
     * bleibt die verb-lose Kurzform). Die deutschen Artikel fehlen hier bewusst.
     */
    private val ADD_DEST_EN = """\b(?:on|onto|to|in)\b\s+(?:the\s+|my\s+)*$LIST_NOUN"""

    /** „Note/Write/Jot (down) <Item> on the list." — EN-Verben, EN-Ziel (s. [ADD_DEST_EN]). */
    private val ADD_WITH_EN_VERB_RX = Regex(
        """\b(?:note|write|jot|stick|chuck)\b\s+(?:down\s+|me\s+|please\s+)*(.+?)\s+$ADD_DEST_EN""",
        RegexOption.IGNORE_CASE,
    )

    /** „Nimm/Entfern/Lösch/Streich/Remove/Delete/Take <Item> von/aus/off der Liste."
     *  Die englischen Zusatz-Verben (cross/scratch/strike/erase) sind keine deutschen
     *  Wörter ⇒ sie können in keinem deutschen Satz zünden. */
    private val REMOVE_RX = Regex(
        """\b(?:nimm|entfern|entferne|l[öo]sch|loesch|streich|streiche|remove|delete|take""" +
            """|cross|scratch|strike|erase)\b""" +
            """\s+(?:die\s+|den\s+|das\s+|mir\s+)*(.+?)\s+$REMOVE_DEST""",
        RegexOption.IGNORE_CASE,
    )

    /** „Liste komplett leeren" — literale Trigger (analog Timers CANCEL_TRIGGERS „alle timer löschen"). */
    private val CLEAR_TRIGGERS = listOf(
        "liste leeren", "leer die liste", "leere die liste", "einkaufsliste leeren",
        "lösch die ganze liste", "loesch die ganze liste",
        "lösche die ganze liste", "loesche die ganze liste",
        "die ganze liste löschen", "die ganze liste loeschen",
        "räum die liste leer", "raeum die liste leer",
        "clear the list", "empty the list", "clear my shopping list", "clear the shopping list",
    )

    /** Read-Fragephrasen — literale Substring-Trigger (analog Timers QUERY_TRIGGERS). */
    private val READ_TRIGGERS = listOf(
        "was steht auf der liste", "was steht auf meiner liste", "was ist auf der liste",
        "was steht auf der einkaufsliste", "was steht auf dem einkaufszettel",
        "was steht auf meinem einkaufszettel", "was muss ich noch einkaufen",
        "was fehlt auf der einkaufsliste", "was fehlt noch auf der liste",
        "zeig mir die liste", "zeig die liste", "zeig mir die einkaufsliste",
        "lies mir die liste vor", "lies die liste vor", "lies mir die einkaufsliste vor",
        "what is on the list", "what s on the list", "whats on the list",
        "what is on the shopping list", "what s on the shopping list",
        "show me the list", "read the list", "read me the list",
    )

    // ── EN-only Ergänzungen (Andi-Befund 2026-07-25: Hoshi ANTWORTET englisch,
    //    VERSTAND aber nur deutsch) ─────────────────────────────────────────────

    /**
     * Die englischen Listen-Benennungen — einmal deklariert, für Lese- und
     * Leer-Trigger gekreuzt. „list" allein steht bewusst NICHT drin: es ist ein
     * häufiges englisches Alltagswort („can you list the options") und braucht
     * immer seinen Artikel als Kontext.
     */
    private val EN_LIST_PHRASES = listOf(
        "the list", "my list", "the shopping list", "my shopping list",
        "the grocery list", "my grocery list",
    )

    /**
     * Englische Vorlese-Fragen als Kreuzprodukt aus Frage-Kopf × Listen-Benennung —
     * ausgeschrieben wären es ~50 Zeilen Literale, die beim Nachpflegen garantiert
     * auseinanderlaufen. Deutsch bleibt in [READ_TRIGGERS], unangetastet.
     */
    private val EN_READ_TRIGGERS: List<String> = buildList {
        val heads = listOf(
            "whats on", "what is on", "whats still on", "what is still on",
            "whats left on", "what is left on", "whats all on",
            "show me", "show", "read me", "read out", "read",
        )
        EN_LIST_PHRASES.forEach { phrase -> heads.forEach { head -> add("$head $phrase") } }
        add("what do i need to buy")
        add("what do i still need to buy")
    }

    /** Englische „mach die Liste leer"-Trigger — ebenfalls als Kreuzprodukt. */
    private val EN_CLEAR_TRIGGERS: List<String> = buildList {
        val heads = listOf("clear", "empty", "wipe", "delete", "erase")
        EN_LIST_PHRASES.forEach { phrase -> heads.forEach { head -> add("$head $phrase") } }
        EN_LIST_PHRASES.forEach { phrase ->
            add("delete everything from $phrase")
            add("remove everything from $phrase")
            add("take everything off $phrase")
        }
    }

    /**
     * Englische Frage-/Hilfsverb-Eröffnungen, die ein ADD ausschließen (s. Schritt
     * (2b) in [classify]). Bewusst OHNE „was"/„wer"/„wie" (deutsch) und ohne die
     * Modalverben „can/could/would" — „can you add milk to the list" ist ein
     * höflicher AUFTRAG und muss weiter greifen.
     */
    private val EN_QUESTION_OPENERS = setOf(
        "what", "whats", "which", "who", "whose", "why", "how", "where", "when",
        "is", "are", "were", "do", "does", "did", "have", "has", "had", "should",
    )
}
