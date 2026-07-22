package me.zolotov.oniguruma.benchmarks;

public abstract class AbstractOnigurumaMatchBenchmarkState {
    protected byte[] pattern;
    protected byte[] smallText;

    protected final void initializeMatchInputs() {
        pattern = OnigurumaBenchmarkInputs.pattern();
        smallText = OnigurumaBenchmarkInputs.smallText();
    }
}
