package me.zolotov.oniguruma.build

import kotlinx.serialization.json.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Aggregates the JMH reports of the JNI and FFM bindings into a single normalized
 * JSON schema, compares the run against the last published baseline, maintains a
 * bounded run history, and assembles a static GitHub Pages bundle (template assets
 * plus generated JSON payloads and `data/data.js`).
 *
 * Outputs under [outputDirectory]:
 * - `raw/` — untouched copies of the input reports
 * - `report/` — `current.json`, `comparison.json`, `history.json`, `summary.md`
 * - `site/` — the deployable Pages bundle
 *
 * Deliberately untracked. The task stamps each report with the wall clock and with the
 * `GITHUB_` and `RUNNER_` environment of the run, and appends to the GitHub step summary; none of
 * that is expressible as a Gradle input. Left tracked, a re-run whose JMH scores happened to be
 * byte-identical was reported UP-TO-DATE, which republished the previous run's commit sha and
 * run id and skipped the step summary entirely.
 */
@UntrackedTask(because = "Stamps each run with wall-clock time and CI environment, and appends to \$GITHUB_STEP_SUMMARY")
abstract class BenchmarkReportTask : DefaultTask() {
    @get:InputFile
    abstract val jniResultsFile: RegularFileProperty

    @get:InputFile
    abstract val ffmResultsFile: RegularFileProperty

    @get:InputDirectory
    abstract val siteTemplateDirectory: DirectoryProperty

    @get:Optional
    @get:InputFile
    abstract val historyFile: RegularFileProperty

    /**
     * Pull request number when this run benchmarks a PR. Enables the per-PR site payload
     * (`site/data/prs/<n>/`), the accumulated `report/pr-history.json`, and the
     * "runs in this PR" summary section.
     */
    @get:Optional
    @get:Input
    abstract val prNumber: Property<String>

    /**
     * Previously accumulated run history for this PR (fetched from the published site).
     * The current run is appended to it; [historyFile] stays the comparison baseline.
     */
    @get:Optional
    @get:InputFile
    abstract val prHistoryFile: RegularFileProperty

    @get:Optional
    @get:Input
    abstract val siteUrl: Property<String>

    @get:Input
    abstract val historyLimit: Property<Int>

    /**
     * Fallback significance threshold for measurements that lack confidence intervals.
     * When both the current and baseline measurements carry a JMH confidence interval,
     * significance is decided by interval overlap instead.
     */
    @get:Input
    abstract val significanceThreshold: Property<Double>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    init {
        historyLimit.convention(90)
        significanceThreshold.convention(0.03)
    }

    @TaskAction
    fun generate() {
        val outputDir = outputDirectory.asFile.get().apply {
            deleteRecursively()
            mkdirs()
        }
        val rawDir = outputDir.resolve("raw").apply(File::mkdirs)
        val reportsDir = outputDir.resolve("report").apply(File::mkdirs)
        val siteDir = outputDir.resolve("site")

        val jniInput = jniResultsFile.asFile.get()
        val ffmInput = ffmResultsFile.asFile.get()

        val jniOutput = rawDir.resolve("jni.json")
        val ffmOutput = rawDir.resolve("ffm.json")
        jniInput.copyTo(jniOutput, overwrite = true)
        ffmInput.copyTo(ffmOutput, overwrite = true)

        val currentRun = buildCurrentRun(jniOutput, ffmOutput)
        val existingHistory = readHistory(historyFile)
        val baselineRun = existingHistory.runs.lastOrNull()
        val comparison = compareRuns(currentRun, baselineRun)
        val updatedHistory = mergeHistory(existingHistory, currentRun)

        val prNumberValue = prNumber.orNull?.trim()?.ifEmpty { null }?.also { value ->
            require(value.all(Char::isDigit)) { "prNumber must be a plain PR number, got '$value'" }
        }
        val prHistory = prNumberValue?.let { mergeHistory(readHistory(prHistoryFile), currentRun) }

        val currentJson = currentRun.toJson()
        val comparisonJson = comparison.toJson(currentRun, baselineRun)
        val historyJson = updatedHistory.toJson()
        val prHistoryJson = prHistory?.toJson()
        val summaryMarkdown = renderSummaryMarkdown(currentRun, baselineRun, comparison, prNumberValue, prHistory)

        reportsDir.resolve("current.json").writeText(json.encodeToString(JsonObject.serializer(), currentJson) + "\n")
        reportsDir.resolve("comparison.json").writeText(json.encodeToString(JsonObject.serializer(), comparisonJson) + "\n")
        reportsDir.resolve("history.json").writeText(json.encodeToString(JsonObject.serializer(), historyJson) + "\n")
        prHistoryJson?.let {
            reportsDir.resolve("pr-history.json").writeText(json.encodeToString(JsonObject.serializer(), it) + "\n")
        }
        reportsDir.resolve("summary.md").writeText(summaryMarkdown)

        siteTemplateDirectory.asFile.get().copyRecursively(siteDir, overwrite = true)
        val siteDataDir = siteDir.resolve("data").apply(File::mkdirs)
        siteDataDir.resolve("latest.json").writeText(json.encodeToString(JsonObject.serializer(), currentJson) + "\n")
        siteDataDir.resolve("comparison.json").writeText(json.encodeToString(JsonObject.serializer(), comparisonJson) + "\n")
        siteDataDir.resolve("history.json").writeText(json.encodeToString(JsonObject.serializer(), historyJson) + "\n")
        // data.js lets the dashboard work over file:// where fetch() of local JSON is blocked.
        siteDataDir.resolve("data.js").writeText(renderDataJs(currentJson, comparisonJson, historyJson))
        if (prNumberValue != null && prHistoryJson != null) {
            // The same three-file contract as data/, so the dashboard can load a PR
            // view (?pr=<n>) through the identical code path. history.json holds only
            // this PR's runs; comparison.json still compares against the main baseline.
            val prDataDir = siteDataDir.resolve("prs/$prNumberValue").apply(File::mkdirs)
            prDataDir.resolve("latest.json").writeText(json.encodeToString(JsonObject.serializer(), currentJson) + "\n")
            prDataDir.resolve("comparison.json").writeText(json.encodeToString(JsonObject.serializer(), comparisonJson) + "\n")
            prDataDir.resolve("history.json").writeText(json.encodeToString(JsonObject.serializer(), prHistoryJson) + "\n")
        }
        siteDir.resolve(".nojekyll").writeText("\n")

        publishGitHubSummary(summaryMarkdown)
    }

    private fun renderDataJs(current: JsonObject, comparison: JsonObject, history: JsonObject): String {
        val payload = buildJsonObject {
            put("latest", current)
            put("comparison", comparison)
            put("history", history)
        }
        return "window.BENCHMARK_DATA = " + compactJson.encodeToString(JsonObject.serializer(), payload) + ";\n"
    }

    private fun publishGitHubSummary(summaryMarkdown: String) {
        val summaryPath = System.getenv("GITHUB_STEP_SUMMARY").orEmpty().trim()
        if (summaryPath.isEmpty()) return
        File(summaryPath).appendText(summaryMarkdown)
    }

    private fun readHistory(property: RegularFileProperty): BenchmarkHistory {
        val candidate = property.orNull?.asFile?.takeIf(File::isFile) ?: return BenchmarkHistory(emptyList())
        val root = json.parseToJsonElement(candidate.readText()).jsonObject
        val schemaVersion = root["schemaVersion"]?.jsonPrimitive?.intOrNull
        if (schemaVersion != HISTORY_SCHEMA_VERSION) {
            // The history is seeded from the live site, so hard-failing here would make every
            // run fail until someone manually deleted the published file. Start a fresh history
            // instead, loudly: losing the trend is bad, but a permanently red pipeline with no
            // migration path is worse.
            logger.warn(
                "Ignoring benchmark history at {}: schema version {} is not the expected {}. " +
                    "Starting a fresh history; previously published trend data will not be extended.",
                candidate.path,
                schemaVersion ?: "absent",
                HISTORY_SCHEMA_VERSION,
            )
            return BenchmarkHistory(emptyList())
        }
        val runs = root.jsonArrayOrEmpty("runs").map { runElement ->
            parseRun(runElement.jsonObject)
        }
        return BenchmarkHistory(runs)
    }

    private fun parseRun(objectValue: JsonObject): BenchmarkRun {
        val metadata = RunMetadata(
            generatedAt = objectValue.string("generatedAt"),
            repository = objectValue.stringOrNull("repository"),
            eventName = objectValue.stringOrNull("eventName"),
            refName = objectValue.stringOrNull("refName"),
            commitSha = objectValue.stringOrNull("commitSha"),
            runId = objectValue.stringOrNull("runId"),
            runAttempt = objectValue.stringOrNull("runAttempt"),
            runNumber = objectValue.stringOrNull("runNumber"),
            runUrl = objectValue.stringOrNull("runUrl"),
            commitUrl = objectValue.stringOrNull("commitUrl"),
            siteUrl = objectValue.stringOrNull("siteUrl"),
            runnerName = objectValue.stringOrNull("runnerName"),
            runnerOs = objectValue.stringOrNull("runnerOs"),
            harness = objectValue.stringOrNull("harness")
        )
        val measurements = objectValue.jsonArrayOrEmpty("measurements").map { measurementElement ->
            parseMeasurement(measurementElement.jsonObject)
        }
        return BenchmarkRun(metadata, measurements)
    }

    private fun parseMeasurement(objectValue: JsonObject): Measurement =
        Measurement(
            suite = objectValue.string("suite"),
            name = objectValue.string("name"),
            displayName = objectValue.string("displayName"),
            group = objectValue.string("group"),
            unit = objectValue.string("unit"),
            value = objectValue.double("value"),
            lowerValue = objectValue.doubleOrNull("lowerValue"),
            upperValue = objectValue.doubleOrNull("upperValue"),
            biggerIsBetter = objectValue.boolean("biggerIsBetter")
        )

    private fun buildCurrentRun(jniReportFile: File, ffmReportFile: File): BenchmarkRun {
        val metadata = RunMetadata(
            generatedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            repository = readEnv("GITHUB_REPOSITORY"),
            eventName = readEnv("GITHUB_EVENT_NAME"),
            refName = readEnv("GITHUB_HEAD_REF") ?: readEnv("GITHUB_REF_NAME"),
            commitSha = readEnv("GITHUB_SHA"),
            runId = readEnv("GITHUB_RUN_ID"),
            runAttempt = readEnv("GITHUB_RUN_ATTEMPT"),
            runNumber = readEnv("GITHUB_RUN_NUMBER"),
            runUrl = githubRunUrl(),
            commitUrl = githubCommitUrl(),
            siteUrl = siteUrl.orNull?.ifBlank { null },
            runnerName = readEnv("RUNNER_NAME"),
            runnerOs = readEnv("RUNNER_OS"),
            harness = harnessDescriptor(jniReportFile, ffmReportFile)
        )

        val measurements = buildList {
            addAll(parseJmhReport(jniReportFile, suite = "jni"))
            addAll(parseJmhReport(ffmReportFile, suite = "ffm"))
        }.sortedWith(compareBy<Measurement>({ suiteSortIndex(it.suite) }, { it.displayName }))

        // An empty run would be appended to the history and become the baseline the next run
        // compares against, so every following run would report all of its measurements as new
        // and the trend charts would restart. Fail instead.
        check(measurements.isNotEmpty()) {
            "No JMH measurements were parsed from ${jniReportFile.path} or ${ffmReportFile.path}. " +
                "Refusing to publish an empty run, which would become the next run's baseline."
        }

        return BenchmarkRun(metadata, measurements)
    }

    /**
     * Summarizes the JMH settings every benchmark in this run was measured with, e.g.
     * `thrpt, 1 fork, 5x1 s warmup, 5x1 s measurement`.
     *
     * Warns when the two suites disagree: their scores are put side by side in the comparison
     * table, which is only meaningful if both were measured the same way.
     */
    private fun harnessDescriptor(vararg reportFiles: File): String? {
        val descriptors = reportFiles
            .flatMap { file -> json.parseToJsonElement(file.readText()).jsonArray }
            .map { entry ->
                val objectValue = entry.jsonObject
                listOfNotNull(
                    objectValue.stringOrNull("mode"),
                    objectValue.stringOrNull("forks")?.let { "$it fork(s)" },
                    objectValue.stringOrNull("warmupIterations")
                        ?.let { "${it}x${objectValue.stringOrNull("warmupTime")} warmup" },
                    objectValue.stringOrNull("measurementIterations")
                        ?.let { "${it}x${objectValue.stringOrNull("measurementTime")} measurement" },
                ).joinToString(", ")
            }
            .filter(String::isNotBlank)
            .distinct()

        if (descriptors.size > 1) {
            logger.warn(
                "Benchmarks in this run were not measured with a single JMH configuration ({}). " +
                    "Scores measured under different settings are not comparable.",
                descriptors.joinToString(" | "),
            )
        }
        return descriptors.singleOrNull() ?: descriptors.firstOrNull()
    }

    private fun parseJmhReport(reportFile: File, suite: String): List<Measurement> {
        val packagePrefix = "$BENCHMARK_PACKAGE_ROOT$suite."
        val entries = json.parseToJsonElement(reportFile.readText()).jsonArray
        return entries.map { entry ->
            val objectValue = entry.jsonObject
            val rawName = objectValue.string("benchmark")
            val metric = objectValue.requiredObject("primaryMetric")
            // JMH reports NaN confidence bounds for benchmarks it could not measure (a single
            // measurement iteration, for instance), and "NaN".toDoubleOrNull() yields NaN rather
            // than null. Encoding one fails the whole task, since kotlinx.serialization rejects
            // non-finite doubles by default. Keep the interval only when both bounds are usable:
            // dropping just one would leave the surviving bound misread as the lower bound.
            val confidence = metric["scoreConfidence"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.doubleOrNull }
                ?.takeIf { bounds -> bounds.size == 2 && bounds.all(Double::isFinite) }
                .orEmpty()
            val unit = metric.string("scoreUnit")
            Measurement(
                suite = suite,
                name = rawName,
                displayName = rawName.removePrefix(packagePrefix),
                group = rawName.removePrefix(packagePrefix).substringBefore('.'),
                unit = unit,
                value = metric.double("score"),
                lowerValue = confidence.getOrNull(0),
                upperValue = confidence.getOrNull(1),
                biggerIsBetter = biggerIsBetterFor(unit, rawName)
            )
        }
    }

    /**
     * Derives the direction of "better" from the JMH score unit, so that adding an
     * [org.openjdk.jmh.annotations.Mode] whose score is a time per operation cannot silently invert
     * every verdict. Throughput units read as `ops/&lt;time&gt;`; time-per-operation units as `&lt;time&gt;/op`.
     *
     * Fails loudly on an unrecognised unit rather than guessing: a wrong guess here is published,
     * PR-commented and colour-coded as if it were a real result.
     */
    private fun biggerIsBetterFor(unit: String, benchmarkName: String): Boolean = when {
        unit.startsWith("ops/") -> true
        unit.endsWith("/op") -> false
        else -> error(
            "Cannot tell whether a higher score is better for '$benchmarkName': unrecognised JMH " +
                "score unit '$unit'. Expected a throughput unit ('ops/s', 'ops/ms', ...) or a " +
                "time-per-operation unit ('ns/op', 'us/op', ...)."
        )
    }

    /**
     * A change in JMH settings shifts every score at once, which the significance threshold then
     * reports as a run full of genuine regressions or improvements. Say so explicitly, because
     * nothing else in the output distinguishes it from a real result.
     */
    private fun warnOnHarnessChange(currentRun: BenchmarkRun, baselineRun: BenchmarkRun?) {
        val current = currentRun.metadata.harness ?: return
        val baseline = baselineRun?.metadata?.harness ?: return
        if (current != baseline) {
            logger.warn(
                "JMH configuration changed since the baseline run (baseline: {}; current: {}). " +
                    "Deltas in this report reflect that change as well as any real difference.",
                baseline,
                current,
            )
        }
    }

    private fun criterionFor(measurement: Measurement, baseline: Measurement?): SignificanceCriterion =
        when {
            baseline != null &&
                measurement.lowerValue != null && measurement.upperValue != null &&
                baseline.lowerValue != null && baseline.upperValue != null -> SignificanceCriterion.CONFIDENCE_INTERVAL
            else -> SignificanceCriterion.THRESHOLD
        }

    private fun isSignificant(
        criterion: SignificanceCriterion,
        measurement: Measurement,
        baseline: Measurement,
        deltaRatio: Double?
    ): Boolean = when (criterion) {
        // JMH reports a 99.9% confidence interval per benchmark; a change is real
        // only when the current and baseline intervals do not overlap.
        SignificanceCriterion.CONFIDENCE_INTERVAL ->
            measurement.lowerValue!! > baseline.upperValue!! || measurement.upperValue!! < baseline.lowerValue!!
        SignificanceCriterion.THRESHOLD ->
            deltaRatio != null && abs(deltaRatio) >= significanceThreshold.get()
    }

    private fun compareRuns(currentRun: BenchmarkRun, baselineRun: BenchmarkRun?): List<ComparisonEntry> {
        warnOnHarnessChange(currentRun, baselineRun)
        return compareAgainst(currentRun, baselineIndex(baselineRun))
    }

    /** Index of a baseline run's measurements, hoistable out of a loop over many runs. */
    private fun baselineIndex(baselineRun: BenchmarkRun?): Map<String, Measurement> =
        baselineRun?.measurements?.associateBy(Measurement::key).orEmpty()

    /**
     * Comparison against an already-built baseline index, and without the harness warning: callers
     * that compare many runs against one baseline would otherwise rebuild the index per run and
     * repeat the warning once per run.
     */
    private fun compareAgainst(
        currentRun: BenchmarkRun,
        baselineByKey: Map<String, Measurement>
    ): List<ComparisonEntry> {
        return currentRun.measurements.map { measurement ->
            val baseline = baselineByKey[measurement.key]
            val delta = baseline?.let { measurement.value - it.value }
            val deltaRatio = baseline
                ?.takeIf { it.value != 0.0 }
                ?.let { (measurement.value - it.value) / it.value }
            val criterion = criterionFor(measurement, baseline)
            val change = when {
                baseline == null -> ChangeType.NEW
                deltaRatio == null || !isSignificant(criterion, measurement, baseline, deltaRatio) -> ChangeType.UNCHANGED
                measurement.biggerIsBetter == (deltaRatio > 0) -> ChangeType.IMPROVEMENT
                else -> ChangeType.REGRESSION
            }
            ComparisonEntry(
                current = measurement,
                baseline = baseline,
                delta = delta,
                deltaRatio = deltaRatio,
                criterion = criterion,
                change = change
            )
        }.sortedWith(compareBy<ComparisonEntry>({ suiteSortIndex(it.current.suite) }, { it.current.displayName }))
    }

    private fun mergeHistory(existingHistory: BenchmarkHistory, currentRun: BenchmarkRun): BenchmarkHistory {
        val deduplicated = existingHistory.runs.filterNot { existingRun ->
            existingRun.metadata.commitSha != null &&
                existingRun.metadata.commitSha == currentRun.metadata.commitSha &&
                existingRun.metadata.refName == currentRun.metadata.refName &&
                existingRun.metadata.eventName == currentRun.metadata.eventName
        }
        return BenchmarkHistory((deduplicated + currentRun).takeLast(max(1, historyLimit.get())))
    }

    private fun renderSummaryMarkdown(
        currentRun: BenchmarkRun,
        baselineRun: BenchmarkRun?,
        comparison: List<ComparisonEntry>,
        prNumber: String?,
        prHistory: BenchmarkHistory?
    ): String {
        val regressions = comparison.topEntries(ChangeType.REGRESSION)
        val improvements = comparison.topEntries(ChangeType.IMPROVEMENT)
        val siteLink = currentRun.metadata.siteUrl?.let { site ->
            buildString {
                append("- Site: [$site]($site)\n")
                if (prNumber != null) {
                    val prUrl = "${site.trimEnd('/')}/?pr=$prNumber"
                    append("- PR dashboard: [$prUrl]($prUrl)\n")
                }
            }
        }.orEmpty()
        val baselineLine = baselineRun?.let {
            val baselineRef = listOfNotNull(it.metadata.refName, it.metadata.commitSha?.take(7)).joinToString(" @ ")
            "- Baseline: `${markdownCode(baselineRef.ifBlank { "latest published run" })}`\n"
        } ?: "- Baseline: none yet; this run created the first snapshot.\n"
        val fallbackPercent = formatNumber("%.1f", significanceThreshold.get() * 100)

        return buildString {
            appendLine("# Benchmark Report")
            appendLine()
            appendLine("- Generated: `${currentRun.metadata.generatedAt}`")
            appendLine("- Measurements: `${currentRun.measurements.size}` across `${currentRun.measurements.map(Measurement::suite).distinct().size}` suites")
            append(baselineLine)
            appendLine("- Significance: per-benchmark 99.9% confidence intervals (±$fallbackPercent% fallback)")
            append(siteLink)
            currentRun.metadata.runUrl?.let { appendLine("- Workflow run: [$it]($it)") }
            currentRun.metadata.commitUrl?.let { appendLine("- Commit: [$it]($it)") }
            appendLine()

            appendLine("## Largest Regressions")
            appendLine()
            append(renderChangeTable(regressions, emptyLabel = "No significant regressions relative to the baseline.\n"))
            appendLine()

            appendLine("## Largest Improvements")
            appendLine()
            append(renderChangeTable(improvements, emptyLabel = "No significant improvements relative to the baseline.\n"))
            appendLine()

            if (prHistory != null) {
                appendLine("## Benchmark Runs in This PR")
                appendLine()
                appendLine("| Run | Commit | Regressions | Improvements |")
                appendLine("| --- | --- | ---: | ---: |")
                // Built once: every run below is compared against this same baseline.
                val baselineByKey = baselineIndex(baselineRun)
                prHistory.runs.forEachIndexed { index, run ->
                    val date = run.metadata.generatedAt
                    val runLabel = run.metadata.runUrl?.let { "[$date]($it)" } ?: "`$date`"
                    val marker = if (index == prHistory.runs.lastIndex) " **(this run)**" else ""
                    val commit = run.metadata.commitSha?.take(7)?.let { sha ->
                        run.metadata.commitUrl?.let { "[`$sha`]($it)" } ?: "`$sha`"
                    } ?: "n/a"
                    // Every run is compared against the same (current) baseline, so the
                    // counts are directly comparable across the PR's pushes.
                    val counts = if (baselineRun != null) {
                        val runComparison = compareAgainst(run, baselineByKey)
                        val runRegressions = runComparison.count { it.change == ChangeType.REGRESSION }
                        val runImprovements = runComparison.count { it.change == ChangeType.IMPROVEMENT }
                        "$runRegressions | $runImprovements"
                    } else {
                        "n/a | n/a"
                    }
                    appendLine("| $runLabel$marker | $commit | $counts |")
                }
                appendLine()
            }

            appendLine("## Suite Coverage")
            appendLine()
            appendLine("| Suite | Measurements |")
            appendLine("| --- | ---: |")
            currentRun.measurements.groupBy(Measurement::suite).toSortedMap(compareBy(::suiteSortIndex)).forEach { (suite, entries) ->
                appendLine("| `${suite}` | ${entries.size} |")
            }
        }
    }

    /**
     * Makes a value safe to drop inside a markdown table cell wrapped in backticks. Benchmark
     * names are Java identifiers, but the baseline ref is a branch name, and a branch containing
     * a backtick or a pipe would otherwise break out of the cell and garble the sticky PR comment.
     */
    private fun markdownCode(value: String): String =
        value.replace("`", "'").replace("|", "\\|").replace(Regex("[\\r\\n]+"), " ")

    private fun renderChangeTable(entries: List<ComparisonEntry>, emptyLabel: String): String =
        if (entries.isEmpty()) {
            emptyLabel
        } else {
            buildString {
                appendLine("| Suite | Benchmark | Current | Baseline | Delta |")
                appendLine("| --- | --- | ---: | ---: | ---: |")
                entries.forEach { entry ->
                    appendLine(
                        "| `${markdownCode(entry.current.suite)}` | " +
                            "`${markdownCode(entry.current.displayName)}` | ${formatValue(entry.current)} | " +
                            "${entry.baseline?.let(::formatValue) ?: "new"} | ${formatDelta(entry)} |"
                    )
                }
            }
        }

    private fun githubRunUrl(): String? {
        val serverUrl = readEnv("GITHUB_SERVER_URL")
        val repository = readEnv("GITHUB_REPOSITORY")
        val runId = readEnv("GITHUB_RUN_ID")
        return if (serverUrl != null && repository != null && runId != null) {
            "$serverUrl/$repository/actions/runs/$runId"
        } else {
            null
        }
    }

    private fun githubCommitUrl(): String? {
        val serverUrl = readEnv("GITHUB_SERVER_URL")
        val repository = readEnv("GITHUB_REPOSITORY")
        val commitSha = readEnv("GITHUB_SHA")
        return if (serverUrl != null && repository != null && commitSha != null) {
            "$serverUrl/$repository/commit/$commitSha"
        } else {
            null
        }
    }

    private fun readEnv(name: String): String? =
        System.getenv(name)?.trim()?.takeIf(String::isNotEmpty)

    private fun formatValue(measurement: Measurement): String {
        val formattedValue = when {
            abs(measurement.value) >= 100 -> formatNumber("%,.2f", measurement.value)
            abs(measurement.value) >= 10 -> formatNumber("%,.3f", measurement.value)
            abs(measurement.value) >= 1 -> formatNumber("%,.4f", measurement.value)
            else -> formatNumber("%,.5f", measurement.value)
        }
        return "$formattedValue ${measurement.unit}"
    }

    private fun formatDelta(entry: ComparisonEntry): String =
        when {
            entry.baseline == null -> "new"
            entry.deltaRatio == null -> "n/a"
            abs(entry.deltaRatio) < DISPLAY_ZERO_EPSILON -> "0.00%"
            entry.change == ChangeType.UNCHANGED -> formatNumber("%+.2f", entry.deltaRatio * 100) + "% (noise)"
            else -> formatNumber("%+.2f", entry.deltaRatio * 100) + "%"
        }

    private fun suiteSortIndex(suite: String): Int =
        when (suite) {
            "jni" -> 0
            "ffm" -> 1
            else -> 99
        }

    private fun buildComparisonEntriesJson(entries: List<ComparisonEntry>): JsonArray = buildJsonArray {
        entries.forEach { entry ->
            add(entry.toJson())
        }
    }

    private fun BenchmarkRun.toJson(): JsonObject = buildJsonObject {
        put("schemaVersion", HISTORY_SCHEMA_VERSION)
        put("generatedAt", metadata.generatedAt)
        metadata.repository?.let { put("repository", it) }
        metadata.eventName?.let { put("eventName", it) }
        metadata.refName?.let { put("refName", it) }
        metadata.commitSha?.let { put("commitSha", it) }
        metadata.runId?.let { put("runId", it) }
        metadata.runAttempt?.let { put("runAttempt", it) }
        metadata.runNumber?.let { put("runNumber", it) }
        metadata.runUrl?.let { put("runUrl", it) }
        metadata.commitUrl?.let { put("commitUrl", it) }
        metadata.siteUrl?.let { put("siteUrl", it) }
        metadata.runnerName?.let { put("runnerName", it) }
        metadata.runnerOs?.let { put("runnerOs", it) }
        metadata.harness?.let { put("harness", it) }
        put("measurements", buildJsonArray {
            measurements.forEach { add(it.toJson()) }
        })
    }

    private fun Measurement.toJson(): JsonObject = buildJsonObject {
        put("suite", suite)
        put("name", name)
        put("displayName", displayName)
        put("group", group)
        put("unit", unit)
        put("value", value)
        lowerValue?.let { put("lowerValue", it) }
        upperValue?.let { put("upperValue", it) }
        put("biggerIsBetter", biggerIsBetter)
    }

    private fun ComparisonEntry.toJson(): JsonObject = buildJsonObject {
        put("current", current.toJson())
        baseline?.let { put("baseline", it.toJson()) }
        delta?.let { put("delta", it) }
        deltaRatio?.let { put("deltaRatio", it) }
        put("criterion", criterion.jsonName)
        put("change", change.name.lowercase())
    }

    private fun List<ComparisonEntry>.toJson(currentRun: BenchmarkRun, baselineRun: BenchmarkRun?): JsonObject = buildJsonObject {
        put("schemaVersion", HISTORY_SCHEMA_VERSION)
        put("generatedAt", currentRun.metadata.generatedAt)
        put("significanceThreshold", significanceThreshold.get())
        put("current", currentRun.toJson())
        baselineRun?.let { put("baseline", it.toJson()) }
        put("entries", buildComparisonEntriesJson(this@toJson))
    }

    private fun BenchmarkHistory.toJson(): JsonObject = buildJsonObject {
        put("schemaVersion", HISTORY_SCHEMA_VERSION)
        put("generatedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
        put("runs", buildJsonArray {
            runs.forEach { add(it.toJson()) }
        })
    }

    private fun List<ComparisonEntry>.topEntries(changeType: ChangeType): List<ComparisonEntry> =
        filter { it.change == changeType && it.deltaRatio != null }
            .sortedByDescending { abs(it.deltaRatio ?: 0.0) }
            .take(8)

    private fun JsonObject.requiredObject(name: String): JsonObject =
        this[name]?.jsonObject ?: error("Missing object '$name'")

    private fun JsonObject.jsonArrayOrEmpty(name: String): JsonArray =
        this[name]?.jsonArray ?: JsonArray(emptyList())

    private fun JsonObject.string(name: String): String =
        stringOrNull(name) ?: error("Missing string '$name'")

    // JsonNull is a JsonPrimitive whose content is the string "null", so an explicit null in the
    // JSON would come back as the four-character text rather than as absent. Writes omit nulls
    // today, so this is latent, but a hand-edited or future-schema history would hit it.
    private fun JsonObject.stringOrNull(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.double(name: String): Double =
        this[name]?.jsonPrimitive?.double ?: error("Missing double '$name'")

    private fun JsonObject.doubleOrNull(name: String): Double? =
        this[name]?.jsonPrimitive?.doubleOrNull

    private fun JsonObject.boolean(name: String): Boolean =
        this[name]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: error("Missing boolean '$name'")

    private companion object {
        private const val BENCHMARK_PACKAGE_ROOT = "me.zolotov.oniguruma."
        private const val HISTORY_SCHEMA_VERSION = 1

        private const val DISPLAY_ZERO_EPSILON = 1e-4

        // Locale.ROOT everywhere: default-locale formatting can emit comma decimal
        // separators that corrupt JSON-adjacent output and percentage strings.
        private fun formatNumber(pattern: String, value: Double): String =
            String.format(Locale.ROOT, pattern, value)

        private val json = Json {
            prettyPrint = true
        }

        private val compactJson = Json
    }
}

private data class BenchmarkHistory(
    val runs: List<BenchmarkRun>
)

private data class BenchmarkRun(
    val metadata: RunMetadata,
    val measurements: List<Measurement>
)

private data class RunMetadata(
    val generatedAt: String,
    val repository: String?,
    val eventName: String?,
    val refName: String?,
    val commitSha: String?,
    val runId: String?,
    val runAttempt: String?,
    val runNumber: String?,
    val runUrl: String?,
    val commitUrl: String?,
    val siteUrl: String?,
    val runnerName: String?,
    val runnerOs: String?,
    /**
     * Fingerprint of the JMH configuration the scores were produced with, read back out of the
     * JMH reports. Scores are only comparable across runs that share it, so it travels with the
     * history rather than being implied by the workflow that happened to produce the run.
     */
    val harness: String?
)

private data class Measurement(
    val suite: String,
    val name: String,
    val displayName: String,
    val group: String,
    val unit: String,
    val value: Double,
    val lowerValue: Double?,
    val upperValue: Double?,
    val biggerIsBetter: Boolean
) {
    val key: String
        get() = "$suite::$name::$unit"
}

private data class ComparisonEntry(
    val current: Measurement,
    val baseline: Measurement?,
    val delta: Double?,
    val deltaRatio: Double?,
    val criterion: SignificanceCriterion,
    val change: ChangeType
)

private enum class SignificanceCriterion(val jsonName: String) {
    CONFIDENCE_INTERVAL("confidence-interval"),
    THRESHOLD("threshold")
}

private enum class ChangeType {
    IMPROVEMENT,
    REGRESSION,
    NEW,
    UNCHANGED
}
