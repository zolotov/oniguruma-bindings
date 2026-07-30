import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar
import me.zolotov.oniguruma.build.OnigurumaSource
import me.zolotov.oniguruma.build.UpdateReadmeVersionTask
import me.zolotov.oniguruma.build.registerOnigurumaSource
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.tasks.CInteropProcess

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
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
    google()
}

val onigurumaSource = registerOnigurumaSource()

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

    // The native backend compiles Oniguruma itself into each target's cinterop klib: cinterop's
    // -Xcompile-source builds the pinned C sources with the clang and sysroot bundled in the
    // Kotlin/Native distribution, so no host C toolchain or per-target cross-compiler is needed,
    // and the published klibs are self-contained. Apple targets still require a macOS host, as
    // Kotlin/Native itself does.
    val nativeTargets = listOf(
        linuxX64(),
        linuxArm64(),
        macosX64(),
        macosArm64(),
        mingwX64(),
    )

    nativeTargets.forEach { target ->
        target.compilations.getByName("main").cinterops.create("oniguruma") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/oniguruma.def"))

            val sourceDir = onigurumaSource.sourceRoot.get().dir("src").asFile
            val configDir = project.file("src/nativeInterop/cinterop/config")
            includeDirs(sourceDir)

            // -Xcompile-source requires the indirect C-call mode (KT-79749).
            extraOpts("-Xccall-mode", "indirect")
            // The source compiler driver is clang++; oniguruma is C, not C++.
            extraOpts("-Xsource-compiler-option", "-xc")
            extraOpts("-Xsource-compiler-option", "-std=gnu99")
            extraOpts("-Xsource-compiler-option", "-I${configDir.absolutePath}")
            extraOpts("-Xsource-compiler-option", "-I${sourceDir.absolutePath}")
            extraOpts("-Xsource-compiler-option", "-DONIG_STATIC")
            extraOpts("-Xsource-compiler-option", "-O2")
            OnigurumaSource.LIBRARY_SOURCES.forEach { source ->
                extraOpts("-Xcompile-source", sourceDir.resolve(source).absolutePath)
            }
        }
    }

    // The Android backend delegates to oniguruma-jni: FFM is unavailable on Android, and the
    // JNI binding's native library can be packaged per-ABI in an app's jniLibs. No test
    // compilations are configured: the common suite needs the Rust JNI library for the
    // executing platform, which neither host nor device test infrastructure provides here.
    androidLibrary {
        namespace = "me.zolotov.oniguruma"
        compileSdk = 36
        // API 26 gives java.nio.file, which oniguruma-jni's public API uses.
        minSdk = 26
        compilations.configureEach {
            compilerOptions.configure {
                // Matches the Java 17 bytecode oniguruma-jni publishes.
                jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
            }
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

        androidMain.dependencies {
            implementation(libs.oniguruma.jni)
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

tasks.withType<CInteropProcess>().configureEach {
    dependsOn(onigurumaSource.unpackTask)
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
