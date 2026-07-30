pluginManagement {
    repositories {
        gradlePluginPortal()
        // The Android Gradle plugin and its dependencies are only published to Google's repository.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "oniguruma-bindings"

include("oniguruma-jni")
include("oniguruma-ffm")
include("koniguruma")
include("benchmarks")
