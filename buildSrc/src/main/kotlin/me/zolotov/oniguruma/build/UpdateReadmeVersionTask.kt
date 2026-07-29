package me.zolotov.oniguruma.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@UntrackedTask(because = "Rewrites its own input in place")
abstract class UpdateReadmeVersionTask : DefaultTask() {

    @get:InputFile
    abstract val readmeFile: RegularFileProperty

    /** Group and artifact, for example `me.zolotov.oniguruma:oniguruma-jni`. */
    @get:Input
    abstract val coordinate: Property<String>

    @get:Input
    abstract val version: Property<String>

    @TaskAction
    fun update() {
        val file = readmeFile.get().asFile
        val coordinate = coordinate.get()
        // The version is whatever follows the coordinate inside a quoted Gradle snippet, so stop at
        // the closing quote: `implementation("me.zolotov.oniguruma:oniguruma-jni:$version")`.
        val versionPart = Regex("(${Regex.escape("$coordinate:")})[^\"]+")

        val original = file.readText()
        val occurrences = versionPart.findAll(original).count()
        require(occurrences > 0) {
            "Found no `$coordinate:<version>` coordinate in ${file.name}. The release keeps the " +
                    "README snippets copy-pasteable, so either the snippets moved or this task needs " +
                    "updating; releasing with stale install instructions is not the intended outcome."
        }

        val updated = versionPart.replace(original) { it.groupValues[1] + version.get() }
        if (updated == original) {
            logger.lifecycle("${file.name} already documents $coordinate:${version.get()}")
        } else {
            file.writeText(updated)
            logger.lifecycle("Documented $coordinate:${version.get()} in ${file.name} ($occurrences occurrences)")
        }
    }
}
