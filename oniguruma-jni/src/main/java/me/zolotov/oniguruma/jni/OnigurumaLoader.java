package me.zolotov.oniguruma.jni;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

final class OnigurumaLoader {
    private static volatile boolean loaded;

    private OnigurumaLoader() {
    }

    static synchronized void loadFromResources() {
        if (!loaded) {
            String libraryName = System.mapLibraryName("oniguruma_jni");
            String resourcePath = determineResourcePath(libraryName);
            Path extractedLib = extractLibraryToTemporaryDirectory(resourcePath, libraryName);
            System.load(extractedLib.toAbsolutePath().toString());
            loaded = true;
        }
    }

    static synchronized void loadFromFile(Path path) {
        if (!loaded) {
            System.load(path.toAbsolutePath().toString());
            loaded = true;
        }
    }

    private static String determineResourcePath(String libraryName) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        String osName;
        if (os.contains("win")) {
            osName = "windows";
        } else if (os.contains("mac") || os.contains("darwin")) {
            osName = "macos";
        } else if (os.contains("linux")) {
            osName = "linux";
        } else {
            throw new UnsupportedOperationException("Unsupported operating system: " + os);
        }

        String archName;
        if (arch.contains("amd64") || arch.contains("x86_64")) {
            archName = "x86_64";
        } else if (arch.contains("aarch64") || arch.contains("arm64")) {
            archName = "aarch64";
        } else if (arch.contains("x86") || arch.contains("i386")) {
            archName = "x86";
        } else if (arch.contains("arm")) {
            String bits = System.getProperty("sun.arch.data.model");
            archName = bits == null || bits.equals("64") ? "arm64" : "arm32";
        } else {
            throw new UnsupportedOperationException("Unsupported architecture: " + arch);
        }

        return "/native/" + osName + "-" + archName + "/" + libraryName;
    }

    private static Path extractLibraryToTemporaryDirectory(String resourcePath, String libraryName) {
        try {
            Path tempDirectory = Files.createTempDirectory("oniguruma_jni");
            Path libraryFile = tempDirectory.resolve(libraryName);

            try (InputStream input = OnigurumaLoader.class.getResourceAsStream(resourcePath)) {
                if (input == null) {
                    throw new UnsatisfiedLinkError("Native library not found in resources: " + resourcePath);
                }
                Files.copy(input, libraryFile, StandardCopyOption.REPLACE_EXISTING);
            }

            tempDirectory.toFile().deleteOnExit();
            libraryFile.toFile().deleteOnExit();
            return libraryFile;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to extract native library from " + resourcePath, e);
        }
    }
}
