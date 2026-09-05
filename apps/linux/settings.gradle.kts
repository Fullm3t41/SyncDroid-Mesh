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

rootProject.name = "SyncDeck"
include(":mesh-protocol", ":sync-core")
project(":mesh-protocol").projectDir = file("../../shared/mesh-protocol")
project(":sync-core").projectDir = file("../../shared/sync-core")
