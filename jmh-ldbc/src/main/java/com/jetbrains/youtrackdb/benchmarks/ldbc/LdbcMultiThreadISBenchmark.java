package com.jetbrains.youtrackdb.benchmarks.ldbc;

import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Multi-threaded LDBC SNB noisy IS-tier benchmark.
 * Runs IS2, IS7, and IC8 with one thread per available processor, 10 forks,
 * and extended warmup.
 *
 * <p>Usage:
 * <pre>
 * ./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests \
 *   -Djmh.args="LdbcMultiThreadISBenchmark.*"
 * </pre>
 */
@Threads(Threads.MAX)
public class LdbcMultiThreadISBenchmark extends LdbcISBenchmarkBase {

  public static void main(String[] args) throws RunnerException {
    new Runner(
        new OptionsBuilder()
            .include(LdbcMultiThreadISBenchmark.class.getSimpleName())
            .build())
        .run();
  }
}
