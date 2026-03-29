package com.jetbrains.youtrackdb.benchmarks.ldbc;

import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Single-threaded LDBC SNB ultra-fast query benchmark.
 * Runs IS1, IS3-IS6, IC13 with a single thread and 5 forks.
 *
 * <p>Usage:
 * <pre>
 * ./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests \
 *   -Djmh.args="LdbcSingleThreadISUltraFast.*"
 * </pre>
 */
@Threads(1)
public class LdbcSingleThreadISUltraFastBenchmark
    extends LdbcISUltraFastBenchmarkBase {

  public static void main(String[] args) throws RunnerException {
    new Runner(
        new OptionsBuilder()
            .include(
                LdbcSingleThreadISUltraFastBenchmark.class.getSimpleName())
            .build())
        .run();
  }
}
