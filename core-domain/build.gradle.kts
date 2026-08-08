repositories {
    mavenCentral()
}

plugins {
    kotlin("jvm") version "2.0.0"
}

java {
    toolchain { languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(21)) }
}

dependencies {
    // Reiner Domain-Kern: NUR Reactor + kotlin-stdlib. Kein Spring.
    api("io.projectreactor:reactor-core:3.6.17")
    // NUR Annotations (Wire-Vertrag von ChatEvent: @JsonTypeInfo/@JsonSubTypes) —
    // leichtgewichtig, KEIN jackson-databind als API (Serialisierung lebt im Adapter).
    api("com.fasterxml.jackson.core:jackson-annotations:2.17.2")
    // databind NUR intern (implementation, nicht api): der [ToolGrammarParser] parst
    // die vom Brain STRUKTURELL erzwungene Tool-JSON ({tool,args}) — das ist
    // Domänen-Logik (das Verstehen der Tat-Entscheidung des Brain), kein Wire-Mapping.
    // Parse-only, fail-soft; bleibt ein internes Detail des Kerns.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    // JUnit 5.12: Engine und Launcher muessen versions-gleich sein — ohne diese
    // Zeile injiziert Gradle 8.10 seinen aelteren Launcher (OutputDirectoryProvider-Crash).
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.junit.jupiter:junit-jupiter")
    // StepVerifier für die reaktiven Policy-Tests (RoutingPolicy/TurnPromptAssembler).
    // Test-only — ArchUnit schließt Tests aus, der Spring-freie Kern bleibt unberührt.
    testImplementation("io.projectreactor:reactor-test:3.6.17")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}
