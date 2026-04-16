plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "parin-v86"

include("v86-api")
include("v86-core")
include("v86-drivers")
include("v86-addons")
include("v86-app")