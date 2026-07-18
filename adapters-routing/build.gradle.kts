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

    // Embedding-Router: JSON-Bau/-Parse für den Ollama-Embed-Call (embeddinggemma,
    // NUR Embeddings — kein zweiter Brain-Call/Turn) via reinem JDK-HttpClient.
    // Kein Spring/WebClient im Adapter (analog :adapters-memory). Reactor (Mono/
    // Schedulers) kommt transitiv als `api` aus :core-domain.
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
