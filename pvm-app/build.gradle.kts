plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":pvm-core"))
    implementation(project(":pvm-drivers"))
    implementation(project(":pvm-addons"))
}

application {
    mainClass.set("io.github.jwyoon1220.pvm.app.MainKt")
}
