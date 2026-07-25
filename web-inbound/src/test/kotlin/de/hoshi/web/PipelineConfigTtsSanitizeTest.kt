package de.hoshi.web

import com.sun.net.httpserver.HttpServer
import de.hoshi.adapters.tts.OpenAiTtsAdapter
import de.hoshi.core.dto.Language
import de.hoshi.core.port.TtsPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import java.lang.reflect.Method
import java.net.InetSocketAddress
import java.time.Duration

/**
 * **PipelineConfigTtsSanitizeTest** — der Sicherheitsbeweis für den BOOT-Pfad.
 *
 * **Der Fund (0.8.1).** Es gibt ZWEI Wege, auf denen ein [TtsPort] entsteht:
 *
 *  1. [TtsEngineFactory] — der LAUFZEIT-Weg (`PUT /api/v1/settings/tts`). Der hüllt seit
 *     `f550fa1` („gilt für jede Engine") JEDE gebaute Engine korrekt in einen
 *     [SanitizingTtsPort]; festgenagelt in [SanitizingTtsPortTest].
 *  2. [PipelineConfig.ttsPort] — der BOOT-Weg. Und GENAU DEN nimmt
 *     [TtsRuntimeConfig.delegatingTtsPort], solange niemand die Engine-/Stimm-Einstellung
 *     angefasst hat (`storedId == null && resolvedVoice == null` ⇒ `DelegatingTtsPort(initial = ttsPort)`).
 *     Das ist der Normalfall einer frischen Installation.
 *
 * Im Boot-Weg hing der [NeverSpeakTtsSanitizer] NUR als Konstruktor-Parameter im
 * [OpenAiTtsAdapter]. `say`, `piper` und `voxtral` wurden NACKT gebaut und sprachen den
 * ROHTEXT — Tokens, LAN-IPs, UUIDs. `f550fa1` reparierte die Fabrik und ließ diesen Bean
 * unberührt, deshalb sah der Fix von außen vollständig aus und war es nicht: die
 * „sprich niemals ein Geheimnis"-Regel galt ausgerechnet für die CLOUD-Engine und nicht
 * für die drei LOKALEN.
 *
 * **Warum akut:** Der Default einer frischen Installation ist lokal (`say`; ein
 * Zwischenstand von 0.8.1 setzte kurz Piper). Damit lief der Normalfall genau
 * durch einen der zuvor ungeschützten lokalen Zweige.
 *
 * **Nachtrag 0.8.1 — die beiden Wege sind jetzt EINER.** [PipelineConfig.ttsPort] baut
 * nichts mehr selbst, sondern ruft [TtsEngineFactory.build]; damit KANN der Boot-Weg
 * nicht mehr anders verriegelt sein als ein Laufzeit-Switch. Dass es dabei bleibt,
 * nagelt [TtsBuildPathSingleTruthTest] fest. Die Beweise unten bleiben unverändert
 * scharf: sie prüfen den Bean-Output des Boot-Pfads — jetzt eben auf der einen Kette.
 *
 * **Wie hier bewiesen wird.** Derselbe Griff wie in [VerbalizingWiringTest]: ein echter,
 * winziger JDK-`HttpServer` als Fake-Sidecar, auf den die Base-URL zeigt — und dann wird
 * geprüft, welcher Text WIRKLICH über die Leitung geht. Nicht der Decorator-Typ, sondern
 * der Wire-Text ist der Beweis; ein Typ-Assert allein hätte die Lücke nicht zwingend
 * aufgedeckt.
 *
 * Die Gegenprobe mit `sanitizeEnabled = false` (Rohtext kommt an) gehört zwingend dazu:
 * ohne sie könnte der Test auch grün sein, weil das Geheimnis nie ankam.
 *
 * `openai` bleibt bewusst ohne zweite Hülle — dieser Adapter trägt den Sanitizer INTERN,
 * direkt vor dem Cloud-Call (stärkste Position, s. [OpenAiTtsSanitizeWiringTest]).
 */
class PipelineConfigTtsSanitizeTest {

    private val config = PipelineConfig()

    /** Eine RFC-1918-Adresse aus Andis echtem LAN — [NeverSpeakTtsSanitizer] maskiert sie zu `[IP]`. */
    private val secretIp = "192.168.178.106"

    /** Ein API-Key-förmiges Geheimnis — [NeverSpeakTtsSanitizer] maskiert es zu `[TOKEN]`. */
    private val secretKey = "sk-ABCDEF0123456789abcdefXYZ"

    /** Der harmlose Teil des Satzes, der das Audio warm hält und deshalb ÜBERLEBEN muss. */
    private val harmlessPrefix = "Der Router hat die IP"

    private val secretSentence = "$harmlessPrefix $secretIp und der Schluessel lautet $secretKey."

    /**
     * Winziger Fake-TTS-Sidecar (Muster [VerbalizingWiringTest]): sammelt jeden Request-Body
     * ein, antwortet mit einem Mini-WAV. `say`, `piper` UND `voxtral` sprechen alle
     * `POST /tts` mit einem JSON-Body, der das Feld `text` trägt — ein Fake genügt für alle drei.
     */
    private fun fakeSidecar(captured: MutableList<String>): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/tts") { ex ->
            captured += ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val wav = ByteArray(16) { 1 }
            ex.sendResponseHeaders(200, wav.size.toLong())
            ex.responseBody.use { it.write(wav) }
        }
        server.start()
        return server
    }

    /**
     * Ruft die `ttsPort`-Bean OHNE Spring auf — die Fabrik trägt die Boot-Defaults 1:1 aus
     * den `@Value`-Annotationen von [TtsRuntimeConfig.ttsEngineFactory], nur die Base-URLs
     * zeigen auf den Fake-Sidecar. Muster [PipelineConfigTtsEngineTest].
     *
     * Seit 0.8.1 baut [PipelineConfig.ttsPort] nicht mehr selbst, sondern ruft diese Fabrik
     * ([TtsBuildPathSingleTruthTest] nagelt das fest) — der BEWEIS unten bleibt derselbe und
     * gilt jetzt für BEIDE Wege gleichzeitig, weil es nur noch einen gibt.
     */
    private fun buildTtsPort(ttsImpl: String, sanitize: Boolean, sidecarUrl: String): TtsPort = config.ttsPort(
        ttsEngineFactory = TtsEngineFactory(
            voxtralBaseUrl = sidecarUrl,
            voxtralVoice = "de_female",
            openaiModel = "gpt-4o-mini-tts",
            openaiVoice = "coral",
            sayBaseUrl = sidecarUrl,
            sayVoice = "",
            sayRate = 0,
            piperBaseUrl = sidecarUrl,
            piperVoice = "de_DE-thorsten-medium",
            sanitizeEnabled = sanitize,
            ttsStreamEnabled = false,
        ),
        ttsImpl = ttsImpl,
    )

    /** Synthetisiert [secretSentence] durch den Boot-Pfad und gibt den beim Sidecar ANGEKOMMENEN Body zurück. */
    private fun textSeenBySidecar(ttsImpl: String, sanitize: Boolean): String {
        val captured = mutableListOf<String>()
        val server = fakeSidecar(captured)
        try {
            val port = buildTtsPort(ttsImpl, sanitize, "http://127.0.0.1:${server.address.port}")
            port.synth(secretSentence, Language.DE).block(Duration.ofSeconds(10))
            assertTrue(
                captured.isNotEmpty(),
                "der Fake-Sidecar muss wirklich angefragt worden sein — sonst beweist der Test NICHTS " +
                    "(die lokalen Adapter schlucken Fehler best-effort und lieferten stumm leeres Audio)",
            )
            return captured.single()
        } finally {
            server.stop(0)
        }
    }

    /** Der Kern-Beweis: was der Sidecar bei ON sehen darf und was nicht. */
    private fun assertSecretMasked(seen: String, engine: String) {
        assertFalse(
            seen.contains(secretIp),
            "[$engine] die rohe LAN-IP ging an den Sidecar — genau die Boot-Pfad-Luecke: $seen",
        )
        assertFalse(
            seen.contains(secretKey),
            "[$engine] der rohe API-Key ging an den Sidecar — genau die Boot-Pfad-Luecke: $seen",
        )
        assertTrue(seen.contains("[IP]"), "[$engine] die IP-Maske muss im gesprochenen Text stehen: $seen")
        assertTrue(seen.contains("[TOKEN]"), "[$engine] die Token-Maske muss im gesprochenen Text stehen: $seen")
        assertTrue(
            seen.contains(harmlessPrefix),
            "[$engine] der harmlose Satzteil muss ueberleben (sanitize maskiert, blockt NICHT): $seen",
        )
    }

    @Test
    fun `piper im Boot-Pfad - das Geheimnis kommt beim Sidecar MASKIERT an`() {
        assertSecretMasked(textSeenBySidecar(TtsEngineIds.PIPER, sanitize = true), TtsEngineIds.PIPER)
    }

    @Test
    fun `say im Boot-Pfad - dieselbe Huelle, der Beweis haengt nicht an piper allein`() {
        assertSecretMasked(textSeenBySidecar(TtsEngineIds.SAY, sanitize = true), TtsEngineIds.SAY)
    }

    @Test
    fun `leeres HOSHI_TTS - auch der Default-Zweig ist verriegelt`() {
        // Leerer Wert = die Fresh-Clone-Default-Naht (`say`, s.
        // TtsEngineIds.canonicalOf) — die Naht, auf der eine Installation ohne gesetzte
        // Env landet, also der Normalfall einer frischen Installation.
        assertSecretMasked(textSeenBySidecar("", sanitize = true), "Default-Zweig (leeres HOSHI_TTS)")
    }

    @Test
    fun `voxtral explizit - der dritte lokale Adapter ist ebenfalls verriegelt`() {
        // Seit der Default-Umstellung ist voxtral nicht mehr der `else`-Zweig — der Beweis
        // fuer diese Engine braucht deshalb einen EIGENEN Test, sonst faellt sie beim
        // naechsten Umbau unbemerkt aus der Abdeckung.
        assertSecretMasked(textSeenBySidecar(TtsEngineIds.VOXTRAL, sanitize = true), TtsEngineIds.VOXTRAL)
    }

    @Test
    fun `Gegenprobe piper - mit abgeschaltetem Flag geht der ROHTEXT raus`() {
        // Ohne diesen Riegel waere der Kern-Test wertlos: er koennte auch gruen sein, weil das
        // Geheimnis nie am Sidecar ankam. Hier ist bewiesen, dass das Flag WIRKLICH der Schalter
        // ist — und was vor dem Fix im Boot-Pfad passierte.
        val seen = textSeenBySidecar(TtsEngineIds.PIPER, sanitize = false)

        assertTrue(seen.contains(secretIp), "OFF muss den Rohtext durchreichen (Alt-/Opt-out-Verhalten): $seen")
        assertTrue(seen.contains(secretKey), "OFF muss den Rohtext durchreichen (Alt-/Opt-out-Verhalten): $seen")
        assertFalse(seen.contains("[IP]"), "bei OFF darf keine Maske auftauchen: $seen")
    }

    @Test
    fun `piper-Bean traegt bei ON wirklich die SanitizingTtsPort-Huelle, bei OFF den nackten Adapter`() {
        assertTrue(
            buildTtsPort(TtsEngineIds.PIPER, sanitize = true, "http://127.0.0.1:1") is SanitizingTtsPort,
            "der Boot-Bean muss die lokale Engine umhuellen — das war die Luecke, die f550fa1 nicht schloss",
        )
        assertFalse(
            buildTtsPort(TtsEngineIds.PIPER, sanitize = false, "http://127.0.0.1:1") is SanitizingTtsPort,
            "OFF muss byte-neutral bleiben (nackter Adapter wie vor 0.8.1)",
        )
    }

    @Test
    fun `openai behaelt seinen INTERNEN Sanitizer statt einer zweiten Huelle`() {
        // Bewusste Asymmetrie: der Cloud-Adapter maskiert selbst, unmittelbar vor dem
        // Egress-Call (staerkste Position). Eine zusaetzliche Huelle waere wirkungslos-doppelt
        // und wuerde einen am Drehtag funktionierenden Pfad ohne Not anfassen.
        val port = buildTtsPort(TtsEngineIds.OPENAI, sanitize = true, "http://127.0.0.1:1")

        assertTrue(
            port is OpenAiTtsAdapter,
            "openai darf NICHT zusaetzlich umhuellt werden, war: ${port::class.simpleName}",
        )
    }

    @Test
    fun `Default-Riegel - HOSHI_TTS_SANITIZE_ENABLED steht an JEDER Lesestelle buchstabengleich auf true`() {
        // (e)/(f) ohne Spring-Context: die `@Value`-Ausdruecke sind zur Laufzeit lesbar
        // (RUNTIME-Retention), also wird der DEFAULT selbst festgenagelt — stabil und ohne
        // Env-/Kontext-Gefummel.
        //
        // Zwei Lesestellen bleiben uebrig, seit PipelineConfig.ttsPort nicht mehr selbst baut:
        //  1. TtsRuntimeConfig.ttsEngineFactory — die EINE Bau-Aufrufstelle (die Kette TUT es).
        //  2. PrivacyController — die UI-Anzeige (sie BEHAUPTET es). Driftet sie ab, zeigt der
        //     Vertrauens-Rand etwas anderes an, als die Kette tut — genau die Sorte stiller
        //     Luege, gegen die dieser Riegel steht.
        val expected = "\${HOSHI_TTS_SANITIZE_ENABLED:true}"

        assertEquals(
            expected,
            sanitizeValueExpr(method(TtsRuntimeConfig::class.java, "ttsEngineFactory")),
            "TtsRuntimeConfig.ttsEngineFactory",
        )
        assertEquals(
            expected,
            // Der ECHTE (nicht-synthetische) Ctor — Kotlin erzeugt wegen des Default-Parameters
            // `ttsEngineStore = null` zusaetzlich eine synthetische Ueberladung.
            sanitizeValueExpr(
                PrivacyController::class.java.constructors
                    .filterNot { it.isSynthetic }
                    .maxByOrNull { it.parameterCount }!!
                    .parameterAnnotations,
            ),
            "PrivacyController (UI-Anzeige)",
        )
    }

    @Test
    fun `Default-Riegel - PipelineConfig haelt KEINE zweite Kopie des Sanitize-Flags`() {
        // Der Gegenpol zum Test darueber: eine zweite `@Value`-Kopie in PipelineConfig waere
        // der Anfang des naechsten Auseinanderlaufens (genau so entstand die Boot-Pfad-Luecke).
        // Wer das Flag hier wieder einfuehrt, muss diesen Test aktiv anfassen.
        val exprs = PipelineConfig::class.java.methods
            .flatMap { it.parameterAnnotations.asSequence().flatMap { anns -> anns.asSequence() }.toList() }
            .filterIsInstance<Value>()
            .map { it.value }
            .filter { it.contains("HOSHI_TTS_SANITIZE_ENABLED") }

        assertTrue(
            exprs.isEmpty(),
            "PipelineConfig darf das Sanitize-Flag NICHT mehr selbst lesen (die Kette baut die " +
                "TtsEngineFactory) — gefunden: $exprs",
        )
    }

    private fun method(owner: Class<*>, name: String): Method =
        owner.methods.single { it.name == name }

    /** Der `@Value`-Ausdruck des Sanitize-Parameters — ueber den Praefix gesucht, damit die Parameter-Reihenfolge egal ist. */
    private fun sanitizeValueExpr(m: Method): String = sanitizeValueExpr(m.parameterAnnotations)

    private fun sanitizeValueExpr(parameterAnnotations: Array<Array<Annotation>>): String =
        parameterAnnotations.asSequence()
            .flatMap { it.asSequence() }
            .filterIsInstance<Value>()
            .map { it.value }
            .single { it.startsWith("\${HOSHI_TTS_SANITIZE_ENABLED") }
}
