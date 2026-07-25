package de.hoshi.core

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/**
 * Der Multilingualität-Phase-0-Guard von core-domain. Kodiert zwei bereits
 * geltende, wertvolle Invarianten als GRÜNE Regeln gegen den Ist-Stand — bricht
 * künftig, sobald jemand versehentlich davon abweicht.
 */
class ArchitectureGuardTest {

    private val imported = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("de.hoshi.core")

    @Test
    fun `core-domain haengt nicht an Spring`() {
        val rule = noClasses()
            .that().resideInAPackage("de.hoshi.core..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework..")
        rule.check(imported)
    }

    /**
     * `LocaleContextHolder` speichert das Locale in einem `ThreadLocal` — in
     * reaktivem Code (Reactor-Scheduler wechseln Threads) unzuverlässig bis
     * schlicht falsch. Die Sprache reist stattdessen explizit als [de.hoshi.core.dto.Language]
     * durch die Domäne (Turn-Parameter, kein Ambient-Lookup).
     */
    @Test
    fun `core-domain nutzt nirgends LocaleContextHolder`() {
        val rule = noClasses()
            .that().resideInAPackage("de.hoshi.core..")
            .should().dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.context.i18n.LocaleContextHolder")
        rule.check(imported)
    }
}
