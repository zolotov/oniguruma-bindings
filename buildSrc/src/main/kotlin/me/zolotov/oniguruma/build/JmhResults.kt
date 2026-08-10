package me.zolotov.oniguruma.build

import org.gradle.api.attributes.Attribute

/**
 * Marks the variant that carries a module's raw JMH `results.json`.
 *
 * Under Isolated Projects `:benchmarks` cannot reach into `:oniguruma-jni`/`:oniguruma-ffm` to read
 * their build directories, so the results file travels as a published-within-the-build artifact
 * instead of a hardcoded path.
 *
 * This is the only attribute on the producing variant, deliberately: consumers that do not ask for
 * it must keep resolving `runtimeElements`/`apiElements`, and Gradle's "longest match"
 * disambiguation gives that for free as long as the standard attributes are not mirrored here.
 */
val JMH_RESULTS_ATTRIBUTE: Attribute<String> = Attribute.of("me.zolotov.oniguruma.jmhResults", String::class.java)

const val JMH_RESULTS_JSON = "results-json"

/** Name of the consumable configuration each benchmarked module exposes. */
const val JMH_RESULTS_ELEMENTS = "jmhResultsElements"
