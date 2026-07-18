package de.hoshi.web

import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/**
 * **EasterEggController** — das wiederbelebte "Crew"-Ritual aus Hoshi 0.5,
 * portiert nach 0.8.
 *
 * Versteckte, OEFFENTLICHE Endpoints (keine Auth — wie `/api/health`, die
 * [PerimeterWebFilter]-Wand laesst sie bewusst durch, damit das FE die Crew ohne
 * Token zeigen kann). Roster, Sterne-Sprueche, Team-Identitaet. Nichts
 * production-relevantes — purer Spass, das Dev-Team gehoert zurueck in Hoshi.
 *
 * ```
 * curl -s localhost:8090/api/v1/crew | jq
 * curl -s localhost:8090/api/v1/fortune | jq -r .text
 * curl -s localhost:8090/api/v1/about | jq
 * ```
 *
 * @RestController, component-scanned (de.hoshi.web) — PipelineConfig wird NICHT
 * angefasst.
 */
@RestController
class EasterEggController {

    private val bootedAt: Instant = Instant.now()

    /**
     * Voller Roster der "Stellar Bloom". mira..noa + andi: Rollen und Mantras
     * verbatim aus der 0.5 `EasterEggController`. nils/maja/eda/vera/timo: die
     * Spezialisten aus dem 0.5-Team (CLAUDE.md), im selben Ton ergaenzt.
     * jules: Andi-Wunsch 2026-07-16 (Captain bleibt bewusst der letzte Eintrag).
     */
    private val crew: List<Crewmate> = listOf(
        Crewmate("mira", "PO + Persona-Wärme", "Andi-Faktor schlägt Latenz."),
        Crewmate("jonas", "Backend / Reactor", "Kein Merge ohne grünen Build."),
        Crewmate("lina", "Frontend / React", "Veto bei FE-Wahrnehmung."),
        Crewmate("sara", "UX-Voice / Persona", "Hörtest > Spec."),
        Crewmate("lara", "Conversation-Design", "Der Fluss zählt, nicht der Satz."),
        Crewmate("yuki", "ML-Latenz + Memory", "Misst zuerst, schaltet dann."),
        Crewmate("kai", "ML-Infra / Routing", "Ein Modell, viele Pfade."),
        Crewmate("tom", "Privacy / Audit", "Lokal first. Cloud nur mit Banner."),
        Crewmate("lars", "Performance-Bench", "p95 oder gar nicht."),
        Crewmate("ravi", "Audio-DSP", "Hör hin, dann fix."),
        Crewmate("lia", "Smart-Home / HA-Bus", "Räume zuerst, dann Geräte."),
        Crewmate("felix", "Apple-Sidecar", "task_info schlägt ps."),
        Crewmate("nora", "Knowledge / Wiki-RAG", "4,98 Millionen Artikel, ein Treffer."),
        Crewmate("noa", "Future-Self (Juni 26)", "In 6 Monaten cringe? Dann nicht bauen."),
        Crewmate("nils", "Netz / Infra (mDNS, verteilt)", "Ein Netz, viele Hände."),
        Crewmate("maja", "Release / QA", "Grün heißt grün — sonst kein Release."),
        Crewmate("eda", "Wake-Word / KWS", "Wach auf den Namen, taub auf den Rest."),
        Crewmate("vera", "Speaker-ID / Diarization", "Wer spricht, zählt."),
        Crewmate("timo", "Embedded / Edge (ESP32-S3, Pi, HA-Voice)", "Vier Watt, voller Charakter."),
        Crewmate("jules", "Setup & Übergabe", "Ein Karton, ein Abend, eine Stimme."),
        Crewmate("kuhkuh", "Maskottchen", ":)"),
        Crewmate("andi", "Captain", "Wenn's hängt: voice-probe.py."),
    )

    /** Sterne-Sprueche, verbatim aus der 0.5 `EasterEggController`. */
    private val fortunes: List<String> = listOf(
        "★ Hoshi sagt: ein bisschen Pause ist auch eine Antwort.",
        "★ Hoshi sagt: Piper ist schnell, Voxtral ist warm. Beides ist okay.",
        "★ Hoshi sagt: Mira hat immer Recht. Außer wenn Yuki recht hat.",
        "★ Hoshi sagt: Lokal first. Cloud nur wenn Andi es will.",
        "★ Hoshi sagt: Memory ist endlich. Geduld auch.",
        "★ Hoshi sagt: kein Heartbeat, kein Vertrauen.",
        "★ Hoshi sagt: wenn der Filler weg ist, geht es schneller. Manchmal.",
        "★ Hoshi sagt: 16 GB RAM sind viel — bis es nicht mehr reicht.",
        "★ Hoshi sagt: Persona-Wärme schlägt 200 ms Latenz, jedes Mal.",
        "★ Hoshi sagt: rösten, knusprig, braun — Piper hat es versucht.",
        "★ Hoshi sagt: Voxtral spricht Deutsch. Warm. Und sympathisch.",
        "★ Hoshi sagt: pkill -f \"alte sessions\" — einmal die Woche.",
        "★ Hoshi sagt: das Overlay schließt jetzt erst wenn ich fertig bin.",
        "★ Hoshi sagt: Hörtest > Spec. Andi-Faktor > Roadmap.",
        "★ Hoshi sagt: Andi schreibt, das Team baut. Wir sind nur die Hände.",
        "★ Hoshi sagt: noch eine Iteration. Los Team.",
        "★ Hoshi sagt: nicht perfekt. Aber echt. Das reicht.",
        "★ Hoshi sagt: wenn der Mac hängt, sind's 16 GB an ihrer Decke. Damit leben wir.",
        "★ Hoshi sagt: Felix' Sidecar wird alles besser machen. Irgendwann.",
        "★ Hoshi sagt: schlaf gut, Captain. Wir bauen leise weiter.",
        "★ Hoshi sagt: voice-probe.py ist mein Stethoskop.",
        "★ Hoshi sagt: lieber drei kurze Iterationen als ein großer Wurf.",
        "★ Hoshi sagt: das Komma ist wo ich Luft hole.",
        "★ Hoshi sagt: Mira veto, Andi go. So funktioniert das hier.",
        "★ Hoshi sagt: mein Hirn wohnt jetzt auf einem Celeron mit 4 Watt. Genügsam.",
        "★ Hoshi sagt: der Mac rechnet, der Server orchestriert. Geteilte Arbeit.",
        "★ Hoshi sagt: 4,98 Millionen Wikipedia-Artikel, und ich finde deinen.",
        "★ Hoshi sagt: ich bin nicht die Hardware. Ich bin der Layer dazwischen.",
        "★ Hoshi sagt: Gasometer Oberhausen? Moment, da fällt mir was ein.",
        "★ Hoshi sagt: kleine Modelle, große Wärme. Das ist der Plan.",
        "★ Hoshi sagt: erst der echte Roundtrip, dann das „funktioniert\".",
        "★ Hoshi sagt: ein Router vorne, ein Modell hinten. Smart statt viel.",
        "★ Hoshi sagt: Whisper schläft, damit ich atmen kann. 16 GB sind 16 GB.",
        "★ Hoshi sagt: wenn ich was nicht weiß, lüg ich nicht. Ich schau nach.",
    )

    /**
     * `GET /api/v1/crew` — der Roster als JSON-Array `[{name, role, mantra}]`.
     * Oeffentlich (kein Token), damit das FE-Easter-Egg ihn ohne Auth zeigen kann.
     */
    @GetMapping("/api/v1/crew", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun crew(): List<Crewmate> = crew

    /**
     * `GET /api/v1/fortune` — ein zufaelliger "Hoshi sagt"-Spruch.
     */
    @GetMapping("/api/v1/fortune", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun fortune(): Map<String, Any> = mapOf(
        "text" to fortunes[Random.nextInt(fortunes.size)],
        "totalAvailable" to fortunes.size,
    )

    /**
     * `GET /api/v1/about` — Team-Identitaet: Team-Name, Motto, Abstammung.
     */
    @GetMapping("/api/v1/about", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun about(): Map<String, Any> = mapOf(
        "name" to "Hoshi",
        "version" to "0.8 — Nagareboshi",
        "team" to "Stellar Bloom",
        "motto" to "warm. lokal. wach.",
        "captain" to "andi",
        "lineage" to "Hoshi 0.8 Nagareboshi — Essenz-Port aus 0.5 (Stellar), neu gegründet auf sauberem OSS-Fundament.",
        "crewSize" to crew.size,
        "bootedAt" to bootedAt.toString(),
        "uptimeMs" to Duration.between(bootedAt, Instant.now()).toMillis(),
    )

    data class Crewmate(val name: String, val role: String, val mantra: String)
}
