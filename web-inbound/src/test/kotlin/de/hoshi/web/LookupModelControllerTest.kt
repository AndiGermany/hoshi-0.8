package de.hoshi.web

import de.hoshi.adapters.escalation.EscalationModelCatalog
import de.hoshi.adapters.escalation.FileBackedEscalationSpendStore
import de.hoshi.core.port.EscalationPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.HttpStatus
import java.nio.file.Path

/**
 * Direkter Konstruktor-Test von [LookupModelController] (kein Spring-Context,
 * kein Netz — Muster [PipelineConfigEscalationTest]): beweist GET-Form, PUT
 * valide/unbekannt (422), dass die offene Decke den Delegaten WIRKLICH
 * umschaltet, dass die geschlossene Decke den Store schreibt aber den Delegaten
 * NICHT scharf schaltet, und dass die Persistenz einen Neustart überlebt.
 */
class LookupModelControllerTest {

    private val ha = "http://localhost:8123"

    private fun spendStore(dir: Path) =
        FileBackedEscalationSpendStore(FileBackedEscalationSpendStore.resolveDefaultPath(dir.resolve("spend.json").toString()))

    private fun controller(
        dir: Path,
        extendedThinkEnabled: Boolean = true,
        escalationModel: String = "",
        delegate: DelegatingEscalationPort = DelegatingEscalationPort("gpt-5.4-nano", EscalationPort.NONE),
        store: JsonFileLookupModelStore = JsonFileLookupModelStore(dir.resolve("lookup-model.json")),
    ) = LookupModelController(
        store = store,
        delegate = delegate,
        spendStore = spendStore(dir),
        extendedThinkEnabled = extendedThinkEnabled,
        escalationModel = escalationModel,
        haBaseUrl = ha,
        webSearchEnabled = false,
    )

    @Test
    fun `GET ohne Store-Wert - aktiv ist das Boot-Default, modelle ist der volle Katalog`(@TempDir dir: Path) {
        val view = controller(dir).lookupModel()
        assertEquals(EscalationModelCatalog.DEFAULT_MODEL_ID, view.aktiv)
        assertEquals(EscalationModelCatalog.MODELS.size, view.modelle.size)
        assertTrue(view.modelle.any { it.id == "gpt-5.6-sol" }, "der volle Katalog inkl. Recherche-Tarife")
    }

    @Test
    fun `GET ohne Store-Wert - Boot-Property statt Katalog-Default, wenn gesetzt`(@TempDir dir: Path) {
        val view = controller(dir, escalationModel = "gpt-5.4-mini").lookupModel()
        assertEquals("gpt-5.4-mini", view.aktiv)
    }

    @Test
    fun `PUT unbekannte Modell-Id - 422, kein Store-Write`(@TempDir dir: Path) {
        val store = JsonFileLookupModelStore(dir.resolve("lookup-model.json"))
        val response = controller(dir, store = store).setModel(LookupModelRequest(id = "gpt-9-phantasie"))

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.statusCode)
        assertEquals("unknown-model", (response.body as SettingsError).error)
        assertEquals(null, store.modelId(), "eine unbekannte Id darf NIE persistiert werden")
    }

    @Test
    fun `PUT bekannte Id bei offener Decke - 200, Store UND Delegat schalten um`(@TempDir dir: Path) {
        val delegate = DelegatingEscalationPort("gpt-5.4-nano", EscalationPort.NONE)
        val store = JsonFileLookupModelStore(dir.resolve("lookup-model.json"))
        val response = controller(dir, extendedThinkEnabled = true, delegate = delegate, store = store)
            .setModel(LookupModelRequest(id = "gpt-5.4-mini"))

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("gpt-5.4-mini", (response.body as LookupModelView).aktiv)
        assertEquals("gpt-5.4-mini", store.modelId(), "Store-Persist bewiesen")
        assertEquals("gpt-5.4-mini", delegate.currentModelId(), "der Delegat zeigt WIRKLICH auf das neue Modell")
    }

    @Test
    fun `PUT bekannte Id bei GESCHLOSSENER Decke - Store schreibt, Delegat bleibt unberuehrt (kein Netz)`(@TempDir dir: Path) {
        val delegate = DelegatingEscalationPort("gpt-5.4-nano", EscalationPort.NONE)
        val store = JsonFileLookupModelStore(dir.resolve("lookup-model.json"))
        val response = controller(dir, extendedThinkEnabled = false, delegate = delegate, store = store)
            .setModel(LookupModelRequest(id = "gpt-5.4-mini"))

        assertEquals(HttpStatus.OK, response.statusCode, "der Wunsch wird trotzdem gespeichert (PREP-Regal)")
        assertEquals("gpt-5.4-mini", store.modelId(), "Store-Persist bewiesen, unabhaengig von der Decke")
        assertEquals(
            "gpt-5.4-nano",
            delegate.currentModelId(),
            "Decke zu ⇒ der Delegat bleibt beim alten Modell (kein Netz, solange Extended Think aus ist)",
        )
    }

    @Test
    fun `Persistenz ueberlebt Reload - ein NEUER Controller ueber demselben Pfad sieht den PUT-Wunsch`(@TempDir dir: Path) {
        val path = dir.resolve("lookup-model.json")
        val first = controller(dir, store = JsonFileLookupModelStore(path))
        first.setModel(LookupModelRequest(id = "gpt-5.6-luna"))

        val restarted = controller(dir, store = JsonFileLookupModelStore(path))
        assertEquals("gpt-5.6-luna", restarted.lookupModel().aktiv, "der PUT-Wunsch ueberlebt einen Neustart")
    }
}
