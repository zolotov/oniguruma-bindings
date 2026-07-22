package me.zolotov.oniguruma.jni;

import me.zolotov.oniguruma.benchmarks.AbstractOnigurumaMatchBenchmarkState;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
public class OnigurumaMatchBenchmark extends AbstractOnigurumaMatchBenchmarkState {
    private Oniguruma oniguruma;
    private long stringPtr;
    private long regexPtr;

    @Setup(Level.Trial)
    public void setup() {
        initializeMatchInputs();
        oniguruma = Oniguruma.createFromResources();
        regexPtr = oniguruma.createRegex(pattern);
        stringPtr = oniguruma.createString(smallText);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        oniguruma.freeRegex(regexPtr);
        oniguruma.freeString(stringPtr);
    }

    @Benchmark
    public int[] benchmarkMatch() {
        return oniguruma.match(
            regexPtr,
            stringPtr,
            0,
            true,
            true
        );
    }
}
