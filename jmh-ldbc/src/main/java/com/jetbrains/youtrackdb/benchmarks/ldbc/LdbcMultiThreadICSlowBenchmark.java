package com.jetbrains.youtrackdb.benchmarks.ldbc;

import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Multi-threaded LDBC SNB Interactive Complex slow query benchmark.
 * Runs IC1, IC4, IC6, IC9, IC12 with one thread per available processor
 * ({@link Threads#MAX}) and long measurement windows.
 *
 * <p>Usage:
 * <pre>
 * ./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests -Djmh.args="LdbcMultiThreadICSlow.*"
 * </pre>
 */
@Threads(Threads.MAX)
public class LdbcMultiThreadICSlowBenchmark extends LdbcICSlowBenchmarkBase {

  public static void main(String[] args) throws RunnerException {
    new Runner(
        new OptionsBuilder()
            .include(LdbcMultiThreadICSlowBenchmark.class.getSimpleName())
            .build())
        .run();
  }
}
