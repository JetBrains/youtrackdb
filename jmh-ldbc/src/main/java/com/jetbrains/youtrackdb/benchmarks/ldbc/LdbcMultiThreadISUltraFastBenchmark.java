package com.jetbrains.youtrackdb.benchmarks.ldbc;

import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Multi-threaded LDBC SNB ultra-fast query benchmark.
 * Runs IS1, IS3-IS6, IC13 with one thread per available processor.
 *
 * <p>Usage:
 * <pre>
 * ./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests \
 *   -Djmh.args="LdbcMultiThreadISUltraFast.*"
 * </pre>
 */
@Threads(Threads.MAX)
public class LdbcMultiThreadISUltraFastBenchmark
    extends LdbcISUltraFastBenchmarkBase {

  public static void main(String[] args) throws RunnerException {
    new Runner(
        new OptionsBuilder()
            .include(
                LdbcMultiThreadISUltraFastBenchmark.class.getSimpleName())
            .build())
        .run();
  }
}
