package de.hoshi.core.supervision

/**
 * EHRLICHER Sidecar-Gesundheitszustand. Die Kern-Lehre aus Hoshi 0.5: `/health`
 * lügt (2xx „grün", obwohl der Prozess ein 6-Tage-Zombie war / die venv tot war /
 * die Bridge 3 Tage hing). Darum gibt es hier KEIN bool und KEIN Fake-grün,
 * sondern drei sauber getrennte Zustände:
 *
 *  - [HealthState.OK]       Naht erreichbar UND wirklich bereit (`status=ok`).
 *  - [HealthState.DEGRADED] Naht erreichbar, ABER noch nicht bereit — `status=loading`
 *                           im Warmup, fehlendes/abweichendes `model`, unerwarteter
 *                           Body. „grün != lebt": antwortet, taugt aber (noch) nicht.
 *  - [HealthState.DOWN]     Naht antwortet NICHT (Timeout, Connection refused, non-2xx).
 *
 * DEGRADED ≠ DOWN ≠ OK ist eine harte Invariante: „loading" ist DEGRADED, nicht OK;
 * unerreichbar ist DOWN, nicht DEGRADED.
 */
enum class HealthState { OK, DEGRADED, DOWN }

/**
 * Eine einzelne ehrliche Probe-Antwort. [detail] ist menschenlesbar fürs Log/Panel;
 * [measuredModel] trägt — wenn die Naht es liefert — den GEMESSENEN Modellnamen
 * (z.B. das `model`-Feld des e4b-`/health`), damit gezeigt werden kann, was wirklich
 * geladen ist statt was geglaubt wird.
 */
data class SidecarHealth(
    val state: HealthState,
    val detail: String,
    val measuredModel: String? = null,
) {
    val isReady: Boolean get() = state == HealthState.OK

    companion object {
        fun ok(detail: String, model: String? = null) = SidecarHealth(HealthState.OK, detail, model)
        fun degraded(detail: String, model: String? = null) = SidecarHealth(HealthState.DEGRADED, detail, model)
        fun down(detail: String) = SidecarHealth(HealthState.DOWN, detail, null)
    }
}
