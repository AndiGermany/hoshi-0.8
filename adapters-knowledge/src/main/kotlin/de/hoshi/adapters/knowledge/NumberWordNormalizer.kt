package de.hoshi.adapters.knowledge

/**
 * **NumberWordNormalizer** (portiert aus Hoshi 0.5 `de.hoshi.app.cloud.NumberWordNormalizer`,
 * T156/Iter-123). Whisper transkribiert deutsche Zahlen häufig als **Zahlworte** statt
 * Ziffern — „11" → „elf", „13" → „dreizehn". Detektoren wie der
 * [BridgeExistenceClaimAdapter] matchen aber auf `\d+` und fielen sonst durch (Andi-
 * Voice-Live-Bug „Elfuro-Schein").
 *
 * Ersetzt deutsche Zahlworte 0..100 durch Ziffern, BEVOR die Existenz-Regex greift.
 * **Konservativ:** nur als ganzes Wort (Wortgrenze, „elf" nicht in „elfenbein"),
 * Großschreibung egal, keine Komposita. Idempotent — schadet Text-Input nicht.
 * Reines Kotlin, kein `@Component`.
 */
class NumberWordNormalizer {

    /** Ersetzt deutsche Zahlworte durch Ziffern. Idempotent. */
    fun normalize(text: String): String {
        if (text.isBlank()) return text
        var out = text
        for ((word, num) in DIGIT_MAP) {
            out = Regex("\\b$word\\b", RegexOption.IGNORE_CASE).replace(out, num)
        }
        return out
    }

    companion object {
        // Reihenfolge wichtig: längere/zusammengesetzte Wörter zuerst, sonst frisst ein
        // kürzeres Token (z.B. „drei") den Tail eines längeren („dreizehn").
        private val DIGIT_MAP: List<Pair<String, String>> = listOf(
            // Zusammengesetzte 21..29 zuerst
            "einundzwanzig" to "21",
            "zweiundzwanzig" to "22",
            "dreiundzwanzig" to "23",
            "vierundzwanzig" to "24",
            "fünfundzwanzig" to "25",
            "fuenfundzwanzig" to "25",
            "sechsundzwanzig" to "26",
            "siebenundzwanzig" to "27",
            "achtundzwanzig" to "28",
            "neunundzwanzig" to "29",
            // 31..39
            "einunddreißig" to "31", "einunddreissig" to "31",
            "zweiunddreißig" to "32", "zweiunddreissig" to "32",
            "dreiunddreißig" to "33", "dreiunddreissig" to "33",
            "vierunddreißig" to "34", "vierunddreissig" to "34",
            "fünfunddreißig" to "35", "fuenfunddreissig" to "35", "fünfunddreissig" to "35",
            "sechsunddreißig" to "36", "sechsunddreissig" to "36",
            "siebenunddreißig" to "37", "siebenunddreissig" to "37",
            "achtunddreißig" to "38", "achtunddreissig" to "38",
            "neununddreißig" to "39", "neununddreissig" to "39",
            // 41..49
            "einundvierzig" to "41",
            "zweiundvierzig" to "42",
            "dreiundvierzig" to "43",
            "vierundvierzig" to "44",
            "fünfundvierzig" to "45", "fuenfundvierzig" to "45",
            "sechsundvierzig" to "46",
            "siebenundvierzig" to "47",
            "achtundvierzig" to "48",
            "neunundvierzig" to "49",
            // 51..59
            "einundfünfzig" to "51", "einundfuenfzig" to "51",
            "zweiundfünfzig" to "52", "zweiundfuenfzig" to "52",
            "dreiundfünfzig" to "53", "dreiundfuenfzig" to "53",
            "vierundfünfzig" to "54", "vierundfuenfzig" to "54",
            "fünfundfünfzig" to "55", "fuenfundfuenfzig" to "55", "fünfundfuenfzig" to "55",
            "sechsundfünfzig" to "56", "sechsundfuenfzig" to "56",
            "siebenundfünfzig" to "57", "siebenundfuenfzig" to "57",
            "achtundfünfzig" to "58", "achtundfuenfzig" to "58",
            "neunundfünfzig" to "59", "neunundfuenfzig" to "59",
            // Zehner-Mehrfache
            "dreißig" to "30",
            "dreissig" to "30",
            "vierzig" to "40",
            "fünfzig" to "50",
            "fuenfzig" to "50",
            "sechzig" to "60",
            "siebzig" to "70",
            "achtzig" to "80",
            "neunzig" to "90",
            "hundert" to "100",
            // 11..20 (vor 1..9, damit „dreizehn" nicht von „drei" überschrieben wird)
            "elf" to "11",
            "zwölf" to "12",
            "zwoelf" to "12",
            "dreizehn" to "13",
            "vierzehn" to "14",
            "fünfzehn" to "15",
            "fuenfzehn" to "15",
            "sechzehn" to "16",
            "siebzehn" to "17",
            "achtzehn" to "18",
            "neunzehn" to "19",
            "zwanzig" to "20",
            // 1..10
            "null" to "0",
            "eins" to "1",
            "zwei" to "2",
            "drei" to "3",
            "vier" to "4",
            "fünf" to "5",
            "fuenf" to "5",
            "sechs" to "6",
            "sieben" to "7",
            "acht" to "8",
            "neun" to "9",
            "zehn" to "10",
        )
    }
}
