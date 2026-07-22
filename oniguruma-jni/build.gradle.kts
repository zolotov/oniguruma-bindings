import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.JavaLibrary
import me.zolotov.oniguruma.build.*
import me.zolotov.oniguruma.build.Platform
import me.zolotov.oniguruma.build.normalizedName

plugins {
    `java-library`
    alias(libs.plugins.jmh)
    alias(libs.plugins.publish)
    alias(libs.plugins.changelog)
    alias(libs.plugins.github)
}

group = "me.zolotov.oniguruma"
description = """
    A JNI wrapper for the Oniguruma regular expression library, with Rust implementation using the onig crate.
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
    jmhImplementation(project(":benchmarks"))
    jmhImplementation(libs.jmh.core)
    jmhAnnotationProcessor(libs.jmh.generator.annprocess)
}

tasks.test {
    useJUnitPlatform()
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    })
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

jmh {
    jvm = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    }.get().executablePath.asFile.absolutePath
    jvmArgsAppend = listOf("--enable-native-access=ALL-UNNAMED")
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
fun nativeLibraryFile(platform: Platform) = layout.buildDirectory.file(
    "target/${buildPlatformRustTarget(platform)}/$nativeRustProfile/${when (platform.os) {
        Os.LINUX -> "liboniguruma_jni.so"
        Os.MACOS -> "liboniguruma_jni.dylib"
        Os.WINDOWS -> "oniguruma_jni.dll"
    }}"
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

    val libraryFiles = nativeResourcePlatforms.map(::nativeLibraryFile)
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
    configure(JavaLibrary(
        javadocJar = JavadocJar.Javadoc(),
        sourcesJar = true
    ))
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
