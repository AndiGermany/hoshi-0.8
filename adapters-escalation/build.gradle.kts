repositories {
    mavenCentral()
}

plugins {
    kotlin("jvm") version "2.0.0"
    // application: erlaubt `:adapters-escalation:run` für den isolierten Live-
    // OpenAI-Nachschlage-Smoke (OpenAiEscalationLiveSmoke) — analog :adapters-tts.
    // Der Live-Smoke läuft zentral beim Orchestrator, NIE in Tests.
    application
}

java {
    toolchain { languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(21)) }
}

dependencies {
    implementation(project(":core-domain"))
    // capability-kernel: der EgressPort-Riegel (guard/sanitize/reconstruct) wandert
    // BY CONSTRUCTION in den Adapter-Konstruktor. Zweiter Kernel-Konsument nach
    // :web-inbound — bewusst: der Riegel gehört an die Egress-Naht, nicht in den Kern.
    implementation(project(":capability-kernel"))

    // spring-webflux NUR als Library für den reaktiven WebClient (wie :adapters-tts) —
    // KEIN spring-boot-starter. Der Eskalations-Adapter lebt außerhalb des Kerns.
    implementation("org.springframework:spring-webflux:6.1.12")
    implementation("org.springframework:spring-context:6.1.12")
    // Reactor-Netty: HTTP-Connector hinter WebClient.
    implementation("io.projectreactor.netty:reactor-netty-http:1.1.22")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    implementation("org.slf4j:slf4j-api:2.0.13")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.13")

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.projectreactor:reactor-test:3.6.9")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}

application {
    // Live-OpenAI-Nachschlage-Beweis (isoliert, ohne Brain) — nur mit echtem Key,
    // gestartet vom Orchestrator/Andi. Tests mocken IMMER.
    mainClass.set("de.hoshi.adapters.escalation.OpenAiEscalationLiveSmokeKt")
}
