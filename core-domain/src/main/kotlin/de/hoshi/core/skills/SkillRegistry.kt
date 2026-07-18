package de.hoshi.core.skills

/**
 * **SkillTier** — wie weit ein Skill physisch reicht, und damit (Tom) wie sein
 * Default bei NICHT-gesetztem Store ausfällt:
 *  - [LOCAL]: rein on-device, kein Netz-Egress ⇒ privacy-unkritisch ⇒ **opt-out**
 *    (Default AN, byte-identisch zum heutigen Verhalten).
 *  - [EGRESS]: verlässt die Box (HTTP/DNS zu einem Drittdienst, z.B. Währung,
 *    Online-Nachschauen) ⇒ **fail-closed** (Default AUS).
 *  - [CLOUD]: schickt Inhalt an ein Cloud-LLM/Drittanbieter ⇒ **fail-closed** (Default AUS).
 *
 * Heute trägt JEDER Skill [LOCAL] ⇒ [defaultEnabled] ist überall true ⇒ byte-neutral;
 * EGRESS/CLOUD sind die fertige Naht, bevor der erste Egress-Skill dazukommt.
 */
enum class SkillTier {
    LOCAL,
    EGRESS,
    CLOUD,
    ;

    /**
     * **Fail-closed Default (Tom):** der Effektiv-Wert eines Skills, für den im Store
     * NICHTS gesetzt ist. Nur [LOCAL] ist opt-out (AN); alles, was die Box verlässt
     * ([EGRESS]/[CLOUD]), ist fail-closed (AUS) — ein Egress-Skill ist nie still aktiv,
     * bevor ihn jemand bewusst einschaltet.
     */
    val defaultEnabled: Boolean
        get() = this == LOCAL
}

/**
 * **SkillDescriptor** — die ruhenden Metadaten eines Skills (reine Daten, Spring-frei):
 * seine [id], der zugehörige Wire-Flag-Name ([wireId], die ENV-Decke), die
 * Anzeige-Labels und sein [tier]. Die Settings-API und das FE (S2) lesen DIESE Liste,
 * statt die Flag-Namen erneut hart zu kodieren.
 *
 * [wireId] ist bewusst je Skill explizit (nicht aus dem Enum abgeleitet): der
 * Smart-Home-Skill hängt historisch am Flag HOSHI_TOOLS_ENABLED, nicht an einem
 * namens-abgeleiteten HOSHI_SMART_HOME_ENABLED.
 */
data class SkillDescriptor(
    val id: SkillId,
    val wireId: String,
    val labelDe: String,
    val labelEn: String,
    val tier: SkillTier = SkillTier.LOCAL,
)

/**
 * **SkillRegistry** — die EINE statische Liste aller bekannten Skills + ihrer
 * Metadaten. Keine Laufzeit-Logik, kein Wiring (der Laufzeit-Toggle kommt in S2 über
 * den [SkillStatePort]); rein die Tabelle, gegen die API/FE rendern.
 */
object SkillRegistry {
    val ALL: List<SkillDescriptor> = listOf(
        SkillDescriptor(SkillId.SMART_HOME, "HOSHI_TOOLS_ENABLED", "Smart-Home", "Smart home"),
        SkillDescriptor(SkillId.SCENES, "HOSHI_SCENES_ENABLED", "Szenen", "Scenes"),
        SkillDescriptor(SkillId.TIMER, "HOSHI_TIMER_ENABLED", "Timer und Wecker", "Timers and alarms"),
        SkillDescriptor(SkillId.CALCULATOR, "HOSHI_CALCULATOR_ENABLED", "Rechner", "Calculator"),
    )

    /** Metadaten zu einem [SkillId] (jede ID ist in [ALL] vertreten). */
    fun byId(id: SkillId): SkillDescriptor = ALL.first { it.id == id }

    /**
     * **Tier-abhängiger Default (Tom)** für einen Skill, dessen Store-Wert NICHT gesetzt ist:
     * LOCAL ⇒ opt-out (true, byte-neutral), EGRESS/CLOUD ⇒ fail-closed (false). DIE eine
     * Wahrheit, gegen die Store, Settings-View und das `runtimeDefault`-Wiring rendern.
     */
    fun defaultEnabledFor(id: SkillId): Boolean = byId(id).tier.defaultEnabled
}
