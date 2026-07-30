import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar
import me.zolotov.oniguruma.build.UpdateReadmeVersionTask
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.publish)
    alias(libs.plugins.changelog)
}

group = "me.zolotov.oniguruma"
description = """
    Kotlin Multiplatform bindings for the Oniguruma regular expression library, exposing one common API
    over the platform-specific oniguruma-bindings backends.
    This library is primarily designed to support syntax highlighting in IntelliJ-based IDEs through the textmate-core library.
""".trimIndent()

// See the matching block in oniguruma-ffm/build.gradle.kts: entries are hand-written into the
// "Unreleased" section, and the release only promotes and extracts that section.
changelog {
    title = "Change Log"
    versionPrefix = "koniguruma-"
    repositoryUrl = "https://github.com/zolotov/oniguruma-bindings"
    groups = emptyList()
    patchEmpty = false
    outputFile = layout.buildDirectory.file("reports/changelog/latest-release-body.md")
}

tasks.register<UpdateReadmeVersionTask>("updateReadmeVersion") {
    group = "release"
    description = "Points the README dependency snippets at the version being released."
    readmeFile = layout.projectDirectory.file("README.md")
    coordinate = "${project.group}:${project.name}"
    version = project.version.toString()
}

repositories {
    mavenCentral()
}

kotlin {
    explicitApi()

    jvm {
        // The JVM backend delegates to oniguruma-ffm, whose classes are compiled for Java 25;
        // the toolchain has to be able to read them, and there is no reason to target anything older.
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        // Tests run under Node.js; the produced klib itself is environment-agnostic and browser
        // consumers load onig.wasm through the createOniguruma(wasmBinary) overload.
        nodejs()
        compilerOptions {
            freeCompilerArgs.add("-opt-in=kotlin.js.ExperimentalWasmJsInterop")
        }
    }

    jvmToolchain(25)

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        wasmJsMain.dependencies {
            implementation(npm("vscode-oniguruma", "2.0.1"))
        }

        jvmMain.dependencies {
            // The published artifact rather than project(":oniguruma-ffm"): the modules release
            // independently, so the wrapper pins the backend version it was built against, and
            // building koniguruma does not require the native toolchain that building
            // oniguruma-ffm from source does.
            implementation(libs.oniguruma.ffm)
        }
    }
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
    }
}

mavenPublishing {
    configure(KotlinMultiplatform(JavadocJar.Empty(), SourcesJar.Sources()))
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()
    pom {
        name.set(project.name)
        description.set(project.description)
        url.set("https://github.com/zolotov/oniguruma-bindings")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                id.set("zolotov")
                name.set("Alexander Zolotov")
                email.set("goldifit@gmail.com")
                url.set("https://github.com/zolotov/")
            }
        }

        scm {
            url.set("https://github.com/zolotov/oniguruma-bindings")
            connection.set("scm:git:git://github.com/zolotov/oniguruma-bindings.git")
            developerConnection.set("scm:git:ssh://github.com/zolotov/oniguruma-bindings.git")
        }
    }
}
