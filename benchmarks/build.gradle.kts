import me.zolotov.oniguruma.build.BenchmarkReportTask

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

tasks.register<BenchmarkReportTask>("ciBenchmark") {
    group = "benchmark"
    description = "Run the JNI and FFM JMH suites, normalize the outputs, emit a GitHub summary, and build a Pages bundle."
    dependsOn(":oniguruma-jni:jmh", ":oniguruma-ffm:jmh")

    jniResultsFile.set(project(":oniguruma-jni").layout.buildDirectory.file("results/jmh/results.json"))
    ffmResultsFile.set(project(":oniguruma-ffm").layout.buildDirectory.file("results/jmh/results.json"))
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
        val candidate = rootProject.file(path)
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
