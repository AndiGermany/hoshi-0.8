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

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}
