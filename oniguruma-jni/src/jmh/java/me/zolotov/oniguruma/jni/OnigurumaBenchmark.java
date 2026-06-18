package me.zolotov.oniguruma.jni;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
public class OnigurumaBenchmark {
    private Oniguruma oniguruma;
    private byte[] pattern;
    private byte[] smallText;
    private byte[] largeText;
    private byte[] invalidPattern;
    private long stringPtr;
    private long regexPtr;

    @Setup(Level.Trial)
    public void setup() {
        oniguruma = Oniguruma.createFromResources();
        pattern = "[0-9]+".getBytes(StandardCharsets.UTF_8);
        regexPtr = oniguruma.createRegex(pattern);
        smallText = "🚧🚧🚧 привет, мир 123!".getBytes(StandardCharsets.UTF_8);

        StringBuilder builder = new StringBuilder();
        while (builder.length() < 64 * 1024) {
            builder.append("val variable = listOf(1, 2, 3).map { it * it } // a typical line of source code\n");
        }
        largeText = builder.toString().getBytes(StandardCharsets.UTF_8);
        invalidPattern = "(unclosed[".getBytes(StandardCharsets.UTF_8);
        stringPtr = oniguruma.createString(smallText);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        oniguruma.freeRegex(regexPtr);
        oniguruma.freeString(stringPtr);
    }

    @Benchmark
    public void benchmarkMatch() {
        oniguruma.match(regexPtr, stringPtr, 0, true, true);
    }

    @Benchmark
    public long benchmarkCreateString() {
        long ptr = oniguruma.createString(smallText);
        oniguruma.freeString(ptr);
        return ptr;
    }

    @Benchmark
    public long benchmarkCreateStringLarge() {
        long ptr = oniguruma.createString(largeText);
        oniguruma.freeString(ptr);
        return ptr;
    }

    @Benchmark
    public long benchmarkCreateRegex() {
        long ptr = oniguruma.createRegex(pattern);
        oniguruma.freeRegex(ptr);
        return ptr;
    }

    @Benchmark
    public long benchmarkCreateRegexError() {
        try {
            return oniguruma.createRegex(invalidPattern);
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
