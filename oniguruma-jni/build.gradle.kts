@file:Suppress("UnstableApiUsage")

import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar
import me.zolotov.oniguruma.build.*
import me.zolotov.oniguruma.build.Platform
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.changelog.ChangelogSectionUrlBuilder

plugins {
    `java-library`
    alias(libs.plugins.jmh)
    alias(libs.plugins.publish)
    alias(libs.plugins.changelog)
}

group = "me.zolotov.oniguruma"
description = """
    A JNI wrapper for the Oniguruma regular expression library, with Rust implementation using the onig crate.
    This library is primarily designed to support syntax highlighting in IntelliJ-based IDEs through the textmate-core library.
""".trimIndent()

// Entries are written by hand into the "Unreleased" section, because a commit subject describes
// what the author did to the source tree rather than what a consumer of the library gets.
// `patchChangelog` only promotes that hand-edited section, and `getChangelog` hands the very
// same text to the GitHub release, so what ships is exactly what was reviewed.
changelog {
    title = "Change Log"
    versionPrefix = "oniguruma-jni-"
    repositoryUrl = "https://github.com/zolotov/oniguruma-bindings"
    // No group headings are seeded into a fresh Unreleased section: an author adds the ones
    // they need (Breaking, Added, Changed, Fixed, Performance) rather than deleting five
    // empty headings after every release.
    groups = emptyList()
    // Never turn an empty Unreleased section into a release section: patchChangelog fails instead,
    // which is what stops a release from shipping without notes.
    patchEmpty = false
    outputFile = layout.buildDirectory.file("reports/changelog/latest-release-body.md")

    // Releases up to 2.0.0 shipped before the repository was split into oniguruma-jni and
    // oniguruma-ffm, and are tagged without the module prefix. Without this, versionPrefix would
    // point every historical comparison link at an `oniguruma-jni-1.0.3` tag that does not exist.
    sectionUrlBuilder = object : ChangelogSectionUrlBuilder {
        private fun tag(version: String) =
            if (version.startsWith("1.") || version == "2.0.0") version else "oniguruma-jni-$version"

        override fun build(
            repositoryUrl: String,
            currentVersion: String?,
            previousVersion: String?,
            isUnreleased: Boolean,
        ): String = when {
            isUnreleased -> when (previousVersion) {
                null -> "$repositoryUrl/commits"
                else -> "$repositoryUrl/compare/${tag(previousVersion)}...HEAD"
            }
            previousVersion == null -> "$repositoryUrl/commits/${tag(currentVersion!!)}"
            else -> "$repositoryUrl/compare/${tag(previousVersion)}...${tag(currentVersion!!)}"
        }
    }
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

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    jmhImplementation(project(":benchmarks"))
    jmhImplementation(libs.jmh.core)
    jmhAnnotationProcessor(libs.jmh.generator.annprocess)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    })
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
    }
}

tasks.named("jmhRunBytecodeGenerator") {
    enabled = false
}

jmh {
    // Keep this lazy: calling .get() here resolved (and downloaded) the toolchain during
    // configuration of every build, so tasks that never run JMH -- including the
    // compileNative-* jobs, which run on a different JDK -- had to provision this one first.
    jvm.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        }.map { it.executablePath.asFile.absolutePath }
    )
    jvmArgsAppend = listOf("--enable-native-access=ALL-UNNAMED")

    // CI runs the default (full) profile so the published trend data comes from steady,
    // fully warmed measurements; "quick" is an opt-in shortcut for local smoke checks.
    val quick = providers.gradleProperty("benchmarkProfile").orNull == "quick"
    // Two forks in the full profile: a single JVM bakes one compilation outcome into every
    // score, and JVM-to-JVM variance is exactly where small JNI-vs-FFM deltas drown.
    fork = if (quick) 1 else 2
    warmupIterations = if (quick) 2 else 5
    warmup = if (quick) "500ms" else "1s"
    iterations = if (quick) 3 else 5
    timeOnIteration = if (quick) "500ms" else "1s"
    resultFormat = "JSON"
    resultsFile = layout.buildDirectory.file("results/jmh/results.json")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    withSourcesJar()
    modularity.inferModulePath.set(true)
}

val currentPlatform = currentPlatform()
val nativeBuildMode = providers.gradleProperty("nativeBuildMode")
    .orElse(providers.environmentVariable("NATIVE_BUILD_MODE"))
val nativeBuildModeValue = nativeBuildMode.orNull ?: "current"
require(nativeBuildModeValue in setOf("current", "all", "skip")) {
    "Unsupported native build mode '$nativeBuildModeValue'. Use 'current', 'all', or 'skip'."
}
val nativePlatforms = listOf(
    Platform(Os.MACOS, Arch.aarch64),
    Platform(Os.MACOS, Arch.x86_64),
    Platform(Os.WINDOWS, Arch.aarch64),
    Platform(Os.WINDOWS, Arch.x86_64),
    Platform(Os.LINUX, Arch.aarch64),
    Platform(Os.LINUX, Arch.x86_64),
)
val nativeResourcePlatforms = when (nativeBuildModeValue) {
    "all", "skip" -> nativePlatforms
    else -> listOf(currentPlatform)
}
val nativeRustProfile = "release"
fun nativeLibraryName(platform: Platform) = when (platform.os) {
    Os.LINUX -> "liboniguruma_jni.so"
    Os.MACOS -> "liboniguruma_jni.dylib"
    Os.WINDOWS -> "oniguruma_jni.dll"
}

/** Where cargo writes the library. */
fun nativeLibraryFile(platform: Platform) = layout.buildDirectory.file(
    "target/${buildPlatformRustTarget(platform)}/$nativeRustProfile/${nativeLibraryName(platform)}"
)

/** Where [generateNativeResources] stages it for packaging, i.e. its path inside the jar. */
fun packagedNativeLibraryFile(platform: Platform) = layout.buildDirectory.file(
    "native/native/${platform.normalizedName}/${nativeLibraryName(platform)}"
)

fun isNativeBuildEnabled(platform: Platform): Boolean = when (nativeBuildModeValue) {
    "skip" -> false
    "all" -> true
    else -> currentPlatform == platform
}

// Whether the native library for the platform is available in this build:
// either compiled by the corresponding task, or provided in prebuilt form
// when the compilation is skipped (the CI release flow downloads prebuilt
// binaries for all platforms into the build directory).
fun isNativeLibraryAvailable(platform: Platform): Boolean = when (nativeBuildModeValue) {
    "skip", "all" -> true
    else -> currentPlatform == platform
}

val compileRustBindingsTaskByPlatform = nativePlatforms.associateWith { platform ->
    tasks.register<CompileRustTask>("compileNative-${buildPlatformRustTarget(platform)}") {
        crateName = "oniguruma-jni"
        rustProfile = nativeRustProfile
        rustTarget = platform
        nativeDirectory = layout.projectDirectory.dir("native")
        enabled = isNativeBuildEnabled(platform)
    }
}

val generateNativeResources = tasks.register<Sync>("generateResourcesDir") {
    destinationDir = layout.buildDirectory.dir("native").get().asFile
    if (nativeBuildModeValue != "skip") {
        dependsOn(nativeResourcePlatforms.map { compileRustBindingsTaskByPlatform.getValue(it) })
    }

    nativeResourcePlatforms.forEach { platform ->
        from(nativeLibraryFile(platform)) {
            into("native/${platform.normalizedName}")
        }
    }
}

tasks.processResources {
    dependsOn(generateNativeResources)
}

sourceSets {
    main {
        resources.srcDirs(generateNativeResources.map { it.destinationDir })
    }
}

val verifyNativeResources = tasks.register("verifyNativeResources") {
    group = "verification"
    description = "Verifies that bundled native resources required by the active native build mode are present."

    dependsOn(generateNativeResources)
    inputs.property("nativeBuildMode", nativeBuildModeValue)

    // Check the staged/packaged location rather than cargo's output directory: Sync silently
    // ignores a `from(...)` whose source is missing, so verifying the compile output would pass
    // even when the library never made it into the resources that get packaged.
    val libraryFiles = nativeResourcePlatforms.map(::packagedNativeLibraryFile)
    val projectDirectory = layout.projectDirectory.asFile
    doLast {
        val missingLibraries = libraryFiles
            .map { it.get().asFile }
            .filterNot { it.isFile }

        if (missingLibraries.isNotEmpty()) {
            error(
                "Missing bundled JNI native libraries:\n" +
                    missingLibraries.joinToString(separator = "\n") { " - ${it.relativeTo(projectDirectory)}" } +
                    "\nBuild the current-platform library with './gradlew :oniguruma-jni:test', or download/build all CI native artifacts before packaging with NATIVE_BUILD_MODE=skip or -PnativeBuildMode=skip."
            )
        }
    }
}

tasks.named<Jar>("sourcesJar") {
    exclude("**/native")
}

tasks.named<Jar>("jar") {
    dependsOn(verifyNativeResources)
}

tasks.withType<Test>().configureEach {
    dependsOn(verifyNativeResources)
}

tasks.matching { it.name == "jmh" }.configureEach {
    dependsOn(verifyNativeResources)
}

val slimJar = tasks.register<Jar>("slimJar") {
    group = "build"
    description = "Assembles a jar archive without native libraries"

    archiveClassifier.set("slim")
    from(sourceSets.main.map { it.output.classesDirs })

    from(sourceSets.main.map { it.output.resourcesDir }) {
        exclude("**/native")
    }

    manifest {
        from(tasks.jar.get().manifest)
    }
    dependsOn(tasks.processResources)
}

val PACKAGING_ATTRIBUTE = Attribute.of("me.zolotov.oniguruma.packaging", String::class.java)

configurations {
    apiElements {
        attributes {
            attribute(PACKAGING_ATTRIBUTE, "full")
        }
    }

    runtimeElements {
        attributes {
            attribute(PACKAGING_ATTRIBUTE, "full")
        }
    }
}

val javaComponent = components.findByName("java") as AdhocComponentWithVariants
javaComponent.addVariantsFromConfiguration(configurations.consumable("slim") {
    // Carry the same runtime dependencies as the regular runtime variant.
    extendsFrom(configurations.implementation.get(), configurations.runtimeOnly.get())
    attributes {
        attribute(PACKAGING_ATTRIBUTE, "slim")
    }
    outgoing { artifact(slimJar) }
}.get()) {}

compileRustBindingsTaskByPlatform.forEach { (platform, task) ->
    val conf = configurations.consumable("bindings_${platform.normalizedName}") {
        attributes {
            attribute(Attribute.of("me.zolotov.oniguruma.platform", String::class.java), platform.normalizedName)
        }
        outgoing {
            artifact(task.map { it.libraryFile }) {
                classifier = platform.normalizedName
                builtBy(task)
            }
        }
    }.get()
    // The variant must be registered at configuration time: modifying the component after
    // the publication has been populated fails in Gradle 9. Only platforms whose binaries
    // are available in this build are published.
    if (isNativeLibraryAvailable(platform)) {
        javaComponent.addVariantsFromConfiguration(conf) { }
    }
}

mavenPublishing {
    configure(JavaLibrary(JavadocJar.Javadoc(), SourcesJar.Sources()))
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
