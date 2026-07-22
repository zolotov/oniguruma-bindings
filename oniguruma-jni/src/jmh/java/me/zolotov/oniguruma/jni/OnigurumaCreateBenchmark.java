package me.zolotov.oniguruma.jni;

import me.zolotov.oniguruma.benchmarks.AbstractOnigurumaCreateBenchmarkState;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
public class OnigurumaCreateBenchmark extends AbstractOnigurumaCreateBenchmarkState {
    private Oniguruma oniguruma;

    @Setup(Level.Trial)
    public void setup() {
        initializeCreateInputs();
        oniguruma = Oniguruma.createFromResources();
    }

    @Benchmark
    public void benchmarkCreateRegex(Blackhole blackhole) {
        long ptr = oniguruma.createRegex(pattern);
        try {
            blackhole.consume(ptr);
        } finally {
            oniguruma.freeRegex(ptr);
        }
    }

    @Benchmark
    public void benchmarkCreateString(Blackhole blackhole) {
        long ptr = oniguruma.createString(smallText);
        try {
            blackhole.consume(ptr);
        } finally {
            oniguruma.freeString(ptr);
        }
    }

    @Benchmark
    public void benchmarkCreateStringLarge(Blackhole blackhole) {
        long ptr = oniguruma.createString(largeText);
        try {
            blackhole.consume(ptr);
        } finally {
            oniguruma.freeString(ptr);
        }
    }

    @Benchmark
    public Object benchmarkCreateRegexError() {
        try {
            return oniguruma.createRegex(invalidPattern);
        } catch (RuntimeException e) {
            return e;
        }
    }
}
