package me.zolotov.oniguruma.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Entry point to the Oniguruma FFM bindings.
 *
 * <p>Caller contract: every {@link OnigurumaRegex} and {@link OnigurumaString} obtained from
 * this instance must be closed exactly once before {@link #close()}. The library deliberately
 * does not track closed state or enforce lifecycle ordering: using a regex or string after it was
 * closed, closing it twice, closing it concurrently with {@link #match(OnigurumaRegex, OnigurumaString, int, boolean, boolean)},
 * or closing this instance while handles are still live is caller error and may fail with native/FFM undefined behavior.
 *
 * <p>Matching keeps one reusable native match region per thread that ever called
 * {@link #match(OnigurumaRegex, OnigurumaString, int, boolean, boolean)} on this instance;
 * {@link #close()} releases all of them. Closing this instance itself is idempotent.
 */
public final class Oniguruma implements AutoCloseable {
    // Layout of the single scratch block createRegex passes to onig_new:
    // [regex_t** out][OnigErrorInfo][pattern bytes]
    private static final long REGEX_OUT_OFFSET = 0;
    private static final long ERROR_INFO_OFFSET = ValueLayout.ADDRESS.byteSize();
    private static final long SCRATCH_HEADER_SIZE =
            ERROR_INFO_OFFSET + OnigurumaNative.ERROR_INFO_LAYOUT.byteSize();
    private static final long PATTERN_OFFSET = SCRATCH_HEADER_SIZE;

    // Patterns up to this size compile through a reused per-thread scratch block; larger ones
    // (rare even in textmate grammars) fall back to a one-off malloc/free.
    private static final long SCRATCH_CAPACITY = 4096;

    private final Arena libraryArena;
    private final OnigurumaNative nativeLib;

    // One reused region per thread. onig_region_new/onig_region_free around every search costs
    // three malloc/free pairs (~45ns), a quarter of a small match; the JNI binding reuses a
    // thread-local region for the same reason. Regions are kept here so close() can free them
    // without walking other threads' thread locals: a thread that ever matched holds its region
    // (~48 bytes plus the offset arrays) until this instance is closed.
    private final Queue<MemorySegment> regions = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<MemorySegment> threadRegion = ThreadLocal.withInitial(this::newRegion);

    // Reused createRegex scratch, pooled for close() the same way regions are: a thread that ever
    // compiled a pattern holds its SCRATCH_CAPACITY block until this instance is closed, even
    // after the thread itself dies.
    private final Queue<MemorySegment> scratchBuffers = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<MemorySegment> threadScratch = ThreadLocal.withInitial(this::newScratch);

    private final AtomicBoolean closed = new AtomicBoolean();

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
        // A single reused block instead of a confined Arena with three separate allocations: the
        // arena's session bookkeeping plus three malloc/zero/free rounds cost ~75ns per call,
        // one malloc/free pair ~20ns, the reused per-thread block ~5ns.
        long scratchSize = SCRATCH_HEADER_SIZE + Math.max(pattern.length, 1);
        boolean pooled = scratchSize <= SCRATCH_CAPACITY;
        MemorySegment scratch = pooled ? threadScratch.get() : nativeLib.allocateNative(scratchSize);
        try {
            MemorySegment regexOut = scratch.asSlice(REGEX_OUT_OFFSET, ValueLayout.ADDRESS.byteSize());
            MemorySegment errorInfo = scratch.asSlice(ERROR_INFO_OFFSET, OnigurumaNative.ERROR_INFO_LAYOUT.byteSize());
            // onig_compile clears errorInfo.par itself, but an error raised before it runs (a
            // rejected argument, an out-of-memory regex_t) leaves the struct untouched, and
            // onig_error_code_to_str would then dereference whatever malloc handed us.
            errorInfo.fill((byte) 0);

            MemorySegment patternSeg = scratch.asSlice(PATTERN_OFFSET, pattern.length);
            MemorySegment.copy(pattern, 0, patternSeg, ValueLayout.JAVA_BYTE, 0, pattern.length);
            MemorySegment patternEnd = patternSeg.asSlice(pattern.length, 0);

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
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new OnigurumaException("Failed to compile pattern", t);
        } finally {
            if (!pooled) {
                nativeLib.freeNative(scratch);
            }
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
            MemorySegment textEnd = text.bufferEnd();
            MemorySegment searchStart = byteOffset == 0 ? textStart : textStart.asSlice(byteOffset, 0);

            // The reused region needs no clearing before the search: onig_search resize-clears a
            // non-null region unconditionally on entry, on both the match and the mismatch paths
            // (oniguruma 6.9.10, regexec.c search_in_range).
            MemorySegment region = threadRegion.get();
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
                return null;
            }
            if (rc < 0) {
                throw new OnigurumaException("onig_search failed: " + nativeLib.errorMessage(rc, null));
            }
            return readRegion(region);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new OnigurumaException("Failed to match regex", t);
        }
    }

    @Override
    public void close() {
        // Idempotent: a second close() must not free the pools again or re-close the arena.
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // Free everything best-effort before closing the arena: aborting the loops on the first
        // failure would leak every remaining region and scratch buffer, and the arena close below
        // unloads the library they would have to be freed through.
        Throwable failure = null;
        for (MemorySegment region = regions.poll(); region != null; region = regions.poll()) {
            try {
                nativeLib.onigRegionFree.invokeExact(region, 1);
            } catch (Throwable t) {
                failure = addFailure(failure, t);
            }
        }
        for (MemorySegment scratch = scratchBuffers.poll(); scratch != null; scratch = scratchBuffers.poll()) {
            try {
                nativeLib.freeNative(scratch);
            } catch (Throwable t) {
                failure = addFailure(failure, t);
            }
        }
        libraryArena.close();
        if (failure != null) {
            if (failure instanceof RuntimeException e) {
                throw e;
            }
            if (failure instanceof Error e) {
                throw e;
            }
            throw new OnigurumaException("Failed to free pooled native memory", failure);
        }
    }

    private static Throwable addFailure(Throwable failure, Throwable next) {
        if (failure == null) {
            return next;
        }
        failure.addSuppressed(next);
        return failure;
    }

    private MemorySegment newScratch() {
        MemorySegment scratch = nativeLib.allocateNative(SCRATCH_CAPACITY);
        scratchBuffers.add(scratch);
        return scratch;
    }

    /**
     * Allocates this thread's reusable match region, already reinterpreted to the region layout so
     * that {@link #readRegion(MemorySegment)} can read its fields directly.
     */
    private MemorySegment newRegion() {
        MemorySegment region;
        try {
            region = (MemorySegment) nativeLib.onigRegionNew.invokeExact();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new OnigurumaException("Failed to allocate match region", t);
        }
        if (region.address() == 0L) {
            throw new OutOfMemoryError("onig_region_new returned null");
        }
        MemorySegment laidOut = region.reinterpret(OnigurumaNative.REGION_LAYOUT.byteSize());
        regions.add(laidOut);
        return laidOut;
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

    /**
     * @param region region segment sized to {@link OnigurumaNative#REGION_LAYOUT}, as returned by
     *               {@link #newRegion()}
     */
    private int[] readRegion(MemorySegment region) {
        int numRegs = nativeLib.regionNumRegs(region);
        if (numRegs <= 0) {
            return new int[0];
        }
        long byteCount = (long) numRegs * Integer.BYTES;
        MemorySegment beg = nativeLib.regionBeg(region).reinterpret(byteCount);
        MemorySegment end = nativeLib.regionEnd(region).reinterpret(byteCount);

        int[] offsets = new int[numRegs * 2];
        for (int i = 0; i < numRegs; i++) {
            offsets[2 * i] = beg.getAtIndex(ValueLayout.JAVA_INT, i);
            offsets[2 * i + 1] = end.getAtIndex(ValueLayout.JAVA_INT, i);
        }
        return offsets;
    }

}
