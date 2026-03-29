package com.jetbrains.youtrackdb.benchmarks.ldbc;

import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Single-threaded LDBC SNB noisy IS-tier benchmark.
 * Runs IS2, IS7, and IC8 with a single thread, 10 forks, and extended warmup.
 *
 * <p>Usage:
 * <pre>
 * ./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests \
 *   -Djmh.args="LdbcSingleThreadISBenchmark.*"
 * </pre>
 */
@Threads(1)
public class LdbcSingleThreadISBenchmark extends LdbcISBenchmarkBase {

  public static void main(String[] args) throws RunnerException {
    new Runner(
        new OptionsBuilder()
            .include(LdbcSingleThreadISBenchmark.class.getSimpleName())
            .build())
        .run();
  }
}
