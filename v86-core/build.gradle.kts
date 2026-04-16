plugins { kotlin("jvm") }

dependencies {
    api(project(":v86-api"))
    implementation("it.unimi.dsi:fastutil:8.5.18")
    testImplementation(kotlin("test"))
}
