package me.zolotov.oniguruma.ffm;

import me.zolotov.oniguruma.benchmarks.AbstractOnigurumaCreateBenchmarkState;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
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

    @TearDown(Level.Trial)
    public void tearDown() {
        oniguruma.close();
    }

    @Benchmark
    public void benchmarkCreateRegex(Blackhole blackhole) {
        try (var regex = oniguruma.createRegex(pattern)) {
            blackhole.consume(regex);
        }
    }

    @Benchmark
    public void benchmarkCreateString(Blackhole blackhole) {
        try (var string = oniguruma.createString(smallText)) {
            blackhole.consume(string);
        }
    }

    @Benchmark
    public void benchmarkCreateStringLarge(Blackhole blackhole) {
        try (var string = oniguruma.createString(largeText)) {
            blackhole.consume(string);
        }
    }

    @Benchmark
    public Object benchmarkCreateRegexError() {
        try {
            return oniguruma.createRegex(invalidPattern);
        } catch (OnigurumaException e) {
            return e;
        }
    }
}
