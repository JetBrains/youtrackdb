package com.jetbrains.youtrack.db.internal.core;

import com.jetbrains.youtrack.db.internal.common.profiler.GranularTicker;
import com.jetbrains.youtrack.db.internal.common.profiler.metrics.TimeInterval;
import com.jetbrains.youtrack.db.internal.common.profiler.metrics.TimeRate;
import com.jetbrains.youtrack.db.internal.common.profiler.Ticker;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Threads(4)
public class MeterBenchmark {

  private Ticker ticker;
  private TimeRate meter;

  @Setup
  public void setup() {
    ticker = new GranularTicker(10_000_000);
    meter = TimeRate.create(
        ticker,
        TimeInterval.of(10, TimeUnit.MILLISECONDS),
        TimeInterval.of(1, TimeUnit.SECONDS),
        TimeUnit.SECONDS
    );
  }

  @TearDown
  public void tearDown() {
    System.out.println("Rate: " + meter.getRate());
    ticker.stop();
  }

  @Benchmark
  public void measure() {
    meter.record(1);
  }

  public static void main(String[] args) throws RunnerException {
    final Options opt =
        new OptionsBuilder()
            .include("MeterBenchmark.*")
            .build();
    new Runner(opt).run();
  }
}
