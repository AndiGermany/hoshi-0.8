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

    // Sprecher-Embedding-Sidecar (CAM++ :9002). Der Adapter spricht ihn ueber den JDK-eigenen
    // java.net.http-Client SYNCHRON an (wie HttpBrainHealthSource in SidecarHealthService) —
    // der Port-Vertrag ist synchron (FloatArray?), das Blocking kapselt der Aufrufer
    // (Enroll-Controller per boundedElastic). Bewusst KEIN spring-webflux noetig.
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
