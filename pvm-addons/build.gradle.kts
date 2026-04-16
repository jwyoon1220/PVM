plugins { kotlin("jvm") }

dependencies {
    api(project(":pvm-api"))
    implementation(project(":pvm-core"))
    testImplementation(kotlin("test"))
}
