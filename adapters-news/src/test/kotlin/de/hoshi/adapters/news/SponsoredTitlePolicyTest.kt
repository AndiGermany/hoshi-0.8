package de.hoshi.adapters.news

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SponsoredTitlePolicyTest {
    @Test
    fun `explicit German and English advertising prefixes are rejected`() {
        listOf(
            "Anzeige: Ein Produkt",
            "  ANZEIGE – Ein Produkt",
            "[Anzeige] Ein Produkt",
            "(Werbung) Ein Produkt",
            "Werbung - Ein Produkt",
            "Sponsored: A product",
            "Sponsored Post: A product",
            "Sponsored Content — A product",
            "Anzeige",
        ).forEach { title -> assertTrue(SponsoredTitlePolicy.isSponsored(title), title) }
    }

    @Test
    fun `editorial words that merely mention advertising are retained`() {
        listOf(
            "Anzeige gegen Hersteller gestellt",
            "Werbung nervt viele Menschen",
            "Sponsored Links werden neu geregelt",
            "Die Anzeige: Was nun passiert",
            "Technik und Werbung",
        ).forEach { title -> assertFalse(SponsoredTitlePolicy.isSponsored(title), title) }
    }
}
