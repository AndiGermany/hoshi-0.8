package de.hoshi.adapters.memory

/**
 * **SensitiveMarker** — reiner, netz-/abhängigkeitsfreier Regex-Detektor für
 * sensible Inhalte (Gesundheit/Finanz/Adresse/Politik/Religion/Sexualität). Portiert
 * die geprüfte Essenz des `detectSensitiveCategory`-Gates aus 0.5
 * (`cloud/PrivacySanitizer.kt`, Sprint D1/T058) — dort blockt es den Cloud-Pfad,
 * hier das Episodic-Persist: ein Treffer-Turn landet NICHT dauerhaft auf Disk und
 * taucht so nie über den Recall-Block wieder auf.
 *
 * **Kein ML, kein Klartext-Leak:** [detect] liefert NUR die [Category] (oder `null`),
 * niemals den getroffenen Text. Der Caller loggt ausschließlich den Kategorie-Namen.
 *
 * **Konservativ by design:** lieber wenige sichere Marker als Über-Blocking. Die
 * Listen meiden bewusst kurze/ambige Stämme, deren Präfix harmlose Wörter trifft
 * (z.B. NICHT bare `tax` → `taxi`, NICHT bare `lohn` → „lohnt sich", NICHT bare
 * `address` → „address the issue"). Marker matchen am **Wort-Anfang** (Unicode-
 * Look-behind auf Nicht-Wort-Zeichen) und stem-/präfix-tolerant (kein End-Boundary),
 * exakt wie 0.5. Mehrwort-Marker (z.B. `wohne in`, `blood pressure`) matchen literal.
 *
 * Bewusst als `object` (zustandslos, eine einzige geteilte Regex-Kompilierung).
 * Begründung Platzierung: lebt neben dem einzigen Consumer [EpisodicMemoryAdapter]
 * (wie [EpisodicEmbedder]/[OllamaEpisodicEmbedder]) — keine neue core-domain-API
 * für etwas, das nur der Memory-Adapter braucht.
 */
object SensitiveMarker {

    /** Sensible Inhalts-Kategorie (nur der Name wird je geloggt, nie der Klartext). */
    enum class Category { GESUNDHEIT, FINANZ, ADRESSE, POLITIK, RELIGION, SEXUALITAET }

    // Numerische DE-Patterns zuerst (eindeutig, kein Wortlisten-Rauschen):
    // Steuer-ID = 11 Ziffern, Renten-/SV-Nummer = Block-Pattern. Konservativ.
    private val taxIdPattern = Regex("""\b\d{11}\b""")
    private val svNummerPattern = Regex("""\b\d{2}\s?\d{6}\s?[A-Z]\s?\d{3}\b""")

    /**
     * DE+EN-Marker je Kategorie. Präfix-/stem-tolerant am Wort-Anfang. Bewusst
     * ohne FP-anfällige Stämme (siehe Klassen-Doc). Reihenfolge der Kategorien
     * entscheidet nur bei Überlappung, welche zuerst gewinnt.
     */
    private val markers: List<Pair<Category, List<String>>> = listOf(
        Category.GESUNDHEIT to listOf(
            // DE
            "krank", "krankheit", "krankenhaus", "krankenkasse", "krankschreib",
            "diagnose", "diagnostiz", "symptom", "schmerz", "arzt", "ärztin",
            "hausarzt", "facharzt", "klinik", "notaufnahme", "medikament", "tablette",
            "dosis", "therapie", "behandlung", "operation", "befund", "blutdruck",
            "blutwert", "depression", "angststörung", "psychiat", "psychother",
            "krebs", "tumor", "chemo", "diabetes", "allergie", "schwanger", "impfung",
            "infektion", "covid", "corona", "rezeptpflicht", "vorsorge",
            // EN
            "illness", "sickness", "disease", "diagnosis", "diagnosed", "doctor",
            "physician", "hospital", "clinic", "prescription", "medication",
            "medicine", "therapy", "treatment", "surgery", "anxiety", "cancer",
            "tumour", "chemotherapy", "allergy", "pregnant", "pregnancy", "vaccine",
            "vaccinat", "antidepress", "psychiatr", "blood pressure", "mental health",
        ),
        Category.FINANZ to listOf(
            // DE
            "konto", "kontostand", "iban", "bic", "überweisung", "gehalt",
            "einkommen", "schulden", "kredit", "darlehen", "hypothek", "ratenzahlung",
            "steuererklär", "steuernummer", "steuer-id", "finanzamt", "rente",
            "altersvorsorge", "versicherung", "depot", "aktien", "sparbuch",
            "kreditkarte", "mahnung", "insolvenz", "pfändung", "bafög", "bürgergeld",
            "sozialhilfe", "arbeitslosengeld", "vermögen",
            // EN
            "bank account", "account balance", "salary", "income", "debt", "loan",
            "mortgage", "credit card", "credit score", "tax return", "tax id",
            "income tax", "insolven", "bankrupt", "pension", "welfare",
            "social security", "overdraft", "paycheck", "net worth",
        ),
        Category.ADRESSE to listOf(
            // DE
            "wohne in", "meine adresse", "postleitzahl", "hausnummer", "anschrift",
            "wohnhaft", "wohnort", "meldeadresse",
            // EN
            "i live at", "my address", "home address", "street address",
            "postal code", "zip code",
        ),
        Category.POLITIK to listOf(
            // DE
            "bundestagswahl", "wahlkampf", "wählerstimme", "partei", "abgeordnet",
            "koalition", "opposition", "politisch", "bundestag", "afd", "cdu",
            "csu", "spd", "fdp",
            // EN
            "election", "vote for", "voted for", "political party", "parliament",
            "republican", "democrat", "left-wing", "right-wing",
        ),
        Category.RELIGION to listOf(
            // DE
            "glaube an gott", "religion", "religiös", "kirche", "moschee", "synagoge",
            "gebet", "beten", "konfession", "katholisch", "evangelisch", "muslim",
            "islam", "jüdisch", "buddhis", "atheist", "taufe", "christlich",
            // EN
            "religious", "church", "mosque", "synagogue", "prayer", "catholic",
            "protestant", "christianity", "jewish",
        ),
        Category.SEXUALITAET to listOf(
            // DE
            "sex", "sexuell", "sexualität", "porno", "erotik", "intim", "verhütung",
            "schwul", "lesbisch", "bisexuell", "transgender", "trans-", "queer",
            "libido", "geschlechtskrankheit", "fetisch",
            // EN
            "sexual", "pornograph", "erotic", "intimate", "contracepti",
            "homosexual", "bisexual", "fetish",
        ),
    )

    // Wort-Anfang-Match: vor dem Marker steht ein Nicht-Wort-Zeichen oder
    // String-Anfang. Erlaubt Stemming (Marker als Präfix). Mehrwort-Marker werden
    // literal gematcht (Regex.escape). \b ist mit Umlauten unzuverlässig → explizites
    // Unicode-Look-behind (genau wie 0.5/PrivacySanitizer).
    private val patterns: List<Pair<Category, Regex>> =
        markers.flatMap { (category, list) ->
            list.map { marker ->
                category to Regex("(?<![\\p{L}\\d])" + Regex.escape(marker.trim()), RegexOption.IGNORE_CASE)
            }
        }

    /**
     * Erkennt sensible Marker in [text] und liefert die getroffene [Category] oder
     * `null` (harmlos). Numerische Patterns zuerst (eindeutig), dann Wortlisten in
     * Kategorie-Reihenfolge. Liefert NIE den Klartext.
     */
    fun detect(text: String): Category? {
        if (text.isBlank()) return null
        if (taxIdPattern.containsMatchIn(text)) return Category.FINANZ
        if (svNummerPattern.containsMatchIn(text)) return Category.GESUNDHEIT
        for ((category, pattern) in patterns) {
            if (pattern.containsMatchIn(text)) return category
        }
        return null
    }
}
