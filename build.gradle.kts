plugins {
    kotlin("jvm") version "2.3.10"
}

group = "io.github.jwyoon1220"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    // Source: https://mvnrepository.com/artifact/it.unimi.dsi/fastutil
    implementation("it.unimi.dsi:fastutil:8.5.18")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}