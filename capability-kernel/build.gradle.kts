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
    // Trust-Kernel: hängt nur am reinen Domain-Kern + kotlin-stdlib. KEIN Spring
    // (die WebFilter-Verdrahtung kommt später in :web-inbound). core-domain ist
    // auf dem Test-Classpath → ArchUnit kann de.hoshi.core.. mit-scannen.
    implementation(project(":core-domain"))

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    // JUnit 5.12: Engine und Launcher muessen versions-gleich sein — ohne diese
    // Zeile injiziert Gradle 8.10 seinen aelteren Launcher (OutputDirectoryProvider-Crash).
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}
