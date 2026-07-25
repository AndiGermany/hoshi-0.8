package de.hoshi.adapters.knowledge

import de.hoshi.core.dto.Language

/**
 * **WMO-Wettercode → Text, alle 5 Sprachen (DE/EN/ES/FR/IT).**
 *
 * Reine Daten-/Übersetzungs-Katalog-Datei, herausgelöst aus
 * [WeatherGroundingProvider] (Multilingual-Welle 2026-07-24, PREP-Notiz
 * `vault/tracks/prep/PREP-i18n-backend-restklassen.md`): die WMO-Wetterlagen
 * sind die EINE Anzeigetext-Klasse im Wetter-Adapter, die uns selbst gehört
 * (kein Nutzerdatum wie Orts-/Raumnamen) — deshalb wird sie übersetzt, nicht
 * die Orte/Labels.
 *
 * **Ein `when`-Block pro Sprache** (statt code-für-code interleaved wie
 * vorher `if (lang == DE) "…" else "…"`): so kann ein Muttersprachler EINE
 * Sprache am Stück lesen/prüfen, ohne zwischen den anderen vier hin- und
 * herzuspringen — und jeder neue WMO-Code zwingt (kein `else` in den
 * Sprach-Blöcken außer dem Katalog-weiten Fallback) niemanden, versehentlich
 * eine Sprache zu vergessen, weil [text] selbst über [Language] exhaustiv
 * verzweigt.
 *
 * **Sprachtyp ist die zentrale [Language]** (core-domain) — genau wie im
 * Schwester-Katalog [WeatherBlockTexts] und im [DayReferenceResolver]. Bis
 * 2026-07-25 stand hier ein modul-interner `WeatherGroundingProvider.Lang` mit
 * exakt denselben fünf Konstanten davor, verbunden über ein Mapping: eine
 * zweite Sprach-Wahrheit ohne Modul- oder Semantikgrenze (der Adapter kennt
 * [Language] ohnehin, sie steht in seiner [de.hoshi.core.pipeline.GroundingPort]-Signatur).
 * Sie ist weg — es gibt nur noch EINEN Sprachtyp, der auseinanderdriften könnte.
 *
 * DE bleibt WORTGLEICH zum bisherigen `WeatherGroundingProvider.weatherCodeText`
 * (Pin-Test-Garantie: der ON-Block-Pin-Test hängt DIREKT an diesen DE-Strings).
 * EN ebenfalls unverändert übernommen. ES/FR/IT sind NEU (2026-07-24) —
 * idiomatisch-gesprochen übersetzt (das hier wird vom Brain vorgelesen, keine
 * Wörterbuch-Wörtlichkeit), aber noch nicht von einem Muttersprachler
 * gegengelesen.
 *
 * Deckt die gängigen Lagen ab (klar/bewölkt/Nebel/Niesel/Regen/Schnee/Schauer/
 * Gewitter); unbekannte Codes → „wechselhaft" (und Pendants in den anderen
 * vier Sprachen).
 */
internal object WeatherCodeTexts {

    /**
     * Zentrale Auflösung: Code + Sprache → Text. Delegiert an genau EINEN
     * Sprach-`when`. KEIN `else`: eine sechste [Language] bricht hier den Build,
     * statt still auf Deutsch zu fallen (Muster [Language]-KDoc).
     */
    fun text(code: Int, language: Language): String = when (language) {
        Language.DE -> de(code)
        Language.EN -> en(code)
        Language.ES -> es(code)
        Language.FR -> fr(code)
        Language.IT -> it(code)
    }

    /** Deutsch — byte-identisch zum bisherigen `weatherCodeText`-DE-Zweig. */
    private fun de(code: Int): String = when (code) {
        0 -> "klar und sonnig"
        1 -> "überwiegend klar"
        2 -> "teilweise bewölkt"
        3 -> "bedeckt"
        45 -> "neblig"
        48 -> "gefrierender Nebel"
        51 -> "leichter Nieselregen"
        53 -> "mäßiger Nieselregen"
        55 -> "starker Nieselregen"
        56, 57 -> "gefrierender Nieselregen"
        61 -> "leichter Regen"
        63 -> "mäßiger Regen"
        65 -> "starker Regen"
        66, 67 -> "gefrierender Regen"
        71 -> "leichter Schneefall"
        73 -> "mäßiger Schneefall"
        75 -> "starker Schneefall"
        77 -> "Schneekörner"
        80 -> "leichte Regenschauer"
        81 -> "mäßige Regenschauer"
        82 -> "starke Regenschauer"
        85 -> "leichte Schneeschauer"
        86 -> "starke Schneeschauer"
        95 -> "Gewitter"
        96 -> "Gewitter mit Hagel"
        99 -> "Gewitter mit starkem Hagel"
        else -> "wechselhaft"
    }

    /** Englisch — unverändert aus dem bisherigen `weatherCodeText`-EN-Zweig. */
    private fun en(code: Int): String = when (code) {
        0 -> "clear and sunny"
        1 -> "mostly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45 -> "foggy"
        48 -> "freezing fog"
        51 -> "light drizzle"
        53 -> "moderate drizzle"
        55 -> "dense drizzle"
        56, 57 -> "freezing drizzle"
        61 -> "light rain"
        63 -> "moderate rain"
        65 -> "heavy rain"
        66, 67 -> "freezing rain"
        71 -> "light snow"
        73 -> "moderate snow"
        75 -> "heavy snow"
        77 -> "snow grains"
        80 -> "light rain showers"
        81 -> "moderate rain showers"
        82 -> "violent rain showers"
        85 -> "light snow showers"
        86 -> "heavy snow showers"
        95 -> "thunderstorm"
        96 -> "thunderstorm with hail"
        99 -> "thunderstorm with heavy hail"
        else -> "changeable"
    }

    /** Spanisch (NEU 2026-07-24) — gesprochen-idiomatisch, ungeprüft von Muttersprachler. */
    private fun es(code: Int): String = when (code) {
        0 -> "despejado y soleado"
        1 -> "mayormente despejado"
        2 -> "parcialmente nublado"
        3 -> "cielo cubierto"
        45 -> "con niebla"
        48 -> "niebla helada"
        51 -> "llovizna ligera"
        53 -> "llovizna moderada"
        55 -> "llovizna intensa"
        56, 57 -> "llovizna helada"
        61 -> "lluvia ligera"
        63 -> "lluvia moderada"
        65 -> "lluvia intensa"
        66, 67 -> "lluvia helada"
        71 -> "nevada ligera"
        73 -> "nevada moderada"
        75 -> "nevada intensa"
        77 -> "gránulos de nieve"
        80 -> "chubascos ligeros"
        81 -> "chubascos moderados"
        82 -> "chubascos torrenciales"
        85 -> "chubascos de nieve ligeros"
        86 -> "chubascos de nieve intensos"
        95 -> "tormenta"
        96 -> "tormenta con granizo"
        99 -> "tormenta con granizo intenso"
        else -> "variable"
    }

    /** Französisch (NEU 2026-07-24) — gesprochen-idiomatisch, ungeprüft von Muttersprachler. */
    private fun fr(code: Int): String = when (code) {
        0 -> "ciel clair et ensoleillé"
        1 -> "plutôt dégagé"
        2 -> "partiellement nuageux"
        3 -> "ciel couvert"
        45 -> "brouillard"
        48 -> "brouillard givrant"
        51 -> "bruine légère"
        53 -> "bruine modérée"
        55 -> "bruine dense"
        56, 57 -> "bruine verglaçante"
        61 -> "pluie légère"
        63 -> "pluie modérée"
        65 -> "forte pluie"
        66, 67 -> "pluie verglaçante"
        71 -> "neige légère"
        73 -> "neige modérée"
        75 -> "forte neige"
        77 -> "grains de neige"
        80 -> "averses de pluie légères"
        81 -> "averses de pluie modérées"
        82 -> "averses de pluie violentes"
        85 -> "averses de neige légères"
        86 -> "averses de neige fortes"
        95 -> "orage"
        96 -> "orage avec grêle"
        99 -> "orage avec grêle violente"
        else -> "changeant"
    }

    /** Italienisch (NEU 2026-07-24) — gesprochen-idiomatisch, ungeprüft von Muttersprachler. */
    private fun it(code: Int): String = when (code) {
        0 -> "sereno e soleggiato"
        1 -> "prevalentemente sereno"
        2 -> "parzialmente nuvoloso"
        3 -> "cielo coperto"
        45 -> "nebbia"
        48 -> "nebbia gelata"
        51 -> "pioviggine leggera"
        53 -> "pioviggine moderata"
        55 -> "pioviggine intensa"
        56, 57 -> "pioviggine gelata"
        61 -> "pioggia leggera"
        63 -> "pioggia moderata"
        65 -> "pioggia forte"
        66, 67 -> "pioggia gelata"
        71 -> "nevicata leggera"
        73 -> "nevicata moderata"
        75 -> "nevicata forte"
        77 -> "granuli di neve"
        80 -> "rovesci di pioggia leggeri"
        81 -> "rovesci di pioggia moderati"
        82 -> "rovesci di pioggia violenti"
        85 -> "rovesci di neve leggeri"
        86 -> "rovesci di neve forti"
        95 -> "temporale"
        96 -> "temporale con grandine"
        99 -> "temporale con grandine forte"
        else -> "variabile"
    }
}
