@file:Suppress("UnstableApiUsage")

import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar
import me.zolotov.oniguruma.build.*
import me.zolotov.oniguruma.build.Platform
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat

plugins {
    `java-library`
    alias(libs.plugins.jmh)
    alias(libs.plugins.publish)
    alias(libs.plugins.changelog)
}

group = "me.zolotov.oniguruma"
description = """
    A Java Foreign Function & Memory (FFM) wrapper for the Oniguruma regular expression library.
    This library is primarily designed to support syntax highlighting in IntelliJ-based IDEs through the textmate-core library.
""".trimIndent()

// See the matching block in oniguruma-jni/build.gradle.kts: entries are hand-written into the
// "Unreleased" section, and the release only promotes and extracts that section.
changelog {
    title = "Change Log"
    versionPrefix = "oniguruma-ffm-"
    repositoryUrl = "https://github.com/zolotov/oniguruma-bindings"
    // No group headings are seeded into a fresh Unreleased section: an author adds the ones
    // they need (Breaking, Added, Changed, Fixed, Performance) rather than deleting five
    // empty headings after every release.
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

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    jmhImplementation(project(":benchmarks"))
    jmhImplementation(libs.jmh.core)
    jmhAnnotationProcessor(libs.jmh.generator.annprocess)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    withSourcesJar()
    modularity.inferModulePath.set(true)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
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

val onigurumaVersion = "6.9.10"
val onigurumaSourceUrl = "https://github.com/kkos/oniguruma/releases/download/v$onigurumaVersion/onig-$onigurumaVersion.tar.gz"

// GitHub release assets are mutable, and these sources are compiled into the native libraries
// bundled in the published artifact. Pin the tarball by content, not just by version, so a
// re-uploaded asset fails the build instead of silently shipping.
// Cross-checked against Homebrew's oniguruma formula for the same URL.
val onigurumaSourceSha256 = "2a5cfc5ae259e4e97f86b68dfffc152cdaffe94e2060b770cb827238d769fc05"

val onigurumaArchive = layout.buildDirectory.file("downloads/oniguruma-$onigurumaVersion.tar.gz")
val onigurumaSourceRoot = layout.buildDirectory.dir("native-src/oniguruma")

val downloadOnigurumaSource = tasks.register("downloadOnigurumaSource") {
    inputs.property("onigurumaVersion", onigurumaVersion)
    inputs.property("onigurumaSourceUrl", onigurumaSourceUrl)
    inputs.property("onigurumaSourceSha256", onigurumaSourceSha256)
    outputs.file(onigurumaArchive)

    val sourceUrl = onigurumaSourceUrl
    val expectedSha256 = onigurumaSourceSha256
    val archiveFile = onigurumaArchive
    doLast {
        val destination = archiveFile.get().asFile
        destination.parentFile.mkdirs()

        // Download beside the destination and only move it into place once the digest matches:
        // writing straight to the declared output meant an interrupted transfer left a truncated
        // archive that Gradle then treated as an up-to-date output on every later build.
        val partial = File(destination.parentFile, "${destination.name}.part")
        partial.delete()
        val connection = URI.create(sourceUrl).toURL().openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 120_000
        }
        connection.getInputStream().use { input ->
            partial.outputStream().use { output -> input.copyTo(output) }
        }

        val digest = MessageDigest.getInstance("SHA-256")
        partial.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actualSha256 = HexFormat.of().formatHex(digest.digest())
        if (actualSha256 != expectedSha256) {
            partial.delete()
            throw GradleException(
                """
                Checksum mismatch for $sourceUrl
                  expected SHA-256: $expectedSha256
                  actual SHA-256:   $actualSha256
                Refusing to build native libraries from unverified sources.
                """.trimIndent()
            )
        }
        Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

val unpackOnigurumaSource = tasks.register<Sync>("unpackOnigurumaSource") {
    dependsOn(downloadOnigurumaSource)
    from(tarTree(resources.gzip(onigurumaArchive)))
    into(onigurumaSourceRoot)
    eachFile {
        val segments = relativePath.segments.drop(1)
        if (segments.isEmpty()) {
            exclude()
        } else {
            relativePath = RelativePath(true, *segments.toTypedArray())
        }
    }
    includeEmptyDirs = false
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
val nativeBuildType = "Release"

fun nativeLibraryFile(platform: Platform) =
    layout.buildDirectory.file(
        "target/${buildPlatformNativeTarget(platform)}/${nativeBuildType.lowercase()}/${onigurumaLibraryName(platform)}"
    )

fun isNativeCompilationEnabled(platform: Platform) = when (nativeBuildModeValue) {
    "skip" -> false
    "all" -> true
    else -> currentPlatform == platform
}

fun isNativeLibraryAvailable(platform: Platform) = when (nativeBuildModeValue) {
    "skip", "all" -> true
    else -> currentPlatform == platform
}

val compileNativeTaskByPlatform = nativePlatforms.associateWith { platform ->
    tasks.register<CompileOnigurumaTask>("compileNative-${buildPlatformNativeTarget(platform)}") {
        dependsOn(unpackOnigurumaSource)
        sourceDirectory.set(onigurumaSourceRoot)
        targetPlatform.set(platform)
        buildType.set(nativeBuildType)
        enabled = isNativeCompilationEnabled(platform)
    }
}

val generateNativeResources = tasks.register<Sync>("generateResourcesDir") {
    destinationDir = layout.buildDirectory.dir("native").get().asFile
    if (nativeBuildModeValue != "skip") {
        dependsOn(nativeResourcePlatforms.map { compileNativeTaskByPlatform.getValue(it) })
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

    // Resolved at configuration time: the doLast lambda must not capture script-level
    // properties, the configuration cache cannot serialize references to the script object.
    val libraryFiles = nativeResourcePlatforms.map { platform ->
        layout.buildDirectory.file("native/native/${platform.normalizedName}/${onigurumaLibraryName(platform)}")
    }
    val projectDirectory = layout.projectDirectory.asFile
    doLast {
        val missingLibraries = libraryFiles
            .map { it.get().asFile }
            .filterNot { it.isFile }

        if (missingLibraries.isNotEmpty()) {
            error(
                "Missing bundled Oniguruma native libraries:\n" +
                    missingLibraries.joinToString(separator = "\n") { " - ${it.relativeTo(projectDirectory)}" } +
                    "\nBuild the current-platform library with './gradlew test', or download/build all CI native artifacts before packaging with NATIVE_BUILD_MODE=skip or -PnativeBuildMode=skip."
            )
        }
    }
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

tasks.named<Jar>("sourcesJar") {
    exclude("**/native")
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

val packagingAttribute = Attribute.of("me.zolotov.oniguruma.packaging", String::class.java)

configurations {
    apiElements {
        attributes {
            attribute(packagingAttribute, "full")
        }
    }

    runtimeElements {
        attributes {
            attribute(packagingAttribute, "full")
        }
    }
}

val javaComponent = components.findByName("java") as AdhocComponentWithVariants
javaComponent.addVariantsFromConfiguration(configurations.consumable("slim") {
    extendsFrom(configurations.implementation.get(), configurations.runtimeOnly.get())
    attributes {
        // Deliberately the only attribute on this variant. Consumers that do not request the
        // packaging attribute must keep resolving runtimeElements/apiElements: they match all
        // standard requested attributes while this variant matches none, so Gradle's
        // "longest match" disambiguation picks them. Mirroring the standard attributes here
        // (usage, category, target JVM, ...) would make full and slim equally good matches for
        // default consumers and fail resolution with an ambiguity error.
        attribute(packagingAttribute, "slim")
    }
    outgoing {
        artifact(slimJar)
    }
}.get()) {}

compileNativeTaskByPlatform.forEach { (platform, task) ->
    val platformAttribute = Attribute.of("me.zolotov.oniguruma.platform", String::class.java)
    val configuration = configurations.consumable("bindings_${platform.normalizedName}") {
        attributes {
            attribute(platformAttribute, platform.normalizedName)
        }
        outgoing {
            artifact(task.map { it.libraryFile }) {
                classifier = platform.normalizedName
                builtBy(task)
            }
        }
    }.get()

    // Variants must be registered at configuration time: modifying the component after the
    // publication metadata has been populated fails in Gradle 9.
    if (isNativeLibraryAvailable(platform)) {
        javaComponent.addVariantsFromConfiguration(configuration) {}
    }
}

mavenPublishing  {
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
