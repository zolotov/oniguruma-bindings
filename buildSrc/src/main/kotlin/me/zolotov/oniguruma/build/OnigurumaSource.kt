package me.zolotov.oniguruma.build

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat

/**
 * The pinned upstream Oniguruma source release every module builds its native code from.
 */
object OnigurumaSource {
    const val VERSION = "6.9.10"
    const val SOURCE_URL = "https://github.com/kkos/oniguruma/releases/download/v$VERSION/onig-$VERSION.tar.gz"

    // GitHub release assets are mutable, and these sources are compiled into the native libraries
    // bundled in the published artifacts. Pin the tarball by content, not just by version, so a
    // re-uploaded asset fails the build instead of silently shipping.
    // Cross-checked against Homebrew's oniguruma formula for the same URL.
    const val SOURCE_SHA256 = "2a5cfc5ae259e4e97f86b68dfffc152cdaffe94e2060b770cb827238d769fc05"

    /**
     * The library sources CMakeLists.txt compiles into `libonig` (POSIX API excluded), relative
     * to the unpacked source's `src/` directory. Used by builds that compile Oniguruma without
     * driving its own build system, such as the Kotlin/Native cinterop.
     */
    val LIBRARY_SOURCES = listOf(
        "regerror.c", "regparse.c", "regext.c", "regcomp.c", "regexec.c",
        "reggnu.c", "regenc.c", "regsyntax.c", "regtrav.c", "regversion.c",
        "st.c", "onig_init.c",
        "unicode.c", "ascii.c", "utf8.c", "utf16_be.c", "utf16_le.c",
        "utf32_be.c", "utf32_le.c", "euc_jp.c", "sjis.c", "iso8859_1.c",
        "iso8859_2.c", "iso8859_3.c", "iso8859_4.c", "iso8859_5.c",
        "iso8859_6.c", "iso8859_7.c", "iso8859_8.c", "iso8859_9.c",
        "iso8859_10.c", "iso8859_11.c", "iso8859_13.c", "iso8859_14.c",
        "iso8859_15.c", "iso8859_16.c", "euc_tw.c", "euc_kr.c", "big5.c",
        "gb18030.c", "koi8_r.c", "cp1251.c",
        "euc_jp_prop.c", "sjis_prop.c",
        "unicode_unfold_key.c",
        "unicode_fold1_key.c", "unicode_fold2_key.c", "unicode_fold3_key.c",
    )
}

class OnigurumaSourceTasks(
    /** Unpacks the verified source tarball; depend on this before reading [sourceRoot]. */
    val unpackTask: TaskProvider<Sync>,
    /** Directory the source tree is unpacked into (its `src/` holds headers and C sources). */
    val sourceRoot: Provider<Directory>,
)

/**
 * Registers `downloadOnigurumaSource` and `unpackOnigurumaSource` tasks fetching the pinned
 * Oniguruma source release into this project's build directory.
 */
fun Project.registerOnigurumaSource(): OnigurumaSourceTasks {
    val archive = layout.buildDirectory.file("downloads/oniguruma-${OnigurumaSource.VERSION}.tar.gz")
    val sourceRoot = layout.buildDirectory.dir("native-src/oniguruma")

    val download = tasks.register("downloadOnigurumaSource") {
        inputs.property("onigurumaVersion", OnigurumaSource.VERSION)
        inputs.property("onigurumaSourceUrl", OnigurumaSource.SOURCE_URL)
        inputs.property("onigurumaSourceSha256", OnigurumaSource.SOURCE_SHA256)
        outputs.file(archive)

        val sourceUrl = OnigurumaSource.SOURCE_URL
        val expectedSha256 = OnigurumaSource.SOURCE_SHA256
        val archiveFile = archive
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

    val unpack = tasks.register<Sync>("unpackOnigurumaSource") {
        dependsOn(download)
        from(tarTree(resources.gzip(archive)))
        into(sourceRoot)
        eachFile {
            val segments = relativePath.segments.drop(1)
            if (segments.isEmpty()) {
                exclude()
            } else {
                relativePath = org.gradle.api.file.RelativePath(true, *segments.toTypedArray())
            }
        }
        includeEmptyDirs = false
    }

    return OnigurumaSourceTasks(unpack, sourceRoot)
}
