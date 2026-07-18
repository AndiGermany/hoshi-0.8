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
    implementation(project(":core-domain"))

    // Multi-User-Gedächtnis: simpler, lokaler sqlite-Store (Datei unter ~/.hoshi/),
    // keyed by speakerId. Reiner JDBC-Treiber — kein Spring, kein WebClient: der
    // Adapter extrahiert deterministisch (KEIN zweiter Brain-Call/Turn) und persistiert.
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")

    // Episodic-Memory: JSON-Bau/-Parse für den Ollama-Embed-Call (embeddinggemma,
    // NUR Embeddings) via reinem JDK-HttpClient — kein Spring/WebClient im Adapter.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    implementation("org.slf4j:slf4j-api:2.0.13")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.13")

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}
