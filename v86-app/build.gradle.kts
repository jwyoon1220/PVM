plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":v86-core"))
    implementation(project(":v86-drivers"))
    implementation(project(":v86-addons"))
}

application {
    mainClass.set("io.github.jwyoon1220.pvm.app.MainKt")
}
