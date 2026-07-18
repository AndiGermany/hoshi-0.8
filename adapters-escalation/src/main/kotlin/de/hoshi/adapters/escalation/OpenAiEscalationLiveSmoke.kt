package de.hoshi.adapters.escalation

import de.hoshi.core.dto.Language
import de.hoshi.core.port.EscalationResult
import de.hoshi.kernel.EgressPort
import java.nio.file.Files
import java.time.Duration

/**
 * **Isolierter Live-Smoke** für den [OpenAiEscalationAdapter] — analog
 * `OpenAiTtsLiveSmoke`: ein echter Call gegen die OpenAI-API, OHNE Brain,
 * OHNE Pipeline. Läuft NUR manuell (Orchestrator/Andi), NIE in Tests.
 *
 *     OPENAI_API_KEY=… ./gradlew :adapters-escalation:run --args="Wie hoch ist der Eiffelturm?"
 *     OPENAI_API_KEY=… ./gradlew :adapters-escalation:run --args="Wann kommt GTA 6 raus? gpt-5.6-sol web"
 *
 * Optional zweites Argument: Modell-ID (Default [EscalationModelCatalog.DEFAULT_MODEL_ID]).
 * Optional drittes Argument (Andi-Auftrag 2026-07-19, video-kritisch): `web`/`noweb`
 * erzwingt bzw. unterdrückt den echten Web-Search-Pfad
 * ([OpenAiEscalationAdapter.webSearch]) explizit; ohne dieses Argument entscheidet die
 * Modell-Klasse — die gpt-5.6-Recherche-Familie ist FÜR Web-Search gedacht (s.
 * [EscalationModelCatalog]-KDoc), Nano/Mini bleiben default am reinen Modellwissen-Pfad.
 * Spend wird in eine TEMP-Datei gebucht (der echte Tages-Zähler bleibt unberührt).
 * Der Key wird nie ausgegeben.
 */
fun main(args: Array<String>) {
    val key = System.getenv("OPENAI_API_KEY")
    if (key.isNullOrBlank()) {
        System.err.println("OPENAI_API_KEY fehlt — Live-Smoke braucht einen echten Key (wird nie geloggt).")
        return
    }
    val query = args.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "Wie hoch ist der Eiffelturm?"
    val model = args.getOrNull(1)?.takeIf { it.isNotBlank() } ?: EscalationModelCatalog.DEFAULT_MODEL_ID
    val webSearchArg = args.getOrNull(2)?.trim()?.lowercase()
    val webSearch = when (webSearchArg) {
        "web" -> true
        "noweb" -> false
        // Kein explizites drittes Argument ⇒ Modell-Klasse entscheidet: die
        // gpt-5.6-Recherche-Familie IST der Web-Search-Fall (s. EscalationModelCatalog).
        else -> model.startsWith("gpt-5.6")
    }

    val spendPath = Files.createTempDirectory("escalation-smoke").resolve("spend.json")
    val store = FileBackedEscalationSpendStore(spendPath)
    val adapter = OpenAiEscalationAdapter(
        egress = EgressPort(),
        apiKey = key,
        spendStore = store,
        model = model,
        webSearch = webSearch,
        timeoutSeconds = 20,
    )

    println("[smoke] model=$model webSearch=$webSearch queryChars=${query.length}")
    val result = adapter.lookup(query, "", Language.DE).block(Duration.ofSeconds(30))
    when (result) {
        is EscalationResult.Answer -> {
            println("[smoke] ANSWER (cost=${"%.4f".format(result.costCents)} ct, quelle=${result.source})")
            println(result.text)
        }
        EscalationResult.Unclear -> println("[smoke] UNKLAR — Modell weiß es ehrlich nicht")
        is EscalationResult.Declined -> println("[smoke] DECLINED — ${result.auditReason}")
        EscalationResult.CapExhausted -> println("[smoke] CAP_EXHAUSTED — Tages-Budget aufgebraucht")
        EscalationResult.Unavailable, null -> println("[smoke] UNAVAILABLE — kein Ergebnis (Key/Netz?)")
    }
    println("[smoke] Tages-Spend (Temp-Datei): ${"%.4f".format(store.spentTodayCents())} ct")
}
