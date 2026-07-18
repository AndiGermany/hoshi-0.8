package de.hoshi.core.cloud

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Beweise fuer den [CloudAuditLog]: nur SHA-256 (nie Klartext), parsebare JSONL,
 * append-only, und Schreibfehler sind nie fatal.
 */
class CloudAuditLogTest {

    private val fixedClock: Clock =
        Clock.fixed(Instant.parse("2026-06-28T10:15:30Z"), ZoneOffset.UTC)

    @Test
    fun `schreibt SHA256 und NIE den Klartext`(@TempDir dir: Path) {
        val file = dir.resolve("cloud-audit.jsonl")
        val audit = CloudAuditLog(file, fixedClock)
        val secret = "verrate Andis geheimes Passwort hunter2"

        audit.record(query = secret, provider = "openai", estimatedCostEur = 0.0123, allowed = true)

        val content = Files.readString(file)
        assertFalse(content.contains(secret), "Klartext darf NIE im Audit-File stehen")
        assertFalse(content.contains("hunter2"), "auch kein Klartext-Fragment")
        assertTrue(content.contains(CloudAuditLog.sha256(secret)), "der SHA-256-Hash gehoert ins File")
    }

    @Test
    fun `Zeile ist parsebares JSON mit allen Feldern`(@TempDir dir: Path) {
        val file = dir.resolve("cloud-audit.jsonl")
        val audit = CloudAuditLog(file, fixedClock)

        audit.record(query = "wie wird das Wetter", provider = "openai", estimatedCostEur = 0.02, allowed = false)

        val lines = Files.readAllLines(file)
        assertEquals(1, lines.size)
        val node = ObjectMapper().readTree(lines.first())
        assertEquals("2026-06-28T10:15:30Z", node.get("timestamp").asText())
        assertEquals(CloudAuditLog.sha256("wie wird das Wetter"), node.get("queryHash").asText())
        assertEquals("openai", node.get("provider").asText())
        assertEquals(0.02, node.get("estimatedCostEur").asDouble(), 1e-9)
        assertFalse(node.get("allowed").asBoolean(), "allowed=false muss durchkommen")
    }

    @Test
    fun `append-only haengt Zeilen an statt zu ersetzen`(@TempDir dir: Path) {
        val file = dir.resolve("cloud-audit.jsonl")
        val audit = CloudAuditLog(file, fixedClock)

        audit.record(query = "frage A", provider = "openai", estimatedCostEur = 0.01, allowed = true)
        audit.record(query = "frage B", provider = "openai", estimatedCostEur = 0.02, allowed = false)

        assertEquals(2, Files.readAllLines(file).size)
    }

    @Test
    fun `Schreibfehler ist nie fatal`(@TempDir dir: Path) {
        // Pfad zeigt auf das Verzeichnis selbst ⇒ writeString scheitert intern.
        val audit = CloudAuditLog(dir, fixedClock)
        // darf NICHT werfen:
        audit.record(query = "egal", provider = "openai", estimatedCostEur = 0.0, allowed = true)
    }
}
