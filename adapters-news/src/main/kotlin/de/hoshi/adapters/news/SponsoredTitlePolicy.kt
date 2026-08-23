package de.hoshi.adapters.news

/**
 * Provider-neutral boundary policy for explicit advertising labels. Only
 * unambiguous prefixes are rejected; editorial titles such as "Anzeige gegen …"
 * remain valid because a marker delimiter or enclosing brackets are required.
 */
internal object SponsoredTitlePolicy {
    private const val MARKER = "(?:anzeige|werbung|sponsored(?:\\s+(?:post|content))?)"
    private val bracketed = Regex(
        "^\\s*[\\[(]\\s*$MARKER\\s*[\\])]" +
            "(?:\\s*(?::|[-–—])\\s*|\\s+|$)",
        RegexOption.IGNORE_CASE,
    )
    private val delimited = Regex(
        "^\\s*$MARKER\\s*(?::|[-–—])\\s*",
        RegexOption.IGNORE_CASE,
    )
    private val markerOnly = Regex("^\\s*$MARKER\\s*$", RegexOption.IGNORE_CASE)

    fun isSponsored(title: String?): Boolean {
        val candidate = title?.takeIf { it.isNotBlank() } ?: return false
        return bracketed.containsMatchIn(candidate) ||
            delimited.containsMatchIn(candidate) ||
            markerOnly.matches(candidate)
    }
}
