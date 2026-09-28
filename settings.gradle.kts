pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "max-kmp-core"

include(":core")
include(":shared")
include(":android")
include(":ios")
include(":desktop")
