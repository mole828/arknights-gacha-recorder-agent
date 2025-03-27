plugins {
    application
    kotlin("jvm") version "2.1.10"
    id("io.ktor.plugin") version "3.0.3"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.10"
}

group = "org.example"
version = "0.0.1"

application {
    mainClass = "com.example.MainKt"
}

repositories {
    mavenCentral()
}


dependencies {
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-cio")

    implementation("io.ktor:ktor-serialization-kotlinx-json")

    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
}