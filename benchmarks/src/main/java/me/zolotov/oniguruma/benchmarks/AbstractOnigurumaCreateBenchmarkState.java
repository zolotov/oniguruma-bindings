package me.zolotov.oniguruma.benchmarks;

public abstract class AbstractOnigurumaCreateBenchmarkState {
    protected byte[] pattern;
    protected byte[] largePattern;
    protected byte[] invalidPattern;
    protected byte[] smallText;
    protected byte[] largeText;

    protected final void initializeCreateInputs() {
        pattern = OnigurumaBenchmarkInputs.pattern();
        largePattern = OnigurumaBenchmarkInputs.largePattern();
        invalidPattern = OnigurumaBenchmarkInputs.invalidPattern();
        smallText = OnigurumaBenchmarkInputs.smallText();
        largeText = OnigurumaBenchmarkInputs.largeText();
    }
}
