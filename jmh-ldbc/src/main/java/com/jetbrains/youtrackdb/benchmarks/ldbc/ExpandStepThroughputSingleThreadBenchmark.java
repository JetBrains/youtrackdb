package com.jetbrains.youtrackdb.benchmarks.ldbc;

import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Single-threaded runner for the Bench-3 {@code ExpandStep} throughput coverage instrument
 * ({@link ExpandStepThroughputBenchmark}).
 *
 * <p>Usage:
 * <pre>
 * ./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests \
 *   -Djmh.args="ExpandStepThroughputSingleThreadBenchmark.*"
 * </pre>
 */
@Threads(1)
public class ExpandStepThroughputSingleThreadBenchmark extends ExpandStepThroughputBenchmark {

  public static void main(String[] args) throws RunnerException {
    new Runner(
        new OptionsBuilder()
            .include(ExpandStepThroughputSingleThreadBenchmark.class.getSimpleName())
            .build())
        .run();
  }
}
