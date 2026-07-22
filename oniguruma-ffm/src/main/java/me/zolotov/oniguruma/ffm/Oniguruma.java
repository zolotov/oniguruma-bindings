package me.zolotov.oniguruma.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Entry point to the Oniguruma FFM bindings.
 *
 * <p>Caller contract: every {@link OnigurumaRegex} and {@link OnigurumaString} obtained from
 * this instance must be closed exactly once before {@link #close()}. The library deliberately
 * does not track closed state or enforce lifecycle ordering: using a regex or string after it was
 * closed, closing it twice, closing it concurrently with {@link #match(OnigurumaRegex, OnigurumaString, int, boolean, boolean)},
 * or closing this instance while handles are still live is caller error and may fail with native/FFM undefined behavior.
 */
public final class Oniguruma implements AutoCloseable {
    private final Arena libraryArena;
    private final OnigurumaNative nativeLib;

    private Oniguruma(Arena libraryArena, OnigurumaNative nativeLib) {
        this.libraryArena = libraryArena;
        this.nativeLib = nativeLib;
    }

    public static Oniguruma createFromResources() {
        var arena = Arena.ofShared();
        try {
            SymbolLookup lookup = OnigurumaNative.loadBundledOrSystemLibrary(arena);
            return new Oniguruma(arena, new OnigurumaNative(lookup));
        } catch (Throwable e) {
            arena.close();
            throw e;
        }
    }

    public static Oniguruma createFromFile(Path path) {
        Objects.requireNonNull(path, "path");
        var arena = Arena.ofShared();
        try {
            SymbolLookup lookup = OnigurumaNative.loadLibraryAt(path, arena);
            return new Oniguruma(arena, new OnigurumaNative(lookup));
        } catch (Throwable e) {
            arena.close();
            throw e;
        }
    }

    public OnigurumaRegex createRegex(byte[] pattern) {
        Objects.requireNonNull(pattern, "pattern");
        try (var scope = Arena.ofConfined()) {
            MemorySegment patternSeg = scope.allocate(Math.max(pattern.length, 1));
            MemorySegment.copy(pattern, 0, patternSeg, ValueLayout.JAVA_BYTE, 0, pattern.length);
            MemorySegment patternEnd = patternSeg.asSlice(pattern.length, 0);

            MemorySegment regexOut = scope.allocate(ValueLayout.ADDRESS);
            MemorySegment errorInfo = scope.allocate(OnigurumaNative.ERROR_INFO_LAYOUT);

            int rc = (int) nativeLib.onigNew.invokeExact(
                    regexOut,
                    patternSeg,
                    patternEnd,
                    OnigurumaNative.ONIG_OPTION_CAPTURE_GROUP,
                    nativeLib.utf8Encoding,
                    nativeLib.defaultSyntax,
                    errorInfo
            );
            if (rc != 0) {
                throw new OnigurumaException("Failed to compile pattern: " + nativeLib.errorMessage(rc, errorInfo));
            }
            MemorySegment handle = regexOut.get(ValueLayout.ADDRESS, 0);
            return new OnigurumaRegex(this, handle);
        } catch (Throwable t) {
            throw new OnigurumaException("Failed to compile pattern", t);
        }
    }

    public void freeRegex(OnigurumaRegex regex) {
        Objects.requireNonNull(regex, "regex").close();
    }

    public OnigurumaString createString(byte[] utf8Content) {
        Objects.requireNonNull(utf8Content, "utf8Content");
        // malloc/free instead of a per-string Arena: closing a shared arena performs a global
        // thread handshake, which made createString ~50x slower than the buffer copy itself.
        MemorySegment buffer = nativeLib.allocateNative(Math.max(utf8Content.length, 1));
        try {
            MemorySegment.copy(utf8Content, 0, buffer, ValueLayout.JAVA_BYTE, 0, utf8Content.length);
            return new OnigurumaString(this, buffer, utf8Content.length);
        } catch (Throwable e) {
            nativeLib.freeNative(buffer);
            throw e;
        }
    }

    public void freeString(OnigurumaString text) {
        Objects.requireNonNull(text, "text").close();
    }

    public int[] match(
            OnigurumaRegex regex,
            OnigurumaString text,
            int byteOffset,
            boolean matchBeginPosition,
            boolean matchBeginString
    ) {
        Objects.requireNonNull(regex, "regex");
        Objects.requireNonNull(text, "text");
        if (regex.owner() != this) {
            throw new IllegalArgumentException("regex was created by a different Oniguruma instance");
        }
        if (text.owner() != this) {
            throw new IllegalArgumentException("text was created by a different Oniguruma instance");
        }

        int textLength = text.contentLength();
        if (byteOffset < 0 || byteOffset > textLength) {
            throw new IllegalArgumentException(
                    "byteOffset " + byteOffset + " out of range [0, " + textLength + "]"
            );
        }

        int options = OnigurumaNative.ONIG_OPTION_NONE;
        if (!matchBeginPosition) {
            options |= OnigurumaNative.ONIG_OPTION_NOT_BEGIN_POSITION;
        }
        if (!matchBeginString) {
            options |= OnigurumaNative.ONIG_OPTION_NOT_BEGIN_STRING;
        }

        try {
            MemorySegment textStart = text.buffer();
            MemorySegment textEnd = textStart.asSlice(textLength, 0);
            MemorySegment searchStart = textStart.asSlice(byteOffset, 0);

            MemorySegment region = (MemorySegment) nativeLib.onigRegionNew.invokeExact();
            if (region.address() == 0L) {
                throw new OutOfMemoryError("onig_region_new returned null");
            }
            try {
                int rc = (int) nativeLib.onigSearch.invokeExact(
                        regex.handle(),
                        textStart,
                        textEnd,
                        searchStart,
                        textEnd,
                        region,
                        options
                );
                if (rc == OnigurumaNative.ONIG_MISMATCH) {
                    return new int[0];
                }
                if (rc < 0) {
                    throw new OnigurumaException("onig_search failed: " + nativeLib.errorMessage(rc, null));
                }
                return readRegion(region);
            } finally {
                nativeLib.onigRegionFree.invokeExact(region, 1);
            }
        } catch (Throwable t) {
            throw new OnigurumaException("Failed to match regex", t);
        }
    }

    @Override
    public void close() {
        libraryArena.close();
    }

    void freeRegexHandle(MemorySegment handle) {
        try {
            nativeLib.onigFree.invokeExact(handle);
        } catch (Throwable t) {
            throw new OnigurumaException("Failed to free regex handle", t);
        }
    }

    void freeStringBuffer(MemorySegment buffer) {
        nativeLib.freeNative(buffer);
    }

    private int[] readRegion(MemorySegment region) {
        MemorySegment laidOut = region.reinterpret(OnigurumaNative.REGION_LAYOUT.byteSize());
        int numRegs = nativeLib.regionNumRegs(laidOut);
        if (numRegs <= 0) {
            return new int[0];
        }
        long byteCount = (long) numRegs * Integer.BYTES;
        MemorySegment beg = nativeLib.regionBeg(laidOut).reinterpret(byteCount);
        MemorySegment end = nativeLib.regionEnd(laidOut).reinterpret(byteCount);

        int[] offsets = new int[numRegs * 2];
        for (int i = 0; i < numRegs; i++) {
            offsets[2 * i] = beg.getAtIndex(ValueLayout.JAVA_INT, i);
            offsets[2 * i + 1] = end.getAtIndex(ValueLayout.JAVA_INT, i);
        }
        return offsets;
    }

}
