package de.hoshi.kernel

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import de.hoshi.core.port.BrainPort
import org.junit.jupiter.api.Test

/**
 * Der erste strukturelle Naht-Guard der hexagonalen Architektur. Bewacht die
 * Abhängigkeits-Richtung: sie zeigt NUR nach innen, der Kern bleibt rein.
 */
class ArchitectureGuardTest {

    private val imported = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("de.hoshi")

    @Test
    fun `core-domain haengt nicht an Spring`() {
        val rule = noClasses()
            .that().resideInAPackage("de.hoshi.core..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework..")
        rule.check(imported)
    }

    @Test
    fun `core-domain haengt nicht an Kernel oder Adaptern (Abhaengigkeit nur nach innen)`() {
        val rule = noClasses()
            .that().resideInAPackage("de.hoshi.core..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "de.hoshi.kernel..",
                "de.hoshi.adapters..",
            )
        rule.check(imported)
    }

    /**
     * Die **max-1-Brain-Wand** strukturell zementiert (16-GB-Realität: zwei warme
     * Modelle = OOM). Wer einen [BrainPort] als Feld HÄLT, kann den Brain rufen —
     * also darf im Domänen-Kern GENAU EINE Klasse ihn halten: der `TurnOrchestrator`
     * (dort privat → genau 1 Aufrufstelle/Turn). Jedes weitere BrainPort-Feld in
     * `de.hoshi.core..` würde einen zweiten Brain-Halter (und damit potenziell einen
     * zweiten warmen Brain) erlauben — dieser Guard fängt genau diese Drift.
     *
     * Nicht-vakuös: `TurnOrchestrator.brain` ist auf dem Test-Classpath und erfüllt
     * die Regel heute; sie schlägt fehl, sobald eine zweite Kern-Klasse einen
     * BrainPort als Feld bekommt.
     */
    @Test
    fun `BrainPort wird im Kern NUR im TurnOrchestrator als Feld gehalten (max-1-Brain)`() {
        val rule = fields()
            .that().haveRawType(BrainPort::class.java)
            .and().areDeclaredInClassesThat().resideInAPackage("de.hoshi.core..")
            .should().beDeclaredInClassesThat()
            .haveFullyQualifiedName("de.hoshi.core.pipeline.TurnOrchestrator")
        rule.check(imported)
    }
}
