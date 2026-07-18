package de.hoshi.core.cloud

import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth

/**
 * **BudgetCap** — die Grenzwerte des Cloud-Spend-Riegels. Jeder Wert `<= 0`
 * heißt **KEIN Limit** (großzügiger, byte-neutraler Default): ein frisch
 * konstruierter [BudgetCap] (= [UNLIMITED]) blockt nie, das System verhält sich
 * exakt wie ohne Riegel.
 *
 *  - [dailyMaxCalls]: max. Anzahl Eskalationen pro Tag.
 *  - [dailyMaxCostEur]: max. EUR-Spend pro Tag.
 *  - [monthlyMaxEur]: max. EUR-Spend pro Kalendermonat
 *    (`HOSHI_CLOUD_MONTHLY_BUDGET_EUR`).
 */
data class BudgetCap(
    val dailyMaxCalls: Int = 0,
    val dailyMaxCostEur: Double = 0.0,
    val monthlyMaxEur: Double = 0.0,
) {
    companion object {
        /** Env-Schlüssel fuer das Monatsbudget in EUR. */
        const val MONTHLY_BUDGET_ENV = "HOSHI_CLOUD_MONTHLY_BUDGET_EUR"

        /** Kein einziges Limit aktiv ⇒ der Riegel ist effektiv aus. */
        val UNLIMITED = BudgetCap()

        /**
         * Liest das Monatsbudget aus der Umgebung (Default-Lookup `System.getenv`,
         * fuer Tests injizierbar). Nicht gesetzt / nicht parsebar ⇒ 0.0 = kein
         * Limit. Bewusst eine reine Factory — die Kern-Logik liest selbst NIE env.
         */
        fun fromEnv(getenv: (String) -> String? = System::getenv): BudgetCap {
            val monthly = getenv(MONTHLY_BUDGET_ENV)?.trim()?.toDoubleOrNull() ?: 0.0
            return BudgetCap(monthlyMaxEur = monthly)
        }
    }
}

/** Warum der Riegel einen Call ablehnt — maschinen-knapp, KEINE Phrase. */
enum class RefuseReason { DAILY_CALL_CAP, DAILY_COST_CAP, MONTHLY_BUDGET }

/**
 * Entscheidung des Riegels VOR einer Eskalation. [Refused] ist bewusst
 * **klartextfrei** ([reason] + [used]/[limit] als Zahlen) — die warme Absage-Phrase
 * bildet der Aufrufer.
 */
sealed interface BudgetDecision {
    /** Innerhalb aller aktiven Caps ⇒ Eskalation erlaubt. */
    data object Allowed : BudgetDecision

    /** Ein Cap erreicht/ueberschritten ⇒ Eskalation abgelehnt. */
    data class Refused(
        val reason: RefuseReason,
        val used: Double,
        val limit: Double,
    ) : BudgetDecision
}

/**
 * **CostTracker** — verfolgt den Cloud-Spend in-memory und sagt VOR jeder
 * Eskalation, ob sie noch im Budget liegt ([check]). Nach einem erfolgten Call
 * bucht der Aufrufer die echten Kosten ([record]).
 *
 * Rein + deterministisch testbar: die [clock] ist injiziert (kein `now()`). Der
 * Tag/Monat wird aus der Clock-Zeitzone abgeleitet; bei Tageswechsel resetten die
 * Tages-Zähler, bei Monatswechsel der Monats-Zähler — ganz ohne Hintergrund-Job,
 * allein über den Vergleich des aktuellen Schlüssels (eine Fake-Clock, die
 * vorgestellt wird, beweist beide Resets).
 *
 * Thread-safe via `@Synchronized` (der Spend kann aus mehreren Turns kommen).
 */
class CostTracker(
    private val cap: BudgetCap,
    private val clock: Clock,
) {
    private var day: LocalDate? = null
    private var month: YearMonth? = null
    private var callsToday: Int = 0
    private var costTodayEur: Double = 0.0
    private var costMonthEur: Double = 0.0

    /**
     * Prüft, ob die NÄCHSTE Eskalation erlaubt ist. Refuse, sobald ein aktives
     * Cap bereits erreicht ist (`>=`). Bei [BudgetCap.UNLIMITED] immer [Allowed].
     */
    @Synchronized
    fun check(): BudgetDecision {
        rollover()
        if (cap.dailyMaxCalls > 0 && callsToday >= cap.dailyMaxCalls) {
            return BudgetDecision.Refused(RefuseReason.DAILY_CALL_CAP, callsToday.toDouble(), cap.dailyMaxCalls.toDouble())
        }
        if (cap.dailyMaxCostEur > 0.0 && costTodayEur >= cap.dailyMaxCostEur) {
            return BudgetDecision.Refused(RefuseReason.DAILY_COST_CAP, costTodayEur, cap.dailyMaxCostEur)
        }
        if (cap.monthlyMaxEur > 0.0 && costMonthEur >= cap.monthlyMaxEur) {
            return BudgetDecision.Refused(RefuseReason.MONTHLY_BUDGET, costMonthEur, cap.monthlyMaxEur)
        }
        return BudgetDecision.Allowed
    }

    /** Bucht EINE erfolgte Eskalation mit ihren Kosten (EUR) auf Tag + Monat. */
    @Synchronized
    fun record(costEur: Double) {
        rollover()
        callsToday += 1
        costTodayEur += costEur
        costMonthEur += costEur
    }

    /** Aktueller Spend-Stand (fuer Sichtbarkeit/Tests). */
    @Synchronized
    fun snapshot(): Snapshot {
        rollover()
        return Snapshot(callsToday, costTodayEur, costMonthEur)
    }

    /** Setzt die Tages-/Monats-Zähler zurück, wenn die Clock einen neuen Tag/Monat zeigt. */
    private fun rollover() {
        val today = LocalDate.now(clock)
        val thisMonth = YearMonth.from(today)
        if (day != today) {
            day = today
            callsToday = 0
            costTodayEur = 0.0
        }
        if (month != thisMonth) {
            month = thisMonth
            costMonthEur = 0.0
        }
    }

    data class Snapshot(
        val callsToday: Int,
        val costTodayEur: Double,
        val costMonthEur: Double,
    )
}
