package de.hoshi.core.supervision

/**
 * **SidecarPort** — die hexagonale Probe-Naht. Funktionales Interface (genau EINE
 * abstrakte Methode), damit Unit-Tests einen Lambda-Fake injizieren können statt
 * eine Live-Naht zu brauchen.
 *
 * Die zu probende Naht (name/url) kommt als [SidecarSpec] herein — so kann genau EINE
 * Probe-Impl (der Live-`HttpSidecarProbe`) jede Naht der [SidecarRegistry] abklopfen,
 * statt pro Sidecar eine eigene Klasse (das war der 0.5-Copy-paste der 5 Watchdogs).
 *
 * Vertrag: [probe] ist EHRLICH — es liefert OK nur bei wirklich-bereit, DEGRADED bei
 * erreichbar-aber-nicht-bereit (`loading`), DOWN bei unerreichbar. Niemals Fake-grün.
 */
fun interface SidecarPort {
    fun probe(sidecar: SidecarSpec): SidecarHealth
}
