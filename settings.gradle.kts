plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "parin-v86"

include("pvm-api")
include("pvm-core")
include("pvm-drivers")
include("pvm-addons")
include("pvm-app")