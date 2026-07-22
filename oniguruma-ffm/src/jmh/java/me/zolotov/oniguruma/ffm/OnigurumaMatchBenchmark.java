package me.zolotov.oniguruma.ffm;

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
    private OnigurumaString string;
    private OnigurumaRegex regex;

    @Setup(Level.Trial)
    public void setup() {
        initializeMatchInputs();
        oniguruma = Oniguruma.createFromResources();
        regex = oniguruma.createRegex(pattern);
        string = oniguruma.createString(smallText);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        oniguruma.freeRegex(regex);
        oniguruma.freeString(string);
        oniguruma.close();
    }

    @Benchmark
    public int[] benchmarkMatch() {
        return oniguruma.match(
            regex,
            string,
            0,
            true,
            true
        );
    }
}
