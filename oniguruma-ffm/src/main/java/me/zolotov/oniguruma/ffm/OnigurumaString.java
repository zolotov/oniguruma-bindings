package me.zolotov.oniguruma.ffm;

import java.lang.foreign.MemorySegment;

/**
 * Native UTF-8 string buffer used by Oniguruma matching.
 *
 * <p>This is a thin native resource wrapper. It does not track whether it has been closed. Clients
 * must close each instance exactly once, must not use it after closing, and must close it before
 * closing the owning {@link Oniguruma} instance.
 */
public final class OnigurumaString implements AutoCloseable {
    private final Oniguruma owner;
    private final MemorySegment buffer;
    private final int contentLength;

    OnigurumaString(Oniguruma owner, MemorySegment buffer, int contentLength) {
        this.owner = owner;
        this.buffer = buffer;
        this.contentLength = contentLength;
    }

    public long handleId() {
        return buffer.address();
    }

    @Override
    public void close() {
        owner.freeStringBuffer(buffer);
    }

    MemorySegment buffer() {
        return buffer;
    }

    int contentLength() {
        return contentLength;
    }

    Oniguruma owner() {
        return owner;
    }
}
