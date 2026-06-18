import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import me.zolotov.oniguruma.build.Arch
import me.zolotov.oniguruma.build.CompileOnigurumaTask
import me.zolotov.oniguruma.build.Os
import me.zolotov.oniguruma.build.Platform
import me.zolotov.oniguruma.build.buildPlatformNativeTarget
import me.zolotov.oniguruma.build.currentPlatform
import me.zolotov.oniguruma.build.normalizedName
import me.zolotov.oniguruma.build.onigurumaLibraryName
import org.gradle.api.attributes.Attribute
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.file.RelativePath
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.jvm.toolchain.JavaLanguageVersion
import java.net.URI

plugins {
    `java-library`
    alias(libs.plugins.jmh)
    id("com.vanniktech.maven.publish") version "0.34.0"
    id("org.hildan.github.changelog") version "2.2.0"
    id("ru.vyarus.github-info") version "2.0.0"
}

group = "me.zolotov.oniguruma"
description = """
    A Java Foreign Function & Memory (FFM) wrapper for the Oniguruma regular expression library.
    This library is primarily designed to support syntax highlighting in IntelliJ-based IDEs through the textmate-core library.
""".trimIndent()

github {
    user = "zolotov"
    license = "Apache"
}

changelog {
    githubUser = github.user
    futureVersionTag = project.version.toString()
    outputFile = file("CHANGELOG.md")
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
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
    jvmArgs("--enable-native-access=ALL-UNNAMED,me.zolotov.oniguruma.ffm")
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
    }
}

tasks.named("jmhRunBytecodeGenerator") {
    enabled = false
}

val onigurumaVersion = "6.9.10"
val onigurumaSourceUrl = "https://github.com/kkos/oniguruma/releases/download/v$onigurumaVersion/onig-$onigurumaVersion.tar.gz"
val onigurumaArchive = layout.buildDirectory.file("downloads/oniguruma-$onigurumaVersion.tar.gz")
val onigurumaSourceRoot = layout.buildDirectory.dir("native-src/oniguruma")

val downloadOnigurumaSource = tasks.register("downloadOnigurumaSource") {
    inputs.property("onigurumaVersion", onigurumaVersion)
    inputs.property("onigurumaSourceUrl", onigurumaSourceUrl)
    outputs.file(onigurumaArchive)

    val sourceUrl = onigurumaSourceUrl
    val archiveFile = onigurumaArchive
    doLast {
        val destination = archiveFile.get().asFile
        destination.parentFile.mkdirs()
        URI.create(sourceUrl)
            .toURL()
            .openStream()
            .use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
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

mavenPublishing {
    configure(
        JavaLibrary(
            javadocJar = JavadocJar.Javadoc(),
            sourcesJar = true,
        )
    )
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
