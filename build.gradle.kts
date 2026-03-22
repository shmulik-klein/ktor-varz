plugins {
    kotlin("jvm") version "2.0.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.0"
    application
}

group = "klein.shmulik"
version = "0.1.0"

repositories {
    mavenCentral()
}

application {
    mainClass.set("klein.shmulik.AppKt")
}

dependencies {
    implementation("io.ktor:ktor-server-core:3.0.0")
    implementation("io.ktor:ktor-server-netty:3.0.0")
    implementation("io.ktor:ktor-server-content-negotiation:3.0.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("io.ktor:ktor-server-tests:3.0.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.0.0")
}