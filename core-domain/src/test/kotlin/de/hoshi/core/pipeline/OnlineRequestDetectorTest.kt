package de.hoshi.core.pipeline

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit-Wand des [OnlineRequestDetector] — der Online-Wunsch-Klasse des
 * [HonestyGate]s (DE seit dem 0.5-Port, EN seit Andis Vorfall 2026-07-25:
 * „Take a look online for a recept of pizza." wurde auf der ENGLISCHEN
 * Oberfläche NICHT als Online-Wunsch erkannt, weil beide Wortlisten rein
 * deutsch waren).
 *
 * Die Tests halten BEIDE Seiten fest:
 *  - die DE-Bestandsmenge (unverändert),
 *  - die neuen EN-Formulierungen,
 *  - und vor allem die GEGEN-Tests: „look"/„check"/„search" sind häufige
 *    Alltagswörter und dürfen NUR mit Netz-Marker triggern.
 */
class OnlineRequestDetectorTest {

    private val detector = OnlineRequestDetector()

    // ── DE — Bestand (muss sich exakt wie bisher verhalten) ──────────────────────
    @Test
    fun `deutsche Online-Wuensche triggern wie bisher`() {
        listOf(
            "Schau bitte online nach.",
            "Guck mal im Internet.",
            "Such das im Netz.",
            "recherchier das",
            "google das mal",
            "Wie viele Einwohner hat Lissabon? Schau online.",
        ).forEach { assertTrue(detector.isOnlineRequest(it), "sollte Online-Wunsch sein: »$it«") }
    }

    // ── EN — der Vorfall-Satz und seine Geschwister ──────────────────────────────
    @Test
    fun `englische Online-Wuensche triggern jetzt genauso`() {
        listOf(
            "Take a look online for a recept of pizza.", // Andis Satz WÖRTLICH
            "Take a look online.",
            "look it up online",
            "Can you look that up?",
            "search the web for pizza recipes",
            "Search the internet for the tallest building.",
            "browse the web for news",
            "google it",
            "Can you google the opening hours?",
            "find out online when GTA 6 is released",
            "check online whether it rains tomorrow",
            "How many people live in Lisbon? Check on the internet.",
        ).forEach { assertTrue(detector.isOnlineRequest(it), "sollte Online-Wunsch sein: »$it«") }
    }

    // ── GEGEN-TESTS: Alltagswörter ohne Netz-Marker ⇒ NIE ────────────────────────
    @Test
    fun `Alltagssaetze mit look-check-search aber ohne Netz-Marker triggern NIE`() {
        listOf(
            "", "   ",
            "I'll check the oven.", // Andi-Vorgabe: der klassische Fehlalarm
            "look at this picture",
            "Look, that's funny!",
            "Can you check if the door is locked?",
            "I'm searching for my keys.",
            "Take a look at the kitchen light.",
            "Browse through the photo album with me.",
        ).forEach { assertFalse(detector.isOnlineRequest(it), "sollte KEIN Online-Wunsch sein: »$it«") }
    }

    // ── GEGEN-TESTS: Wissensfragen ÜBER das Netz ⇒ NIE (KDoc-Vertrag) ────────────
    @Test
    fun `Wissensfragen ueber Internet und Google triggern NIE`() {
        listOf(
            "Wie funktioniert das Internet?",
            "Was ist Google?",
            "How does the internet work?",
            "What is Google?",
            "Google is a big company.",
            "Was macht Google online?", // Aussage ÜBER die Firma, trotz Scope-Wort
            "I find the internet fascinating.", // „the internet" OHNE Präposition ⇒ kein Scope
            "I was shopping online yesterday.", // Scope, aber kein Nachschau-Verb
            "Ich war gestern online einkaufen",
        ).forEach { assertFalse(detector.isOnlineRequest(it), "sollte KEIN Online-Wunsch sein: »$it«") }
    }
}
