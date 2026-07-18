package de.hoshi.core.skills

/**
 * **SkillStatePort** — die pro-Turn lesbare Toggle-Wahrheit: ist ein [SkillId] JETZT
 * an? Genau EIN Lese-Punkt (Spring-frei, side-effect-frei), den der
 * [de.hoshi.core.pipeline.DeterministicToolIntentClassifier] bei jedem `classify()`
 * befragt. Der Laufzeit-Store (JsonFile) und die Settings-API hängen sich in S2 an
 * DIESEN Port — der Classifier-Code ändert sich dafür nicht mehr.
 *
 * Bewusst ein [fun interface] (SAM): der heutige Default ist [NONE] (alles aus ⇒ der
 * Classifier liefert nie einen Call, identisch zum `ToolIntentClassifier.DISABLED`-
 * Verhalten), und die Migration der bestehenden 4-Boolean-Konstruktion läuft über
 * den konstanten Port [ofStatic] — byte-identisch.
 */
fun interface SkillStatePort {
    fun isEnabled(id: SkillId): Boolean

    companion object {
        /**
         * Verhaltens-neutraler Default: JEDER Skill aus. Mit diesem Port klassifiziert
         * der Classifier nichts (kein Timer, kein Calc, und der Smart-Home-Guard greift
         * sofort) ⇒ exakt der heutige all-OFF-Zustand.
         */
        val NONE: SkillStatePort = SkillStatePort { false }

        /**
         * **Konstanter Port aus festen Boolean-Werten** — der byte-identische Ersatz für
         * die bisherigen vier Ctor-Booleans. Bildet jede [SkillId] auf ihren festen Wert
         * ab; ändert sich zur Laufzeit nie (für statische Konstruktion + Tests). Der
         * dynamische Store (S2) implementiert denselben Port stattdessen veränderlich.
         */
        fun ofStatic(
            smartHome: Boolean,
            scenes: Boolean,
            timer: Boolean,
            calculator: Boolean,
        ): SkillStatePort = SkillStatePort { id ->
            when (id) {
                SkillId.SMART_HOME -> smartHome
                SkillId.SCENES -> scenes
                SkillId.TIMER -> timer
                SkillId.CALCULATOR -> calculator
            }
        }
    }
}
