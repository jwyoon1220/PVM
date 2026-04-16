plugins { kotlin("jvm") }

dependencies {
    api(project(":pvm-api"))
    implementation("it.unimi.dsi:fastutil:8.5.18")
    implementation("org.ow2.asm:asm:9.7.1")
    testImplementation(kotlin("test"))
}
