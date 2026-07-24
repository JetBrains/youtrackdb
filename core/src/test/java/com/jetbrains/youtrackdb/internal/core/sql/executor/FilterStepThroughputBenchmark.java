package com.jetbrains.youtrackdb.internal.core.sql.executor;

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
 * Bench 2 — {@code FilterStep} throughput COVERAGE instrument (Track 06).
 *
 * <p>Drives the real query pipeline so the {@code FilterStep.filterMap} IR-first branch is exercised
 * end-to-end. It has TWO INDEPENDENT {@code @Benchmark} methods with distinct names that are NEVER
 * compared to each other — each measures its OWN path's absolute throughput, to be tracked over time
 * (InfluxDB / jmh-regression-alert.py) as a future-regression guard:
 * <ul>
 *   <li>{@link #filterStep_ir} — lowerable predicate ({@code age > :p}) → IR evaluator branch;</li>
 *   <li>{@link #filterStep_astFallback} — unlowerable predicate ({@code age IN [...]}) → AST
 *       {@code matchesFilters} fallback branch.</li>
 * </ul>
 *
 * <p><b>Filter-dominated design (AD4).</b> The dataset is unindexed (full sequential scan), the
 * projection is trivial ({@code SELECT age}), records are small (4 props), storage is in-memory, the
 * filter runs on every scanned row, and selectivity is ~50%. Even so, {@code filterMap} is a
 * minority of per-row cost (scan + entity-load dominate), so this bench is coverage-only — Bench 1
 * remains the sensitive detector. The two methods are per-path throughput, not a before/after A/B.
 *
 * <p><b>AD1.</b> Each method returns the materialized row count, so JMH consumes it and cannot
 * dead-code-eliminate the query.
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
public abstract class FilterStepThroughputBenchmark {

  /** IR branch: full scan + lowerable {@code age > :p} filter. Returns the row count (AD1). */
  @Benchmark
  public long filterStep_ir(ThroughputBenchmarkState state) {
    long count = 0;
    try (ResultSet rs = state.session.query(state.irSql, state.irParams)) {
      while (rs.hasNext()) {
        rs.next();
        count++;
      }
    }
    return count;
  }

  /** AST-fallback branch: full scan + unlowerable {@code age IN [...]} filter. Returns count (AD1). */
  @Benchmark
  public long filterStep_astFallback(ThroughputBenchmarkState state) {
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
