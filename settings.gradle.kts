plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "oniguruma-bindings"

enableFeaturePreview("NO_IMPLICIT_LOOKUP_IN_PARENT_PROJECTS")

include("oniguruma-jni")
include("oniguruma-ffm")
include("benchmarks")
