repositories {
    mavenCentral()
}

plugins {
    kotlin("jvm") version "2.0.0"
    application
}

java {
    toolchain { languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(21)) }
}

dependencies {
    implementation(project(":core-domain"))

    // spring-webflux NUR als Library für den reaktiven WebClient (wie :adapters-brain) —
    // KEIN spring-boot-starter. Der Probe-Adapter lebt außerhalb des Kerns.
    implementation("org.springframework:spring-webflux:6.1.12")
    implementation("org.springframework:spring-context:6.1.12")
    implementation("io.projectreactor.netty:reactor-netty-http:1.1.22")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    implementation("org.slf4j:slf4j-api:2.0.13")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.13")

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

application {
    // `hoshi services` → live, read-only inspect gegen die echte Infra.
    mainClass.set("de.hoshi.adapters.supervision.ServicesLiveKt")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}
