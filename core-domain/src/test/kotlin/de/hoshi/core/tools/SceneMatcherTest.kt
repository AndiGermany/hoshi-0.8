package de.hoshi.core.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Beweist den konservativen, false-positive-aversen [SceneMatcher] gegen die ECHTEN
 * HA-`scene_id`s (read-only geladen 2026-06-27, 36 Szenen). Distinktiver Treffer ⇒
 * realer `scene_id`; bloßer Raum / generisches „licht" / Mehrdeutigkeit ⇒ `null`.
 */
class SceneMatcherTest {

    /** Andis 36 reale HA-Szenen (read-only `GET /api/states`, scene.* gestrippt). */
    private val real36 = listOf(
        "arbeitszimmer_gedimmt", "arbeitszimmer_hell", "arbeitszimmer_nachtlicht",
        "flur_energie_tanken", "flur_entspannen", "flur_fruhlingsbluten", "flur_gedimmt",
        "flur_hell", "flur_konzentrieren", "flur_lesen", "flur_nachtlicht", "flur_nordlichter",
        "flur_sonnenuntergang_savanne", "flur_tropendammerung",
        "kuche_gedimmt", "kuche_hell", "kuche_nachtlicht",
        "schlafzimmer_gedimmt", "schlafzimmer_hell", "schlafzimmer_nachtlicht",
        "schlafzimmer_schlafzimmer_2",
        "wohnzimmer_abend", "wohnzimmer_chillmodus", "wohnzimmer_energie_tanken",
        "wohnzimmer_entspannen", "wohnzimmer_fruhlingsbluten", "wohnzimmer_gedimmt",
        "wohnzimmer_hell", "wohnzimmer_konzentrieren", "wohnzimmer_lesen", "wohnzimmer_nachtlicht",
        "wohnzimmer_nordlichter", "wohnzimmer_sonnenuntergang_savanne", "wohnzimmer_sternennebel",
        "wohnzimmer_tropendammerung", "wohnzimmer_tv_licht",
    )

    /** Realer Katalog OHNE flur_nordlichter — so ist „nordlichter" eindeutig wohnzimmer. */
    private val nordlichterUnique = real36.filterNot { it == "flur_nordlichter" }

    @Test
    fun `die nordlichter matcht wohnzimmer_nordlichter wenn eindeutig`() {
        assertEquals("wohnzimmer_nordlichter", SceneMatcher.match("die nordlichter", nordlichterUnique))
    }

    @Test
    fun `kueche gedimmt matcht kuche_gedimmt (Umlaut)`() {
        assertEquals("kuche_gedimmt", SceneMatcher.match("küche gedimmt", real36))
    }

    @Test
    fun `kuche gedimmt matcht kuche_gedimmt (ASCII)`() {
        assertEquals("kuche_gedimmt", SceneMatcher.match("kuche gedimmt", real36))
    }

    @Test
    fun `nachtlicht im flur matcht flur_nachtlicht`() {
        assertEquals("flur_nachtlicht", SceneMatcher.match("nachtlicht im flur", real36))
    }

    @Test
    fun `flur nachtlicht matcht flur_nachtlicht`() {
        assertEquals("flur_nachtlicht", SceneMatcher.match("flur nachtlicht", real36))
    }

    @Test
    fun `schlafzimmer hell matcht schlafzimmer_hell`() {
        assertEquals("schlafzimmer_hell", SceneMatcher.match("schlafzimmer hell", real36))
    }

    @Test
    fun `mach das licht an matcht keine Szene (generisches licht ist nicht distinktiv)`() {
        // wohnzimmer_tv_licht enthält den Token "licht" — der ist generisch, kein Trigger.
        assertNull(SceneMatcher.match("mach das licht an", real36))
    }

    @Test
    fun `wohnzimmer allein matcht keine Szene (bloßer Raum-Token reicht nicht)`() {
        assertNull(SceneMatcher.match("wohnzimmer", real36))
    }

    @Test
    fun `nachtlicht ohne Raum ist mehrdeutig und liefert null`() {
        // 5 nachtlicht-Szenen, kein Raum genannt ⇒ kein Raten.
        assertNull(SceneMatcher.match("nachtlicht", real36))
    }

    @Test
    fun `nordlichter mit zwei Treffern ohne Raum liefert null (konservativ in Produktion)`() {
        // Realdaten: wohnzimmer_nordlichter UND flur_nordlichter ⇒ mehrdeutig ⇒ null.
        assertNull(SceneMatcher.match("die nordlichter", real36))
    }

    @Test
    fun `Raum im Text schaerft zwischen zwei nordlichter-Szenen`() {
        // Der genannte Raum trifft als Szenen-Token mit (Score) ⇒ die richtige Szene gewinnt.
        assertEquals("flur_nordlichter", SceneMatcher.match("nordlichter im flur", real36))
        assertEquals("wohnzimmer_nordlichter", SceneMatcher.match("nordlichter wohnzimmer", real36))
    }

    @Test
    fun `leerer Katalog liefert null`() {
        assertNull(SceneMatcher.match("die nordlichter", emptyList()))
    }

    @Test
    fun `mehrwort-Szene fruehlingsblueten matcht ueber Umlaut-Normalisierung`() {
        // Frühlingsblüten → HA-Slug fruhlingsbluten (ü→u, nicht ue): Text muss trotzdem matchen.
        assertEquals("wohnzimmer_fruhlingsbluten", SceneMatcher.match("frühlingsblüten wohnzimmer", real36))
    }
}
