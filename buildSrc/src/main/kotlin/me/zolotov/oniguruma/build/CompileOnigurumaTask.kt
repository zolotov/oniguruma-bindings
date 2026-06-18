package me.zolotov.oniguruma.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories

abstract class CompileOnigurumaTask @Inject constructor(
    objectFactory: ObjectFactory,
    providerFactory: ProviderFactory,
    projectLayout: ProjectLayout,
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:InputDirectory
    val sourceDirectory = objectFactory.directoryProperty()

    @get:Nested
    val targetPlatform = objectFactory.property(Platform::class.java)

    @get:Input
    val buildType = objectFactory.property(String::class.java).convention("Release")

    @get:Internal
    val cmakeBuildDirectory = projectLayout.buildDirectory.dir(
        providerFactory.provider {
            "cmake/${buildPlatformNativeTarget(targetPlatform.get())}/${buildType.get().lowercase()}"
        }
    )

    @get:OutputFile
    val libraryFile = providerFactory.provider {
        val platform = targetPlatform.get()
        val directory = projectLayout.buildDirectory
            .dir("target/${buildPlatformNativeTarget(platform)}/${buildType.get().lowercase()}")
            .get()
            .asFile

        directory.resolve(onigurumaLibraryName(platform))
    }

    @TaskAction
    fun compile() {
        compileWithCmake(targetPlatform.get())
    }

    private fun compileWithCmake(platform: Platform) {
        val cmake = execOperations.findCommand("cmake")
            ?: error("Unable to find 'cmake'. Install CMake to build Oniguruma native libraries.")

        val buildDirectory = cmakeBuildDirectory.get().asFile.toPath()
        buildDirectory.createDirectories()

        execOperations.exec {
            commandLine(
                listOf(
                    cmake.toString(),
                    "-S",
                    sourceDirectory.get().asFile.absolutePath,
                    "-B",
                    buildDirectory.toString(),
                ) + cmakePlatformArguments(platform) + listOf(
                    "-DBUILD_SHARED_LIBS=ON",
                    "-DBUILD_TESTING=OFF",
                    "-DCMAKE_BUILD_TYPE=${buildType.get()}",
                )
            )
        }

        execOperations.exec {
            commandLine(
                cmake.toString(),
                "--build",
                buildDirectory.toString(),
                "--config",
                buildType.get(),
                "--parallel",
                Runtime.getRuntime().availableProcessors().toString(),
            )
        }

        val output = libraryFile.get().toPath()
        output.parent.createDirectories()
        findBuiltLibrary(buildDirectory, output.fileName.toString()).copyTo(output, overwrite = true)
    }

    private fun cmakePlatformArguments(platform: Platform): List<String> = when (platform.os) {
        Os.LINUX -> when (platform.arch) {
            Arch.x86_64 -> emptyList()
            Arch.aarch64 -> listOf(
                "-DCMAKE_SYSTEM_NAME=Linux",
                "-DCMAKE_SYSTEM_PROCESSOR=aarch64",
                "-DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc",
            )
        }

        Os.MACOS -> listOf(
            "-DCMAKE_OSX_ARCHITECTURES=${if (platform.arch == Arch.aarch64) "arm64" else "x86_64"}",
        )

        Os.WINDOWS -> listOf(
            "-A",
            if (platform.arch == Arch.aarch64) "ARM64" else "x64",
        )
    }

    private fun findBuiltLibrary(buildDirectory: Path, libraryName: String): Path {
        Files.walk(buildDirectory).use { paths ->
            return paths
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().equals(libraryName, ignoreCase = currentOs() == Os.WINDOWS) }
                .findFirst()
                .orElseThrow {
                    IllegalStateException("Unable to find built Oniguruma library '$libraryName' in $buildDirectory")
                }
        }
    }
}
