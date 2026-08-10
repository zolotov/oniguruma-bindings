import me.zolotov.oniguruma.build.BenchmarkReportTask
import me.zolotov.oniguruma.build.JMH_RESULTS_ATTRIBUTE
import me.zolotov.oniguruma.build.JMH_RESULTS_ELEMENTS
import me.zolotov.oniguruma.build.JMH_RESULTS_JSON

plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

// Under Isolated Projects this project cannot read the benchmarked modules' layouts, so each one
// exposes its raw JMH results.json as an artifact of a `jmhResultsElements` variant and this
// project resolves it like any other dependency. The `builtBy` on the producing side carries the
// dependency on the `jmh` task, so no explicit `dependsOn` of a task path is needed here.
fun jmhResultsOf(producer: String): Provider<RegularFile> {
    val declaration = configurations.dependencyScope("${producer}JmhResults")
    dependencies.add(declaration.name, dependencyFactory.createProjectDependency(":$producer"))
    val resolvable = configurations.resolvable("${producer}JmhResultsPath") {
        extendsFrom(declaration.get())
        attributes {
            attribute(JMH_RESULTS_ATTRIBUTE, JMH_RESULTS_JSON)
        }
    }
    return layout.file(resolvable.flatMap { it.elements }.map { elements ->
        val files = elements.map { it.asFile }
        require(files.size == 1) {
            "Expected exactly one $JMH_RESULTS_ELEMENTS artifact from :$producer, got $files"
        }
        files.single()
    })
}

tasks.register<BenchmarkReportTask>("ciBenchmark") {
    group = "benchmark"
    description = "Run the JNI and FFM JMH suites, normalize the outputs, emit a GitHub summary, and build a Pages bundle."

    jniResultsFile.set(jmhResultsOf("oniguruma-jni"))
    ffmResultsFile.set(jmhResultsOf("oniguruma-ffm"))
    siteTemplateDirectory.set(layout.projectDirectory.dir("site"))
    outputDirectory.set(layout.buildDirectory.dir("ci"))
    siteUrl.convention(providers.gradleProperty("benchmarkSiteUrl"))
    historyLimit.convention(providers.gradleProperty("benchmarkHistoryLimit").map(String::toInt).orElse(90))
    significanceThreshold.convention(
        providers.gradleProperty("benchmarkSignificanceThreshold").map(String::toDouble).orElse(0.03)
    )

    // A missing history file is legitimate -- CI seeds it from the published site and deletes it
    // on a 404 for a brand new site -- but it must not be silent. Without a baseline every
    // measurement is reported as "new", which on the dashboard and in the PR comment is
    // indistinguishable from "nothing regressed". A path that exists but is not a regular file
    // is a typo, not a fresh start, so that fails.
    fun resolveOptionalHistory(propertyName: String, target: RegularFileProperty) {
        val path = providers.gradleProperty(propertyName).orNull ?: return
        // settingsDirectory, not rootProject.file: CI passes these paths relative to the repository
        // root, and reaching for the root project's model is an Isolated Projects violation.
        val candidate = layout.settingsDirectory.file(path).asFile
        when {
            candidate.isFile -> target.set(candidate)
            candidate.exists() -> error("-P$propertyName=$path exists but is not a regular file.")
            else -> logger.warn(
                "-P{}={} does not exist: running without a comparison baseline, so every " +
                    "measurement will be reported as new.",
                propertyName,
                path,
            )
        }
    }

    resolveOptionalHistory("benchmarkHistoryFile", historyFile)
    prNumber.convention(providers.gradleProperty("benchmarkPrNumber"))
    resolveOptionalHistory("benchmarkPrHistoryFile", prHistoryFile)
}
