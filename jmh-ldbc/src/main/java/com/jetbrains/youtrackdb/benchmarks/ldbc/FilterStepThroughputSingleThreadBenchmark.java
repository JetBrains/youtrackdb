package com.jetbrains.youtrackdb.benchmarks.ldbc;

import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Single-threaded runner for the Bench-2 {@code FilterStep} throughput coverage instrument
 * ({@link FilterStepThroughputBenchmark}).
 *
 * <p>Usage:
 * <pre>
 * ./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests \
 *   -Djmh.args="FilterStepThroughputSingleThreadBenchmark.*"
 * </pre>
 */
@Threads(1)
public class FilterStepThroughputSingleThreadBenchmark extends FilterStepThroughputBenchmark {

  public static void main(String[] args) throws RunnerException {
    new Runner(
        new OptionsBuilder()
            .include(FilterStepThroughputSingleThreadBenchmark.class.getSimpleName())
            .build())
        .run();
  }
}
