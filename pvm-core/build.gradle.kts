plugins { kotlin("jvm") }

dependencies {
    api(project(":pvm-api"))
    implementation("it.unimi.dsi:fastutil:8.5.18")
    testImplementation(kotlin("test"))
}
