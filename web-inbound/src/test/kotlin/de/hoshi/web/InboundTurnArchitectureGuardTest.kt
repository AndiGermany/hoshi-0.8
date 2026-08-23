package de.hoshi.web

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import de.hoshi.core.dto.ChatRequest
import de.hoshi.core.pipeline.TurnOrchestrator
import org.junit.jupiter.api.Test

class InboundTurnArchitectureGuardTest {

    private val productionClasses = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("de.hoshi.web")

    @Test
    fun `nur der Inbound-Gateway darf den TurnOrchestrator aufrufen`() {
        val rule = noClasses()
            .that().haveNameNotMatching("de\\.hoshi\\.web\\.InboundTurnGateway(\\$.*)?")
            .should().callMethod(
                TurnOrchestrator::class.java,
                "handle",
                ChatRequest::class.java,
            )
            .because(
                "jeder neue Turn-Inbound source und conversationKey durch " +
                    "InboundTurnGateway serverseitig ueberschreiben muss",
            )

        rule.check(productionClasses)
    }
}
