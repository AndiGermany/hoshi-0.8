package de.hoshi.adapters.tts

import com.ibm.icu.text.RuleBasedNumberFormat
import com.ibm.icu.util.ULocale
import de.hoshi.core.dto.Language
import de.hoshi.core.pipeline.VerbalizerPort
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * **IcuVerbalizer** — die echte [VerbalizerPort]-Implementierung (Multilingualitaet
 * Phase 1), gestuetzt auf ICU4J [RuleBasedNumberFormat] (SPELLOUT/ORDINAL). Macht
 * Ziffern/Uhrzeiten VOR der Synthese sprechbar, weil piper/`say`/OpenAI-TTS Zahlen
 * unterschiedlich normalisieren und SSML `say-as` bei 2 von 3 Engines wirkungslos ist
 * (s. [VerbalizerPort]-KDoc).
 *
 * **Bewusst konservativer v1-Umfang** (Andi-Auftrag: „NUR wenn du es sicher
 * hinbekommst"):
 *  - **Uhrzeiten**: `"20 Uhr 15"` (DE, literales „Uhr") und `"20:15"`
 *    (sprachunabhaengiges Doppelpunkt-Format) → ausgeschriebene Stunde+Minute in
 *    der jeweiligen [Language]. Volle Stunde (Minute 0/fehlend) OHNE Minutenteil
 *    (`"zwanzig Uhr"`, nicht `"zwanzig Uhr null"`).
 *  - **Ganz-/Dezimalzahlen**: DE/ES/FR/IT nutzen Komma, EN Punkt als
 *    Dezimaltrennzeichen (`"17,1"` bzw. `"17.1"`) — ICU spricht den
 *    Trenner selbst sprachrichtig aus (Komma/point/coma/virgule/virgola).
 *  - **Ordinalzahlen NUR fuer Deutsch**: `"3."` → `"dritte"` ueber das ICU-Ruleset
 *    `%spellout-ordinal` (empirisch verifiziert: liefert die generische, nicht
 *    deklinierte Form — grammatisch nicht immer exakt, z.B. „am dritte Mai" statt
 *    „am dritten Mai", aber ZIFFERNFREI und deterministisch). Fuer EN existieren
 *    Ordinalzahlen ohnehin nicht als reine Ziffer+Punkt-Form (`"3rd"`), braucht
 *    also keine Sonderbehandlung. Fuer ES/FR/IT liefert ICU4J NUR genus-spezifische
 *    Ordinal-Rulesets (`%digits-ordinal-feminine`/`-masculine`/…) OHNE einen
 *    generischen Default — eine automatische Genus-Wahl waere geraten statt
 *    sicher, darum bewusst WEGGELASSEN: `"3."` faellt dort auf die Kardinalzahl
 *    zurueck (`"tres."`) statt auf ein geratenes Genus. Ehrlich unvollstaendig,
 *    nie falsch geraten.
 *
 * **Robustheit (NIE werfen):** jeder Zahlen-Fund wird EINZELN in einem `try/catch`
 * verbalisiert — schlaegt einer fehl (z.B. ein Fantasie-Zahl-Fragment jenseits von
 * [Long.MAX_VALUE]), bleibt GENAU dieses Fragment im Originaltext stehen, der Rest
 * des Satzes wird trotzdem verbalisiert. Ein zusaetzlicher aeusserer `try/catch`
 * faengt jeden sonstigen Fehler (z.B. am Regex selbst) ab und gibt dann den
 * KOMPLETTEN Originaltext zurueck. Ein Assistent darf lieber eine Ziffer
 * aussprechen, als an einem Satz zu ersticken.
 */
class IcuVerbalizer : VerbalizerPort {

    override fun verbalize(text: String, language: Language): String {
        if (text.isBlank()) return text
        return try {
            val cfg = configFor(language)
            // Weiche Trennstriche (U+00AD) streichen: ICU fuegt sie in deutschen
            // Komposita ("zwei­und­vierzig") als unsichtbare Zeilenumbruch-Hinweise
            // ein — fuer TTS pure Geraeuschlosigkeit, aber unnoetiger Ballast.
            cfg.pattern.replace(text) { match -> verbalizeMatch(match, cfg) }.replace(SOFT_HYPHEN, "")
        } catch (e: Exception) {
            log.warn("IcuVerbalizer: unerwarteter Fehler bei Sprache {}, gebe Original zurueck: {}", language, e.toString())
            text
        }
    }

    /** Verbalisiert EINEN Regex-Fund. Wirft NIE — bei Fehler bleibt [match] unveraendert stehen. */
    private fun verbalizeMatch(match: MatchResult, cfg: LangNumConfig): String =
        try {
            val groups = match.groups as MatchNamedGroupCollection
            when {
                groups.safeGet("clockColon") != null -> verbalizeClockColon(match, cfg)
                groups.safeGet("clockWord") != null -> verbalizeClockWord(match, cfg)
                groups.safeGet("ordinal") != null -> verbalizeOrdinal(groups.safeGet("ordinal")!!.value, cfg)
                groups.safeGet("decimal") != null -> verbalizeDecimal(groups.safeGet("decimal")!!.value, cfg)
                else -> verbalizeInteger(match.value, cfg)
            }
        } catch (e: Exception) {
            log.warn("IcuVerbalizer: Fragment '{}' nicht verbalisierbar, bleibt unveraendert: {}", match.value, e.toString())
            match.value
        }

    /**
     * Sicherer Named-Group-Zugriff: nicht jede Sprache definiert JEDE benannte Gruppe
     * (z.B. hat nur Deutsch eine `ordinal`-Gruppe im kompilierten Pattern, s.
     * [LangNumConfig.buildPattern]). `Matcher.group(name)` wirft `IllegalArgumentException`,
     * wenn der Name im Pattern STRUKTURELL fehlt (unabhaengig vom aktuellen Fund) —
     * das ist kein Verbalisierungs-Fehler, sondern eine erwartete Sprach-Variante.
     */
    private fun MatchNamedGroupCollection.safeGet(name: String): MatchGroup? =
        try {
            this[name]
        } catch (e: IllegalArgumentException) {
            null
        }

    private fun verbalizeClockColon(match: MatchResult, cfg: LangNumConfig): String {
        val (h, m) = COLON_SPLIT.matchEntire(match.value)!!.destructured
        return formatClock(h.toInt(), m.toInt(), cfg)
    }

    private fun verbalizeClockWord(match: MatchResult, cfg: LangNumConfig): String {
        val groups = match.groups as MatchNamedGroupCollection
        val hour = groups["cwHour"]!!.value.toInt()
        val minute = groups["cwMinute"]?.value?.toIntOrNull()
        return formatClock(hour, minute ?: 0, cfg, fullHour = minute == null)
    }

    private fun formatClock(hour: Int, minute: Int, cfg: LangNumConfig, fullHour: Boolean = minute == 0): String {
        val hourWord = cfg.cardinal.format(hour.toLong())
        if (fullHour || minute == 0) return "$hourWord ${cfg.hourUnitWord}"
        val minuteWord = if (minute in 1..9) "${cfg.zeroWord} ${cfg.cardinal.format(minute.toLong())}" else cfg.cardinal.format(minute.toLong())
        return if (cfg.unitAlwaysPresent) "$hourWord ${cfg.hourUnitWord} $minuteWord" else "$hourWord $minuteWord"
    }

    /** NUR fuer Deutsch erreichbar (einzige Sprache mit einem generischen Ordinal-Ruleset, s. Klassen-KDoc). */
    private fun verbalizeOrdinal(raw: String, cfg: LangNumConfig): String {
        val n = raw.removeSuffix(".").trim().toLong()
        return cfg.cardinal.format(n, cfg.ordinalRuleSet!!)
    }

    private fun verbalizeDecimal(raw: String, cfg: LangNumConfig): String {
        val normalized = raw.replace(cfg.decimalSeparator, '.')
        return cfg.cardinal.format(normalized.toDouble())
    }

    private fun verbalizeInteger(raw: String, cfg: LangNumConfig): String =
        cfg.cardinal.format(raw.toLong())

    /** Sprach-spezifische ICU-Formatter + der dazu passende Erkennungs-Regex (gecacht, ICU-Objekte sind teuer). */
    private fun configFor(language: Language): LangNumConfig =
        configCache.computeIfAbsent(language) { LangNumConfig.forLanguage(it) }

    private companion object {
        private val log = LoggerFactory.getLogger(IcuVerbalizer::class.java)
        private val configCache = ConcurrentHashMap<Language, LangNumConfig>()

        /** Zerlegt ein bereits erkanntes `HH:MM` in Stunde/Minute. */
        private val COLON_SPLIT = Regex("""(\d{1,2}):(\d{2})""")

        /** ICU-Artefakt in deutschen Komposita-Zahlwoertern (s. [verbalize]-KDoc). */
        private const val SOFT_HYPHEN = "\u00AD"
    }
}

/** Bündelt ICU-Formatter + Regex + Sprech-Konventionen EINER [Language]. */
private class LangNumConfig(
    val cardinal: RuleBasedNumberFormat,
    val ordinalRuleSet: String?,
    val hourUnitWord: String,
    val unitAlwaysPresent: Boolean,
    val decimalSeparator: Char,
) {
    val zeroWord: String by lazy { cardinal.format(0L) }
    val pattern: Regex = buildPattern(decimalSeparator, ordinalRuleSet != null)

    companion object {
        fun forLanguage(language: Language): LangNumConfig = when (language) {
            Language.DE -> LangNumConfig(
                cardinal = RuleBasedNumberFormat(ULocale.GERMAN, RuleBasedNumberFormat.SPELLOUT),
                ordinalRuleSet = "%spellout-ordinal", // empirisch verifiziert: format(3, …) == "dritte"
                hourUnitWord = "Uhr",
                unitAlwaysPresent = true, // "Uhr" steht sowohl bei voller Stunde als auch als Verbinder vor der Minute
                decimalSeparator = ',',
            )
            Language.EN -> LangNumConfig(
                cardinal = RuleBasedNumberFormat(ULocale.ENGLISH, RuleBasedNumberFormat.SPELLOUT),
                ordinalRuleSet = null, // EN schreibt Ordinalzahlen als "3rd", nie als reine Ziffer+Punkt -> kein Bedarf
                hourUnitWord = "o'clock",
                unitAlwaysPresent = false, // nur bei voller Stunde ("eight o'clock"), sonst bloss "eight fifteen"
                decimalSeparator = '.',
            )
            Language.ES -> LangNumConfig(
                cardinal = RuleBasedNumberFormat(ULocale.forLanguageTag("es"), RuleBasedNumberFormat.SPELLOUT),
                ordinalRuleSet = null, // nur genus-spezifische Rulesets (feminine/masculine) -> kein sicherer Default
                hourUnitWord = "en punto",
                unitAlwaysPresent = false,
                decimalSeparator = ',',
            )
            Language.FR -> LangNumConfig(
                cardinal = RuleBasedNumberFormat(ULocale.FRENCH, RuleBasedNumberFormat.SPELLOUT),
                ordinalRuleSet = null, // dito: nur feminine/masculine Rulesets
                hourUnitWord = "heures",
                unitAlwaysPresent = true, // franzoesisch: "vingt heures quinze" wie deutsch "Uhr"
                decimalSeparator = ',',
            )
            Language.IT -> LangNumConfig(
                cardinal = RuleBasedNumberFormat(ULocale.ITALIAN, RuleBasedNumberFormat.SPELLOUT),
                ordinalRuleSet = null, // dito: nur feminine/masculine Rulesets
                hourUnitWord = "in punto",
                unitAlwaysPresent = false,
                decimalSeparator = ',',
            )
        }

        /**
         * Baut den Erkennungs-Regex EINER Sprache. Reihenfolge der Alternativen ist
         * SICHERHEITSRELEVANT fuer die Korrektheit (nicht fuer Speak-Safety): Java/Kotlin-
         * Regex-Alternation nimmt die ERSTE passende Alternative an einer Startposition
         * (kein automatisches Longest-Match) — darum stehen die spezifischeren Muster
         * (Uhrzeit/Ordinal/Dezimalzahl) VOR dem generischen Ganzzahl-Fang `integer`.
         */
        private fun buildPattern(decimalSeparator: Char, hasOrdinal: Boolean): Regex {
            val decSepEscaped = if (decimalSeparator == '.') """\.""" else decimalSeparator.toString()
            val ordinalAlt = if (hasOrdinal) """(?<ordinal>\b\d{1,2}\.)(?=\s)|""" else ""
            return Regex(
                """(?<clockColon>\b(?:[01]?\d|2[0-3]):[0-5]\d\b)""" +
                    """|(?<clockWord>\b(?<cwHour>[01]?\d|2[0-3])\s?Uhr(?:\s+(?<cwMinute>[0-5]?\d))?\b)""" +
                    """|$ordinalAlt""" +
                    """(?<decimal>\b\d+$decSepEscaped\d+\b)""" +
                    """|(?<integer>\b\d+\b)""",
            )
        }
    }
}
