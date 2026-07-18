repositories {
    mavenCentral()
}

plugins {
    kotlin("jvm") version "2.0.0"
    id("org.jetbrains.kotlin.plugin.spring") version "2.0.0"
    id("org.springframework.boot") version "3.3.3"
    id("io.spring.dependency-management") version "1.1.6"
}

java {
    toolchain { languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(21)) }
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.3.3"))

    // Logging: GENAU EIN slf4j-Binding (logback, Boot-Default). Früher war logback
    // global excluded „für den Wand-Beweis" — Folge: KEIN Binding → slf4j-NOP → die
    // App loggte NICHTS (maskierte 2026-06-26 den HA-reactor-block-Bug). Für den realen
    // Deploy ist Logging Pflicht. Die per-Adapter slf4j-simple-Excludes bleiben (sonst Doppel-Binding).
    implementation("org.springframework.boot:spring-boot-starter-logging")

    // Erster Inbound-Adapter: verdrahtet den Trust-Kernel (PerimeterPort) in
    // einen echten WebFilter am gebooteten WebFlux-Context.
    implementation(project(":core-domain"))
    implementation(project(":capability-kernel"))
    // M2c-Wiring: der echte Brain-Adapter (MlxBrainAdapter, e4b :8041) wird hier
    // im @Configuration zum TurnOrchestrator verdrahtet. slf4j-simple raus —
    // web-inbound bringt sein eigenes Boot-Logging (Doppel-Binding vermeiden).
    implementation(project(":adapters-brain")) {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
    // Audio-Naht: der Voxtral-TTS-Adapter (:8042) wird hier zum TtsStage verdrahtet.
    implementation(project(":adapters-tts")) {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
    // Sprach-EINGABE-Naht: der Whisper-STT-Adapter (:9001) wird hier zum SttPort
    // verdrahtet — der Inbound-Rand /api/v1/voice macht Hoshi ansprechbar.
    implementation(project(":adapters-stt")) {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
    // Sprecher-EMBEDDING-Naht (0.8, Stimm-Anlernen): der CamppSpeakerAdapter (CAM++ :9002)
    // wird hier flag-gated (HOSHI_SPEAKER_ENROLL_ENABLED, default OFF) als SpeakerEmbedPort
    // fuer den Enroll-Rand (POST /api/v1/speakers/enroll) verdrahtet. Nutzt java.net.http
    // synchron (kein WebClient) — das Blocking kapselt der Controller per boundedElastic.
    implementation(project(":adapters-speaker")) {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
    // M4-Step-1 Wiki-Grounding: der Fts5GroundingAdapter (Knowledge-Bridge :8035)
    // wird hier flag-gated (HOSHI_GROUNDING_ENABLED, default OFF) zum GroundingPort.
    implementation(project(":adapters-knowledge")) {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
    // Multi-User-Gedächtnis (0.8): der EntityMemoryAdapter (sqlite, speakerId-keyed)
    // wird hier flag-gated (HOSHI_MEMORY_ENABLED, default OFF) als EntityContextPort
    // (Recall) + EntityMemoryWriter (Store-Hook) verdrahtet.
    implementation(project(":adapters-memory")) {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
    // AMBIG-Routing: der EmbeddingRouterRefiner (embeddinggemma :11434) wird hier
    // flag-gated (HOSHI_EMBEDDING_ROUTER, default ON) als echter RouteRefiner statt
    // des Passthrough-Stubs verdrahtet — schärfere AMBIG-Auflösung, best-effort.
    implementation(project(":adapters-routing")) {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
    // Tat-Executor: der HaToolPort (HA REST /api/services, area_id-Targeting) wird hier
    // flag-gated (HOSHI_HA_ENABLED, default OFF) als echter ToolPort statt des
    // HONEST_PLACEHOLDER verdrahtet — Default OFF ⇒ Platzhalter, Verhalten unverändert.
    implementation(project(":adapters-ha")) {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
    // Musik Stufe A (Andi-GO 2026-07-03): RadioPort-Impl — radio-browser.info-Suche mit
    // Aehnlichkeits-Schwelle + HA play_media aufs konfigurierte media_player-Ziel.
    implementation(project(":adapters-radio")) {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
    // Extended Think (S1/S2): der OpenAiEscalationAdapter (EscalationPort, Egress-Riegel +
    // Tages-Cap by construction) wird in PipelineConfig flag-gated
    // (HOSHI_EXTENDED_THINK_ENABLED, default OFF ⇒ EscalationPort.NONE) verdrahtet.
    implementation(project(":adapters-escalation")) {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
    // Ops-Status-Watchdog (HOSHI_SIDECAR_WATCH_ENABLED, default OFF): der
    // HttpSidecarProbe (SidecarPort) wird hier vom SidecarHealthService genutzt, um die
    // Mac-Sidecars über die Mac-IP zu proben (GET /api/v1/ops/status). slf4j-simple raus
    // (web-inbound bringt sein eigenes Boot-Logging — Doppel-Binding vermeiden).
    implementation(project(":adapters-supervision")) {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }

    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "ch.qos.logback", module = "logback-classic")
    }
    testImplementation("io.projectreactor:reactor-test:3.6.9")
}

tasks.test {
    // Standard-`test`/`build` laeuft den Hot-Path-Vertrag. Die Offline-Replay-Harness
    // (`@Tag("offline-replay")`) ist BEWUSST KEIN Pflicht-Vertrag im Standard-Build —
    // sie wird hier ausgeschlossen und nur ueber `verifyOffline` explizit gefahren.
    // (Sie wird trotzdem MIT-kompiliert, ein Bruch faellt also im Build auf.)
    useJUnitPlatform {
        excludeTags("offline-replay")
    }
    testLogging { events("passed", "skipped", "failed") }
}

// ── verify:offline — die reproduzierbare Offline-Replay-Harness (off-Hot-Path) ──
// Beweist deterministisches Routing/Fastpath/Sprache/Persona OHNE Live-Brain (kein :8041).
// Start: `./gradlew verifyOffline` (oder `./gradlew :web-inbound:verifyOffline`).
// Bewusst NICHT an `check`/`build` gehaengt — `./gradlew build` bleibt unberuehrt gruen.
tasks.register<Test>("verifyOffline") {
    description = "Offline-Replay-Harness: beweist deterministisches Routing/Fastpath/Sprache ohne Live-Brain."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("offline-replay")
    }
    testLogging { events("passed", "skipped", "failed") }
}
