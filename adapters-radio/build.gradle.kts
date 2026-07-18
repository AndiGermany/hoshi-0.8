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

    // Jackson NUR fürs Parsen der radio-browser-Antwort + den HA-Service-Body.
    // Beide Adapter sprechen synchrones JDK-HttpClient (wie :adapters-ha/HaToolPort:
    // der RadioPort wird synchron off der Event-Loop gerufen, kein WebClient nötig).
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
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
