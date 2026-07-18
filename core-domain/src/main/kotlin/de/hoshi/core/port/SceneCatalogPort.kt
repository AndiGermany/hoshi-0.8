package de.hoshi.core.port

/**
 * Hexagonaler Port, der die Liste der bekannten HA-`scene_id`s (ohne `scene.`-Präfix)
 * liefert — die Quelle für den [de.hoshi.core.tools.SceneMatcher]. Der Kern kennt nur
 * diese Naht; WOHER die Szenen kommen (HA `/api/states`, Datei, Test-Fixture) lebt im
 * Adapter.
 *
 * [EMPTY] ist der verhaltens-neutrale Default: kein Katalog ⇒ der Classifier fällt auf
 * sein heutiges naives `scene.<token>`-Verhalten zurück.
 */
fun interface SceneCatalogPort {
    fun sceneIds(): List<String>

    companion object {
        /** Leerer Katalog — der Szenen-by-Name-Pfad bleibt inaktiv (Verhalten unverändert). */
        val EMPTY = SceneCatalogPort { emptyList() }
    }
}
