package com.jetbrains.youtrackdb.benchmarks.ldbc;

import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Bench 3 — {@code ExpandStep} throughput COVERAGE instrument (Track 06).
 *
 * <p>Drives the real query pipeline over a star graph so the {@code ExpandStep.filterMap} IR-first
 * branch is exercised end-to-end on a push-down filter. TWO INDEPENDENT {@code @Benchmark} methods
 * with distinct names, NEVER compared to each other — each measures its OWN path's absolute
 * throughput, tracked over time (InfluxDB / jmh-regression-alert.py) as a future-regression guard:
 * <ul>
 *   <li>{@link #expandStep_ir} — lowerable push-down filter ({@code age > 49}) → IR branch;</li>
 *   <li>{@link #expandStep_astFallback} — unlowerable push-down filter ({@code age IN [...]}) → AST
 *       {@code matchesFilters} fallback branch.</li>
 * </ul>
 *
 * <p><b>Coverage-only (AD4, worst masking ratio).</b> {@code ExpandStep.filterMap} calls the SAME
 * {@code AnalyzedExprEvaluator.evaluate} as {@code FilterStep}, whose cost is already covered by
 * Bench 1; expansion (edge traversal + entity loads) dominates per-leaf cost, so a filter
 * regression is heavily attenuated here. This bench's marginal value is the ExpandStep integration
 * wiring. Bench 1 remains the sensitive detector.
 *
 * <p><b>AD1.</b> Each method returns the materialized row count, so JMH consumes it.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
@Fork(value = 3, jvmArgsAppend = {
    "-Xms4g", "-Xmx4g",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
    "--add-opens=java.base/java.net=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED",
    "--add-opens=java.base/sun.security.x509=ALL-UNNAMED",
    "--add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED",
})
public abstract class ExpandStepThroughputBenchmark {

  /** IR branch: expand + lowerable {@code age > 49} push-down filter. Returns row count (AD1). */
  @Benchmark
  public long expandStep_ir(ExpandThroughputBenchmarkState state) {
    long count = 0;
    try (ResultSet rs = state.session.query(state.irSql)) {
      while (rs.hasNext()) {
        rs.next();
        count++;
      }
    }
    return count;
  }

  /** AST-fallback branch: expand + unlowerable {@code age IN [...]} push-down. Returns count (AD1). */
  @Benchmark
  public long expandStep_astFallback(ExpandThroughputBenchmarkState state) {
    long count = 0;
    try (ResultSet rs = state.session.query(state.astSql)) {
      while (rs.hasNext()) {
        rs.next();
        count++;
      }
    }
    return count;
  }
}
