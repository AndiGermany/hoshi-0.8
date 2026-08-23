package de.hoshi.core.port

import java.time.LocalTime

/**
 * **NightModeMode** — die Betriebsart eines [NightModeConfig] pro Gerät.
 * [ALWAYS] ignoriert [NightModeConfig.from]/[NightModeConfig.to] (immer gedämpft,
 * solange [NightModeConfig.enabled]); [SCHEDULE] wertet das Nacht-Zeitfenster aus
 * (mit Mitternachts-Rollover, s. [NightModeCompute]).
 */
enum class NightModeMode { SCHEDULE, ALWAYS }

/**
 * Framework- and clock-free night-mode settings for one device.
 * ADR-005-nachtmodus-pro-geraet: v1 stores one config per `satelliteId`, never
 * one global household value.
 *
 * @property enabled ADR-005: master toggle; false always computes inactive.
 * @property mode scheduled window or always dimmed while enabled.
 * @property from inclusive `HH:mm` window start, used only by [NightModeMode.SCHEDULE].
 * @property to exclusive `HH:mm` end; a window may cross midnight.
 * @property dim ADR-005: continuous slider value `0.0..1.0`, not fixed levels.
 */
data class NightModeConfig(
    val enabled: Boolean = false,
    val mode: NightModeMode = NightModeMode.SCHEDULE,
    val from: String = "22:00",
    val to: String = "07:00",
    val dim: Double = 0.3,
)

/**
 * **NightModeCompute** — die REINE, uhrfreie Berechnung des `night_mode`-Downlink-
 * Frames (Nachtmodus, Scheibe 2 von 3: `vault/tracks/prep/PREP-nachtmodus.md`).
 * Der SERVER hat die Uhr, das ESP32-Gerät nicht — [active] ist die EINE Wahrheit,
 * die ALLE DREI Push-Wege teilen (ws-Connect-Initialzustand, Settings-PUT,
 * Scheduler-Tick-Grenze, s. `:web-inbound` `NightModeService`), damit sie niemals
 * auseinanderdriften.
 *
 * **Mitternachts-Rollover ist der Knackpunkt:** ein Fenster wie `22:00`–`07:00`
 * (from > to) umschließt Mitternacht — `now` ist dann GENAU dann im Fenster, wenn
 * es NACH `from` ODER VOR `to` liegt (`now >= from || now < to`). Ein „normales"
 * Fenster (`from <= to`, z.B. `09:00`–`17:00`) bleibt das simple `from <= now < to`.
 * Beide Fälle sind ein HALB-OFFENES Intervall (`from` inklusiv, `to` exklusiv) —
 * exakt `to` gilt schon als „vorbei".
 *
 * **Never-throw:** kaputte/unparsebare `HH:mm`-Strings (z.B. eine handgeschriebene
 * Store-Datei) lösen NIE eine Exception aus — [active] liefert dann `false`
 * (konservativ: lieber kein Dimmen als ein Crash am Push-Rand).
 */
object NightModeCompute {

    /**
     * `true` ⇔ das Gerät JETZT gedämpft sein soll: `enabled` UND (ALWAYS ODER
     * (SCHEDULE UND `now` liegt im [from, to)-Fenster, mit Mitternachts-Rollover)).
     */
    fun active(config: NightModeConfig, now: LocalTime): Boolean {
        if (!config.enabled) return false
        return when (config.mode) {
            NightModeMode.ALWAYS -> true
            NightModeMode.SCHEDULE -> {
                val from = parseTimeOrNull(config.from) ?: return false
                val to = parseTimeOrNull(config.to) ?: return false
                inWindow(from, to, now)
            }
        }
    }

    /**
     * Das fertige `night_mode`-Downlink-Frame: `{"type":"night_mode","active":bool,"dim":double}`.
     * `dim` ist IMMER der (auf `[0,1]` geklammerte) konfigurierte Wert — bedeutsam ist er
     * nur, wenn `active=true` ist (das Gerät ignoriert ihn sonst im Normalbetrieb, s.
     * [NightModeConfig.dim]-KDoc); er wird bei `active=false` NICHT künstlich auf 0 gesetzt,
     * damit ein Re-Aktivieren (z.B. Moduswechsel) sofort mit dem zuletzt gewählten Dimm-Wert
     * greift, ohne dass der Nutzer ihn neu setzen muss.
     */
    fun buildFrame(config: NightModeConfig, now: LocalTime): Map<String, Any?> =
        linkedMapOf(
            "type" to "night_mode",
            "active" to active(config, now),
            "dim" to config.dim.coerceIn(0.0, 1.0),
        )

    /**
     * Mitternachts-Rollover-Logik: `from <= to` ⇒ normales Fenster `[from, to)`;
     * `from > to` (z.B. `22:00`–`07:00`) ⇒ das Fenster umschließt Mitternacht,
     * `now` ist drin, wenn es NACH `from` ODER VOR `to` liegt.
     */
    private fun inWindow(from: LocalTime, to: LocalTime, now: LocalTime): Boolean =
        if (from <= to) now >= from && now < to else now >= from || now < to

    /**
     * Tolerantes `HH:mm`-Parsing (ISO_LOCAL_TIME, zwei-stellige Stunde) — kaputte/leere
     * Strings liefern `null` statt einer Exception (never-throw an dieser Naht). Auch
     * von `:web-inbound` (Settings-PUT-Validierung) genutzt, damit Parsing-Regel und
     * Compute-Regel niemals auseinanderdriften.
     */
    fun parseTimeOrNull(raw: String): LocalTime? = runCatching { LocalTime.parse(raw.trim()) }.getOrNull()
}
