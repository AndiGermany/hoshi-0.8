package de.hoshi.core.skills

/**
 * **SkillId** — die stabile, geschlossene Identität eines togglebaren Skills (P0 des
 * Skills-Frameworks). Reine Domäne (Spring-frei), die EINE Wahrheit, gegen die der
 * [de.hoshi.core.pipeline.DeterministicToolIntentClassifier] pro Turn fragt.
 *
 * Heute genau die vier lokalen Skills, die der Classifier bereits kennt — als
 * Enum statt loser Ctor-Booleans, damit der Laufzeit-Toggle (S2) EINE Naht hat.
 * Egress-Skills (Währung, Online-Nachschauen) kommen in späteren Scheiben als
 * eigene Enum-Werte dazu; jeder neue Skill ist additiv und braucht keine zweite
 * Wahrheit.
 */
enum class SkillId {
    SMART_HOME,
    SCENES,
    TIMER,
    CALCULATOR,
}
